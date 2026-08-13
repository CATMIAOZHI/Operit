package com.ai.assistance.operit.data.stats

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.api.chat.llmprovider.TokenStatsPersistenceException
import com.ai.assistance.operit.api.chat.llmprovider.TokenTrackingAIService
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.ui.features.settings.screens.QuarantineExportCleanup
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** File + Room tests for the stage-2 durability linearization points. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class QuarantineReliabilityTest : TokenStatReliabilityTestBase() {
    @Test
    fun `summary only evidence can be explicitly acknowledged before snapshot`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        val summary = File(spool, "quarantine_summary.jsonl")
        summary.writeText("{\"count\":1}\n")
        var blockRan = false

        try {
            TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = true) {
                blockRan = true
            }
            fail("snapshot must not silently omit the quarantine summary")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("quarantine evidence"))
        }
        assertFalse(blockRan)
        assertEquals(1, TokenStatSpool.quarantineSummaryInfo(context)!!.recordCount)

        TokenStatSpool.acknowledgeAndDeleteQuarantine(
            context = context,
            names = emptySet(),
            deleteSummary = true,
        )

        assertFalse(summary.exists())
        assertEquals(null, TokenStatSpool.quarantineSummaryInfo(context))
        TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = true) {
            blockRan = true
        }
        assertTrue(blockRan)
    }

    @Test
    fun `two corrupt segments quarantine uniquely and healthy segment drains`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        File(spool, "sealed_1.jsonl").writeText("{bad-one\n")
        File(spool, "sealed_2.jsonl").writeText("{bad-two\n")
        File(spool, "sealed_3.jsonl").writeText(line(request("healthy-after-corrupt")) + "\n")
        Mockito.mockStatic(AppLogger::class.java).use {
            TokenStatSpool.replay(context)
            awaitEvent("healthy-after-corrupt")
        }
        assertEquals(1, database.tokenStatsDao().countEvents())
        val evidence = TokenStatSpool.quarantineEvidence(context)
        assertEquals(2, evidence.size)
        assertEquals(2, evidence.map { it.name }.toSet().size)
    }

    @Test
    fun `quarantine at cap summarizes over-cap segment and keeps within-cap full evidence`() =
        runBlocking {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            val existing = File(spool, "quarantine_existing_sealed_1.jsonl")
            RandomAccessFile(existing, "rw").use { it.setLength(TokenStatSpool.MAX_QUARANTINE_BYTES) }
            File(spool, "sealed_2.jsonl").writeText("{new-bad\n")
            Mockito.mockStatic(AppLogger::class.java).use {
                TokenStatSpool.replay(context)
                awaitNoSealedSegments(spool)
            }
            // 硬边界：容量内完整证据保留；超限损坏段替换为固定大小摘要并移除原段
            assertTrue(existing.exists())
            assertFalse("over-cap corrupt segment must be replaced by its summary", File(spool, "sealed_2.jsonl").exists())
            assertEquals(1, TokenStatSpool.quarantineEvidence(context).size)
            assertTrue(
                "evidence disk usage must stay within the hard cap",
                TokenStatSpool.quarantineEvidence(context).sumOf { it.length() } <= TokenStatSpool.MAX_QUARANTINE_BYTES
            )
            val summary = TokenStatSpool.quarantineSummaryInfo(context)
            assertNotNull("over-cap evidence must be reported as a bounded summary", summary)
            assertEquals(1, summary!!.recordCount)

            // 导出包含摘要文件；确认删除只作用于完整证据（摘要保留为滚动记录）
            val exported = TokenStatSpool.exportQuarantineEvidence(context, File(root, "evidence-export"))
            assertEquals(2, exported.size)
            assertTrue(exported.any { it.name == "quarantine_summary.jsonl" })
            TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf(existing.name))
            assertFalse(existing.exists())
            assertEquals(0, TokenStatSpool.quarantineEvidence(context).size)
            assertEquals(1, TokenStatSpool.quarantineSummaryInfo(context)!!.recordCount)
        }

    @Test
    fun `quarantine hard cap keeps disk bounded far beyond cap and healthy drain continues`() =
        runBlocking {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            val existing = File(spool, "quarantine_existing_sealed_1.jsonl")
            RandomAccessFile(existing, "rw").use { it.setLength(TokenStatSpool.MAX_QUARANTINE_BYTES) }
            repeat(8) { index -> File(spool, "sealed_${index + 2}.jsonl").writeText("{bad-$index\n") }
            File(spool, "sealed_10.jsonl").writeText(line(request("healthy-beyond-cap")) + "\n")
            Mockito.mockStatic(AppLogger::class.java).use {
                TokenStatSpool.replay(context)
                awaitEvent("healthy-beyond-cap")
            }
            // 远超上限时：磁盘占用有界（完整证据不超上限）、摘要累计、健康段照常排空
            assertEquals(1, database.tokenStatsDao().countEvents())
            assertEquals("healthy-beyond-cap", database.tokenStatsDao().getAllEvents().single().eventId)
            val evidence = TokenStatSpool.quarantineEvidence(context)
            assertEquals(1, evidence.size)
            assertTrue(
                "evidence disk usage must stay within the hard cap",
                evidence.sumOf { it.length() } <= TokenStatSpool.MAX_QUARANTINE_BYTES
            )
            val summary = TokenStatSpool.quarantineSummaryInfo(context)
            assertNotNull(summary)
            assertEquals(8, summary!!.recordCount)
            assertTrue(
                "summary must have a fixed upper bound",
                summary.summaryBytes <= TokenStatSpool.MAX_QUARANTINE_SUMMARY_BYTES
            )

            // 导出/删除入口在满容量时可调用，摘要随导出提供
            val exported = TokenStatSpool.exportQuarantineEvidence(context, File(root, "evidence-export"))
            assertEquals(2, exported.size)
            TokenStatSpool.acknowledgeAndDeleteQuarantine(context, evidence.map { it.name }.toSet())
            assertEquals(0, TokenStatSpool.quarantineEvidence(context).size)
        }

    @Test
    fun `quarantine summary is rolling and never contains corrupt content`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        val existing = File(spool, "quarantine_existing_sealed_1.jsonl")
        RandomAccessFile(existing, "rw").use { it.setLength(TokenStatSpool.MAX_QUARANTINE_BYTES) }
        val total = TokenStatSpool.MAX_QUARANTINE_SUMMARY_LINES + 50
        repeat(total) { index -> File(spool, "sealed_${index + 2}.jsonl").writeText("{corrupt-body-$index\n") }
        Mockito.mockStatic(AppLogger::class.java).use {
            TokenStatSpool.replay(context)
            awaitNoSealedSegments(spool)
        }
        val summary = TokenStatSpool.quarantineSummaryInfo(context)
        assertNotNull(summary)
        assertTrue(
            "summary must roll at a fixed line cap: ${summary!!.recordCount}",
            summary.recordCount <= TokenStatSpool.MAX_QUARANTINE_SUMMARY_LINES
        )
        assertTrue(
            "summary must have a fixed byte cap",
            summary.summaryBytes <= TokenStatSpool.MAX_QUARANTINE_SUMMARY_BYTES
        )
        val summaryText = File(spool, "quarantine_summary.jsonl").readText()
        assertTrue("newest records must survive the roll", summaryText.contains("sealed_${total + 1}.jsonl"))
        assertTrue("summary must carry hash, bytes and line counts", summaryText.contains("sha256"))
        assertFalse("summary must never embed corrupt content", summaryText.contains("corrupt-body"))
        assertTrue(existing.exists())
        assertTrue(
            TokenStatSpool.quarantineEvidence(context).sumOf { it.length() } <= TokenStatSpool.MAX_QUARANTINE_BYTES
        )
    }

    @Test
    fun `quarantine summary publish failure keeps old summary and pending segment`() = runBlocking {
        val previousAtomic = TokenStatSpool.quarantineAtomicMoveForTest
        TokenStatSpool.quarantineAtomicMoveForTest = { _, _ -> false }
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        val summaryFile = File(spool, "quarantine_summary.jsonl")
        summaryFile.writeText("{\"old\":\"preserved\"}\n")
        // 让回退提交失败：.bak 位置放一个非空目录，renameTo 无法覆盖（发布失败路径）
        val bakDir = File(spool, "quarantine_summary.jsonl.bak")
        bakDir.mkdirs()
        File(bakDir, "lock").writeText("x")
        val existing = File(spool, "quarantine_existing_sealed_1.jsonl")
        RandomAccessFile(existing, "rw").use { it.setLength(TokenStatSpool.MAX_QUARANTINE_BYTES) }
        val segment = File(spool, "sealed_2.jsonl")
        segment.writeText("{fail-publish-bad\n")
        try {
            Mockito.mockStatic(AppLogger::class.java).use {
                TokenStatSpool.replay(context)
                delay(800)
            }
            // 发布失败：旧摘要保持完整、待处理段保留、错误可见（不声称成功）
            assertEquals("{\"old\":\"preserved\"}\n", summaryFile.readText())
            assertTrue("pending segment must be retained on publish failure", segment.exists())
            assertEquals(1, TokenStatSpool.quarantineSummaryInfo(context)!!.recordCount)
        } finally {
            TokenStatSpool.quarantineAtomicMoveForTest = previousAtomic
            File(bakDir, "lock").delete()
            bakDir.delete()
            File(spool, "quarantine_summary.jsonl.new").delete()
        }
    }

    @Test
    fun `quarantine summary survives interruption at each replacement step`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        val summaryFile = File(spool, "quarantine_summary.jsonl")
        val oldContent = "{\"k\":\"old\"}\n"
        val newContent = "{\"k\":\"new\"}\n"

        // 窗口 A：target 缺失、.new 完整就绪（target→bak 之后、.new→target 之前崩溃）
        summaryFile.writeText(oldContent)
        File(spool, "quarantine_summary.jsonl.new").writeText(newContent)
        assertTrue(summaryFile.delete())
        val infoA = TokenStatSpool.quarantineSummaryInfo(context)
        assertNotNull(infoA)
        val recoveredA = summaryFile.readText().trim()
        assertTrue(
            "interruption must recover complete old or new: $recoveredA",
            recoveredA == oldContent.trim() || recoveredA == newContent.trim(),
        )

        // 窗口 B：target 缺失、.bak=完整旧（bak 已就绪但恢复前崩溃）
        summaryFile.writeText(oldContent)
        File(spool, "quarantine_summary.jsonl.bak").writeText(oldContent)
        assertTrue(summaryFile.delete())
        assertNotNull(TokenStatSpool.quarantineSummaryInfo(context))
        assertEquals(oldContent.trim(), summaryFile.readText().trim())

        // 窗口 C：target=完整新、.bak=残留旧（提交后、清理前崩溃）
        summaryFile.writeText(newContent)
        File(spool, "quarantine_summary.jsonl.bak").writeText(oldContent)
        assertNotNull(TokenStatSpool.quarantineSummaryInfo(context))
        assertEquals(newContent.trim(), summaryFile.readText().trim())
        assertFalse("stale backup must be cleaned after a successful read", File(spool, "quarantine_summary.jsonl.bak").exists())

        // 窗口 D：仅 .tmp 残留（tmp 写入后崩溃）→ target 完整旧
        summaryFile.writeText(oldContent)
        File(spool, "quarantine_summary.jsonl.tmpstale").writeText(newContent)
        assertNotNull(TokenStatSpool.quarantineSummaryInfo(context))
        assertEquals(oldContent.trim(), summaryFile.readText().trim())
    }

    @Test
    fun `quarantine summary byte cap enforced with oversized pre-existing summary`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        // 预置超字节上限但行数很少的旧摘要（旧版本残留/手工膨胀），裁剪必须自愈
        val bigLine = "{\"padding\":\"${"x".repeat(30 * 1024)}\"}\n"
        File(spool, "quarantine_summary.jsonl").writeText(bigLine.repeat(3))
        val existing = File(spool, "quarantine_existing_sealed_1.jsonl")
        RandomAccessFile(existing, "rw").use { it.setLength(TokenStatSpool.MAX_QUARANTINE_BYTES) }
        File(spool, "sealed_2.jsonl").writeText("{byte-cap-bad\n")
        Mockito.mockStatic(AppLogger::class.java).use {
            TokenStatSpool.replay(context)
            awaitNoSealedSegments(spool)
        }
        val summary = TokenStatSpool.quarantineSummaryInfo(context)!!
        assertTrue(
            "summary must shrink below the byte cap: ${summary.summaryBytes}",
            summary.summaryBytes <= TokenStatSpool.MAX_QUARANTINE_SUMMARY_BYTES,
        )
        assertTrue(summary.recordCount <= TokenStatSpool.MAX_QUARANTINE_SUMMARY_LINES)
        assertTrue("newest record must survive the byte roll", File(spool, "quarantine_summary.jsonl").readText().contains("sealed_2.jsonl"))
    }

    @Test
    fun `quarantine summary retry after crash does not duplicate record`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        val existing = File(spool, "quarantine_existing_sealed_1.jsonl")
        RandomAccessFile(existing, "rw").use { it.setLength(TokenStatSpool.MAX_QUARANTINE_BYTES) }
        val body = "{retry-bad\n"
        File(spool, "sealed_2.jsonl").writeText(body)
        // 模拟“上次摘要已发布、段删除前崩溃”：摘要已有同一段（file+sha256）的完整记录
        val sha =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(body.toByteArray(Charsets.UTF_8))
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        File(spool, "quarantine_summary.jsonl").writeText(
            "{\"ts\":1,\"file\":\"sealed_2.jsonl\",\"bytes\":${body.length}," +
                "\"sha256\":\"$sha\",\"lineCount\":1,\"corruptLines\":1}\n",
        )
        Mockito.mockStatic(AppLogger::class.java).use {
            TokenStatSpool.replay(context)
            awaitNoSealedSegments(spool)
        }
        // 崩溃重试幂等：不重复追加记录，段正常处置
        val summary = TokenStatSpool.quarantineSummaryInfo(context)!!
        assertEquals("crash retry must not duplicate the record", 1, summary.recordCount)
        assertTrue(File(spool, "quarantine_summary.jsonl").readText().contains("sealed_2.jsonl"))
    }

    @Test
    fun `within-cap corrupt rename failure is kept as bounded pending-delete evidence and healthy drain continues`() =
        runBlocking {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            File(spool, "sealed_1.jsonl").writeText("{rename-fail-bad\n")
            File(spool, "sealed_2.jsonl").writeText(line(request("healthy-after-pending")) + "\n")
            Mockito.mockStatic(AppLogger::class.java).use {
                // 只让“移入证据区”的重命名失败，pending-delete 重命名放行（容量内预算允许）
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    if (to.name.startsWith("quarantine_") && !to.name.startsWith("quarantine_pending_delete_")) {
                        false
                    } else {
                        null
                    }
                }
                try {
                    TokenStatSpool.replay(context)
                    awaitEvent("healthy-after-pending")
                    awaitNoSealedSegments(spool)
                } finally {
                    TokenStatSpool.segmentRenameForTest = null
                }
            }
            // 健康事件恰一次入库；删除/重命名失败的段已移出 sealed 扫描队列为有界证据
            assertEquals(1, database.tokenStatsDao().countEvents())
            assertEquals("healthy-after-pending", database.tokenStatsDao().getAllEvents().single().eventId)
            val evidence = TokenStatSpool.quarantineEvidence(context)
            val pending = evidence.filter { it.name.startsWith("quarantine_pending_delete_") }
            assertEquals("failed rename must be retained as pending-delete evidence", 1, pending.size)
            assertTrue("full evidence must be preserved", pending.single().readText().contains("rename-fail-bad"))
            assertTrue(
                "error evidence must stay within the hard cap",
                evidence.sumOf { it.length() } <= TokenStatSpool.MAX_QUARANTINE_BYTES,
            )
            assertFalse("no tombstone needed while the pending budget fits", File(spool, "quarantine_skip_manifest.jsonl").exists())

            // 维护/后台重试：恢复重命名能力后，下一次 drain 把 pending 证据移回证据区
            TokenStatSpool.replay(context)
            awaitNoPendingEvidence(spool)
            val restored = TokenStatSpool.quarantineEvidence(context)
            assertTrue(
                "pending-delete evidence must be restored to the evidence area",
                restored.any { it.name.startsWith("quarantine_") && !it.name.startsWith("quarantine_pending_delete_") },
            )
            assertEquals(1, database.tokenStatsDao().countEvents())
        }

    @Test
    fun `over-cap delete failure with full evidence area tombstone the segment and healthy drain continues`() =
        runBlocking {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            val existing = File(spool, "quarantine_existing_sealed_1.jsonl")
            RandomAccessFile(existing, "rw").use { it.setLength(TokenStatSpool.MAX_QUARANTINE_BYTES) }
            File(spool, "sealed_2.jsonl").writeText("{tombstone-bad\n")
            File(spool, "sealed_3.jsonl").writeText(line(request("healthy-after-tombstone")) + "\n")
            Mockito.mockStatic(AppLogger::class.java).use {
                // 注入删除失败：只对 over-cap 损坏段生效（P1-2），健康段删除不受影响
                TokenStatSpool.segmentDeleteForTest = { file ->
                    if (file.name == "sealed_2.jsonl") false else null
                }
                try {
                    TokenStatSpool.replay(context)
                    awaitEvent("healthy-after-tombstone")
                } finally {
                    TokenStatSpool.segmentDeleteForTest = null
                }
            }
            // 后续健康事件恰一次入库；删除失败的 over-cap 段被 tombstone 跳过（摘要已有 hash/bytes）
            assertEquals(1, database.tokenStatsDao().countEvents())
            assertEquals("healthy-after-tombstone", database.tokenStatsDao().getAllEvents().single().eventId)
            assertTrue(
                "tombstoned segment must be recorded in the bounded manifest",
                File(spool, "quarantine_skip_manifest.jsonl").readText().contains("sealed_2.jsonl"),
            )
            assertEquals(1, TokenStatSpool.quarantineSummaryInfo(context)!!.recordCount)
            val evidence = TokenStatSpool.quarantineEvidence(context)
            assertTrue(
                "quarantine evidence area must stay within the hard cap (managed set separately bounded)",
                evidence.filter { it.name.startsWith("quarantine_") }.sumOf { it.length() } <= TokenStatSpool.MAX_QUARANTINE_BYTES,
            )
            // P1-3：tombstoned 原 sealed 作为 managed evidence 可见（参与 UI 计数/导出/删除）
            assertTrue(
                "tombstoned original sealed must appear as managed evidence",
                evidence.any { it.name == "sealed_2.jsonl" },
            )
            assertTrue(
                TokenStatSpool.quarantineEvidence(context).none { it.name.startsWith("quarantine_pending_delete_") },
            )

            // 维护/后台重试：恢复删除能力后，下一次 drain 删除 tombstoned 段并移除记录
            TokenStatSpool.replay(context)
            awaitSegmentGone(spool, "sealed_2.jsonl")
            awaitManifestWithout(spool, "sealed_2.jsonl")
            assertEquals(1, database.tokenStatsDao().countEvents())
            assertEquals(1, TokenStatSpool.quarantineSummaryInfo(context)!!.recordCount)
        }

    @Test
    fun `permanent dispose failures fill the managed set bounded then refuse appends and recover`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                // P1-1：删除与重命名永久失败（只针对 sealed 段：损坏处置、维护移回全部失败）
                TokenStatSpool.segmentDeleteForTest = { file ->
                    if (file.name.startsWith("sealed_")) false else null
                }
                TokenStatSpool.segmentRenameForTest = { from, _ ->
                    if (from.name.startsWith("sealed_")) false else null
                }
                try {
                    // 超过受管集合上限的损坏段：受管集合封顶，剩余段有界跳过
                    repeat(TokenStatSpool.MAX_TOMBSTONE_ENTRIES + 5) { index ->
                        File(spool, "sealed_${index + 1}.jsonl").writeText("{permanent-fail-$index\n")
                    }
                    val drainStart = System.nanoTime()
                    TokenStatSpool.replay(context)
                    val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                    val manifestCount: () -> Int = {
                        safeManifestText(manifest)
                            ?.lineSequence()?.filter { it.isNotBlank() }?.count() ?: 0
                    }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < deadline &&
                        manifestCount() != TokenStatSpool.MAX_TOMBSTONE_ENTRIES
                    ) {
                        delay(20)
                    }
                    val drainMs = (System.nanoTime() - drainStart) / 1_000_000
                    assertTrue("drain must return bounded: ${drainMs}ms", drainMs < 10_000)
                    delay(500)
                    val entryCount = manifestCount()
                    assertEquals(
                        "managed set must cap at the hard limit, never roll identities away",
                        TokenStatSpool.MAX_TOMBSTONE_ENTRIES,
                        entryCount,
                    )
                    // 总占用有界：受管段（≤4MiB/段 × 上限）+ manifest（≤64KiB）+ 证据区
                    val totalBytes = spool.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    assertTrue(
                        "total spool usage must be bounded: $totalBytes",
                        totalBytes <= TokenStatSpool.MAX_TOMBSTONE_ENTRIES * (TokenStatSpool.MAX_SEGMENT_BYTES + 4096) +
                            TokenStatSpool.MAX_QUARANTINE_SUMMARY_BYTES + 1_048_576,
                    )

                    // 超限新业务明确失败且无伪 durable
                    try {
                        TokenStatSpool.append(context, line(request("refused-after-cap")), "refused-after-cap")
                        fail("append beyond managed capacity must throw TokenStatsPersistenceException")
                    } catch (e: TokenStatsPersistenceException) {
                    }
                    assertEquals(0, database.tokenStatsDao().countEvents())

                    // snapshot barrier 有界失败（未受管段仍在队列），绝不死锁
                    val snapStart = System.nanoTime()
                    try {
                        TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = true) { }
                        fail("snapshot must not claim drained while unmanageable segments remain")
                    } catch (e: IOException) {
                        assertTrue(e.message!!.contains("pending events"))
                    }
                    val snapMs = (System.nanoTime() - snapStart) / 1_000_000
                    assertTrue("snapshot must be bounded: ${snapMs}ms", snapMs < 10_000)

                    // 恢复文件系统：maintenance 清理受管段与陈旧条目 → 容量释放 → 新业务可继续
                    TokenStatSpool.segmentDeleteForTest = null
                    TokenStatSpool.segmentRenameForTest = null
                    TokenStatSpool.replay(context)
                    awaitNoSealedSegments(spool)
                    awaitManifestWithout(spool, "sealed_")
                    TokenTrackingAIService.recordSafely(context, request("after-managed-recovery"))
                    awaitEvent("after-managed-recovery")
                    assertEquals(1, database.tokenStatsDao().countEvents())
                    assertEquals(
                        "after-managed-recovery",
                        database.tokenStatsDao().getAllEvents().single().eventId,
                    )
                } finally {
                    TokenStatSpool.segmentDeleteForTest = null
                    TokenStatSpool.segmentRenameForTest = null
                }
            }
        }

    @Test
    fun `stale tombstone identity never deletes or skips a reused-name healthy segment`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            // 旧损坏段 sealed_1 曾因处置失败被 tombstone（身份 = 旧内容 hash）
            val oldBody = "{old-corrupt\n"
            File(spool, "sealed_1.jsonl").writeText(oldBody)
            // 崩溃窗口：旧文件被外部删除，manifest 尚未更新
            assertTrue(File(spool, "sealed_1.jsonl").delete())
            File(spool, "quarantine_skip_manifest.jsonl").writeText(
                "{\"ts\":1,\"file\":\"sealed_1.jsonl\",\"bytes\":${oldBody.toByteArray(Charsets.UTF_8).size}," +
                    "\"sha256\":\"${sha256Hex(oldBody.toByteArray(Charsets.UTF_8))}\",\"overCap\":false}\n",
            )
            // 新健康段复用同名（不同 hash）
            File(spool, "sealed_1.jsonl").writeText(line(request("reused-name-healthy")) + "\n")
            TokenStatSpool.replay(context)
            awaitEvent("reused-name-healthy")
            awaitSegmentGone(spool, "sealed_1.jsonl")
            // P1-2：健康段恰一次真实入库，绝不被 tombstone 跳过或删除
            assertEquals(1, database.tokenStatsDao().countEvents())
            assertEquals("reused-name-healthy", database.tokenStatsDao().getAllEvents().single().eventId)
            // 陈旧记录被移除
            val manifest = File(spool, "quarantine_skip_manifest.jsonl")
            assertFalse(
                "stale tombstone must be removed",
                manifest.isFile && manifest.readText().contains("sealed_1.jsonl"),
            )
        }
    }

    @Test
    fun `quarantine summary byte cap counts UTF-8 bytes for non-ASCII lines`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            // P2-1：预置摘要的 UTF-16 长度低于 64KiB，但 UTF-8 字节超上限（每字符 3 字节）
            val chineseLine = "{\"padding\":\"${"统".repeat(22 * 1024)}\"}\n"
            File(spool, "quarantine_summary.jsonl").writeText(chineseLine)
            val existing = File(spool, "quarantine_existing_sealed_1.jsonl")
            RandomAccessFile(existing, "rw").use { it.setLength(TokenStatSpool.MAX_QUARANTINE_BYTES) }
            File(spool, "sealed_2.jsonl").writeText("{utf8-cap-bad\n")
            TokenStatSpool.replay(context)
            awaitNoSealedSegments(spool)
            val summary = TokenStatSpool.quarantineSummaryInfo(context)!!
            assertTrue(
                "summary UTF-8 bytes must respect the cap: ${summary.summaryBytes}",
                summary.summaryBytes <= TokenStatSpool.MAX_QUARANTINE_SUMMARY_BYTES,
            )
            val text = File(spool, "quarantine_summary.jsonl").readText()
            assertTrue("newest record must survive the roll", text.contains("sealed_2.jsonl"))
            assertTrue(
                "oversized non-ASCII line must be replaced by a fixed ASCII truncated record",
                text.contains("\"truncated\":true"),
            )
            assertFalse("truncated record must never embed content", text.contains("统"))
        }
    }

    @Test
    fun `append capacity check recovers a full managed set from backup sidecar`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            // 崩溃窗口：canonical 缺失，只有 .bak（完整旧值）——受管集合已满
            val content = (1..TokenStatSpool.MAX_TOMBSTONE_ENTRIES).joinToString("\n") { index ->
                "{\"ts\":1,\"file\":\"sealed_$index.jsonl\",\"bytes\":3," +
                    "\"sha256\":\"${sha256Hex("x$index".toByteArray(Charsets.UTF_8))}\",\"overCap\":true}"
            } + "\n"
            File(spool, "quarantine_skip_manifest.jsonl.bak").writeText(content)
            try {
                // append 容量检查必须看到恢复后的满受管集合：明确拒绝且不发布新文件
                TokenStatSpool.append(context, line(request("refused-bak-recovery")), "refused-bak-recovery")
                fail("append must fail when the recovered managed set is full")
            } catch (e: TokenStatsPersistenceException) {
            }
            assertEquals(0, database.tokenStatsDao().countEvents())
            // canonical 已恢复且 .bak 身份被清理
            val manifest = File(spool, "quarantine_skip_manifest.jsonl")
            assertEquals(
                TokenStatSpool.MAX_TOMBSTONE_ENTRIES,
                manifest.readText().lineSequence().filter { it.isNotBlank() }.count(),
            )
            assertFalse(File(spool, "quarantine_skip_manifest.jsonl.bak").exists())
        }
    }

    @Test
    fun `seal copy partial target isolation with not durable dir sync writes tombstone evidence and fails closed`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("dispose-sync-a"))
                val lineB = line(request("dispose-sync-b"))
                val partial = lineA + "\n"
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n" + lineB + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    TokenStatSpool.sealHardLinkForTest = { _, _ -> false }
                    TokenStatSpool.sealCopyForTest = { _, target ->
                        target.writeText(partial)
                        false
                    }
                    // gate(2) OK；隔离 rename 后的目录项 sync（第 3 次）失败 → dispose 返回
                    // 失败并写 tombstone（候选 sealed 身份受管证据），绝不只留日志（P2）
                    var calls = 0
                    TokenStatSpool.dirSyncForTest = {
                        calls += 1
                        if (calls != 3) TokenStatSpool.DirSyncResult.OK else TokenStatSpool.DirSyncResult.FAILED
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        assertFalse(
                            "append must fail when the partial target disposal is not durable",
                            TokenStatSpool.append(context, line(request("dispose-sync-c")), "dispose-sync-c"),
                        )
                    } finally {
                        TokenStatSpool.sealCopyForTest = null
                        TokenStatSpool.sealHardLinkForTest = null
                    }
                    // 隔离文件本身是受管证据（seal_failed_*，可见/导出/ack/维护），tombstone
                    // 按候选 sealed 身份记录：崩溃后 sealed_1 以同内容重现时 scanner 跳过
                    val isolated = spool.listFiles().orEmpty().single { it.name.startsWith("seal_failed_") }
                    assertEquals("partial bytes must be preserved as isolated evidence", partial, isolated.readText())
                    val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                    assertTrue(
                        "candidate identity must be tombstoned so a reappeared sealed_1 is skipped",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                    )
                    assertEquals(
                        "active must be retained",
                        lineA + "\n" + lineB + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    // 恢复：维护清理隔离副本，健康内容各恰一次入 Room（tombstone 条目随文件
                    // 消失确认后移除）
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    TokenStatSpool.replay(context)
                    awaitEvent("dispose-sync-a")
                    awaitEvent("dispose-sync-b")
                    awaitSegmentGone(spool, isolated.name)
                    awaitNoSealedSegments(spool)
                    assertEquals(2, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.sealCopyForTest = null
                    TokenStatSpool.sealHardLinkForTest = null
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `seal copy partial target deletion with not durable dir sync writes tombstone evidence and fails closed`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("dispose-del-a"))
                val lineB = line(request("dispose-del-b"))
                val partial = lineA + "\n"
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n" + lineB + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    TokenStatSpool.sealHardLinkForTest = { _, _ -> false }
                    TokenStatSpool.sealCopyForTest = { _, target ->
                        target.writeText(partial)
                        false
                    }
                    // 隔离 rename 失败 → 走安全删除；删除后的目录项 sync（第 3 次）失败 →
                    // 按候选 sealed 身份写 tombstone 并返回失败（P2：绝不只留日志）
                    TokenStatSpool.segmentRenameForTest = { from, _ ->
                        if (from.name.startsWith("sealed_")) false else null
                    }
                    var calls = 0
                    TokenStatSpool.dirSyncForTest = {
                        calls += 1
                        if (calls != 3) TokenStatSpool.DirSyncResult.OK else TokenStatSpool.DirSyncResult.FAILED
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        assertFalse(
                            "append must fail when the partial target deletion is not durable",
                            TokenStatSpool.append(context, line(request("dispose-del-c")), "dispose-del-c"),
                        )
                    } finally {
                        TokenStatSpool.sealCopyForTest = null
                        TokenStatSpool.sealHardLinkForTest = null
                        TokenStatSpool.segmentRenameForTest = null
                    }
                    // 删除可见但未确认：候选名字不再存在，tombstone 记录其稳定身份（崩溃后
                    // 以同内容重现时 scanner 跳过，绝不普通排空）
                    assertFalse("partial target deletion is visible", File(spool, "sealed_1.jsonl").exists())
                    val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                    assertTrue(
                        "candidate identity must be tombstoned for the crash-reappearance window",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                    )
                    assertEquals(
                        "active must be retained",
                        lineA + "\n" + lineB + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    // 恢复：tombstone 条目随文件消失确认后移除，健康内容各恰一次入 Room
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    TokenStatSpool.replay(context)
                    awaitEvent("dispose-del-a")
                    awaitEvent("dispose-del-b")
                    awaitManifestWithout(spool, "sealed_1.jsonl")
                    awaitNoSealedSegments(spool)
                    assertEquals(2, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.sealCopyForTest = null
                    TokenStatSpool.sealHardLinkForTest = null
                    TokenStatSpool.segmentRenameForTest = null
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `over-cap dispose delete with FAILED and UNSUPPORTED dir sync keeps summary retryable and recovers without duplicate`() =
        runBlocking {
            suspend fun scenario(result: TokenStatSpool.DirSyncResult, tag: String) {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val existing = File(spool, "quarantine_existing_$tag.jsonl")
                RandomAccessFile(existing, "rw").use { it.setLength(TokenStatSpool.MAX_QUARANTINE_BYTES) }
                File(spool, "sealed_2.jsonl").writeText("{$tag-overcap-bad\n")
                // bootstrap gate(2) + 摘要严格发布(2) 成功，删除后目录项 sync（第 5 次）失败
                var calls = 0
                TokenStatSpool.dirSyncForTest = {
                    calls += 1
                    if (calls <= 4) TokenStatSpool.DirSyncResult.OK else result
                }
                TokenStatSpool.replay(context)
                awaitSummaryPublishedAndSegmentGone(spool, "sealed_2.jsonl")
                // 摘要已发布（可见）、段已删除（可见）但目录项未确认：本轮不得声称完成——
                // 无事件入 Room；恢复后摘要不重复。目录 sync 未恢复前严格读取不信任 canonical
                // （P1-2），此处直接断言摘要文件可见。
                assertTrue(
                    "summary canonical must be published and visible",
                    File(spool, "quarantine_summary.jsonl").isFile,
                )
                assertFalse("over-cap segment deletion is visible", File(spool, "sealed_2.jsonl").exists())
                assertEquals(0, database.tokenStatsDao().countEvents())
                // 恢复：目录项 sync OK 后幂等完成（摘要记录不重复、无遗留队列）
                TokenStatSpool.shutdownWriterForTest()
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                TokenStatSpool.replay(context)
                awaitNoSealedSegments(spool)
                assertEquals(1, TokenStatSpool.quarantineSummaryInfo(context)!!.recordCount)
                assertEquals(0, database.tokenStatsDao().countEvents())
            }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.FAILED, "fail") { r, t -> scenario(r, t) }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.UNSUPPORTED, "unsupported") { r, t -> scenario(r, t) }
        }

}
