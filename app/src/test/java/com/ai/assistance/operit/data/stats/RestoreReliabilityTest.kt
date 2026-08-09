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
internal class RestoreReliabilityTest : TokenStatReliabilityTestBase() {
    @Test
    fun `restore barrier waits for segment read and old task cannot insert afterward`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        File(spool, "sealed_1.jsonl").writeText(line(request("old-before-restore")) + "\n")
        val read = CountDownLatch(1)
        val release = CountDownLatch(1)
        TokenStatSpool.afterSegmentReadForTest = {
            read.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        TokenStatSpool.replay(context)
        assertTrue(read.await(10, TimeUnit.SECONDS))

        val restore = async {
            TokenStatSpool.withExclusiveSnapshotAccess(
                context,
                drainBefore = false,
                clearAfter = true,
            ) {
                // Simulates the restored database contents replacing everything inserted before
                // this exclusive section. No old worker may insert after this point.
                database.tokenStatsDao().deleteAllEvents()
            }
        }
        delay(100)
        assertFalse("restore must wait for the in-flight old drain", restore.isCompleted)
        release.countDown()
        restore.await()
        delay(100)
        assertNull(database.tokenStatsDao().getEvent("old-before-restore"))
    }

    @Test
    fun `deferred restore commit failure preserves old and new request accounting`() = runBlocking {
        val oldRequest = request("old-request-after-commit-failure")
        val oldEpoch = oldRequest.sessionEpoch
        try {
            TokenStatSpool.withExclusiveRestoreAccess(
                context = context,
                prepareBeforeCommit = {},
                commitReplacement = { throw IOException("REPLACING write failed") },
            ) {
                fail("replacement must not run when commit fails")
            }
            fail("commit failure must propagate")
        } catch (e: IOException) {
            assertEquals("REPLACING write failed", e.message)
        }

        assertEquals(oldEpoch, TokenStatSpool.captureRestoreEpoch())
        assertTrue(TokenStatSpool.isAcceptingEvents())
        TokenTrackingAIService.recordSafely(context, oldRequest)
        TokenTrackingAIService.recordSafely(context, request("new-request-after-commit-failure"))
        awaitEvent("old-request-after-commit-failure")
        awaitEvent("new-request-after-commit-failure")
        assertEquals(2, database.tokenStatsDao().countEvents())
    }

