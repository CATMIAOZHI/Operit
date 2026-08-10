package com.ai.assistance.operit.ui.features.tokenstats

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.PriceOverrideScope
import com.ai.assistance.operit.data.model.TokenStatBaselineEntity
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatEventEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.stats.JdbcSQLiteDriver
import com.ai.assistance.operit.data.stats.TokenCostCurrency
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.data.stats.TokenStatIdentityResolver
import com.ai.assistance.operit.data.stats.TokenStatStatus
import com.ai.assistance.operit.data.stats.TokenStatsCostMode
import com.ai.assistance.operit.data.stats.TokenStatsLedger
import com.ai.assistance.operit.data.stats.TokenStatsPreset
import com.ai.assistance.operit.data.stats.TokenStatsQueryService
import com.ai.assistance.operit.data.stats.TokenStatsResetCoordinator
import com.ai.assistance.operit.data.stats.TokenStatsSettingsStore
import com.ai.assistance.operit.data.stats.TokenStatsReadiness
import com.ai.assistance.operit.data.stats.TokenStatsStartupCoordinator
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
        // ApiPreferences 是 JVM 级单例：删除“全部 + baseline”路径会走真实 DataStore，
        // 每个测试前清空单例，保证绑定到本测试的临时目录（与 ApiPreferencesResetFailureTest
        // 的隔离方式一致，避免跨测试共享 DataStore 文件）。
        clearApiPreferencesSingletons()
        tempDir = kotlin.io.path.createTempDirectory("token-vm-test").toFile()
        context = mockContext(tempDir)
        database =
            Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
                .setDriver(JdbcSQLiteDriver())
                .addMigrations(AppDatabase.MIGRATION_28_29, AppDatabase.MIGRATION_29_30, AppDatabase.MIGRATION_30_31)
                .allowMainThreadQueries()
                .build()
        dao = database.tokenStatsDao()
        settings = FakeSettingsStore()
        TokenStatsQueryService.databaseProvider = { database }
        TokenStatsQueryService.legacyPricesProvider = { emptyMap() }
        TokenStatsQueryService.queryDispatcher = UnconfinedTestDispatcher()
        // 阶段 5 删除走 TokenStatsResetCoordinator（spool replay 用注入的数据库）
        TokenStatsResetCoordinator.daoProvider = { dao }
        TokenStatsLedger.databaseProvider = { database }
    }

    @After
    fun tearDown() {
        TokenStatsQueryService.databaseProvider = null
        TokenStatsQueryService.legacyPricesProvider = null
        TokenStatsQueryService.queryDispatcher = Dispatchers.IO
        TokenStatsResetCoordinator.daoProvider = null
        TokenStatsLedger.databaseProvider = null
        database.close()
        Dispatchers.resetMain()
    }

    /** 清空 `Context.apiDataStore` 委托缓存与 ApiPreferences INSTANCE（跨测试隔离）。 */
    private fun clearApiPreferencesSingletons() {
        val facade = Class.forName("com.ai.assistance.operit.data.preferences.ApiPreferencesKt")
        val delegateField = facade.getDeclaredField("apiDataStore\$delegate")
        delegateField.isAccessible = true
        val delegate = delegateField.get(null)
        val instanceField =
            delegate.javaClass.getDeclaredField("INSTANCE").apply { isAccessible = true }
        instanceField.set(delegate, null)
        val prefsInstanceField =
            ApiPreferences::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        prefsInstanceField.set(null, null)
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

    /**
     * 直接以 DataStore 的 protobuf 文件格式（datastore/<name>.preferences_pb）写入
     * legacy 累计键（token_input_/token_cached_input_/token_output_<provider_model>）。
     * Windows JVM 上同一 DataStore 文件每测试只能经 DataStore 写入一次（见
     * ApiPreferencesResetFailureTest 类注释：二次写入的原子替换 rename 失败），因此
     * 种子键绕过 DataStore 写管线、用库自身 PreferencesProto 编码直接落盘，把唯一
     * 一次真实 DataStore 写入留给被测删除流程（生产路径不受该测试环境约束）。
     * 键名/文件路径与 `preferencesDataStore(name = "api_settings")` 约定一致。
     */
    private fun seedLegacyTokenCountsFile(
        filesDir: File,
        providerModel: String,
        input: Long,
        cached: Long,
        output: Long,
    ) {
        val datastoreDir = File(filesDir, "datastore")
        check(datastoreDir.mkdirs() || datastoreDir.isDirectory)
        val mapBuilder = androidx.datastore.preferences.PreferencesProto.PreferenceMap.newBuilder()
        fun putLong(key: String, value: Long) {
            mapBuilder.putPreferences(
                key,
                androidx.datastore.preferences.PreferencesProto.Value.newBuilder()
                    .setLong(value)
                    .build(),
            )
        }
        putLong("token_input_${providerModel.replace(":", "_")}", input)
        putLong("token_cached_input_${providerModel.replace(":", "_")}", cached)
        putLong("token_output_${providerModel.replace(":", "_")}", output)
        File(datastoreDir, "api_settings.preferences_pb").outputStream().use { output ->
            mapBuilder.build().writeTo(output)
        }
    }

    private fun constructViewModel(): TokenUsageStatisticsViewModel =
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
            // P1 关键链路：readiness 门控注入 no-op（不触碰真实 spool/协调器）
            readiness = TokenStatsReadiness { true },
        )

    private fun newViewModel(): TokenUsageStatisticsViewModel =
        constructViewModel().also { it.loadForEntry() }

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

    private fun awaitActivity(viewModel: TokenUsageStatisticsViewModel) {
        val deadline = System.currentTimeMillis() + 15_000
        while (viewModel.state.value.activity.loading) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for activity statistics")
            }
            Thread.sleep(10)
        }
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

    private suspend fun seedBaseline(identityId: String, requestCount: Long = 3L) {
        dao.upsertBaseline(
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
    fun `construction does not load and each route entry loads once`() {
        val viewModel = constructViewModel()
        Thread.sleep(50)
        assertEquals(0L, viewModel.state.value.refreshVersion)

        viewModel.loadForEntry()
        awaitRefresh(viewModel, 0)
        assertEquals(1L, viewModel.state.value.refreshVersion)

        viewModel.loadForEntry()
        awaitRefresh(viewModel, 1)
        assertEquals(2L, viewModel.state.value.refreshVersion)
    }

    @Test
    fun `initial fallback picks first preset with data and persists it as auto`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", configId = "cfg-a")
            // 事件只在 6 天前：5h/12h/24h 空，7d 有数据
            dao.insertEvent(event("e1", "id-1", nowMs - 6L * 24 * 3600_000L + 12 * 3600_000L))
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)
        assertEquals(TokenStatsPreset.LAST_7D, viewModel.state.value.selectedPreset)
        assertFalse(viewModel.state.value.userChoseTime)
        // 自动回退结果持久化为 manual=false，下次进入仍可按最新数据重新探测。
        assertNotNull(settings.savedSelection)
        assertEquals(TokenStatsPreset.LAST_7D, settings.savedSelection!!.preset)
        assertFalse(settings.savedManual)
        assertEquals(1, settings.timeSelectionSaveCount)
    }

    @Test
    fun `second viewmodel rechecks persisted automatic fallback on entry`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", configId = "cfg-a")
            // 事件只在 6 天前：5h/12h/24h 空，7d 有数据
            dao.insertEvent(event("e1", "id-1", nowMs - 6L * 24 * 3600_000L + 12 * 3600_000L))
        }
        val vm1 = newViewModel()
        awaitRefresh(vm1, 0)
        assertEquals(TokenStatsPreset.LAST_7D, vm1.state.value.selectedPreset)
        assertEquals(1, settings.timeSelectionSaveCount)

        // 清空事件后再次进入：全部范围为空，自动回退应回到最短 5h。
        runBlocking { dao.deleteAllEvents() }
        val vm2 = newViewModel()
        awaitRefresh(vm2, 0)
        assertEquals(TokenStatsPreset.LAST_5H, vm2.state.value.selectedPreset)
        assertFalse(vm2.state.value.userChoseTime)
        assertEquals(2, settings.timeSelectionSaveCount)
        assertEquals(0L, vm2.state.value.range?.eventCount)
    }

    @Test
    fun `manual rolling selection with data stays locked`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", configId = "cfg-a")
            // 24h 内有数据，保留用户手动选择，不缩短到 5h。
            dao.insertEvent(event("e1", "id-1", nowMs - 20 * 3600_000L))
            settings.savedSelection = TokenStatsTimeSelection(TokenStatsPreset.LAST_24H)
            settings.savedManual = true
        }
        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)
        // 手动范围本身有数据 → 不自动跳转
        assertEquals(TokenStatsPreset.LAST_24H, viewModel.state.value.selectedPreset)
        assertTrue(viewModel.state.value.userChoseTime)
        assertEquals(1L, viewModel.state.value.range?.eventCount)
        // 已有选择 → 本次 load 不再保存
        assertEquals(0, settings.timeSelectionSaveCount)
    }

    @Test
    fun `empty manual rolling selection expands on entry`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", configId = "cfg-a")
            // 手选 24h 无数据，但 7d 有数据，进入页面时自动扩展。
            dao.insertEvent(event("e1", "id-1", nowMs - 30 * 3600_000L))
            settings.savedSelection = TokenStatsTimeSelection(TokenStatsPreset.LAST_24H)
            settings.savedManual = true
        }

        val viewModel = newViewModel()
        awaitRefresh(viewModel, 0)

        assertEquals(TokenStatsPreset.LAST_7D, viewModel.state.value.selectedPreset)
        assertFalse(viewModel.state.value.userChoseTime)
        assertEquals(1L, viewModel.state.value.range?.eventCount)
        assertFalse(settings.savedManual)
        assertEquals(1, settings.timeSelectionSaveCount)
    }

    // ==== 自定义范围 ====

    @Test
    fun `custom range rejects invalid and accepts valid bounds`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", configId = "cfg-a")
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
        val tooEarly = java.time.Instant.ofEpochMilli(start)
            .atZone(shanghai)
            .toLocalDate()
            .minusDays(TokenUsageStatisticsViewModel.MAX_CUSTOM_RANGE_DAYS + 1L)
            .atStartOfDay(shanghai)
            .toInstant()
            .toEpochMilli()
        val tooLong =
            viewModel.setCustomRange(
                tooEarly,
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
            seedIdentity("id-1", configId = "cfg-a")
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
            seedIdentity("id-1", configId = "cfg-a")
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

    // ==== P1 关键链路：readiness 门控 ====

    @Test
    fun `first query waits for the readiness gate before loading data`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", configId = "cfg-a")
        }
        val gate = CompletableDeferred<Unit>()
        val readiness = TokenStatsReadiness { _ ->
            gate.await()
            true
        }
        val vm =
            TokenUsageStatisticsViewModel(
                context = context,
                settings = settings,
                zone = shanghai,
                nowMs = { nowMs },
                dao = dao,
                stringResolver = { "msg-$it" },
                dispatcher = Dispatchers.Unconfined,
                readiness = readiness,
            )
        vm.loadForEntry()
        // 门控挂起期间：首次查询不得完成（loading 保持、无结果、无版本推进）
        assertTrue(vm.state.value.loading)
        Thread.sleep(100)
        assertTrue("activity query must remain gated with the main statistics", vm.state.value.activity.loading)
        assertEquals(0L, vm.state.value.refreshVersion)
        assertNull(vm.state.value.range)
        kotlinx.coroutines.runBlocking {
            dao.insertEvent(event("e1", "id-1", nowMs - 3_600_000L))
        }
        // 释放门控：查询执行并携带数据完成
        gate.complete(Unit)
        awaitRefresh(vm, 0)
        awaitActivity(vm)
        assertEquals(1L, vm.state.value.range!!.eventCount)
        assertEquals(1L, vm.state.value.activity.insights.totalRequests)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `not ready within timeout falls back to query then auto-refreshes after readiness`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", configId = "cfg-a")
            dao.insertEvent(event("e1", "id-1", nowMs - 3_600_000L))
        }
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val timeouts = mutableListOf<Long>()
        val gate = CompletableDeferred<Unit>()
        val readiness = TokenStatsReadiness { timeoutMs ->
            timeouts += timeoutMs
            if (calls.getAndIncrement() == 0) {
                // 首次：模拟超时未就绪——VM 必须先按现状查询，不能无限等待
                false
            } else {
                gate.await()
                true
            }
        }
        val vm =
            TokenUsageStatisticsViewModel(
                context = context,
                settings = settings,
                zone = shanghai,
                nowMs = { nowMs },
                dao = dao,
                stringResolver = { "msg-$it" },
                dispatcher = Dispatchers.Unconfined,
                readiness = readiness,
            )
        vm.loadForEntry()
        // 首次未就绪：按现状完成查询（pre-replay 快照可暂时展示，但不无限停留）
        awaitRefresh(vm, 0)
        assertEquals(1L, vm.state.value.range!!.eventCount)
        // 后台等待就绪：完成后自动刷新（第二次查询）
        gate.complete(Unit)
        val versionAfterFallback = vm.state.value.refreshVersion
        awaitRefresh(vm, versionAfterFallback)
        assertEquals(1L, vm.state.value.range!!.eventCount)
        assertFalse(vm.state.value.loading)
        assertEquals("readiness must be awaited exactly twice (gate + refresh)", 2, calls.get())
        assertEquals(listOf(5_000L, 65_000L), timeouts)
    }

    @Test
    fun `initial timeout joins delayed coordinator flight and auto loads once`() {
        val migrationCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val drainCalls = java.util.concurrent.atomic.AtomicInteger(0)
        TokenStatsStartupCoordinator.initializationTimeoutMsForTest = 500L
        TokenStatsStartupCoordinator.ensureMigratedStep = {
            migrationCalls.incrementAndGet()
            kotlinx.coroutines.delay(200)
            true
        }
        TokenStatsStartupCoordinator.consumePendingRestoreStep = { true }
        TokenStatsStartupCoordinator.initialDrainStep = { _, remainingMs ->
            assertTrue(remainingMs in 1L until 500L)
            drainCalls.incrementAndGet()
            true
        }
        try {
            val vm =
                TokenUsageStatisticsViewModel(
                    context = context,
                    settings = settings,
                    zone = shanghai,
                    nowMs = { nowMs },
                    dao = dao,
                    stringResolver = { "msg-$it" },
                    dispatcher = Dispatchers.Unconfined,
                    readiness = TokenStatsStartupCoordinator.readiness(context),
                    readinessInitialWaitMs = 10L,
                    readinessRefreshWaitMs = 600L,
                )
            vm.loadForEntry()

            awaitRefresh(vm, 0)
            val fallbackVersion = vm.state.value.refreshVersion
            awaitRefresh(vm, fallbackVersion)
            Thread.sleep(100)
            assertEquals(fallbackVersion + 1, vm.state.value.refreshVersion)
            assertEquals("both waits must share one initialization", 1, migrationCalls.get())
            assertEquals(1, drainCalls.get())
        } finally {
            TokenStatsStartupCoordinator.ensureMigratedStep = null
            TokenStatsStartupCoordinator.consumePendingRestoreStep = null
            TokenStatsStartupCoordinator.initialDrainStep = null
            TokenStatsStartupCoordinator.initializationTimeoutMsForTest = null
        }
    }

    @Test
    fun `failed background readiness can retry without duplicate refresh tasks`() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val secondAttempt = CompletableDeferred<Unit>()
        val releaseSecondAttempt = CompletableDeferred<Unit>()
        val readiness = TokenStatsReadiness { _ ->
            when (calls.incrementAndGet()) {
                1 -> false // initial gate
                2 -> {
                    secondAttempt.complete(Unit)
                    releaseSecondAttempt.await()
                    false // first full-lifecycle subscription fails
                }
                else -> true
            }
        }
        val vm =
            TokenUsageStatisticsViewModel(
                context = context,
                settings = settings,
                zone = shanghai,
                nowMs = { nowMs },
                dao = dao,
                stringResolver = { "msg-$it" },
                dispatcher = Dispatchers.Unconfined,
                readiness = readiness,
            )
        vm.loadForEntry()
        runBlocking { withTimeout(5_000) { secondAttempt.await() } }

        val initialVersion = vm.state.value.refreshVersion
        vm.load()
        vm.load()
        assertEquals("active readiness subscription must remain single-flight in the VM", 2, calls.get())
        releaseSecondAttempt.complete(Unit)
        awaitRefresh(vm, initialVersion)

        val beforeRetry = vm.state.value.refreshVersion
        vm.load()
        awaitRefresh(vm, beforeRetry)
        Thread.sleep(100)
        assertEquals("successful readiness must trigger exactly one load", beforeRetry + 1, vm.state.value.refreshVersion)
        assertEquals("later load must retry readiness once", 3, calls.get())
    }

    // ==== P1-4：旧 load 不得污染共享 state ====

    @Test
    fun `stale load cannot overwrite newer load result`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", configId = "cfg-a")
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
                readiness = TokenStatsReadiness { true },
            )
        vm.loadForEntry()
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
            seedIdentity("id-1", configId = "cfg-a")
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
                readiness = TokenStatsReadiness { true },
            )
        vm.loadForEntry()
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
            seedIdentity("id-1", configId = "cfg-a")
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

        viewModel.setIncludeLegacy(false)
        assertFalse(viewModel.state.value.includeLegacy)
        assertFalse(settings.savedIncludeLegacy)

        // 手动汇率：合法保存后不再标记估算
        val v3 = viewModel.state.value.refreshVersion
        assertTrue(viewModel.setManualRate(7.35))
        awaitRefresh(viewModel, v3)
        assertEquals(7.35, viewModel.state.value.manualRate, 0.0)
        assertFalse(viewModel.state.value.rateIsEstimated)
        assertEquals(7.35, settings.savedRate, 0.0)

        // 非法汇率：拒绝且不持久化
        assertFalse(viewModel.setManualRate(-1.0))
        assertFalse(viewModel.setManualRate(1e-50))
        assertFalse(viewModel.setManualRate(1e50))
        assertFalse(viewModel.setManualRate(Double.NaN))
        assertFalse(viewModel.setManualRate(Double.POSITIVE_INFINITY))
        assertEquals(7.35, settings.savedRate, 0.0)
    }

    // ==== 价格覆盖与分组操作 ====

    @Test
    fun `price override save updates overrides and negative value fails with message`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", configId = "cfg-a")
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
            seedIdentity("id-1", configId = "cfg-a")
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
            seedIdentity("id-1", configId = "cfg-a")
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

    // ==== 阶段 5：删除（范围 / 模型 / 全部；删除后页面状态刷新一致） ====

    @Test
    fun `delete current range removes in range events only and refreshes state`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", configId = "cfg-a")
            seedIdentity("id-2", configId = "cfg-b")
            dao.insertEvent(event("e-in", "id-1", startedAtMs = nowMs - 3_600_000L))
            dao.insertEvent(event("e-out", "id-1", startedAtMs = nowMs - 7L * 86_400_000L))
            seedBaseline("id-1")
            seedBaseline("id-2")

            val viewModel = newViewModel()
            awaitRefresh(viewModel, 0)
            // 首次回退：5h 内恰有事件 → LAST_5H；currentRange 与查询同界
            assertEquals(TokenStatsPreset.LAST_5H, viewModel.state.value.selectedPreset)
            val range = viewModel.state.value.currentRange
            assertNotNull(range)
            assertTrue(range!!.startMs <= nowMs - 3_600_000L)
            assertTrue(nowMs - 3_600_000L < range.endMs)

            val from = viewModel.state.value.refreshVersion
            viewModel.deleteRangeEvents()
            awaitRefresh(viewModel, from)

            // 删除真实生效：范围内事件消失，范围外保留
            assertNull(dao.getEvent("e-in"))
            assertNotNull(dao.getEvent("e-out"))
            // baseline 绝不因范围删除被触碰
            assertNotNull(dao.getBaseline("id-1"))
            assertNotNull(dao.getBaseline("id-2"))
            // 页面状态刷新一致：生命周期只剩 1 条事件、2 行 baseline；范围空数据
            val lifetime = viewModel.state.value.lifetime!!
            assertEquals(1L, lifetime.eventTotals.requests)
            assertEquals(2L, lifetime.baselineTotals.identityCount)
            assertEquals(0L, viewModel.state.value.range!!.eventCount)
        }
    }

    @Test
    fun `delete display model covers full group members and keeps baseline by choice`() {
        kotlinx.coroutines.runBlocking {
            // 展示组 group-x：跨 provider:model 的两个身份 + 组外同 provider:model 身份
            seedIdentity("x-1", configId = "cfg-a", provider = "OPENAI", model = "gpt-4o", displayModelId = "group-x")
            seedIdentity("x-2", provider = "DEEPSEEK", model = "deepseek-chat", displayModelId = "group-x")
            seedIdentity("y-1", configId = "cfg-b", provider = "OPENAI", model = "gpt-4o", displayModelId = "group-y")
            dao.insertEvent(event("e-x1", "x-1", startedAtMs = nowMs - 3_600_000L))
            dao.insertEvent(event("e-x2", "x-2", startedAtMs = nowMs - 3_600_000L))
            dao.insertEvent(event("e-y1", "y-1", startedAtMs = nowMs - 3_600_000L))
            seedBaseline("x-1")
            seedBaseline("y-1")

            val viewModel = newViewModel()
            awaitRefresh(viewModel, 0)

            // 第二步选择“仅删除事件”：组内全部成员事件删除（含无事件组成员身份影响
            // 由 DAO 全表解析验证），baseline 保留，组外同 provider:model 不受影响
            val from = viewModel.state.value.refreshVersion
            viewModel.deleteDisplayModel("group-x", deleteBaselines = false)
            awaitRefresh(viewModel, from)

            assertNull(dao.getEvent("e-x1"))
            assertNull(dao.getEvent("e-x2"))
            assertNotNull("same provider:model in another group must survive", dao.getEvent("e-y1"))
            assertNotNull(dao.getBaseline("x-1"))
            assertNotNull(dao.getBaseline("y-1"))
            assertEquals(1L, viewModel.state.value.lifetime!!.eventTotals.requests)
        }
    }

    @Test
    fun `delete display model with baseline removes member baselines and refreshes`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("x-1", configId = "cfg-a", provider = "OPENAI", model = "gpt-4o", displayModelId = "group-x")
            seedIdentity("y-1", provider = "DEEPSEEK", model = "deepseek-chat", displayModelId = "group-y")
            dao.insertEvent(event("e-x1", "x-1", startedAtMs = nowMs - 3_600_000L))
            dao.insertEvent(event("e-y1", "y-1", startedAtMs = nowMs - 3_600_000L))
            seedBaseline("x-1")
            seedBaseline("y-1")

            val viewModel = newViewModel()
            awaitRefresh(viewModel, 0)
            val from = viewModel.state.value.refreshVersion
            viewModel.deleteDisplayModel("group-x", deleteBaselines = true)
            awaitRefresh(viewModel, from)

            assertNull(dao.getEvent("e-x1"))
            assertNotNull(dao.getEvent("e-y1"))
            assertNull(dao.getBaseline("x-1"))
            assertNotNull("other group baseline must survive", dao.getBaseline("y-1"))
            assertEquals(1L, viewModel.state.value.lifetime!!.baselineTotals.identityCount)
        }
    }

    @Test
    fun `legacy datastore keys are cleared only when the deleted group contains the legacy identity`() {
        kotlinx.coroutines.runBlocking {
            // 两组同 provider:model：group-x 只含配置身份 cfg-a；group-y 含 legacy 身份
            // （configId=""，旧 DataStore 累计键的 baseline 迁移目标，键按 provider:model 共享）
            seedIdentity("x-1", configId = "cfg-a", provider = "OPENAI", model = "gpt-4o", displayModelId = "group-x")
            seedIdentity("y-legacy", configId = "", provider = "OPENAI", model = "gpt-4o", displayModelId = "group-y")
            seedBaseline("x-1")
            seedBaseline("y-legacy")
            val prefs = ApiPreferences.getInstance(context)
            // Windows JVM 约束（见 ApiPreferencesResetFailureTest 类注释）：DataStore 1.0.0
            // 以 File.renameTo 原子替换，Windows 上目标文件已存在时替换失败——同一文件
            // 每测试只能被 DataStore 写入一次。因此 legacy 键用库自身 PreferencesProto
            // 直接落盘（读断言可用），被测流程的唯一真实 DataStore 写入留给排空
            // （applyLegacyCleanup，P1 闭环：删除事务 → 排空清键 + marker）。
            seedLegacyTokenCountsFile(tempDir, "OPENAI:gpt-4o", input = 100L, cached = 10L, output = 50L)

            val viewModel = newViewModel()
            awaitRefresh(viewModel, 0)

            // 删除只含 cfg-a 的组（baseline=yes）：legacy 身份不在目标组 → 不得清旧键
            val from = viewModel.state.value.refreshVersion
            viewModel.deleteDisplayModel("group-x", deleteBaselines = true)
            awaitRefresh(viewModel, from)

            assertNull("cfg-a baseline must be deleted", dao.getBaseline("x-1"))
            assertNotNull("legacy baseline in other group must survive", dao.getBaseline("y-legacy"))
            assertEquals(
                "legacy DataStore key must survive a non-legacy group deletion",
                100L,
                prefs.getInputTokensForProviderModel("OPENAI:gpt-4o"),
            )
            assertEquals(50L, prefs.getOutputTokensForProviderModel("OPENAI:gpt-4o"))

            // 删除含 legacy 身份的组（baseline=yes）：其 baseline 随组删除、旧键必须清除。
            // 先移除磁盘文件，使排空（applyLegacyCleanup）成为该文件的首次写入
            // （DataStore 内存状态已在上面读断言时缓存，编辑仍基于含键的状态）。
            check(File(File(tempDir, "datastore"), "api_settings.preferences_pb").delete())
            val from2 = viewModel.state.value.refreshVersion
            viewModel.deleteDisplayModel("group-y", deleteBaselines = true)
            awaitRefresh(viewModel, from2)

            assertNull(dao.getBaseline("y-legacy"))
            assertEquals(
                "legacy DataStore key must be cleared with its baseline",
                0L,
                prefs.getInputTokensForProviderModel("OPENAI:gpt-4o"),
            )
            assertEquals(0L, prefs.getOutputTokensForProviderModel("OPENAI:gpt-4o"))
        }
    }

    @Test
    fun `delete all events only keeps baseline and refreshes`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", configId = "cfg-a")
            seedIdentity("id-2", configId = "cfg-b")
            dao.insertEvent(event("e-1", "id-1", startedAtMs = nowMs - 3_600_000L))
            dao.insertEvent(event("e-2", "id-2", startedAtMs = nowMs - 3_600_000L))
            seedBaseline("id-1")
            seedBaseline("id-2")

            val viewModel = newViewModel()
            awaitRefresh(viewModel, 0)
            val from = viewModel.state.value.refreshVersion
            viewModel.deleteAllStatistics(deleteBaselines = false)
            awaitRefresh(viewModel, from)

            assertEquals(0, dao.countEvents())
            assertEquals("baseline must survive", 2, dao.countBaselines())
            val lifetime = viewModel.state.value.lifetime!!
            assertEquals(0L, lifetime.eventTotals.requests)
            assertEquals(2L, lifetime.baselineTotals.identityCount)
            assertEquals(0L, viewModel.state.value.range!!.eventCount)
        }
    }

    @Test
    fun `delete all with baseline clears legacy keys and refreshes`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", configId = "cfg-a")
            seedIdentity("id-2", configId = "cfg-b")
            dao.insertEvent(event("e-1", "id-1", startedAtMs = nowMs - 3_600_000L))
            dao.insertEvent(event("e-2", "id-2", startedAtMs = nowMs - 3_600_000L))
            seedBaseline("id-1")
            seedBaseline("id-2")

            val viewModel = newViewModel()
            awaitRefresh(viewModel, 0)
            val from = viewModel.state.value.refreshVersion
            viewModel.deleteAllStatistics(deleteBaselines = true)
            awaitRefresh(viewModel, from)

            assertEquals(0, dao.countEvents())
            assertEquals(0, dao.countBaselines())
            val lifetime = viewModel.state.value.lifetime!!
            assertEquals(0L, lifetime.eventTotals.requests)
            assertEquals(0L, lifetime.baselineTotals.identityCount)
        }
    }

    @Test
    fun `partial reset failure still refreshes committed Room deletion`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", configId = "cfg-a")
            dao.insertEvent(event("e-1", "id-1", startedAtMs = nowMs - 3_600_000L))
            seedBaseline("id-1")
        }
        val viewModel =
            TokenUsageStatisticsViewModel(
                context = context,
                settings = settings,
                zone = shanghai,
                nowMs = { nowMs },
                dao = dao,
                stringResolver = { "msg-$it" },
                dispatcher = Dispatchers.Unconfined,
                readiness = TokenStatsReadiness { true },
                resetAllWithBaselines = {
                    dao.resetAllStatisticsTx()
                    false // Simulate a legacy DataStore outbox drain failure after Room commits.
                },
            )
        viewModel.loadForEntry()
        awaitRefresh(viewModel, 0)
        awaitActivity(viewModel)
        assertEquals(1L, viewModel.state.value.activity.insights.totalRequests)

        val from = viewModel.state.value.refreshVersion
        viewModel.deleteAllStatistics(deleteBaselines = true)
        awaitActionMessage(viewModel)
        awaitRefresh(viewModel, from)
        awaitActivity(viewModel)

        assertTrue(viewModel.actionMessage.value!!.isError)
        kotlinx.coroutines.runBlocking {
            assertEquals(0, dao.countEvents())
            assertEquals(0, dao.countBaselines())
        }
        assertEquals(0L, viewModel.state.value.lifetime!!.eventTotals.requests)
        assertEquals(0L, viewModel.state.value.activity.insights.totalRequests)
    }

    @Test
    fun `delete failures surface error message and keep data intact`() {
        kotlinx.coroutines.runBlocking {
            seedIdentity("id-1", configId = "cfg-a")
            dao.insertEvent(event("e-1", "id-1", startedAtMs = nowMs - 3_600_000L))
            seedBaseline("id-1")

            val viewModel = newViewModel()
            awaitRefresh(viewModel, 0)
            // 数据库不可用：三种删除都应报错而不是假装成功
            val failures =
                listOf(
                    { viewModel.deleteRangeEvents() },
                    { viewModel.deleteDisplayModel("id-1", deleteBaselines = false) },
                    { viewModel.deleteAllStatistics(deleteBaselines = false) },
                )
            for (action in failures) {
                TokenStatsResetCoordinator.daoProvider =
                    { _: Context -> throw RuntimeException("db down") }
                try {
                    action()
                    awaitActionMessage(viewModel)
                    assertTrue("error message expected", viewModel.actionMessage.value!!.isError)
                    viewModel.consumeActionMessage()
                } finally {
                    TokenStatsResetCoordinator.daoProvider = { dao }
                }
            }
            // 失败不产生任何删除
            assertEquals(1, dao.countEvents())
            assertEquals(1, dao.countBaselines())
        }
    }
}

