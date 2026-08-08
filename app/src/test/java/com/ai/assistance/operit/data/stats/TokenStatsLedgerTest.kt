package com.ai.assistance.operit.data.stats

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.BillingMode
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
 * 统一 usage 记录器测试（真实 Room 数据库，JVM 驱动）：
 * 身份/展示分组自动创建、价格层级与成本、幂等防重、未知 vs 0、
 * 数据库写入失败不影响业务、取消传播、诊断字段脱敏语义。
 */
class TokenStatsLedgerTest {

    private lateinit var tempDir: File
    private lateinit var database: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory("ledger-test").toFile()
        context = mockContext(tempDir)
        database =
            Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
                .setDriver(JdbcSQLiteDriver())
                .addMigrations(AppDatabase.MIGRATION_28_29, AppDatabase.MIGRATION_29_30, AppDatabase.MIGRATION_30_31)
                .allowMainThreadQueries()
                .build()
        TokenStatsLedger.databaseProvider = { database }
        TokenStatsLedger.legacyPriceProvider = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TokenStatsLedger.databaseProvider = null
        TokenStatsLedger.legacyPriceProvider = null
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

    private fun request(
        eventId: String = "evt-1",
        category: TokenStatCategory = TokenStatCategory.CHAT,
        status: TokenStatStatus? = TokenStatStatus.COMPLETED,
        usage: ProviderUsageSnapshot? =
            ProviderUsageSnapshot(
uncachedInputTokens = 800L,
cachedInputTokens = 200L,
cacheWriteTokens = 0L,
outputTokens = 500L,
reasoningTokens = 50L,
                reasoningIncludedInOutput = true,
                source = "test",
            ),
        firstTokenAtMs: Long? = 1200,
        configId: String = "cfg-1",
        provider: String = "OPENAI",
        model: String = "gpt-4o-2024-11-20",
    ): TokenStatRequestContext {
        val ctx =
            TokenStatRequestContext(
                eventId = eventId,
                category = category,
                configId = configId,
                provider = provider,
                model = model,
                startedAtMs = 1000,
            )
        usage?.let { ctx.onUsage(it) }
        firstTokenAtMs?.let { ctx.onFirstToken(it) }
        status?.let { ctx.finish(it, 2000) }
        return ctx
    }

    @Test
    fun `records event with identity display group pricing snapshot and cost`() = runBlocking {
        TokenStatsLedger.record(context, request())

        val event = database.tokenStatsDao().getEvent("evt-1")!!
        assertEquals(TokenStatCategory.CHAT.name, event.category)
        assertEquals(TokenStatStatus.COMPLETED.name, event.status)
        assertEquals(1000L, event.startedAtMs)
        assertEquals(2000L, event.endedAtMs)
        assertEquals(1200L, event.firstTokenAtMs)
        assertEquals(800L, event.uncachedInputTokens)
        assertEquals(200L, event.cachedInputTokens)
        assertEquals(0L, event.cacheWriteTokens)
        assertEquals(500L, event.outputTokens)
        assertEquals(50L, event.reasoningTokens)
        assertEquals(true, event.reasoningIncludedInOutput)
        // gpt-4o-2024-11-20 内置默认价（USD 计价）：1.5/1.5/6 每百万
        assertEquals("USD", event.pricingCurrency)
        assertEquals(PricingSource.DEFAULT.name, event.pricingSource)
        assertEquals("TOKEN", event.billingMode)
        assertEquals(
            4500.0 / 1_000_000.0,
            event.costInPricingCurrency!!,
            1e-12,
        )

        // 身份与展示分组自动创建
        val identity =
            database.tokenStatsDao().getIdentityByTriple("cfg-1", "OPENAI", "gpt-4o-2024-11-20")!!
        assertEquals(event.statIdentityId, identity.identityId)
        val display = database.tokenStatsDao().getDisplayModel(identity.displayModelId)
        assertNotNull(display)

        // 诊断字段只含脱敏元数据
        val diagnostics = event.diagnosticsJson!!
        assertTrue(diagnostics.contains("\"source\":\"test\""))
        assertTrue(diagnostics.contains("\"usageObserved\":true"))
        assertTrue(diagnostics.contains("\"usageReportCount\":1"))
        assertTrue("diagnostics must not contain content or credentials", !diagnostics.contains("apiKey"))
    }

