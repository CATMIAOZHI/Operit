package com.ai.assistance.operit.data.backup

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AtomicRestoreMarkerStore 崩溃安全协议测试（纯 JVM）。
 *
 * 验证不变量：正式 marker 文件在任意中断后要么是完整旧 generation，要么是完整
 * 新 generation，绝不截断。覆盖：
 * - 目标已存在时的覆盖写入（原子 move 成功/不支持/失败三种路径）；
 * - old/new/backup 回退协议的各中断窗口（通过直接构造中间文件状态模拟崩溃）；
 * - 读取总是返回完整内容；残留（.new/.bak/.tmp）在读取/写入时被清理。
 */
class AtomicRestoreMarkerStoreTest {

    private fun storeAt(
        dir: File,
        atomicMove: (File, File) -> Boolean = ::realAtomicMove,
    ): Pair<File, AtomicRestoreMarkerStore> {
        val markerFile = File(dir, "marker.txt")
        return markerFile to AtomicRestoreMarkerStore(markerFile, atomicMove)
    }

    /** 与生产默认一致的原子替换：真实 Files.move(ATOMIC_MOVE, REPLACE_EXISTING)。 */
    private fun realAtomicMove(from: File, to: File): Boolean = try {
        java.nio.file.Files.move(
            from.toPath(),
            to.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        true
    } catch (e: Exception) {
        false
    }

    private fun assertFullUuid(content: String?, expected: String? = null) {
        assertNotNull("read must never return null when a marker exists", content)
        if (expected != null) {
            assertEquals(expected, content)
        } else {
            // 完整 UUID：不是半写/截断内容
            assertNotNull("marker must be a complete UUID, got: $content", UUID.fromString(content!!.trim()))
        }
    }

    @Test
    fun `atomic move success overwrites existing target with full generation`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("marker-atomic").toFile()
            val (markerFile, store) = storeAt(dir)
            store.write("generation-old")
            assertFullUuid(markerFile.readText(), "generation-old")

            store.write("generation-new")
            assertFullUuid(markerFile.readText(), "generation-new")
            // 无残留
            assertFalse(File(dir, "marker.txt.new").exists())
            assertFalse(File(dir, "marker.txt.bak").exists())
        }

