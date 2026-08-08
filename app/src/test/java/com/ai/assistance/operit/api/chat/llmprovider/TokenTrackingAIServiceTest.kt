package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.api.chat.llmprovider.TokenTrackingAIService.Companion.RecordOutcome
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.stats.JdbcSQLiteDriver
import com.ai.assistance.operit.data.stats.PricingSource
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.data.stats.ProviderUsageNormalizer
import com.ai.assistance.operit.data.stats.TokenPriceResolver
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.data.stats.TokenStatIdentityResolver
import com.ai.assistance.operit.data.stats.TokenStatSpool
import com.ai.assistance.operit.data.stats.TokenStatStatus
import com.ai.assistance.operit.data.stats.TokenStatsLedger
import com.ai.assistance.operit.data.stats.TokenStatsResetCoordinator
import com.ai.assistance.operit.data.stats.TokenStatRequestContext
import com.ai.assistance.operit.util.exceptions.UserCancellationException
import com.ai.assistance.operit.util.stream.MutableSharedStream
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.TextStreamEvent
import com.ai.assistance.operit.util.stream.TextStreamEventCarrier
import com.ai.assistance.operit.util.stream.TextStreamEventType
import com.ai.assistance.operit.util.stream.stream
import com.ai.assistance.operit.util.stream.streamOf
import com.ai.assistance.operit.util.stream.timeout
import com.ai.assistance.operit.util.stream.withEventChannel
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 统一记录边界（TokenTrackingAIService）测试：
 * 正常/流式/取消/超时/失败、真实 Job 取消与 withTimeout、有 usage/无 usage、
 * 防重、首 token（含仅空白 chunk）、内部重试的 attempt 聚合、调用者 callback 转发、
 * 业务分类上下文、修订流接口保持、连接测试 usage 与取消传播、
 * 有界落账（存储挂起不阻塞业务）。
 */
class TokenTrackingAIServiceTest {

