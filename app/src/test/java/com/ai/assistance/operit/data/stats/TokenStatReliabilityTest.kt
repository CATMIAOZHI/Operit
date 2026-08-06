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
class TokenStatReliabilityTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        root = kotlin.io.path.createTempDirectory("token-stat-reliability").toFile()
        context = mock<Context>().also { ctx ->
            whenever(ctx.applicationContext).thenReturn(ctx)
            whenever(ctx.packageName).thenReturn("com.ai.assistance.operit")
            whenever(ctx.filesDir).thenReturn(root)
            whenever(ctx.getDatabasePath(any())).thenAnswer { File(root, it.getArgument<String>(0)) }
        }
        database =
            Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
                .setDriver(JdbcSQLiteDriver())
                .addMigrations(AppDatabase.MIGRATION_28_29, AppDatabase.MIGRATION_29_30)
                .allowMainThreadQueries()
                .build()
        TokenStatsLedger.databaseProvider = { database }
        TokenStatsLedger.legacyPriceProvider = { _, _ -> null }
        TokenStatSpool.clearPendingStateForTest()
        TokenTrackingAIService.resetPricingExecutorForTest()
        TokenStatSpool.afterSegmentReadForTest = null
        TokenStatSpool.spoolDeleteForTest = null
        TokenStatSpool.segmentDeleteForTest = null
        TokenStatSpool.segmentRenameForTest = null
        TokenStatSpool.quarantineAtomicMoveForTest = null
        TokenStatSpool.metadataReadErrorForTest = null
        TokenStatSpool.metadataWriteErrorForTest = null
        TokenStatSpool.segmentReadErrorForTest = null
        TokenStatSpool.ackAtomicMoveForTest = null
        TokenStatSpool.directoryListingForTest = null
        TokenStatSpool.beforeSealPublishForTest = null
        TokenStatSpool.sealHardLinkForTest = null
        TokenStatSpool.sealActiveDeleteForTest = null
        TokenStatSpool.fileSyncForTest = null
        // P1 终审：Windows JVM 测试统一注入“目录 fsync 支持且成功”（平台无关）——生产
        // Android/Linux 支持目录 fd fsync；UNSUPPORTED/FAILED 只由显式 fail-closed 测试在
        // 测试体内注入并在 finally 还原，不存在“原地排空”平台模式。
        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
        TokenStatSpool.sealCopyForTest = null
    }

    @After
    fun tearDown() {
        // P1-1 终审修复：测试可能以“目录项未确认持久”状态结束（gate=false），tearDown 的快照
        // barrier 会重新 bootstrap——必须先恢复“目录 fsync 支持且成功”的平台常态（Windows JVM
        // 真实探测恒为 UNSUPPORTED），否则 gate 在 tearDown 中失败并掩盖测试结果。
        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
        runBlocking {
            TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = false) { }
        }
        TokenStatsLedger.databaseProvider = null
        TokenStatsLedger.legacyPriceProvider = null
        TokenStatSpool.afterSegmentReadForTest = null
        TokenStatSpool.spoolDeleteForTest = null
        TokenStatSpool.segmentDeleteForTest = null
        TokenStatSpool.segmentRenameForTest = null
        TokenStatSpool.quarantineAtomicMoveForTest = null
        TokenStatSpool.metadataReadErrorForTest = null
        TokenStatSpool.metadataWriteErrorForTest = null
        TokenStatSpool.segmentReadErrorForTest = null
        TokenStatSpool.ackAtomicMoveForTest = null
        TokenStatSpool.directoryListingForTest = null
        TokenStatSpool.beforeSealPublishForTest = null
        TokenStatSpool.sealHardLinkForTest = null
        TokenStatSpool.sealActiveDeleteForTest = null
        TokenStatSpool.fileSyncForTest = null
        TokenStatSpool.dirSyncForTest = null
        TokenStatSpool.sealCopyForTest = null
        TokenStatSpool.prepareTimeoutMs = 5_000L
        TokenStatSpool.insertTimeoutMs = 5_000L
        TokenStatSpool.exclusiveQuiesceTimeoutMs = 5_000L
        // 每个测试结束必须无遗留 spool worker 线程：shutdown 已释放的 worker 后确认终止
        TokenTrackingAIService.resetPricingExecutorForTest()
        TokenStatSpool.resetExecutorsForTest()
        TokenStatSpool.shutdownWriterForTest()
        awaitNoSpoolWorkerThreads()
        database.close()
    }

    private fun request(
        id: String,
        generation: Long = 0L,
        startedAt: Long = 1_000L,
    ) = TokenStatRequestContext(
        eventId = id,
        category = TokenStatCategory.CHAT,
        configId = "cfg",
        provider = "DEEPSEEK",
        model = "deepseek-chat",
        startedAtMs = startedAt,
        acceptedGeneration = generation,
        // P1 终审：请求“开始”时同步捕获 restore epoch（与生产 newRequest 一致）；恢复屏障
        // 递增 epoch 后，捕获于屏障前的旧请求在 append 时被明确拒绝。
        sessionEpoch = TokenStatSpool.captureRestoreEpoch(),
    ).apply {
        onUsage(
            ProviderUsageSnapshot(
                uncachedInputTokens = 10L,
                cachedInputTokens = 0L,
                cacheWriteTokens = 0L,
                outputTokens = 5L,
                source = "test",
            ),
        )
        finish(TokenStatStatus.COMPLETED, startedAt)
    }

    private suspend fun line(request: TokenStatRequestContext): String =
        TokenStatsLedger.prepareEventLine(context, request, request.toSpoolBaseJson())

    private suspend fun awaitEvent(id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (database.tokenStatsDao().getEvent(id) == null && System.nanoTime() < deadline) delay(20)
    }

    /**
     * 模拟 SQLite 忽略线程中断但可释放的挂起：任何 cancel(true) 都无法终止，直到门闩
     * 打开才返回（释放后线程能真正终止，测试结束不留遗留线程）。
     */
    private fun gateIgnoringInterrupts(gate: CountDownLatch) {
        while (true) {
            try {
                if (gate.await(1, TimeUnit.SECONDS)) return
            } catch (_: InterruptedException) {
            }
        }
    }

    /** 等待 spool 专属 worker 线程全部终止；超时即失败（测试结束必须无遗留线程）。 */
    private fun awaitNoSpoolWorkerThreads() {
        fun live(): List<String> =
            Thread.getAllStackTraces().entries
                .filter { it.key.isAlive && it.key.name.startsWith("operit-token-stats-") }
                .map { it.key.name }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (live().isEmpty()) return
            Thread.sleep(20)
        }
        fail("spool worker threads leaked: ${live()}")
    }

    @Test
    fun `price override read timeout is durable unknown not default`() = runBlocking {
        database.tokenStatsDao().upsertPriceOverride(
            scope = TokenPriceResolver.SCOPE_CONFIG,
            provider = "DEEPSEEK",
            model = "deepseek-chat",
            configId = "cfg",
            billingMode = BillingMode.TOKEN.name,
            pricingCurrency = "USD",
            inputPricePerMillion = 99.0,
            cachedInputPricePerMillion = 99.0,
            outputPricePerMillion = 99.0,
        )
        TokenStatsLedger.legacyPriceProvider = { _, _ -> delay(Long.MAX_VALUE); null }
        TokenStatSpool.prepareTimeoutMs = 50L

        TokenTrackingAIService.recordSafely(context, request("price-timeout"))
        awaitEvent("price-timeout")
        val event = database.tokenStatsDao().getEvent("price-timeout")!!
        assertEquals(PricingSource.UNKNOWN.name, event.pricingSource)
        assertNull(event.inputPricePerMillion)
        assertNull(event.costInPricingCurrency)
        assertTrue(event.diagnosticsJson!!.contains("pricing_read_timeout"))
    }

    @Test
    fun `more than two thousand append failures never return durable`() = runBlocking {
        File(root, TokenStatSpool.SPOOL_DIR_NAME).writeText("not a directory")
        Mockito.mockStatic(AppLogger::class.java).use {
            repeat(2_001) { index ->
                try {
                    TokenTrackingAIService.recordSafely(context, request("disk-failure-$index"))
                    fail("append failure must throw")
                } catch (_: TokenStatsPersistenceException) {
                }
            }
        }
        assertEquals(0, TokenStatSpool.emergencyQueueSizeForTest())
        assertEquals(0, database.tokenStatsDao().countEvents())
    }

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
    fun `crash half line never splices the next healthy event`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            // 崩溃残留：active 尾部半行 JSON，无换行
            File(spool, "active.jsonl").writeText("{\"v\":2,\"eventId\":\"evt-crash-half\"")
            TokenTrackingAIService.recordSafely(context, request("evt-healthy-after-crash"))
            TokenStatSpool.replay(context)
            awaitEvent("evt-healthy-after-crash")
            // 健康事件恰好一次进入 Room，残缺证据完整保留在 quarantine
            assertEquals(1, database.tokenStatsDao().countEvents())
            assertEquals("evt-healthy-after-crash", database.tokenStatsDao().getAllEvents().single().eventId)
            val evidence = TokenStatSpool.quarantineEvidence(context)
            assertEquals(1, evidence.size)
            assertTrue("partial evidence must be preserved", evidence.single().readText().contains("evt-crash-half"))
        }
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
    fun `same-name same-size same-mtime replacement is never skipped isolated or acked away`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val healthyLine = line(request("replacement-healthy"))
                val healthyBytes = (healthyLine + "\n").toByteArray(Charsets.UTF_8).size
                // 旧损坏段与健康新段字节数完全一致（P1-1：仅凭 length+mtime 的缓存才会被骗）
                val oldBody = "{old-corrupt-" + "x".repeat(healthyBytes - "{old-corrupt-".length - 1) + "\n"
                val oldSha = sha256Hex(oldBody.toByteArray(Charsets.UTF_8))
                val fixedMtime = 1_700_000_000_000L

                val file = File(spool, "sealed_1.jsonl")
                file.writeText(oldBody)
                file.setLastModified(fixedMtime)
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                manifest.writeText(
                    "{\"ts\":1,\"file\":\"sealed_1.jsonl\",\"bytes\":$healthyBytes," +
                        "\"sha256\":\"$oldSha\",\"overCap\":false}\n",
                )
                // 先建立旧身份（旧实现中身份哈希缓存在此记住 length+mtime+sha）
                TokenStatSpool.quarantineEvidence(context)

                // 同名同长同 mtime 替换为不同内容（健康行）
                assertTrue(file.delete())
                file.writeText(healthyLine + "\n")
                file.setLastModified(fixedMtime)

                // ack：陈旧记录被移除，健康文件绝不删除
                TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf("sealed_1.jsonl"))
                assertTrue("ack must never delete a replaced same-name healthy segment", file.exists())
                assertFalse(
                    "stale tombstone must be removed by ack",
                    manifest.isFile && manifest.readText().contains("sealed_1.jsonl"),
                )

                // 重建陈旧记录，让维护入口与扫描器都看到它
                manifest.writeText(
                    "{\"ts\":2,\"file\":\"sealed_1.jsonl\",\"bytes\":$healthyBytes," +
                        "\"sha256\":\"$oldSha\",\"overCap\":false}\n",
                )
                // replay：维护清理不删不隔离、扫描器不跳过，健康事件恰一次
                TokenStatSpool.replay(context)
                awaitEvent("replacement-healthy")
                awaitSegmentGone(spool, "sealed_1.jsonl")
                assertEquals(1, database.tokenStatsDao().countEvents())
                assertEquals(
                    "replacement-healthy",
                    database.tokenStatsDao().getAllEvents().single().eventId,
                )
                assertFalse(
                    "stale tombstone must be removed after replay",
                    manifest.isFile && manifest.readText().contains("sealed_1.jsonl"),
                )
                assertTrue(
                    "healthy segment must never be isolated as evidence",
                    TokenStatSpool.quarantineEvidence(context).none { it.name == "sealed_1.jsonl" },
                )
            }
        }

    @Test
    fun `total spool cap stops appends while dao permanently fails and recovers after drain`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val previousCap = TokenStatSpool.totalSpoolMaxBytesForTest
                // 行先于失败 DAO 生成（行生成需要真实价格读取），DAO 只负责排空失败
                val lines = (0 until 400).map { index ->
                    line(request("cap-$index")) to "cap-$index"
                }
                TokenStatSpool.MAX_SEGMENT_BYTES = 8L * 1024
                // 总 cap：约 3 个小段 + 行余量；DAO 永久失败 → sealed 段只增不减
                TokenStatSpool.totalSpoolMaxBytesForTest = 24L * 1024
                val failingDao = mock<TokenStatsDao>()
                whenever(failingDao.insertIdentityIfAbsent(any())).thenThrow(RuntimeException("dao down"))
                whenever(failingDao.upsertDisplayModel(any())).thenThrow(RuntimeException("dao down"))
                whenever(failingDao.insertEventIfNotResetCovered(any())).thenThrow(RuntimeException("dao down"))
                val proxy = mock<AppDatabase>()
                whenever(proxy.tokenStatsDao()).thenReturn(failingDao)
                TokenStatsLedger.databaseProvider = { proxy }
                try {
                    var rejected = 0
                    for ((text, eventId) in lines) {
                        try {
                            TokenStatSpool.append(context, text, eventId)
                        } catch (e: TokenStatsPersistenceException) {
                            rejected++
                        }
                    }
                    assertTrue("append must be refused once the total cap is reached: $rejected", rejected > 0)
                    val totalAtRejection = spool.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    assertTrue(
                        "total spool bytes must never exceed the cap: $totalAtRejection",
                        totalAtRejection <= (TokenStatSpool.totalSpoolMaxBytesForTest ?: 0),
                    )
                    // 固定 cap 前停止：拒绝后不再发布任何新字节（seal 只是改名不增字节）
                    val frozen = spool.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    repeat(20) {
                        try {
                            TokenStatSpool.append(context, lines[0].first, "refused-$it")
                            fail("append after cap must keep failing")
                        } catch (e: TokenStatsPersistenceException) {
                        }
                    }
                    assertEquals(
                        "no new spool bytes may be published after the cap",
                        frozen,
                        spool.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())

                    // drain 成功（DAO 恢复）后空间释放，append 继续
                    TokenStatsLedger.databaseProvider = { database }
                    TokenStatSpool.replay(context)
                    awaitNoSealedSegments(spool)
                    TokenTrackingAIService.recordSafely(context, request("after-total-cap-recovery"))
                    awaitEvent("after-total-cap-recovery")
                    assertEquals(
                        "after-total-cap-recovery",
                        database.tokenStatsDao().getEvent("after-total-cap-recovery")!!.eventId,
                    )
                } finally {
                    TokenStatsLedger.databaseProvider = { database }
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                    TokenStatSpool.totalSpoolMaxBytesForTest = previousCap
                }
            }
        }

    @Test
    fun `single legal line exactly at the total cap is accepted and the next is refused`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val previousCap = TokenStatSpool.totalSpoolMaxBytesForTest
                // cap 与单行合法上限一致：恰好一行可写入，第二行必须明确拒绝
                TokenStatSpool.totalSpoolMaxBytesForTest = TokenStatSpool.MAX_LINE_BYTES.toLong()
                // 行必须先于失败 DAO 生成（行生成需要真实价格读取）；排空失败段才留在
                // spool，第二次 append 才会在总容量检查处触顶
                val padded =
                    padLineTo(
                        line(request("single-line-at-cap")),
                        TokenStatSpool.MAX_LINE_BYTES,
                    )
                val secondLine = line(request("refused-after-single"))
                val failingDao = mock<TokenStatsDao>()
                whenever(failingDao.insertIdentityIfAbsent(any())).thenThrow(RuntimeException("dao down"))
                whenever(failingDao.upsertDisplayModel(any())).thenThrow(RuntimeException("dao down"))
                whenever(failingDao.insertEventIfNotResetCovered(any())).thenThrow(RuntimeException("dao down"))
                val proxy = mock<AppDatabase>()
                whenever(proxy.tokenStatsDao()).thenReturn(failingDao)
                TokenStatsLedger.databaseProvider = { proxy }
                try {
                    assertEquals(
                        TokenStatSpool.MAX_LINE_BYTES,
                        (padded + "\n").toByteArray(Charsets.UTF_8).size,
                    )
                    assertTrue(TokenStatSpool.append(context, padded, "single-line-at-cap"))
                    try {
                        TokenStatSpool.append(context, secondLine, "refused-after-single")
                        fail("append beyond the total cap must throw TokenStatsPersistenceException")
                    } catch (e: TokenStatsPersistenceException) {
                    }
                    // DAO 恢复后排空成功：单行事件恰一次，被拒绝的行从未发布
                    TokenStatsLedger.databaseProvider = { database }
                    TokenStatSpool.replay(context)
                    awaitEvent("single-line-at-cap")
                    assertEquals(1, database.tokenStatsDao().countEvents())
                    assertEquals(
                        "single-line-at-cap",
                        database.tokenStatsDao().getAllEvents().single().eventId,
                    )
                } finally {
                    TokenStatsLedger.databaseProvider = { database }
                    TokenStatSpool.totalSpoolMaxBytesForTest = previousCap
                }
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
    fun `drain stays bounded and healthy appends stay durable while enumeration fails then recovers`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                // 健康段：根枚举失败期间 drain fail-closed 退避——段保留、不入 Room
                File(spool, "sealed_1.jsonl").writeText(line(request("enum-drain-1")) + "\n")
                // 陈旧候选：消失原件身份仍在 manifest
                val oldBody = "{enum-drain-stale\n"
                val oldSha = sha256Hex(oldBody.toByteArray(Charsets.UTF_8))
                val manifestLine =
                    "{\"ts\":1,\"file\":\"sealed_2.jsonl\",\"bytes\":${oldBody.toByteArray(Charsets.UTF_8).size}," +
                        "\"sha256\":\"$oldSha\",\"overCap\":false}\n"
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                manifest.writeText(manifestLine)
                // UNCOMMITTED trash 真实持有 sealed_2（valid mapping）
                val trash =
                    File(spool, "quarantine_ack_trash_${UUID.randomUUID().toString().replace("-", "")}")
                trash.mkdirs()
                File(trash, "sealed_2.jsonl").writeText(oldBody)
                File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).writeText(
                    TokenStatSpool.ACK_STATE_UNCOMMITTED + "\n" +
                        "{\"o\":\"sealed_2.jsonl\",\"t\":\"sealed_2.jsonl\",\"b\":${oldBody.toByteArray(Charsets.UTF_8).size},\"s\":\"$oldSha\"}\n",
                )
                TokenStatSpool.directoryListingForTest = { dir ->
                    if (dir == spool) null else dir.listFiles()
                }
                try {
                    TokenStatSpool.replay(context)
                    delay(700)
                    assertNull(
                        "no segment may drain while the root enumeration fails",
                        database.tokenStatsDao().getEvent("enum-drain-1"),
                    )
                    assertTrue("healthy segment must be preserved", File(spool, "sealed_1.jsonl").exists())
                    // 有界：trash 不处置、manifest 不重写
                    assertTrue(trash.exists())
                    assertEquals(manifestLine, safeManifestText(manifest))
                    // 健康 append 在枚举失败期间仍然 durable（事件留在 active，不排空）
                    assertTrue(
                        TokenStatSpool.append(
                            context,
                            line(request("enum-append-2")),
                            "enum-append-2",
                        ),
                    )
                    delay(700)
                    assertNull(
                        "appended event must stay durable but not drain while the root enumeration fails",
                        database.tokenStatsDao().getEvent("enum-append-2"),
                    )
                    assertTrue("appended event must stay in active.jsonl", File(spool, "active.jsonl").exists())
                    assertTrue(trash.exists())
                    assertEquals(manifestLine, safeManifestText(manifest))
                } finally {
                    TokenStatSpool.directoryListingForTest = null
                }
                // 枚举恢复后处理：健康段与 active 排空；stale 清理与回滚完成
                TokenStatSpool.replay(context)
                awaitEvent("enum-drain-1")
                awaitEvent("enum-append-2")
                awaitManifestWithout(spool, "sealed_2.jsonl")
                val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < restoreDeadline && trash.exists()) delay(20)
                assertFalse("trash must be rolled back after enumeration recovers", trash.exists())
                val evidenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var restored = false
                while (System.nanoTime() < evidenceDeadline && !restored) {
                    restored = TokenStatSpool.quarantineEvidence(context).any { it.readText() == oldBody }
                    if (!restored) delay(20)
                }
                assertTrue("held identity evidence must be restored after recovery", restored)
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
    fun `spool root enumeration failure aborts snapshot and seal without touching segments then recovers exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val body1 = line(request("enum-null-sealed")) + "\n"
                val body2 = line(request("enum-null-active")) + "\n"
                val sealed1 = File(spool, "sealed_1.jsonl")
                sealed1.writeText(body1)
                val active = File(spool, "active.jsonl")
                active.writeText(body2)
                val activeText = active.readText()
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                try {
                    // active 已有内容：下一次 append 必走 seal 路径（P1-7 场景）
                    TokenStatSpool.MAX_SEGMENT_BYTES = active.length() + 1
                    TokenStatSpool.directoryListingForTest = { dir ->
                        if (dir == spool) null else dir.listFiles()
                    }
                    try {
                        // 1) 快照的 drain 阶段 fail-closed → block 绝不执行、文件原字节不变
                        var blockRan = false
                        try {
                            TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = true) {
                                blockRan = true
                            }
                            fail("snapshot must fail while the spool root enumeration fails")
                        } catch (e: IOException) {
                            assertTrue(
                                "failure must come from the snapshot drain barrier",
                                e.message!!.contains("drain"),
                            )
                        }
                        assertFalse("snapshot block must not run", blockRan)
                        assertEquals("sealed_1 must stay byte-identical", body1, sealed1.readText())
                        assertEquals("active must stay byte-identical", activeText, active.readText())

                        // 2) seal 绝不覆盖：需要 seal 的 append 明确失败，sealed_1/active 原样
                        assertFalse(
                            "append requiring a seal must fail while enumeration fails",
                            TokenStatSpool.append(
                                context,
                                line(request("enum-null-extra")),
                                "enum-null-extra",
                            ),
                        )
                        assertEquals("sealed_1 must never be overwritten", body1, sealed1.readText())
                        assertEquals("active must not be sealed or truncated", activeText, active.readText())

                        // 3) drain 保留：后台 drain 轮退避，文件与事件原样
                        TokenStatSpool.replay(context)
                        delay(700)
                        assertEquals("sealed_1 must be preserved by the failing drain", body1, sealed1.readText())
                        assertEquals("active must be preserved by the failing drain", activeText, active.readText())
                        assertNull(
                            "sealed event must not reach Room while enumeration fails",
                            database.tokenStatsDao().getEvent("enum-null-sealed"),
                        )
                        assertNull(
                            "active event must not reach Room while enumeration fails",
                            database.tokenStatsDao().getEvent("enum-null-active"),
                        )
                    } finally {
                        TokenStatSpool.directoryListingForTest = null
                    }
                    // 4) 恢复 seam → 两事件各恰一次入 Room
                    TokenStatSpool.replay(context)
                    awaitEvent("enum-null-sealed")
                    awaitEvent("enum-null-active")
                    assertEquals(
                        "each preserved event must be recorded exactly once",
                        2,
                        database.tokenStatsDao().countEvents(),
                    )
                } finally {
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `seal never overwrites an occupied target and picks the next safe index`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val bodyA = line(request("seal-collide-a")) + "\n"
                val bodyB = line(request("seal-collide-b")) + "\n"
                File(spool, "sealed_1.jsonl").writeText(bodyA)
                val sealed2 = File(spool, "sealed_2.jsonl")
                sealed2.writeText(bodyB)
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                try {
                    // active 已含一条完整行；枚举缝隐藏 sealed_2 → 计算出的 next=2 已被
                    // 占用，seal 必须跳到 3，绝不覆盖 sealed_2
                    val bodyC = line(request("seal-collide-c")) + "\n"
                    File(spool, "active.jsonl").writeText(bodyC)
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    TokenStatSpool.directoryListingForTest = { dir ->
                        if (dir == spool) {
                            dir.listFiles()?.filter { it.name != "sealed_2.jsonl" }?.toTypedArray()
                        } else {
                            dir.listFiles()
                        }
                    }
                    try {
                        assertTrue(
                            "append must seal active to a free index and succeed",
                            TokenStatSpool.append(
                                context,
                                line(request("seal-collide-d")),
                                "seal-collide-d",
                            ),
                        )
                        // seam 仍生效：并发 drain 与 seal 都看不见 sealed_2 → 占用目标不可能被覆盖
                        assertEquals(
                            "occupied sealed target must never be overwritten",
                            bodyB,
                            sealed2.readText(),
                        )
                    } finally {
                        TokenStatSpool.directoryListingForTest = null
                    }
                    // 恢复枚举后全部事件各恰一次入 Room
                    TokenStatSpool.replay(context)
                    awaitEvent("seal-collide-a")
                    awaitEvent("seal-collide-b")
                    awaitEvent("seal-collide-c")
                    awaitEvent("seal-collide-d")
                    assertEquals(
                        "each event must be recorded exactly once",
                        4,
                        database.tokenStatsDao().countEvents(),
                    )
                } finally {
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `seal publish conflict keeps target bytes and seals active at a higher index`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
            val seamBody = "{pre-existing-conflict\n"
            try {
                // active 已含两条完整行；下一次 append 必触发 seal（候选编号 1）
                val lineA = line(request("seal-seam-a"))
                val lineB = line(request("seal-seam-b"))
                val lineC = line(request("seal-seam-c"))
                File(spool, "active.jsonl").writeText(lineA + "\n" + lineB + "\n")
                TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                var hooks = 0
                TokenStatSpool.beforeSealPublishForTest = { target ->
                    hooks += 1
                    if (hooks == 1) {
                        // 候选选定后、实际 publish 前出现同名不同内容的目标（模拟异常残留）
                        target.writeText(seamBody)
                        true
                    } else {
                        null
                    }
                }
                // 停掉后台 writer：seal 仍在 append 内同步完成，但断言阶段不会被并发 drain
                // 改写/隔离文件（确定性）
                TokenStatSpool.shutdownWriterForTest()
                try {
                    assertTrue(
                        "append must seal active to a free index and succeed",
                        TokenStatSpool.append(context, lineC, "seal-seam-c"),
                    )
                } finally {
                    TokenStatSpool.beforeSealPublishForTest = null
                }
                // 冲突目标原字节不变；active 数据安全落到更高编号 sealed_2；新事件在 active
                assertEquals(
                    "conflict target must keep its original bytes",
                    seamBody,
                    File(spool, "sealed_1.jsonl").readText(),
                )
                assertEquals(
                    "active data must be sealed to a higher index",
                    lineA + "\n" + lineB + "\n",
                    File(spool, "sealed_2.jsonl").readText(),
                )
                assertEquals(
                    "new event must be durable in active",
                    lineC + "\n",
                    File(spool, "active.jsonl").readText(),
                )
                // 全部事件各恰一次入 Room；冲突残留被隔离为完整证据、字节不变
                TokenStatSpool.replay(context)
                awaitEvent("seal-seam-a")
                awaitEvent("seal-seam-b")
                awaitEvent("seal-seam-c")
                awaitNoSealedSegments(spool)
                assertEquals(3, database.tokenStatsDao().countEvents())
                val evidence = TokenStatSpool.quarantineEvidence(context)
                assertTrue(
                    "conflict residue must be preserved byte-identical as evidence",
                    evidence.any { it.isFile && it.readText() == seamBody },
                )
            } finally {
                TokenStatSpool.beforeSealPublishForTest = null
                TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
            }
        }
    }

    @Test
    fun `hardlink seal crash window recovers before append and each event drains exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val lineA = line(request("hardlink-window-a"))
                val lineB = line(request("hardlink-window-b"))
                val active = File(spool, "active.jsonl")
                active.writeText(lineA + "\n")
                // 模拟崩溃窗口：createLink(sealed_1, active) 成功但 active 删除前崩溃 → 同 inode
                Files.createLink(File(spool, "sealed_1.jsonl").toPath(), active.toPath())
                // 停掉后台 writer：恢复发生在 append 内同步完成，断言不被并发 drain 干扰
                TokenStatSpool.shutdownWriterForTest()
                // append 必须先恢复重复（删除 sealed 副本）再写入，绝不能把新内容写进已 seal 段
                assertTrue(TokenStatSpool.append(context, lineB, "hardlink-window-b"))
                assertFalse(
                    "sealed duplicate must be removed before append, never polluted",
                    File(spool, "sealed_1.jsonl").exists(),
                )
                TokenStatSpool.replay(context)
                awaitEvent("hardlink-window-a")
                awaitEvent("hardlink-window-b")
                awaitNoSealedSegments(spool)
                assertEquals(2, database.tokenStatsDao().countEvents())
                assertEquals(
                    "each event must be recorded exactly once",
                    setOf("hardlink-window-a", "hardlink-window-b"),
                    database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                )
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
    fun `hardlink seal post-active-delete dir sync failure keeps durable link and drains once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("link-post-sync-a"))
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    var dirSyncCalls = 0
                    TokenStatSpool.dirSyncForTest = {
                        dirSyncCalls += 1
                        // 前两次是 P1-1 bootstrap gate（filesDir + spool 目录）；第三次（链接
                        // 创建）成功，第四次（active 删除）失败
                        if (dirSyncCalls != 4) TokenStatSpool.DirSyncResult.OK else TokenStatSpool.DirSyncResult.FAILED
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        assertFalse(
                            "append must fail when the post-delete dir sync fails",
                            TokenStatSpool.append(context, line(request("link-post-sync-b")), "link-post-sync-b"),
                        )
                    } finally {
                        // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）
                        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    }
                    assertEquals(4, dirSyncCalls)
                    assertTrue(
                        "durable link must be kept",
                        File(spool, "sealed_1.jsonl").exists(),
                    )
                    assertFalse("active must have been removed in-process", File(spool, "active.jsonl").exists())
                    // 恢复：link 是唯一内容持有者（同 inode），正常排空，事件恰一次入 Room
                    TokenStatSpool.replay(context)
                    awaitEvent("link-post-sync-a")
                    awaitNoSealedSegments(spool)
                    assertEquals(1, database.tokenStatsDao().countEvents())
                    assertTrue(TokenStatSpool.append(context, line(request("link-post-sync-c")), "link-post-sync-c"))
                    awaitEvent("link-post-sync-c")
                    assertEquals(2, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `mid-copy partial target is isolated never drained or overwritten and active is retained`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("partial-a"))
                val lineB = line(request("partial-b"))
                val partial = lineA + "\n" // copy 中途只写入了完整行的前缀内容
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n" + lineB + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    TokenStatSpool.sealHardLinkForTest = { _, _ -> false }
                    TokenStatSpool.sealCopyForTest = { _, target ->
                        target.writeText(partial)
                        false
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        // copy 中途失败留下部分目标：append 必须明确失败且 active 保留
                        assertFalse(
                            "append must fail when the seal copy fails mid-way",
                            TokenStatSpool.append(context, line(request("partial-c")), "partial-c"),
                        )
                    } finally {
                        TokenStatSpool.sealCopyForTest = null
                        TokenStatSpool.sealHardLinkForTest = null
                    }
                    assertEquals(
                        "active must be retained with the full content",
                        lineA + "\n" + lineB + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertFalse(
                        "partial target must not remain as a normal sealed segment",
                        spool.listFiles().orEmpty().any { it.isFile && it.name.startsWith("sealed_") },
                    )
                    val isolated = spool.listFiles().orEmpty().single { it.name.startsWith("seal_failed_") }
                    assertEquals("partial bytes must be preserved as isolated evidence", partial, isolated.readText())
                    // 恢复：隔离副本由维护清理；部分内容绝不入 Room（完整内容只排空一次）
                    TokenStatSpool.replay(context)
                    awaitEvent("partial-a")
                    awaitEvent("partial-b")
                    awaitSegmentGone(spool, isolated.name)
                    awaitNoSealedSegments(spool)
                    assertEquals(2, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.sealCopyForTest = null
                    TokenStatSpool.sealHardLinkForTest = null
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
    fun `hardlink seal dir sync UNSUPPORTED never deletes active and never publishes`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("unsupported-link-a"))
                val lineB = line(request("unsupported-link-b"))
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    // 平台明确不支持目录 fsync：发布路径必须 fail-closed（UNSUPPORTED ≠ 成功），
                    // 硬链接已建立但目录项未持久 → 回滚链接、保留唯一 fsynced active、绝不 PUBLISHED。
                    // 前两次 sync 是 P1-1 bootstrap gate（filesDir + spool 目录，已确认），
                    // 第三次是链接创建的目录项，第四次是回滚删除链接的目录项（P2 终审：
                    // 回滚删除同样是目录项变更，必须确认持久，非 OK 同时失效 gate）。
                    var dirSyncCalls = 0
                    TokenStatSpool.dirSyncForTest = {
                        dirSyncCalls += 1
                        if (dirSyncCalls <= 2) TokenStatSpool.DirSyncResult.OK
                        else TokenStatSpool.DirSyncResult.UNSUPPORTED
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        assertFalse(
                            "append must fail when the platform does not support dir fsync",
                            TokenStatSpool.append(context, lineB, "unsupported-link-b"),
                        )
                    } finally {
                        TokenStatSpool.dirSyncForTest = null
                    }
                    assertEquals(4, dirSyncCalls)
                    assertEquals(
                        "active must be retained byte-identical",
                        lineA + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertFalse(
                        "UNSUPPORTED must never publish a sealed segment",
                        spool.listFiles().orEmpty().any { it.isFile && it.name.startsWith("sealed_") },
                    )
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `copy seal dir sync UNSUPPORTED never deletes active and never publishes`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("unsupported-copy-a"))
                val lineB = line(request("unsupported-copy-b"))
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    TokenStatSpool.sealHardLinkForTest = { _, _ -> false }
                    // copy 回退的目录同步同样 fail-closed：目标已处置、active 保留、绝不 PUBLISHED。
                    // 前两次 sync 是 P1-1 bootstrap gate（filesDir + spool 目录，已确认），
                    // 第三次是 copy 目标创建的目录项。
                    var dirSyncCalls = 0
                    TokenStatSpool.dirSyncForTest = {
                        dirSyncCalls += 1
                        if (dirSyncCalls <= 2) TokenStatSpool.DirSyncResult.OK
                        else TokenStatSpool.DirSyncResult.UNSUPPORTED
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        assertFalse(
                            "append must fail when the platform does not support dir fsync",
                            TokenStatSpool.append(context, lineB, "unsupported-copy-b"),
                        )
                    } finally {
                        // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）——
                        // 后续 quarantineEvidence 读取需要恢复 tombstone 写入残留的 `.new`
                        // sidecar（P2 受管证据），strict 读取要求目录 sync OK 才能返回。
                        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                        TokenStatSpool.sealHardLinkForTest = null
                    }
                    // 5 次目录 sync = bootstrap gate(2) + copy 目标创建(1) + 失败目标隔离
                    // rename(1) + tombstone 暂存(1，P2：隔离后目录项未确认 → 按候选 sealed
                    // 身份写 tombstone 受管证据，绝不只留日志)
                    assertEquals(5, dirSyncCalls)
                    assertEquals(
                        "active must be retained byte-identical",
                        lineA + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertFalse(
                        "UNSUPPORTED must never publish a sealed segment",
                        spool.listFiles().orEmpty().any { it.isFile && it.name.startsWith("sealed_") },
                    )
                    // 部分目标被隔离为 seal_failed_*（受管失败发布证据，立即可见）
                    val isolated =
                        spool.listFiles().orEmpty().single { it.isFile && it.name.startsWith("seal_failed_") }
                    assertEquals("isolated target must keep the copied bytes", lineA + "\n", isolated.readText())
                    assertTrue(
                        "isolated target must be visible as quarantine evidence",
                        TokenStatSpool.quarantineEvidence(context).any { it.name == isolated.name },
                    )
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.sealHardLinkForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `unsupported dir sync fails closed never clears active and recovers exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("failclosed-a"))
                val lineB = line(request("failclosed-b"))
                val lineC = line(request("failclosed-c"))
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n" + lineB + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    // 平台明确不支持目录 fsync：封段发布必须 fail-closed——绝不原地清空/删除
                    // 唯一 fsynced active，也绝不返回 durable
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.UNSUPPORTED }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        assertFalse(
                            "append must fail closed when the platform does not support dir fsync",
                            TokenStatSpool.append(context, lineC, "failclosed-c"),
                        )
                    } finally {
                        // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）
                        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    }
                    // active 原字节保留；无 sealed/seal_failed 发布残留；无事件入 Room
                    assertEquals(
                        "active must be retained byte-identical",
                        lineA + "\n" + lineB + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertTrue(
                        "fail-closed mode must never publish sealed segments",
                        spool.listFiles().orEmpty().none { it.isFile && it.name.startsWith("sealed_") },
                    )
                    assertTrue(
                        "fail-closed mode must never create seal_failed targets",
                        spool.listFiles().orEmpty().none { it.isFile && it.name.startsWith("seal_failed_") },
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    // 恢复：目录 fsync 恢复 OK 后 append 成功，全部事件各恰一次入 Room
                    assertTrue(TokenStatSpool.append(context, lineC, "failclosed-c"))
                    TokenStatSpool.replay(context)
                    awaitEvent("failclosed-a")
                    awaitEvent("failclosed-b")
                    awaitEvent("failclosed-c")
                    awaitNoSealedSegments(spool)
                    assertEquals(3, database.tokenStatsDao().countEvents())
                    assertEquals(
                        "each event must be recorded exactly once",
                        setOf("failclosed-a", "failclosed-b", "failclosed-c"),
                        database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                    )
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `first spool directory creation with unsupported dir sync returns false and retries after recovery`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME)
                assertFalse("spool must not pre-exist", spool.exists())
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.UNSUPPORTED }
                TokenStatSpool.shutdownWriterForTest()
                try {
                    assertFalse(
                        "append must not return durable when the first spool dir creation cannot be confirmed",
                        TokenStatSpool.append(context, line(request("first-dir-a")), "first-dir-a"),
                    )
                } finally {
                    // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                }
                // 已创建的目录可保留供重试，但从未声明 durable；active 尚未写入
                assertTrue("created spool dir may be retained for retry", spool.isDirectory)
                assertFalse("active must not be written before the dir entry is durable", File(spool, "active.jsonl").exists())
                assertEquals(0, database.tokenStatsDao().countEvents())
                // 恢复能力 OK：重试成功，事件恰一次入 Room
                assertTrue(
                    TokenStatSpool.append(context, line(request("first-dir-a")), "first-dir-a"),
                )
                TokenStatSpool.replay(context)
                awaitEvent("first-dir-a")
                awaitNoSealedSegments(spool)
                assertEquals(1, database.tokenStatsDao().countEvents())
                assertEquals(
                    setOf("first-dir-a"),
                    database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                )
            }
        }

    @Test
    fun `first active file creation with unsupported dir sync returns false retains line and recovers exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                var dirSyncCalls = 0
                TokenStatSpool.dirSyncForTest = {
                    dirSyncCalls += 1
                    // 前两次是 P1-1 bootstrap gate（filesDir + spool 目录，已确认）；第三次
                    // （首建 active 的目录项）平台不支持——内容已写+fsync 但目录项未确认
                    if (dirSyncCalls <= 2) TokenStatSpool.DirSyncResult.OK
                    else TokenStatSpool.DirSyncResult.UNSUPPORTED
                }
                TokenStatSpool.shutdownWriterForTest()
                val lineA = line(request("first-active-a"))
                try {
                    assertFalse(
                        "append must not return durable when the first active creation dir sync is unsupported",
                        TokenStatSpool.append(context, lineA, "first-active-a"),
                    )
                } finally {
                    // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                }
                // 写+fd.sync 已发生但目录项未确认：本次不 durable，源 line 内容保留在 active
                assertEquals(3, dirSyncCalls)
                assertEquals(
                    "source line must be retained on disk",
                    lineA + "\n",
                    File(spool, "active.jsonl").readText(),
                )
                assertEquals(0, database.tokenStatsDao().countEvents())
                // 恢复能力 OK：下一次 append 先经 bootstrap gate 重新确认目录项再写新事件，
                // 两者各恰一次
                assertTrue(
                    TokenStatSpool.append(context, line(request("first-active-b")), "first-active-b"),
                )
                TokenStatSpool.replay(context)
                awaitEvent("first-active-a")
                awaitEvent("first-active-b")
                awaitNoSealedSegments(spool)
                assertEquals(2, database.tokenStatsDao().countEvents())
                assertEquals(
                    setOf("first-active-a", "first-active-b"),
                    database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                )
            }
        }

    @Test
    fun `unsupported dir sync fails closed for every append until recovery then drains exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME)
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val count = 24
                try {
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.UNSUPPORTED }
                    TokenStatSpool.MAX_SEGMENT_BYTES = 700
                    TokenStatSpool.shutdownWriterForTest()
                    // 目录 fsync 不支持：首次 spool/active 创建无法确认目录项——每次 append
                    // 都 fail-closed（绝不返回 durable、绝不永久挂起、绝不清空已写入内容）
                    repeat(count) { index ->
                        assertFalse(
                            "append must fail closed under unsupported dir sync without stalling: $index",
                            TokenStatSpool.append(context, line(request("win-failclosed-$index")), "win-failclosed-$index"),
                        )
                    }
                    assertTrue("created spool dir may be retained for retry", spool.isDirectory)
                    assertFalse(
                        "active must not be written before any directory entry is durable",
                        File(spool, "active.jsonl").exists(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    // 恢复：能力恢复 OK 后重试/replay，全部事件各恰一次入 Room
                    // （P1 终审：Windows JVM 真实探测恒为 UNSUPPORTED，必须回到注入的 OK）
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    repeat(count) { index ->
                        assertTrue(
                            TokenStatSpool.append(context, line(request("win-failclosed-$index")), "win-failclosed-$index"),
                        )
                    }
                    TokenStatSpool.replay(context)
                    repeat(count) { index -> awaitEvent("win-failclosed-$index") }
                    awaitNoSealedSegments(spool)
                    assertEquals(count, database.tokenStatsDao().countEvents())
                    assertEquals(
                        count,
                        database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet().size,
                    )
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `corrupt active tail with unsupported dir sync fails closed retaining original bytes then recovers exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("corrupt-failclosed-a"))
                val lineB = line(request("corrupt-failclosed-b"))
                // 崩溃残留：active 尾部半行 JSON，无换行
                val original = lineA + "\n" + "{\"v\":2,\"eventId\":\"corrupt-failclosed-tail\""
                try {
                    File(spool, "active.jsonl").writeText(original)
                    TokenStatSpool.MAX_SEGMENT_BYTES = original.length.toLong() + 1
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.UNSUPPORTED }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        // 损坏尾行需要封段处置：目录项未确认持久前绝不隔离证据、绝不插入
                        // 健康行、绝不截断/清空 active（copy+file sync 之后必须 dir sync OK
                        // 才允许继续）
                        assertFalse(
                            "append must fail closed when sealing a corrupt tail needs dir fsync",
                            TokenStatSpool.append(context, lineB, "corrupt-failclosed-b"),
                        )
                    } finally {
                        // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）
                        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    }
                    // active 原字节不动（含损坏尾行）；尚无证据被切走；无事件入 Room
                    assertEquals(
                        "active must retain the original bytes including the corrupt tail",
                        original,
                        File(spool, "active.jsonl").readText(),
                    )
                    assertTrue(
                        "no evidence may be cut before its directory entry is durable",
                        TokenStatSpool.quarantineEvidence(context).isEmpty(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    // 恢复：目录 fsync OK 后损坏尾行作为完整证据隔离（至少一个 durable 位置），
                    // 健康事件各恰一次入 Room
                    assertTrue(TokenStatSpool.append(context, lineB, "corrupt-failclosed-b"))
                    TokenStatSpool.replay(context)
                    awaitEvent("corrupt-failclosed-a")
                    awaitEvent("corrupt-failclosed-b")
                    awaitNoSealedSegments(spool)
                    assertEquals(2, database.tokenStatsDao().countEvents())
                    val evidence = TokenStatSpool.quarantineEvidence(context)
                    assertTrue(
                        "corrupt tail evidence must be preserved with the original bytes",
                        evidence.any { it.readText() == original },
                    )
                } finally {
                    TokenStatSpool.dirSyncForTest = null
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
    fun `bootstrap gate re-confirms unconfirmed spool dir entry after simulated restart and never merges events`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME)
                val lineA = line(request("bootstrap-dir-a"))
                val lineB = line(request("bootstrap-dir-b"))
                try {
                    // 上一进程：首次创建 spool 目录，父目录/新目录的目录项 sync 失败（磁盘
                    // 可见但未确认持久）——append 明确失败，active 未写入
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.FAILED }
                    TokenStatSpool.shutdownWriterForTest()
                    assertFalse(TokenStatSpool.append(context, lineA, "bootstrap-dir-a"))
                    assertTrue("created spool dir is visible on disk", spool.isDirectory)
                    assertFalse("no line may be written before dir entries are durable", File(spool, "active.jsonl").exists())
                    assertEquals(0, database.tokenStatsDao().countEvents())

                    // 模拟进程重启：清空全部内存状态（含 bootstrap gate 标记），磁盘状态保留
                    TokenStatSpool.clearPendingStateForTest()
                    // 目录项仍无法确认：本次 append 必须失败，绝不写新行（第二事件此前从未写入）
                    assertFalse(TokenStatSpool.append(context, lineB, "bootstrap-dir-b"))
                    assertFalse(
                        "no line may be written while the spool dir entry is unconfirmed",
                        File(spool, "active.jsonl").exists(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())

                    // 恢复：bootstrap gate 重新确认目录项后，两个事件各恰一次入 Room
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    assertTrue(TokenStatSpool.append(context, lineA, "bootstrap-dir-a"))
                    assertTrue(TokenStatSpool.append(context, lineB, "bootstrap-dir-b"))
                    TokenStatSpool.replay(context)
                    awaitEvent("bootstrap-dir-a")
                    awaitEvent("bootstrap-dir-b")
                    awaitNoSealedSegments(spool)
                    assertEquals(2, database.tokenStatsDao().countEvents())
                    assertEquals(
                        setOf("bootstrap-dir-a", "bootstrap-dir-b"),
                        database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                    )
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                }
            }
        }

    @Test
    fun `bootstrap gate re-confirms unconfirmed active entry after simulated restart and keeps bytes until confirmed`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val lineA = line(request("bootstrap-active-a"))
                val lineB = line(request("bootstrap-active-b"))
                var dirSyncCalls = 0
                try {
                    // 上一进程：bootstrap gate 两次确认通过，但首建 active 的目录项 sync 失败
                    // （内容已写+fsync、磁盘可见、未确认）
                    TokenStatSpool.dirSyncForTest = {
                        dirSyncCalls += 1
                        if (dirSyncCalls <= 2) TokenStatSpool.DirSyncResult.OK
                        else TokenStatSpool.DirSyncResult.FAILED
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    assertFalse(TokenStatSpool.append(context, lineA, "bootstrap-active-a"))
                    assertEquals(3, dirSyncCalls)
                    assertEquals(
                        "unconfirmed line must stay visible on disk",
                        lineA + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())

                    // 模拟进程重启：清空全部内存状态（含 bootstrap gate 标记），active 字节保留
                    TokenStatSpool.clearPendingStateForTest()
                    // 目录项仍无法确认：本次 append 失败且 active 字节不变（绝不再追加新行）
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.FAILED }
                    assertFalse(TokenStatSpool.append(context, lineB, "bootstrap-active-b"))
                    assertEquals(
                        "active bytes must be unchanged while the dir entry is unconfirmed",
                        lineA + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())

                    // 恢复：gate 重新确认后追加第二事件（此前从未写入），两事件各恰一次
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    assertTrue(TokenStatSpool.append(context, lineB, "bootstrap-active-b"))
                    TokenStatSpool.replay(context)
                    awaitEvent("bootstrap-active-a")
                    awaitEvent("bootstrap-active-b")
                    awaitNoSealedSegments(spool)
                    assertEquals(2, database.tokenStatsDao().countEvents())
                    assertEquals(
                        setOf("bootstrap-active-a", "bootstrap-active-b"),
                        database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                    )
                } finally {
                    TokenStatSpool.dirSyncForTest = null
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
    fun `maintenance seal dir sync failure after gate true forces the next append to re-bootstrap before writing`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val lineA = line(request("gate-maint-a"))
                val lineB = line(request("gate-maint-b"))
                var calls = 0
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n")
                    // 阶段 1：bootstrap gate 两次确认 OK（gate=true），随后维护 drain 的封段
                    // 发布目录项 sync（第 3 次）失败 → 维护失败；gate 必须同步失效（P1-1）
                    TokenStatSpool.dirSyncForTest = {
                        calls += 1
                        if (calls != 3) TokenStatSpool.DirSyncResult.OK
                        else TokenStatSpool.DirSyncResult.FAILED
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    TokenStatSpool.replay(context)
                    val sealDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (calls < 3 && System.nanoTime() < sealDeadline) delay(10)
                    TokenStatSpool.shutdownWriterForTest()
                    assertTrue("seal must have been attempted", calls >= 3)
                    // 阶段 2：gate 已失效且目录 sync 持续失败——下一次 append 必须重新
                    // bootstrap；bootstrap 失败 → append 明确失败且 active 字节不变
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.FAILED }
                    assertFalse(TokenStatSpool.append(context, lineB, "gate-maint-b"))
                    assertEquals(
                        "active must stay byte-identical",
                        lineA + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    // 阶段 3：恢复——bootstrap 重新确认后 append 成功，事件各恰一次
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    assertTrue(TokenStatSpool.append(context, lineB, "gate-maint-b"))
                    TokenStatSpool.replay(context)
                    awaitEvent("gate-maint-a")
                    awaitEvent("gate-maint-b")
                    awaitNoSealedSegments(spool)
                    assertEquals(2, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                }
            }
        }

    /**
     * P1-2/P1-3 终审通用 runner：以 [result]（FAILED/UNSUPPORTED）运行一次完整场景，保证
     * 前后内存/磁盘/数据库状态隔离（spool 目录重建 + 内存标记复位 + 事件表清空），并在
     * finally 还原全部注入缝。场景开始前恢复“目录 fsync 支持且成功”的平台常态
     * （Windows JVM 真实探测恒为 UNSUPPORTED），使场景内部的 phase-1 正常协议可用。
     */
    private suspend fun runDirSyncFailClosedScenario(
        result: TokenStatSpool.DirSyncResult,
        tag: String,
        scenario: suspend (TokenStatSpool.DirSyncResult, String) -> Unit,
    ) {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME)
        Mockito.mockStatic(AppLogger::class.java).use {
            try {
                spool.deleteRecursively()
                TokenStatSpool.clearPendingStateForTest()
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                database.tokenStatsDao().deleteAllEvents()
                scenario(result, tag)
            } finally {
                TokenStatSpool.dirSyncForTest = null
                TokenStatSpool.segmentRenameForTest = null
                TokenStatSpool.segmentDeleteForTest = null
                TokenStatSpool.ackAtomicMoveForTest = null
                TokenStatSpool.spoolDeleteForTest = null
                spool.deleteRecursively()
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
                delay(900)
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
    fun `pending-delete reverse rename not durable keeps retryable record and recovers exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val body = "{reverse-rename-bad\n"
                File(spool, "sealed_1.jsonl").writeText(body)
                File(spool, "sealed_2.jsonl").writeText(line(request("reverse-rename-healthy")) + "\n")
                // 阶段 1：证据区 rename 失败 → pending-delete 有界证据；健康段照常排空
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    if (to.name.startsWith("quarantine_") && !to.name.startsWith("quarantine_pending_delete_")) {
                        false
                    } else {
                        null
                    }
                }
                TokenStatSpool.replay(context)
                awaitEvent("reverse-rename-healthy")
                val pending = spool.listFiles().orEmpty().single {
                    it.isFile && it.name.startsWith("quarantine_pending_delete_")
                }
                TokenStatSpool.segmentRenameForTest = null
                assertEquals(1, database.tokenStatsDao().countEvents())
                // 阶段 2：维护恢复 rename 可见但目录项 sync 失败 → 尽力反向 rename 回
                // pending-delete 名（重建明确可重试记录）；反向 rename 的目录项同样必须严格
                // sync（P2 终审），未确认持久绝不视为已重建 → 本轮退避，记录保留
                var failSyncs = true
                TokenStatSpool.dirSyncForTest = {
                    if (failSyncs) TokenStatSpool.DirSyncResult.FAILED
                    else TokenStatSpool.DirSyncResult.OK
                }
                TokenStatSpool.shutdownWriterForTest()
                TokenStatSpool.replay(context)
                delay(900)
                assertTrue(
                    "pending-delete record must be rebuilt when the restore rename is not durable",
                    pending.exists(),
                )
                val evidence = TokenStatSpool.quarantineEvidence(context)
                assertEquals(1, evidence.size)
                assertTrue(
                    "evidence must still be the pending-delete record",
                    evidence.single().name.startsWith("quarantine_pending_delete_"),
                )
                // 退避期间任何 append 都不发布新字节（gate 已失效，bootstrap 重新确认前拒绝）
                assertFalse(
                    TokenStatSpool.append(context, line(request("reverse-rename-blocked")), "reverse-rename-blocked"),
                )
                assertFalse(
                    "no event may be written while dir entries are unconfirmed",
                    File(spool, "active.jsonl").exists(),
                )
                assertEquals(1, database.tokenStatsDao().countEvents())
                // 阶段 3：恢复——记录移回完整证据区，事件仍恰一次，后续 append 正常
                failSyncs = false
                TokenStatSpool.shutdownWriterForTest()
                TokenStatSpool.replay(context)
                awaitNoPendingEvidence(spool)
                val restored = TokenStatSpool.quarantineEvidence(context)
                assertEquals(1, restored.size)
                assertTrue("full evidence must be restored exactly once", restored.single().readText() == body)
                assertEquals(1, database.tokenStatsDao().countEvents())
                assertTrue(TokenStatSpool.append(context, line(request("reverse-rename-post")), "reverse-rename-post"))
                TokenStatSpool.replay(context)
                awaitEvent("reverse-rename-post")
                assertEquals(2, database.tokenStatsDao().countEvents())
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

    private fun padLineTo(line: String, targetBytes: Int): String {
        val overhead = ",\"pad\":\"\"".toByteArray(Charsets.UTF_8).size
        val current = (line + "\n").toByteArray(Charsets.UTF_8).size
        val padding = targetBytes - current - overhead
        check(padding >= 0) { "line too large to pad: $current + $overhead > $targetBytes" }
        return line + ",\"pad\":\"" + "x".repeat(padding) + "\""
    }

    /**
     * manifest 轮询安全读：drain 线程可能正在原子替换该文件，Windows 下同一瞬间的读取
     * 会以共享冲突失败；失败时短暂重试，持续失败返回 null 由调用方重试/断言兜底。
     */
    private fun safeManifestText(manifest: File): String? {
        repeat(3) {
            try {
                return if (manifest.isFile) manifest.readText() else null
            } catch (e: Exception) {
                Thread.sleep(10)
            }
        }
        return null
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private suspend fun awaitNoPendingEvidence(spool: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline &&
            spool.listFiles().orEmpty().any { it.isFile && it.name.startsWith("quarantine_pending_delete_") }
        ) {
            delay(20)
        }
    }

    private suspend fun awaitSegmentGone(spool: File, name: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && File(spool, name).exists()) delay(20)
    }

    private suspend fun awaitManifestWithout(spool: File, name: String) {
        val manifest = File(spool, "quarantine_skip_manifest.jsonl")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline &&
            safeManifestText(manifest)?.contains(name) == true
        ) {
            delay(20)
        }
        assertFalse(
            "tombstone entry must be removed after cleanup",
            safeManifestText(manifest)?.contains(name) == true,
        )
    }

    private suspend fun awaitNoSealedSegments(spool: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline &&
            spool.listFiles().orEmpty().any { it.isFile && it.name.startsWith("sealed_") }
        ) {
            delay(20)
        }
    }
}
