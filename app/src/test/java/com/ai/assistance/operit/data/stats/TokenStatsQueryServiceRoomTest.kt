package com.ai.assistance.operit.data.stats

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.TokenStatBaselineEntity
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatEventEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.wheneverBlocking

/**
 * 统计查询服务集成测试（真实 Room + JVM SQLite 驱动，阶段 3）：
 * 同事务只读快照（P1-2，含并发提交一致性）、固定查询次数（防 N+1，SQL 记录驱动）、
 * 生命周期/范围分页增量聚合（>10k 不整表实体化）、展示模型筛选语义
 * （null=全部/空=无事件/1000+ 模型不触发 SQLite IN 上限）、IO 线程与 startedAtMs 索引
 * （P2-3）、半开边界、初始回退、重估端到端、baseline 不进范围、Context 生产入口。
 */
class TokenStatsQueryServiceRoomTest {

    private lateinit var tempDir: File
    private lateinit var recordingDriver: RecordingSQLiteDriver
    private lateinit var database: AppDatabase
    private lateinit var context: Context

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val nowMs = localMs("2026-08-07T15:00:00", shanghai)

    private fun localMs(dateTime: String, zone: ZoneId): Long =
        java.time.LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory("query-service-test").toFile()
        context = mockContext(tempDir)
        recordingDriver = RecordingSQLiteDriver()
        database =
            Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
                .setDriver(recordingDriver)
                .addMigrations(AppDatabase.MIGRATION_28_29, AppDatabase.MIGRATION_29_30, AppDatabase.MIGRATION_30_31)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        TokenStatsQueryService.databaseProvider = null
        TokenStatsQueryService.legacyPricesProvider = null
        TokenStatsQueryService.queryDispatcher = Dispatchers.IO
        TokenStatsQueryService.lifetimeEventPageSize = 1_000
        TokenStatsQueryService.rangeEventPageSize = 1_000
        TokenStatsQueryService.activityEventPageSize = 1_000
        database.close()
    }

    private fun mockContext(filesDir: File): Context {
        val context = mock<Context>()
        org.mockito.kotlin.whenever(context.applicationContext).thenReturn(context)
        org.mockito.kotlin.whenever(context.packageName).thenReturn("com.ai.assistance.operit")
        org.mockito.kotlin.whenever(context.filesDir).thenReturn(filesDir)
        org.mockito.kotlin.whenever(context.getDatabasePath(any())).thenAnswer { invocation ->
            File(filesDir, invocation.getArgument<String>(0))
        }
        return context
    }

    // ==== 种子数据 ====

    private suspend fun seedIdentity(
        dao: TokenStatsDao,
        identityId: String,
        configId: String = "cfg-1",
        provider: String = "OPENAI",
        model: String = "gpt-4o-2024-11-20",
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
        cost: Double? = null,
        status: String = TokenStatStatus.COMPLETED.name,
        category: String = TokenStatCategory.CHAT.name,
        uncached: Long? = 100L,
        cached: Long? = 0L,
        output: Long? = 50L,
    ): TokenStatEventEntity =
        TokenStatEventEntity(
            eventId = id,
            statIdentityId = identityId,
            category = category,
            status = status,
            acceptedGeneration = 0L,
            startedAtMs = startedAtMs,
            endedAtMs = startedAtMs + 1_000L,
            firstTokenAtMs = startedAtMs + 200L,
            uncachedInputTokens = uncached,
            cachedInputTokens = cached,
            cacheWriteTokens = 0L,
            totalInputTokens = null,
            outputTokens = output,
            reasoningTokens = null,
            reasoningIncludedInOutput = true,
            cacheWriteSeparateBilling = false,
            billingMode = BillingMode.TOKEN.name,
            pricingCurrency = PricingCurrency.USD.name,
            inputPricePerMillion = 1.5,
            cachedInputPricePerMillion = 1.5,
            cacheWritePricePerMillion = null,
            outputPricePerMillion = 6.0,
            pricePerRequest = null,
            pricingSource = PricingSource.DEFAULT.name,
            costInPricingCurrency = cost,
            diagnosticsJson = null,
        )

    private fun identityEntity(
        identityId: String,
        configId: String = "cfg-1",
        provider: String = "OPENAI",
        model: String = "gpt-4o-2024-11-20",
    ): TokenStatIdentityEntity =
        TokenStatIdentityEntity(
            identityId = identityId,
            configId = configId,
            provider = provider,
            model = model,
            displayModelId = TokenStatIdentityResolver.displayModelIdFor(model),
        )

    // ==== 集成：范围读取 ====

    @Test
    fun `range data aggregates events from one range read with half-open boundary`() = runBlocking {
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1")
        val start = localMs("2026-08-07T10:00:00", shanghai)
        val end = localMs("2026-08-07T15:00:00", shanghai)
        dao.insertEvents(
            listOf(
                event("e1", "id-1", start, cost = 1.0),
                event("e2", "id-1", start + 3_600_000L, cost = 2.0),
                event("e3", "id-1", end - 1L, cost = 3.0),
                // 恰好等于 endMs：半开区间 [start, end)，不属于范围
                event("e4", "id-1", end, cost = 4.0),
            )
        )
        val data =
            TokenStatsQueryService.rangeData(
                dao, TokenStatsTimeRanges.customRange(start, end),
                TokenStatsQueryParams(), shanghai,
            )
        assertEquals(3L, data.eventCount)
        assertEquals(3L, data.summary.requests)
        assertEquals(42.0, data.summary.cost.knownAmount, 1e-9) // (1+2+3)*7
        // 桶合计 == 范围总计；桶数 = 5 小时 / 10 分钟
        assertEquals(30, data.buckets.size)
        assertEquals(
            data.summary.requests,
            data.buckets.sumOf { it.totals.requests },
        )
        assertEquals(
            data.summary.cost.knownAmount,
            data.buckets.sumOf { it.totals.cost.knownAmount },
            1e-9,
        )
        // 明细与总计一致
        assertEquals(3L, data.displayModels.single().totals.requests)
        assertEquals(3L, data.categories.single { it.category == TokenStatCategory.CHAT }.totals.requests)
        assertEquals(3L, data.statuses.single().totals.requests)
        // e1 在 10:00:00 整点，属于第 0 个桶
        assertEquals(1L, data.buckets[0].totals.requests)
    }

    @Test
    fun `lifetime overview includes events and baseline`() = runBlocking {
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1")
        dao.insertEvents(listOf(event("e1", "id-1", nowMs - 3_600_000L, cost = 1.0)))
        dao.upsertBaseline(
            TokenStatBaselineEntity(
                identityId = "id-1",
                inputTokens = 100L,
                cachedInputTokens = 10L,
                outputTokens = 50L,
                requestCount = 5L,
                pricingCurrency = PricingCurrency.USD.name,
                costInPricingCurrency = 2.0,
                isEstimated = true,
                fingerprint = "fp",
                importedAtMs = 0L,
                frozenBillingMode = BillingMode.TOKEN.name,
            )
        )
        val overview = TokenStatsQueryService.lifetimeOverview(dao, TokenStatsQueryParams())
        assertEquals(1L, overview.eventTotals.requests)
        assertEquals(7.0, overview.eventTotals.cost.knownAmount, 1e-9)
        assertEquals(5L, overview.baselineTotals.requests)
        assertEquals(14.0, overview.baselineTotals.cost.knownAmount, 1e-9)
        assertEquals(6L, overview.combinedRequests)
    }

    @Test
    fun `baseline never enters range data`() = runBlocking {
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1")
        dao.upsertBaseline(
            TokenStatBaselineEntity(
                identityId = "id-1",
                inputTokens = 100L,
                cachedInputTokens = 10L,
                outputTokens = 50L,
                requestCount = 5L,
                pricingCurrency = PricingCurrency.USD.name,
                costInPricingCurrency = 2.0,
                isEstimated = true,
                fingerprint = "fp",
                importedAtMs = 0L,
                frozenBillingMode = BillingMode.TOKEN.name,
            )
        )
        val range = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_30D, nowMs, shanghai)
        val data = TokenStatsQueryService.rangeData(dao, range, TokenStatsQueryParams(), shanghai)
        assertEquals(0L, data.eventCount)
        assertEquals(0L, data.summary.requests)
        assertTrue(data.buckets.all { it.totals.requests == 0L })
    }

    // ==== 初始回退 ====

    @Test
    fun `initial preset falls back 5h to 12h to 24h to 7d to 30d`() = runBlocking {
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1")
        val hourMs = TokenStatsTimeRanges.HOUR_MS

        suspend fun presetFor(eventOffsetMs: Long): TokenStatsPreset {
            dao.deleteAllEvents()
            dao.insertEvents(listOf(event("e", "id-1", nowMs - eventOffsetMs, cost = 0.1)))
            return TokenStatsQueryService.initialPresetWithData(dao, shanghai, nowMs)
        }

        assertEquals(TokenStatsPreset.LAST_5H, presetFor(2L * hourMs))
        assertEquals(TokenStatsPreset.LAST_12H, presetFor(10L * hourMs))
        assertEquals(TokenStatsPreset.LAST_24H, presetFor(20L * hourMs))
        // 5 天前：5h/12h/24h 都空，7d（含今天共 7 个自然日）有数据
        assertEquals(TokenStatsPreset.LAST_7D, presetFor(5L * 24L * hourMs))
        // 25 天前：只有 30d 范围有数据
        assertEquals(TokenStatsPreset.LAST_30D, presetFor(25L * 24L * hourMs))
        // 40 天前：任何预设都空 -> 回退默认 5h
        assertEquals(TokenStatsPreset.LAST_5H, presetFor(40L * 24L * hourMs))

        dao.deleteAllEvents()
        assertEquals(TokenStatsPreset.LAST_5H, TokenStatsQueryService.initialPresetWithData(dao, shanghai, nowMs))
    }

    // ==== 模型筛选 ====

    @Test
    fun `display model filter returns only selected model events`() = runBlocking {
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1", model = "gpt-4o-2024-11-20", displayModelId = "gpt-4o-2024-11-20")
        seedIdentity(dao, "id-2", configId = "cfg-2", model = "deepseek-chat", displayModelId = "deepseek-chat")
        dao.insertEvents(
            listOf(
                event("e1", "id-1", nowMs - 3_600_000L, cost = 1.0),
                event("e2", "id-2", nowMs - 2 * 3_600_000L, cost = 2.0),
            )
        )
        val range = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_5H, nowMs, shanghai)
        val data =
            TokenStatsQueryService.rangeData(
                dao, range,
                TokenStatsQueryParams(displayModelIds = setOf("gpt-4o-2024-11-20")),
                shanghai,
            )
        assertEquals(1L, data.eventCount)
        assertEquals(1L, data.summary.requests)
        assertEquals(7.0, data.summary.cost.knownAmount, 1e-9)
        assertEquals(1, data.displayModels.size)
    }

    @Test
    fun `empty display model filter returns no events while null returns all`() = runBlocking {
        // P2-2 语义：displayModelIds = null → 全部模型；空集合 → 无事件（不是全部！）
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1")
        dao.insertEvents(listOf(event("e1", "id-1", nowMs - 3_600_000L, cost = 1.0)))
        val range = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_5H, nowMs, shanghai)

        val none =
            TokenStatsQueryService.rangeData(
                dao, range, TokenStatsQueryParams(displayModelIds = emptySet()), shanghai,
            )
        assertEquals(0L, none.eventCount)
        assertEquals(0L, none.summary.requests)
        assertTrue(none.displayModels.isEmpty())
        assertTrue(none.buckets.all { it.totals.requests == 0L })

        val all =
            TokenStatsQueryService.rangeData(
                dao, range, TokenStatsQueryParams(displayModelIds = null), shanghai,
            )
        assertEquals(1L, all.eventCount)
        assertEquals(1L, all.summary.requests)
    }

    // ==== 同事务快照与查询次数（SQL 记录驱动，防 N+1） ====

    @Test
    fun `range data does fixed snapshot reads without re-fetching tables`() = runBlocking {
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1")
        dao.insertEvents(listOf(event("e1", "id-1", nowMs - 3_600_000L, cost = 1.0)))
        recordingDriver.clear()

        val range = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_5H, nowMs, shanghai)
        val data =
            TokenStatsQueryService.rangeData(
                dao, range, TokenStatsQueryParams(), shanghai, legacyPrices = emptyMap(),
            )
        assertEquals(1L, data.summary.requests)

        val statements = recordingDriver.executed.toList()
        fun countWhere(predicate: (RecordedSql) -> Boolean): Int = statements.count(predicate)
        // 历史口径：事件/identity/display model 各恰好一次；价格覆盖与 baseline 不读
        assertEquals(
            1,
            countWhere { it.sql.contains("FROM token_stat_events") && it.sql.contains("WHERE startedAtMs") },
        )
        assertEquals(
            "recorded: ${statements.joinToString("\n") { it.toString() }}",
            1,
            countWhere { it.sql.contains("FROM token_stat_identities") },
        )
        assertEquals(1, countWhere { it.sql.contains("FROM token_stat_display_models") })
        assertEquals(0, countWhere { it.sql.contains("FROM token_stat_price_overrides") })
        assertEquals(0, countWhere { it.sql.contains("FROM token_stat_baselines") })
        // 绝不整表读取事件（getAllEvents）
        assertEquals(0, countWhere { it.sql.trim() == "SELECT * FROM token_stat_events" })

        // 重估口径：额外只读一次价格覆盖，其余不变
        TokenStatsQueryService.rangeData(
            dao, range, TokenStatsQueryParams(mode = TokenStatsCostMode.REVALUED), shanghai,
            legacyPrices = emptyMap(),
        )
        assertEquals(
            1,
            recordingDriver.executed.count { it.sql.contains("FROM token_stat_price_overrides") },
        )
    }

    @Test
    fun `display model filter uses identity snapshot and one bounded event page`() = runBlocking {
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1", model = "gpt-4o-2024-11-20", displayModelId = "gpt-4o-2024-11-20")
        seedIdentity(dao, "id-2", configId = "cfg-2", model = "deepseek-chat", displayModelId = "deepseek-chat")
        dao.insertEvents(
            listOf(
                event("e1", "id-1", nowMs - 3_600_000L, cost = 1.0),
                event("e2", "id-2", nowMs - 2 * 3_600_000L, cost = 2.0),
            )
        )
        recordingDriver.clear()

        val range = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_5H, nowMs, shanghai)
        val data =
            TokenStatsQueryService.rangeData(
                dao, range,
                TokenStatsQueryParams(displayModelIds = setOf("gpt-4o-2024-11-20", "deepseek-chat")),
                shanghai,
            )
        assertEquals(2L, data.summary.requests)
        assertEquals(0, recordingDriver.executed.count { it.sql.contains("displayModelId IN") })
        val pageQueries = recordingDriver.executed.filter { it.sql.contains("ORDER BY startedAtMs ASC") }
        assertEquals(1, pageQueries.size)
        assertTrue(pageQueries.single().rows <= TokenStatsQueryService.rangeEventPageSize)
    }

    @Test
    fun `display model filter over 900 models pages without SQLite IN bindings`() = runBlocking {
        val dao = database.tokenStatsDao()
        val modelCount = 1_001
        val identities =
            (0 until modelCount).map { index ->
                TokenStatIdentityEntity(
                    identityId = "id-$index",
                    configId = "cfg",
                    provider = "PROVIDER",
                    model = "m$index",
                    displayModelId = "m$index",
                )
            }
        dao.insertIdentitiesIfAbsent(identities)
        dao.upsertDisplayModels(
            identities.map { identity ->
                TokenStatDisplayModelEntity(
                    displayModelId = identity.displayModelId,
                    normalizedModel = identity.model,
                    displayName = identity.model,
                )
            }
        )
        dao.insertEvents(
            identities.map { identity ->
                event("e-${identity.identityId}", identity.identityId, nowMs - 3_600_000L, cost = 0.01)
            }
        )
        recordingDriver.clear()

        val range = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_5H, nowMs, shanghai)
        val data =
            TokenStatsQueryService.rangeData(
                dao, range,
                TokenStatsQueryParams(displayModelIds = (0 until modelCount).map { "m$it" }.toSet()),
                shanghai,
            )
        assertEquals(modelCount.toLong(), data.eventCount)
        assertEquals(modelCount.toLong(), data.summary.requests)
        assertEquals(modelCount, data.displayModels.size)

        assertEquals(0, recordingDriver.executed.count { it.sql.contains("displayModelId IN") })
        val pageQueries = recordingDriver.executed.filter { it.sql.contains("ORDER BY startedAtMs ASC") }
        assertEquals(2, pageQueries.size)
        assertTrue(pageQueries.all { it.rows <= TokenStatsQueryService.rangeEventPageSize })
    }

    // ==== 生命周期分页（P2-1：不整表实体化） ====

    @Test
    fun `lifetime overview pages events within page size and never materializes all`() = runBlocking {
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1")
        val count = 10_500
        dao.insertEvents(
            (0 until count).map { index ->
                event("bulk-$index", "id-1", nowMs - (index % 24) * 3_600_000L - index, cost = 0.1)
            }
        )
        recordingDriver.clear()

        val overview = TokenStatsQueryService.lifetimeOverview(dao, TokenStatsQueryParams())
        assertEquals(count.toLong(), overview.eventTotals.requests)
        assertEquals(count.toLong(), overview.eventTotals.cost.totalContributionCount)
        assertTrue(overview.eventTotals.cost.isFullyKnown)

        val statements = recordingDriver.executed.toList()
        // 绝不调用整表读取 getAllEvents
        assertEquals(
            0,
            statements.count { it.sql.trim() == "SELECT * FROM token_stat_events" },
        )
        val pageQueries = statements.filter { it.sql.contains("ORDER BY startedAtMs ASC") }
        // 10500 / 1000 = 10 满页 + 1 部分页
        assertEquals(11, pageQueries.size)
        // 每页 LIMIT 绑定 == 页大小，最大返回行数 ≤ 页大小
        assertTrue(pageQueries.all { it.binds[4] == "1000" })
        assertTrue(pageQueries.all { it.rows <= 1_000 })
        assertEquals(1_000, pageQueries.maxOf { it.rows })
    }

    @Test
    fun `lifetime paging respects injected small page size with exact page bounds`() = runBlocking {
        TokenStatsQueryService.lifetimeEventPageSize = 7
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1")
        dao.insertEvents(
            (0 until 20).map { index ->
                event("p$index", "id-1", nowMs - index * 1_000L, cost = 0.1)
            }
        )
        recordingDriver.clear()

        val overview = TokenStatsQueryService.lifetimeOverview(dao, TokenStatsQueryParams())
        assertEquals(20L, overview.eventTotals.requests)

        val pageQueries = recordingDriver.executed.filter { it.sql.contains("ORDER BY startedAtMs ASC") }
        // 7 + 7 + 6
        assertEquals(3, pageQueries.size)
        assertEquals(listOf(7, 7, 6), pageQueries.map { it.rows })
        assertTrue(pageQueries.all { it.binds[4] == "7" })
    }

    @Test
    fun `activity snapshot pages the ledger and incrementally preserves totals`() = runBlocking {
        TokenStatsQueryService.activityEventPageSize = 7
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1")
        dao.insertEvents(
            (0 until 20).map { index ->
                event(
                    id = "activity-$index",
                    identityId = "id-1",
                    startedAtMs = localMs("2026-08-${(1 + index / 5).toString().padStart(2, '0')}T${(index % 5 + 8).toString().padStart(2, '0')}:00:00", shanghai),
                )
            }
        )
        recordingDriver.clear()

        val snapshot = TokenStatsQueryService.activitySnapshot(dao, shanghai)

        assertEquals(20L, snapshot.totalRequests)
        assertEquals(4, snapshot.dayTotals.size)
        assertEquals(3_000L, snapshot.dayTotals.values.sum())
        assertEquals(20L, snapshot.hourCounts.sum())
        val pageQueries = recordingDriver.executed.filter {
            it.sql.contains("SELECT eventId, startedAtMs") && it.sql.contains("ORDER BY startedAtMs ASC")
        }
        assertEquals(listOf(7, 7, 6), pageQueries.map { it.rows })
        assertTrue(pageQueries.all { it.binds[4] == "7" })
    }

    // ==== 快照一致性（P1-2：并发提交完全前或完全后） ====

    private fun assertInternallyConsistent(data: TokenStatsRangeData, expectedRequests: Long) {
        assertEquals(expectedRequests, data.summary.requests)
        assertEquals(data.summary.requests, data.buckets.sumOf { it.totals.requests })
        assertEquals(data.summary.requests, data.displayModels.sumOf { it.totals.requests })
        assertEquals(
            data.summary.requests,
            data.displayModels.sumOf { model -> model.identities.sumOf { it.totals.requests } },
        )
        assertEquals(
            data.summary.cost.knownAmount,
            data.buckets.sumOf { it.totals.cost.knownAmount },
            1e-9,
        )
    }

    @Test
    fun `concurrent identity and event commit never yields partial snapshot`() = runBlocking {
        // P1-2：查询服务的所有 Room 读取在**同一事务快照**内。写入协程在读者
        // 反复查询期间提交新 identity 与事件（两次独立提交，窗口内读者可能读到
        // “identity 已提交、事件未提交”的中间态）。旧实现多次独立读取可跨越该
        // 窗口，出现 summary 有事件但模型桶缺失；新实现任何时刻都只能看到
        // 完全前（5）/完全后（10）且自洽的结果。
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-a", model = "model-a", displayModelId = "model-a")
        dao.insertEvents(
            (1..5).map { index -> event("a$index", "id-a", nowMs - index * 3_600_000L, cost = 1.0) }
        )
        val range = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_5H, nowMs, shanghai)
        assertInternallyConsistent(
            TokenStatsQueryService.rangeData(dao, range, TokenStatsQueryParams(), shanghai),
            5L,
        )

        val writer =
            launch(Dispatchers.IO) {
                // identity 与事件分两次提交；间隔由调度器自然产生，
                // 保证读者有机会落在两个提交之间
                seedIdentity(dao, "id-b", configId = "cfg-b", model = "model-b", displayModelId = "model-b")
                dao.insertEvents(
                    (6..10).map { index ->
                        event("b$index", "id-b", nowMs - (index - 5) * 3_600_000L, cost = 2.0)
                    }
                )
            }

        var sawPost = false
        repeat(60) {
            val data = TokenStatsQueryService.rangeData(dao, range, TokenStatsQueryParams(), shanghai)
            val requests = data.summary.requests
            assertTrue("requests must be 5 or 10, was $requests", requests == 5L || requests == 10L)
            assertInternallyConsistent(data, requests)
            if (requests == 10L) sawPost = true
        }
        writer.join()
        // 提交完成后：完全后状态，两个模型明细齐全
        val after = TokenStatsQueryService.rangeData(dao, range, TokenStatsQueryParams(), shanghai)
        assertInternallyConsistent(after, 10L)
        assertEquals(2, after.displayModels.size)
        assertEquals(
            setOf("id-a", "id-b"),
            after.displayModels.flatMap { it.identities.map { identity -> identity.identityId } }.toSet(),
        )
        assertTrue(sawPost)
    }

    // ==== 查询计划与线程（P2-3） ====

    @Test
    fun `time range query plan uses the startedAtMs index`() = runBlocking {
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1")
        val start = nowMs - 30L * TokenStatsTimeRanges.DAY_MS
        dao.insertEvents(
            (0 until 2000).map { index ->
                event("idx-$index", "id-1", start + index * TokenStatsTimeRanges.HOUR_MS, cost = 0.1)
            }
        )
        val dbFile = context.getDatabasePath("app_database").absolutePath
        JdbcSQLiteDriver().open(dbFile).use { connection ->
            connection.prepare("ANALYZE").use { it.step() }
            val plan = StringBuilder()
            connection.prepare(
                "EXPLAIN QUERY PLAN " +
                    "SELECT * FROM token_stat_events WHERE startedAtMs >= ? AND startedAtMs < ?"
            ).use { statement ->
                statement.bindLong(1, nowMs - 5L * TokenStatsTimeRanges.HOUR_MS)
                statement.bindLong(2, nowMs)
                while (statement.step()) {
                    for (column in 0 until statement.getColumnCount()) {
                        if (!statement.isNull(column)) plan.append(statement.getText(column)).append(' ')
                    }
                    plan.append('\n')
                }
            }
            assertTrue(
                "查询计划必须使用 startedAtMs 索引，实际: $plan",
                plan.contains("index_token_stat_events_startedAtMs"),
            )
        }
    }

    @Test
    fun `context facade executes room and aggregation on io dispatcher not caller thread`() = runBlocking {
        // P2-3：生产入口显式切到 queryDispatcher（默认 Dispatchers.IO），
        // 阶段 4 从 Main 调用不阻塞；通过注入缝记录执行线程。
        TokenStatsQueryService.databaseProvider = { database }
        var providerThread: String? = null
        TokenStatsQueryService.legacyPricesProvider = { _ ->
            providerThread = Thread.currentThread().name
            emptyMap()
        }
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1")
        dao.insertEvents(listOf(event("e1", "id-1", nowMs - 3_600_000L, cost = 1.0)))
        try {
            val data =
                TokenStatsQueryService.presetRangeData(
                    context, TokenStatsPreset.LAST_5H,
                    TokenStatsQueryParams(mode = TokenStatsCostMode.REVALUED),
                    shanghai, nowMs,
                )
            assertEquals(1L, data.summary.requests)
            assertTrue(
                "聚合必须运行在非调用线程（IO），实际: $providerThread",
                providerThread != null && providerThread != "main",
            )
        } finally {
            TokenStatsQueryService.legacyPricesProvider = null
        }
    }

    // ==== 查询次数（mock DAO 固定查询契约） ====

    @Test
    fun `range data loads one paged snapshot and never re-fetches dao`() = runBlocking {
        val dao = mock<TokenStatsDao>()
        wheneverBlocking {
            dao.loadRangeSnapshotPaged(
                anyLong(), anyLong(), anyOrNull(), anyBoolean(), anyInt(), any()
            )
        } doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val onPage =
                invocation.getArgument(5) as
                    (List<TokenStatEventEntity>, Map<String, TokenStatIdentityEntity>, Map<String, TokenStatDisplayModelEntity>, List<TokenStatPriceOverrideEntity>) -> Unit
            val identities = mapOf("id-1" to identityEntity("id-1"))
            onPage(listOf(event("e1", "id-1", 0L, cost = 1.0)), identities, emptyMap(), emptyList())
            TokenStatsRangeRead(
                identitiesById = identities,
                displayModelsById = emptyMap(),
                overrides = emptyList(),
            )
        }

        val range = TokenStatsTimeRanges.customRange(0L, 3_600_000L)
        val data = TokenStatsQueryService.rangeData(dao, range, TokenStatsQueryParams(), shanghai)
        assertEquals(1L, data.summary.requests)

        verifyBlocking(dao, Mockito.times(1)) {
            loadRangeSnapshotPaged(
                anyLong(), anyLong(), anyOrNull(), anyBoolean(), anyInt(), any()
            )
        }
        verifyBlocking(dao, never()) { getAllEvents() }
        verifyBlocking(dao, never()) {
            getEventsInRangePage(anyLong(), anyLong(), anyLong(), any(), anyInt())
        }
        verifyBlocking(dao, never()) { getAllIdentities() }
        verifyBlocking(dao, never()) { getAllDisplayModels() }
        verifyBlocking(dao, never()) { getAllPriceOverrides() }
        verifyBlocking(dao, never()) { getAllBaselines() }
    }

    @Test
    fun `range data passes display model filter list and revalued override flag`() = runBlocking {
        val dao = mock<TokenStatsDao>()
        wheneverBlocking {
            dao.loadRangeSnapshotPaged(
                anyLong(), anyLong(), anyOrNull(), anyBoolean(), anyInt(), any()
            )
        } doReturn
            TokenStatsRangeRead(
                identitiesById = emptyMap(),
                displayModelsById = emptyMap(),
                overrides = emptyList(),
            )

        val range = TokenStatsTimeRanges.customRange(0L, 3_600_000L)
        TokenStatsQueryService.rangeData(
            dao, range,
            TokenStatsQueryParams(displayModelIds = setOf("m1", "m2"), mode = TokenStatsCostMode.REVALUED),
            shanghai,
        )
        verifyBlocking(dao, Mockito.times(1)) {
            loadRangeSnapshotPaged(
                anyLong(), anyLong(), eq(listOf("m1", "m2")), eq(true), anyInt(), any()
            )
        }
    }

    @Test
    fun `lifetime overview loads one paged snapshot`() = runBlocking {
        val dao = mock<TokenStatsDao>()
        wheneverBlocking { dao.loadLifetimeSnapshot(anyBoolean(), anyInt(), any()) } doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val onPage =
                invocation.getArgument(2) as
                    (List<TokenStatEventEntity>, Map<String, TokenStatIdentityEntity>, List<TokenStatPriceOverrideEntity>) -> Unit
            onPage(listOf(event("e1", "id-1", 0L, cost = 1.0)), mapOf("id-1" to identityEntity("id-1")), emptyList())
            TokenStatsLifetimeRead(
                identitiesById = emptyMap(),
                displayModelsById = emptyMap(),
                overrides = emptyList(),
                baselines = emptyList(),
                totalEvents = 1L,
            )
        }

        val overview = TokenStatsQueryService.lifetimeOverview(dao, TokenStatsQueryParams())
        assertEquals(1L, overview.eventTotals.requests)
        verifyBlocking(dao, Mockito.times(1)) { loadLifetimeSnapshot(anyBoolean(), anyInt(), any()) }
        verifyBlocking(dao, never()) { getAllEvents() }
        verifyBlocking(dao, never()) { getAllBaselines() }
    }

    @Test
    fun `initial preset probes at most five exists queries`() = runBlocking {
        val dao = mock<TokenStatsDao>()
        wheneverBlocking { dao.rangeHasEvents(anyLong(), anyLong()) } doReturn false
        // 只有 7d 范围有数据：5h/12h/24h 各探测一次后命中
        val last7 = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_7D, nowMs, shanghai)
        wheneverBlocking { dao.rangeHasEvents(last7.startMs, last7.endMs) } doReturn true

        assertEquals(TokenStatsPreset.LAST_7D, TokenStatsQueryService.initialPresetWithData(dao, shanghai, nowMs))
        verifyBlocking(dao, Mockito.times(4)) { rangeHasEvents(anyLong(), anyLong()) }

        // 全部为空：5 次探测后回退默认
        val emptyDao = mock<TokenStatsDao>()
        wheneverBlocking { emptyDao.rangeHasEvents(anyLong(), anyLong()) } doReturn false
        assertEquals(
            TokenStatsPreset.LAST_5H,
            TokenStatsQueryService.initialPresetWithData(emptyDao, shanghai, nowMs),
        )
        verifyBlocking(emptyDao, Mockito.times(5)) { rangeHasEvents(anyLong(), anyLong()) }
    }

    // ==== 大事件量 ====

    @Test
    fun `large volume range query is a single read with consistent sums`() = runBlocking {
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "bulk", model = "gpt-4o-2024-11-20", displayModelId = "bulk")
        val range = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_30D, nowMs, shanghai)
        val count = 10_000
        val stepMs = range.durationMs / count
        val events =
            (0 until count).map { index ->
                event(
                    id = "bulk-$index",
                    identityId = "bulk",
                    startedAtMs = range.startMs + index * stepMs,
                    cost = (index % 10) * 0.1,
                )
            }
        dao.insertEvents(events)

        val data =
            TokenStatsQueryService.rangeData(
                dao, range, TokenStatsQueryParams(), shanghai, legacyPrices = emptyMap(),
            )

        assertEquals(count.toLong(), data.eventCount)
        assertEquals(count.toLong(), data.summary.requests)
        assertEquals(count.toLong(), data.buckets.sumOf { it.totals.requests })
        assertEquals(
            data.summary.cost.knownAmount,
            data.buckets.sumOf { it.totals.cost.knownAmount },
            1e-6,
        )
        assertEquals(data.summary.requests, data.displayModels.single().totals.requests)
        // 大数据量只验证结果与查询结构，不做脆弱时限断言
    }

    // ==== 重估端到端 ====

    @Test
    fun `revalued mode resolves current overrides end to end`() = runBlocking {
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1")
        dao.upsertPriceOverride(
            scope = TokenPriceResolver.SCOPE_PROVIDER_MODEL,
            provider = "OPENAI",
            model = "gpt-4o-2024-11-20",
            configId = null,
            billingMode = BillingMode.TOKEN.name,
            pricingCurrency = PricingCurrency.USD.name,
            inputPricePerMillion = 1.0,
            cachedInputPricePerMillion = 1.0,
            cacheWritePricePerMillion = null,
            outputPricePerMillion = 2.0,
            pricePerRequest = null,
        )
        dao.insertEvents(
            listOf(
                event("e1", "id-1", nowMs - 3_600_000L, cost = null, uncached = 1_000L, output = 500L),
            )
        )
        val range = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_5H, nowMs, shanghai)
        val data =
            TokenStatsQueryService.rangeData(
                dao, range,
                TokenStatsQueryParams(mode = TokenStatsCostMode.REVALUED),
                shanghai,
            )
        assertTrue(data.summary.cost.isFullyKnown)
        // (1000*1 + 500*2)/1e6 = 0.002 USD -> 0.014 CNY（覆盖价 1/2，非内置 1.5/6）
        assertEquals(0.014, data.summary.cost.knownAmount, 1e-9)
        val pricing = data.displayModels.single().identities.single().pricing!!
        assertEquals(BillingMode.TOKEN, pricing.billingMode)
        assertTrue(pricing.known)
    }

    // ==== Context 生产入口（注入缝） ====

    @Test
    fun `context facade resolves database through seam`() = runBlocking {
        TokenStatsQueryService.databaseProvider = { database }
        TokenStatsQueryService.legacyPricesProvider = { emptyMap() }
        val dao = database.tokenStatsDao()
        seedIdentity(dao, "id-1")
        dao.insertEvents(
            listOf(
                event("e1", "id-1", nowMs - 3_600_000L, cost = 1.0),
                event("e2", "id-1", nowMs - 2 * 3_600_000L, cost = 2.0),
            )
        )
        val data =
            TokenStatsQueryService.presetRangeData(
                context, TokenStatsPreset.LAST_5H, TokenStatsQueryParams(), shanghai, nowMs,
            )
        assertEquals(2L, data.summary.requests)
        assertEquals(21.0, data.summary.cost.knownAmount, 1e-9)

        val preset = TokenStatsQueryService.initialPresetWithData(context, shanghai, nowMs)
        assertEquals(TokenStatsPreset.LAST_5H, preset)
    }
}