    @Test
    fun `same eventId is idempotent and usage report count is deduplicated`() = runBlocking {
        TokenStatsLedger.record(context, request(eventId = "evt-dup"))
        TokenStatsLedger.record(context, request(eventId = "evt-dup"))

        assertEquals(1, database.tokenStatsDao().countEvents())
    }

    @Test
    fun `accepting and recording requests preserve renamed default display group`() = runBlocking {
        val dao = database.tokenStatsDao()
        val model = "gpt-4o-2024-11-20"
        val displayModelId = TokenStatIdentityResolver.displayModelIdFor(model)
        TokenStatsLedger.record(context, request(eventId = "evt-alias-1", model = model))
        dao.updateDisplayModelName(displayModelId, "Primary GPT")

        TokenStatsLedger.ensureIdentityAndCaptureGeneration(
            context = context,
            configId = "cfg-2",
            provider = "OPENAI",
            model = model,
        )
        assertEquals("Primary GPT", dao.getDisplayModel(displayModelId)!!.displayName)

        TokenStatsLedger.record(
            context,
            request(eventId = "evt-alias-2", configId = "cfg-2", model = model),
        )
        assertEquals("Primary GPT", dao.getDisplayModel(displayModelId)!!.displayName)
    }

    @Test
    fun `duplicate usage callbacks keep last snapshot only`() = runBlocking {
        val ctx = request(eventId = "evt-last")
        ctx.onUsage(
            ProviderUsageSnapshot(
uncachedInputTokens = 1L,
outputTokens = 2L,
                source = "first",
            )
        )
        ctx.onUsage(
            ProviderUsageSnapshot(
uncachedInputTokens = 10L,
outputTokens = 20L,
                source = "last",
            )
        )
        // request() 默认已上报一次 + 本次两次
        assertEquals(3, ctx.usageReportCount)
        assertEquals("last", ctx.lastUsage!!.source)
    }

    @Test
    fun `no usage keeps event with null fields and null cost`() = runBlocking {
        TokenStatsLedger.record(context, request(usage = null, firstTokenAtMs = null))

        val event = database.tokenStatsDao().getEvent("evt-1")!!
        assertNull(event.uncachedInputTokens)
        assertNull(event.cachedInputTokens)
        assertNull(event.cacheWriteTokens)
        assertNull(event.outputTokens)
        assertNull(event.reasoningTokens)
        assertNull(event.costInPricingCurrency)
        assertNull("no first token -> null", event.firstTokenAtMs)
        assertNotNull("event still recorded without usage", event.eventId)
        assertTrue(event.diagnosticsJson!!.contains("\"usageObserved\":false"))
    }

    @Test
    fun `failed and cancelled statuses are recorded with their usage`() = runBlocking {
        val failed =
            request(eventId = "evt-fail", status = TokenStatStatus.FAILED, firstTokenAtMs = null)
        TokenStatsLedger.record(context, failed)
        val failedEvent = database.tokenStatsDao().getEvent("evt-fail")!!
        assertEquals(TokenStatStatus.FAILED.name, failedEvent.status)
        assertEquals(800L, failedEvent.uncachedInputTokens)
        assertNull(failedEvent.firstTokenAtMs)

        val cancelled = request(eventId = "evt-cancel", status = TokenStatStatus.CANCELLED)
        TokenStatsLedger.record(context, cancelled)
        val cancelledEvent = database.tokenStatsDao().getEvent("evt-cancel")!!
        assertEquals(TokenStatStatus.CANCELLED.name, cancelledEvent.status)
        assertEquals(500L, cancelledEvent.outputTokens)
    }

    @Test
    fun `category is fixed business classification`() = runBlocking {
        val cases =
            listOf(
                TokenStatCategory.SUBAGENT,
                TokenStatCategory.SUMMARY,
                TokenStatCategory.TITLE,
                TokenStatCategory.MEMORY,
                TokenStatCategory.CHARACTER_GENERATION,
                TokenStatCategory.CONNECTION_TEST,
                TokenStatCategory.OTHER,
            )
        cases.forEachIndexed { index, category ->
            TokenStatsLedger.record(
                context,
                request(eventId = "evt-cat-$index", category = category),
            )
            assertEquals(
                category.name,
                database.tokenStatsDao().getEvent("evt-cat-$index")!!.category,
            )
        }
    }

