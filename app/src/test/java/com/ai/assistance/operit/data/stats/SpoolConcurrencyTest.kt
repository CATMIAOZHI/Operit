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
internal class SpoolConcurrencyTest : TokenStatReliabilityTestBase() {
    @Test
    fun `generation handles same millisecond clock rollback and request spanning reset`() = runBlocking {
        val dao = database.tokenStatsDao()
        val oldSameMillisecond = request("old-same-ms", generation = 0L, startedAt = 5_000L)
        val oldFinishesAfterReset = request("old-spanning", generation = 0L, startedAt = 9_000L)

        dao.resetAllStatisticsTx()
        assertEquals(1L, dao.currentResetGeneration())
        TokenStatsLedger.recordWith(context, dao, oldSameMillisecond)
        TokenStatsLedger.recordWith(context, dao, oldFinishesAfterReset)
        assertEquals(0, dao.countEvents())

        // New request after reset is accepted even if its wall clock moved backwards.
        TokenStatsLedger.recordWith(
            context,
            dao,
            request("new-clock-rollback", generation = 1L, startedAt = 1L),
        )
        assertEquals(1, dao.countEvents())
        assertEquals(1L, dao.getEvent("new-clock-rollback")!!.acceptedGeneration)
    }

    @Test
    fun `interrupt ignoring insert never locks spool and restore barrier stays clean`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val previousInsert = TokenStatSpool.insertTimeoutMs
            TokenStatSpool.insertTimeoutMs = 100
            try {
                val spoolDir = File(root, TokenStatSpool.SPOOL_DIR_NAME)
                spoolDir.mkdirs()
                // 先构建行（价格解析走真实 DAO），再安装忽略中断但可释放的 insert 挂起
                val lineA = line(request("evt-hung-a"))
                val lineB = line(request("evt-hung-b"))
                val realDao = database.tokenStatsDao()
                val release = CountDownLatch(1)
                val blockingDao = mock<TokenStatsDao>()
                whenever(blockingDao.insertIdentityIfAbsent(any())).thenAnswer { invocation ->
                    // SQLite 忽略中断：cancel(true) 无法终止；释放后委托真实 DAO 完成
                    gateIgnoringInterrupts(release)
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

                // append A durable；drain 启动后 insert 挂起（忽略中断）
                assertTrue(TokenStatSpool.append(context, lineA, "evt-hung-a"))
                val latchDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (TokenStatSpool.pendingLatchCountForTest() == 0 && System.nanoTime() < latchDeadline) {
                    delay(10)
                }
                assertEquals(1, TokenStatSpool.pendingLatchCountForTest())

                // 硬上限（insertTimeoutMs）之后：锁必须已释放，append 不再被阻塞
                val startedSecond = System.nanoTime()
                assertTrue(TokenStatSpool.append(context, lineB, "evt-hung-b"))
                val secondElapsedMs = (System.nanoTime() - startedSecond) / 1_000_000
                assertTrue("append must never block on the hung insert: ${secondElapsedMs}ms", secondElapsedMs < 10_000)

                // restore barrier：wedged insert 仍存活（已通过 fence、正在 Room 内）时，
                // 必须有界失败且绝不替换文件；等待结束后旧 insert 仍登记在 registry
                val startedRestore = System.nanoTime()
                try {
                    TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = false, clearAfter = true) { }
                    fail("restore must fail bounded while an old insert is still live")
                } catch (e: IOException) {
                    assertTrue("restore must report the live insert", e.message!!.contains("still active"))
                }
                val restoreElapsedMs = (System.nanoTime() - startedRestore) / 1_000_000
                assertTrue("restore must be bounded: ${restoreElapsedMs}ms", restoreElapsedMs < 10_000)
                assertEquals(1, TokenStatSpool.activeInsertCountForTest())

                // 模拟重启前必须释放并确认旧 insert 线程终止：释放门闩 → registry 真正清空
                release.countDown()
                val registryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (TokenStatSpool.activeInsertCountForTest() != 0 && System.nanoTime() < registryDeadline) {
                    delay(10)
                }
                assertEquals(0, TokenStatSpool.activeInsertCountForTest())

                // 丢弃已完成的旧 worker（shutdown 后线程真实终止），再模拟进程重启；
                // 被卡任务由新排空重放幂等完成（失败的 restore 从未替换数据库）
                TokenStatSpool.resetExecutorsForTest()
                TokenStatSpool.shutdownWriterForTest()
                awaitNoSpoolWorkerThreads()
                assertEquals(0, TokenStatSpool.activeInsertCountForTest())
                assertEquals(0, TokenStatSpool.pendingLatchCountForTest())

                TokenStatsLedger.databaseProvider = { database }
                TokenTrackingAIService.recordSafely(context, request("evt-after-restore"))
                awaitEvent("evt-after-restore")
                awaitEvent("evt-hung-a")
                awaitEvent("evt-hung-b")
                assertEquals(3, database.tokenStatsDao().countEvents())

                // 重试 restore：registry 已空，替换模拟可执行，恢复后的 DB 无旧事件
                TokenStatSpool.withExclusiveSnapshotAccess(
                    context,
                    drainBefore = false,
                    clearAfter = true,
                ) {
                    database.tokenStatsDao().deleteAllEvents()
                }
                assertEquals(0, database.tokenStatsDao().countEvents())
                assertNull(database.tokenStatsDao().getEvent("evt-hung-a"))
                assertNull(database.tokenStatsDao().getEvent("evt-hung-b"))
                assertNull(database.tokenStatsDao().getEvent("evt-after-restore"))

                // 恢复后的新事件正常落账且只出现一次。P1 终审：恢复替换已开始（accepting=
                // false），同进程后续事件被明确拒绝——必须先模拟进程重启（reset 状态）才
                // 允许写入；这正是 UI“稍后重启”窗口的语义。
                TokenStatSpool.resetExecutorsForTest()
                TokenTrackingAIService.recordSafely(context, request("evt-post-restore"))
                awaitEvent("evt-post-restore")
                assertEquals(1, database.tokenStatsDao().countEvents())
                assertEquals("evt-post-restore", database.tokenStatsDao().getAllEvents().single().eventId)
            } finally {
                TokenStatsLedger.databaseProvider = { database }
                TokenStatSpool.resetExecutorsForTest()
                TokenStatSpool.insertTimeoutMs = previousInsert
            }
        }
    }

    @Test
    fun `database preparation timeouts stay single flight with bounded threads`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val previousPrepare = TokenStatSpool.prepareTimeoutMs
            TokenStatSpool.prepareTimeoutMs = 50
            try {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                File(spool, "sealed_1.jsonl").writeText(line(request("evt-db-prep-hang")) + "\n")
                // 数据库准备挂起且忽略中断（可释放）：每次 drain 循环都必须单飞复用同一任务
                val release = CountDownLatch(1)
                TokenStatsLedger.databaseProvider = {
                    gateIgnoringInterrupts(release)
                    database
                }
                TokenStatSpool.replay(context)
                // 第 1 个退避周期
                delay(1_200)
                TokenStatSpool.replay(context)
                // 第 2 个退避周期
                delay(2_200)
                val dbThreads = Thread.getAllStackTraces().keys.count {
                    it.isAlive && it.name.startsWith("operit-token-stats-database")
                }
                assertTrue("database preparation must stay single-flight: $dbThreads", dbThreads <= 1)
                assertEquals(0, database.tokenStatsDao().countEvents())

                // 释放被卡住的准备任务并确认旧 worker 真实终止后再模拟重启
                release.countDown()
                TokenStatSpool.resetExecutorsForTest()
                TokenStatSpool.shutdownWriterForTest()
                awaitNoSpoolWorkerThreads()

                // 恢复后（重置 worker 模拟重启）事件仍能落账
                TokenStatsLedger.databaseProvider = { database }
                TokenStatSpool.replay(context)
                awaitEvent("evt-db-prep-hang")
                assertEquals(1, database.tokenStatsDao().countEvents())
            } finally {
                TokenStatsLedger.databaseProvider = { database }
                TokenStatSpool.resetExecutorsForTest()
                TokenStatSpool.prepareTimeoutMs = previousPrepare
            }
        }
    }

    @Test
    fun `concurrent exports keep unique directories and one failing export never deletes the other success`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                File(spool, "quarantine_aa_sealed_1.jsonl").writeText("aa-bad\n")
                File(spool, "quarantine_bb_sealed_2.jsonl").writeText("bb-bad\n")
                val base = File(root, "export-runs").apply { mkdirs() }
                // P2：UI 命名 = 时间戳前缀 + UUID；同一毫秒前缀下 UUID 保证目录唯一，
                // 目录用 Files.createDirectory 独占创建（已存在即失败）
                fun uniqueExportDir(ts: String): File =
                    File(base, "token_stats_quarantine_${ts}_${UUID.randomUUID().toString().replace("-", "")}")
                        .also { Files.createDirectory(it.toPath()) }
                val destA = uniqueExportDir("20260806_120000")
                val destB = uniqueExportDir("20260806_120000")
                // 两个导出经 lifecycleMutex 串行：A 的 manifest 两次读取（manifestContent +
                // evidence 列表）放行，B 的 manifestContent 读取（第 3 次）注入失败
                var manifestReads = 0
                TokenStatSpool.metadataReadErrorForTest = { file ->
                    if (file.name == "quarantine_skip_manifest.jsonl") {
                        manifestReads += 1
                        manifestReads == 3
                    } else {
                        false
                    }
                }
                try {
                    val exportA = async { TokenStatSpool.exportQuarantineEvidence(context, destA) }
                    val exportB = async {
                        try {
                            TokenStatSpool.exportQuarantineEvidence(context, destB)
                            fail("export B must fail with the injected manifest read failure")
                        } catch (e: IOException) {
                        }
                    }
                    val exportedA = exportA.await()
                    exportB.await()
                    // 各自目录独立且完整：A 成功导出两份证据
                    assertEquals(
                        setOf("quarantine_aa_sealed_1.jsonl", "quarantine_bb_sealed_2.jsonl"),
                        exportedA.map { it.name }.toSet(),
                    )
                    assertEquals(exportedA.size, destA.listFiles().orEmpty().size)
                    // 失败的导出绝不删除另一成功导出的目录/内容；自身目录也未被删除
                    assertTrue("successful export directory must stay intact", destA.isDirectory)
                    assertTrue(destA.listFiles().orEmpty().all { it.isFile })
                    assertTrue("failed export directory must not be deleted by the spool", destB.isDirectory)

                    // 恢复后正常：新的导出成功且只含本次证据
                    TokenStatSpool.metadataReadErrorForTest = null
                    val retried = TokenStatSpool.exportQuarantineEvidence(
                        context,
                        uniqueExportDir("20260806_120001"),
                    )
                    assertTrue(retried.any { it.name.startsWith("quarantine_aa_") })
                    assertTrue(retried.any { it.name.startsWith("quarantine_bb_") })
                } finally {
                    TokenStatSpool.metadataReadErrorForTest = null
                }
            }
        }

    // ── P1 终审：durable bootstrap gate / 维护目录项严格同步 ──────────────────────

    @Test
    fun `schedule during an in-flight drain round is not lost and the worker reruns`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        val lineA = line(request("rerun-a"))
        val lineB = line(request("rerun-b"))
        File(spool, "sealed_1.jsonl").writeText(lineA + "\n" + lineB + "\n")
        var rounds = 0
        var replayInjected = false
        TokenStatSpool.afterDrainRoundForTest = {
            rounds += 1
            // 第一轮结束、轮末决策之前注入一次 replay：请求必须被保留并由同一 worker
            // 立即 rerun（旧实现：drainScheduled=true 直接丢弃该请求，轮数恒为 1）。
            if (!replayInjected) {
                replayInjected = true
                TokenStatSpool.replay(context)
            }
        }
        try {
            TokenStatSpool.replay(context)
            // 两轮结束：第 1 轮排空数据，第 2 轮消费注入的请求（维护轮）后 retire
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (rounds < 2 && System.nanoTime() < deadline) delay(10)
            assertEquals("injected replay must trigger a rerun round", 2, rounds)
            awaitEvent("rerun-a")
            awaitEvent("rerun-b")
            awaitNoSealedSegments(spool)
            assertFalse("request must be consumed by the rerun", TokenStatSpool.drainRequestPendingForTest())
            assertFalse("worker must retire after the rerun", TokenStatSpool.drainScheduledForTest())
        } finally {
            TokenStatSpool.afterDrainRoundForTest = null
        }
    }

    @Test
    fun `rejected drain schedule retains the request and recovers on the next schedule`() = runBlocking {
        TokenStatSpool.rejectDrainScheduleForTest = true
        try {
            val lineA = line(request("rejected-schedule-a"))
            assertTrue("append must succeed durably despite rejected scheduling", TokenStatSpool.append(context, lineA, "rejected-schedule-a"))
            assertTrue("request must be retained after rejection", TokenStatSpool.drainRequestPendingForTest())
            assertFalse("schedule token must be released after rejection", TokenStatSpool.drainScheduledForTest())
            // 恢复调度能力后 replay：请求不丢，事件最终入 Room
            TokenStatSpool.rejectDrainScheduleForTest = false
            TokenStatSpool.replay(context)
            awaitEvent("rejected-schedule-a")
            assertFalse(TokenStatSpool.drainRequestPendingForTest())
            assertFalse(TokenStatSpool.drainScheduledForTest())
        } finally {
            TokenStatSpool.rejectDrainScheduleForTest = false
        }
    }

    @Test
    fun `await initial drain joins concurrent waiters and failed rounds are retryable`() = runBlocking {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
        val lineA = line(request("init-drain-a"))
        val lineB = line(request("init-drain-b"))
        File(spool, "sealed_1.jsonl").writeText(lineA + "\n" + lineB + "\n")
        // 失败轮：bootstrap gate 目录 sync 失败 → drainCore false → 等待者按失败完成
        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.FAILED }
        try {
            assertFalse("failed round must complete the waiter with false", TokenStatSpool.awaitInitialDrain(context, 5_000))
            // 失败不缓存：恢复后重试成功；并发调用 join 同一轮
            TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
            val r1 = async { TokenStatSpool.awaitInitialDrain(context, 10_000) }
            val r2 = async { TokenStatSpool.awaitInitialDrain(context, 10_000) }
            assertTrue("retry must succeed", r1.await())
            assertTrue("concurrent join must see the same success", r2.await())
            awaitEvent("init-drain-a")
            awaitEvent("init-drain-b")
            awaitNoSealedSegments(spool)
            assertFalse(TokenStatSpool.drainRequestPendingForTest())
            assertFalse(TokenStatSpool.drainScheduledForTest())
        } finally {
            TokenStatSpool.dirSyncForTest = null
        }
    }

    @Test
    fun `timed out initial drain waiter is removed when scheduling stays rejected`() = runBlocking {
        TokenStatSpool.rejectDrainScheduleForTest = true
        try {
            assertFalse(TokenStatSpool.awaitInitialDrain(context, 25))
            assertEquals(0, TokenStatSpool.initialDrainWaiterCountForTest())
            assertTrue("drain request remains retryable", TokenStatSpool.drainRequestPendingForTest())
        } finally {
            TokenStatSpool.rejectDrainScheduleForTest = false
        }
    }

}