    @Test
    fun `atomic move unsupported falls back to old new backup protocol without truncation`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("marker-fallback").toFile()
            val (markerFile, store) = storeAt(dir, atomicMove = { _, _ -> false })
            // 首次写入（目标不存在）
            store.write("generation-1")
            assertFullUuid(markerFile.readText(), "generation-1")
            // 目标已存在：回退协议必须完整替换，绝不 copyTo 截断
            store.write("generation-2")
            assertFullUuid(markerFile.readText(), "generation-2")
            assertEquals("generation-2", store.read()?.trim())
            // 回退协议完成：无残留
            assertFalse(File(dir, "marker.txt.new").exists())
            assertFalse(File(dir, "marker.txt.bak").exists())
            assertTrue("stale tmp must be cleaned", dir.listFiles()!!.none { it.name.contains(".tmp") })
        }

    @Test
    fun `atomic move throwing is treated as unsupported and falls back`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("marker-throw").toFile()
            val (markerFile, store) = storeAt(dir, atomicMove = { _, _ -> throw java.io.IOException("no atomic move") })
            store.write("generation-1")
            store.write("generation-2")
            assertFullUuid(markerFile.readText(), "generation-2")
            assertFalse(File(dir, "marker.txt.new").exists())
            assertFalse(File(dir, "marker.txt.bak").exists())
        }

    @Test
    fun `repeated fallback rewrites never truncate the official marker`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("marker-repeat").toFile()
            val (markerFile, store) = storeAt(dir, atomicMove = { _, _ -> false })
            repeat(10) { index ->
                store.write("generation-$index")
                val content = markerFile.readText()
                assertEquals("generation-$index", content)
                assertFalse(File(dir, "marker.txt.new").exists())
                assertFalse(File(dir, "marker.txt.bak").exists())
            }
            assertEquals("generation-9", store.read()?.trim())
        }

    @Test
    fun `interrupted after target moved to backup recovers complete new generation`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("marker-win-b").toFile()
            val (markerFile, store) = storeAt(dir, atomicMove = { _, _ -> false })
            // 模拟崩溃窗口 b：目标已移到 .bak（旧值），.new 是 fsync 后的完整新值
            File(dir, "marker.txt.bak").writeText("generation-old")
            File(dir, "marker.txt.new").writeText("generation-new")
            assertFalse(markerFile.exists())

            // 读取必须恢复完整值（提交点后的语义：新值），绝不返回 null 或半写
            val content = store.read()
            assertFullUuid(content, "generation-new")
            assertTrue("target must be repaired", markerFile.isFile)
            assertFalse("stale backup must be cleaned", File(dir, "marker.txt.bak").exists())
        }

    @Test
    fun `interrupted with only backup restores complete old generation`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("marker-win-c").toFile()
            val (markerFile, store) = storeAt(dir, atomicMove = { _, _ -> false })
            // 模拟中断后仅剩 .bak（旧值完整）
            File(dir, "marker.txt.bak").writeText("generation-old")
            assertFalse(markerFile.exists())

            val content = store.read()
            assertFullUuid(content, "generation-old")
            assertTrue(markerFile.isFile)
        }

    @Test
    fun `interrupted during new file write leaves official marker intact`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("marker-partial").toFile()
            val (markerFile, store) = storeAt(dir)
            markerFile.writeText("generation-old")
            // 模拟写入新内容时崩溃：目标仍是旧值，.tmp 是半写垃圾
            File(dir, "marker.txt.tmpabc").writeText("generatio")
            File(dir, "marker.txt.new").writeText("generatio")

            assertFullUuid(markerFile.readText(), "generation-old")
            // 读取清理半写残留
            val content = store.read()
            assertFullUuid(content, "generation-old")
            assertFalse(File(dir, "marker.txt.new").exists())
            assertTrue("stale tmp must be cleaned on read", dir.listFiles()!!.none { it.name.contains(".tmp") })
        }

    @Test
    fun `write repairs missing target from complete new before proceeding`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("marker-repair").toFile()
            val (markerFile, store) = storeAt(dir, atomicMove = { _, _ -> false })
            // 上次写入崩溃在提交点前：目标缺失，.new 完整、.bak 是旧值
            File(dir, "marker.txt.bak").writeText("generation-old")
            File(dir, "marker.txt.new").writeText("generation-new")
            assertFalse(markerFile.exists())

            // 新写入必须先恢复信号（不得删除 .new/.bak 导致 marker 丢失），再覆盖
            store.write("generation-next")
            assertEquals("generation-next", markerFile.readText())
            assertFalse(File(dir, "marker.txt.new").exists())
            assertFalse(File(dir, "marker.txt.bak").exists())
        }

    @Test
    fun `delete removes marker and all residue`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("marker-delete").toFile()
            val (markerFile, store) = storeAt(dir, atomicMove = { _, _ -> false })
            store.write("generation-1")
            File(dir, "marker.txt.new").writeText("stale")
            File(dir, "marker.txt.bak").writeText("stale")
            File(dir, "marker.txt.tmpx").writeText("stale")

            store.delete()
            assertFalse(markerFile.exists())
            assertFalse(File(dir, "marker.txt.new").exists())
            assertFalse(File(dir, "marker.txt.bak").exists())
            assertTrue(dir.listFiles()!!.none { it.name.contains(".tmp") })
            assertNull(store.read())
        }

    @Test
    fun `no marker at all returns null and tolerates stale tmp`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("marker-none").toFile()
            val (_, store) = storeAt(dir)
            File(dir, "marker.txt.tmpabc").writeText("garbage")
            assertNull(store.read())
            assertFalse(File(dir, "marker.txt.tmpabc").exists())
        }

    // ── P1-3 终审：严格目录项持久模式（TokenStatSpool 的 summary/manifest/ack state 使用）──

    private fun strictStoreAt(
        dir: File,
        strictSync: (File) -> Boolean,
        atomicMove: (File, File) -> Boolean = ::realAtomicMove,
    ): Pair<File, AtomicRestoreMarkerStore> {
        val markerFile = File(dir, "marker.txt")
        return markerFile to AtomicRestoreMarkerStore(markerFile, atomicMove, strictSync)
    }

    @Test
    fun `strict write fails when the commit dir sync is not OK and canonical may already be visible`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("marker-strict-write").toFile()
            var syncCalls = 0
            // 第一次写入的 staging sync 成功，commit（atomic move 提交点）sync 失败
            val (markerFile, store) = strictStoreAt(dir, strictSync = {
                syncCalls += 1
                syncCalls != 2
            })
            try {
                store.write("generation-old")
                fail("strict write must fail when the commit dir sync is not OK")
            } catch (e: java.io.IOException) {
            }
            assertEquals(2, syncCalls)
            // canonical 发布已可见但 sync 失败：内容是完整新值（调用方失败，下次重读幂等）
            assertFullUuid(markerFile.readText(), "generation-old")

            // P1-2 终审：write 提交 move 成功但 sync 失败——canonical 已可见但目录项未确认
            // 持久。持续失败下 read 必须继续失败（绝不信任 canonical），恢复 OK 才返回。
            val (_, failing) = strictStoreAt(dir, strictSync = { false })
            try {
                failing.read()
                fail("strict read must fail while the commit sync failure persists")
            } catch (e: java.io.IOException) {
                assertTrue(e.message!!.contains("durable"))
            }
            try {
                failing.read()
                fail("strict read must keep failing under persistent dir sync failure")
            } catch (e: java.io.IOException) {
                assertTrue(e.message!!.contains("durable"))
            }

            // 恢复严格回调（总是 OK）：重试写入成功
            val (_, recovered) = strictStoreAt(dir, strictSync = { true })
            recovered.write("generation-new")
            assertFullUuid(markerFile.readText(), "generation-new")
            // 幂等：canonical 是完整新值，后续读取不受影响
            assertFullUuid(recovered.read(), "generation-new")
            assertFalse(File(dir, "marker.txt.new").exists())
        }

    @Test
    fun `strict write fails when any directory entry change cannot be confirmed`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("marker-strict-stage").toFile()
            // staging rename（tmp→.new）后的目录项 sync 失败 → 整个 write 抛 IOException
            val (_, store) = strictStoreAt(dir, strictSync = { false })
            try {
                store.write("generation-new")
                fail("strict write must fail when a directory entry change is not confirmed")
            } catch (e: java.io.IOException) {
                assertTrue(e.message!!.contains("durable"))
            }
            // canonical 从未发布：读取仍为 null（无半写/部分状态冒充完整值）
            assertNull(File(dir, "marker.txt").let { if (it.exists()) it.readText() else null })
        }

    @Test
    fun `strict read fails while canonical exists until the dir sync recovers`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("marker-strict-canonical-gate").toFile()
            var syncOk = true
            val (markerFile, store) = strictStoreAt(dir, strictSync = { syncOk })
            store.write("generation-1")
            assertEquals("generation-1", markerFile.readText())
            // P1-2 终审：canonical 可见但目录项未确认持久 → read 必须失败，绝不信任 canonical
            syncOk = false
            try {
                store.read()
                fail("strict read must fail while the dir sync is not OK even with canonical present")
            } catch (e: java.io.IOException) {
                assertTrue(e.message!!.contains("durable"))
            }
            // 持续失败：再次 read 仍然失败（canonical 存在不放行）
            try {
                store.read()
                fail("strict read must keep failing while the dir sync stays not OK")
            } catch (e: java.io.IOException) {
                assertTrue(e.message!!.contains("durable"))
            }
            // 恢复：dir sync OK 后 read 返回完整 canonical
            syncOk = true
            assertFullUuid(store.read(), "generation-1")
            assertFalse(File(dir, "marker.txt.new").exists())
        }

    @Test
    fun `strict read sidecar recovery rename must be confirmed durable`() =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("marker-strict-read").toFile()
            // 崩溃窗口：目标缺失、.new 完整（恢复 rename 是目录项变更）
            File(dir, "marker.txt.new").writeText("generation-new")
            val (markerFile, store) = strictStoreAt(dir, strictSync = { false })
            try {
                store.read()
                fail("strict read recovery must fail when the rename is not confirmed durable")
            } catch (e: java.io.IOException) {
                assertTrue(e.message!!.contains("durable"))
            }
            // 失败后状态保留：恢复 rename 已可见（canonical = 完整新值，绝不截断/半写），
            // 调用方失败——下一轮重读按完整值幂等完成
            assertTrue(markerFile.exists())
            assertEquals("generation-new", markerFile.readText())
            assertFalse(File(dir, "marker.txt.new").exists())
            // 恢复严格回调：同一完整值直接读取成功
            val (_, recovered) = strictStoreAt(dir, strictSync = { true })
            assertFullUuid(recovered.read(), "generation-new")
            assertTrue(markerFile.isFile)
        }
}