    @Test
    fun `restore with a live Room insert fails bounded before replacement and later restore is clean`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val previousInsert = TokenStatSpool.insertTimeoutMs
                val previousQuiesce = TokenStatSpool.exclusiveQuiesceTimeoutMs
                TokenStatSpool.insertTimeoutMs = 100
                TokenStatSpool.exclusiveQuiesceTimeoutMs = 150
                try {
                    val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                    val lineA = line(request("evt-live-a"))
                    File(spool, "sealed_1.jsonl").writeText(lineA + "\n")

                    // 真实 Room + 真实 spool 文件；DAO 层在 fence 之后、事务写入前挂起
                    // （模拟 SQLite 已持有连接、忽略中断的旧 insert），释放后委托真实 DAO
                    val realDao = database.tokenStatsDao()
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val blockingDao = mock<TokenStatsDao>()
                    whenever(blockingDao.insertIdentityIfAbsent(any())).thenAnswer { invocation ->
                        entered.countDown()
                        // SQLite 忽略中断：阻塞中的 insert 必须继续等待，不能被 task.cancel 打断
                        while (true) {
                            try {
                                if (release.await(1, TimeUnit.SECONDS)) break
                            } catch (_: InterruptedException) {
                            }
                        }
                        runBlocking { realDao.insertIdentityIfAbsent(invocation.getArgument(0)) }
                    }
                    whenever(blockingDao.upsertDisplayModel(any())).thenAnswer { invocation ->
                        runBlocking { realDao.upsertDisplayModel(invocation.getArgument(0)) }
                    }
                    whenever(blockingDao.insertEventIfNotResetCovered(any())).thenAnswer { invocation ->
                        runBlocking { realDao.insertEventIfNotResetCovered(invocation.getArgument(0)) }
                    }
                    val proxy = mock<AppDatabase>()
                    whenever(proxy.tokenStatsDao()).thenReturn(blockingDao)
                    TokenStatsLedger.databaseProvider = { proxy }

                    TokenStatSpool.replay(context)
                    assertTrue(
                        "insert must have passed the fence and be inside Room",
                        entered.await(10, TimeUnit.SECONDS)
                    )
                    assertEquals(1, TokenStatSpool.activeInsertCountForTest())

                    // insert timeout 已释放 lifecycleMutex；restore 门闩必须有界失败，
                    // 替换块绝不执行（数据库不被覆盖/污染），durable 段保留
                    val startedRestore = System.nanoTime()
                    try {
                        TokenStatSpool.withExclusiveSnapshotAccess(
                            context,
                            drainBefore = false,
                            clearAfter = true,
                        ) {
                            fail("replacement must never run while an old insert is live")
                        }
                        fail("restore must fail bounded")
                    } catch (e: IOException) {
                        assertTrue("restore must report the live insert", e.message!!.contains("still active"))
                    }
                    val restoreElapsedMs = (System.nanoTime() - startedRestore) / 1_000_000
                    assertTrue("restore must be bounded: ${restoreElapsedMs}ms", restoreElapsedMs < 10_000)
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    assertTrue(
                        "durable segment must survive a failed restore",
                        File(spool, "sealed_1.jsonl").exists()
                    )

                    // 释放旧 insert：它只能写入未被替换的旧库；registry 清空后重试 restore 干净通过
                    release.countDown()
                    awaitEvent("evt-live-a")
                    assertEquals(1, database.tokenStatsDao().countEvents())
                    assertEquals(0, TokenStatSpool.activeInsertCountForTest())

                    TokenStatSpool.withExclusiveSnapshotAccess(
                        context,
                        drainBefore = false,
                        clearAfter = true,
                    ) {
                        // 模拟恢复数据库替换：旧事件必须已从排空路径彻底消失
                        database.tokenStatsDao().deleteAllEvents()
                    }
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    // 自定义 SQLiteDriver 的 Room 没有 SupportSQLiteOpenHelper：直接复用 JVM 驱动
                    // 打开同一数据库文件校验完整性
                    val integrity =
                        JdbcSQLiteDriver().open(File(root, "app_database").absolutePath).use { connection ->
                            connection.prepare("PRAGMA integrity_check").use { statement ->
                                statement.step()
                                statement.getText(0)
                            }
                        }
                    assertEquals("restored database must pass integrity check", "ok", integrity)
                } finally {
                    TokenStatsLedger.databaseProvider = { database }
                    TokenStatSpool.resetExecutorsForTest()
                    TokenStatSpool.insertTimeoutMs = previousInsert
                    TokenStatSpool.exclusiveQuiesceTimeoutMs = previousQuiesce
                }
            }
        }

    @Test
    fun `restore cleanup deletion failure is explicit`() = runBlocking {
        File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs(); resolve("active.jsonl").writeText("x") }
        TokenStatSpool.spoolDeleteForTest = { false }
        try {
            TokenStatSpool.withExclusiveSnapshotAccess(
                context,
                drainBefore = false,
                clearAfter = true,
            ) { }
            fail("restore cleanup failure must propagate")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("cleanup failed"))
        }
    }

    @Test
    fun `snapshot barrier moves spool only event into Room exactly once`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        File(spool, "sealed_1.jsonl").writeText(line(request("spool-only-backup")) + "\n")

        TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = true) {
            assertEquals(1, database.tokenStatsDao().countEvents())
            assertTrue(spool.listFiles().orEmpty().none { it.name.startsWith("sealed_") })
        }
        // A replay after the snapshot/restore boundary is idempotent and cannot duplicate it.
        TokenStatSpool.replay(context)
        delay(100)
        assertEquals(1, database.tokenStatsDao().countEvents())
    }

    @Test
    fun `snapshot fails before block while quarantine evidence would be excluded`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        File(spool, "sealed_1.jsonl").writeText("{corrupt snapshot evidence\n")
        var blockRan = false

        Mockito.mockStatic(AppLogger::class.java).use {
            try {
                TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = true) {
                    blockRan = true
                }
                fail("snapshot must not silently omit quarantine evidence")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("quarantine evidence"))
            }
        }

        assertFalse("snapshot block must not run", blockRan)
        val evidence = TokenStatSpool.quarantineEvidence(context)
        assertEquals(1, evidence.size)
        assertTrue(evidence.single().readText().contains("corrupt snapshot evidence"))
    }

    @Test
    fun `manifest read failure fails closed scanner ack and append and recovers after restore`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                File(spool, "sealed_1.jsonl").writeText("{managed-bad\n")
                // 先正常建立受管失败段（重命名失败 → tombstone 记录落盘）
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

                    // 注入 manifest 读取失败：scanner/容量/维护全部中止退避，受管段不处理
                    TokenStatSpool.metadataReadErrorForTest = { file ->
                        file.name == "quarantine_skip_manifest.jsonl"
                    }
                    TokenStatSpool.replay(context)
                    delay(700)
                    assertTrue(
                        "managed segment must not be processed while the manifest is unreadable",
                        File(spool, "sealed_1.jsonl").exists(),
                    )
                    assertTrue(
                        "manifest entry must be retained",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                    )

                    // ack 报错：manifest 不可读时不能确认删除
                    try {
                        TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf("sealed_1.jsonl"))
                        fail("ack must fail while the manifest is unreadable")
                    } catch (e: IOException) {
                    }
                    assertTrue(
                        "entry must survive a failed ack",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                    )

                    // 容量检查 fail-closed：append 不发布、不声称 durable
                    assertFalse(
                        "append must fail closed while the manifest is unreadable",
                        TokenStatSpool.append(
                            context,
                            line(request("fail-closed-append")),
                            "fail-closed-append",
                        ),
                    )
                    assertFalse(
                        File(spool, "active.jsonl").isFile && File(spool, "active.jsonl").length() > 0L,
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())

                    // 恢复后正常：append 重新 durable，ack 按身份删除并清理记录
                    TokenStatSpool.metadataReadErrorForTest = null
                    assertTrue(
                        TokenStatSpool.append(
                            context,
                            line(request("after-manifest-recovery")),
                            "after-manifest-recovery",
                        ),
                    )
                    TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf("sealed_1.jsonl"))
                    assertFalse(File(spool, "sealed_1.jsonl").exists())
                    assertFalse(
                        "entry must be removed after a successful ack",
                        safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                    )
                    TokenStatSpool.replay(context)
                    awaitEvent("after-manifest-recovery")
                } finally {
                    TokenStatSpool.metadataReadErrorForTest = null
                    TokenStatSpool.segmentRenameForTest = null
                }
            }
        }

    @Test
    fun `ordinary evidence stage failure with rollback failure keeps uncommitted trash and maintenance restores it`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                // 普通（非受管）quarantine 证据：从不在 tombstone manifest 中。P1-1 修复前，
                // 维护会因 manifest 缺失推断“已提交”而删除 trash（丢失未确认的证据）。
                val ev1 = File(spool, "quarantine_ord_a_sealed_1.jsonl").apply { writeText("{ord-a\n") }
                val ev2 = File(spool, "quarantine_ord_b_sealed_2.jsonl").apply { writeText("{ord-b\n") }
                // 第 2 个文件 stage rename 失败 + 第 1 个文件回滚失败 → ack 报错，trash 保留
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    when {
                        to.parentFile?.name?.startsWith("quarantine_ack_trash_") == true &&
                            to.name == ev2.name -> false
                        to.parentFile?.name != null &&
                            !to.parentFile!!.name.startsWith("quarantine_ack_trash_") &&
                            to.name == ev1.name -> false
                        else -> null
                    }
                }
                try {
                    try {
                        TokenStatSpool.acknowledgeAndDeleteQuarantine(
                            context,
                            setOf(ev1.name, ev2.name),
                        )
                        fail("ack must report the staging failure")
                    } catch (e: IOException) {
                    }
                    val trashDirs = spool.listFiles().orEmpty()
                        .filter { it.isDirectory && it.name.startsWith("quarantine_ack_trash_") }
                    assertEquals(1, trashDirs.size)
                    val trash = trashDirs.single()
                    assertTrue("staged evidence must stay in trash", File(trash, ev1.name).exists())
                    assertFalse(ev1.exists())
                    assertTrue("ev2 stage never happened", ev2.exists())

                    // replay 维护（回滚 rename 仍被注入失败）：绝不删除 trash 与证据
                    TokenStatSpool.replay(context)
                    delay(700)
                    assertTrue("maintenance must never delete uncommitted ordinary evidence", trash.exists())
                    assertTrue(File(trash, ev1.name).exists())
                    assertFalse("no partial rollback may occur", ev1.exists())

                    // 恢复 rename 能力后 replay：维护按 mapping+identity 回滚，证据不删最终恢复
                    TokenStatSpool.segmentRenameForTest = null
                    TokenStatSpool.replay(context)
                    val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < restoreDeadline && trash.exists()) delay(20)
                    assertFalse("trash must be gone after a successful maintenance rollback", trash.exists())
                    assertTrue("ev1 must be restored to the evidence area", ev1.exists())
                    assertTrue("ev2 must stay in the evidence area", ev2.exists())
                    val evidence = TokenStatSpool.quarantineEvidence(context)
                    assertEquals(setOf(ev1.name, ev2.name), evidence.map { it.name }.toSet())
                } finally {
                    TokenStatSpool.segmentRenameForTest = null
                }
            }
        }

    @Test
    fun `export fails closed when the spool root enumeration fails and recovers after the seam is restored`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                // 两个非空 stuck trash + 一个普通隔离文件：根枚举失败时 export 绝不能
                // 成功遗漏任何证据（P1-6）
                val trashA = File(spool, "quarantine_ack_trash_${UUID.randomUUID().toString().replace("-", "")}")
                trashA.mkdirs()
                val evidenceA = File(trashA, "sealed_1.jsonl")
                evidenceA.writeText("{root-null-a\n")
                val trashB = File(spool, "quarantine_ack_trash_${UUID.randomUUID().toString().replace("-", "")}")
                trashB.mkdirs()
                val evidenceB = File(trashB, "sealed_2.jsonl")
                evidenceB.writeText("{root-null-b\n")
                val quarantineFile = File(spool, "quarantine_sealed_3.jsonl")
                quarantineFile.writeText("{root-null-ev\n")
                val base = File(root, "export-root-null").apply { mkdirs() }
                val destination = File(base, "run-1").also { Files.createDirectory(it.toPath()) }
                TokenStatSpool.directoryListingForTest = { dir ->
                    if (dir == spool) null else dir.listFiles()
                }
                try {
                    try {
                        TokenStatSpool.exportQuarantineEvidence(context, destination)
                        fail("export must fail when the spool root enumeration fails")
                    } catch (e: IOException) {
                        assertTrue("failure must name the enumeration error", e.message!!.contains("enumerate"))
                    }
                    // 源证据全部保留
                    assertTrue(trashA.exists())
                    assertTrue(evidenceA.exists())
                    assertTrue(trashB.exists())
                    assertTrue(evidenceB.exists())
                    assertTrue(quarantineFile.exists())
                    // partial 目标未报告成功；UI 清理 helper 确认本轮目标被清除
                    assertTrue(QuarantineExportCleanup.deleteRecursively(destination))
                    assertFalse(destination.exists())
                } finally {
                    TokenStatSpool.directoryListingForTest = null
                }
                // 恢复 seam 后完整 export 含全部证据（stuck trash 子目录 + 隔离文件）
                val destination2 = File(base, "run-2").also { Files.createDirectory(it.toPath()) }
                val exported = TokenStatSpool.exportQuarantineEvidence(context, destination2)
                assertTrue(exported.any { it.name == trashA.name })
                assertTrue(exported.any { it.name == trashB.name })
                assertTrue(exported.any { it.name == quarantineFile.name })
                val exportedTrash = File(destination2, trashA.name)
                assertTrue(exportedTrash.isDirectory)
                assertTrue(File(exportedTrash, evidenceA.name).readText() == "{root-null-a\n")
            }
        }

    @Test
    fun `export fails closed when an ack trash directory enumeration fails and recovers after the seam is restored`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val trash = File(spool, "quarantine_ack_trash_${UUID.randomUUID().toString().replace("-", "")}")
                trash.mkdirs()
                val evidence = File(trash, "sealed_1.jsonl")
                evidence.writeText("{child-null-evidence\n")
                // 普通隔离文件先于 trash 被复制：child 枚举失败时目标目录已含部分内容
                val quarantineFile = File(spool, "quarantine_sealed_2.jsonl")
                quarantineFile.writeText("{child-null-quarantine\n")
                val base = File(root, "export-child-null").apply { mkdirs() }
                val destination = File(base, "run-1").also { Files.createDirectory(it.toPath()) }
                TokenStatSpool.directoryListingForTest = { dir ->
                    if (dir == trash) null else dir.listFiles()
                }
                try {
                    try {
                        TokenStatSpool.exportQuarantineEvidence(context, destination)
                        fail("export must fail when an ack trash directory enumeration fails")
                    } catch (e: IOException) {
                        assertTrue("failure must name the enumeration error", e.message!!.contains("enumerate"))
                    }
                    // 源证据全部保留
                    assertTrue(trash.exists())
                    assertTrue(evidence.exists())
                    assertTrue(quarantineFile.exists())
                    // partial 目标未报告成功；UI 清理 helper 确认本轮目标被清除
                    assertTrue(QuarantineExportCleanup.deleteRecursively(destination))
                    assertFalse(destination.exists())
                } finally {
                    TokenStatSpool.directoryListingForTest = null
                }
                // 恢复 seam 后完整 export 含全部证据
                val destination2 = File(base, "run-2").also { Files.createDirectory(it.toPath()) }
                val exported = TokenStatSpool.exportQuarantineEvidence(context, destination2)
                assertTrue(exported.any { it.name == trash.name })
                assertTrue(exported.any { it.name == quarantineFile.name })
                val exportedTrash = File(destination2, trash.name)
                assertTrue(exportedTrash.isDirectory)
                assertTrue(File(exportedTrash, evidence.name).readText() == "{child-null-evidence\n")
            }
        }

    @Test
    fun `metadata publish refused at the hard cap edge stays bounded and maintenance restores appends`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val previousCap = TokenStatSpool.totalSpoolMaxBytesForTest
                TokenStatSpool.totalSpoolMaxBytesForTest = 24L * 1024 * 1024
                try {
                    val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                    // 证据区打满 16MiB：12MiB + 4MiB 两个文件（ack 其中一个后仍满 → 继续 summarize）
                    val existingBig = File(spool, "quarantine_a_sealed_1.jsonl")
                    RandomAccessFile(existingBig, "rw").use { it.setLength(12L * 1024 * 1024) }
                    val existingSmall = File(spool, "quarantine_b_sealed_2.jsonl")
                    RandomAccessFile(existingSmall, "rw").use { it.setLength(4L * 1024 * 1024) }
                    // 数据总量恰好等于总上限：首次 summary 发布的投影（+4×content）必超限
                    val segment = File(spool, "sealed_3.jsonl")
                    RandomAccessFile(segment, "rw").use { it.setLength(8L * 1024 * 1024) }
                    val cap = TokenStatSpool.totalSpoolMaxBytesForTest ?: 0L
                    TokenStatSpool.replay(context)
                    delay(900)
                    // 有界失败：不写正式 summary、段保留、总量不超过上限
                    assertFalse(
                        "summary must not be published when the metadata budget is exhausted",
                        File(spool, "quarantine_summary.jsonl").exists(),
                    )
                    assertTrue("pending segment must be retained", segment.exists())
                    assertTrue(
                        "total must stay within the cap: ${spool.walkTopDown().filter { it.isFile }.sumOf { it.length() }}",
                        spool.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= cap,
                    )
                    // 维护释放空间（此时总量恰好 = cap，ack 的 trash 状态元数据投影按 P1-1
                    // 必被拒——见 ack 状态预算测试；这里模拟外部/维护释放：移除一个证据文件）
                    // → 重试成功发布摘要 → append 恢复
                    assertTrue(existingSmall.delete())
                    TokenStatSpool.replay(context)
                    awaitSegmentGone(spool, "sealed_3.jsonl")
                    val summary = TokenStatSpool.quarantineSummaryInfo(context)
                    assertNotNull("summary must be published after maintenance frees the budget", summary)
                    assertTrue(
                        TokenStatSpool.append(
                            context,
                            line(request("after-budget-recovery")),
                            "after-budget-recovery",
                        ),
                    )
                    TokenStatSpool.replay(context)
                    awaitEvent("after-budget-recovery")
                } finally {
                    TokenStatSpool.totalSpoolMaxBytesForTest = previousCap
                }
            }
        }

    @Test
    fun `restore cleanup dir sync failure invalidates the gate so consecutive appends fail without writing and recover exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val lineB = line(request("gate-restore-b"))
                val lineC = line(request("gate-restore-c"))
                var calls = 0
                try {
                    // 阶段 0：gate=true——经快照 barrier 完成 bootstrap 两次确认（filesDir +
                    // spool），不触发 drain（append 会调度 drain 与阶段 1 的恢复竞态）
                    TokenStatSpool.dirSyncForTest = {
                        calls += 1
                        if (calls <= 2) TokenStatSpool.DirSyncResult.OK
                        else TokenStatSpool.DirSyncResult.FAILED
                    }
                    TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = false) {
                        File(spool, "active.jsonl").writeText(line(request("gate-restore-a")) + "\n")
                    }
                    assertEquals(2, calls)
                    // 阶段 1：restore 清理删除 spool 目录，删除后 filesDir 目录项 sync（第 3 次）
                    // 失败 → restore 明确失败；删除开始前 gate 必须已失效（P1-1 修复）
                    try {
                        TokenStatSpool.withExclusiveSnapshotAccess(
                            context,
                            drainBefore = false,
                            clearAfter = true,
                        ) { }
                        fail("restore must fail when the cleanup dir sync fails")
                    } catch (e: IOException) {
                        assertTrue(e.message!!.contains("durable"))
                    }
                    assertEquals(3, calls)
                    assertFalse("spool deletion is visible", spool.exists())
                    // 阶段 2：restore 替换已开始（清理失败属替换后失败）——P1 终审 fence
                    // 拒绝本进程一切后续 append（accepting=false，直到重启），任何事件绝不
                    // 写入；若 fence 失效，bootstrap gate 也已失效，同样全部失败
                    assertFalse(TokenStatSpool.append(context, lineB, "gate-restore-b"))
                    assertFalse(TokenStatSpool.append(context, lineC, "gate-restore-c"))
                    assertFalse(
                        "no event may be written while dir entries are unconfirmed",
                        File(spool, "active.jsonl").exists(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    // 阶段 3：恢复 OK。P1 终审：恢复替换已开始（清理失败属于替换后失败），
                    // 同进程事件被明确拒绝——先模拟进程重启（reset 状态）才允许写入；
                    // 重启后目录项重新确认（bootstrap），两事件各恰一次入 Room。
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    TokenStatSpool.clearPendingStateForTest()
                    assertTrue(TokenStatSpool.append(context, lineB, "gate-restore-b"))
                    assertTrue(TokenStatSpool.append(context, lineC, "gate-restore-c"))
                    TokenStatSpool.replay(context)
                    awaitEvent("gate-restore-b")
                    awaitEvent("gate-restore-c")
                    awaitNoSealedSegments(spool)
                    assertEquals(2, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                }
            }
        }

    @Test
    fun `pending-delete evidence restore with FAILED and UNSUPPORTED dir sync rebuilds retryable record and recovers exactly once`() =
        runBlocking {
            suspend fun scenario(result: TokenStatSpool.DirSyncResult, tag: String) {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val body = "{$tag-pending-bad\n"
                File(spool, "sealed_1.jsonl").writeText(body)
                File(spool, "sealed_2.jsonl").writeText(line(request("syncfail-pending-healthy-$tag")) + "\n")
                // 阶段 1：证据区 rename 失败 → pending-delete 有界证据；健康段照常排空
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    if (to.name.startsWith("quarantine_") && !to.name.startsWith("quarantine_pending_delete_")) {
                        false
                    } else {
                        null
                    }
                }
                TokenStatSpool.replay(context)
                awaitEvent("syncfail-pending-healthy-$tag")
                assertEquals(1, database.tokenStatsDao().countEvents())
                val pending = spool.listFiles().orEmpty().single {
                    it.isFile && it.name.startsWith("quarantine_pending_delete_")
                }
                // 阶段 2：维护恢复 rename 后目录项 sync 失败（bootstrap gate 已在阶段 1 确认，
                // 本阶段第一次 sync 就是恢复 rename 的目录项）→ 尽力移回 pending-delete 名
                // （重建可重试记录），本轮不推进
                TokenStatSpool.segmentRenameForTest = null
                TokenStatSpool.dirSyncForTest = { result }
                TokenStatSpool.shutdownWriterForTest()
                TokenStatSpool.replay(context)
                delay(900)
                assertTrue(
                    "pending-delete record must be rebuilt when the restore rename is not durable",
                    pending.exists(),
                )
                // pending-delete 文件本身是受管证据（计入 quarantineEvidence），但必须仍是
                // pending-delete 名（未被推进到完整证据区）
                val evidence = TokenStatSpool.quarantineEvidence(context)
                assertEquals(1, evidence.size)
                assertTrue(
                    "evidence must still be the pending-delete record",
                    evidence.single().name.startsWith("quarantine_pending_delete_"),
                )
                assertEquals(1, database.tokenStatsDao().countEvents())
                // 恢复：rename 目录项确认持久后证据恰一次回到完整证据区
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                TokenStatSpool.shutdownWriterForTest()
                TokenStatSpool.replay(context)
                awaitNoPendingEvidence(spool)
                val restored = TokenStatSpool.quarantineEvidence(context)
                assertEquals(1, restored.size)
                assertTrue("full evidence must be restored exactly once", restored.single().readText() == body)
                assertEquals(1, database.tokenStatsDao().countEvents())
            }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.FAILED, "fail") { r, t -> scenario(r, t) }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.UNSUPPORTED, "unsupported") { r, t -> scenario(r, t) }
        }

    @Test
    fun `tombstone evidence restore rename with FAILED and UNSUPPORTED dir sync keeps manifest entry and recovers exactly once`() =
        runBlocking {
            suspend fun scenario(result: TokenStatSpool.DirSyncResult, tag: String) {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val body = "{$tag-evidence-bad\n"
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                File(spool, "sealed_1.jsonl").writeText(body)
                File(spool, "sealed_2.jsonl").writeText(line(request("syncfail-evidence-healthy-$tag")) + "\n")
                // 阶段 1：两次 rename 都失败 → tombstone（容量内，overCap=false）；健康段排空
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    if (to.name.startsWith("quarantine_")) false else null
                }
                TokenStatSpool.replay(context)
                awaitEvent("syncfail-evidence-healthy-$tag")
                val entryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < entryDeadline &&
                    safeManifestText(manifest)?.contains("sealed_1.jsonl") != true
                ) {
                    delay(20)
                }
                assertTrue(safeManifestText(manifest)?.contains("sealed_1.jsonl") == true)
                assertEquals(1, database.tokenStatsDao().countEvents())
                // 阶段 1 的 drain 可能仍在收尾（tombstone 发布后的队列复扫 sync）——先静默
                // 至 drain 完全结束，阶段 2 的计数 seam 才能从确定的第一笔 sync 开始
                delay(300)
                // 阶段 2：恢复 rename 成功但目录项 sync 失败（bootstrap gate 已在阶段 1 确认；
                // 本阶段第 1 次 sync 是 manifest 严格读取、第 2 次是容量判定读取、第 3 次才是
                // restore rename 的目录项）→ 条目保留、本轮不推进
                TokenStatSpool.segmentRenameForTest = null
                var calls = 0
                TokenStatSpool.dirSyncForTest = {
                    calls += 1
                    if (calls == 3) result else TokenStatSpool.DirSyncResult.OK
                }
                TokenStatSpool.shutdownWriterForTest()
                TokenStatSpool.replay(context)
                delay(900)
                assertTrue(
                    "manifest entry must be retained while the restore rename is unconfirmed",
                    safeManifestText(manifest)?.contains("sealed_1.jsonl") == true,
                )
                assertFalse("sealed original is gone (rename visible)", File(spool, "sealed_1.jsonl").exists())
                assertTrue(
                    "evidence must already be at the quarantine name",
                    TokenStatSpool.quarantineEvidence(context).any { it.readText() == body },
                )
                assertEquals(1, database.tokenStatsDao().countEvents())
                // 恢复：确认 rename 持久后条目幂等移除，证据恰一次
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                TokenStatSpool.shutdownWriterForTest()
                TokenStatSpool.replay(context)
                awaitManifestWithout(spool, "sealed_1.jsonl")
                assertEquals(
                    1,
                    TokenStatSpool.quarantineEvidence(context).count { it.readText() == body },
                )
                assertEquals(1, database.tokenStatsDao().countEvents())
            }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.FAILED, "fail") { r, t -> scenario(r, t) }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.UNSUPPORTED, "unsupported") { r, t -> scenario(r, t) }
        }

    @Test
    fun `restore cleanup with FAILED and UNSUPPORTED dir sync fails closed and retry after recovery is idempotent`() =
        runBlocking {
            suspend fun scenario(result: TokenStatSpool.DirSyncResult, tag: String) {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                File(spool, "active.jsonl").writeText("{$tag-restore\n")
                // bootstrap gate(2) OK，spool 目录删除后的 filesDir 目录项 sync（第 3 次）失败
                var calls = 0
                TokenStatSpool.dirSyncForTest = {
                    calls += 1
                    if (calls <= 2) TokenStatSpool.DirSyncResult.OK else result
                }
                try {
                    TokenStatSpool.withExclusiveSnapshotAccess(
                        context,
                        drainBefore = false,
                        clearAfter = true,
                    ) { }
                    fail("restore must fail when the spool cleanup is not durable")
                } catch (e: IOException) {
                    assertTrue("restore state must be retained", e.message!!.contains("durable"))
                }
                assertFalse("spool deletion is visible", spool.exists())
                // 重试幂等：目录已不存在时跳过删除，确认持久后 restore 成功
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                TokenStatSpool.withExclusiveSnapshotAccess(
                    context,
                    drainBefore = false,
                    clearAfter = true,
                ) { }
                assertFalse(spool.exists())
            }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.FAILED, "fail") { r, t -> scenario(r, t) }
            runDirSyncFailClosedScenario(TokenStatSpool.DirSyncResult.UNSUPPORTED, "unsupported") { r, t -> scenario(r, t) }
        }

}