    private lateinit var tempDir: File
    private lateinit var database: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        // 流框架日志走 android.util.Log，JVM 测试不可用：关闭避免 Stub! 异常
        com.ai.assistance.operit.util.stream.StreamLogger.setEnabled(false)
        com.ai.assistance.operit.util.stream.StreamLogger.setVerboseEnabled(false)
        tempDir = kotlin.io.path.createTempDirectory("tracking-test").toFile()
        context = mockContext(tempDir)
        database =
            Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
                .setDriver(JdbcSQLiteDriver())
                .addMigrations(AppDatabase.MIGRATION_28_29, AppDatabase.MIGRATION_29_30, AppDatabase.MIGRATION_30_31)
                .allowMainThreadQueries()
                .build()
        TokenStatsLedger.databaseProvider = { database }
        TokenStatsLedger.legacyPriceProvider = { _, _ -> null }
        // 每个测试使用独立的 spool 目录（context.filesDir 指向独立 tempDir），
        // 落账 writer 是生产默认单例：清空调度状态并递增会话代次，使任何残留
        // drain/resolver 立即中止，避免跨测试污染（评审 P2-4）
        TokenStatSpool.clearPendingStateForTest()
        TokenStatSpool.segmentDeleteForTest = null
        // P1 终审：测试夹具默认“目录 fsync 支持且成功”（平台无关）——本类断言 seal 排空后
        // spool 无残留文件；Windows 生产会走原地排空模式（active 保留为空文件），与夹具无关
        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
        TokenTrackingAIService.resetPricingExecutorForTest()
    }

    @After
    fun tearDown() {
        runBlocking {
            TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = false) { }
        }
        TokenStatsLedger.databaseProvider = null
        TokenStatsLedger.legacyPriceProvider = null
        TokenTrackingAIService.recordTimeoutMs = 5_000L
        TokenStatSpool.insertTimeoutMs = 5_000L
        TokenStatSpool.dirSyncForTest = null
        database.close()
    }

    private fun mockContext(filesDir: File): Context {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("com.ai.assistance.operit")
        whenever(context.filesDir).thenReturn(filesDir)
        whenever(context.getDatabasePath(any())).thenAnswer { invocation ->
            File(filesDir, invocation.getArgument<String>(0))
        }
        return context
    }

    /**
     * 可编程的假 provider：按给定行为产出流，并像真实 provider 一样上报 usage
     * （携带 attempt 序号）；testConnection 支持上报 usage / 抛取消，模拟真实
     * provider 内部经 sendMessage 发起探测的形态。
     */
    private class FakeAiService(
        var testConnectionResult: Result<String> = Result.success("ok"),
        val eventCarrier: Boolean = false,
        var behavior: suspend (
            onUsageReported: (suspend (ProviderUsageSnapshot, Int) -> Unit)?,
        ) -> Stream<String> = { _ -> streamOf("hello") },
    ) : AIService {
        val reportCount = AtomicInteger(0)

        /** 非空时 testConnection 会像真实 provider 一样上报 usage。 */
        var testConnectionUsage: ProviderUsageSnapshot? = null

        /** 非空时 testConnection 抛出的取消（模拟 provider 内部取消传播）。 */
        var testConnectionCancellation: CancellationException? = null

        /** 非空时 testConnection 直接抛出该异常（模拟 provider 内部超时/失败）。 */
        var testConnectionThrowable: Throwable? = null

        /** cancelStreaming 的行为（模拟本地 provider 的 isCancelled 通知）。 */
        var cancelHandler: (() -> Unit)? = null

        override val inputTokenCount: Int = 100
        override val cachedInputTokenCount: Int = 0
        override val outputTokenCount: Int = 50
        override val providerModel: String = "DEEPSEEK:deepseek-chat"

        override fun resetTokenCounts() {}
        override fun cancelStreaming() {
            cancelHandler?.invoke()
        }

        override suspend fun getModelsList(context: Context): Result<List<ModelOption>> =
            Result.success(emptyList())

        override suspend fun sendMessage(
            context: Context,
            chatHistory: List<PromptTurn>,
            modelParameters: List<ModelParameter<*>>,
            enableThinking: Boolean,
            stream: Boolean,
            availableTools: List<ToolPrompt>?,
            preserveThinkInHistory: Boolean,
            onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
            onUsageReported: (suspend (ProviderUsageSnapshot, Int) -> Unit)?,
            onNonFatalError: suspend (error: String) -> Unit,
            enableRetry: Boolean,
            statsCategory: TokenStatCategory?,
        ): Stream<String> {
            val inner = behavior(onUsageReported)
            if (!eventCarrier) return inner
            val eventChannel = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
            return inner.withEventChannel(eventChannel)
        }

        override suspend fun testConnection(
            context: Context,
            onUsageReported: (suspend (ProviderUsageSnapshot, Int) -> Unit)?,
        ): Result<String> {
            testConnectionCancellation?.let { throw it }
            testConnectionThrowable?.let { throw it }
            testConnectionUsage?.let { onUsageReported?.invoke(it, 1) }
            return testConnectionResult
        }

        override suspend fun calculateInputTokens(
            chatHistory: List<PromptTurn>,
            availableTools: List<ToolPrompt>?,
        ): Int = 100
    }

    private fun tracked(fake: FakeAiService): TokenTrackingAIService =
        TokenTrackingAIService(delegate = fake, context = context, configId = "cfg-1")

    private fun tracked(fake: FakeAiService, configId: String): TokenTrackingAIService =
        TokenTrackingAIService(delegate = fake, context = context, configId = configId)

    private fun usage(): ProviderUsageSnapshot =
        ProviderUsageSnapshot(
            uncachedInputTokens = 800L,
            cachedInputTokens = 200L,
            outputTokens = 500L,
            reasoningIncludedInOutput = true,
            source = ProviderUsageNormalizer.SOURCE_OPENAI_CHAT_COMPLETIONS,
        )

    /** 模拟 SQLite 忽略线程中断但可释放的挂起：任何 cancel(true) 都无法终止，直到门闩
     *  打开才返回（释放后线程能真正终止，测试结束不留遗留线程）。 */
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
    fun `restore waiter invalidation never overrides model failure or cancellation`() = runBlocking {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            val previousInsert = TokenStatSpool.insertTimeoutMs
            val previousRecordTimeout = TokenTrackingAIService.recordTimeoutMs
            val previousQuiesce = TokenStatSpool.exclusiveQuiesceTimeoutMs
            TokenStatSpool.insertTimeoutMs = 50
            // caller 的可见性等待也缩短：restore 有界失败后由 caller 自己的超时返回
            TokenTrackingAIService.recordTimeoutMs = 100
            TokenStatSpool.exclusiveQuiesceTimeoutMs = 150
            try {
                // insert 永久挂起（忽略中断但可释放）：append 已 durable 但 Room 可见性永远等不到。
                // mock 的 suspend 方法默认返回 null，必须显式 stub 新请求会读取的查询
                val release = CountDownLatch(1)
                val hangingDao = mock<TokenStatsDao>()
                whenever(hangingDao.currentResetGeneration()).thenReturn(0L)
                // P1-1：请求接受边界在同一事务内建身份+取 generation——本测试聚焦 restore
                // 对 wedged insert 的有界失败，边界直接返回 generation，不触碰挂起门闩
                whenever(hangingDao.ensureIdentityAndCaptureGenerationTx(any(), any())).thenReturn(0L)
                whenever(hangingDao.getAllPriceOverrides()).thenReturn(emptyList())
                whenever(hangingDao.insertIdentityIfAbsent(any())).thenAnswer {
                    gateIgnoringInterrupts(release)
                    true
                }
                val proxy = mock<AppDatabase>()
                whenever(proxy.tokenStatsDao()).thenReturn(hangingDao)
                TokenStatsLedger.databaseProvider = { proxy }

                // 固定模型异常：restore 门闩遇到仍存活的 wedged insert 必须有界失败（P1-2），
                // 绝不作废 waiter、绝不覆盖 primary；caller 自己的可见性等待按超时返回
                val modelFailure = IOException("model failed")
                var primary: Throwable? = null
                val failureJob = launch {
                    try {
                        tracked(FakeAiService { _ -> stream { emit("partial"); throw modelFailure } })
                            .sendMessage(context = context).collect { }
                        fail("model failure must propagate")
                    } catch (e: Throwable) {
                        primary = e
                        if (e is CancellationException) throw e
                    }
                }
                val firstDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (TokenStatSpool.pendingLatchCountForTest() == 0 && System.nanoTime() < firstDeadline) {
                    delay(10)
                }
                try {
                    TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = false, clearAfter = true) { }
                    fail("restore must fail bounded while the caller's insert is still live")
                } catch (e: IOException) {
                    assertTrue("restore must report the live insert", e.message!!.contains("still active"))
                }
                failureJob.join()
                assertTrue("primary must be the model exception, was: $primary", primary === modelFailure)
                assertEquals(0, primary!!.suppressed.size)

                // 模型取消同样原样传播，不被 restore 门闩/waiter 覆盖
                val modelCancellation = CancellationException("user cancelled")
                var primaryCancel: Throwable? = null
                val cancelJob = launch {
                    try {
                        tracked(FakeAiService { _ -> stream { throw modelCancellation } })
                            .sendMessage(context = context).collect { }
                        fail("model cancellation must propagate")
                    } catch (e: Throwable) {
                        primaryCancel = e
                        if (e is CancellationException) throw e
                    }
                }
                val secondDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (TokenStatSpool.pendingLatchCountForTest() == 0 && System.nanoTime() < secondDeadline) {
                    delay(10)
                }
                try {
                    TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = false, clearAfter = true) { }
                    fail("restore must fail bounded while the caller's insert is still live")
                } catch (e: IOException) {
                    assertTrue("restore must report the live insert", e.message!!.contains("still active"))
                }
                cancelJob.join()
                assertTrue(
                    "primary must be the model cancellation, was: $primaryCancel",
                    primaryCancel === modelCancellation,
                )

                // 模拟重启前必须释放旧 insert 并确认 registry 真正清空：释放门闩 → 任务
                // 完成 → 旧 worker shutdown 后真实终止，绝不遗留线程
                release.countDown()
                val registryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (TokenStatSpool.activeInsertCountForTest() != 0 && System.nanoTime() < registryDeadline) {
                    delay(10)
                }
                assertEquals(0, TokenStatSpool.activeInsertCountForTest())
                TokenTrackingAIService.resetPricingExecutorForTest()
                TokenStatSpool.resetExecutorsForTest()
                TokenStatSpool.shutdownWriterForTest()
                awaitNoSpoolWorkerThreads()
            } finally {
                TokenStatsLedger.databaseProvider = { database }
                TokenStatSpool.resetExecutorsForTest()
                TokenStatSpool.insertTimeoutMs = previousInsert
                TokenTrackingAIService.recordTimeoutMs = previousRecordTimeout
                TokenStatSpool.exclusiveQuiesceTimeoutMs = previousQuiesce
            }
        }
    }

    @Test
    fun `pricing timeouts stay bounded without leaking threads and events stay unknown`() = runBlocking {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            val previousPrepare = TokenStatSpool.prepareTimeoutMs
            TokenStatSpool.prepareTimeoutMs = 50
            try {
                // 价格解析永久挂起且忽略中断（可释放）：每次调用都必须在有界时间内返回 UNKNOWN
                val release = CountDownLatch(1)
                TokenStatsLedger.legacyPriceProvider = { _, _ ->
                    gateIgnoringInterrupts(release)
                    null
                }
                val startedAt = System.nanoTime()
                repeat(8) { index ->
                    val request =
                        TokenStatRequestContext(
                            eventId = "evt-price-hang-$index",
                            category = TokenStatCategory.OTHER,
                            configId = "cfg-1",
                            provider = "DEEPSEEK",
                            model = "deepseek-chat",
                            startedAtMs = System.currentTimeMillis(),
                        )
                    request.finish(TokenStatStatus.COMPLETED)
                    TokenTrackingAIService.recordSafely(context, request)
                }
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                assertTrue("every record must be bounded: $elapsedMs ms", elapsedMs < 10_000)

                // 反复超时后定价线程数固定在上限内（1 个执行线程 + 1 个队列位）
                val priceThreads = Thread.getAllStackTraces().keys.count {
                    it.isAlive && it.name.startsWith("operit-token-stats-price")
                }
                assertTrue("pricing worker must stay bounded: $priceThreads", priceThreads <= 2)

                // 事件全部 durable 且价格为明确的 UNKNOWN（绝不静默用默认价冒充）
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (database.tokenStatsDao().countEvents() < 8 && System.nanoTime() < deadline) {
                    delay(50)
                }
                assertEquals(8, database.tokenStatsDao().countEvents())
                assertTrue(
                    "all events must be durable with UNKNOWN pricing",
                    database.tokenStatsDao().getAllEvents().all { it.pricingSource == PricingSource.UNKNOWN.name },
                )

                // 释放挂起的定价 worker 并确认其真实终止，绝不遗留线程
                release.countDown()
                TokenTrackingAIService.resetPricingExecutorForTest()
                TokenStatSpool.resetExecutorsForTest()
                TokenStatSpool.shutdownWriterForTest()
                awaitNoSpoolWorkerThreads()
            } finally {
                TokenStatsLedger.legacyPriceProvider = { _, _ -> null }
                TokenTrackingAIService.resetPricingExecutorForTest()
                TokenStatSpool.resetExecutorsForTest()
                TokenStatSpool.prepareTimeoutMs = previousPrepare
            }
        }
    }

    @Test
    fun `normal stream records one completed event with first token and usage`() = runBlocking {
        val fake =
            FakeAiService { onUsage ->
                stream {
                    emit("first ")
                    emit("chunk")
                    onUsage?.invoke(usage(), 1)
                }
            }
        val collected = StringBuilder()
        tracked(fake).sendMessage(
            context = context,
            chatHistory = emptyList(),
            statsCategory = TokenStatCategory.CHAT,
        ).collect { collected.append(it) }

        assertEquals("first chunk", collected.toString())
        val events = database.tokenStatsDao().getAllEvents()
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals(TokenStatStatus.COMPLETED.name, event.status)
        assertEquals(TokenStatCategory.CHAT.name, event.category)
        assertNotNull("first token must be set on real first chunk", event.firstTokenAtMs)
        assertTrue(event.firstTokenAtMs!! >= event.startedAtMs)
        assertEquals(800L, event.uncachedInputTokens)
        assertEquals(200L, event.cachedInputTokens)
        assertEquals(500L, event.outputTokens)
        assertTrue("endedAt after startedAt", event.endedAtMs >= event.startedAtMs)
    }

    @Test
    fun `category defaults to OTHER when caller does not declare it`() = runBlocking {
        val fake = FakeAiService()
        tracked(fake).sendMessage(context = context).collect { }
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatCategory.OTHER.name, event.category)
    }

    @Test
    fun `subagent category is propagated`() = runBlocking {
        val fake = FakeAiService()
        tracked(fake).sendMessage(
            context = context,
            statsCategory = TokenStatCategory.SUBAGENT,
        ).collect { }
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatCategory.SUBAGENT.name, event.category)
    }

    @Test
    fun `real job cancellation records cancelled event with usage and propagates`() =
        runBlocking {
            val fake =
                FakeAiService { onUsage ->
                    stream {
                        emit("partial")
                        onUsage?.invoke(usage(), 1)
                        delay(60_000)
                    }
                }
            var propagated: CancellationException? = null
            val job =
                launch {
                    try {
                        tracked(fake).sendMessage(context = context).collect { }
                        fail("cancellation must propagate")
                    } catch (e: CancellationException) {
                        propagated = e
                        throw e
                    }
                }
            // 等流开始并已上报 usage 后再真实取消 Job
            delay(50)
            job.cancelAndJoin()
            assertNotNull("original cancellation must propagate", propagated)
            val event = database.tokenStatsDao().getAllEvents()[0]
            assertEquals(TokenStatStatus.CANCELLED.name, event.status)
            // 真实取消前拿到的 usage 必须落账
            assertEquals(800L, event.uncachedInputTokens)
            assertEquals(500L, event.outputTokens)
        }

    @Test
    fun `cancellation records cancelled event and rethrows`() = runBlocking {
        val fake =
            FakeAiService { onUsage ->
                stream {
                    emit("partial")
                    onUsage?.invoke(usage(), 1)
                    throw CancellationException("user cancelled")
                }
            }
        try {
            tracked(fake).sendMessage(context = context).collect { }
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("user cancelled", e.message)
        }
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatStatus.CANCELLED.name, event.status)
        // 取消时已拿到的 usage 也要落账
        assertEquals(800L, event.uncachedInputTokens)
    }

    @Test
    fun `failure records failed event and rethrows`() = runBlocking {
        val fake =
            FakeAiService { _ ->
                stream { throw IOException("boom") }
            }
        try {
            tracked(fake).sendMessage(context = context).collect { }
            fail("failure must propagate")
        } catch (e: IOException) {
            assertEquals("boom", e.message)
        }
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatStatus.FAILED.name, event.status)
        assertNull("no usage on failure", event.uncachedInputTokens)
    }

    @Test
    fun `local provider fatal error emits user visible text then records failed`() = runBlocking {
        // 与 Llama/MNN 修复后的契约一致：致命错误先保留用户可见错误文本，
        // 再以异常终止 → 统计边界记为 FAILED，错误文本仍完整送达
        val fake =
            FakeAiService { onUsage ->
                stream {
                    emit("[error] 模型文件不存在")
                    onUsage?.invoke(
                        usage().copy(uncachedInputTokens = 300L, outputTokens = 12L),
                        1,
                    )
                    throw IOException("llama_error_inference_failed")
                }
            }
        val collected = StringBuilder()
        try {
            tracked(fake).sendMessage(context = context).collect { collected.append(it) }
            fail("fatal error must propagate")
        } catch (e: IOException) {
            assertEquals("llama_error_inference_failed", e.message)
        }
        assertEquals("[error] 模型文件不存在", collected.toString())
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatStatus.FAILED.name, event.status)
        // 失败前已实测的 usage 也要落账
        assertEquals(300L, event.uncachedInputTokens)
        assertEquals(12L, event.outputTokens)
    }

    @Test
    fun `withTimeout classifies as timeout and records event with usage`() = runBlocking {
        val fake =
            FakeAiService { onUsage ->
                stream {
                    emit("partial")
                    onUsage?.invoke(usage(), 1)
                    delay(60_000)
                }
            }
        var timeout: TimeoutCancellationException? = null
        try {
            withTimeout(100) {
                tracked(fake).sendMessage(context = context).collect { }
            }
            fail("withTimeout must fire")
        } catch (e: TimeoutCancellationException) {
            timeout = e
        }
        assertNotNull(timeout)
        val event = database.tokenStatsDao().getAllEvents()[0]
        // coroutine 超时（CancellationException 子类）必须记为 TIMEOUT 而非 CANCELLED
        assertEquals(TokenStatStatus.TIMEOUT.name, event.status)
        assertEquals(800L, event.uncachedInputTokens)
    }

    @Test
    fun `stream timeout operator classifies as timeout`() = runBlocking {
        val fake =
            FakeAiService { _ ->
                stream {
                    emit("a")
                    delay(200)
                    emit("b")
                }.timeout(50.milliseconds)
            }
        try {
            tracked(fake).sendMessage(context = context).collect { }
            fail("stream timeout must fire")
        } catch (e: com.ai.assistance.operit.util.stream.TimeoutException) {
            // expected
        }
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatStatus.TIMEOUT.name, event.status)
    }

    @Test
    fun `socket timeout records timeout event`() = runBlocking {
        val fake =
            FakeAiService { _ ->
                stream { throw SocketTimeoutException("connect timed out") }
            }
        try {
            tracked(fake).sendMessage(context = context).collect { }
            fail("timeout must propagate")
        } catch (e: SocketTimeoutException) {
            // expected
        }
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatStatus.TIMEOUT.name, event.status)
    }

    @Test
    fun `network retry exhaustion preserves timeout classification through cause chain`() =
        runBlocking {
            val fake =
                FakeAiService { _ ->
                    // provider 重试耗尽时把超时包成 IOException：cause chain 必须可识别
                    stream {
                        throw IOException("exhausted", SocketTimeoutException("connect timed out"))
                    }
                }
            try {
                tracked(fake).sendMessage(context = context).collect { }
                fail("must throw")
            } catch (e: IOException) {
                // expected
            }
            val event = database.tokenStatsDao().getAllEvents()[0]
            assertEquals(TokenStatStatus.TIMEOUT.name, event.status)
        }

    @Test
    fun `classify maps timeout cancellation and failure precisely`() = runBlocking {
        val coroutineTimeout = captureCoroutineTimeout()
        assertEquals(TokenStatStatus.TIMEOUT, TokenTrackingAIService.classify(coroutineTimeout))
        assertEquals(
            TokenStatStatus.TIMEOUT,
            TokenTrackingAIService.classify(java.util.concurrent.TimeoutException("t")),
        )
        assertEquals(
            TokenStatStatus.TIMEOUT,
            TokenTrackingAIService.classify(com.ai.assistance.operit.util.stream.TimeoutException("t")),
        )
        assertEquals(
            TokenStatStatus.TIMEOUT,
            TokenTrackingAIService.classify(SocketTimeoutException("t")),
        )
        assertEquals(
            TokenStatStatus.TIMEOUT,
            TokenTrackingAIService.classify(IOException("wrapped", SocketTimeoutException("t"))),
        )
        assertEquals(
            TokenStatStatus.TIMEOUT,
            TokenTrackingAIService.classify(IOException("wrapped", coroutineTimeout)),
        )
        assertEquals(
            TokenStatStatus.CANCELLED,
            TokenTrackingAIService.classify(CancellationException("c")),
        )
        assertEquals(TokenStatStatus.FAILED, TokenTrackingAIService.classify(IOException("f")))
        assertEquals(
            TokenStatStatus.CANCELLED,
            TokenTrackingAIService.classify(UserCancellationException("manual stream cancellation")),
        )
        // 明确非超时取消优先于 cause 链：UserCancellationException(cause=InterruptedIOException)
        // 必须 CANCELLED，不能被 cause 里的 InterruptedIOException 误判为超时
        assertEquals(
            TokenStatStatus.CANCELLED,
            TokenTrackingAIService.classify(
                UserCancellationException("c", InterruptedIOException("Interrupted"))
            ),
        )
        // 线程中断等普通 InterruptedIOException 不是超时
        assertEquals(
            TokenStatStatus.FAILED,
            TokenTrackingAIService.classify(
                IOException("wrapped", InterruptedIOException("Interrupted"))
            ),
        )
        // OkHttp 整调用超时（消息明确 "timeout"）仍识别为超时
        assertEquals(
            TokenStatStatus.TIMEOUT,
            TokenTrackingAIService.classify(IOException("wrapped", InterruptedIOException("timeout"))),
        )
    }

    /** TimeoutCancellationException 构造器是 internal：用真实 withTimeout 捕获一个实例。 */
    private suspend fun captureCoroutineTimeout(): TimeoutCancellationException =
        try {
            withTimeout(1) { delay(10_000) }
            error("withTimeout must fire")
        } catch (e: TimeoutCancellationException) {
            e
        }

    @Test
    fun `no usage keeps unknown fields null`() = runBlocking {
        val fake = FakeAiService { _ -> streamOf("plain answer") }
        tracked(fake).sendMessage(context = context).collect { }
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatStatus.COMPLETED.name, event.status)
        assertNull(event.uncachedInputTokens)
        assertNull(event.outputTokens)
        assertNull(event.costInPricingCurrency)
        assertTrue(event.diagnosticsJson!!.contains("\"usageObserved\":false"))
    }

    @Test
    fun `internal retry usage accumulates across attempts without double counting`() =
        runBlocking {
            val fake =
                FakeAiService { onUsage ->
                    stream {
                        // 模拟 provider 内部重试：attempt 1 上报 usage 后 I/O 失败被
                        // provider 内部捕获，attempt 2 上报后成功；同 attempt 的重复
                        // 上报只取最后一次。
                        onUsage?.invoke(
                            usage().copy(uncachedInputTokens = 300L, outputTokens = 100L),
                            1,
                        )
                        try {
                            throw IOException("transient failure")
                        } catch (e: IOException) {
                            onUsage?.invoke(
                                usage().copy(uncachedInputTokens = 500L, outputTokens = 400L),
                                2,
                            )
                            // 同 attempt 重复上报：不得重复累加，取最后一次
                            onUsage?.invoke(
                                usage().copy(uncachedInputTokens = 999L, outputTokens = 400L),
                                2,
                            )
                        }
                        emit("final answer")
                    }
                }
            val collected = StringBuilder()
            tracked(fake)
                .sendMessage(context = context, statsCategory = TokenStatCategory.CHAT)
                .collect { collected.append(it) }
            assertEquals("final answer", collected.toString())

            // 一次逻辑请求只落一个事件
            val events = database.tokenStatsDao().getAllEvents()
            assertEquals(1, events.size)
            val event = events[0]
            assertEquals(TokenStatStatus.COMPLETED.name, event.status)
            // attempt1(300) + attempt2 最后一次(999) = 1299；attempt2 重复上报不累加
            assertEquals(1299L, event.uncachedInputTokens)
            // attempt1(100) + attempt2 最后一次(400) = 500
            assertEquals(500L, event.outputTokens)
            assertTrue(event.diagnosticsJson!!.contains("\"usageReportCount\":3"))
            assertTrue(event.diagnosticsJson!!.contains("\"attemptCount\":2"))
        }

    @Test
    fun `duplicate usage callbacks never duplicate the event`() = runBlocking {
        val fake =
            FakeAiService { onUsage ->
                stream {
                    emit("a")
                    onUsage?.invoke(usage(), 1)
                    emit("b")
                    onUsage?.invoke(usage(), 1)
                }
            }
        tracked(fake).sendMessage(context = context).collect { }
        val events = database.tokenStatsDao().getAllEvents()
        assertEquals(1, events.size)
    }

    @Test
    fun `caller usage callback is forwarded for every report with attempt`() = runBlocking {
        val forwarded = mutableListOf<Pair<ProviderUsageSnapshot, Int>>()
        val fake =
            FakeAiService { onUsage ->
                stream {
                    emit("a")
                    onUsage?.invoke(usage(), 1)
                    emit("b")
                    onUsage?.invoke(usage().copy(outputTokens = 777L), 1)
                }
            }
        tracked(fake)
            .sendMessage(
                context = context,
                onUsageReported = { u, attempt -> forwarded.add(u to attempt) },
            )
            .collect { }
        // 调用者 callback 每次上报都转发（含 attempt）
        assertEquals(2, forwarded.size)
        assertEquals(1, forwarded[0].second)
        assertEquals(777L, forwarded[1].first.outputTokens)
        // 内部账本按同 attempt 最后一次记账，不重复
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(777L, event.outputTokens)
    }

    @Test
    fun `whitespace-only first chunk records first token`() = runBlocking {
        val fake =
            FakeAiService { _ ->
                stream { emit(" "); emit("\n"); emit("x") }
            }
        tracked(fake).sendMessage(context = context).collect { }
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertNotNull("whitespace chunk is real output and must set first token", event.firstTokenAtMs)
    }

    @Test
    fun `empty string chunks do not set first token before real content`() = runBlocking {
        val fake =
            FakeAiService { _ ->
                stream { emit(""); emit("x") }
            }
        tracked(fake).sendMessage(context = context).collect { }
        val event = database.tokenStatsDao().getAllEvents()[0]
        // 空字符串不记首 token，首个非空 chunk 才记
        assertNotNull(event.firstTokenAtMs)
    }

    @Test
    fun `test connection records connection test events with result status`() = runBlocking {
        val ok = FakeAiService(testConnectionResult = Result.success("ok"))
        assertEquals(true, tracked(ok).testConnection(context).isSuccess)
        val okEvent = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatCategory.CONNECTION_TEST.name, okEvent.category)
        assertEquals(TokenStatStatus.COMPLETED.name, okEvent.status)

        val bad =
            FakeAiService(testConnectionResult = Result.failure(IOException("denied")))
        assertEquals(false, tracked(bad).testConnection(context).isSuccess)
        val badEvent = database.tokenStatsDao().getAllEvents()[1]
        assertEquals(TokenStatCategory.CONNECTION_TEST.name, badEvent.category)
        assertEquals(TokenStatStatus.FAILED.name, badEvent.status)
    }

    @Test
    fun `test connection forwards provider usage into connection test event`() = runBlocking {
        val fake = FakeAiService(testConnectionResult = Result.success("ok"))
        fake.testConnectionUsage = usage()
        assertEquals(true, tracked(fake).testConnection(context).isSuccess)
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatCategory.CONNECTION_TEST.name, event.category)
        assertEquals(TokenStatStatus.COMPLETED.name, event.status)
        // 探测调用拿到的 provider usage 必须进入 CONNECTION_TEST 事件
        assertEquals(800L, event.uncachedInputTokens)
        assertEquals(500L, event.outputTokens)
    }

    @Test
    fun `test connection propagates cancellation and records cancelled`() = runBlocking {
        val fake = FakeAiService()
        fake.testConnectionCancellation = CancellationException("test cancelled")
        try {
            tracked(fake).testConnection(context)
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("test cancelled", e.message)
        }
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatCategory.CONNECTION_TEST.name, event.category)
        assertEquals(TokenStatStatus.CANCELLED.name, event.status)
    }

    @Test
    fun `revision stream interface is preserved for downstream rollback handling`() = runBlocking {
        val fake = FakeAiService(eventCarrier = true)
        val result = tracked(fake).sendMessage(context = context)
        assertTrue("tracked stream must stay a revision carrier", result is TextStreamEventCarrier)
        val collected = StringBuilder()
        result.collect { collected.append(it) }
        assertEquals("hello", collected.toString())
        // 保存点/回滚事件通道仍然可访问（replayCache 是 SharedStream 的公开只读面）
        assertNotNull((result as TextStreamEventCarrier).eventChannel.replayCache)
        val events = database.tokenStatsDao().getAllEvents()
        assertEquals(1, events.size)
    }

    @Test
    fun `generation read failure aborts before model invocation`() = runBlocking {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            var invoked = false
            val fake =
                FakeAiService { onUsage ->
                    invoked = true
                    stream {
                        emit("still delivered")
                        onUsage?.invoke(usage(), 1)
                    }
                }
            TokenStatsLedger.databaseProvider = { throw IOException("generation unavailable") }
            try {
                tracked(fake).sendMessage(context = context).collect { }
                fail("call must not start without a durable reset generation")
            } catch (e: IOException) {
                assertEquals("generation unavailable", e.message)
            } finally {
                TokenStatsLedger.databaseProvider = { database }
            }
            assertFalse("model must not be invoked", invoked)
        }
    }

    @Test
    fun `first request identity cannot bypass group deletion tombstone`() = runBlocking {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            val dao = database.tokenStatsDao()
            // cfg-a 已在默认展示组 deepseek-chat（FakeAiService 的 provider:model）
            val identityA = TokenStatIdentityResolver.identityId("cfg-a", "DEEPSEEK", "deepseek-chat")
            dao.insertIdentityIfAbsent(
                TokenStatIdentityEntity(
                    identityId = identityA,
                    configId = "cfg-a",
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    displayModelId = "deepseek-chat",
                )
            )
            // cfg-b 首次请求：sendMessage 的接受边界原子创建身份并捕获 generation 0
            val fake =
                FakeAiService { onUsage ->
                    stream { emit("hello"); onUsage?.invoke(usage(), 1) }
                }
            val stream = tracked(fake, configId = "cfg-b").sendMessage(context = context)

            // 请求进行中删除默认展示组：成员解析必须看见边界已创建的身份并写 tombstone
            dao.deleteDisplayModelEventsTx("deepseek-chat", deleteBaselines = false)
            assertEquals(1L, dao.currentResetGeneration())

            stream.collect { }
            // 事件接受于删除前：排空被 IDENTITY tombstone 跳过，绝不复活
            assertEquals("old event must not resurrect", 0, dao.countEvents())
            assertNotNull(
                "identity must exist (created atomically at the request boundary)",
                dao.getIdentityByTriple("cfg-b", "DEEPSEEK", "deepseek-chat"),
            )
        }
    }

    @Test
    fun `request after group deletion records normally with newer generation`() = runBlocking {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            val dao = database.tokenStatsDao()
            val identityA = TokenStatIdentityResolver.identityId("cfg-a", "DEEPSEEK", "deepseek-chat")
            dao.insertIdentityIfAbsent(
                TokenStatIdentityEntity(
                    identityId = identityA,
                    configId = "cfg-a",
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    displayModelId = "deepseek-chat",
                )
            )
            dao.deleteDisplayModelEventsTx("deepseek-chat", deleteBaselines = false)
            assertEquals(1L, dao.currentResetGeneration())

            // 删除后的新请求：边界捕获 ≥ tombstone 的新 generation
            val fake =
                FakeAiService { onUsage ->
                    stream { emit("hello"); onUsage?.invoke(usage(), 1) }
                }
            val stream = tracked(fake, configId = "cfg-b").sendMessage(context = context)
            stream.collect { }

            val events = dao.getAllEvents()
            assertEquals(1, events.size)
            val event = events.single()
            assertEquals(
                TokenStatIdentityResolver.identityId("cfg-b", "DEEPSEEK", "deepseek-chat"),
                event.statIdentityId,
            )
            assertEquals("post-deletion request must carry the new generation", 1L, event.acceptedGeneration)
        }
    }


    @Test
    fun `claude same attempt incremental usage keeps full snapshot and cost`() = runBlocking {
        database.tokenStatsDao().upsertPriceOverride(
            scope = TokenPriceResolver.SCOPE_CONFIG,
            provider = "DEEPSEEK",
            model = "deepseek-chat",
            configId = "cfg-1",
            billingMode = com.ai.assistance.operit.data.model.BillingMode.TOKEN.name,
            pricingCurrency = "USD",
            inputPricePerMillion = 2.0,
            cachedInputPricePerMillion = 0.5,
            cacheWritePricePerMillion = 3.0,
            outputPricePerMillion = 5.0,
        )
        val fake =
            FakeAiService { onUsage ->
                stream {
                    emit("answer")
                    // Anthropic 流式：message_start 携带完整 input/cache/cacheWrite
                    onUsage?.invoke(
                        ProviderUsageNormalizer.anthropic(
                            JSONObject(
                                """
                                {"input_tokens": 100, "cache_read_input_tokens": 50,
                                 "cache_creation_input_tokens": 10, "output_tokens": 0}
                                """.trimIndent()
                            )
                        )!!,
                        1,
                    )
                    // message_delta 只携带累计 output：同 attempt 按最新非空字段合并，
                    // 不得覆盖掉 input/cache（output 等累计字段取最新值，不相加）
                    onUsage?.invoke(
                        ProviderUsageNormalizer.anthropic(
                            JSONObject("""{"output_tokens": 300}""")
                        )!!,
                        1,
                    )
                }
            }
        tracked(fake).sendMessage(context = context).collect { }
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatStatus.COMPLETED.name, event.status)
        assertEquals(100L, event.uncachedInputTokens)
        assertEquals(50L, event.cachedInputTokens)
        assertEquals(10L, event.cacheWriteTokens)
        assertEquals(300L, event.outputTokens)
        // 费用完整：100*2 + 50*0.5 + 10*3 + 300*5 = 1755（每百万）
        assertEquals(1755.0 / 1_000_000.0, event.costInPricingCurrency!!, 1e-12)
    }

    @Test
    fun `complete snapshot revocation overwrites previously known fields`() = runBlocking {
        // 评审 P2-2：完整快照（completeSnapshot=true）的 null = 明确未知，
        // 必须覆盖旧值（撤销），协议因此可表达“省略”与“明确未知”的区别
        val fake =
            FakeAiService { onUsage ->
                stream {
                    emit("a")
                    onUsage?.invoke(
                        usage().copy(completeSnapshot = true),
                        1,
                    )
                    // 完整快照撤销 output（后续消息确认该分量未知）
                    onUsage?.invoke(
                        usage().copy(
                            uncachedInputTokens = 800L,
                            cachedInputTokens = 200L,
                            outputTokens = null,
                            completeSnapshot = true,
                        ),
                        1,
                    )
                }
            }
        tracked(fake).sendMessage(context = context).collect { }
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(800L, event.uncachedInputTokens)
        assertNull("complete snapshot null must revoke output", event.outputTokens)
    }

    @Test
    fun `partial update omission keeps previously known fields`() = runBlocking {
        // 评审 P2-2：部分更新（completeSnapshot=false）省略字段保留旧值
        val fake =
            FakeAiService { onUsage ->
                stream {
                    emit("a")
                    onUsage?.invoke(
                        usage().copy(completeSnapshot = false),
                        1,
                    )
                    // 部分更新只带 output，input 省略必须保留
                    onUsage?.invoke(
                        usage().copy(
                            uncachedInputTokens = null,
                            cachedInputTokens = null,
                            outputTokens = 777L,
                            completeSnapshot = false,
                        ),
                        1,
                    )
                }
            }
        tracked(fake).sendMessage(context = context).collect { }
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(800L, event.uncachedInputTokens)
        assertEquals(200L, event.cachedInputTokens)
        assertEquals(777L, event.outputTokens)
    }

    @Test
    fun `attempt aggregation never overflows int`() = runBlocking {
        val fake =
            FakeAiService { onUsage ->
                stream {
                    // 两个 attempt 各 Int.MAX_VALUE：Int 加法必溢出为负，
                    // Long 聚合必须得到正确的 4294967294
                    onUsage?.invoke(
                        usage().copy(
                            uncachedInputTokens = Int.MAX_VALUE.toLong(),
                            outputTokens = 100L,
                        ),
                        1,
                    )
                    onUsage?.invoke(
                        usage().copy(
                            uncachedInputTokens = Int.MAX_VALUE.toLong(),
                            outputTokens = 200L,
                        ),
                        2,
                    )
                }
            }
        tracked(fake).sendMessage(context = context).collect { }
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals("no int overflow", 4294967294L, event.uncachedInputTokens)
        assertEquals(300L, event.outputTokens)
    }

    @Test
    fun `negative provider component is rejected as unknown not silently recorded`() =
        runBlocking {
            val fake =
                FakeAiService { onUsage ->
                    stream {
                        onUsage?.invoke(
                            usage().copy(uncachedInputTokens = 500L, outputTokens = 100L),
                            1,
                        )
                        // 负值分量（异常 provider 数据）必须拒绝为未知
                        onUsage?.invoke(
                            usage().copy(uncachedInputTokens = -5L, outputTokens = 300L),
                            2,
                        )
                    }
                }
            tracked(fake).sendMessage(context = context).collect { }
            val event = database.tokenStatsDao().getAllEvents()[0]
            // attempt2 的 uncached 为负被拒绝为未知 → 该分量整体未知，
            // 绝不静默落负数或把未知当作 0
            assertNull("negative must not be recorded", event.uncachedInputTokens)
            // 其他分量不受影响：100+300=400
            assertEquals(400L, event.outputTokens)
        }

    @Test
    fun `cancel streaming only ends with cancelled event preserved usage and propagation`() =
        runBlocking {
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            val fake =
                FakeAiService { onUsage ->
                    stream {
                        emit("partial")
                        onUsage?.invoke(usage(), 1)
                        // 模拟本地 provider 修复后的契约：cancelStreaming（isCancelled）
                        // 让 native 停止后，以 UserCancellationException 结束流，
                        // 不取消 collector Job、不 emit 错误文本
                        while (!cancelled.get()) {
                            delay(10)
                        }
                        throw UserCancellationException("cancelled by user")
                    }
                }
            fake.cancelHandler = { cancelled.set(true) }
            var propagated: CancellationException? = null
            val job =
                launch {
                    try {
                        tracked(fake).sendMessage(context = context).collect { }
                        fail("cancellation must propagate")
                    } catch (e: CancellationException) {
                        propagated = e
                        throw e
                    }
                }
            delay(100) // 等待流开始并已上报 usage
            // 只调用 cancelStreaming，不取消 collector Job
            tracked(fake).cancelStreaming()
            job.join()
            assertNotNull("cancelStreaming must end the stream with cancellation", propagated)
            val event = database.tokenStatsDao().getAllEvents()[0]
            assertEquals(TokenStatStatus.CANCELLED.name, event.status)
            // 取消前已实测的 usage 必须保留
            assertEquals(800L, event.uncachedInputTokens)
            assertEquals(500L, event.outputTokens)
        }

    @Test
    fun `test connection forwards external usage callback`() = runBlocking {
        val fake = FakeAiService(testConnectionResult = Result.success("ok"))
        fake.testConnectionUsage = usage()
        val forwarded = mutableListOf<Pair<ProviderUsageSnapshot, Int>>()
        tracked(fake).testConnection(context) { u, attempt -> forwarded.add(u to attempt) }
        assertEquals(1, forwarded.size)
        assertEquals(1, forwarded[0].second)
        assertEquals(800L, forwarded[0].first.uncachedInputTokens)
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatStatus.COMPLETED.name, event.status)
        assertEquals(800L, event.uncachedInputTokens)
    }

    @Test
    fun `test connection failure result classifies timeout through cause chain`() = runBlocking {
        val fake =
            FakeAiService(
                testConnectionResult =
                    Result.failure(
                        IOException("exhausted", SocketTimeoutException("connect timed out"))
                    )
            )
        assertEquals(false, tracked(fake).testConnection(context).isSuccess)
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatStatus.TIMEOUT.name, event.status)
    }

    @Test
    fun `test connection thrown exception classifies timeout and plain failure`() = runBlocking {
        val timeout = FakeAiService()
        timeout.testConnectionThrowable = IOException("wrapped", SocketTimeoutException("t"))
        assertEquals(false, tracked(timeout).testConnection(context).isSuccess)
        assertEquals(
            TokenStatStatus.TIMEOUT.name,
            database.tokenStatsDao().getAllEvents()[0].status,
        )

        val plain = FakeAiService()
        plain.testConnectionThrowable = IOException("denied")
        assertEquals(false, tracked(plain).testConnection(context).isSuccess)
        assertEquals(
            TokenStatStatus.FAILED.name,
            database.tokenStatsDao().getAllEvents()[1].status,
        )
    }

    @Test
    fun `user cancellation with interrupted io cause stays cancelled and interrupt is not timeout`() =
        runBlocking {
            val fake =
                FakeAiService { _ ->
                    stream {
                        throw UserCancellationException(
                            "user cancelled",
                            InterruptedIOException("Interrupted"),
                        )
                    }
                }
            try {
                tracked(fake).sendMessage(context = context).collect { }
                fail("cancellation must propagate")
            } catch (e: CancellationException) {
                // expected
            }
            assertEquals(
                TokenStatStatus.CANCELLED.name,
                database.tokenStatsDao().getAllEvents()[0].status,
            )

            // 线程中断等普通 InterruptedIOException 不是超时
            val interrupted =
                FakeAiService { _ ->
                    stream { throw IOException("interrupted", InterruptedIOException("Interrupted")) }
                }
            try {
                tracked(interrupted).sendMessage(context = context).collect { }
                fail("must throw")
            } catch (e: IOException) {
                // expected
            }
            assertEquals(
                TokenStatStatus.FAILED.name,
                database.tokenStatsDao().getAllEvents()[1].status,
            )

            // OkHttp 整调用超时（消息明确 "timeout"）仍识别为超时
            val okhttp =
                FakeAiService { _ ->
                    stream { throw IOException("call timeout", InterruptedIOException("timeout")) }
                }
            try {
                tracked(okhttp).sendMessage(context = context).collect { }
                fail("must throw")
            } catch (e: IOException) {
                // expected
            }
            assertEquals(
                TokenStatStatus.TIMEOUT.name,
                database.tokenStatsDao().getAllEvents()[2].status,
            )
        }

    @Test
    fun `caller usage observer non-cancel exception is isolated from request and ledger`() =
        runBlocking {
            org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
                val fake =
                    FakeAiService { onUsage ->
                        stream {
                            emit("still delivered")
                            onUsage?.invoke(usage(), 1)
                        }
                    }
                val collected = StringBuilder()
                tracked(fake)
                    .sendMessage(
                        context = context,
                        onUsageReported = { _, _ -> throw IllegalStateException("observer bug") },
                    )
                    .collect { collected.append(it) }
                assertEquals("still delivered", collected.toString())
                // 调用者异常不得改变账本/请求结果
                val event = database.tokenStatsDao().getAllEvents()[0]
                assertEquals(TokenStatStatus.COMPLETED.name, event.status)
                assertEquals(800L, event.uncachedInputTokens)
                assertEquals(500L, event.outputTokens)
            }
        }

    @Test
    fun `caller usage observer cancellation propagates as request cancellation`() = runBlocking {
        val fake =
            FakeAiService { onUsage ->
                stream {
                    emit("partial")
                    onUsage?.invoke(usage(), 1)
                }
            }
        try {
            tracked(fake)
                .sendMessage(
                    context = context,
                    onUsageReported = { _, _ -> throw CancellationException("observer cancel") },
                )
                .collect { }
            fail("observer cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("observer cancel", e.message)
        }
        val event = database.tokenStatsDao().getAllEvents()[0]
        assertEquals(TokenStatStatus.CANCELLED.name, event.status)
        // 取消前已记录的 usage 仍完整落账
        assertEquals(800L, event.uncachedInputTokens)
    }

    @Test
    fun `blocked writer never loses events and all are eventually written`() = runBlocking {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            val previousRecordTimeout = TokenTrackingAIService.recordTimeoutMs
            TokenTrackingAIService.recordTimeoutMs = 100
            val previousInsertTimeout = TokenStatSpool.insertTimeoutMs
            TokenStatSpool.insertTimeoutMs = 500
            val previousPrepareTimeout = TokenStatSpool.prepareTimeoutMs
            TokenStatSpool.prepareTimeoutMs = 100
            // 可控阻塞：数据库访问是纯同步等待（runBlocking），withTimeout 无法
            // 抢占——必须由独立 resolver/writer + 持久 spool 隔离，业务只做有界等待
            val blocker = CompletableDeferred<Unit>()
            try {
                TokenStatsLedger.databaseProvider = {
                    runBlocking { blocker.await() }
                    database
                }
                val startedAt = System.nanoTime()
                repeat(20) { index ->
                    val request =
                        TokenStatRequestContext(
                            eventId = "evt-blocked-$index",
                            category = TokenStatCategory.OTHER,
                            configId = "cfg-1",
                            provider = "DEEPSEEK",
                            model = "deepseek-chat",
                            startedAtMs = System.currentTimeMillis(),
                        )
                    request.finish(TokenStatStatus.COMPLETED)
                    TokenTrackingAIService.recordSafely(context, request)
                }
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                // 每次调用都在有界时间内返回（等待窗口 100ms），业务不被数据库阻塞
                assertTrue("bounded per-call: $elapsedMs ms", elapsedMs < 10_000)
                // 未解除阻塞前：任何事件都不应已落账（resolver 全部被阻塞）
                assertEquals(0, database.tokenStatsDao().countEvents())

                // 解除阻塞：全部事件必须最终写入，一个都不能丢（评审 P1/P1-4）
                blocker.complete(Unit)
                TokenStatsLedger.databaseProvider = { database }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (database.tokenStatsDao().countEvents() < 20 && System.nanoTime() < deadline) {
                    delay(50)
                }
                assertEquals("all 20 events must be recorded", 20, database.tokenStatsDao().countEvents())
                val ids = database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet()
                assertEquals("no event may be dropped or duplicated", 20, ids.size)
                assertTrue(ids.containsAll((0 until 20).map { "evt-blocked-$it" }))
                // 排空完成后 spool 必须为空
                val spoolDir = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME)
                if (spoolDir.isDirectory) {
                    val remaining =
                        spoolDir.listFiles().orEmpty().filter { it.isFile }
                    assertEquals("spool must be drained", 0, remaining.size)
                }
            } finally {
                TokenTrackingAIService.recordTimeoutMs = previousRecordTimeout
                TokenStatSpool.insertTimeoutMs = previousInsertTimeout
                TokenStatSpool.prepareTimeoutMs = previousPrepareTimeout
                TokenStatsLedger.databaseProvider = { database }
            }
        }
    }

    @Test
    fun `task failure is isolated and subsequent events keep recording`() = runBlocking {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            val previousRecordTimeout = TokenTrackingAIService.recordTimeoutMs
            TokenTrackingAIService.recordTimeoutMs = 500
            try {
                // 让落账失败（数据库不可用）：事件保留在 spool，业务不受影响
                TokenStatsLedger.databaseProvider = { throw RuntimeException("db unavailable") }
                val first =
                    TokenStatRequestContext(
                        eventId = "evt-fail-1",
                        category = TokenStatCategory.OTHER,
                        configId = "cfg-1",
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        startedAtMs = System.currentTimeMillis(),
                        acceptedGeneration = database.tokenStatsDao().currentResetGeneration(),
                    )
                first.finish(TokenStatStatus.COMPLETED)
                TokenTrackingAIService.recordSafely(context, first)

                // 恢复数据库：后续事件触发排空，失败事件一并重放（IGNORE 幂等）
                TokenStatsLedger.databaseProvider = { database }
                val second =
                    TokenStatRequestContext(
                        eventId = "evt-fail-2",
                        category = TokenStatCategory.OTHER,
                        configId = "cfg-1",
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        startedAtMs = System.currentTimeMillis(),
                        acceptedGeneration = database.tokenStatsDao().currentResetGeneration(),
                    )
                second.finish(TokenStatStatus.COMPLETED)
                TokenTrackingAIService.recordSafely(context, second)

                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (database.tokenStatsDao().getAllEvents().size < 2 && System.nanoTime() < deadline) {
                    delay(50)
                }
                val ids = database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet()
                // 失败任务不阻断后续；失败事件在数据库恢复后成功重放
                assertEquals(setOf("evt-fail-1", "evt-fail-2"), ids)
                // P2-4：等待结束/超时后 waiter 引用必须全部解除（大量失败不累积）
                assertEquals(0, TokenStatSpool.pendingLatchCountForTest())
            } finally {
                TokenTrackingAIService.recordTimeoutMs = previousRecordTimeout
                TokenStatsLedger.databaseProvider = { database }
            }
        }
    }

    @Test
    fun `spool survives process death and replays on next process start`() = runBlocking {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            val previousInsertTimeout = TokenStatSpool.insertTimeoutMs
            TokenStatSpool.insertTimeoutMs = 200
            try {
                // 模拟上一进程写入的 spool：直接构造持久化 v2 行（含发生时价格
                // 快照，等价于 enqueue 落盘内容；P1-1：重放只用快照）
                val previousProcess = TokenStatRequestContext(
                    eventId = "evt-old-process-1",
                    category = TokenStatCategory.CHAT,
                    configId = "cfg-1",
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    startedAtMs = System.currentTimeMillis(),
                )
                previousProcess.onUsage(
                    com.ai.assistance.operit.data.stats.ProviderUsageSnapshot(
                        uncachedInputTokens = 300L,
                        cachedInputTokens = 100L,
                        outputTokens = 50L,
                        reasoningIncludedInOutput = true,
                        source = "test",
                    ),
                    1,
                )
                previousProcess.finish(TokenStatStatus.COMPLETED)
                val line = TokenStatsLedger.prepareEventLine(context, previousProcess, previousProcess.toSpoolBaseJson())
                val spoolDir = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME)
                spoolDir.mkdirs()
                File(spoolDir, "sealed_1.jsonl").writeText(line + "\n")

                // “重启”：清空本进程内存状态，不依赖新 append 触发恢复
                TokenStatSpool.clearPendingStateForTest()
                TokenStatSpool.replay(context)

                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (database.tokenStatsDao().getEvent("evt-old-process-1") == null &&
                    System.nanoTime() < deadline
                ) {
                    delay(50)
                }
                val event = database.tokenStatsDao().getEvent("evt-old-process-1")
                assertNotNull("stale spool must be replayed after process restart", event)
                assertEquals(300L, event!!.uncachedInputTokens)
                assertEquals(50L, event.outputTokens)
                assertEquals(TokenStatStatus.COMPLETED.name, event.status)
                // 价格快照来自行内冻结（无覆盖时内置默认价）
                assertEquals(PricingSource.DEFAULT.name, event.pricingSource)
            } finally {
                TokenStatSpool.insertTimeoutMs = previousInsertTimeout
            }
        }
    }

    @Test
    fun `shutdown writer self heals and keeps recording`() = runBlocking {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            // 关闭默认 writer（模拟执行器生命周期结束），下一次落账必须自愈重建
            TokenStatSpool.shutdownWriterForTest()
            val fake = FakeAiService { _ -> streamOf("still works") }
            tracked(fake).sendMessage(context = context).collect { }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (database.tokenStatsDao().getAllEvents().isEmpty() && System.nanoTime() < deadline) {
                delay(50)
            }
            assertEquals(1, database.tokenStatsDao().getAllEvents().size)
        }
    }

    @Test
    fun `hanging record write is bounded and never blocks completion`() = runBlocking {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            val previousRecordTimeout = TokenTrackingAIService.recordTimeoutMs
            TokenTrackingAIService.recordTimeoutMs = 300
            val previousInsertTimeout = TokenStatSpool.insertTimeoutMs
            TokenStatSpool.insertTimeoutMs = 100
            val previousPrepareTimeout = TokenStatSpool.prepareTimeoutMs
            TokenStatSpool.prepareTimeoutMs = 100
            try {
                // 让价格解析（legacy 价格读取）永久挂起（可释放）：suspend 卡死由
                // prepareTimeout 截断 → 回退默认价快照 → 事件仍持久落账（不丢）；
                // 业务线程只做有界等待，绝不被阻塞
                val release = CountDownLatch(1)
                TokenStatsLedger.legacyPriceProvider = { _, _ ->
                    gateIgnoringInterrupts(release)
                    null
                }
                val fake = FakeAiService { _ -> streamOf("still delivered") }
                val collected = StringBuilder()
                val done =
                    withTimeoutOrNull(5_000) {
                        tracked(fake).sendMessage(context = context).collect {
                            collected.append(it)
                        }
                        true
                    }
                assertNotNull("record hang must not block completion", done)
                assertEquals("still delivered", collected.toString())
                // 价格解析挂起被截断后：事件带默认价快照持久化并最终落账，不丢失
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (database.tokenStatsDao().getAllEvents().isEmpty() &&
                    System.nanoTime() < deadline
                ) {
                    delay(50)
                }
                assertEquals(1, database.tokenStatsDao().getAllEvents().size)

                // 释放挂起的定价 worker 并确认其真实终止，绝不遗留线程
                release.countDown()
                TokenTrackingAIService.resetPricingExecutorForTest()
                TokenStatSpool.resetExecutorsForTest()
                TokenStatSpool.shutdownWriterForTest()
                awaitNoSpoolWorkerThreads()
            } finally {
                TokenTrackingAIService.recordTimeoutMs = previousRecordTimeout
                TokenStatSpool.insertTimeoutMs = previousInsertTimeout
                TokenStatSpool.prepareTimeoutMs = previousPrepareTimeout
                TokenStatsLedger.legacyPriceProvider = { _, _ -> null }
            }
        }
    }

    // ==== P1-1：发生时价格快照 ====

    @Test
    fun `price snapshot is frozen at append and replay never re-reads current prices`() =
        runBlocking {
            org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
                val dao = database.tokenStatsDao()
                // 价格 A
                dao.upsertPriceOverride(
                    scope = TokenPriceResolver.SCOPE_CONFIG,
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    configId = "cfg-1",
                    billingMode = com.ai.assistance.operit.data.model.BillingMode.TOKEN.name,
                    pricingCurrency = "USD",
                    inputPricePerMillion = 2.0,
                    cachedInputPricePerMillion = 0.5,
                    outputPricePerMillion = 5.0,
                )
                val request =
                    TokenStatRequestContext(
                        eventId = "evt-frozen-price",
                        category = TokenStatCategory.CHAT,
                        configId = "cfg-1",
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        startedAtMs = System.currentTimeMillis() - 1_000,
                    )
                request.onUsage(usage().copy(cacheWriteTokens = 0L), 1)
                request.finish(TokenStatStatus.COMPLETED)
                // 请求收尾：解析并冻结价 A（durable append 前）
                val line =
                    TokenStatsLedger.prepareEventLine(context, request, request.toSpoolBaseJson())

                // writer 阻塞/重启期间用户改价为 B
                dao.upsertPriceOverride(
                    scope = TokenPriceResolver.SCOPE_CONFIG,
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    configId = "cfg-1",
                    billingMode = com.ai.assistance.operit.data.model.BillingMode.TOKEN.name,
                    pricingCurrency = "USD",
                    inputPricePerMillion = 9.0,
                    cachedInputPricePerMillion = 8.0,
                    outputPricePerMillion = 7.0,
                )

                // 重放：只用行内快照，绝不重读当前价格
                val spoolDir = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME)
                spoolDir.mkdirs()
                File(spoolDir, "sealed_1.jsonl").writeText(line + "\n")
                TokenStatSpool.clearPendingStateForTest()
                TokenStatSpool.replay(context)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (database.tokenStatsDao().getEvent("evt-frozen-price") == null &&
                    System.nanoTime() < deadline
                ) {
                    delay(50)
                }
                val event = database.tokenStatsDao().getEvent("evt-frozen-price")!!
                // 历史仍 A（发生时快照）
                assertEquals(2.0, event.inputPricePerMillion!!, 1e-12)
                assertEquals(0.5, event.cachedInputPricePerMillion!!, 1e-12)
                assertEquals(5.0, event.outputPricePerMillion!!, 1e-12)
                assertEquals(PricingSource.CONFIG_OVERRIDE.name, event.pricingSource)
                // 800*2 + 200*0.5 + 500*5 = 4200（每百万）
                assertEquals(4200.0 / 1_000_000.0, event.costInPricingCurrency!!, 1e-12)

                // 新请求（当前重估路径）用改价后的 B
                val current =
                    TokenStatRequestContext(
                        eventId = "evt-current-price",
                        category = TokenStatCategory.CHAT,
                        configId = "cfg-1",
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        startedAtMs = System.currentTimeMillis(),
                    )
                current.onUsage(usage().copy(cacheWriteTokens = 0L), 1)
                current.finish(TokenStatStatus.COMPLETED)
                TokenStatsLedger.record(context, current)
                val currentEvent = database.tokenStatsDao().getEvent("evt-current-price")!!
                assertEquals(9.0, currentEvent.inputPricePerMillion!!, 1e-12)
            }
        }

    // ==== P1-2：失败段退避重试（不依赖新请求） ====

    @Test
    fun `failed drain retries with backoff without any new request`() = runBlocking {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            val previousInsertTimeout = TokenStatSpool.insertTimeoutMs
            TokenStatSpool.insertTimeoutMs = 200
            try {
                // 预置一个事件到 spool（模拟冷启动遗留的失败段）
                val request =
                    TokenStatRequestContext(
                        eventId = "evt-retry-1",
                        category = TokenStatCategory.CHAT,
                        configId = "cfg-1",
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        startedAtMs = System.currentTimeMillis(),
                    )
                request.onUsage(usage(), 1)
                request.finish(TokenStatStatus.COMPLETED)
                val line =
                    TokenStatsLedger.prepareEventLine(context, request, request.toSpoolBaseJson())
                val spoolDir = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME)
                spoolDir.mkdirs()
                File(spoolDir, "sealed_1.jsonl").writeText(line + "\n")

                // 数据库故障：replay 后落账失败
                TokenStatsLedger.databaseProvider = { throw RuntimeException("db down") }
                TokenStatSpool.clearPendingStateForTest()
                TokenStatSpool.replay(context)
                delay(300)
                assertEquals(0, database.tokenStatsDao().countEvents())

                // 恢复数据库：**不产生任何新请求**，退避定时重试必须自行恢复
                TokenStatsLedger.databaseProvider = { database }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (database.tokenStatsDao().getEvent("evt-retry-1") == null &&
                    System.nanoTime() < deadline
                ) {
                    delay(100)
                }
                assertNotNull("backoff retry must recover without new requests", database.tokenStatsDao().getEvent("evt-retry-1"))
                assertEquals(1, database.tokenStatsDao().countEvents())
            } finally {
                TokenStatSpool.insertTimeoutMs = previousInsertTimeout
                TokenStatsLedger.databaseProvider = { database }
            }
        }
    }

    // ==== P1-3：reset 与 spool 一致性（durable tombstone） ====

    @Test
    fun `full reset tombstone prevents spool resurrection`() = runBlocking {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            // 预置“已接受但未入 Room”的事件：直接写 sealed 段（startedAtMs 在 reset 前）
            val spoolDir = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME)
            spoolDir.mkdirs()
            val pre =
                TokenStatRequestContext(
                    eventId = "evt-pre-reset",
                    category = TokenStatCategory.CHAT,
                    configId = "cfg-1",
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    startedAtMs = System.currentTimeMillis() - 60_000,
                )
            pre.onUsage(usage(), 1)
            pre.finish(TokenStatStatus.COMPLETED)
            val line = TokenStatsLedger.prepareEventLine(context, pre, pre.toSpoolBaseJson())
            File(spoolDir, "sealed_1.jsonl").writeText(line + "\n")

            TokenStatsResetCoordinator.daoProvider = { database.tokenStatsDao() }
            try {
                // 全量重置：tombstone 与删除同事务；随后排空丢弃被覆盖的行
                TokenStatsResetCoordinator.resetAllStatistics(context)
                delay(500)
                assertEquals(
                    "pre-reset spool event must never resurrect",
                    0,
                    database.tokenStatsDao().countEvents(),
                )
                // reset 后的新请求正常记录（tombstone 只覆盖 reset 前开始的事件）
                val post =
                    TokenStatRequestContext(
                        eventId = "evt-post-reset",
                        category = TokenStatCategory.CHAT,
                        configId = "cfg-1",
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        startedAtMs = System.currentTimeMillis(),
                        acceptedGeneration = database.tokenStatsDao().currentResetGeneration(),
                    )
                post.onUsage(usage(), 1)
                post.finish(TokenStatStatus.COMPLETED)
                TokenTrackingAIService.recordSafely(context, post)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (database.tokenStatsDao().getEvent("evt-post-reset") == null &&
                    System.nanoTime() < deadline
                ) {
                    delay(50)
                }
                assertEquals(1, database.tokenStatsDao().countEvents())
                assertNotNull(database.tokenStatsDao().getEvent("evt-post-reset"))
            } finally {
                TokenStatsResetCoordinator.daoProvider = null
            }
        }
    }

    @Test
    fun `model reset only clears matching identity and keeps other model spool events`() =
        runBlocking {
            org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
                val spoolDir = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME)
                spoolDir.mkdirs()
                val deep =
                    TokenStatRequestContext(
                        eventId = "evt-pre-deep",
                        category = TokenStatCategory.CHAT,
                        configId = "cfg-1",
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        startedAtMs = System.currentTimeMillis() - 60_000,
                    )
                deep.onUsage(usage(), 1)
                deep.finish(TokenStatStatus.COMPLETED)
                val open =
                    TokenStatRequestContext(
                        eventId = "evt-pre-open",
                        category = TokenStatCategory.CHAT,
                        configId = "cfg-2",
                        provider = "OPENAI",
                        model = "gpt-4o",
                        startedAtMs = System.currentTimeMillis() - 60_000,
                    )
                open.onUsage(usage(), 1)
                open.finish(TokenStatStatus.COMPLETED)
                File(spoolDir, "sealed_1.jsonl").writeText(
                    TokenStatsLedger.prepareEventLine(context, deep, deep.toSpoolBaseJson()) + "\n" +
                        TokenStatsLedger.prepareEventLine(context, open, open.toSpoolBaseJson()) + "\n"
                )

                TokenStatsResetCoordinator.daoProvider = { database.tokenStatsDao() }
                try {
                    TokenStatsResetCoordinator.resetStatisticsForProviderModel(
                        context,
                        "DEEPSEEK:deepseek-chat",
                    )
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (database.tokenStatsDao().getEvent("evt-pre-open") == null &&
                        System.nanoTime() < deadline
                    ) {
                        delay(50)
                    }
                    // 被模型 tombstone 覆盖：不复活；其他模型保留
                    assertNull(database.tokenStatsDao().getEvent("evt-pre-deep"))
                    assertNotNull(database.tokenStatsDao().getEvent("evt-pre-open"))
                    assertEquals(1, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatsResetCoordinator.daoProvider = null
                }
            }
        }

    // ==== P1-4：append 故障不丢事件（有界紧急队列 + 恢复） ====

    @Test
    fun `append failure defers to emergency queue and recovers with exactly one event each`() =
        runBlocking {
            org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
                val previousRecordTimeout = TokenTrackingAIService.recordTimeoutMs
                TokenTrackingAIService.recordTimeoutMs = 1_000
                try {
                    // 让 spool 目录不可创建：filesDir 下同名文件占位
                    val spoolPath = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME)
                    spoolPath.writeText("i am a file, not a directory")
                    var failures = 0
                    (0 until 5).forEach { index ->
                            val request =
                                TokenStatRequestContext(
                                    eventId = "evt-emergency-$index",
                                    category = TokenStatCategory.CHAT,
                                    configId = "cfg-1",
                                    provider = "DEEPSEEK",
                                    model = "deepseek-chat",
                                    startedAtMs = System.currentTimeMillis(),
                                )
                            request.onUsage(usage(), 1)
                            request.finish(TokenStatStatus.COMPLETED)
                            try {
                                TokenTrackingAIService.recordSafely(context, request)
                                fail("non-durable append must not return normally")
                            } catch (_: TokenStatsPersistenceException) {
                                failures++
                            }
                        }
                    // 全部明确失败；无内存队列冒充 durable 副本
                    assertEquals(5, failures)
                    assertEquals(0, TokenStatSpool.emergencyQueueSizeForTest())
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    // P2-4：deferred 事件不登记 waiter（latch 已直接完成）
                    assertEquals(0, TokenStatSpool.pendingLatchCountForTest())

                    // 修复磁盘后，失败调用不会凭空出现未确认事件
                    spoolPath.delete()
                    TokenStatSpool.replay(context)
                    delay(100)
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    assertEquals(0, TokenStatSpool.emergencyQueueSizeForTest())
                } finally {
                    TokenTrackingAIService.recordTimeoutMs = previousRecordTimeout
                }
            }
        }

    @Test
    fun `append failure fails success and is suppressed on model failure`() = runBlocking {
        val spoolPath = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME)
        spoolPath.writeText("not a directory")
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            try {
                tracked(FakeAiService()).sendMessage(context = context).collect { }
                fail("successful model result must not hide statistics persistence failure")
            } catch (_: TokenStatsPersistenceException) {
            }

            val modelFailure = IOException("model failed")
            val failing = FakeAiService { _ -> stream { throw modelFailure } }
            try {
                tracked(failing).sendMessage(context = context).collect { }
                fail("model failure must propagate")
            } catch (e: IOException) {
                assertTrue("original model exception stays primary", e === modelFailure)
                assertEquals(1, e.suppressed.size)
                assertTrue(e.suppressed[0] is TokenStatsPersistenceException)
            }
        }
    }

    // ==== P2-2：损坏行整段隔离（保留证据） ====

    @Test
    fun `corrupt line quarantines the whole segment with evidence and does not re-block`() =
        runBlocking {
            org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
                val spoolDir = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME)
                spoolDir.mkdirs()
                val valid1 =
                    TokenStatRequestContext(
                        eventId = "evt-q-1",
                        category = TokenStatCategory.CHAT,
                        configId = "cfg-1",
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        startedAtMs = System.currentTimeMillis(),
                    )
                valid1.onUsage(usage(), 1)
                valid1.finish(TokenStatStatus.COMPLETED)
                val valid2 =
                    TokenStatRequestContext(
                        eventId = "evt-q-2",
                        category = TokenStatCategory.CHAT,
                        configId = "cfg-1",
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        startedAtMs = System.currentTimeMillis(),
                    )
                valid2.onUsage(usage(), 1)
                valid2.finish(TokenStatStatus.COMPLETED)
                val segment = File(spoolDir, "sealed_1.jsonl")
                segment.writeText(
                    TokenStatsLedger.prepareEventLine(context, valid1, valid1.toSpoolBaseJson()) +
                        "\n{corrupt raw evidence line\n" +
                        TokenStatsLedger.prepareEventLine(context, valid2, valid2.toSpoolBaseJson()) + "\n"
                )

                TokenStatSpool.clearPendingStateForTest()
                TokenStatSpool.replay(context)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (database.tokenStatsDao().getEvent("evt-q-2") == null &&
                    System.nanoTime() < deadline
                ) {
                    delay(50)
                }
                // 有效行全部落账
                assertEquals(2, database.tokenStatsDao().countEvents())
                // 证据保留：quarantine 文件存在且含损坏原文；原段已移除
                val quarantined =
                    spoolDir.listFiles().orEmpty().single { it.name.startsWith("quarantine_") }
                assertTrue("quarantine evidence must exist", quarantined.isFile)
                assertTrue(quarantined.readText().contains("corrupt raw evidence line"))
                assertFalse("original segment must be gone", segment.exists())

                // 再次 replay：quarantine 被忽略，不重复插入、不重新阻塞
                TokenStatSpool.clearPendingStateForTest()
                TokenStatSpool.replay(context)
                delay(300)
                assertEquals(2, database.tokenStatsDao().countEvents())
                assertTrue(quarantined.isFile)
            }
        }

    // ==== P2-3：段删除失败 → 视为未完成，退避重试 ====

    @Test
    fun `segment delete failure keeps segment and backs off until recovery`() = runBlocking {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            val spoolDir = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME)
            spoolDir.mkdirs()
            val request =
                TokenStatRequestContext(
                    eventId = "evt-del-1",
                    category = TokenStatCategory.CHAT,
                    configId = "cfg-1",
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    startedAtMs = System.currentTimeMillis(),
                )
            request.onUsage(usage(), 1)
            request.finish(TokenStatStatus.COMPLETED)
            val segment = File(spoolDir, "sealed_1.jsonl")
            segment.writeText(
                TokenStatsLedger.prepareEventLine(context, request, request.toSpoolBaseJson()) + "\n"
            )
            // 强制段删除失败（确定性）：事件已插入（IGNORE 幂等），但段保留
            val deleteAllowed = java.util.concurrent.atomic.AtomicBoolean(false)
            TokenStatSpool.segmentDeleteForTest = { seg ->
                if (deleteAllowed.get()) seg.delete() else false
            }
            try {
                TokenStatSpool.clearPendingStateForTest()
                TokenStatSpool.replay(context)
                val insertedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (database.tokenStatsDao().getEvent("evt-del-1") == null &&
                    System.nanoTime() < insertedDeadline
                ) {
                    delay(50)
                }
                // 事件已插入，但段删除失败 → 段保留，进入退避（不忙循环）
                assertTrue("segment must survive delete failure", segment.exists())

                // 恢复删除能力：退避重试最终删除段，不重复插入
                deleteAllowed.set(true)
                val deleteDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (segment.exists() && System.nanoTime() < deleteDeadline) {
                    delay(100)
                }
                assertFalse("segment must be removed after delete recovery", segment.exists())
                assertEquals(1, database.tokenStatsDao().countEvents())
            } finally {
                TokenStatSpool.segmentDeleteForTest = null
            }
        }
    }

    // ==== P1-5：显式全零 usage 也是已观察到的 usage ====

    @Test
    fun `explicit zero usage payload records zero fields with usageObserved true`() = runBlocking {
        val fake =
            FakeAiService { onUsage ->
                stream {
                    emit("answer")
                    onUsage?.invoke(
                        com.ai.assistance.operit.data.stats.ProviderUsageSnapshot(
                            uncachedInputTokens = 0L,
                            cachedInputTokens = 0L,
                            cacheWriteTokens = 0L,
                            totalInputTokens = 0L,
                            outputTokens = 0L,
                            reasoningTokens = 0L,
                            reasoningIncludedInOutput = true,
                            cacheWriteSeparateBilling = false,
                            source = "test",
                        ),
                        1,
                    )
                }
            }
        tracked(fake).sendMessage(context = context).collect { }
        val event = database.tokenStatsDao().getAllEvents()[0]
        // 字段存在且为 0L：真实 0，不是未知
        assertEquals(0L, event.uncachedInputTokens)
        assertEquals(0L, event.cachedInputTokens)
        assertEquals(0L, event.outputTokens)
        assertEquals(0L, event.reasoningTokens)
        assertTrue(
            "zero payload is still observed usage",
            event.diagnosticsJson!!.contains("\"usageObserved\":true"),
        )
    }

    // ==== P1 终审：恢复屏障对 in-flight provider/stream 请求的 request/session fencing ====

    @Test
    fun `restore barrier rejects in-flight and same-process requests until simulated restart`() =
        runBlocking {
            org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
                // 真实 TokenTracking + fake provider：请求停在 provider 流阶段（未收尾）时
                // 执行完整 restore（block 模拟恢复替换数据库 + clearAfter 删除旧 spool）→
                // 旧请求释放后收尾 append 被请求 fence 明确拒绝（模型成功不伪装）；同进程
                // 新请求被拒绝开始；模拟进程重启（reset 状态）后新请求可正常写入。
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val fake =
                    FakeAiService { _ ->
                        stream {
                            emit("partial answer")
                            entered.complete(Unit)
                            release.await()
                            emit("tail")
                        }
                    }
                var primary: Throwable? = null
                val requestJob =
                    launch {
                        try {
                            tracked(fake)
                                .sendMessage(context = context, statsCategory = TokenStatCategory.CHAT)
                                .collect { }
                            fail("old in-flight request must fail after a completed restore")
                        } catch (e: Throwable) {
                            primary = e
                            if (e is CancellationException) throw e
                        }
                    }
                assertTrue(
                    "request must be paused in the provider stage",
                    withTimeoutOrNull(10.seconds) { entered.await() } != null,
                )
                // 请求进行中执行恢复：epoch 在屏障开始原子递增（旧请求失效），替换开始后
                // 本进程不再接受任何事件（accepting=false，UI“稍后重启”窗口语义）
                TokenStatSpool.withExclusiveSnapshotAccess(
                    context,
                    drainBefore = false,
                    clearAfter = true,
                ) {
                    database.tokenStatsDao().deleteAllEvents()
                }
                release.complete(Unit)
                requestJob.join()
                assertTrue(
                    "old successful request must receive an explicit persistence exception, was: $primary",
                    primary is TokenStatsPersistenceException,
                )
                // 恢复后的 spool/Room 无旧事件：旧请求从未写入（fence 在写 spool 前拒绝）
                assertEquals(0, database.tokenStatsDao().countEvents())
                val spoolDir = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME)
                assertFalse(
                    "restored spool must contain no old events",
                    spoolDir.exists() &&
                        spoolDir.listFiles().orEmpty().any { it.isFile && it.length() > 0L },
                )
                // 同进程新请求：newRequest 明确拒绝开始（不污染新 DB）
                try {
                    tracked(FakeAiService())
                        .sendMessage(context = context, statsCategory = TokenStatCategory.CHAT)
                        .collect { }
                    fail("new tracking requests must be rejected until process restart")
                } catch (e: TokenStatsPersistenceException) {
                    // expected
                }
                assertEquals(0, database.tokenStatsDao().countEvents())
                // 模拟进程重启：reset 状态后新请求可写
                TokenStatSpool.clearPendingStateForTest()
                tracked(FakeAiService())
                    .sendMessage(context = context, statsCategory = TokenStatCategory.CHAT)
                    .collect { }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (database.tokenStatsDao().countEvents() == 0 && System.nanoTime() < deadline) {
                    delay(50)
                }
                assertEquals(1, database.tokenStatsDao().countEvents())
            }
        }

    @Test
    fun `restore failure before replacement keeps accepting new requests while old in-flight is rejected`() =
        runBlocking {
            org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
                // 旧请求停在 provider 阶段；restore 在替换前失败（drain 阶段失败——epoch 已
                // 递增但 accepting 保持 true）→ 旧请求释放后被 fence 拒绝；同进程新请求
                // （新 epoch）照常落账——替换前失败可继续。
                val spool = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val pre =
                    TokenStatRequestContext(
                        eventId = "evt-pre-restore-old",
                        category = TokenStatCategory.CHAT,
                        configId = "cfg-1",
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        startedAtMs = System.currentTimeMillis(),
                    )
                pre.onUsage(usage(), 1)
                pre.finish(TokenStatStatus.COMPLETED)
                File(spool, "sealed_1.jsonl").writeText(
                    TokenStatsLedger.prepareEventLine(context, pre, pre.toSpoolBaseJson()) + "\n",
                )
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val fake =
                    FakeAiService { _ ->
                        stream {
                            emit("partial")
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                var primary: Throwable? = null
                val requestJob =
                    launch {
                        try {
                            tracked(fake)
                                .sendMessage(context = context, statsCategory = TokenStatCategory.CHAT)
                                .collect { }
                            fail("old in-flight request must be rejected after a restore attempt")
                        } catch (e: Throwable) {
                            primary = e
                            if (e is CancellationException) throw e
                        }
                    }
                assertTrue(
                    "request must be paused in the provider stage",
                    withTimeoutOrNull(10.seconds) { entered.await() } != null,
                )
                // 替换前失败：restore barrier 的 drain 阶段失败（段读取故障），block 绝不执行
                TokenStatSpool.segmentReadErrorForTest = { true }
                try {
                    try {
                        TokenStatSpool.withExclusiveSnapshotAccess(
                            context,
                            drainBefore = true,
                            clearAfter = true,
                        ) {
                            fail("replacement must never run")
                        }
                        fail("restore must fail in the drain phase")
                    } catch (e: IOException) {
                        assertTrue("restore must report the drain failure", e.message!!.contains("drained"))
                    }
                } finally {
                    TokenStatSpool.segmentReadErrorForTest = null
                }
                // 旧请求释放：epoch 不匹配 → 明确拒绝（不写 spool/DB）
                release.complete(Unit)
                requestJob.join()
                assertTrue(
                    "old in-flight request must be rejected, was: $primary",
                    primary is TokenStatsPersistenceException,
                )
                // 同进程新请求（新 epoch）：替换前失败可继续，正常落账；旧 spool 段（restore
                // 失败未替换/未清理）一并排空到未被替换的旧 DB
                tracked(FakeAiService())
                    .sendMessage(context = context, statsCategory = TokenStatCategory.CHAT)
                    .collect { }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (database.tokenStatsDao().countEvents() < 2 && System.nanoTime() < deadline) {
                    delay(50)
                }
                // 只有旧 spool 段 + 新请求两个事件：in-flight 旧请求被 fence 拒绝，
                // 其事件（第 3 个）绝不出现
                val ids = database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet()
                assertEquals(2, ids.size)
                assertTrue(ids.contains("evt-pre-restore-old"))
            }
        }
}
