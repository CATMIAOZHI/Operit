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
internal class CleanupReliabilityTest : TokenStatReliabilityTestBase() {
    @Test
    fun `quarantine export and delete file work never runs on the caller main thread`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        // 超过 16MiB 的证据：满上限 + 额外段（复制/fsync 足够大，能卡住 Main）
        RandomAccessFile(File(spool, "quarantine_existing_sealed_1.jsonl"), "rw").use {
            it.setLength(TokenStatSpool.MAX_QUARANTINE_BYTES)
        }
        File(spool, "quarantine_existing_sealed_2.jsonl").writeText("legacy-over-cap\n")

        val mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main-thread") }
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
        val ioThreads = ConcurrentHashMap.newKeySet<String>()
        val previousIo = TokenStatSpool.ioDispatcher
        TokenStatSpool.ioDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Dispatchers.IO.dispatch(context) {
                    ioThreads += Thread.currentThread().name
                    block.run()
                }
            }
        }
        try {
            withContext(Dispatchers.Main) {
                val exported =
                    TokenStatSpool.exportQuarantineEvidence(context, File(root, "evidence-export"))
                assertTrue(exported.size >= 2)
                TokenStatSpool.acknowledgeAndDeleteQuarantine(context, exported.map { it.name }.toSet())
            }
            assertTrue("file I/O must actually dispatch", ioThreads.isNotEmpty())
            assertFalse(
                "evidence file I/O must never run on the main thread: $ioThreads",
                ioThreads.any { it == "test-main-thread" }
            )
            assertEquals(0, TokenStatSpool.quarantineEvidence(context).size)
        } finally {
            TokenStatSpool.ioDispatcher = previousIo
            Dispatchers.resetMain()
            mainExecutor.shutdown()
        }
    }

    @Test
    fun `quarantine summary publishes atomically via fallback when atomic move unsupported`() =
        runBlocking {
            val previous = TokenStatSpool.quarantineAtomicMoveForTest
            // 强制 ATOMIC_MOVE 不支持（P1-1）：必须走 old/new/backup 回退且结果完整
            TokenStatSpool.quarantineAtomicMoveForTest = { _, _ -> false }
            try {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val existing = File(spool, "quarantine_existing_sealed_1.jsonl")
                RandomAccessFile(existing, "rw").use { it.setLength(TokenStatSpool.MAX_QUARANTINE_BYTES) }
                // 目标已存在（旧摘要）时回退协议必须保留旧值直到新值就绪
                File(spool, "quarantine_summary.jsonl").writeText("{\"old\":\"value\"}\n")
                File(spool, "sealed_2.jsonl").writeText("{forced-fallback-bad\n")
                Mockito.mockStatic(AppLogger::class.java).use {
                    TokenStatSpool.replay(context)
                    awaitNoSealedSegments(spool)
                }
                val summary = TokenStatSpool.quarantineSummaryInfo(context)!!
                assertEquals(2, summary.recordCount)
                val text = File(spool, "quarantine_summary.jsonl").readText()
                assertTrue("newest record must survive the fallback publish", text.contains("sealed_2.jsonl"))
                assertTrue("old record must be preserved in the rebuilt summary", text.contains("\"old\":\"value\""))
                assertTrue(text.contains("sha256"))
                assertFalse("fallback must not leave staged sidecars", File(spool, "quarantine_summary.jsonl.new").exists())
                assertFalse("fallback must not leave backup sidecars", File(spool, "quarantine_summary.jsonl.bak").exists())
            } finally {
                TokenStatSpool.quarantineAtomicMoveForTest = previous
            }
        }

    @Test
    fun `twice-rename-failure original sealed is managed evidence exportable and ack-deleted`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                File(spool, "sealed_1.jsonl").writeText("{twice-rename-bad\n")
                File(spool, "sealed_2.jsonl").writeText(line(request("healthy-after-evidence")) + "\n")
                // P1-3：两次重命名都失败（进证据区 + pending-delete 都失败）→ tombstone 原段
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    if (to.name.startsWith("quarantine_")) false else null
                }
                try {
                    TokenStatSpool.replay(context)
                    awaitEvent("healthy-after-evidence")
                    // 等 drain 完成损坏段处置：tombstone 记录落盘（原段保留在磁盘上，
                    // 不能等它消失——受管失败段本就不消失）
                    val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < deadline &&
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") != true
                    ) {
                        delay(20)
                    }
                    assertTrue(
                        "tombstone must be recorded for the twice-rename-failed segment",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                    )
                } finally {
                    TokenStatSpool.segmentRenameForTest = null
                }
                assertEquals(1, database.tokenStatsDao().countEvents())
                // tombstoned 原 sealed 必须作为 managed evidence 参与计数/字节
                val evidence = TokenStatSpool.quarantineEvidence(context)
                assertTrue(
                    "original sealed must appear as managed evidence",
                    evidence.any { it.name == "sealed_1.jsonl" },
                )
                // 导出包含原文件（原文件名，身份可追溯）并附 manifest
                val exported = TokenStatSpool.exportQuarantineEvidence(context, File(root, "evidence-export"))
                assertTrue(exported.any { it.name == "sealed_1.jsonl" })
                assertTrue(exported.any { it.name == "quarantine_skip_manifest.jsonl" })
                assertTrue(
                    "exported managed evidence must retain the corrupt content",
                    exported.single { it.name == "sealed_1.jsonl" }.readText().contains("twice-rename-bad"),
                )
                // ack 确认删除：按 identity 删除原文件并移除对应 manifest 记录
                TokenStatSpool.acknowledgeAndDeleteQuarantine(context, evidence.map { it.name }.toSet())
                assertFalse("acked managed evidence must be deleted", File(spool, "sealed_1.jsonl").exists())
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                assertFalse(
                    "manifest entry must be removed after ack",
                    manifest.isFile && manifest.readText().contains("sealed_1.jsonl"),
                )
                assertTrue(TokenStatSpool.quarantineEvidence(context).isEmpty())
                // 健康继续
                TokenTrackingAIService.recordSafely(context, request("after-evidence-ack"))
                awaitEvent("after-evidence-ack")
                assertEquals(2, database.tokenStatsDao().countEvents())
            }
        }

    @Test
    fun `export recovers canonical summary and manifest when only sidecars remain`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            // P2-2：崩溃窗口——canonical 缺失，内容只在 .new sidecar（完整、已 fsync）
            val summaryContent = "{\"ts\":1,\"file\":\"sealed_9.jsonl\",\"bytes\":1,\"sha256\":\"abc\"}\n"
            File(spool, "quarantine_summary.jsonl.new").writeText(summaryContent)
            val manifestContent =
                "{\"ts\":1,\"file\":\"sealed_1.jsonl\",\"bytes\":2,\"sha256\":\"def\",\"overCap\":true}\n"
            File(spool, "quarantine_skip_manifest.jsonl.new").writeText(manifestContent)
            val exported = TokenStatSpool.exportQuarantineEvidence(context, File(root, "evidence-export"))
            assertEquals(
                summaryContent,
                exported.single { it.name == "quarantine_summary.jsonl" }.readText(),
            )
            assertEquals(
                manifestContent,
                exported.single { it.name == "quarantine_skip_manifest.jsonl" }.readText(),
            )
            // canonical 也已被恢复，后续信息/ack 不再依赖 sidecar
            assertEquals(summaryContent, File(spool, "quarantine_summary.jsonl").readText())
            assertEquals(manifestContent, File(spool, "quarantine_skip_manifest.jsonl").readText())
        }
    }

    @Test
    fun `evidence info and ack recover manifest and summary from new sidecar without export`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val oldBody = "{sidecar-new-only\n"
                File(spool, "sealed_1.jsonl").writeText(oldBody)
                val sha = sha256Hex(oldBody.toByteArray(Charsets.UTF_8))
                val manifestContent =
                    "{\"ts\":1,\"file\":\"sealed_1.jsonl\",\"bytes\":${oldBody.toByteArray(Charsets.UTF_8).size}," +
                        "\"sha256\":\"$sha\",\"overCap\":false}\n"
                // 崩溃窗口：canonical 缺失，内容只在 .new（完整、已 fsync）
                File(spool, "quarantine_skip_manifest.jsonl.new").writeText(manifestContent)
                val summaryContent = "{\"ts\":1,\"file\":\"sealed_9.jsonl\",\"bytes\":1,\"sha256\":\"abc\"}\n"
                File(spool, "quarantine_summary.jsonl.new").writeText(summaryContent)

                // 不先 export：直接调用 evidence/info/ack
                val evidence = TokenStatSpool.quarantineEvidence(context)
                assertTrue(
                    "managed evidence must be visible after sidecar recovery",
                    evidence.any { it.name == "sealed_1.jsonl" },
                )
                val info = TokenStatSpool.quarantineSummaryInfo(context)
                assertNotNull("summary info must recover from sidecar", info)
                assertEquals(1, info!!.recordCount)
                // canonical 已恢复且 sidecar 身份被清理
                assertEquals(manifestContent, File(spool, "quarantine_skip_manifest.jsonl").readText())
                assertEquals(summaryContent, File(spool, "quarantine_summary.jsonl").readText())
                assertFalse(File(spool, "quarantine_skip_manifest.jsonl.new").exists())
                assertFalse(File(spool, "quarantine_summary.jsonl.new").exists())
                // ack 按身份删除
                TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf("sealed_1.jsonl"))
                assertFalse(File(spool, "sealed_1.jsonl").exists())
                assertFalse(
                    "manifest entry must be removed after ack",
                    File(spool, "quarantine_skip_manifest.jsonl").readText().contains("sealed_1.jsonl"),
                )
            }
        }

    @Test
    fun `each export uses its own empty directory and stale exports never leak`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        File(spool, "quarantine_first_sealed_1.jsonl").writeText("first-bad\n")
        val exportA = File(root, "token_stats_quarantine_A")
        val exportedA = TokenStatSpool.exportQuarantineEvidence(context, exportA)
        assertTrue(exportedA.any { it.name.startsWith("quarantine_first_") })
        TokenStatSpool.acknowledgeAndDeleteQuarantine(context, exportedA.map { it.name }.toSet())
        // 第二次导出到新目录：只含本次证据，上一次的残留绝不混入/冒充
        File(spool, "quarantine_second_sealed_2.jsonl").writeText("second-bad\n")
        val exportB = File(root, "token_stats_quarantine_B")
        val exportedB = TokenStatSpool.exportQuarantineEvidence(context, exportB)
        assertTrue(exportedB.any { it.name.startsWith("quarantine_second_") })
        assertFalse(
            "a previous export must never leak into the new export directory",
            exportB.listFiles().orEmpty().any { it.name.startsWith("quarantine_first_") },
        )
        assertFalse(
            "previous export must never be reported as this run's result",
            exportedB.any { it.name.startsWith("quarantine_first_") },
        )
    }

    @Test
    fun `export into a non-empty destination is refused without touching its content`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        File(spool, "quarantine_x_sealed_1.jsonl").writeText("bad\n")
        val dest = File(root, "token_stats_quarantine_existing").apply { mkdirs() }
        val userFile = File(dest, "user-notes.txt").apply { writeText("do not touch") }
        try {
            TokenStatSpool.exportQuarantineEvidence(context, dest)
            fail("export into a non-empty destination must be refused")
        } catch (e: IOException) {
            assertTrue("refusal must name the reason", e.message!!.contains("not empty"))
        }
        assertEquals("do not touch", userFile.readText())
        assertFalse(
            "no evidence may be written into a refused destination",
            dest.listFiles().orEmpty().any { it.name.startsWith("quarantine_") },
        )
    }

    @Test
    fun `segment read failure keeps managed entries and ack refuses until identity is readable`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                File(spool, "sealed_1.jsonl").writeText("{unreadable-bad\n")
                // 正常建立受管失败段（重命名失败 → tombstone）
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    if (to.name.startsWith("quarantine_")) false else null
                }
                try {
                    TokenStatSpool.replay(context)
                    val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < deadline &&
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") != true
                    ) {
                        delay(20)
                    }
                    assertTrue(safeManifestText(manifest)?.contains("sealed_1.jsonl") == true)

                    // 段原始字节读取失败（身份校验 UNREADABLE）：受管段不处理、entry 保留
                    TokenStatSpool.segmentReadErrorForTest = { file -> file.name == "sealed_1.jsonl" }
                    TokenStatSpool.replay(context)
                    delay(700)
                    assertTrue(
                        "unreadable managed segment must be skipped, never processed",
                        File(spool, "sealed_1.jsonl").exists(),
                    )
                    assertTrue(
                        "manifest entry must be retained for the unreadable segment",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                    )
                    // evidence 列表不暴露身份不可校验的受管段（不可安全导出/ack）
                    assertTrue(
                        TokenStatSpool.quarantineEvidence(context).none { it.name == "sealed_1.jsonl" },
                    )
                    // ack 不能成功
                    try {
                        TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf("sealed_1.jsonl"))
                        fail("ack must fail while the segment identity is unreadable")
                    } catch (e: IOException) {
                        assertTrue("ack must name the unverifiable identity", e.message!!.contains("identity"))
                    }
                    assertTrue(File(spool, "sealed_1.jsonl").exists())
                    assertTrue(
                        "entry must survive a failed ack",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                    )

                    // 恢复后正常：ack 按身份删除并移除记录
                    TokenStatSpool.segmentReadErrorForTest = null
                    TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf("sealed_1.jsonl"))
                    assertFalse(File(spool, "sealed_1.jsonl").exists())
                    assertFalse(
                        "entry must be removed after a successful ack",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                    )
                } finally {
                    TokenStatSpool.segmentReadErrorForTest = null
                    TokenStatSpool.segmentRenameForTest = null
                }
            }
        }

    @Test
    fun `ack manifest read failure preserves quarantine evidence managed evidence and manifest`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                // quarantine area 完整证据（无 manifest 记录）+ 受管失败段（重命名失败 → tombstone）
                File(spool, "quarantine_area_sealed_1.jsonl").writeText("area-bad\n")
                File(spool, "sealed_2.jsonl").writeText("{managed-bad\n")
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    if (to.name.startsWith("quarantine_")) false else null
                }
                try {
                    TokenStatSpool.replay(context)
                    val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < deadline &&
                        safeManifestText(manifest)?.contains("sealed_2.jsonl") != true
                    ) {
                        delay(20)
                    }
                    assertTrue(safeManifestText(manifest)?.contains("sealed_2.jsonl") == true)

                    // manifest 不可读 → 整个 ack 失败：quarantine + managed + manifest 全部保留
                    TokenStatSpool.metadataReadErrorForTest = { file ->
                        file.name == "quarantine_skip_manifest.jsonl"
                    }
                    try {
                        TokenStatSpool.acknowledgeAndDeleteQuarantine(
                            context,
                            setOf("quarantine_area_sealed_1.jsonl", "sealed_2.jsonl"),
                        )
                        fail("ack must fail while the manifest is unreadable")
                    } catch (e: IOException) {
                    }
                    assertTrue(
                        "quarantine evidence must survive a failed ack",
                        File(spool, "quarantine_area_sealed_1.jsonl").exists(),
                    )
                    assertTrue(
                        "managed evidence must survive a failed ack",
                        File(spool, "sealed_2.jsonl").exists(),
                    )
                    assertTrue(
                        "manifest entry must survive a failed ack",
                        safeManifestText(manifest)?.contains("sealed_2.jsonl") == true,
                    )
                    assertTrue(
                        "no ack trash directory may be left behind",
                        spool.listFiles().orEmpty().none { it.name.startsWith("quarantine_ack_trash_") },
                    )
                } finally {
                    TokenStatSpool.metadataReadErrorForTest = null
                    TokenStatSpool.segmentRenameForTest = null
                }
            }
        }

    @Test
    fun `ack with later unreadable managed identity keeps earlier match and all entries`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                File(spool, "sealed_1.jsonl").writeText("{first-bad\n")
                File(spool, "sealed_2.jsonl").writeText("{second-bad\n")
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    if (to.name.startsWith("quarantine_")) false else null
                }
                try {
                    TokenStatSpool.replay(context)
                    val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < deadline &&
                        (safeManifestText(manifest)?.contains("sealed_1.jsonl") != true ||
                            safeManifestText(manifest)?.contains("sealed_2.jsonl") != true)
                    ) {
                        delay(20)
                    }
                    assertTrue(safeManifestText(manifest)?.contains("sealed_1.jsonl") == true)
                    assertTrue(safeManifestText(manifest)?.contains("sealed_2.jsonl") == true)

                    // 后一个段身份不可校验（UNREADABLE）→ 整个 ack 失败：前一个 MATCH 也不删除
                    TokenStatSpool.segmentReadErrorForTest = { file -> file.name == "sealed_2.jsonl" }
                    try {
                        TokenStatSpool.acknowledgeAndDeleteQuarantine(
                            context,
                            setOf("sealed_1.jsonl", "sealed_2.jsonl"),
                        )
                        fail("ack must fail when any managed identity is unreadable")
                    } catch (e: IOException) {
                        assertTrue("ack must name the unverifiable identity", e.message!!.contains("identity"))
                    }
                    assertTrue(
                        "earlier matched segment must not be deleted on a partial failure",
                        File(spool, "sealed_1.jsonl").exists(),
                    )
                    assertTrue(File(spool, "sealed_2.jsonl").exists())
                    assertTrue(
                        "both entries must survive the failed ack",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true &&
                            safeManifestText(manifest)?.contains("sealed_2.jsonl") == true,
                    )

                    // 恢复后一次 ack 按身份删除两个段并移除两条记录
                    TokenStatSpool.segmentReadErrorForTest = null
                    TokenStatSpool.acknowledgeAndDeleteQuarantine(
                        context,
                        setOf("sealed_1.jsonl", "sealed_2.jsonl"),
                    )
                    assertFalse(File(spool, "sealed_1.jsonl").exists())
                    assertFalse(File(spool, "sealed_2.jsonl").exists())
                    assertFalse(safeManifestText(manifest)?.contains("sealed_1.jsonl") == true)
                    assertFalse(safeManifestText(manifest)?.contains("sealed_2.jsonl") == true)
                } finally {
                    TokenStatSpool.segmentReadErrorForTest = null
                    TokenStatSpool.segmentRenameForTest = null
                }
            }
        }

    @Test
    fun `ack staging rename failure rolls back staged renames and keeps manifest`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                File(spool, "sealed_1.jsonl").writeText("{rollback-a\n")
                File(spool, "sealed_2.jsonl").writeText("{rollback-b\n")
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    if (to.name.startsWith("quarantine_")) false else null
                }
                try {
                    TokenStatSpool.replay(context)
                    val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < deadline &&
                        (safeManifestText(manifest)?.contains("sealed_1.jsonl") != true ||
                            safeManifestText(manifest)?.contains("sealed_2.jsonl") != true)
                    ) {
                        delay(20)
                    }
                    assertTrue(safeManifestText(manifest)?.contains("sealed_1.jsonl") == true)
                    assertTrue(safeManifestText(manifest)?.contains("sealed_2.jsonl") == true)

                    // 第 2 个文件的 stage rename 失败 → 第 1 个已 stage 的文件必须回滚，
                    // manifest 不改；回滚 rename 的目标是 spool 根目录，不受注入影响
                    TokenStatSpool.segmentRenameForTest = { _, to ->
                        when {
                            to.parentFile?.name?.startsWith("quarantine_ack_trash_") == true &&
                                to.name == "sealed_2.jsonl" -> false
                            else -> null
                        }
                    }
                    try {
                        TokenStatSpool.acknowledgeAndDeleteQuarantine(
                            context,
                            setOf("sealed_1.jsonl", "sealed_2.jsonl"),
                        )
                        fail("ack must fail when staging a rename fails")
                    } catch (e: IOException) {
                        assertTrue("ack must report the staging failure", e.message!!.contains("stage"))
                    }
                    assertTrue(
                        "staged file must be rolled back after a failed rename",
                        File(spool, "sealed_1.jsonl").exists(),
                    )
                    assertTrue(File(spool, "sealed_2.jsonl").exists())
                    assertTrue(
                        "no trash directory may remain after rollback",
                        spool.listFiles().orEmpty().none { it.name.startsWith("quarantine_ack_trash_") },
                    )
                    assertTrue(
                        "both entries must survive the failed ack",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true &&
                            safeManifestText(manifest)?.contains("sealed_2.jsonl") == true,
                    )

                    // 恢复真实 rename 后 ack 成功：按身份删除两个段并移除两条记录
                    TokenStatSpool.segmentRenameForTest = null
                    TokenStatSpool.acknowledgeAndDeleteQuarantine(
                        context,
                        setOf("sealed_1.jsonl", "sealed_2.jsonl"),
                    )
                    assertFalse(File(spool, "sealed_1.jsonl").exists())
                    assertFalse(File(spool, "sealed_2.jsonl").exists())
                    assertFalse(safeManifestText(manifest)?.contains("sealed_1.jsonl") == true)
                    assertFalse(safeManifestText(manifest)?.contains("sealed_2.jsonl") == true)
                } finally {
                    TokenStatSpool.segmentRenameForTest = null
                }
            }
        }

    @Test
    fun `ack manifest write failure rolls back all staged files and keeps old manifest`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val managed = File(spool, "sealed_1.jsonl").apply { writeText("{managed-bad\n") }
                val quarantine =
                    File(spool, "quarantine_area_sealed_2.jsonl").apply { writeText("area-bad\n") }
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                val oldManifest =
                    "{\"file\":\"${managed.name}\",\"bytes\":${managed.length()}," +
                        "\"sha256\":\"${sha256Hex(managed.readBytes())}\",\"overCap\":false}\n"
                manifest.writeText(oldManifest)
                TokenStatSpool.metadataWriteErrorForTest = { it.name == manifest.name }
                try {
                    TokenStatSpool.acknowledgeAndDeleteQuarantine(
                        context,
                        setOf(managed.name, quarantine.name),
                    )
                    fail("ack must fail when the manifest cannot be published")
                } catch (e: IOException) {
                    assertTrue("ack must report the manifest failure", e.message!!.contains("manifest"))
                } finally {
                    TokenStatSpool.metadataWriteErrorForTest = null
                }
                assertTrue("managed evidence must be restored", managed.isFile)
                assertTrue("quarantine evidence must be restored", quarantine.isFile)
                assertEquals("old manifest must remain byte-for-byte intact", oldManifest, manifest.readText())
                assertTrue(
                    "no trash directory may remain after a successful rollback",
                    spool.listFiles().orEmpty().none { it.name.startsWith("quarantine_ack_trash_") },
                )
            }
        }

    @Test
    fun `ack rollback move with not durable dir sync keeps uncommitted trash and maintenance recovers it`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                File(spool, "sealed_1.jsonl").writeText("{rollback-sync-bad\n")
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    if (to.name.startsWith("quarantine_")) false else null
                }
                var calls = 0
                try {
                    TokenStatSpool.replay(context)
                    val entryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < entryDeadline &&
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") != true
                    ) {
                        delay(20)
                    }
                    assertTrue(safeManifestText(manifest)?.contains("sealed_1.jsonl") == true)
                    TokenStatSpool.segmentRenameForTest = null
                    // 阶段 2：manifest 重写失败触发回滚；回滚 move 的目录项 sync（第 7 次：
                    // 1 次 manifest 严格读取 + 1 次 trash 创建 + 2 次暂存 + 2 次状态写入）
                    // 失败 → trash 保留 UNCOMMITTED 状态、上层失败，绝不静默（P2）
                    TokenStatSpool.metadataWriteErrorForTest = { it.name == manifest.name }
                    TokenStatSpool.dirSyncForTest = {
                        calls += 1
                        if (calls == 7) TokenStatSpool.DirSyncResult.FAILED
                        else TokenStatSpool.DirSyncResult.OK
                    }
                    try {
                        TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf("sealed_1.jsonl"))
                        fail("ack must fail when the rollback dir sync is not OK")
                    } catch (e: IOException) {
                        assertTrue("ack must report the manifest failure", e.message!!.contains("manifest"))
                    }
                    val trashDirs = spool.listFiles().orEmpty()
                        .filter { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") }
                    assertEquals("uncommitted trash must be retained after a not-durable rollback", 1, trashDirs.size)
                    val state = File(trashDirs.single(), TokenStatSpool.ACK_TRASH_STATE_FILE_NAME)
                    assertTrue(
                        "state must remain UNCOMMITTED for maintenance rollback",
                        state.readText().startsWith(TokenStatSpool.ACK_STATE_UNCOMMITTED),
                    )
                    // 回滚 move 已可见（证据回到原路径）但目录项未确认：mapping 仍持有身份，
                    // 维护按状态机幂等完成
                    assertTrue("evidence is back at its original path", File(spool, "sealed_1.jsonl").exists())
                    assertTrue(
                        "manifest entry must survive the failed ack",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                    )
                    // 阶段 3：恢复后维护按 UNCOMMITTED + mapping 完成回滚并删除 trash；损坏
                    // sealed 随后被扫描器重新隔离为完整证据（与 ack 崩溃窗口协议一致）
                    TokenStatSpool.metadataWriteErrorForTest = null
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    TokenStatSpool.replay(context)
                    val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < restoreDeadline &&
                        spool.listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") }
                    ) {
                        delay(20)
                    }
                    assertTrue(
                        "trash must be resolved by maintenance after recovery",
                        spool.listFiles().orEmpty().none { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") },
                    )
                    val body = "{rollback-sync-bad\n"
                    val evidenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < evidenceDeadline &&
                        TokenStatSpool.quarantineEvidence(context).none { it.readText() == body }
                    ) {
                        delay(20)
                    }
                    assertEquals(
                        "evidence must be re-quarantined exactly once after the rollback",
                        1,
                        TokenStatSpool.quarantineEvidence(context).count { it.readText() == body },
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.segmentRenameForTest = null
                    TokenStatSpool.metadataWriteErrorForTest = null
                    TokenStatSpool.dirSyncForTest = null
                }
            }
        }

    @Test
    fun `committed ack trash residue counts into the total cap and maintenance cleans it`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val previousCap = TokenStatSpool.totalSpoolMaxBytesForTest
                val previousDelete = TokenStatSpool.spoolDeleteForTest
                try {
                    // 大证据文件：ack 后留在 trash（删除被强制失败），必须计入总容量
                    val evidence = File(spool, "quarantine_trash_cap_sealed_1.jsonl")
                    RandomAccessFile(evidence, "rw").use { it.setLength(28L * 1024) }
                    TokenStatSpool.MAX_SEGMENT_BYTES = 8L * 1024
                    // 总 cap 32KiB：准入上限 = 32K − min(512K, 32K−8K) = 8KiB，28KiB 残留
                    // 证据已让每次 append 的递归投影超限——旧实现只数顶层会放行到实际 36KiB
                    TokenStatSpool.totalSpoolMaxBytesForTest = 32L * 1024
                    TokenStatSpool.spoolDeleteForTest = { false }
                    TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf(evidence.name))
                    val trashDirs = spool.listFiles().orEmpty()
                        .filter { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") }
                    assertEquals("committed trash must remain when deletion is forced to fail", 1, trashDirs.size)
                    assertEquals(
                        "commit flip must be persisted in the trash state file",
                        TokenStatSpool.ACK_STATE_COMMITTED + "\n",
                        File(trashDirs.single(), TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).readText(),
                    )

                    // 行先于失败 DAO 生成（行生成需要真实价格读取）；DAO 只负责排空失败
                    val lines = (0 until 200).map { index ->
                        line(request("trash-cap-$index")) to "trash-cap-$index"
                    }
                    // DAO 永久失败 → sealed 段只增不减；递归总容量必须计入 trash 残留
                    val failingDao = mock<TokenStatsDao>()
                    whenever(failingDao.insertIdentityIfAbsent(any())).thenThrow(RuntimeException("dao down"))
                    whenever(failingDao.upsertDisplayModel(any())).thenThrow(RuntimeException("dao down"))
                    whenever(failingDao.insertEventIfNotResetCovered(any())).thenThrow(RuntimeException("dao down"))
                    val proxy = mock<AppDatabase>()
                    whenever(proxy.tokenStatsDao()).thenReturn(failingDao)
                    TokenStatsLedger.databaseProvider = { proxy }
                    var rejected = 0
                    for ((text, eventId) in lines) {
                        try {
                            TokenStatSpool.append(context, text, eventId)
                        } catch (e: TokenStatsPersistenceException) {
                            rejected++
                        }
                    }
                    assertEquals(
                        "every append must be refused while the trash residue holds the admission budget: $rejected",
                        lines.size,
                        rejected,
                    )
                    val cap = TokenStatSpool.totalSpoolMaxBytesForTest ?: 0L
                    fun recursiveTotal(): Long = spool.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    assertTrue(
                        "recursive total including trash must never exceed the cap: ${recursiveTotal()}",
                        recursiveTotal() <= cap,
                    )
                    // 冻结断言：拒绝后不再发布任何字节
                    val frozen = recursiveTotal()
                    repeat(10) {
                        try {
                            TokenStatSpool.append(context, lines[0].first, "refused-trash-$it")
                            fail("append after trash-inclusive cap must keep failing")
                        } catch (e: TokenStatsPersistenceException) {
                        }
                    }
                    assertEquals(frozen, recursiveTotal())
                    assertEquals(0, database.tokenStatsDao().countEvents())

                    // 维护补删恢复：删除恢复后 replay 清掉 committed trash
                    TokenStatSpool.spoolDeleteForTest = null
                    TokenStatSpool.replay(context)
                    val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < cleanupDeadline &&
                        spool.listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") }
                    ) {
                        delay(20)
                    }
                    assertTrue(
                        "committed trash must be removed by maintenance once deletion works",
                        spool.listFiles().orEmpty().none { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") },
                    )
                    // DAO 恢复后排空与 append 都恢复正常
                    TokenStatsLedger.databaseProvider = { database }
                    assertTrue(
                        TokenStatSpool.append(
                            context,
                            line(request("after-trash-recovery")),
                            "after-trash-recovery",
                        ),
                    )
                    TokenStatSpool.replay(context)
                    awaitEvent("after-trash-recovery")
                } finally {
                    TokenStatsLedger.databaseProvider = { database }
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                    TokenStatSpool.totalSpoolMaxBytesForTest = previousCap
                    TokenStatSpool.spoolDeleteForTest = previousDelete
                }
            }
        }

    @Test
    fun `ack refuses when trash state metadata would push the total over the cap`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            val previousCap = TokenStatSpool.totalSpoolMaxBytesForTest
            try {
                // 大量小证据文件 → mapping 状态文件较大；cap 只留 4KiB 头部空间，
                // 4 槽位最坏投影（mapping ~10KiB × 4）必然超限
                val files = (0 until 60).map { index ->
                    File(spool, "quarantine_many_$index.jsonl").apply { writeText("bad-$index\n") }
                }
                val totalNow = spool.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                TokenStatSpool.totalSpoolMaxBytesForTest = totalNow + 4 * 1024
                try {
                    TokenStatSpool.acknowledgeAndDeleteQuarantine(context, files.map { it.name }.toSet())
                    fail("ack must fail when the trash state metadata does not fit the total cap")
                } catch (e: IOException) {
                }
                // 全部证据仍在原位、没有 trash 目录残留、总量不超限（stage 已回滚）
                files.forEach { assertTrue("evidence must stay in place: ${it.name}", it.exists()) }
                assertTrue(
                    "no trash directory may remain after the refused ack",
                    spool.listFiles().orEmpty().none { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") },
                )
                assertTrue(
                    "total must stay within the cap: ${spool.walkTopDown().filter { it.isFile }.sumOf { it.length() }}",
                    spool.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= (TokenStatSpool.totalSpoolMaxBytesForTest ?: 0L),
                )
            } finally {
                TokenStatSpool.totalSpoolMaxBytesForTest = previousCap
            }
        }
    }

    @Test
    fun `ack staging failure with rollback failure keeps uncommitted trash and maintenance recovers it`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                File(spool, "sealed_1.jsonl").writeText("{rb-fail-a\n")
                File(spool, "sealed_2.jsonl").writeText("{rb-fail-b\n")
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    if (to.name.startsWith("quarantine_")) false else null
                }
                try {
                    TokenStatSpool.replay(context)
                    val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < deadline &&
                        (safeManifestText(manifest)?.contains("sealed_1.jsonl") != true ||
                            safeManifestText(manifest)?.contains("sealed_2.jsonl") != true)
                    ) {
                        delay(20)
                    }
                    assertTrue(safeManifestText(manifest)?.contains("sealed_1.jsonl") == true)
                    assertTrue(safeManifestText(manifest)?.contains("sealed_2.jsonl") == true)

                    // 第 2 个文件 stage 失败 + 第 1 个文件回滚失败 → ack 报错，trash 保留
                    TokenStatSpool.segmentRenameForTest = { _, to ->
                        when {
                            to.parentFile?.name?.startsWith("quarantine_ack_trash_") == true &&
                                to.name == "sealed_2.jsonl" -> false
                            to.parentFile?.name != null &&
                                !to.parentFile!!.name.startsWith("quarantine_ack_trash_") &&
                                to.name == "sealed_1.jsonl" -> false
                            else -> null
                        }
                    }
                    try {
                        TokenStatSpool.acknowledgeAndDeleteQuarantine(
                            context,
                            setOf("sealed_1.jsonl", "sealed_2.jsonl"),
                        )
                        fail("ack must report the staging failure")
                    } catch (e: IOException) {
                    }
                    val trashDirs = spool.listFiles().orEmpty()
                        .filter { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") }
                    assertEquals("uncommitted trash must be retained after rollback failure", 1, trashDirs.size)
                    val trash = trashDirs.single()
                    assertTrue("staged evidence must stay in trash", File(trash, "sealed_1.jsonl").exists())
                    assertFalse(File(spool, "sealed_1.jsonl").exists())
                    assertTrue("sealed_2 must stay in place (stage never happened)", File(spool, "sealed_2.jsonl").exists())
                    assertTrue(
                        "trash state must be UNCOMMITTED with a mapping",
                        File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME)
                            .readText().startsWith(TokenStatSpool.ACK_STATE_UNCOMMITTED),
                    )
                    assertTrue(
                        "manifest entry must remain",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                    )
                    assertTrue(
                        "manifest entry must remain",
                        safeManifestText(manifest)?.contains("sealed_2.jsonl") == true,
                    )

                    // replay 维护（rename 仍被注入失败）：不删 trash、不删证据、manifest 条目保留
                    TokenStatSpool.replay(context)
                    delay(700)
                    assertTrue("maintenance must never delete uncommitted trash", trash.exists())
                    assertTrue(File(trash, "sealed_1.jsonl").exists())
                    assertTrue(
                        "manifest entry must survive maintenance",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                    )
                    assertFalse(
                        "sealed_2 was restored to the evidence area by maintenance",
                        File(spool, "sealed_2.jsonl").exists(),
                    )

                    // 恢复 rename 能力后 replay：维护按 mapping+identity 回滚并自愈
                    TokenStatSpool.segmentRenameForTest = null
                    TokenStatSpool.replay(context)
                    val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < restoreDeadline &&
                        spool.listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") }
                    ) {
                        delay(20)
                    }
                    assertTrue(
                        "trash must be gone after a successful maintenance rollback",
                        spool.listFiles().orEmpty().none { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") },
                    )
                    awaitManifestWithout(spool, "sealed_1.jsonl")
                    awaitManifestWithout(spool, "sealed_2.jsonl")
                    // 两份证据都回到完整证据区（可导出/可 ack）
                    val evidence = TokenStatSpool.quarantineEvidence(context)
                    assertEquals(2, evidence.size)
                    assertTrue(evidence.any { it.name.contains("sealed_1.jsonl") })
                    assertTrue(evidence.any { it.name.contains("sealed_2.jsonl") })
                } finally {
                    TokenStatSpool.segmentRenameForTest = null
                }
            }
        }

    @Test
    fun `crash window with published manifest rolls back uncommitted trash and scanner re-quarantines`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                // 手工构造崩溃窗口：主 manifest 已发布（不含该身份），但 commit 标记未写。
                // P1-1：UNCOMMITTED 绝不根据 manifest 缺失推断已提交——必须回滚证据，
                // 回滚后的损坏 sealed 会被扫描器重新隔离（ack 视失败但不丢证据）。
                val body = "{crash-window-bad\n"
                val sha = sha256Hex(body.toByteArray(Charsets.UTF_8))
                val trash = File(spool, "quarantine_ack_trash_${UUID.randomUUID().toString().replace("-", "")}")
                trash.mkdirs()
                File(trash, "sealed_1.jsonl").writeText(body)
                File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).writeText(
                    TokenStatSpool.ACK_STATE_UNCOMMITTED + "\n" +
                        "{\"o\":\"sealed_1.jsonl\",\"t\":\"sealed_1.jsonl\",\"b\":${body.toByteArray(Charsets.UTF_8).size},\"s\":\"$sha\"}\n",
                )
                // manifest 不存在 = 条目已全部移除（旧实现会据此误判 committed 并删除证据）
                TokenStatSpool.replay(context)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline && trash.exists()) delay(20)
                assertFalse(
                    "maintenance must roll back uncommitted crash-window trash",
                    trash.exists(),
                )
                // 回滚后的损坏 sealed 被扫描器重新隔离为完整证据，绝不丢失
                val evidenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var reQuarantined = false
                while (System.nanoTime() < evidenceDeadline && !reQuarantined) {
                    reQuarantined = TokenStatSpool.quarantineEvidence(context).any {
                        it.name.contains("sealed_1.jsonl") && it.readText() == body
                    }
                    if (!reQuarantined) delay(20)
                }
                assertTrue("rolled-back corrupt segment must be re-quarantined as evidence", reQuarantined)
            }
        }

    @Test
    fun `partially corrupt ack trash mapping is fail-closed and maintenance retains everything`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val body1 = "{p12-a\n"
                val body2 = "{p12-b\n"
                val sha1 = sha256Hex(body1.toByteArray(Charsets.UTF_8))
                val sha2 = sha256Hex(body2.toByteArray(Charsets.UTF_8))
                val trash = File(spool, "quarantine_ack_trash_${UUID.randomUUID().toString().replace("-", "")}")
                trash.mkdirs()
                File(trash, "sealed_1.jsonl").writeText(body1)
                File(trash, "sealed_2.jsonl").writeText(body2)
                // 首行有效 mapping + 一行损坏 mapping：mapNotNull 会静默丢弃损坏行，
                // 只回滚 1 个文件并删除 trash——旧实现会丢失第 2 份证据（P1-2）
                File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).writeText(
                    TokenStatSpool.ACK_STATE_UNCOMMITTED + "\n" +
                        "{\"o\":\"sealed_1.jsonl\",\"t\":\"sealed_1.jsonl\",\"b\":${body1.toByteArray(Charsets.UTF_8).size},\"s\":\"$sha1\"}\n" +
                        "{corrupt-json\n",
                )
                TokenStatSpool.replay(context)
                delay(700)
                assertTrue("partially corrupt mapping must keep the trash", trash.exists())
                assertTrue(File(trash, "sealed_1.jsonl").exists())
                assertTrue(File(trash, "sealed_2.jsonl").exists())
                assertFalse("no rollback may happen from a partial mapping", File(spool, "sealed_1.jsonl").exists())
                // UI 可见：作为 stuck 受管证据列出
                assertEquals(listOf(trash), TokenStatSpool.stuckAckTrashEvidence(context))

                // 修复为重复 mapping（同一原名两条）→ 仍然 fail-closed 保留
                val lineA =
                    "{\"o\":\"sealed_1.jsonl\",\"t\":\"sealed_1.jsonl\",\"b\":${body1.toByteArray(Charsets.UTF_8).size},\"s\":\"$sha1\"}\n"
                File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).writeText(
                    TokenStatSpool.ACK_STATE_UNCOMMITTED + "\n" + lineA + lineA,
                )
                TokenStatSpool.replay(context)
                delay(700)
                assertTrue("duplicate mapping must keep the trash", trash.exists())
                assertTrue(File(trash, "sealed_1.jsonl").exists())
                assertTrue(File(trash, "sealed_2.jsonl").exists())

                // 完整修复 mapping（两份证据都被覆盖）→ 维护回滚并自愈
                File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).writeText(
                    TokenStatSpool.ACK_STATE_UNCOMMITTED + "\n" + lineA +
                        "{\"o\":\"sealed_2.jsonl\",\"t\":\"sealed_2.jsonl\",\"b\":${body2.toByteArray(Charsets.UTF_8).size},\"s\":\"$sha2\"}\n",
                )
                TokenStatSpool.replay(context)
                val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < restoreDeadline && trash.exists()) delay(20)
                assertFalse("trash must be rolled back once the mapping is complete", trash.exists())
                // 回滚后的损坏 sealed 被扫描器重新隔离为完整证据
                val evidence = TokenStatSpool.quarantineEvidence(context)
                assertEquals(2, evidence.size)
                assertTrue(evidence.any { it.readText() == body1 })
                assertTrue(evidence.any { it.readText() == body2 })
            }
        }

    @Test
    fun `partial mapping with unreadable trash enumeration is fail-closed and manifest stays verbatim`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val body1 = "{enum-null-a\n"
                val body2 = "{enum-null-b\n"
                val sha1 = sha256Hex(body1.toByteArray(Charsets.UTF_8))
                val sha2 = sha256Hex(body2.toByteArray(Charsets.UTF_8))
                val manifestLine =
                    "{\"ts\":1,\"file\":\"sealed_1.jsonl\",\"bytes\":${body1.toByteArray(Charsets.UTF_8).size}," +
                        "\"sha256\":\"$sha1\",\"overCap\":false}\n"
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                manifest.writeText(manifestLine)
                val trash =
                    File(spool, "quarantine_ack_trash_${UUID.randomUUID().toString().replace("-", "")}")
                trash.mkdirs()
                File(trash, "sealed_1.jsonl").writeText(body1)
                File(trash, "sealed_2.jsonl").writeText(body2)
                // 首行有效 mapping + 一行损坏：全有或全无解析必然失败；trash 枚举再失败时，
                // 即使 mapping 已覆盖可见证据，未枚举的证据也无法排除 → 仍必须 fail-closed
                File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).writeText(
                    TokenStatSpool.ACK_STATE_UNCOMMITTED + "\n" +
                        "{\"o\":\"sealed_1.jsonl\",\"t\":\"sealed_1.jsonl\",\"b\":${body1.toByteArray(Charsets.UTF_8).size},\"s\":\"$sha1\"}\n" +
                        "{corrupt-json\n",
                )
                // 健康段用于证明维护轮确实运行（枚举失败期间照常排空，不做破坏性决策）
                File(spool, "sealed_9.jsonl").writeText(line(request("enum-null-healthy")) + "\n")
                TokenStatSpool.directoryListingForTest = { dir ->
                    if (dir == trash) null else dir.listFiles()
                }
                try {
                    TokenStatSpool.replay(context)
                    awaitEvent("enum-null-healthy")
                    delay(700)
                    assertTrue("trash must be retained while its enumeration fails", trash.exists())
                    assertTrue(File(trash, "sealed_1.jsonl").exists())
                    assertTrue(File(trash, "sealed_2.jsonl").exists())
                    assertFalse(
                        "no rollback may happen from a partial mapping with failed enumeration",
                        File(spool, "sealed_1.jsonl").exists(),
                    )
                    assertFalse("no un-enumerated evidence may be deleted", File(spool, "sealed_2.jsonl").exists())
                    assertEquals(
                        "manifest must be preserved verbatim",
                        manifestLine,
                        safeManifestText(manifest),
                    )
                    // P1-6 fail-closed：stuck 证据枚举走同一 seam——枚举失败时 UI 查询必须
                    // 明确抛错，绝不能返回部分/空列表误导用户删除
                    try {
                        TokenStatSpool.stuckAckTrashEvidence(context)
                        fail("stuck ack trash evidence must fail while trash enumeration fails")
                    } catch (e: IOException) {
                        assertTrue("failure must name the enumeration error", e.message!!.contains("enumerate"))
                    }
                    try {
                        TokenStatSpool.stuckAckTrashBytes(context)
                        fail("stuck ack trash bytes must fail while trash enumeration fails")
                    } catch (e: IOException) {
                        assertTrue("failure must name the enumeration error", e.message!!.contains("enumerate"))
                    }
                    // 有界：重复维护轮不改写 manifest、不处置 trash
                    TokenStatSpool.replay(context)
                    delay(700)
                    assertTrue(trash.exists())
                    assertEquals(
                        "repeated maintenance rounds must not rewrite the manifest",
                        manifestLine,
                        safeManifestText(manifest),
                    )
                } finally {
                    TokenStatSpool.directoryListingForTest = null
                }
                // 恢复枚举 + 完整 mapping → 维护回滚并自愈（cleanup 成功）
                File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).writeText(
                    TokenStatSpool.ACK_STATE_UNCOMMITTED + "\n" +
                        "{\"o\":\"sealed_1.jsonl\",\"t\":\"sealed_1.jsonl\",\"b\":${body1.toByteArray(Charsets.UTF_8).size},\"s\":\"$sha1\"}\n" +
                        "{\"o\":\"sealed_2.jsonl\",\"t\":\"sealed_2.jsonl\",\"b\":${body2.toByteArray(Charsets.UTF_8).size},\"s\":\"$sha2\"}\n",
                )
                TokenStatSpool.replay(context)
                val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < restoreDeadline && trash.exists()) delay(20)
                assertFalse("trash must be rolled back once enumeration and mapping recover", trash.exists())
                // P1-6：枚举成功且无 trash 时才是真正的空列表
                assertEquals(emptyList<File>(), TokenStatSpool.stuckAckTrashEvidence(context))
                val evidenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var evidence: List<File> = emptyList()
                while (System.nanoTime() < evidenceDeadline && evidence.size != 2) {
                    evidence = TokenStatSpool.quarantineEvidence(context)
                    if (evidence.size != 2) delay(20)
                }
                assertEquals(2, evidence.size)
                assertTrue(evidence.any { it.readText() == body1 })
                assertTrue(evidence.any { it.readText() == body2 })
            }
        }

    @Test
    fun `spool root enumeration failure makes trash state unknown and blocks stale removal`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                // 消失原件的 manifest 条目：根枚举失败时无法证明旧身份不被未枚举的 trash 持有
                val oldBody = "{root-enum-stale\n"
                val oldSha = sha256Hex(oldBody.toByteArray(Charsets.UTF_8))
                val manifestLine =
                    "{\"ts\":1,\"file\":\"sealed_1.jsonl\",\"bytes\":${oldBody.toByteArray(Charsets.UTF_8).size}," +
                        "\"sha256\":\"$oldSha\",\"overCap\":false}\n"
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                manifest.writeText(manifestLine)
                // UNCOMMITTED trash 真实持有该身份（根枚举失败时完全不可见）
                val trash =
                    File(spool, "quarantine_ack_trash_${UUID.randomUUID().toString().replace("-", "")}")
                trash.mkdirs()
                File(trash, "sealed_1.jsonl").writeText(oldBody)
                File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).writeText(
                    TokenStatSpool.ACK_STATE_UNCOMMITTED + "\n" +
                        "{\"o\":\"sealed_1.jsonl\",\"t\":\"sealed_1.jsonl\",\"b\":${oldBody.toByteArray(Charsets.UTF_8).size},\"s\":\"$oldSha\"}\n",
                )
                // 健康段：根枚举失败期间 drain 必须 fail-closed 退避——段保留、绝不入 Room
                File(spool, "sealed_9.jsonl").writeText(line(request("root-enum-healthy")) + "\n")
                TokenStatSpool.directoryListingForTest = { dir ->
                    if (dir == spool) null else dir.listFiles()
                }
                try {
                    TokenStatSpool.replay(context)
                    delay(700)
                    assertNull(
                        "no segment may drain while the root enumeration fails",
                        database.tokenStatsDao().getEvent("root-enum-healthy"),
                    )
                    assertTrue("healthy segment must be preserved", File(spool, "sealed_9.jsonl").exists())
                    assertEquals(
                        "stale removal must be blocked while the root enumeration fails",
                        manifestLine,
                        safeManifestText(manifest),
                    )
                    assertTrue(
                        "trash must be retained while the root enumeration fails",
                        trash.exists(),
                    )
                    assertTrue(File(trash, "sealed_1.jsonl").exists())
                    // 有界：重复维护轮保持原样
                    TokenStatSpool.replay(context)
                    delay(700)
                    assertEquals(manifestLine, safeManifestText(manifest))
                    assertTrue(trash.exists())
                } finally {
                    TokenStatSpool.directoryListingForTest = null
                }
                // 枚举恢复：健康段排空；身份确实被 trash 持有 → 回滚后按 MATCH 处置，条目最终移除
                TokenStatSpool.replay(context)
                awaitEvent("root-enum-healthy")
                awaitManifestWithout(spool, "sealed_1.jsonl")
                val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < restoreDeadline && trash.exists()) delay(20)
                assertFalse("trash must be rolled back once enumeration recovers", trash.exists())
                val evidenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var restored = false
                while (System.nanoTime() < evidenceDeadline && !restored) {
                    restored = TokenStatSpool.quarantineEvidence(context).any { it.readText() == oldBody }
                    if (!restored) delay(20)
                }
                assertTrue("held evidence must be restored after recovery", restored)
            }
        }

    @Test
    fun `maintenance defers rollback while trash enumeration fails and cleanup succeeds after the seam recovers`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val body = "{enum-recovery-bad\n"
                val sha = sha256Hex(body.toByteArray(Charsets.UTF_8))
                val trash =
                    File(spool, "quarantine_ack_trash_${UUID.randomUUID().toString().replace("-", "")}")
                trash.mkdirs()
                File(trash, "sealed_1.jsonl").writeText(body)
                File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).writeText(
                    TokenStatSpool.ACK_STATE_UNCOMMITTED + "\n" +
                        "{\"o\":\"sealed_1.jsonl\",\"t\":\"sealed_1.jsonl\",\"b\":${body.toByteArray(Charsets.UTF_8).size},\"s\":\"$sha\"}\n",
                )
                // mapping 完全有效也必须在枚举失败时 fail-closed：无法证明没有未枚举的证据
                // 健康段用于证明维护轮确实运行（枚举失败期间照常排空，不做破坏性决策）
                File(spool, "sealed_9.jsonl").writeText(line(request("enum-recovery-healthy")) + "\n")
                TokenStatSpool.directoryListingForTest = { dir ->
                    if (dir == trash) null else dir.listFiles()
                }
                try {
                    TokenStatSpool.replay(context)
                    awaitEvent("enum-recovery-healthy")
                    delay(700)
                    assertTrue(
                        "valid mapping must still be fail-closed while enumeration fails",
                        trash.exists(),
                    )
                    assertTrue(File(trash, "sealed_1.jsonl").exists())
                    assertFalse(
                        "no rollback may happen while enumeration fails",
                        File(spool, "sealed_1.jsonl").exists(),
                    )
                } finally {
                    TokenStatSpool.directoryListingForTest = null
                }
                // 恢复 seam → rollback cleanup 成功：trash 删除、证据回到原槽位、被扫描器隔离
                TokenStatSpool.replay(context)
                val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < restoreDeadline && trash.exists()) delay(20)
                assertFalse("trash must be deleted after the successful rollback", trash.exists())
                val evidenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var reQuarantined = false
                while (System.nanoTime() < evidenceDeadline && !reQuarantined) {
                    reQuarantined = TokenStatSpool.quarantineEvidence(context).any {
                        it.isFile && it.readText() == body
                    }
                    if (!reQuarantined) delay(20)
                }
                assertTrue("rolled-back corrupt segment must be re-quarantined as evidence", reQuarantined)
            }
        }

    @Test
    fun `seal publish with active delete failure is rolled back and later recovers exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("delete-fail-a"))
                val lineB = line(request("delete-fail-b"))
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    var failures = 0
                    TokenStatSpool.sealActiveDeleteForTest = {
                        failures += 1
                        false
                    }
                    try {
                        // seal：createLink 成功 → 删除 active 失败 → 回滚链接 → seal 失败
                        // → append 明确失败（B 未发布、无伪 durable）
                        assertFalse(
                            "append must fail when the post-publish active delete fails",
                            TokenStatSpool.append(context, lineB, "delete-fail-b"),
                        )
                    } finally {
                        TokenStatSpool.sealActiveDeleteForTest = null
                    }
                    assertEquals(1, failures)
                    // 回滚成功：无 sealed 残留；active 保持原内容
                    assertTrue(
                        "rolled-back seal must leave no sealed residue",
                        spool.listFiles().orEmpty().none { it.name.startsWith("sealed_") },
                    )
                    assertEquals(lineA + "\n", File(spool, "active.jsonl").readText())
                    // 恢复后：既有事件恰一次入 Room，被拒绝的 B 从未发布
                    TokenStatSpool.replay(context)
                    awaitEvent("delete-fail-a")
                    assertEquals(1, database.tokenStatsDao().countEvents())
                    assertTrue(
                        "append must succeed after the delete failure recovers",
                        TokenStatSpool.append(context, lineB, "delete-fail-b"),
                    )
                    awaitEvent("delete-fail-b")
                    assertEquals(2, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.sealActiveDeleteForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `hardlink unsupported falls back to atomic no-replace copy publish`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
            val lineA = line(request("copy-fallback-a"))
            val lineB = line(request("copy-fallback-b"))
            try {
                File(spool, "active.jsonl").writeText(lineA + "\n")
                TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                TokenStatSpool.sealHardLinkForTest = { _, _ -> false }
                // 停掉后台 writer：seal 在 append 内同步完成（copy 回退），断言不被并发 drain 干扰
                TokenStatSpool.shutdownWriterForTest()
                try {
                    assertTrue(
                        "append must seal via the copy fallback and succeed",
                        TokenStatSpool.append(context, lineB, "copy-fallback-b"),
                    )
                } finally {
                    TokenStatSpool.sealHardLinkForTest = null
                }
                // copy 发布成功：sealed_1 = active 原内容，active = 新事件
                assertEquals(lineA + "\n", File(spool, "sealed_1.jsonl").readText())
                assertEquals(lineB + "\n", File(spool, "active.jsonl").readText())
                // 恢复 writer 后全部事件各恰一次入 Room
                TokenStatSpool.replay(context)
                awaitEvent("copy-fallback-a")
                awaitEvent("copy-fallback-b")
                awaitNoSealedSegments(spool)
                assertEquals(2, database.tokenStatsDao().countEvents())
                assertEquals(
                    "each event must be recorded exactly once",
                    setOf("copy-fallback-a", "copy-fallback-b"),
                    database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                )
            } finally {
                TokenStatSpool.sealHardLinkForTest = null
                TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
            }
        }
    }

    @Test
    fun `copy fallback crash window content duplicate is recovered and drains once`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
            val lineA = line(request("copy-window-a"))
            val lineB = line(request("copy-window-b"))
            val lineC = line(request("copy-window-c"))
            try {
                val content = lineA + "\n" + lineB + "\n"
                // 模拟 copy 回退崩溃窗口：sealed_1 复制完成、active 删除未发生
                // （两个独立 inode 同内容）
                File(spool, "active.jsonl").writeText(content)
                File(spool, "sealed_1.jsonl").writeText(content)
                TokenStatSpool.MAX_SEGMENT_BYTES = content.length.toLong() + 1
                TokenStatSpool.shutdownWriterForTest()
                // append 必须先按内容识别并删除 sealed 副本；随后的 seal 把内容重新封为
                // 唯一的 sealed_1（单份，绝不重复拼接、绝不污染旧副本）
                assertTrue(TokenStatSpool.append(context, lineC, "copy-window-c"))
                assertEquals(
                    "sealed segment must hold the single copy of the old active content",
                    content,
                    File(spool, "sealed_1.jsonl").readText(),
                )
                assertEquals(
                    "new event must be durable in active",
                    lineC + "\n",
                    File(spool, "active.jsonl").readText(),
                )
                TokenStatSpool.replay(context)
                awaitEvent("copy-window-a")
                awaitEvent("copy-window-b")
                awaitEvent("copy-window-c")
                awaitNoSealedSegments(spool)
                assertEquals(3, database.tokenStatsDao().countEvents())
                assertEquals(
                    "each event must be recorded exactly once",
                    setOf("copy-window-a", "copy-window-b", "copy-window-c"),
                    database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                )
            } finally {
                TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
            }
        }
    }

    @Test
    fun `copy fallback target fsync failure retains active and recovers exactly once`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
            val lineA = line(request("target-fsync-a"))
            val lineB = line(request("target-fsync-b"))
            try {
                File(spool, "active.jsonl").writeText(lineA + "\n")
                TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                TokenStatSpool.sealHardLinkForTest = { _, _ -> false }
                TokenStatSpool.fileSyncForTest = { false }
                TokenStatSpool.shutdownWriterForTest()
                try {
                    // copy 完成后目标 fsync 失败：必须保留 active、处置目标、明确失败
                    assertFalse(
                        "append must fail when the sealed target fsync fails",
                        TokenStatSpool.append(context, lineB, "target-fsync-b"),
                    )
                } finally {
                    TokenStatSpool.fileSyncForTest = null
                    TokenStatSpool.sealHardLinkForTest = null
                }
                assertEquals("active must be retained", lineA + "\n", File(spool, "active.jsonl").readText())
                assertFalse(
                    "no normal sealed segment may be left from the failed publish",
                    spool.listFiles().orEmpty().any { it.isFile && it.name.startsWith("sealed_") },
                )
                // 目标被隔离为 seal_failed_*（identity 确认通过，内容 = active 前缀/相等）
                val isolated = spool.listFiles().orEmpty().single { it.name.startsWith("seal_failed_") }
                assertEquals("isolated target must keep the copied bytes", lineA + "\n", isolated.readText())
                // 恢复：维护清理隔离副本，既有事件恰一次入 Room；被拒事件随后发布成功
                TokenStatSpool.replay(context)
                awaitEvent("target-fsync-a")
                awaitSegmentGone(spool, isolated.name)
                assertEquals(1, database.tokenStatsDao().countEvents())
                assertTrue(TokenStatSpool.append(context, lineB, "target-fsync-b"))
                awaitEvent("target-fsync-b")
                assertEquals(2, database.tokenStatsDao().countEvents())
            } finally {
                TokenStatSpool.fileSyncForTest = null
                TokenStatSpool.sealHardLinkForTest = null
                TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
            }
        }
    }

    @Test
    fun `copy fallback first dir sync failure retains active and recovers exactly once`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
            val lineA = line(request("dirsync-fail-a"))
            val lineB = line(request("dirsync-fail-b"))
            try {
                File(spool, "active.jsonl").writeText(lineA + "\n")
                TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                TokenStatSpool.sealHardLinkForTest = { _, _ -> false }
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.FAILED }
                TokenStatSpool.shutdownWriterForTest()
                try {
                    // 目标创建未确认持久（目录 sync 失败）：必须保留 active、处置目标、明确失败
                    assertFalse(
                        "append must fail when the target-creating dir sync fails",
                        TokenStatSpool.append(context, lineB, "dirsync-fail-b"),
                    )
                } finally {
                    // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED，恢复
                    // 路径必须回到注入的 OK 才能运行正常 seal 发布/排空协议）
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    TokenStatSpool.sealHardLinkForTest = null
                }
                assertEquals("active must be retained", lineA + "\n", File(spool, "active.jsonl").readText())
                assertFalse(
                    "no normal sealed segment may be left from the failed publish",
                    spool.listFiles().orEmpty().any { it.isFile && it.name.startsWith("sealed_") },
                )
                TokenStatSpool.replay(context)
                awaitEvent("dirsync-fail-a")
                awaitNoSealedSegments(spool)
                assertEquals(1, database.tokenStatsDao().countEvents())
                assertTrue(TokenStatSpool.append(context, lineB, "dirsync-fail-b"))
                awaitEvent("dirsync-fail-b")
                assertEquals(2, database.tokenStatsDao().countEvents())
            } finally {
                TokenStatSpool.dirSyncForTest = null
                TokenStatSpool.sealHardLinkForTest = null
                TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
            }
        }
    }

    @Test
    fun `copy fallback post-active-delete dir sync failure keeps durable target and drains once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("post-sync-a"))
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    TokenStatSpool.sealHardLinkForTest = { _, _ -> false }
                    var dirSyncCalls = 0
                    TokenStatSpool.dirSyncForTest = {
                        dirSyncCalls += 1
                        // 前两次是 P1-1 bootstrap gate（filesDir + spool 目录）；第三次（目标
                        // 创建）成功，第四次（active 删除）失败
                        if (dirSyncCalls != 4) TokenStatSpool.DirSyncResult.OK else TokenStatSpool.DirSyncResult.FAILED
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        // 目标已 data+creation durable 后删除 active 的目录同步失败：返回 FAILED
                        // 阻止本轮后续 append 污染，但绝不回滚已 durable 的 target
                        assertFalse(
                            "append must fail when the post-delete dir sync fails",
                            TokenStatSpool.append(context, line(request("post-sync-b")), "post-sync-b"),
                        )
                    } finally {
                        // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）
                        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                        TokenStatSpool.sealHardLinkForTest = null
                    }
                    assertEquals(4, dirSyncCalls)
                    assertEquals(
                        "durable target must be kept with the full content",
                        lineA + "\n",
                        File(spool, "sealed_1.jsonl").readText(),
                    )
                    assertFalse("active must have been removed in-process", File(spool, "active.jsonl").exists())
                    // 恢复：target 是唯一内容持有者，正常排空，事件恰一次入 Room
                    TokenStatSpool.replay(context)
                    awaitEvent("post-sync-a")
                    awaitNoSealedSegments(spool)
                    assertEquals(1, database.tokenStatsDao().countEvents())
                    assertTrue(TokenStatSpool.append(context, line(request("post-sync-c")), "post-sync-c"))
                    awaitEvent("post-sync-c")
                    assertEquals(2, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.sealHardLinkForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `hardlink seal first dir sync failure rolls back link retains active and recovers exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("link-sync-a"))
                val lineB = line(request("link-sync-b"))
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.FAILED }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        // 链接目录项未确认持久：必须回滚链接、保留 active、明确失败
                        assertFalse(
                            "append must fail when the link-creating dir sync fails",
                            TokenStatSpool.append(context, lineB, "link-sync-b"),
                        )
                    } finally {
                        // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）
                        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    }
                    assertEquals("active must be retained", lineA + "\n", File(spool, "active.jsonl").readText())
                    assertFalse(
                        "rolled-back seal must leave no hardlink residue",
                        spool.listFiles().orEmpty().any { it.name.startsWith("sealed_") },
                    )
                    TokenStatSpool.replay(context)
                    awaitEvent("link-sync-a")
                    awaitNoSealedSegments(spool)
                    assertEquals(1, database.tokenStatsDao().countEvents())
                    assertTrue(TokenStatSpool.append(context, lineB, "link-sync-b"))
                    awaitEvent("link-sync-b")
                    assertEquals(2, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `seal copy partial cleanup failures tombstone the target never drain it and recover as bounded evidence`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("tombstone-partial-a"))
                val lineB = line(request("tombstone-partial-b"))
                val partial = lineA + "\n" // 严格部分：只是 active 第一行的前缀内容，身份与完整内容不同
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n" + lineB + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    TokenStatSpool.sealHardLinkForTest = { _, _ -> false }
                    TokenStatSpool.sealCopyForTest = { _, target ->
                        target.writeText(partial)
                        false
                    }
                    TokenStatSpool.segmentRenameForTest = { from, _ ->
                        if (from.name.startsWith("sealed_")) false else null
                    }
                    TokenStatSpool.segmentDeleteForTest = { f ->
                        if (f.name.startsWith("sealed_")) false else null
                    }
                    try {
                        assertFalse(
                            "append must fail when the seal copy fails mid-way",
                            TokenStatSpool.append(context, line(request("tombstone-partial-c")), "tombstone-partial-c"),
                        )
                    } finally {
                        TokenStatSpool.sealCopyForTest = null
                    }
                    // rename/delete 都失败 → tombstone skip：manifest 记录身份，scanner 跳过
                    val partialFile = File(spool, "sealed_1.jsonl")
                    assertTrue("partial target must stay at the candidate name", partialFile.exists())
                    assertEquals("partial bytes must be preserved", partial, partialFile.readText())
                    assertTrue(
                        "partial target must be recorded in the tombstone manifest",
                        safeManifestText(File(spool, "quarantine_skip_manifest.jsonl"))?.contains("sealed_1.jsonl") == true,
                    )
                    // 带 seams 恢复：tombstoned 部分目标被跳过（文件保留、绝不普通排空），健康
                    // 内容封到下一编号并恰一次入 Room
                    TokenStatSpool.replay(context)
                    awaitEvent("tombstone-partial-a")
                    awaitEvent("tombstone-partial-b")
                    assertEquals(2, database.tokenStatsDao().countEvents())
                    assertTrue("tombstoned partial must still exist", partialFile.exists())
                    // 移除失败 seam 后维护把部分目标移入完整证据区（有界证据）并移除 manifest 条目
                    TokenStatSpool.segmentRenameForTest = null
                    TokenStatSpool.segmentDeleteForTest = null
                    TokenStatSpool.replay(context)
                    awaitSegmentGone(spool, "sealed_1.jsonl")
                    awaitManifestWithout(spool, "sealed_1.jsonl")
                    val evidence = TokenStatSpool.quarantineEvidence(context)
                    assertTrue(
                        "isolated partial must become bounded quarantine evidence",
                        evidence.any { it.isFile && it.readText() == partial },
                    )
                    assertEquals(2, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.sealCopyForTest = null
                    TokenStatSpool.sealHardLinkForTest = null
                    TokenStatSpool.segmentRenameForTest = null
                    TokenStatSpool.segmentDeleteForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `copy fallback with durable dir syncs publishes and drains each event exactly once`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
            val lineA = line(request("dir-durable-a"))
            val lineB = line(request("dir-durable-b"))
            try {
                File(spool, "active.jsonl").writeText(lineA + "\n")
                TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                TokenStatSpool.sealHardLinkForTest = { _, _ -> false }
                var dirSyncCalls = 0
                TokenStatSpool.dirSyncForTest = {
                    dirSyncCalls += 1
                    TokenStatSpool.DirSyncResult.OK // 模拟 Android/Linux 目录 fsync 成功
                }
                TokenStatSpool.shutdownWriterForTest()
                try {
                    assertTrue(
                        "append must seal via copy with durable dir syncs",
                        TokenStatSpool.append(context, lineB, "dir-durable-b"),
                    )
                } finally {
                    // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    TokenStatSpool.sealHardLinkForTest = null
                }
                // P1 终审：封段发布 2 次目录同步（目标创建、active 删除）+ append 侧 1 次
                // （seal 删除 active 后新 active 属首次创建，目录项必须确认持久）+ P1-1
                // bootstrap gate 2 次（filesDir + spool 目录，本测试进程首次使用）
                assertEquals(5, dirSyncCalls)
                assertEquals("sealed_1 must hold the old content", lineA + "\n", File(spool, "sealed_1.jsonl").readText())
                assertEquals("active must hold the new event", lineB + "\n", File(spool, "active.jsonl").readText())
                TokenStatSpool.replay(context)
                awaitEvent("dir-durable-a")
                awaitEvent("dir-durable-b")
                awaitNoSealedSegments(spool)
                assertEquals(2, database.tokenStatsDao().countEvents())
                assertEquals(
                    "each event must be recorded exactly once",
                    setOf("dir-durable-a", "dir-durable-b"),
                    database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                )
            } finally {
                TokenStatSpool.dirSyncForTest = null
                TokenStatSpool.sealHardLinkForTest = null
                TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
            }
        }
    }

    @Test
    fun `seal failed target deletion failure stays visible exportable ackable and ack frees the cap`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                // seal 发布失败隔离的部分目标（受管失败发布证据）与普通证据并存
                val failed = File(spool, "seal_failed_${UUID.randomUUID().toString().replace("-", "")}")
                failed.writeText("{partial-copy-evidence\n")
                val regular = File(spool, "quarantine_existing_sealed_1.jsonl")
                regular.writeText("{regular-evidence\n")
                // 维护删除失败（seam）：隔离副本保留、下一轮重试，绝不自动消失
                TokenStatSpool.segmentDeleteForTest = { f ->
                    if (f.name.startsWith("seal_failed_")) false else null
                }
                try {
                    TokenStatSpool.replay(context)
                    delay(700)
                    assertTrue("deletion failure must keep the failed target", failed.exists())
                    // 可见：quarantineEvidence 含 seal_failed_*，字节计入证据总量
                    val evidence = TokenStatSpool.quarantineEvidence(context)
                    assertTrue("seal_failed target must be visible as evidence", evidence.any { it.name == failed.name })
                    assertTrue("regular evidence must stay visible", evidence.any { it.name == regular.name })
                    assertTrue(
                        "seal_failed bytes must count toward the evidence total",
                        TokenStatSpool.quarantineEvidence(context).sumOf { it.length() } >= failed.length(),
                    )
                    // 导出包含隔离目标
                    val exported =
                        TokenStatSpool.exportQuarantineEvidence(context, File(root, "p2-evidence-export"))
                    assertTrue("seal_failed target must be exportable", exported.any { it.name == failed.name })
                    // 用户确认删除（NOFOLLOW/path 根校验在 ack 内部）→ 证据消失、容量释放
                    val bytesBefore = TokenStatSpool.quarantineEvidence(context).sumOf { it.length() }
                    TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf(failed.name))
                    assertFalse("ack must delete the seal_failed target", failed.exists())
                    val bytesAfter = TokenStatSpool.quarantineEvidence(context).sumOf { it.length() }
                    assertTrue("ack must release the held evidence bytes", bytesAfter < bytesBefore)
                    assertTrue(
                        "remaining evidence must still be intact",
                        TokenStatSpool.quarantineEvidence(context).any { it.name == regular.name },
                    )
                } finally {
                    TokenStatSpool.segmentDeleteForTest = null
                }
            }
        }

    @Test
    fun `corrupt uncommitted trash mapping never drops held manifest identity for vanished original`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                // P1 场景：sealed_1 是受管失败段（tombstone 条目），文件已被 ack stage 进
                // UNCOMMITTED trash 后崩溃（主 manifest 已发布、commit 翻转未写），随后状态
                // mapping 损坏（一条有效 + 一条损坏）。根文件缺失时，旧实现因 held 集合为空
                // 会把 sealed_1 条目按 stale 移除——fail-closed 被违背。
                val body1 = "{corrupt-held-a\n"
                val body2 = "{corrupt-held-b\n"
                val sha1 = sha256Hex(body1.toByteArray(Charsets.UTF_8))
                val sha2 = sha256Hex(body2.toByteArray(Charsets.UTF_8))
                val manifestLine =
                    "{\"ts\":1,\"file\":\"sealed_1.jsonl\",\"bytes\":${body1.toByteArray(Charsets.UTF_8).size}," +
                        "\"sha256\":\"$sha1\",\"overCap\":false}\n"
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                manifest.writeText(manifestLine)
                val trash = File(spool, "quarantine_ack_trash_${UUID.randomUUID().toString().replace("-", "")}")
                trash.mkdirs()
                File(trash, "sealed_1.jsonl").writeText(body1)
                File(trash, "sealed_2.jsonl").writeText(body2)
                File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).writeText(
                    TokenStatSpool.ACK_STATE_UNCOMMITTED + "\n" +
                        "{\"o\":\"sealed_1.jsonl\",\"t\":\"sealed_1.jsonl\",\"b\":${body1.toByteArray(Charsets.UTF_8).size},\"s\":\"$sha1\"}\n" +
                        "{corrupt-json\n",
                )

                TokenStatSpool.replay(context)
                delay(700)
                assertTrue("corrupt mapping must keep the trash", trash.exists())
                assertTrue(File(trash, "sealed_1.jsonl").exists())
                assertTrue(File(trash, "sealed_2.jsonl").exists())
                assertFalse(
                    "no rollback may happen from a partial mapping",
                    File(spool, "sealed_1.jsonl").exists(),
                )
                assertEquals(
                    "manifest sealed_1 entry must be preserved verbatim",
                    manifestLine,
                    safeManifestText(manifest),
                )
                assertEquals(listOf(trash), TokenStatSpool.stuckAckTrashEvidence(context))

                // 后续维护轮保持有界：hasUnknown 时整轮跳过 manifest 重写，条目逐字不变
                TokenStatSpool.replay(context)
                delay(700)
                assertEquals(
                    "repeated maintenance rounds must not rewrite the manifest",
                    manifestLine,
                    safeManifestText(manifest),
                )

                // 修复 mapping（两份证据都被覆盖）→ 维护回滚到根并安全重新隔离/处置：
                // sealed_1 与 manifest 身份 MATCH → 移入完整证据区并移除条目；sealed_2 被
                // 扫描器重新隔离。manifest 与证据状态最终一致。
                File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).writeText(
                    TokenStatSpool.ACK_STATE_UNCOMMITTED + "\n" +
                        "{\"o\":\"sealed_1.jsonl\",\"t\":\"sealed_1.jsonl\",\"b\":${body1.toByteArray(Charsets.UTF_8).size},\"s\":\"$sha1\"}\n" +
                        "{\"o\":\"sealed_2.jsonl\",\"t\":\"sealed_2.jsonl\",\"b\":${body2.toByteArray(Charsets.UTF_8).size},\"s\":\"$sha2\"}\n",
                )
                TokenStatSpool.replay(context)
                val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < restoreDeadline && trash.exists()) delay(20)
                assertFalse("trash must be rolled back once the mapping is complete", trash.exists())
                awaitManifestWithout(spool, "sealed_1.jsonl")
                val evidenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var evidence: List<File> = emptyList()
                while (System.nanoTime() < evidenceDeadline && evidence.size != 2) {
                    evidence = TokenStatSpool.quarantineEvidence(context)
                    if (evidence.size != 2) delay(20)
                }
                assertEquals(2, evidence.size)
                assertTrue(evidence.any { it.isFile && it.readText() == body1 })
                assertTrue(evidence.any { it.isFile && it.readText() == body2 })
            }
        }

    @Test
    fun `scanner keeps manifest identity when corrupt uncommitted trash may hold the reused-name original`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                // 旧身份仍可能被损坏 mapping 的 UNCOMMITTED trash 持有：根目录同名新文件与
                // manifest 条目 MISMATCH 时，scanner 绝不能按 stale 移除条目（否则旧身份
                // 失去保护，回滚后重新隔离也无法与受管集合对应）。
                val oldBody = "{scanner-held-old\n"
                val newBody = "{scanner-held-new\n"
                val oldSha = sha256Hex(oldBody.toByteArray(Charsets.UTF_8))
                val manifestLine =
                    "{\"ts\":1,\"file\":\"sealed_1.jsonl\",\"bytes\":${oldBody.toByteArray(Charsets.UTF_8).size}," +
                        "\"sha256\":\"$oldSha\",\"overCap\":false}\n"
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                manifest.writeText(manifestLine)
                val trash = File(spool, "quarantine_ack_trash_${UUID.randomUUID().toString().replace("-", "")}")
                trash.mkdirs()
                File(trash, "sealed_1.jsonl").writeText(oldBody)
                File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).writeText(
                    TokenStatSpool.ACK_STATE_UNCOMMITTED + "\n" +
                        "{\"o\":\"sealed_1.jsonl\",\"t\":\"sealed_1.jsonl\",\"b\":${oldBody.toByteArray(Charsets.UTF_8).size},\"s\":\"$oldSha\"}\n" +
                        "{corrupt-json\n",
                )
                File(spool, "sealed_1.jsonl").writeText(newBody)

                TokenStatSpool.replay(context)
                // 同名新文件照常被处理进完整证据区（内容不变），但 manifest 条目必须保留
                val evidenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var quarantined = false
                while (System.nanoTime() < evidenceDeadline && !quarantined) {
                    quarantined = TokenStatSpool.quarantineEvidence(context).any {
                        it.isFile && it.readText() == newBody
                    }
                    if (!quarantined) delay(20)
                }
                assertTrue("the reused-name new file must be processed into the evidence area", quarantined)
                assertTrue("corrupt mapping must keep the trash", trash.exists())
                assertTrue(File(trash, "sealed_1.jsonl").exists())
                assertEquals(
                    "manifest entry must be retained while the old identity may be held in trash",
                    manifestLine,
                    safeManifestText(manifest),
                )
            }
        }

    @Test
    fun `stateless non-empty ack trash is visible exportable ackable and append recovers`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousCap = TokenStatSpool.totalSpoolMaxBytesForTest
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                try {
                    TokenStatSpool.MAX_SEGMENT_BYTES = 8L * 1024
                    // 总 cap 32KiB：准入上限 = 32K − min(512K, 32K−8K) = 8KiB；28KiB 无状态
                    // trash 残留必须让每次 append 的递归投影超限（占用绝不隐藏，P1-3）
                    TokenStatSpool.totalSpoolMaxBytesForTest = 32L * 1024
                    val trash = File(spool, "quarantine_ack_trash_${UUID.randomUUID().toString().replace("-", "")}")
                    trash.mkdirs()
                    val evidence = File(trash, "sealed_stuck_1.jsonl")
                    RandomAccessFile(evidence, "rw").use { it.setLength(28L * 1024) }
                    // 无状态非空 trash：maintenance fail-closed 保留（绝不删除），UI 可见
                    TokenStatSpool.replay(context)
                    delay(700)
                    assertTrue("maintenance must retain a stateless non-empty trash", trash.exists())
                    assertEquals(listOf(trash), TokenStatSpool.stuckAckTrashEvidence(context))
                    assertEquals(listOf(trash), TokenStatSpool.quarantineEvidence(context))
                    assertEquals(28L * 1024, TokenStatSpool.stuckAckTrashBytes(context))

                    // 释放前：cap 被 trash 占用 → 新统计 append 明确拒绝
                    val lines = (0 until 200).map { index ->
                        line(request("stuck-cap-$index")) to "stuck-cap-$index"
                    }
                    var rejected = 0
                    for ((text, eventId) in lines) {
                        try {
                            TokenStatSpool.append(context, text, eventId)
                        } catch (e: TokenStatsPersistenceException) {
                            rejected++
                        }
                    }
                    assertEquals(lines.size, rejected)

                    // export 将 trash 目录内容复制到唯一子目录（含状态/sidecar）
                    val base = File(root, "export-stuck").apply { mkdirs() }
                    val destination = File(base, "run-1").also { Files.createDirectory(it.toPath()) }
                    val exported = TokenStatSpool.exportQuarantineEvidence(context, destination)
                    assertTrue(exported.any { it.name == trash.name })
                    val exportedTrash = File(destination, trash.name)
                    assertTrue(exportedTrash.isDirectory)
                    assertEquals(28L * 1024, File(exportedTrash, evidence.name).length())

                    // 确认删除 stuck trash（显式授权，无需 mapping）→ 容量释放 → append 恢复
                    TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf(trash.name))
                    assertFalse("ack must delete the acknowledged stuck trash", trash.exists())
                    assertTrue(
                        TokenStatSpool.append(
                            context,
                            line(request("after-stuck-ack")),
                            "after-stuck-ack",
                        ),
                    )
                    TokenStatSpool.replay(context)
                    awaitEvent("after-stuck-ack")
                } finally {
                    TokenStatSpool.totalSpoolMaxBytesForTest = previousCap
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `rollback never overwrites an occupied slot and recovers after the slot frees`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                // 崩溃窗口 + 回滚目标被同名不同内容的新文件占用
                val oldBody = "{old-occupied-bad\n"
                val newBody = "{new-occupant-bad\n"
                val oldSha = sha256Hex(oldBody.toByteArray(Charsets.UTF_8))
                val trash = File(spool, "quarantine_ack_trash_${UUID.randomUUID().toString().replace("-", "")}")
                trash.mkdirs()
                File(trash, "sealed_1.jsonl").writeText(oldBody)
                File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).writeText(
                    TokenStatSpool.ACK_STATE_UNCOMMITTED + "\n" +
                        "{\"o\":\"sealed_1.jsonl\",\"t\":\"sealed_1.jsonl\",\"b\":${oldBody.toByteArray(Charsets.UTF_8).size},\"s\":\"$oldSha\"}\n",
                )
                // 主 manifest 仍含旧身份（ack 未提交）→ 必须回滚而非删除
                File(spool, "quarantine_skip_manifest.jsonl").writeText(
                    "{\"ts\":1,\"file\":\"sealed_1.jsonl\",\"bytes\":${oldBody.toByteArray(Charsets.UTF_8).size}," +
                        "\"sha256\":\"$oldSha\",\"overCap\":false}\n",
                )
                File(spool, "sealed_1.jsonl").writeText(newBody)

                TokenStatSpool.replay(context)
                // 回滚目标被不同内容占用：绝不覆盖，保留 trash 证据并 fail-closed
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline &&
                    File(spool, "sealed_1.jsonl").exists()
                ) {
                    delay(20)
                }
                assertTrue(
                    "occupied-slot rollback must retain the trash evidence",
                    trash.exists() && File(trash, "sealed_1.jsonl").exists(),
                )
                // 新内容未被覆盖：作为健康处理进入完整证据区（身份仍是新内容）
                val evidence = TokenStatSpool.quarantineEvidence(context)
                assertTrue(
                    "the new occupant must be processed into the evidence area untouched",
                    evidence.any { it.name.contains("sealed_1.jsonl") },
                )
                assertTrue(
                    "the new occupant content must be intact",
                    evidence.first { it.name.contains("sealed_1.jsonl") }.readText() == newBody,
                )
                assertTrue(
                    "manifest entry must be retained while the old identity is held in trash",
                    safeManifestText(File(spool, "quarantine_skip_manifest.jsonl"))?.contains("sealed_1.jsonl") == true,
                )

                // 槽位释放后（新文件已移入证据区）→ replay：回滚成功并自愈
                TokenStatSpool.replay(context)
                val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < restoreDeadline && trash.exists()) delay(20)
                assertFalse("trash must be rolled back once the slot frees", trash.exists())
                awaitManifestWithout(spool, "sealed_1.jsonl")
                val restored = TokenStatSpool.quarantineEvidence(context)
                assertEquals("both the old and the new evidence must be present", 2, restored.size)
                assertTrue(restored.any { it.readText() == oldBody })
                assertTrue(restored.any { it.readText() == newBody })
            }
        }

    @Test
    fun `ack refuses path traversal names without touching spool files`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        File(spool, "quarantine_safe_sealed_1.jsonl").writeText("safe-bad\n")
        try {
            TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf("../outside.jsonl"))
            fail("ack must refuse traversal names")
        } catch (e: IOException) {
            assertTrue("refusal must name the unsafe target", e.message!!.contains("unsafe"))
        }
        assertTrue(File(spool, "quarantine_safe_sealed_1.jsonl").exists())
    }

    @Test
    fun `first summary publish at the total cap edge with fallback sidecars keeps total bounded`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val previousCap = TokenStatSpool.totalSpoolMaxBytesForTest
                val previousAtomic = TokenStatSpool.quarantineAtomicMoveForTest
                TokenStatSpool.totalSpoolMaxBytesForTest = 24L * 1024 * 1024
                // 强制回退协议（P1-1：canonical/.new/.bak/tmp sidecar 瞬态同时存在）
                TokenStatSpool.quarantineAtomicMoveForTest = { _, _ -> false }
                try {
                    val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                    // 证据区打满（16MiB 硬 cap）→ 新损坏段必须走 summarize 路径（首次 summary 写）
                    val existing = File(spool, "quarantine_existing_sealed_1.jsonl")
                    RandomAccessFile(existing, "rw").use { it.setLength(TokenStatSpool.MAX_QUARANTINE_BYTES) }
                    // 数据总量：16MiB 证据 + 7.5MiB 损坏段 ≈ 23.5MiB，接近 24MiB 总上限边缘
                    val segment = File(spool, "sealed_2.jsonl")
                    RandomAccessFile(segment, "rw").use {
                        it.setLength(7L * 1024 * 1024 + 512L * 1024)
                    }
                    val cap = TokenStatSpool.totalSpoolMaxBytesForTest ?: 0L
                    val totalBytes: () -> Long = {
                        spool.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    }
                    TokenStatSpool.replay(context)
                    // 轮询：整个处置过程实际 top-level 总字节始终 ≤ 总上限
                    val pollDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (segment.exists() && System.nanoTime() < pollDeadline) {
                        assertTrue(
                            "total must stay within the cap while draining: ${totalBytes()}",
                            totalBytes() <= cap,
                        )
                        delay(20)
                    }
                    assertTrue("total must stay within the cap at rest: ${totalBytes()}", totalBytes() <= cap)
                    val summary = TokenStatSpool.quarantineSummaryInfo(context)
                    assertNotNull("first summary must be published at the cap edge", summary)
                    assertTrue(
                        "summary must carry the over-cap segment record",
                        File(spool, "quarantine_summary.jsonl").readText().contains("sealed_2.jsonl"),
                    )
                    // sidecar 已清理（回退发布完成）
                    assertFalse(File(spool, "quarantine_summary.jsonl.new").exists())
                    assertFalse(File(spool, "quarantine_summary.jsonl.bak").exists())
                    // 维护（ack 证据区）后 append 恢复
                    TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf(existing.name))
                    assertTrue(
                        TokenStatSpool.append(
                            context,
                            line(request("after-cap-edge-summary")),
                            "after-cap-edge-summary",
                        ),
                    )
                    TokenStatSpool.replay(context)
                    awaitEvent("after-cap-edge-summary")
                } finally {
                    TokenStatSpool.totalSpoolMaxBytesForTest = previousCap
                    TokenStatSpool.quarantineAtomicMoveForTest = previousAtomic
                }
            }
        }

    @Test
    fun `seal-failed target cleanup with FAILED and UNSUPPORTED dir sync does not advance and recovers exactly once`() =
        runBlocking {
            suspend fun scenario(result: TokenStatSpool.DirSyncResult, tag: String) {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val isolated = File(spool, "seal_failed_$tag-partial")
                isolated.writeText("{partial-$tag\n")
                File(spool, "sealed_9.jsonl").writeText(line(request("syncfail-sealfailed-healthy-$tag")) + "\n")
                // bootstrap gate(2) OK，seal_failed 删除后的目录项 sync（第 3 次）失败
                var calls = 0
                TokenStatSpool.dirSyncForTest = {
                    calls += 1
                    if (calls <= 2) TokenStatSpool.DirSyncResult.OK else result
                }
                TokenStatSpool.replay(context)
                delay(900)
                // 删除可见但未确认：本轮不推进（健康段也不排空）；隔离副本不丢证据
                assertFalse("seal-failed target deletion is visible", isolated.exists())
                assertTrue("healthy segment must stay pending while the round is not durable", File(spool, "sealed_9.jsonl").exists())
                assertEquals(0, database.tokenStatsDao().countEvents())
                // 恢复：目录项确认持久后健康段恰一次入 Room
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                TokenStatSpool.shutdownWriterForTest()
                TokenStatSpool.replay(context)
                awaitEvent("syncfail-sealfailed-healthy-$tag")
                awaitNoSealedSegments(spool)
                assertEquals(1, database.tokenStatsDao().countEvents())
            }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.FAILED, "fail") { r, t -> scenario(r, t) }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.UNSUPPORTED, "unsupported") { r, t -> scenario(r, t) }
        }

    @Test
    fun `tombstone over-cap delete with FAILED and UNSUPPORTED dir sync keeps manifest entry and recovers exactly once`() =
        runBlocking {
            suspend fun scenario(result: TokenStatSpool.DirSyncResult, tag: String) {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val existing = File(spool, "quarantine_existing_$tag.jsonl")
                RandomAccessFile(existing, "rw").use { it.setLength(TokenStatSpool.MAX_QUARANTINE_BYTES) }
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                File(spool, "sealed_2.jsonl").writeText("{$tag-managed-bad\n")
                // 阶段 1：删除失败 + 证据区已满 → over-cap tombstone 条目（正常协议）
                TokenStatSpool.segmentDeleteForTest = { file ->
                    if (file.name == "sealed_2.jsonl") false else null
                }
                TokenStatSpool.replay(context)
                val entryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < entryDeadline &&
                    safeManifestText(manifest)?.contains("sealed_2.jsonl") != true
                ) {
                    delay(20)
                }
                assertTrue(safeManifestText(manifest)?.contains("sealed_2.jsonl") == true)
                assertEquals(1, TokenStatSpool.quarantineSummaryInfo(context)!!.recordCount)
                // 阶段 1 的 drain 可能仍在收尾（摘要/条目发布后的队列复扫 sync）——先静默
                // 至 drain 完全结束，阶段 2 的计数 seam 才能从确定的第一笔 sync 开始
                delay(300)
                // 阶段 2：维护删除成功但目录项 sync 失败（bootstrap gate 已在阶段 1 确认；
                // 本阶段第 1 次 sync 是 manifest 严格读取，第 2 次才是删除的目录项）→ manifest
                // 条目保留（可重试记录）、本轮不推进
                TokenStatSpool.segmentDeleteForTest = null
                var calls = 0
                TokenStatSpool.dirSyncForTest = {
                    calls += 1
                    if (calls == 2) result else TokenStatSpool.DirSyncResult.OK
                }
                TokenStatSpool.shutdownWriterForTest()
                TokenStatSpool.replay(context)
                delay(900)
                assertTrue(
                    "manifest entry must be retained while the deletion is unconfirmed",
                    safeManifestText(manifest)?.contains("sealed_2.jsonl") == true,
                )
                assertFalse("over-cap segment deletion is visible", File(spool, "sealed_2.jsonl").exists())
                assertEquals(0, database.tokenStatsDao().countEvents())
                // 恢复：确认“消失”持久后条目幂等移除，摘要记录不重复
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                TokenStatSpool.shutdownWriterForTest()
                TokenStatSpool.replay(context)
                awaitManifestWithout(spool, "sealed_2.jsonl")
                assertEquals(1, TokenStatSpool.quarantineSummaryInfo(context)!!.recordCount)
                assertEquals(0, database.tokenStatsDao().countEvents())
            }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.FAILED, "fail") { r, t -> scenario(r, t) }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.UNSUPPORTED, "unsupported") { r, t -> scenario(r, t) }
        }

    @Test
    fun `ack staging with FAILED and UNSUPPORTED dir sync fails closed keeps evidence and recovers exactly once`() =
        runBlocking {
            suspend fun scenario(result: TokenStatSpool.DirSyncResult, tag: String) {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                // 两个失败点：trash 目录创建后的目录项 sync（failCall=1）、首个证据移动后的
                // 目录项 sync（failCall=2，跨 spool 根与 trash 两个目录）
                for (failCall in 1..2) {
                    spool.deleteRecursively()
                    spool.mkdirs()
                    TokenStatSpool.clearPendingStateForTest()
                    File(spool, "sealed_1.jsonl").writeText("{$tag-ackstage-bad\n")
                    TokenStatSpool.segmentRenameForTest = { _, to ->
                        if (to.name.startsWith("quarantine_")) false else null
                    }
                    TokenStatSpool.replay(context)
                    val entryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < entryDeadline &&
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") != true
                    ) {
                        delay(20)
                    }
                    assertTrue(
                        "phase-1 tombstone entry must exist before ack",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                    )
                    // 阶段 1 的 drain 可能仍在收尾（tombstone 发布后的队列复扫 sync）——先
                    // 静默至 drain 完全结束，ack 的计数 seam 才能从确定的第一笔 sync 开始
                    delay(300)
                    TokenStatSpool.segmentRenameForTest = null
                    var calls = 0
                    TokenStatSpool.dirSyncForTest = {
                        calls += 1
                        if (calls == failCall) result else TokenStatSpool.DirSyncResult.OK
                    }
                    try {
                        TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf("sealed_1.jsonl"))
                        fail("ack must fail when a staging boundary dir sync is not OK; calls=$calls failCall=$failCall result=$result")
                    } catch (e: IOException) {
                    }
                    // 操作失败、状态保留：证据未丢、manifest 未改
                    assertTrue("managed evidence must stay in place", File(spool, "sealed_1.jsonl").exists())
                    assertTrue(
                        "manifest entry must survive the failed ack",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                    )
                }
                // 清理失败迭代留下的空 trash（mkdir 已可见但目录项 sync 未确认；维护入口对
                // 空 trash 同样安全删除，此处等价地清理后重试）
                spool.listFiles().orEmpty()
                    .filter { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") }
                    .forEach { it.deleteRecursively() }
                // 恢复：目录项 sync OK 后 ack 恰一次完成（证据删除、条目移除）
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf("sealed_1.jsonl"))
                assertFalse(File(spool, "sealed_1.jsonl").exists())
                assertFalse(safeManifestText(manifest)?.contains("sealed_1.jsonl") == true)
                assertTrue(
                    "no trash residue after a successful ack",
                    spool.listFiles().orEmpty().none { it.name.startsWith("quarantine_ack_trash_") },
                )
            }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.FAILED, "fail") { r, t -> scenario(r, t) }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.UNSUPPORTED, "unsupported") { r, t -> scenario(r, t) }
        }

    @Test
    fun `ack commit flip with FAILED and UNSUPPORTED dir sync fails closed retains uncommitted trash and maintenance recovers it`() =
        runBlocking {
            suspend fun scenario(result: TokenStatSpool.DirSyncResult, tag: String) {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                val sealedBody = "{$tag-flip-bad\n"
                val areaBody = "area-body-$tag\n"
                File(spool, "sealed_1.jsonl").writeText(sealedBody)
                File(spool, "quarantine_area_$tag.jsonl").writeText(areaBody)
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    if (to.name.startsWith("quarantine_")) false else null
                }
                TokenStatSpool.replay(context)
                val entryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < entryDeadline &&
                    safeManifestText(manifest)?.contains("sealed_1.jsonl") != true
                ) {
                    delay(20)
                }
                assertTrue(
                    "phase-1 tombstone entry must exist before ack",
                    safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                )
                // 阶段 1 的 drain 可能仍在收尾（tombstone 发布后的队列复扫 sync）——先静默
                // 至 drain 完全结束，ack 的计数 seam 才能从确定的第一笔 sync 开始
                delay(300)
                TokenStatSpool.segmentRenameForTest = null
                // 第 11 次 sync = COMMITTED 翻转的暂存目录项（manifest 严格读取 1 + mkdir 1
                // + staging 4 + 状态文件 2 + manifest 重写 2 + 翻转 staging 1）——翻转未确认
                // 持久 → ack 失败、状态保留
                var calls = 0
                TokenStatSpool.dirSyncForTest = {
                    calls += 1
                    if (calls == 11) result else TokenStatSpool.DirSyncResult.OK
                }
                try {
                    TokenStatSpool.acknowledgeAndDeleteQuarantine(
                        context,
                        setOf("sealed_1.jsonl", "quarantine_area_$tag.jsonl"),
                    )
                    fail("ack must fail when the commit flip is not durable; calls=$calls result=$result")
                } catch (e: IOException) {
                    assertTrue(e.message!!.contains("commit"))
                }
                val trashDirs = spool.listFiles().orEmpty()
                    .filter { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") }
                assertEquals("uncommitted trash must be retained", 1, trashDirs.size)
                val trash = trashDirs.single()
                val state = File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME)
                assertTrue(
                    "state must remain UNCOMMITTED for maintenance rollback",
                    state.readText().startsWith(TokenStatSpool.ACK_STATE_UNCOMMITTED),
                )
                assertTrue("staged managed evidence stays in trash", File(trash, "sealed_1.jsonl").exists())
                assertTrue("staged area evidence stays in trash", File(trash, "quarantine_area_$tag.jsonl").exists())
                assertTrue(
                    "manifest entries were already published",
                    safeManifestText(manifest)?.contains("sealed_1.jsonl") != true,
                )
                // 维护恢复：UNCOMMITTED 按 mapping+identity 回滚 → 证据各恰一次回到原路径
                // （损坏 sealed 随后被扫描器重新隔离为证据）
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                TokenStatSpool.replay(context)
                val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < restoreDeadline &&
                    spool.listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") }
                ) {
                    delay(20)
                }
                assertTrue(
                    "trash must be rolled back by maintenance",
                    spool.listFiles().orEmpty().none { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") },
                )
                val evidence = TokenStatSpool.quarantineEvidence(context)
                assertEquals(1, evidence.count { it.readText() == sealedBody })
                assertEquals(1, evidence.count { it.readText() == areaBody })
                assertEquals(0, database.tokenStatsDao().countEvents())
            }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.FAILED, "fail") { r, t -> scenario(r, t) }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.UNSUPPORTED, "unsupported") { r, t -> scenario(r, t) }
        }

    @Test
    fun `ack trash delete with FAILED and UNSUPPORTED dir sync fails closed and retry is idempotent`() =
        runBlocking {
            suspend fun scenario(result: TokenStatSpool.DirSyncResult, tag: String) {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val evidence = File(spool, "quarantine_ackdelete_$tag.jsonl")
                evidence.writeText("{$tag-ackdelete\n")
                // 第 8 次 sync = COMMITTED 翻转后 trash 删除的目录项（mkdir 1 + staging 2 +
                // 状态文件 2 + 翻转 2 + 删除 sync 1）——删除可见但未确认 → ack 失败
                var calls = 0
                TokenStatSpool.dirSyncForTest = {
                    calls += 1
                    if (calls == 8) result else TokenStatSpool.DirSyncResult.OK
                }
                try {
                    TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf(evidence.name))
                    fail("ack must fail when the trash deletion is not durable")
                } catch (e: IOException) {
                    assertTrue(e.message!!.contains("deletion not durable"))
                }
                assertFalse("trash deletion is visible", evidence.exists())
                assertTrue(
                    "no trash residue",
                    spool.listFiles().orEmpty().none { it.name.startsWith("quarantine_ack_trash_") },
                )
                // 重试幂等：证据已可见删除，再次 ack 无操作成功（崩溃后 COMMITTED trash 重现
                // 由维护有界补删）
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf(evidence.name))
                assertFalse(evidence.exists())
            }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.FAILED, "fail") { r, t -> scenario(r, t) }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.UNSUPPORTED, "unsupported") { r, t -> scenario(r, t) }
        }

    @Test
    fun `tombstone manifest publish with FAILED and UNSUPPORTED dir sync fails closed keeps old manifest and recovers exactly once`() =
        runBlocking {
            suspend fun scenario(result: TokenStatSpool.DirSyncResult, tag: String) {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                File(spool, "sealed_1.jsonl").writeText("{$tag-publish-bad\n")
                File(spool, "sealed_2.jsonl").writeText(line(request("syncfail-publish-healthy-$tag")) + "\n")
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    if (to.name.startsWith("quarantine_")) false else null
                }
                // bootstrap gate(2) OK，tombstone manifest 严格发布的暂存目录项 sync（第 3 次）
                // 失败 → 发布 FAILED（不是 RECORDED）：manifest 未发布、段保留、健康段不排空
                var calls = 0
                TokenStatSpool.dirSyncForTest = {
                    calls += 1
                    if (calls <= 2) TokenStatSpool.DirSyncResult.OK else result
                }
                TokenStatSpool.replay(context)
                delay(900)
                assertFalse("manifest must not be published", manifest.exists())
                assertTrue("original segment must be retained", File(spool, "sealed_1.jsonl").exists())
                assertEquals(0, database.tokenStatsDao().countEvents())
                // 恢复：目录项 sync OK 后按正常协议完成——损坏段作为证据恰一次隔离、健康段
                // 恰一次入 Room（manifest 从未发布，无重复条目）
                TokenStatSpool.segmentRenameForTest = null
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                TokenStatSpool.shutdownWriterForTest()
                TokenStatSpool.replay(context)
                awaitEvent("syncfail-publish-healthy-$tag")
                awaitNoSealedSegments(spool)
                assertEquals(1, database.tokenStatsDao().countEvents())
                assertEquals(
                    1,
                    TokenStatSpool.quarantineEvidence(context).count { it.readText() == "{$tag-publish-bad\n" },
                )
            }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.FAILED, "fail") { r, t -> scenario(r, t) }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.UNSUPPORTED, "unsupported") { r, t -> scenario(r, t) }
        }

    @Test
    fun `seal duplicate cleanup with FAILED and UNSUPPORTED dir sync fails closed until confirmed and recovers exactly once`() =
        runBlocking {
            suspend fun scenario(result: TokenStatSpool.DirSyncResult, tag: String) {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val lineA = line(request("syncfail-dup-a-$tag"))
                val lineB = line(request("syncfail-dup-b-$tag"))
                File(spool, "active.jsonl").writeText(lineA + "\n")
                File(spool, "sealed_1.jsonl").writeText(lineA + "\n") // copy 回退崩溃窗口副本
                // bootstrap gate(2) OK，重复副本删除后的目录项 sync（第 3 次）失败 → 恢复
                // 未确认：append fail-closed，绝不带着“可能还有重复”的状态写入
                var calls = 0
                TokenStatSpool.dirSyncForTest = {
                    calls += 1
                    if (calls <= 2) TokenStatSpool.DirSyncResult.OK else result
                }
                assertFalse(TokenStatSpool.append(context, lineB, "syncfail-dup-b-$tag"))
                assertTrue("active is intact", File(spool, "active.jsonl").readText() == lineA + "\n")
                assertFalse("duplicate removal is visible", File(spool, "sealed_1.jsonl").exists())
                assertEquals(0, database.tokenStatsDao().countEvents())
                // 恢复：无重复 → 正常追加，两事件各恰一次入 Room
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                assertTrue(TokenStatSpool.append(context, lineB, "syncfail-dup-b-$tag"))
                TokenStatSpool.replay(context)
                awaitEvent("syncfail-dup-a-$tag")
                awaitEvent("syncfail-dup-b-$tag")
                awaitNoSealedSegments(spool)
                assertEquals(2, database.tokenStatsDao().countEvents())
                assertEquals(
                    setOf("syncfail-dup-a-$tag", "syncfail-dup-b-$tag"),
                    database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                )
            }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.FAILED, "fail") { r, t -> scenario(r, t) }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.UNSUPPORTED, "unsupported") { r, t -> scenario(r, t) }
        }

    // ==== P2 终审：目录遗漏修复（回滚删除/反向 rename 的严格目录同步、mapping 身份捕获） ====

    @Test
    fun `seal rollback deletion sync failure fails closed and next append re-bootstraps before writing`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("rollback-sync-a"))
                val lineB = line(request("rollback-sync-b"))
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    // 硬链接创建目录项 OK → active 删除失败 → 回滚删除链接：删除后的目录项
                    // sync（第 4 次）失败（P2 终审）→ 回滚未确认持久、gate 同步失效、seal 明确失败
                    var dirSyncCalls = 0
                    TokenStatSpool.sealActiveDeleteForTest = { false }
                    TokenStatSpool.dirSyncForTest = {
                        dirSyncCalls += 1
                        // 1-2 bootstrap gate；3 链接创建目录项 OK；4 回滚删除的目录项 FAILED
                        if (dirSyncCalls == 4) TokenStatSpool.DirSyncResult.FAILED
                        else TokenStatSpool.DirSyncResult.OK
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        assertFalse(
                            "append must fail when the seal rollback deletion is not durable",
                            TokenStatSpool.append(context, lineB, "rollback-sync-b"),
                        )
                    } finally {
                        TokenStatSpool.sealActiveDeleteForTest = null
                        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    }
                    assertEquals("rollback deletion must be sync-confirmed (P2)", 4, dirSyncCalls)
                    assertEquals("active must be retained", lineA + "\n", File(spool, "active.jsonl").readText())
                    assertFalse(
                        "rolled-back seal must leave no sealed residue",
                        spool.listFiles().orEmpty().any { it.name.startsWith("sealed_") },
                    )
                    // 恢复：gate 已失效——下一次 append 先 bootstrap 重新确认目录项再正常写入
                    assertTrue(TokenStatSpool.append(context, lineB, "rollback-sync-b"))
                    TokenStatSpool.replay(context)
                    awaitEvent("rollback-sync-a")
                    awaitEvent("rollback-sync-b")
                    awaitNoSealedSegments(spool)
                    assertEquals(2, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.sealActiveDeleteForTest = null
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `ack rollback mixed move success with sync failure writes complete mapping from actual locations and maintenance recovers`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val body1 = "{p23-a\n"
                val body2 = "{p23-b\n"
                val body3 = "{p23-c\n"
                val ev1 = File(spool, "quarantine_ord_a_sealed_1.jsonl").apply { writeText(body1) }
                val ev2 = File(spool, "quarantine_ord_b_sealed_2.jsonl").apply { writeText(body2) }
                val ev3 = File(spool, "quarantine_ord_c_sealed_3.jsonl").apply { writeText(body3) }
                // ev1/ev2 成功 stage；ev3 stage 失败触发回滚。回滚时 ev2 移回失败（留在 trash），
                // ev1 移回成功但目录项 sync 失败（第 7 次）——此时再写 UNCOMMITTED 状态时
                // ev1 已不在 trash，mapping 身份必须从实际所在位置（original）捕获（P2 终审），
                // 绝不能从已移走的 target 盲读（会得到 0 字节/空哈希甚至写失败）
                var calls = 0
                TokenStatSpool.ackAtomicMoveForTest = { from, to ->
                    when {
                        to.name == ev3.name -> false
                        to.name == ev2.name && from.parentFile?.name?.startsWith("quarantine_ack_trash_") == true ->
                            false
                        else -> null
                    }
                }
                TokenStatSpool.dirSyncForTest = {
                    calls += 1
                    // 1 trash 创建；2-5 stage；6-7 回滚 ev1 的双目录 sync（第 7 次失败）
                    if (calls == 7) TokenStatSpool.DirSyncResult.FAILED
                    else TokenStatSpool.DirSyncResult.OK
                }
                try {
                    try {
                        TokenStatSpool.acknowledgeAndDeleteQuarantine(
                            context,
                            setOf(ev1.name, ev2.name, ev3.name),
                        )
                        fail("ack must report the staging failure")
                    } catch (e: IOException) {
                    }
                    val trashDirs = spool.listFiles().orEmpty()
                        .filter { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") }
                    assertEquals("uncommitted trash must be retained", 1, trashDirs.size)
                    val trash = trashDirs.single()
                    assertTrue("ev2 rollback failed so it stays in trash", File(trash, ev2.name).exists())
                    assertTrue("ev1 rollback move is visible at the original path", ev1.exists())
                    assertTrue("ev3 was never staged", ev3.exists())
                    // 状态 mapping 必须完整且身份正确（P2 终审：从实际所在位置捕获）
                    val stateFile = File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME)
                    assertTrue("state must be written after the not-durable rollback", stateFile.isFile)
                    val lines = stateFile.readText().lineSequence().filter { it.isNotBlank() }.toList()
                    assertEquals(TokenStatSpool.ACK_STATE_UNCOMMITTED, lines.first())
                    assertEquals("mapping must cover both staged files", 2, lines.size - 1)
                    val entryA = JSONObject(lines[1])
                    assertEquals(ev1.name, entryA.getString("o"))
                    assertEquals(body1.toByteArray(Charsets.UTF_8).size.toLong(), entryA.getLong("b"))
                    assertEquals(sha256Hex(body1.toByteArray(Charsets.UTF_8)), entryA.getString("s"))
                    val entryB = JSONObject(lines[2])
                    assertEquals(ev2.name, entryB.getString("o"))
                    assertEquals(body2.toByteArray(Charsets.UTF_8).size.toLong(), entryB.getLong("b"))
                    assertEquals(sha256Hex(body2.toByteArray(Charsets.UTF_8)), entryB.getString("s"))
                    // UI 可管理：stuck trash 作为受管证据可见
                    assertEquals(listOf(trash), TokenStatSpool.stuckAckTrashEvidence(context))
                    // 维护按 mapping+identity 完整回滚：trash 删除、全部证据回到证据区
                    TokenStatSpool.ackAtomicMoveForTest = null
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    TokenStatSpool.replay(context)
                    val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < restoreDeadline &&
                        spool.listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") }
                    ) {
                        delay(20)
                    }
                    assertTrue(
                        "trash must be resolved by maintenance once moves recover",
                        spool.listFiles().orEmpty().none { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") },
                    )
                    val evidence = TokenStatSpool.quarantineEvidence(context)
                    assertEquals(3, evidence.size)
                    assertTrue(evidence.any { it.readText() == body1 })
                    assertTrue(evidence.any { it.readText() == body2 })
                    assertTrue(evidence.any { it.readText() == body3 })
                    assertEquals(emptyList<File>(), TokenStatSpool.stuckAckTrashEvidence(context))
                    assertEquals(0, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.ackAtomicMoveForTest = null
                    TokenStatSpool.dirSyncForTest = null
                }
            }
        }

    // ==== P1 关键链路：drain 请求合并（丢失唤醒修复）====

}
