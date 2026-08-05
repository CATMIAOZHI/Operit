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
}
