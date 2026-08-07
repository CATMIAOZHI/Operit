package com.ai.assistance.operit.ui.features.tokenstats

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.PriceOverrideScope
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatEventEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.stats.JdbcSQLiteDriver
import com.ai.assistance.operit.data.stats.TokenCostCurrency
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.data.stats.TokenStatIdentityResolver
import com.ai.assistance.operit.data.stats.TokenStatStatus
import com.ai.assistance.operit.data.stats.TokenStatsCostMode
import com.ai.assistance.operit.data.stats.TokenStatsPreset
import com.ai.assistance.operit.data.stats.TokenStatsQueryService
import com.ai.assistance.operit.data.stats.TokenStatsSettingsStore
import com.ai.assistance.operit.data.stats.TokenStatsTimeSelection
import com.ai.assistance.operit.data.stats.TokenStatsPriceOverrideDraft
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * 统计页 ViewModel 逻辑测试（阶段 4，JVM + 真实 Room + 内存假偏好存储）：
 * 首次自动回退与手选锁定、自定义范围校验、筛选刷新、设置保存与持久化、
 * 价格覆盖与分组操作后刷新。不触碰 DataStore/真机。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenUsageStatisticsViewModelTest {

    private lateinit var tempDir: File
    private lateinit var database: AppDatabase
    private lateinit var dao: TokenStatsDao
    private lateinit var context: Context
    private lateinit var settings: FakeSettingsStore

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val nowMs = localMs("2026-08-07T15:00:00", shanghai)

    private fun localMs(dateTime: String, zone: ZoneId): Long =
        java.time.LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tempDir = kotlin.io.path.createTempDirectory("token-vm-test").toFile()
        context = mockContext(tempDir)
        database =
            Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
                .setDriver(JdbcSQLiteDriver())
                .addMigrations(AppDatabase.MIGRATION_28_29, AppDatabase.MIGRATION_29_30)
                .allowMainThreadQueries()
                .build()
        dao = database.tokenStatsDao()
        settings = FakeSettingsStore()
        TokenStatsQueryService.databaseProvider = { database }
        TokenStatsQueryService.legacyPricesProvider = { emptyMap() }
        TokenStatsQueryService.queryDispatcher = UnconfinedTestDispatcher()
    }

    @After
    fun tearDown() {
        TokenStatsQueryService.databaseProvider = null
        TokenStatsQueryService.legacyPricesProvider = null
        TokenStatsQueryService.queryDispatcher = Dispatchers.IO
        database.close()
        Dispatchers.resetMain()
    }

    private fun mockContext(filesDir: File): Context {
        val context = Mockito.mock(Context::class.java)
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("com.ai.assistance.operit")
        whenever(context.filesDir).thenReturn(filesDir)
        whenever(context.getDatabasePath(any())).thenAnswer { invocation ->
            File(filesDir, invocation.getArgument<String>(0))
        }
        return context
    }

    private fun newViewModel(): TokenUsageStatisticsViewModel =
        TokenUsageStatisticsViewModel(
            context = context,
            settings = settings,
            zone = shanghai,
            nowMs = { nowMs },
            dao = dao,
            // JVM 测试：Room 在后台线程恢复协程，非 Main 调度器 + 假文案，
            // 避免 TestMainDispatcher/不可 mock 的 Context.getString
            stringResolver = { "msg-$it" },
            dispatcher = Dispatchers.Unconfined,
        )

    /**
     * 等待异步查询落定：Room 在 arch 后台线程恢复协程，不能靠虚拟时间推进；
     * 用 refreshVersion 单调递增判断“本次操作触发的查询已完成”。
     */
    private fun awaitRefresh(viewModel: TokenUsageStatisticsViewModel, fromVersion: Long) {
        val deadline = System.currentTimeMillis() + 15_000
        while (viewModel.state.value.refreshVersion <= fromVersion) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for refresh (loading=${viewModel.state.value.loading})")
            }
            Thread.sleep(10)
        }
    }

    private fun awaitActionMessage(viewModel: TokenUsageStatisticsViewModel) {
        val deadline = System.currentTimeMillis() + 15_000
        while (viewModel.actionMessage.value == null) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for action message")
            }
            Thread.sleep(10)
        }
    }

    private suspend fun seedIdentity(
        identityId: String,
        configId: String = "cfg-1",
        provider: String = "OPENAI",
        model: String = "gpt-4o",
    ) {
        val displayModelId = TokenStatIdentityResolver.displayModelIdFor(model)
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
        status: String = TokenStatStatus.COMPLETED.name,
        category: String = TokenStatCategory.CHAT.name,
        cost: Double? = 0.01,
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
            pricingSource = com.ai.assistance.operit.data.stats.PricingSource.DEFAULT.name,
            costInPricingCurrency = cost,
            diagnosticsJson = null,
        )

    // ==== 首次自动回退 ====

    @Test
    fun `initial fallback picks first preset with data and persists it as auto`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1")
            // 事件只在 6 天前：5h/12h/24h 空，7d 有数据
            dao.insertEvent(event("e1", "id-1", nowMs - 6L * 24 * 3600_000L + 12 * 3600_000L))
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)
        assertEquals(TokenStatsPreset.LAST_7D, viewModel.state.value.selectedPreset)
        assertFalse(viewModel.state.value.userChoseTime)
        // 自动回退结果已持久化（manual=false）：下次进入直接复用，不再探测（P1-2）
        assertNotNull(settings.savedSelection)
        assertEquals(TokenStatsPreset.LAST_7D, settings.savedSelection!!.preset)
        assertFalse(settings.savedManual)
        assertEquals(1, settings.timeSelectionSaveCount)
    }

    @Test
    fun `second viewmodel reuses persisted auto fallback without probing`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1")
            // 事件只在 6 天前：5h/12h/24h 空，7d 有数据
            dao.insertEvent(event("e1", "id-1", nowMs - 6L * 24 * 3600_000L + 12 * 3600_000L))
        }
        val vm1 = newViewModel()
        awaitRefresh(vm1, 0)
        assertEquals(TokenStatsPreset.LAST_7D, vm1.state.value.selectedPreset)
        assertEquals(1, settings.timeSelectionSaveCount)

        // 清空事件：若第二个 VM 重新探测会回退到 5h（无任何数据）；必须复用
        // 已保存的自动选择且不再次保存（P1-2 跨 VM 验证）
        runBlocking { dao.deleteAllEvents() }
        val vm2 = newViewModel()
        awaitRefresh(vm2, 0)
        assertEquals(TokenStatsPreset.LAST_7D, vm2.state.value.selectedPreset)
        assertFalse(vm2.state.value.userChoseTime)
        assertEquals(1, settings.timeSelectionSaveCount)
        assertEquals(0L, vm2.state.value.range?.eventCount)
    }

    @Test
    fun `user selection locks time and disables auto fallback`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1")
            // 数据在 30 小时前：5h/12h/24h 全空（自动回退会选 7d），但用户已选择 24h
            dao.insertEvent(event("e1", "id-1", nowMs - 30 * 3600_000L))
            settings.savedSelection = TokenStatsTimeSelection(TokenStatsPreset.LAST_24H)
            settings.savedManual = true
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)
        // 用户选择过 → 不自动回退
        assertEquals(TokenStatsPreset.LAST_24H, viewModel.state.value.selectedPreset)
        assertTrue(viewModel.state.value.userChoseTime)
        assertTrue(viewModel.state.value.range?.eventCount == 0L)
        // 已有选择 → 本次 load 不再保存
        assertEquals(0, settings.timeSelectionSaveCount)
    }

    // ==== 自定义范围 ====

    @Test
    fun `custom range rejects invalid and accepts valid bounds`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1")
            dao.insertEvent(event("e1", "id-1", nowMs - 2 * 3600_000L))
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)
        val start = nowMs - 24 * 3600_000L

        // end <= start → 拒绝，不改变选择
        val rejected = viewModel.setCustomRange(start, start)
        assertFalse(rejected)
        assertNotNull(viewModel.actionMessage.value)
        assertEquals(TokenStatsPreset.LAST_5H, viewModel.state.value.selectedPreset)

        // 超过 3 年 → 拒绝
        val tooLong =
            viewModel.setCustomRange(
                start - TokenUsageStatisticsViewModel.MAX_CUSTOM_RANGE_DAYS * 24 * 3600_000L - 1,
                start,
            )
        assertFalse(tooLong)

        // 合法范围 → 应用并查询
        val versionBefore = viewModel.state.value.refreshVersion
        val accepted = viewModel.setCustomRange(start, nowMs)
        assertTrue(accepted)
        awaitRefresh(viewModel, versionBefore)
        assertEquals(TokenStatsPreset.CUSTOM, viewModel.state.value.selectedPreset)
        assertTrue(viewModel.state.value.userChoseTime)
        assertNotNull(viewModel.state.value.customRange)
        assertEquals(1L, viewModel.state.value.range?.eventCount)
        // 持久化（manual=true：用户手动选择）
        assertNotNull(settings.savedSelection)
        assertEquals(TokenStatsPreset.CUSTOM, settings.savedSelection!!.preset)
        assertTrue(settings.savedManual)
    }

    // ==== 筛选刷新 ====

    @Test
    fun `model filter refreshes range data`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", model = "gpt-4o")
            seedIdentity("id-2", configId = "cfg-2", model = "claude-3-5-sonnet")
            dao.insertEvents(
                listOf(
                    event("e1", "id-1", nowMs - 3_600_000L),
                    event("e2", "id-2", nowMs - 3_600_000L),
                )
            )
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)
        assertEquals(2, viewModel.state.value.range?.displayModels?.size)

        val gptId = TokenStatIdentityResolver.displayModelIdFor("gpt-4o")
        val v1 = viewModel.state.value.refreshVersion
        viewModel.toggleModel(gptId)
        awaitRefresh(viewModel, v1)
        val filtered = viewModel.state.value.range
        assertEquals(1, filtered?.displayModels?.size)
        assertEquals(gptId, filtered?.displayModels?.single()?.displayModelId)
        assertEquals(1L, filtered?.eventCount)

        // 再点一次 → 全部
        val v2 = viewModel.state.value.refreshVersion
        viewModel.toggleModel(gptId)
        awaitRefresh(viewModel, v2)
        assertEquals(2, viewModel.state.value.range?.displayModels?.size)
    }

    @Test
    fun `category and status filters refresh range data`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1")
            dao.insertEvents(
                listOf(
                    event("e1", "id-1", nowMs - 3_600_000L),
                    event(
                        "e2", "id-1", nowMs - 2 * 3_600_000L,
                        category = TokenStatCategory.CONNECTION_TEST.name,
                    ),
                    event(
                        "e3", "id-1", nowMs - 3 * 3_600_000L,
                        status = TokenStatStatus.FAILED.name,
                    ),
                )
            )
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)
        assertEquals(3L, viewModel.state.value.range?.eventCount)

        val v1 = viewModel.state.value.refreshVersion
        viewModel.toggleCategory(TokenStatCategory.CONNECTION_TEST)
        awaitRefresh(viewModel, v1)
        val byCategory = viewModel.state.value.range
        assertEquals(1L, byCategory?.eventCount)
        assertEquals(
            setOf(TokenStatCategory.CONNECTION_TEST),
            byCategory?.categories?.map { it.category }?.toSet(),
        )

        val v2 = viewModel.state.value.refreshVersion
        viewModel.toggleCategory(TokenStatCategory.CONNECTION_TEST)
        awaitRefresh(viewModel, v2)
        assertEquals(3L, viewModel.state.value.range?.eventCount)

        val v3 = viewModel.state.value.refreshVersion
        viewModel.toggleStatus(TokenStatStatus.FAILED)
        awaitRefresh(viewModel, v3)
        assertEquals(1L, viewModel.state.value.range?.eventCount)
    }

    // ==== P1-5：模型下拉选项不受模型筛选影响 ====

    @Test
    fun `model dropdown options stay complete after selecting a model`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", model = "gpt-4o")
            seedIdentity("id-2", configId = "cfg-2", model = "claude-3-5-sonnet")
            dao.insertEvents(
                listOf(
                    event("e1", "id-1", nowMs - 3_600_000L, category = TokenStatCategory.CHAT.name),
                    event(
                        "e2", "id-2", nowMs - 3_600_000L,
                        category = TokenStatCategory.CONNECTION_TEST.name,
                    ),
                )
            )
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)
        val gptId = TokenStatIdentityResolver.displayModelIdFor("gpt-4o")
        val claudeId = TokenStatIdentityResolver.displayModelIdFor("claude-3-5-sonnet")
        assertEquals(2, viewModel.state.value.availableDisplayModels.size)

        // 选 A（gpt）后：结果只剩 A，但下拉选项仍含 B（P1-5）
        val v1 = viewModel.state.value.refreshVersion
        viewModel.toggleModel(gptId)
        awaitRefresh(viewModel, v1)
        assertEquals(
            setOf(gptId),
            viewModel.state.value.range?.displayModels?.map { it.displayModelId }?.toSet(),
        )
        assertEquals(
            setOf(gptId, claudeId),
            viewModel.state.value.availableDisplayModels.map { it.displayModelId }.toSet(),
        )

        // 再选 B → AB 同时选中，结果恢复两个模型
        val v2 = viewModel.state.value.refreshVersion
        viewModel.toggleModel(claudeId)
        awaitRefresh(viewModel, v2)
        assertEquals(setOf(gptId, claudeId), viewModel.state.value.selectedModels)
        assertEquals(2, viewModel.state.value.range?.displayModels?.size)

        // B 被分类筛选出当前结果（也离开 available）后，仍保留在选项中可显示
        val v3 = viewModel.state.value.refreshVersion
        viewModel.toggleCategory(TokenStatCategory.CHAT)
        awaitRefresh(viewModel, v3)
        val range = viewModel.state.value.range
        assertEquals(
            setOf(gptId),
            range?.displayModels?.map { it.displayModelId }?.toSet(),
        )
        assertFalse(viewModel.state.value.availableDisplayModels.any { it.displayModelId == claudeId })
        assertEquals("claude-3-5-sonnet", viewModel.state.value.knownModelNames[claudeId])
        assertTrue(claudeId in viewModel.state.value.selectedModels)
    }

    // ==== P2：全选/清空只触发一次查询 ====

    @Test
    fun `clearing all categories or statuses triggers exactly one load`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1")
            dao.insertEvents(
                listOf(
                    event("e1", "id-1", nowMs - 3_600_000L),
                    event(
                        "e2", "id-1", nowMs - 2 * 3_600_000L,
                        category = TokenStatCategory.CONNECTION_TEST.name,
                    ),
                    event(
                        "e3", "id-1", nowMs - 3 * 3_600_000L,
                        status = TokenStatStatus.FAILED.name,
                    ),
                )
            )
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)

        // 先选中两个分类（各自一次查询），再一键清空：必须只再查询一次
        val v0 = viewModel.state.value.refreshVersion
        viewModel.toggleCategory(TokenStatCategory.CHAT)
        awaitRefresh(viewModel, v0)
        val v1 = viewModel.state.value.refreshVersion
        viewModel.toggleCategory(TokenStatCategory.CONNECTION_TEST)
        awaitRefresh(viewModel, v1)
        assertEquals(2, viewModel.state.value.selectedCategories!!.size)

        val beforeClear = viewModel.state.value.refreshVersion
        viewModel.clearCategories()
        awaitRefresh(viewModel, beforeClear)
        assertEquals(beforeClear + 1, viewModel.state.value.refreshVersion)
        assertNull(viewModel.state.value.selectedCategories)

        // 状态同理
        val v2 = viewModel.state.value.refreshVersion
        viewModel.toggleStatus(TokenStatStatus.COMPLETED)
        awaitRefresh(viewModel, v2)
        val v3 = viewModel.state.value.refreshVersion
        viewModel.toggleStatus(TokenStatStatus.FAILED)
        awaitRefresh(viewModel, v3)
        assertEquals(2, viewModel.state.value.selectedStatuses!!.size)

        val beforeClearStatus = viewModel.state.value.refreshVersion
        viewModel.clearStatuses()
        awaitRefresh(viewModel, beforeClearStatus)
        assertEquals(beforeClearStatus + 1, viewModel.state.value.refreshVersion)
        assertNull(viewModel.state.value.selectedStatuses)
    }

    // ==== P1-4：旧 load 不得污染共享 state ====

    @Test
    fun `stale load cannot overwrite newer load result`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1")
            dao.insertEvent(event("e1", "id-1", nowMs - 3_600_000L))
        }
        val gated = GatedSettingsStore()
        val vm =
            TokenUsageStatisticsViewModel(
                context = context,
                settings = gated,
                zone = shanghai,
                nowMs = { nowMs },
                dao = dao,
                stringResolver = { "msg-$it" },
                dispatcher = Dispatchers.Unconfined,
            )
        // 第一次 load 卡在偏好读取（构造期间已挂起，尚未写任何 state）
        runBlocking { withTimeout(5_000) { gated.firstLoadStarted.await() } }

        // 第二次 load：汇率已改 → 完成后 state 必须是最新参数
        gated.savedRate = 7.5
        gated.rateEstimated = false
        val v = vm.state.value.refreshVersion
        vm.load()
        awaitRefresh(vm, v)
        assertEquals(7.5, vm.state.value.manualRate, 0.0)
        assertFalse(vm.state.value.rateIsEstimated)
        val versionAfterSecond = vm.state.value.refreshVersion

        // 释放 gate：旧 load 已被取消（Job cancel），不得再写 state
        gated.gate.complete(Unit)
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.state.value.refreshVersion != versionAfterSecond) {
            if (System.currentTimeMillis() > deadline) {
                fail("stale load overwrote newer state")
            }
            Thread.sleep(10)
        }
        assertEquals(7.5, vm.state.value.manualRate, 0.0)
        assertFalse(vm.state.value.rateIsEstimated)
        assertFalse(vm.state.value.loading)
    }

    // ==== P1-3：生命周期 ====

    @Test
    fun `viewmodel clear cancels pending load before it writes state`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1")
            dao.insertEvent(event("e1", "id-1", nowMs - 3_600_000L))
        }
        val dispatcher = StandardTestDispatcher()
        val vm =
            TokenUsageStatisticsViewModel(
                context = context,
                settings = settings,
                zone = shanghai,
                nowMs = { nowMs },
                dao = dao,
                stringResolver = { "msg-$it" },
                dispatcher = dispatcher,
            )
        // load 已入队但未执行；ViewModelStore.clear() 触发 onCleared →
        // viewModelScope 取消 → 任务不运行、不写 state、不执行首次回退持久化
        val store = androidx.lifecycle.ViewModelStore()
        store.put("token-stats", vm)
        store.clear()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0L, vm.state.value.refreshVersion)
        assertTrue(vm.state.value.loading) // 初始值，未被 load 改写
        assertEquals(0, settings.timeSelectionSaveCount)
    }

    // ==== 设置保存 ====

    @Test
    fun `currency cost mode and rate changes persist and refresh`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1")
            dao.insertEvent(event("e1", "id-1", nowMs - 3_600_000L))
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)
        assertEquals(PricingCurrency.CNY, viewModel.state.value.targetCurrency)
        assertTrue(viewModel.state.value.rateIsEstimated)
        assertEquals(TokenCostCurrency.DEFAULT_USD_TO_CNY_RATE, viewModel.state.value.manualRate, 0.0)

        val v1 = viewModel.state.value.refreshVersion
        viewModel.setTargetCurrency(PricingCurrency.USD)
        awaitRefresh(viewModel, v1)
        assertEquals(PricingCurrency.USD, viewModel.state.value.targetCurrency)
        assertEquals(PricingCurrency.USD, settings.savedCurrency)

        val v2 = viewModel.state.value.refreshVersion
        viewModel.setCostMode(TokenStatsCostMode.REVALUED)
        awaitRefresh(viewModel, v2)
        assertEquals(TokenStatsCostMode.REVALUED, viewModel.state.value.costMode)
        assertEquals(TokenStatsCostMode.REVALUED, settings.savedMode)

        // 手动汇率：合法保存后不再标记估算
        val v3 = viewModel.state.value.refreshVersion
        assertTrue(viewModel.setManualRate(7.35))
        awaitRefresh(viewModel, v3)
        assertEquals(7.35, viewModel.state.value.manualRate, 0.0)
        assertFalse(viewModel.state.value.rateIsEstimated)
        assertEquals(7.35, settings.savedRate, 0.0)

        // 非法汇率：拒绝且不持久化
        assertFalse(viewModel.setManualRate(-1.0))
        assertEquals(7.35, settings.savedRate, 0.0)
    }

    // ==== 价格覆盖与分组操作 ====

    @Test
    fun `price override save updates overrides and negative value fails with message`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1")
            dao.insertEvent(event("e1", "id-1", nowMs - 3_600_000L))
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)

        val v1 = viewModel.state.value.refreshVersion
        viewModel.upsertPriceOverride(
            TokenStatsPriceOverrideDraft(
                scope = PriceOverrideScope.PROVIDER_MODEL,
                provider = "OPENAI",
                model = "gpt-4o",
                configId = null,
                billingMode = BillingMode.TOKEN,
                currency = PricingCurrency.USD,
                inputPricePerMillion = 2.0,
                cachedInputPricePerMillion = null,
                cacheWritePricePerMillion = null,
                outputPricePerMillion = 8.0,
                pricePerRequest = null,
            )
        )
        awaitRefresh(viewModel, v1)
        assertEquals(1, viewModel.state.value.overrides.size)

        viewModel.upsertPriceOverride(
            TokenStatsPriceOverrideDraft(
                scope = PriceOverrideScope.PROVIDER_MODEL,
                provider = "OPENAI",
                model = "gpt-4o",
                configId = null,
                billingMode = BillingMode.TOKEN,
                currency = PricingCurrency.USD,
                inputPricePerMillion = -2.0,
                cachedInputPricePerMillion = null,
                cacheWritePricePerMillion = null,
                outputPricePerMillion = 8.0,
                pricePerRequest = null,
            )
        )
        awaitActionMessage(viewModel)
        assertTrue(viewModel.actionMessage.value!!.isError)
        assertEquals(1, viewModel.state.value.overrides.size)
    }

    @Test
    fun `editing price override keeps business key and only updates values`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1")
            dao.insertEvent(event("e1", "id-1", nowMs - 3_600_000L))
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)

        val draft =
            TokenStatsPriceOverrideDraft(
                scope = PriceOverrideScope.PROVIDER_MODEL,
                provider = "OPENAI",
                model = "gpt-4o",
                configId = null,
                billingMode = BillingMode.TOKEN,
                currency = PricingCurrency.USD,
                inputPricePerMillion = 2.0,
                cachedInputPricePerMillion = null,
                cacheWritePricePerMillion = null,
                outputPricePerMillion = 8.0,
                pricePerRequest = null,
            )
        val v1 = viewModel.state.value.refreshVersion
        viewModel.upsertPriceOverride(draft)
        awaitRefresh(viewModel, v1)
        val existing = viewModel.state.value.overrides.single()

        // 同键编辑 → 更新成功，仍只有一行（P1-7）
        val v2 = viewModel.state.value.refreshVersion
        viewModel.updatePriceOverride(existing, draft.copy(outputPricePerMillion = 9.0))
        awaitRefresh(viewModel, v2)
        assertEquals(1, viewModel.state.value.overrides.size)
        assertEquals(9.0, viewModel.state.value.overrides.single().outputPricePerMillion!!, 0.0)

        // 改业务键 → manager 拒绝：行不变 + 错误消息（P1-7）
        val v3 = viewModel.state.value.refreshVersion
        viewModel.updatePriceOverride(existing, draft.copy(provider = "ANTHROPIC"))
        awaitActionMessage(viewModel)
        assertTrue(viewModel.actionMessage.value!!.isError)
        assertEquals(1, viewModel.state.value.overrides.size)
        assertEquals("openai", viewModel.state.value.overrides.single().provider)
        assertEquals(v3, viewModel.state.value.refreshVersion)
    }

    @Test
    fun `group rename and create reflect in range display models`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1")
            seedIdentity("id-2", configId = "cfg-2")
            dao.insertEvents(
                listOf(
                    event("e1", "id-1", nowMs - 3_600_000L),
                    event("e2", "id-2", nowMs - 3_600_000L),
                )
            )
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)
        val gptId = TokenStatIdentityResolver.displayModelIdFor("gpt-4o")

        val v1 = viewModel.state.value.refreshVersion
        viewModel.renameDisplayGroup(gptId, "GPT-4 主力")
        awaitRefresh(viewModel, v1)
        assertEquals("GPT-4 主力", viewModel.state.value.range?.displayModels?.single()?.displayName)

        val v2 = viewModel.state.value.refreshVersion
        viewModel.createGroupAndMerge("组合模型", listOf("id-1", "id-2"))
        awaitRefresh(viewModel, v2)
        val models = viewModel.state.value.range?.displayModels.orEmpty()
        assertEquals(1, models.size)
        assertEquals("组合模型", models.single().displayName)
        assertEquals(2, models.single().identities.size)
    }

    // ==== P1 修复：分组元数据与统计筛选无关 ====

    @Test
    fun `group metadata stays complete when range only shows one member`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", model = "gpt-4o")
            seedIdentity("id-2", configId = "cfg-2", model = "claude-3-5-sonnet")
            // 只有 A 在 5h 预设范围内有事件；B 的事件在 8 天前（不在范围）
            dao.insertEvent(event("e1", "id-1", nowMs - 3_600_000L))
            dao.insertEvent(event("e2", "id-2", nowMs - 8L * 24 * 3600_000L))
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)
        val gptId = TokenStatIdentityResolver.displayModelIdFor("gpt-4o")
        val claudeId = TokenStatIdentityResolver.displayModelIdFor("claude-3-5-sonnet")

        // 范围明细（筛选结果）只含当前范围有事件的身份/分组：只看到 A
        assertEquals(1, viewModel.state.value.range?.displayModels?.size)
        assertEquals(gptId, viewModel.state.value.range?.displayModels?.single()?.displayModelId)

        // 完整分组元数据含两个分组与各自的完整成员（不受事件/筛选影响）
        val groups = viewModel.state.value.groupModels.associateBy { it.displayModelId }
        assertEquals(setOf(gptId, claudeId), groups.keys)
        assertEquals(listOf("id-1"), groups.getValue(gptId).memberIdentityIds)
        assertEquals(listOf("id-2"), groups.getValue(claudeId).memberIdentityIds)

        // 对话框按完整成员 id 执行合并（UI 从 groupModels 取）：两个身份都被移动
        val v1 = viewModel.state.value.refreshVersion
        viewModel.createGroupAndMerge("组合", listOf("id-1", "id-2"))
        awaitRefresh(viewModel, v1)
        val groupId = kotlinx.coroutines.runBlocking {
            dao.getAllDisplayModels().first { it.displayName == "组合" }.displayModelId
        }
        assertEquals(
            setOf(groupId),
            kotlinx.coroutines.runBlocking {
                dao.getAllIdentities().map { it.displayModelId }.toSet()
            },
        )
        // 刷新后的元数据同步：新组合组含完整成员；原默认组行保留但已无成员
        // （空组仍是合法目标，见 manager 语义）
        val groupsAfter = viewModel.state.value.groupModels
        assertEquals(
            setOf("id-1", "id-2"),
            groupsAfter.first { it.displayModelId == groupId }.memberIdentityIds.toSet(),
        )
        assertTrue(
            groupsAfter.filter { it.displayModelId != groupId }
                .all { it.memberIdentityIds.isEmpty() }
        )
    }

    @Test
    fun `group without events in range is still available as merge target`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", model = "gpt-4o")
            seedIdentity("id-3", configId = "cfg-3", model = "gemini-2.0-flash")
            // 只有 gpt 有事件；gemini 组完全无事件
            dao.insertEvent(event("e1", "id-1", nowMs - 3_600_000L))
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)
        val geminiId = TokenStatIdentityResolver.displayModelIdFor("gemini-2.0-flash")

        // 范围明细看不到 gemini（无事件），但完整元数据里有 → 对话框可列为目标
        assertFalse(
            viewModel.state.value.range?.displayModels?.any { it.displayModelId == geminiId }
                ?: true
        )
        assertTrue(viewModel.state.value.groupModels.any { it.displayModelId == geminiId })

        // 把 A 合并进无事件的目标组：操作成功，归属变更
        val v1 = viewModel.state.value.refreshVersion
        viewModel.mergeIntoGroup(listOf("id-1"), geminiId)
        awaitRefresh(viewModel, v1)
        assertEquals(
            geminiId,
            kotlinx.coroutines.runBlocking { dao.getIdentity("id-1")!!.displayModelId },
        )
    }
}