    @Test
    fun `config override and legacy override drive pricing source and cost`() = runBlocking {
        val dao = database.tokenStatsDao()

        // CONFIG 覆盖
        dao.upsertPriceOverride(
            scope = TokenPriceResolver.SCOPE_CONFIG,
            provider = "DEEPSEEK",
            model = "deepseek-chat",
            configId = "cfg-1",
            billingMode = BillingMode.TOKEN.name,
            pricingCurrency = "USD",
            inputPricePerMillion = 2.0,
            cachedInputPricePerMillion = 0.5,
            cacheWritePricePerMillion = 3.0,
            outputPricePerMillion = 5.0,
        )
        TokenStatsLedger.record(
            context,
            request(
                eventId = "evt-cfg",
                provider = "DEEPSEEK",
                model = "deepseek-chat",
            ),
        )
        val cfgEvent = database.tokenStatsDao().getEvent("evt-cfg")!!
        assertEquals(PricingSource.CONFIG_OVERRIDE.name, cfgEvent.pricingSource)
        assertEquals("USD", cfgEvent.pricingCurrency)
        assertEquals(2.0, cfgEvent.inputPricePerMillion!!, 1e-12)
        // 默认 usage：cacheWrite=0（确认无缓存写入），800*2 + 200*0.5 + 500*5 = 4200（每百万）
        assertEquals(4200.0 / 1_000_000.0, cfgEvent.costInPricingCurrency!!, 1e-12)

        // LEGACY 覆盖（旧 DataStore 价格）
        TokenStatsLedger.legacyPriceProvider = { _, _ ->
            LegacyPriceSettings(
                billingMode = BillingMode.TOKEN,
                inputPricePerMillion = 1.0,
                cachedInputPricePerMillion = 0.5,
                outputPricePerMillion = 2.0,
            )
        }
        TokenStatsLedger.record(
            context,
            request(
                eventId = "evt-legacy",
                configId = "cfg-2",
                provider = "DEEPSEEK",
                model = "deepseek-chat",
                usage =
                    ProviderUsageSnapshot(
uncachedInputTokens = 800L,
cachedInputTokens = 200L,
cacheWriteTokens = 100L,
outputTokens = 500L,
                        source = "t",
                    ),
            ),
        )
        val legacyEvent = database.tokenStatsDao().getEvent("evt-legacy")!!
        assertEquals(PricingSource.LEGACY_OVERRIDE.name, legacyEvent.pricingSource)
        // cacheWriteTokens=100 且 cacheWritePricePerMillion=null（旧系统无缓存写入价）→
        // 成本必须为 null（未知），不得静默按 0 计费
        assertNull("cache write without price keeps cost unknown", legacyEvent.costInPricingCurrency)
    }

    @Test
    fun `separate reasoning billed additionally only when not included in output`() = runBlocking {
        val dao = database.tokenStatsDao()
        dao.upsertPriceOverride(
            scope = TokenPriceResolver.SCOPE_CONFIG,
            provider = "DEEPSEEK",
            model = "deepseek-chat",
            configId = "cfg-1",
            billingMode = BillingMode.TOKEN.name,
            pricingCurrency = "USD",
            inputPricePerMillion = 1.0,
            cachedInputPricePerMillion = 0.5,
            cacheWritePricePerMillion = 1.0,
            outputPricePerMillion = 2.0,
        )

        // 推理已包含在 output：只按 output 计费
        val included =
            request(
                eventId = "evt-included",
                provider = "DEEPSEEK",
                model = "deepseek-chat",
                usage =
                    ProviderUsageSnapshot(
uncachedInputTokens = 100L,
cachedInputTokens = 0L,
cacheWriteTokens = 0L,
outputTokens = 200L,
reasoningTokens = 50L,
                        reasoningIncludedInOutput = true,
                        source = "t",
                    ),
            )
        TokenStatsLedger.record(context, included)
        val includedCost = database.tokenStatsDao().getEvent("evt-included")!!.costInPricingCurrency!!

        // 推理独立计数：output + reasoning 一起按输出单价
        val separate =
            request(
                eventId = "evt-separate",
                provider = "DEEPSEEK",
                model = "deepseek-chat",
                usage =
                    ProviderUsageSnapshot(
uncachedInputTokens = 100L,
cachedInputTokens = 0L,
cacheWriteTokens = 0L,
outputTokens = 200L,
reasoningTokens = 50L,
                        reasoningIncludedInOutput = false,
                        source = "t",
                    ),
            )
        TokenStatsLedger.record(context, separate)
        val separateCost = database.tokenStatsDao().getEvent("evt-separate")!!.costInPricingCurrency!!

        assertEquals((100.0 / 1_000_000.0) + (200.0 / 1_000_000.0 * 2.0), includedCost, 1e-12)
        assertEquals(
            (100.0 / 1_000_000.0) + ((200.0 + 50.0) / 1_000_000.0 * 2.0),
            separateCost,
            1e-12,
        )
        assertTrue("separate reasoning must cost more", separateCost > includedCost)
    }

