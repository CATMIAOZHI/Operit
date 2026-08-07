package com.ai.assistance.operit.data.stats

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.TokenStatBaselineEntity
import com.ai.assistance.operit.data.model.TokenStatCleanupOperationEntity
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatEventEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import java.io.File
import kotlinx.coroutines.runBlocking
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
 * 阶段 5 删除矩阵的 DAO 级测试（真实 Room + JVM SQLite 驱动）：
 *
 * - 时间范围删除只删有时间戳的事件，边界 [startMs, endMs) 精确，绝不碰 baseline；
 * - 按展示分组删除覆盖完整组成员（跨 provider/model 合并组），IDENTITY tombstone
 *   精确到身份，同一 provider:model 的其他分组不受影响；
 *   baseline 是否删除由参数决定（yes/no 两分支）；
 * - 全部删除 yes/no 两分支；身份/展示分组/价格覆盖一律保留；
 * - RANGE/IDENTITY tombstone 与 spool 排空插入同界：删除前接受的事件不复活，
 *   删除后接受的事件正常入账（统一 generation 计数，不依赖墙钟）；
 * - 删除后查询/计数/聚合结果一致，无缓存旧数据。
 */
class TokenStatsDeletionTest {

    private lateinit var tempDir: File
    private lateinit var database: AppDatabase
    private lateinit var dao: TokenStatsDao

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory("token-stats-deletion-test").toFile()
        val context = mockContext(tempDir)
        database =
            Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
                .setDriver(JdbcSQLiteDriver())
                .allowMainThreadQueries()
                .build()
        dao = database.tokenStatsDao()
    }

    @After
    fun tearDown() {
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

    private suspend fun seedIdentity(
        identityId: String,
        configId: String = "cfg-1",
        provider: String = "OPENAI",
        model: String = "gpt-4o",
        displayModelId: String = TokenStatIdentityResolver.displayModelIdFor(model),
    ) {
        dao.insertIdentityIfAbsent(
            TokenStatIdentityEntity(
                identityId = identityId,
                configId = configId,
                provider = provider,
                model = model,
                displayModelId = displayModelId,
            )
        )
        dao.upsertDisplayModel(
            TokenStatDisplayModelEntity(
                displayModelId = displayModelId,
                normalizedModel = TokenStatIdentityResolver.normalizeModelName(model),
                displayName = model,
            )
        )
    }

    private fun event(
        id: String,
        identityId: String,
        startedAtMs: Long,
        generation: Long = 0L,
        status: String = TokenStatStatus.COMPLETED.name,
    ): TokenStatEventEntity =
        TokenStatEventEntity(
            eventId = id,
            statIdentityId = identityId,
            category = TokenStatCategory.CHAT.name,
            status = status,
            acceptedGeneration = generation,
            startedAtMs = startedAtMs,
            endedAtMs = startedAtMs + 1_000L,
            firstTokenAtMs = startedAtMs + 200L,
            uncachedInputTokens = 100L,
            cachedInputTokens = 0L,
            cacheWriteTokens = 0L,
            totalInputTokens = null,
            outputTokens = 50L,
            reasoningTokens = null,
            reasoningIncludedInOutput = true,
            cacheWriteSeparateBilling = false,
            billingMode = BillingMode.TOKEN.name,
            pricingCurrency = PricingCurrency.USD.name,
            inputPricePerMillion = 1.5,
            cachedInputPricePerMillion = null,
            cacheWritePricePerMillion = null,
            outputPricePerMillion = 6.0,
            pricePerRequest = null,
            pricingSource = PricingSource.DEFAULT.name,
            costInPricingCurrency = 0.01,
            diagnosticsJson = null,
        )

    private fun baseline(identityId: String, requestCount: Long = 3L): TokenStatBaselineEntity =
        TokenStatBaselineEntity(
            identityId = identityId,
            inputTokens = 100L * requestCount,
            cachedInputTokens = 0L,
            outputTokens = 50L * requestCount,
            requestCount = requestCount,
            pricingCurrency = PricingCurrency.USD.name,
            costInPricingCurrency = 0.01 * requestCount,
            isEstimated = true,
            fingerprint = "fp-$identityId",
            importedAtMs = 1L,
            frozenBillingMode = BillingMode.TOKEN.name,
            frozenInputPricePerMillion = 1.0,
            frozenOutputPricePerMillion = 2.0,
        )

    // ==== 时间范围删除 ====

    @Test
    fun `range deletion removes only in range events and never touches baseline`() =
        runBlocking {
            seedIdentity("id-a", configId = "cfg-a")
            seedIdentity("id-b", configId = "cfg-b")
            dao.upsertBaseline(baseline("id-a"))
            dao.upsertBaseline(baseline("id-b"))

            // 半开区间 [10_000, 20_000)：startMs 在界内的删，endMs 处与界外的保留
            dao.insertEvent(event("in-1", "id-a", startedAtMs = 10_000L))
            dao.insertEvent(event("in-2", "id-a", startedAtMs = 19_999L))
            dao.insertEvent(event("boundary-end", "id-a", startedAtMs = 20_000L))
            dao.insertEvent(event("before", "id-b", startedAtMs = 9_999L))
            dao.insertEvent(event("after", "id-b", startedAtMs = 20_001L))

            val deleted = dao.deleteRangeEventsTx(10_000L, 20_000L)
            assertEquals(2, deleted)

            assertEquals("boundary-end must survive at endMs", "boundary-end", dao.getEvent("boundary-end")!!.eventId)
            assertEquals("before must survive", "before", dao.getEvent("before")!!.eventId)
            assertEquals("after must survive", "after", dao.getEvent("after")!!.eventId)
            assertNull(dao.getEvent("in-1"))
            assertNull(dao.getEvent("in-2"))
            assertEquals(3, dao.countEvents())

            // baseline 绝不因范围删除被触碰
            assertEquals(2, dao.countBaselines())
            assertNotNull(dao.getBaseline("id-a"))
            assertNotNull(dao.getBaseline("id-b"))

            // 身份/展示分组/价格覆盖保留（两个身份同模型 → 同一默认展示组一行）
            assertNotNull(dao.getIdentity("id-a"))
            assertNotNull(dao.getIdentity("id-b"))
            assertEquals(1, dao.getAllDisplayModels().size)

            // 删除后查询一致：范围内无事件
            assertFalse(dao.rangeHasEvents(10_000L, 20_000L))
            assertEquals(1, dao.rangeCutoffs().size)
        }

    @Test
    fun `range deletion rejects invalid boundaries`() = runBlocking {
        val failure = runCatching { dao.deleteRangeEventsTx(20_000L, 10_000L) }
        assertTrue("end <= start must be rejected", failure.isFailure)
        assertEquals(0, dao.rangeCutoffs().size)
    }

    @Test
    fun `range deletion tombstone skips pre deletion in range events and accepts post deletion ones`() =
        runBlocking {
            seedIdentity("id-a", configId = "cfg-a")
            // 删除范围 [10_000, 20_000)，删除时 generation 递增为 1
            dao.insertEvent(event("old-in", "id-a", startedAtMs = 12_000L, generation = 0L))
            dao.insertEvent(event("old-out", "id-a", startedAtMs = 30_000L, generation = 0L))
            dao.deleteRangeEventsTx(10_000L, 20_000L)
            assertEquals(1, dao.countEvents())
            assertNull(dao.getEvent("old-in"))
            assertNotNull(dao.getEvent("old-out"))

            // 删除前接受但尚未入账的排空事件：落在范围内 → 跳过（不复活）
            assertFalse(dao.insertEventIfNotResetCovered(event("drain-in", "id-a", startedAtMs = 12_000L, generation = 0L)))
            // 删除前接受但范围外 → 正常入账
            assertTrue(dao.insertEventIfNotResetCovered(event("drain-out", "id-a", startedAtMs = 30_000L, generation = 0L)))
            // 删除后接受的新事件（generation >= cutoff）：即使落在已删范围内也正常入账
            assertTrue(dao.insertEventIfNotResetCovered(event("new-in", "id-a", startedAtMs = 12_001L, generation = 1L)))

            assertEquals(3, dao.countEvents())
            assertNull(dao.getEvent("drain-in"))
            assertNotNull(dao.getEvent("drain-out"))
            assertNotNull(dao.getEvent("new-in"))
        }

    // ==== 按展示分组删除 ====

    @Test
    fun `model deletion covers full group members across providers and preserves other groups`() =
        runBlocking {
            // 展示组 group-x：两个身份，来自不同 provider:model
            seedIdentity("x-1", configId = "cfg-a", provider = "OPENAI", model = "gpt-4o", displayModelId = "group-x")
            seedIdentity("x-2", provider = "DEEPSEEK", model = "deepseek-chat", displayModelId = "group-x")
            // 其他分组：与 x-1 同 provider:model（验证精确到身份，不误伤）
            seedIdentity("y-1", configId = "cfg-b", provider = "OPENAI", model = "gpt-4o", displayModelId = "group-y")

            dao.insertEvent(event("e-x1", "x-1", startedAtMs = 1_000L))
            dao.insertEvent(event("e-x2", "x-2", startedAtMs = 2_000L))
            dao.insertEvent(event("e-y1", "y-1", startedAtMs = 3_000L))
            dao.upsertBaseline(baseline("x-1"))
            dao.upsertBaseline(baseline("x-2"))
            dao.upsertBaseline(baseline("y-1"))

            // 不删 baseline：只删组内事件，组外（含同 provider:model）事件与全部 baseline 保留
            val deleted = dao.deleteDisplayModelEventsTx("group-x", deleteBaselines = false)
            assertEquals(2, deleted.deletedEvents)
            assertNull("non-legacy group with baseline=no creates no cleanup operation", deleted.cleanupOperation)
            assertNull(dao.getEvent("e-x1"))
            assertNull(dao.getEvent("e-x2"))
            assertNotNull("same provider:model in another group must survive", dao.getEvent("e-y1"))
            assertEquals(3, dao.countBaselines())
            assertNotNull(dao.getBaseline("x-1"))

            // 身份行保留（只清计数，保留配置/分组）
            assertNotNull(dao.getIdentity("x-1"))
            assertEquals("group-x", dao.getIdentity("x-1")!!.displayModelId)
        }

    @Test
    fun `model deletion with baseline removes group member baselines only`() = runBlocking {
        seedIdentity("x-1", displayModelId = "group-x")
        seedIdentity("y-1", provider = "DEEPSEEK", model = "deepseek-chat", displayModelId = "group-y")
        dao.insertEvent(event("e-x1", "x-1", startedAtMs = 1_000L))
        dao.insertEvent(event("e-y1", "y-1", startedAtMs = 2_000L))
        dao.upsertBaseline(baseline("x-1"))
        dao.upsertBaseline(baseline("y-1"))

        val deleted = dao.deleteDisplayModelEventsTx("group-x", deleteBaselines = true)
        assertEquals(1, deleted.deletedEvents)
        assertNull(
            "group without legacy members must not create a cleanup operation",
            deleted.cleanupOperation,
        )
        assertNull(dao.getEvent("e-x1"))
        assertNotNull(dao.getEvent("e-y1"))
        assertNull("group baseline must be deleted", dao.getBaseline("x-1"))
        assertNotNull("other group baseline must survive", dao.getBaseline("y-1"))
        assertEquals(1, dao.countBaselines())
    }

    @Test
    fun `model deletion writes identity tombstones that do not cover other groups`() =
        runBlocking {
            seedIdentity("x-1", configId = "cfg-a", provider = "OPENAI", model = "gpt-4o", displayModelId = "group-x")
            seedIdentity("y-1", configId = "cfg-b", provider = "OPENAI", model = "gpt-4o", displayModelId = "group-y")

            dao.insertEvent(event("e-x1", "x-1", startedAtMs = 1_000L, generation = 0L))
            dao.insertEvent(event("e-y1", "y-1", startedAtMs = 2_000L, generation = 0L))
            dao.deleteDisplayModelEventsTx("group-x", deleteBaselines = false)
            assertEquals(1, dao.countEvents())
            assertNotNull(dao.getEvent("e-y1"))

            // 删除前接受、删除后排空的同 provider:model 事件：
            // 组成员身份 → 跳过；其他分组身份 → 正常入账（IDENTITY 精确到身份）
            assertFalse(dao.insertEventIfNotResetCovered(event("drain-x", "x-1", startedAtMs = 3_000L, generation = 0L)))
            assertTrue(dao.insertEventIfNotResetCovered(event("drain-y", "y-1", startedAtMs = 3_000L, generation = 0L)))
            assertNull(dao.getEvent("drain-x"))
            assertNotNull(dao.getEvent("drain-y"))

            // 删除后新接受的事件正常入账
            assertTrue(dao.insertEventIfNotResetCovered(event("new-x", "x-1", startedAtMs = 4_000L, generation = 1L)))
            assertNotNull(dao.getEvent("new-x"))
        }

    @Test
    fun `model deletion on empty group is a no-op`() = runBlocking {
        seedIdentity("y-1", displayModelId = "group-y")
        dao.insertEvent(event("e-y1", "y-1", startedAtMs = 1_000L))

        val deleted = dao.deleteDisplayModelEventsTx("group-x", deleteBaselines = true)
        assertEquals(0, deleted.deletedEvents)
        assertNull(deleted.cleanupOperation)
        assertEquals(1, dao.countEvents())
        assertEquals("empty group must not write tombstones", 0L, dao.currentResetGeneration())
    }
    // ==== 请求接受边界原子性（P1-1） ====

    /**
     * 首次请求（身份尚不存在）在**请求接受边界**原子创建身份并捕获 generation 0；
     * 随后删除默认展示组：成员解析必须看见边界已创建的身份并写 IDENTITY tombstone，
     * 删除前接受的事件在排空/落账时被跳过，绝不复活。
     */
    @Test
    fun `request boundary before group deletion is covered by identity tombstone`() = runBlocking {
        seedIdentity("id-a", configId = "cfg-a", provider = "OPENAI", model = "gpt-4o")
        dao.insertEvent(event("e-a", "id-a", startedAtMs = 1_000L, generation = 0L))

        // 请求接受边界：同一事务内创建 cfg-b 身份（同模型默认组）并读取 generation
        val generation =
            dao.ensureIdentityAndCaptureGenerationTx(
                identity =
                    TokenStatIdentityEntity(
                        identityId = TokenStatIdentityResolver.identityId("cfg-b", "OPENAI", "gpt-4o"),
                        configId = "cfg-b",
                        provider = "OPENAI",
                        model = "gpt-4o",
                        displayModelId = "gpt-4o",
                    ),
                displayModel =
                    TokenStatDisplayModelEntity(
                        displayModelId = "gpt-4o",
                        normalizedModel = "gpt-4o",
                        displayName = "gpt-4o",
                    ),
            )
        assertEquals("first request captures generation 0", 0L, generation)

        // 删除默认展示组：事务内从 identity 全表解析成员 → 必须包含边界刚创建的身份
        val deleted = dao.deleteDisplayModelEventsTx("gpt-4o", deleteBaselines = false)
        assertEquals(1, deleted.deletedEvents)
        assertEquals(1L, dao.currentResetGeneration())

        // cfg-b 的旧事件（接受于删除前）排空时被 IDENTITY tombstone 跳过，不复活
        val identityB = dao.getIdentityByTriple("cfg-b", "OPENAI", "gpt-4o")!!
        assertFalse(
            dao.insertEventIfNotResetCovered(
                event("drain-b", identityB.identityId, startedAtMs = 2_000L, generation = 0L)
            )
        )
        assertNull(dao.getEvent("drain-b"))
        assertEquals(0, dao.countEvents())
    }

    /** 删除先于请求边界：边界捕获 ≥ tombstone 的新 generation，新请求事件正常入账。 */
    @Test
    fun `request boundary after group deletion captures newer generation and records normally`() =
        runBlocking {
            seedIdentity("id-a", configId = "cfg-a", provider = "OPENAI", model = "gpt-4o")
            dao.insertEvent(event("e-a", "id-a", startedAtMs = 1_000L, generation = 0L))
            dao.deleteDisplayModelEventsTx("gpt-4o", deleteBaselines = false)
            assertEquals(1L, dao.currentResetGeneration())

            val generation =
                dao.ensureIdentityAndCaptureGenerationTx(
                    identity =
                        TokenStatIdentityEntity(
                            identityId = TokenStatIdentityResolver.identityId("cfg-b", "OPENAI", "gpt-4o"),
                            configId = "cfg-b",
                            provider = "OPENAI",
                            model = "gpt-4o",
                            displayModelId = "gpt-4o",
                        ),
                    displayModel =
                        TokenStatDisplayModelEntity(
                            displayModelId = "gpt-4o",
                            normalizedModel = "gpt-4o",
                            displayName = "gpt-4o",
                        ),
                )
            assertEquals("boundary after deletion must capture new generation", 1L, generation)

            // 删除后接受的请求事件正常入账（acceptedGeneration >= tombstone）
            val identityB = dao.getIdentityByTriple("cfg-b", "OPENAI", "gpt-4o")!!
            assertTrue(
                dao.insertEventIfNotResetCovered(
                    event("new-b", identityB.identityId, startedAtMs = 3_000L, generation = generation)
                )
            )
            assertNotNull(dao.getEvent("new-b"))
            assertEquals(1, dao.countEvents())
        }

    // ==== 全部删除 ====

    @Test
    fun `delete all without baseline keeps baseline and clears events`() = runBlocking {
        seedIdentity("id-a", configId = "cfg-a")
        seedIdentity("id-b", configId = "cfg-b")
        dao.insertEvent(event("e-a", "id-a", startedAtMs = 1_000L))
        dao.insertEvent(event("e-b", "id-b", startedAtMs = 2_000L))
        dao.upsertBaseline(baseline("id-a"))

        dao.deleteAllStatisticsTx(deleteBaselines = false)
        assertEquals(0, dao.countEvents())
        assertEquals("baseline must survive when not confirmed", 1, dao.countBaselines())
        assertNotNull(dao.getIdentity("id-a"))
        assertNotNull(dao.getIdentity("id-b"))
        assertEquals(1, dao.getAllDisplayModels().size)
        assertNotNull("FULL tombstone must be written", dao.fullResetCutoff())
    }

    @Test
    fun `delete all with baseline removes events and baseline and keeps identity config`() =
        runBlocking {
            seedIdentity("id-a", configId = "cfg-a")
            dao.insertEvent(event("e-a", "id-a", startedAtMs = 1_000L))
            dao.upsertBaseline(baseline("id-a"))
            dao.upsertPriceOverride(
                scope = "PROVIDER_MODEL",
                provider = "OPENAI",
                model = "gpt-4o",
                configId = null,
                billingMode = BillingMode.TOKEN.name,
                pricingCurrency = "USD",
                inputPricePerMillion = 9.0,
                outputPricePerMillion = 9.0,
            )

            dao.deleteAllStatisticsTx(deleteBaselines = true)
            assertEquals(0, dao.countEvents())
            assertEquals(0, dao.countBaselines())
            // 身份/展示分组/价格覆盖保留（“重置只清计数、保留配置”语义）
            assertNotNull(dao.getIdentity("id-a"))
            assertEquals(1, dao.getAllDisplayModels().size)
            assertEquals(1, dao.getAllPriceOverrides().size)
        }

    @Test
    fun `full deletion supersedes older tombstones and unified generation never collides`() =
        runBlocking {
            seedIdentity("x-1", displayModelId = "group-x")
            seedIdentity("y-1", provider = "DEEPSEEK", model = "deepseek-chat", displayModelId = "group-y")

            // 依次执行三种删除，generation 跨两表统一递增：1（范围）→ 2（模型）→ 3（全部）
            dao.insertEvent(event("e-x", "x-1", startedAtMs = 1_000L, generation = 0L))
            dao.insertEvent(event("e-y", "y-1", startedAtMs = 2_000L, generation = 0L))
            dao.deleteRangeEventsTx(500L, 1_500L)
            assertEquals(1L, dao.currentResetGeneration())
            dao.deleteDisplayModelEventsTx("group-y", deleteBaselines = true)
            assertEquals(2L, dao.currentResetGeneration())
            assertEquals(0, dao.countEvents())

            dao.deleteAllStatisticsTx(deleteBaselines = true)
            assertEquals(3L, dao.currentResetGeneration())
            // 卫生：FULL 之后旧边界（RANGE/MODEL）全部清除，只剩 FULL
            assertTrue("range cutoffs must be cleared by full deletion", dao.rangeCutoffs().isEmpty())
            assertTrue("model cutoffs must be cleared by full deletion", dao.modelResetCutoffs().isEmpty())
            assertEquals(3L, dao.fullResetCutoff()!!.generation)

            // FULL 覆盖：更早接受的事件全部跳过（含跨越中间删除的 generation）
            assertFalse(dao.insertEventIfNotResetCovered(event("drain-old", "x-1", startedAtMs = 1_000L, generation = 0L)))
            assertFalse(dao.insertEventIfNotResetCovered(event("drain-old-2", "y-1", startedAtMs = 2_000L, generation = 1L)))
            // 删除后新事件正常
            assertTrue(dao.insertEventIfNotResetCovered(event("new", "x-1", startedAtMs = 3_000L, generation = 3L)))
            assertEquals(1, dao.countEvents())
            assertNotNull(dao.getEvent("new"))
        }

    // ==== legacy cleanup outbox（阶段 5 P1 闭环） ====

    @Test
    fun `display group deletion with baseline creates cleanup operation with exact legacy items`() =
        runBlocking {
            // group-x：legacy A（configId=""）、配置身份 cfg-B、legacy C —— 只登记 A 和 C
            seedIdentity("x-legacy-a", configId = "", provider = "OPENAI", model = "gpt-4o", displayModelId = "group-x")
            seedIdentity("x-cfg-b", configId = "cfg-b", provider = "DEEPSEEK", model = "deepseek-chat", displayModelId = "group-x")
            seedIdentity("x-legacy-c", configId = "", provider = "ANTHROPIC", model = "claude-3-5-sonnet", displayModelId = "group-x")
            dao.upsertBaseline(baseline("x-legacy-a"))
            dao.upsertBaseline(baseline("x-cfg-b"))
            dao.upsertBaseline(baseline("x-legacy-c"))

            val result = dao.deleteDisplayModelEventsTx("group-x", deleteBaselines = true)
            assertEquals(0, result.deletedEvents)
            val op = result.cleanupOperation
            assertNotNull("legacy members must produce a cleanup operation", op)
            assertEquals(TokenStatCleanupOperationEntity.SCOPE_DISPLAY_GROUP, op!!.scope)
            assertEquals("group-x", op.targetRef)
            assertEquals(TokenStatCleanupOperationEntity.STATUS_PENDING, op.status)
            val items = dao.getCleanupItems(op.operationId)
            assertEquals(
                "only legacy members are registered as immutable provider:model snapshots",
                listOf("OPENAI:gpt-4o", "ANTHROPIC:claude-3-5-sonnet"),
                items.map { "${it.provider}:${it.model}" },
            )
            assertEquals(listOf("x-legacy-a", "x-legacy-c"), items.map { it.identityId })
            // baseline 全删（含配置身份），身份/分组保留
            assertNull(dao.getBaseline("x-legacy-a"))
            assertNull(dao.getBaseline("x-cfg-b"))
            assertNull(dao.getBaseline("x-legacy-c"))
            assertEquals(3, dao.getAllIdentities().size)
        }

    @Test
    fun `display group deletion with baseline=no never creates cleanup operation`() = runBlocking {
        seedIdentity("x-legacy-a", configId = "", provider = "OPENAI", model = "gpt-4o", displayModelId = "group-x")
        dao.upsertBaseline(baseline("x-legacy-a"))

        // baseline=no：即使组内有 legacy 成员也不建 operation、不清共享键
        val result = dao.deleteDisplayModelEventsTx("group-x", deleteBaselines = false)
        assertEquals(0, result.deletedEvents)
        assertNull(result.cleanupOperation)
        assertEquals(0, dao.countPendingCleanupOperations())
        assertNotNull("baseline must survive when not confirmed", dao.getBaseline("x-legacy-a"))
    }

    @Test
    fun `member moves linearize with the deletion transaction snapshot`() = runBlocking {
        seedIdentity("x-legacy-a", configId = "", provider = "OPENAI", model = "gpt-4o", displayModelId = "group-x")
        seedIdentity("f-legacy", configId = "", provider = "DEEPSEEK", model = "deepseek-chat", displayModelId = "group-x")
        seedIdentity("e-legacy", configId = "", provider = "ANTHROPIC", model = "claude-3-5-sonnet", displayModelId = "group-other")

        // 已提交的移入/移出（各自独立事务）——删除事务必须看到提交后的成员归属
        dao.updateIdentityDisplayModel("f-legacy", "group-other")
        dao.updateIdentityDisplayModel("e-legacy", "group-x")

        val result = dao.deleteDisplayModelEventsTx("group-x", deleteBaselines = true)
        assertEquals(0, result.deletedEvents)
        val items = dao.getCleanupItems(result.cleanupOperation!!.operationId)
        assertEquals(
            "deletion must use the membership snapshot at transaction time",
            listOf("e-legacy", "x-legacy-a"),
            items.map { it.identityId }.sorted(),
        )
        // 删除事务提交后再移入的成员绝不被该 operation 覆盖（快照不可变）
        dao.updateIdentityDisplayModel("f-legacy", "group-x")
        assertEquals(
            listOf("e-legacy", "x-legacy-a"),
            dao.getCleanupItems(result.cleanupOperation!!.operationId).map { it.identityId }.sorted(),
        )
    }

    @Test
    fun `delete all with baseline creates ALL kind operation and without baseline creates none`() =
        runBlocking {
            seedIdentity("id-a", configId = "cfg-a")
            dao.insertEvent(event("e-a", "id-a", startedAtMs = 1_000L))
            dao.upsertBaseline(baseline("id-a"))

            val noBaseline = dao.deleteAllStatisticsTx(deleteBaselines = false)
            assertEquals(1, noBaseline.deletedEvents)
            assertNull("baseline=no must not create ALL cleanup", noBaseline.cleanupOperation)
            assertEquals(0, dao.countPendingCleanupOperations())
            assertNotNull(dao.getBaseline("id-a"))

            val withBaseline = dao.deleteAllStatisticsTx(deleteBaselines = true)
            assertEquals(0, withBaseline.deletedEvents)
            val op = withBaseline.cleanupOperation
            assertNotNull(op)
            assertEquals(TokenStatCleanupOperationEntity.SCOPE_ALL, op!!.scope)
            assertEquals(TokenStatCleanupOperationEntity.STATUS_PENDING, op.status)
            assertTrue("ALL kind carries no items", dao.getCleanupItems(op.operationId).isEmpty())
        }

    @Test
    fun `model reset creates cleanup operation only for legacy members`() = runBlocking {
        seedIdentity("m-legacy", configId = "", provider = "DEEPSEEK", model = "deepseek-chat", displayModelId = "deepseek-chat")
        seedIdentity("m-cfg", configId = "cfg-1", provider = "DEEPSEEK", model = "deepseek-chat", displayModelId = "deepseek-chat")
        seedIdentity("other", configId = "", provider = "OPENAI", model = "gpt-4o", displayModelId = "gpt-4o")
        dao.upsertBaseline(baseline("m-legacy"))
        dao.upsertBaseline(baseline("m-cfg"))
        dao.upsertBaseline(baseline("other"))

        val op = dao.resetModelTx("DEEPSEEK", "deepseek-chat")
        assertNotNull(op)
        assertEquals(TokenStatCleanupOperationEntity.SCOPE_MODEL, op!!.scope)
        assertEquals("DEEPSEEK:deepseek-chat", op.targetRef)
        val items = dao.getCleanupItems(op.operationId)
        assertEquals("only legacy members are registered", listOf("m-legacy"), items.map { it.identityId })
        assertNull(dao.getBaseline("m-legacy"))
        assertNull(dao.getBaseline("m-cfg"))
        assertNotNull("other model must survive", dao.getBaseline("other"))

        // 另一模型的 legacy 成员同样被登记（精确到自身 provider:model）
        val opOther = dao.resetModelTx("OPENAI", "gpt-4o")
        assertNotNull(opOther)
        assertEquals(
            listOf("other"),
            dao.getCleanupItems(opOther!!.operationId).map { it.identityId },
        )
        assertNull(dao.getBaseline("other"))

        // 完全无身份的模型：不建 operation
        val opEmpty = dao.resetModelTx("ANTHROPIC", "claude-3-5-sonnet")
        assertNull(opEmpty)
    }

    @Test
    fun `cleanup fence rejects pending or unmarked operations`() = runBlocking {
        // 空 outbox：任意 marker 集合都通过
        assertTrue(dao.cleanupFenceSatisfied(emptySet()))
        assertTrue(dao.cleanupFenceSatisfied(setOf("any-marker")))

        // PENDING 存在 → 拒绝（即使 marker 齐全）
        seedIdentity("x-legacy-a", configId = "", provider = "OPENAI", model = "gpt-4o", displayModelId = "group-x")
        val result = dao.deleteDisplayModelEventsTx("group-x", deleteBaselines = true)
        val opId = result.cleanupOperation!!.operationId
        assertFalse("PENDING must block the import fence", dao.cleanupFenceSatisfied(setOf(opId)))

        // APPLIED 且 marker 在 → 通过
        assertEquals(1, dao.ackCleanupOperation(opId))
        assertTrue(dao.cleanupFenceSatisfied(setOf(opId)))
        // APPLIED 但 marker 缺失（旧快照）→ 拒绝
        assertFalse("missing marker must block the import fence", dao.cleanupFenceSatisfied(emptySet()))
        // ACK 幂等：重复 ACK 返回 0
        assertEquals(0, dao.ackCleanupOperation(opId))
    }
}