/** 内存假实现：验证持久化调用与首次回退语义（无用户选择 = null）。 */
private class FakeSettingsStore : TokenStatsSettingsStore {
    var savedRate: Double = TokenCostCurrency.DEFAULT_USD_TO_CNY_RATE
    var rateEstimated: Boolean = true
    var savedCurrency: PricingCurrency = PricingCurrency.CNY
    var savedMode: TokenStatsCostMode = TokenStatsCostMode.HISTORICAL
    var savedIncludeLegacy: Boolean = true
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

    override suspend fun loadIncludeLegacy(): Boolean = savedIncludeLegacy

    override suspend fun saveIncludeLegacy(include: Boolean) {
        savedIncludeLegacy = include
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
    var savedIncludeLegacy: Boolean = true
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

    override suspend fun loadIncludeLegacy(): Boolean = savedIncludeLegacy

    override suspend fun saveIncludeLegacy(include: Boolean) {
        savedIncludeLegacy = include
    }

    override suspend fun loadTimeSelection(): TokenStatsTimeSelection? = savedSelection

    override suspend fun loadSelectionWasManual(): Boolean = savedManual

    override suspend fun saveTimeSelection(selection: TokenStatsTimeSelection?, manual: Boolean) {
        savedSelection = selection
        savedManual = manual
    }
}