    @Test
    fun `database write failure is logged and never breaks the caller`() = runBlocking {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            TokenStatsLedger.databaseProvider = {
                throw RuntimeException("db unavailable")
            }
            try {
                TokenStatsLedger.record(context, request())
                // 失败被吞掉（已记录日志），不向上抛出
            } catch (e: Exception) {
                fail("DB write failure must not propagate: ${e.message}")
            }
        }
    }

    @Test
    fun `cancellation propagates and is not swallowed as write failure`() = runBlocking {
        TokenStatsLedger.databaseProvider = {
            throw CancellationException("test cancellation")
        }

        try {
            TokenStatsLedger.record(context, request())
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertEquals("test cancellation", e.message)
        }
    }

    @Test
    fun `first token is only set once`() {
        val ctx = request(firstTokenAtMs = null)
        ctx.onFirstToken(1100)
        ctx.onFirstToken(9999)
        assertEquals(1100L, ctx.firstTokenAtMs)
    }

    @Test
    fun `finish is only applied once`() {
        val ctx = request()
        ctx.finish(TokenStatStatus.COMPLETED, 2000)
        ctx.finish(TokenStatStatus.FAILED, 9999)
        assertEquals(TokenStatStatus.COMPLETED, ctx.status)
        assertEquals(2000, ctx.endedAtMs)
    }

    // ==== P1-3：费用 fixture（典型 provider 快照） ====
    private suspend fun configPricing(dao: com.ai.assistance.operit.data.dao.TokenStatsDao) {
        dao.upsertPriceOverride(
            scope = TokenPriceResolver.SCOPE_CONFIG,
            provider = "DEEPSEEK",
            model = "deepseek-chat",
            configId = "cfg-1",
            billingMode = BillingMode.TOKEN.name,
            pricingCurrency = "USD",
            inputPricePerMillion = 2.0,
            cachedInputPricePerMillion = 0.5,
            cacheWritePricePerMillion = 3.0,
            outputPricePerMillion = 5.0,
        )
    }

    @Test
    fun `typical openai chat completion without cache write fields still computes cost`() =
        runBlocking {
            configPricing(database.tokenStatsDao())
            // OpenAI 常规响应没有 cache_creation：cacheWrite=null 且无独立计费概念，
            // 不得因缺该字段令全成本未知
            val snapshot =
                ProviderUsageNormalizer.openAiChatCompletions(
                    JSONObject(
                        """
                        {
                          "prompt_tokens": 1000,
                          "completion_tokens": 500,
                          "prompt_tokens_details": {"cached_tokens": 200}
                        }
                        """.trimIndent()
                    )
                )!!
            TokenStatsLedger.record(
                context,
                request(
                    eventId = "evt-openai-typical",
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    usage = snapshot,
                ),
            )
            val event = database.tokenStatsDao().getEvent("evt-openai-typical")!!
            assertEquals(800L, event.uncachedInputTokens)
            assertEquals(200L, event.cachedInputTokens)
            assertNull(event.cacheWriteTokens)
            // 800*2 + 200*0.5 + 500*5 = 4200（每百万）
            assertEquals(4200.0 / 1_000_000.0, event.costInPricingCurrency!!, 1e-12)
        }

    @Test
    fun `typical openai responses and gemini fixtures compute cost without cache write`() =
        runBlocking {
            configPricing(database.tokenStatsDao())
            val openai =
                ProviderUsageNormalizer.openAiResponses(
                    JSONObject(
                        """
                        {
                          "input_tokens": 1000,
                          "output_tokens": 500,
                          "input_tokens_details": {"cached_tokens": 200}
                        }
                        """.trimIndent()
                    )
                )!!
            TokenStatsLedger.record(
                context,
                request(
                    eventId = "evt-openai-responses",
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    usage = openai,
                ),
            )
            val responsesEvent = database.tokenStatsDao().getEvent("evt-openai-responses")!!
            assertEquals(4200.0 / 1_000_000.0, responsesEvent.costInPricingCurrency!!, 1e-12)

            val gemini =
                ProviderUsageNormalizer.gemini(
                    JSONObject(
                        """
                        {
                          "promptTokenCount": 1000,
                          "cachedContentTokenCount": 300,
                          "candidatesTokenCount": 400
                        }
                        """.trimIndent()
                    )
                )!!
            TokenStatsLedger.record(
                context,
                request(
                    eventId = "evt-gemini",
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    usage = gemini,
                ),
            )
            val geminiEvent = database.tokenStatsDao().getEvent("evt-gemini")!!
            // 700*2 + 300*0.5 + 400*5 = 3550（每百万）
            assertEquals(3550.0 / 1_000_000.0, geminiEvent.costInPricingCurrency!!, 1e-12)
        }

    @Test
    fun `anthropic typical fixture bills cache write separately`() = runBlocking {
        configPricing(database.tokenStatsDao())
        val anthropic =
            ProviderUsageNormalizer.anthropic(
                JSONObject(
                    """
                    {
                      "input_tokens": 500,
                      "cache_read_input_tokens": 200,
                      "cache_creation_input_tokens": 100,
                      "output_tokens": 300
                    }
                    """.trimIndent()
                )
            )!!
        TokenStatsLedger.record(
            context,
            request(
                eventId = "evt-anthropic",
                provider = "DEEPSEEK",
                model = "deepseek-chat",
                usage = anthropic,
            ),
        )
        val event = database.tokenStatsDao().getEvent("evt-anthropic")!!
        assertEquals(100L, event.cacheWriteTokens)
        // 500*2 + 200*0.5 + 100*3 + 300*5 = 2900（每百万）
        assertEquals(2900.0 / 1_000_000.0, event.costInPricingCurrency!!, 1e-12)
    }

    @Test
    fun `anthropic absent cache write keeps cost unknown while openai absent cached split stays null`() =
        runBlocking {
            configPricing(database.tokenStatsDao())
            // Anthropic 缓存创建独立计费：字段缺失即分量未知 → 成本未知
            val anthropic =
                ProviderUsageNormalizer.anthropic(
                    JSONObject("""{"input_tokens": 500, "output_tokens": 300}""")
                )!!
            TokenStatsLedger.record(
                context,
                request(
                    eventId = "evt-anthropic-unknown",
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    usage = anthropic,
                ),
            )
            val anthropicEvent = database.tokenStatsDao().getEvent("evt-anthropic-unknown")!!
            assertNull("独立计费分量未知 → 成本必须未知", anthropicEvent.costInPricingCurrency)

            // OpenAI 缺 cached details：输入拆分未知 → 不把总输入确定为 uncached，成本未知
            val openai =
                ProviderUsageNormalizer.openAiChatCompletions(
                    JSONObject("""{"prompt_tokens": 1000, "completion_tokens": 500}""")
                )!!
            TokenStatsLedger.record(
                context,
                request(
                    eventId = "evt-openai-unknown-split",
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    usage = openai,
                ),
            )
            val openaiEvent = database.tokenStatsDao().getEvent("evt-openai-unknown-split")!!
            assertNull("cached 拆分未知 → uncached 必须未知", openaiEvent.uncachedInputTokens)
            assertNull(openaiEvent.cachedInputTokens)
            assertNull("输入拆分未知 → 成本未知", openaiEvent.costInPricingCurrency)
        }

    // ==== P1-4：attempt 聚合 ====

    @Test
    fun `usage across attempts aggregates without double counting same attempt`() = runBlocking {
        configPricing(database.tokenStatsDao())
        val ctx =
            TokenStatRequestContext(
                eventId = "evt-attempts",
                category = TokenStatCategory.CHAT,
                configId = "cfg-1",
                provider = "DEEPSEEK",
                model = "deepseek-chat",
                startedAtMs = 1000,
            )
        // attempt 1 上报（流式多 chunk 重复上报）
        ctx.onUsage(
            ProviderUsageSnapshot(
uncachedInputTokens = 310L,
cachedInputTokens = 100L,
outputTokens = 120L,
                cacheWriteSeparateBilling = false,
                source = "test",
            ),
            attempt = 1,
        )
        ctx.onUsage(
            ProviderUsageSnapshot(
uncachedInputTokens = 310L,
cachedInputTokens = 100L,
outputTokens = 120L,
                cacheWriteSeparateBilling = false,
                source = "test",
            ),
            attempt = 1,
        )
        // attempt 2 成功上报
        ctx.onUsage(
            ProviderUsageSnapshot(
uncachedInputTokens = 500L,
cachedInputTokens = 200L,
outputTokens = 400L,
                cacheWriteSeparateBilling = false,
                source = "test",
            ),
            attempt = 2,
        )
        ctx.finish(TokenStatStatus.COMPLETED, 2000)
        TokenStatsLedger.record(context, ctx)

        val event = database.tokenStatsDao().getEvent("evt-attempts")!!
        // 同 attempt 取最后一次（310）+ attempt2（500）= 810；输出 120 + 400 = 520
        assertEquals(810L, event.uncachedInputTokens)
        assertEquals(300L, event.cachedInputTokens)
        assertEquals(520L, event.outputTokens)
        // 费用按聚合用量计算：810*2 + 300*0.5 + 520*5 = 4370（每百万）
        assertEquals(4370.0 / 1_000_000.0, event.costInPricingCurrency!!, 1e-12)
        assertTrue(event.diagnosticsJson!!.contains("\"usageReportCount\":3"))
        assertTrue(event.diagnosticsJson!!.contains("\"attemptCount\":2"))
    }

    @Test
    fun `aggregated usage keeps component unknown when any attempt leaves it unknown`() {
        val ctx =
            TokenStatRequestContext(
                eventId = "evt-partial",
                category = TokenStatCategory.CHAT,
                configId = "cfg-1",
                provider = "DEEPSEEK",
                model = "deepseek-chat",
                startedAtMs = 1000,
            )
        ctx.onUsage(
            ProviderUsageSnapshot(
uncachedInputTokens = 100L,
outputTokens = 10L,
                source = "test",
            ),
            attempt = 1,
        )
        ctx.onUsage(
            ProviderUsageSnapshot(
uncachedInputTokens = 200L,
                outputTokens = null,
                source = "test",
            ),
            attempt = 2,
        )
        val aggregated = ctx.aggregatedUsage()!!
        assertEquals(300L, aggregated.uncachedInputTokens)
        assertNull("任一 attempt 未知则分量保持未知", aggregated.outputTokens)
    }

    // ==== P2-1：总输入（totalInputTokens）与单价相同时的费用覆盖 ====

    private suspend fun equalPricePricing(dao: com.ai.assistance.operit.data.dao.TokenStatsDao) {
        dao.upsertPriceOverride(
            scope = TokenPriceResolver.SCOPE_CONFIG,
            provider = "DEEPSEEK",
            model = "deepseek-chat",
            configId = "cfg-1",
            billingMode = BillingMode.TOKEN.name,
            pricingCurrency = "USD",
            inputPricePerMillion = 1.0,
            cachedInputPricePerMillion = 1.0,
            cacheWritePricePerMillion = 3.0,
            outputPricePerMillion = 5.0,
        )
    }

    @Test
    fun `typical compat endpoint without cached details computes cost when prices equal`() =
        runBlocking {
            equalPricePricing(database.tokenStatsDao())
            // OpenAI 兼容端点常规响应缺 prompt_tokens_details：拆分未知，
            // 但 provider 明确上报总输入；输入与缓存输入单价相同 → 可按总输入计费
            val openai =
                ProviderUsageNormalizer.openAiChatCompletions(
                    JSONObject("""{"prompt_tokens": 1000, "completion_tokens": 500}""")
                )!!
            assertEquals(1000L, openai.totalInputTokens)
            assertNull(openai.uncachedInputTokens)
            assertNull(openai.cachedInputTokens)
            TokenStatsLedger.record(
                context,
                request(
                    eventId = "evt-openai-total",
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    usage = openai,
                ),
            )
            val openaiEvent = database.tokenStatsDao().getEvent("evt-openai-total")!!
            // 1000*1 + 500*5 = 3500（每百万）
            assertEquals(3500.0 / 1_000_000.0, openaiEvent.costInPricingCurrency!!, 1e-12)

            // Gemini 缺 cachedContentTokenCount：同样按总输入计费
            val gemini =
                ProviderUsageNormalizer.gemini(
                    JSONObject("""{"promptTokenCount": 600, "candidatesTokenCount": 200}""")
                )!!
            assertEquals(600L, gemini.totalInputTokens)
            assertNull(gemini.uncachedInputTokens)
            TokenStatsLedger.record(
                context,
                request(
                    eventId = "evt-gemini-total",
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    usage = gemini,
                ),
            )
            val geminiEvent = database.tokenStatsDao().getEvent("evt-gemini-total")!!
            // 600*1 + 200*5 = 1600（每百万）
            assertEquals(1600.0 / 1_000_000.0, geminiEvent.costInPricingCurrency!!, 1e-12)
        }

    @Test
    fun `total input keeps cost unknown when input and cached prices differ`() = runBlocking {
        configPricing(database.tokenStatsDao()) // input 2.0 vs cached 0.5：单价不同
        val openai =
            ProviderUsageNormalizer.openAiChatCompletions(
                JSONObject("""{"prompt_tokens": 1000, "completion_tokens": 500}""")
            )!!
        assertEquals(1000L, openai.totalInputTokens)
        TokenStatsLedger.record(
            context,
            request(
                eventId = "evt-openai-diff-price",
                provider = "DEEPSEEK",
                model = "deepseek-chat",
                usage = openai,
            ),
        )
        val event = database.tokenStatsDao().getEvent("evt-openai-diff-price")!!
        // 单价不同且拆分未知：不得把总输入伪装成 uncached，成本保持未知
        assertNull(event.uncachedInputTokens)
        assertNull(event.costInPricingCurrency)
    }

    // ==== P2-2：结构化列（v30）持久化 ====

    @Test
    fun `structured billing columns are persisted for direct revaluation`() = runBlocking {
        val openai =
            ProviderUsageNormalizer.openAiChatCompletions(
                JSONObject("""{"prompt_tokens": 1000, "completion_tokens": 500}""")
            )!!
        TokenStatsLedger.record(
            context,
            request(
                eventId = "evt-structured",
                usage = openai,
            ),
        )
        val event = database.tokenStatsDao().getEvent("evt-structured")!!
        assertEquals(1000L, event.totalInputTokens)
        assertEquals(false, event.cacheWriteSeparateBilling)
        // 无 usage 的事件保持 null（未知），与 0 可区分
        TokenStatsLedger.record(
            context,
            request(eventId = "evt-structured-null", usage = null),
        )
        val nullEvent = database.tokenStatsDao().getEvent("evt-structured-null")!!
        assertNull(nullEvent.totalInputTokens)
        assertNull(nullEvent.cacheWriteSeparateBilling)
    }

    // ==== P1-1：同 attempt 增量快照按最新非空字段合并 ====

    @Test
    fun `same attempt incremental snapshots merge latest non-null fields without summing`() {
        val ctx =
            TokenStatRequestContext(
                eventId = "evt-claude-stream",
                category = TokenStatCategory.CHAT,
                configId = "cfg-1",
                provider = "ANTHROPIC",
                model = "claude-sonnet",
                startedAtMs = 1000,
            )
        // 真实 message_start 形态：完整 input/cache/cacheWrite，output 为占位 0
        val messageStart =
            ProviderUsageNormalizer.anthropic(
                JSONObject(
                    """
                    {"input_tokens": 100, "cache_read_input_tokens": 50,
                     "cache_creation_input_tokens": 10, "output_tokens": 0}
                    """.trimIndent()
                )
            )!!
        // 真实 message_delta 形态：只有累计 output
        val messageDelta =
            ProviderUsageNormalizer.anthropic(JSONObject("""{"output_tokens": 300}"""))!!
        ctx.onUsage(messageStart, attempt = 1)
        ctx.onUsage(messageDelta, attempt = 1)

        val aggregated = ctx.aggregatedUsage()!!
        assertEquals(100L, aggregated.uncachedInputTokens)
        assertEquals(50L, aggregated.cachedInputTokens)
        assertEquals(10L, aggregated.cacheWriteTokens)
        assertEquals(300L, aggregated.outputTokens)
        // 输出是累计值：只取最新，不能 start(0) + delta(300) 相加
        assertEquals(300L, aggregated.outputTokens)
        assertEquals(160L, aggregated.totalInputTokens)
    }
}