/** 内存假实现：验证持久化调用与首次回退语义（无用户选择 = null）。 */
private class FakeSettingsStore : TokenStatsSettingsStore {
    var savedRate: Double = TokenCostCurrency.DEFAULT_USD_TO_CNY_RATE
    var rateEstimated: Boolean = true
    var savedCurrency: PricingCurrency = PricingCurrency.CNY
    var savedMode: TokenStatsCostMode = TokenStatsCostMode.HISTORICAL
    var savedSelection: TokenStatsTimeSelection? = null
    var savedManual: Boolean = false
    /** saveTimeSelection 调用次数（P1-2：第二个 VM 不得再次保存/探测）。 */
    var timeSelectionSaveCount: Int = 0

    override suspend fun loadRateWithEstimate(): Pair<Double, Boolean> =
        savedRate to rateEstimated

    override suspend fun saveRate(rate: Double) {
        savedRate = rate
        rateEstimated = false
    }

    override suspend fun loadTargetCurrency(): PricingCurrency = savedCurrency

    override suspend fun saveTargetCurrency(currency: PricingCurrency) {
        savedCurrency = currency
    }

    override suspend fun loadCostMode(): TokenStatsCostMode = savedMode

    override suspend fun saveCostMode(mode: TokenStatsCostMode) {
        savedMode = mode
    }

    override suspend fun loadTimeSelection(): TokenStatsTimeSelection? = savedSelection

    override suspend fun loadSelectionWasManual(): Boolean = savedManual

    override suspend fun saveTimeSelection(selection: TokenStatsTimeSelection?, manual: Boolean) {
        savedSelection = selection
        savedManual = manual
        timeSelectionSaveCount++
    }
}

/**
 * 可控制挂起的偏好存储（P1-4）：第一次 [loadRateWithEstimate] 挂起在 [gate] 上
 * （期间不写任何 state），用于验证旧 load 被取消后不得覆盖新 load 的结果。
 */
private class GatedSettingsStore : TokenStatsSettingsStore {
    var savedRate: Double = TokenCostCurrency.DEFAULT_USD_TO_CNY_RATE
    var rateEstimated: Boolean = true
    var savedCurrency: PricingCurrency = PricingCurrency.CNY
    var savedMode: TokenStatsCostMode = TokenStatsCostMode.HISTORICAL
    var savedSelection: TokenStatsTimeSelection? = null
    var savedManual: Boolean = false
    val firstLoadStarted = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    private var rateReads = 0

    override suspend fun loadRateWithEstimate(): Pair<Double, Boolean> {
        rateReads++
        if (rateReads == 1) {
            firstLoadStarted.complete(Unit)
            gate.await()
        }
        return savedRate to rateEstimated
    }

    override suspend fun saveRate(rate: Double) {
        savedRate = rate
        rateEstimated = false
    }

    override suspend fun loadTargetCurrency(): PricingCurrency = savedCurrency

    override suspend fun saveTargetCurrency(currency: PricingCurrency) {
        savedCurrency = currency
    }

    override suspend fun loadCostMode(): TokenStatsCostMode = savedMode

    override suspend fun saveCostMode(mode: TokenStatsCostMode) {
        savedMode = mode
    }

    override suspend fun loadTimeSelection(): TokenStatsTimeSelection? = savedSelection

    override suspend fun loadSelectionWasManual(): Boolean = savedManual

    override suspend fun saveTimeSelection(selection: TokenStatsTimeSelection?, manual: Boolean) {
        savedSelection = selection
        savedManual = manual
    }
}
