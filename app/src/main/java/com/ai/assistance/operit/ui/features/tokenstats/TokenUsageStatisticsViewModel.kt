package com.ai.assistance.operit.ui.features.tokenstats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.PriceOverrideScope
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.stats.ApiPreferencesTokenStatsSettingsStore
import com.ai.assistance.operit.data.stats.TokenActivityAggregator
import com.ai.assistance.operit.data.stats.TokenActivityInsights
import com.ai.assistance.operit.data.stats.TokenActivityViewMode
import com.ai.assistance.operit.data.stats.TokenActivityYearData
import com.ai.assistance.operit.data.stats.TokenCostCurrency
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.data.stats.TokenStatStatus
import com.ai.assistance.operit.data.stats.TokenStatsCostMode
import com.ai.assistance.operit.data.stats.TokenStatsDisplayModelBreakdown
import com.ai.assistance.operit.data.stats.TokenStatsGroupModelInfo
import com.ai.assistance.operit.data.stats.TokenStatsLifetimeOverview
import com.ai.assistance.operit.data.stats.TokenStatsPreset
import com.ai.assistance.operit.data.stats.TokenStatsQueryParams
import com.ai.assistance.operit.data.stats.TokenStatsQueryService
import com.ai.assistance.operit.data.stats.TokenStatsRangeData
import com.ai.assistance.operit.data.stats.TokenStatsSettingsManager
import com.ai.assistance.operit.data.stats.TokenStatsSettingsStore
import com.ai.assistance.operit.data.stats.TokenStatsTimeRange
import com.ai.assistance.operit.data.stats.TokenStatsTimeRanges
import com.ai.assistance.operit.data.stats.TokenStatsTimeSelection
import com.ai.assistance.operit.data.stats.TokenStatsPriceOverrideDraft
import com.ai.assistance.operit.data.stats.TokenStatsResetCoordinator
import com.ai.assistance.operit.util.AppLogger
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TokenActivityUiState(
    val loading: Boolean = true,
    val viewMode: TokenActivityViewMode = TokenActivityViewMode.DAILY,
    val recentSelected: Boolean = true,
    val selectedYear: Int = 0,
    val availableYears: List<Int> = emptyList(),
    val yearData: TokenActivityYearData? = null,
    val insights: TokenActivityInsights = TokenActivityInsights(),
)

/** 页面 UI 状态（阶段 4）。 */
data class TokenStatsUiState(
    val loading: Boolean = true,
    val errorMessage: String? = null,
    /**
     * 每次查询完成的单调版本号：UI/测试可用它等待“这次操作触发的查询已落定”
     * （Room 在后台线程恢复协程，loading 标志本身有竞态窗口）。
     */
    val refreshVersion: Long = 0,
    /** 生命周期累计总览（不受筛选影响）。 */
    val lifetime: TokenStatsLifetimeOverview? = null,
    /** 当前时间范围的完整查询结果（受筛选影响）。 */
    val range: TokenStatsRangeData? = null,
    /** 当前时间预设（首次自动回退后为回退结果）。 */
    val selectedPreset: TokenStatsPreset = TokenStatsPreset.LAST_5H,
    /** 自定义范围的显式边界；非 CUSTOM 预设时为 null。 */
    val customRange: TokenStatsTimeRange? = null,
    /**
     * 当前查询实际使用的时间范围（阶段 5 删除入口）：与展示/查询完全同界——
     * CUSTOM 用自定义边界，其余预设用 [TokenStatsTimeRanges.rangeFor] 实时计算。
     * 删除当前范围必须与用户所见范围一致，不能在 UI 侧另行计算。
     */
    val currentRange: TokenStatsTimeRange? = null,
    /** true = 用户手动选择过时间（不再自动回退）。 */
    val userChoseTime: Boolean = false,
    val targetCurrency: PricingCurrency = PricingCurrency.CNY,
    val costMode: TokenStatsCostMode = TokenStatsCostMode.HISTORICAL,
    /** true = 生命周期累计包含迁移的旧版 baseline；关闭只影响展示，不删除数据。 */
    val includeLegacy: Boolean = true,
    val manualRate: Double = TokenCostCurrency.DEFAULT_USD_TO_CNY_RATE,
    /** true = 汇率是默认估算值（用户未设置），界面必须明显标注。 */
    val rateIsEstimated: Boolean = true,
    /** 展示模型筛选；empty = 全部。 */
    val selectedModels: Set<String> = emptySet(),
    /** 业务分类筛选；null = 全部。 */
    val selectedCategories: Set<TokenStatCategory>? = null,
    /** 请求状态筛选；null = 全部。 */
    val selectedStatuses: Set<TokenStatStatus>? = null,
    /**
     * 模型筛选下拉的可选项（P1-5）：与当前范围同时间/分类/状态/口径筛选但
     * **不应用模型筛选**，因此选中某模型后其他模型仍可选。
     */
    val availableDisplayModels: List<TokenStatsDisplayModelBreakdown> = emptyList(),
    /** 已知展示模型 id → 名称（P1-5：被筛选出当前结果但仍选中的模型可显示）。 */
    val knownModelNames: Map<String, String> = emptyMap(),
    /** 全部价格覆盖（供管理区展示）。 */
    val overrides: List<TokenStatPriceOverrideEntity> = emptyList(),
    /**
     * 完整展示分组元数据（阶段 4 P1 修复）：与统计筛选无关的分组成员/合并目标
     * 来源——范围明细只含当前筛选下有事件的身份/分组，分组操作（合并成员、
     * 目标组列表）必须用完整归属，否则无事件成员被漏移、无事件目标组不可选。
     */
    val groupModels: List<TokenStatsGroupModelInfo> = emptyList(),
    /** 全局历史活动；独立于下方时间、模型、分类和状态筛选。 */
    val activity: TokenActivityUiState = TokenActivityUiState(),
)

/** 一次性操作结果消息（Toast）：错误或成功提示，消费后清除。 */
data class TokenStatsActionMessage(
    val text: String,
    val isError: Boolean = false,
)

/**
 * 统计页 ViewModel（阶段 4）。
 *
 * - 时间选择：`settings` 中**从未选择**时，每次进入按 5h→12h→24h→7d→30d
 *   自动回退到最近有数据的预设；用户手动选择后持久化，此后不再自动跳转。
 * - 筛选/币种/费用口径/汇率变更都触发重新查询；查询走
 *   [TokenStatsQueryService]（同事务快照 + IO 线程）。
 * - 依赖注入缝（测试）：[settings] 替换为内存假实现、[dao] 传入测试 Room
 *   DAO、[nowMs]/[zone] 固定时间；生产默认全部使用真实实现。
 */
@android.annotation.SuppressLint("StaticFieldLeak")
class TokenUsageStatisticsViewModel(
    private val context: Context,
    private val settings: TokenStatsSettingsStore = ApiPreferencesTokenStatsSettingsStore(context),
    /** 页面时区（图表时间标签与自定义范围边界），生产 = 系统默认。 */
    val zone: ZoneId = ZoneId.systemDefault(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    dao: TokenStatsDao? = null,
    /**
     * 错误文案解析（测试注入，避免 JVM 上不可 mock 的 Context.getString）；
     * 生产默认走真实 Context。
     */
    private val stringResolver: (Int) -> String = { context.applicationContext.getString(it) },
    /**
     * 协程调度器（测试注入非 Main 调度器，避免 JVM 上 Room 后台恢复与
     * TestMainDispatcher 冲突）；生产默认 = Main.immediate（与 viewModelScope 一致）。
     */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    // 只保存 applicationContext（进程级单例，无泄漏风险；与 CustomEmojiViewModel 同模式）
    private val appContext: Context = context.applicationContext
    private val tag = "TokenUsageStatisticsViewModel"

    private val statsDao: TokenStatsDao =
        dao ?: AppDatabase.getDatabase(appContext).tokenStatsDao()

    private val manager = TokenStatsSettingsManager(statsDao)

    private val _state = MutableStateFlow(TokenStatsUiState())
    val state: StateFlow<TokenStatsUiState> = _state.asStateFlow()

    private val _actionMessage = MutableStateFlow<TokenStatsActionMessage?>(null)
    val actionMessage: StateFlow<TokenStatsActionMessage?> = _actionMessage.asStateFlow()

    /** 丢弃过期加载结果（与 Rainytoken 参考实现同思路的 generation 防竞态）。 */
    private var loadGeneration = 0

    /** 当前加载任务：新一轮 [load] 先取消旧任务，旧任务不得写 state（P1-4）。 */
    private var loadJob: Job? = null

    private var activityLoadJob: Job? = null
    private var activityLoadGeneration = 0

    /** 已知展示模型 id → 最近一次查询所见名称（P1-5，永不清除，只增补）。 */
    private val knownModelNames = mutableMapOf<String, String>()

    init {
        loadForEntry()
    }

    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    // ==== 查询 ====

    fun load() {
        loadInternal(reconsiderAutomaticTime = false)
    }

    /** 进入/返回统计页时重新探测自动时间范围；用户手选范围始终保持不变。 */
    fun loadForEntry() {
        loadActivity()
        loadInternal(reconsiderAutomaticTime = true)
    }

    private fun loadActivity(requestedRecent: Boolean = true, requestedYear: Int? = null) {
        activityLoadJob?.cancel()
        val generation = ++activityLoadGeneration
        _state.update {
            it.copy(
                activity = it.activity.copy(
                    loading = true,
                    recentSelected = requestedRecent,
                    selectedYear = requestedYear ?: it.activity.selectedYear,
                )
            )
        }
        activityLoadJob = viewModelScope.launch(dispatcher) {
            try {
                val records = TokenStatsQueryService.activityRecords(appContext)
                val result = withContext(Dispatchers.Default) {
                    val years = TokenActivityAggregator.availableYears(records, zone, nowMs())
                    val recent = requestedRecent || requestedYear !in years
                    val year = requestedYear?.takeIf { it in years } ?: years.first()
                    ActivityLoadResult(
                        years = years,
                        year = year,
                        recent = recent,
                        data = if (recent) {
                            TokenActivityAggregator.recentData(records, zone, nowMs())
                        } else {
                            TokenActivityAggregator.yearData(records, zone, year, nowMs())
                        },
                        insights = TokenActivityAggregator.insights(records, zone),
                    )
                }
                if (generation != activityLoadGeneration) return@launch
                _state.update {
                    it.copy(
                        activity = it.activity.copy(
                            loading = false,
                            recentSelected = result.recent,
                            selectedYear = result.year,
                            availableYears = result.years,
                            yearData = result.data,
                            insights = result.insights,
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == activityLoadGeneration) {
                    _state.update { it.copy(activity = it.activity.copy(loading = false)) }
                }
                runCatching { AppLogger.e(tag, "Token 活动加载失败", e) }
            }
        }
    }

    fun setActivityViewMode(mode: TokenActivityViewMode) {
        _state.update { it.copy(activity = it.activity.copy(viewMode = mode)) }
    }

    fun setActivityYear(year: Int) {
        val activity = _state.value.activity
        if ((!activity.recentSelected && year == activity.selectedYear) || year !in activity.availableYears) return
        loadActivity(requestedRecent = false, requestedYear = year)
    }

    fun setActivityRecent() {
        if (_state.value.activity.recentSelected) return
        loadActivity(requestedRecent = true)
    }

    private fun loadInternal(reconsiderAutomaticTime: Boolean) {
        loadJob?.cancel()
        val generation = ++loadGeneration
        // 筛选状态同步快照：偏好读取挂起期间用户可能已改筛选并触发新 load，
        // 旧任务一律用本快照构造 params，不混入新状态（P1-4）。
        val filterSnapshot = _state.value
        loadJob = viewModelScope.launch(dispatcher) {
            try {
                // 偏好全部读取为不可变本地快照：任何 _state.update 之前先核对
                // generation，旧 load 即使恢复也不污染共享 state（P1-4）。
                val rateInfo = settings.loadRateWithEstimate()
                val currency = settings.loadTargetCurrency()
                val mode = settings.loadCostMode()
                val includeLegacy = settings.loadIncludeLegacy()
                val savedSelection = settings.loadTimeSelection()
                val selectionWasManual = settings.loadSelectionWasManual()

                val preset: TokenStatsPreset
                val customRange: TokenStatsTimeRange?
                val userChoseTime: Boolean
                val savedRange = savedSelection?.let { selection ->
                    selection.customRangeOrNull()
                        ?: selection.preset.takeIf { it != TokenStatsPreset.CUSTOM }?.let {
                            TokenStatsTimeRanges.rangeFor(it, nowMs(), zone)
                        }
                }
                val emptyManualRollingSelection =
                    reconsiderAutomaticTime &&
                        selectionWasManual &&
                        savedSelection?.preset in TokenStatsPreset.INITIAL_FALLBACK_ORDER &&
                        savedRange != null &&
                        !TokenStatsQueryService.rangeHasEvents(appContext, savedRange)
                val shouldProbeAutomaticRange =
                    reconsiderAutomaticTime &&
                        (savedSelection == null || !selectionWasManual || emptyManualRollingSelection)

                if (savedSelection != null && !shouldProbeAutomaticRange) {
                    // 普通刷新直接复用；手选范围有数据时也保持用户选择。
                    preset = savedSelection.preset
                    customRange = savedSelection.customRangeOrNull()
                    userChoseTime = selectionWasManual
                } else {
                    // 自动模式每次进入重探测；手选滚动范围为空时才扩展到更大窗口。
                    val suggested =
                        TokenStatsQueryService.initialPresetWithData(appContext, zone, nowMs())
                    if (savedSelection?.preset != suggested ||
                        savedSelection.customRangeOrNull() != null ||
                        selectionWasManual
                    ) {
                        settings.saveTimeSelection(TokenStatsTimeSelection(suggested), manual = false)
                    }
                    preset = suggested
                    customRange = null
                    userChoseTime = false
                }

                val params = TokenStatsQueryParams(
                    targetCurrency = currency,
                    manualRate = rateInfo.first,
                    rateIsEstimated = rateInfo.second,
                    mode = mode,
                    displayModelIds = filterSnapshot.selectedModels.ifEmpty { null },
                    categories = filterSnapshot.selectedCategories,
                    statuses = filterSnapshot.selectedStatuses,
                )
                val range: TokenStatsTimeRange? =
                    when {
                        preset == TokenStatsPreset.CUSTOM && customRange != null -> customRange
                        preset == TokenStatsPreset.CUSTOM -> null
                        else -> TokenStatsTimeRanges.rangeFor(preset, nowMs(), zone)
                    }

                if (generation != loadGeneration) return@launch
                _state.update { it.copy(loading = true, errorMessage = null) }

                // 并发查询（P1-5）：范围结果 + 模型菜单可用项同范围但不应用模型
                // 筛选；无模型筛选时可用项直接复用主结果，不产生重复查询。
                val result = coroutineScope {
                    val lifetimeD = async(dispatcher) {
                        TokenStatsQueryService.lifetimeOverview(appContext, params)
                    }
                    val rangeD = async(dispatcher) {
                        range?.let {
                            TokenStatsQueryService.rangeData(appContext, it, params, zone)
                        }
                    }
                    val availableD = async(dispatcher) {
                        if (params.displayModelIds == null || range == null) {
                            null
                        } else {
                            TokenStatsQueryService.rangeData(
                                appContext,
                                range,
                                params.copy(displayModelIds = null),
                                zone,
                            )
                        }
                    }
                    val overridesD = async(dispatcher) { manager.allPriceOverrides() }
                    // 分组元数据与统计筛选无关（P1 修复）：并发读取完整分组归属，
                    // 供分组管理对话框的成员与目标列表使用
                    val groupsD = async(dispatcher) { manager.groupModels() }
                    val rangeData = rangeD.await()
                    QueryLoadResult(
                        lifetime = lifetimeD.await(),
                        range = rangeData,
                        available = availableD.await() ?: rangeData,
                        overrides = overridesD.await(),
                        groups = groupsD.await(),
                    )
                }

                if (generation != loadGeneration) return@launch
                rememberModelNames(result.range?.displayModels.orEmpty())
                rememberModelNames(result.available?.displayModels.orEmpty())
                _state.update {
                    it.copy(
                        loading = false,
                        errorMessage = null,
                        lifetime = result.lifetime,
                        range = result.range,
                        availableDisplayModels = result.available?.displayModels.orEmpty(),
                        knownModelNames = knownModelNames.toMap(),
                        targetCurrency = currency,
                        manualRate = rateInfo.first,
                        rateIsEstimated = rateInfo.second,
                        costMode = mode,
                        includeLegacy = includeLegacy,
                        selectedPreset = preset,
                        customRange = customRange,
                        currentRange = range,
                        userChoseTime = userChoseTime,
                        overrides = result.overrides,
                        groupModels = result.groups,
                        refreshVersion = it.refreshVersion + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == loadGeneration) {
                    _state.update {
                        it.copy(
                            loading = false,
                            errorMessage = stringResolver(R.string.token_stats_load_failed),
                        )
                    }
                }
                runCatching { AppLogger.e(tag, "统计页加载失败", e) }
            }
        }
    }

    /** 记录最近一次查询所见模型名（供被筛选出当前结果但仍选中的模型显示）。 */
    private fun rememberModelNames(models: List<TokenStatsDisplayModelBreakdown>) {
        models.forEach { knownModelNames[it.displayModelId] = it.displayName }
    }

    // ==== 时间选择 ====

    /** 用户手动选择预设：持久化（manual=true）后锁定（不再自动回退）。 */
    fun selectPreset(preset: TokenStatsPreset) {
        if (preset == TokenStatsPreset.CUSTOM) return
        viewModelScope.launch(dispatcher) {
            settings.saveTimeSelection(TokenStatsTimeSelection(preset), manual = true)
            load()
        }
    }

    /**
     * 设置自定义范围（半开区间 [startMs, endMs)）。
     * 校验：end > start 且时长不超过 [MAX_CUSTOM_RANGE_DAYS] 天；
     * 非法时不持久化、不触发查询，返回 false 并由 [actionMessage] 说明原因。
     */
    fun setCustomRange(startMs: Long, endMs: Long): Boolean {
        if (endMs <= startMs) {
            _actionMessage.value =
                TokenStatsActionMessage(
                    text = stringResolver(R.string.token_stats_custom_range_invalid),
                    isError = true,
                )
            return false
        }
        val range = TokenStatsTimeRanges.customRange(startMs, endMs)
        if (range.durationMs > MAX_CUSTOM_RANGE_DAYS * TokenStatsTimeRanges.DAY_MS) {
            _actionMessage.value =
                TokenStatsActionMessage(
                    text = stringResolver(R.string.token_stats_custom_range_too_long),
                    isError = true,
                )
            return false
        }
        viewModelScope.launch(dispatcher) {
            settings.saveTimeSelection(
                TokenStatsTimeSelection(
                    preset = TokenStatsPreset.CUSTOM,
                    customStartMs = range.startMs,
                    customEndMs = range.endMs,
                ),
                manual = true,
            )
            load()
        }
        return true
    }

    // ==== 筛选 ====

    fun toggleModel(displayModelId: String) {
        _state.update { state ->
            val newSet = state.selectedModels.toMutableSet()
            if (!newSet.add(displayModelId)) newSet.remove(displayModelId)
            state.copy(selectedModels = newSet)
        }
        load()
    }

    fun selectAllModels() {
        _state.update { it.copy(selectedModels = emptySet()) }
        load()
    }

    fun toggleCategory(category: TokenStatCategory) {
        _state.update { state ->
            val current = state.selectedCategories
            val newSet = (current?.toMutableSet() ?: mutableSetOf())
            if (!newSet.add(category)) newSet.remove(category)
            state.copy(selectedCategories = newSet.ifEmpty { null })
        }
        load()
    }

    fun toggleStatus(status: TokenStatStatus) {
        _state.update { state ->
            val current = state.selectedStatuses
            val newSet = (current?.toMutableSet() ?: mutableSetOf())
            if (!newSet.add(status)) newSet.remove(status)
            state.copy(selectedStatuses = newSet.ifEmpty { null })
        }
        load()
    }

    /** “全部分类”：一次置空并只触发一次查询（P2，避免逐项 toggle 的多次 load）。 */
    fun clearCategories() {
        _state.update { it.copy(selectedCategories = null) }
        load()
    }

    /** “全部状态”：一次置空并只触发一次查询（P2，避免逐项 toggle 的多次 load）。 */
    fun clearStatuses() {
        _state.update { it.copy(selectedStatuses = null) }
        load()
    }

    // ==== 口径/币种/汇率 ====

    fun setIncludeLegacy(include: Boolean) {
        viewModelScope.launch(dispatcher) {
            settings.saveIncludeLegacy(include)
            _state.update { it.copy(includeLegacy = include) }
        }
    }

    fun setCostMode(mode: TokenStatsCostMode) {
        viewModelScope.launch(dispatcher) {
            settings.saveCostMode(mode)
            load()
        }
    }

    fun setTargetCurrency(currency: PricingCurrency) {
        viewModelScope.launch(dispatcher) {
            settings.saveTargetCurrency(currency)
            load()
        }
    }

    /** 手动汇率：非正或非有限值拒绝（不改持久化、不重查），返回 false。 */
    fun setManualRate(rate: Double): Boolean {
        if (!rate.isFinite() || rate <= 0.0) return false
        viewModelScope.launch(dispatcher) {
            settings.saveRate(rate)
            load()
        }
        return true
    }

    // ==== 价格覆盖 ====

    fun upsertPriceOverride(draft: TokenStatsPriceOverrideDraft) {
        viewModelScope.launch(dispatcher) {
            runCatching { manager.upsertPriceOverride(draft) }
                .onSuccess { load() }
                .onFailure { e ->
                    _actionMessage.value =
                        TokenStatsActionMessage(
                            text = stringResolver(R.string.token_stats_pricing_save_failed),
                            isError = true,
                        )
                    runCatching { AppLogger.e(tag, "保存价格覆盖失败", e) }
                }
        }
    }

    /** 编辑已有价格覆盖：业务键只读，仅更新价格/币种/计费方式（P1-7）。 */
    fun updatePriceOverride(existing: TokenStatPriceOverrideEntity, draft: TokenStatsPriceOverrideDraft) {
        viewModelScope.launch(dispatcher) {
            runCatching { manager.updatePriceOverride(existing, draft) }
                .onSuccess { load() }
                .onFailure { e ->
                    _actionMessage.value =
                        TokenStatsActionMessage(
                            text = stringResolver(R.string.token_stats_pricing_save_failed),
                            isError = true,
                        )
                    runCatching { AppLogger.e(tag, "更新价格覆盖失败", e) }
                }
        }
    }

    fun deletePriceOverride(
        scope: PriceOverrideScope,
        provider: String,
        model: String,
        configId: String?,
    ) {
        viewModelScope.launch(dispatcher) {
            runCatching { manager.deletePriceOverride(scope, provider, model, configId) }
                .onSuccess { load() }
                .onFailure { e ->
                    _actionMessage.value =
                        TokenStatsActionMessage(
                            text = stringResolver(R.string.token_stats_pricing_delete_failed),
                            isError = true,
                        )
                    runCatching { AppLogger.e(tag, "删除价格覆盖失败", e) }
                }
        }
    }

    // ==== 分组 / 别名 ====

    fun renameDisplayGroup(displayModelId: String, displayName: String) {
        viewModelScope.launch(dispatcher) {
            runCatching { manager.renameDisplayGroup(displayModelId, displayName) }
                .onSuccess { load() }
                .onFailure { e ->
                    _actionMessage.value =
                        TokenStatsActionMessage(
                            text = stringResolver(R.string.token_stats_group_rename_failed),
                            isError = true,
                        )
                    runCatching { AppLogger.e(tag, "重命名分组失败", e) }
                }
        }
    }

    /** 新建分组并把指定身份移入；成功返回新分组 id，失败返回 null。 */
    fun createGroupAndMerge(groupName: String, identityIds: List<String>) {
        viewModelScope.launch(dispatcher) {
            runCatching { manager.createGroupAndMove(groupName, identityIds) }
                .onSuccess { load() }
                .onFailure { e ->
                    _actionMessage.value =
                        TokenStatsActionMessage(
                            text = stringResolver(R.string.token_stats_group_create_failed),
                            isError = true,
                        )
                    runCatching { AppLogger.e(tag, "创建分组失败", e) }
                }
        }
    }

    /** 把指定身份合并到已有分组。 */
    fun mergeIntoGroup(identityIds: List<String>, targetDisplayModelId: String) {
        viewModelScope.launch(dispatcher) {
            runCatching { manager.moveIdentitiesToGroup(identityIds, targetDisplayModelId) }
                .onSuccess { load() }
                .onFailure { e ->
                    _actionMessage.value =
                        TokenStatsActionMessage(
                            text = stringResolver(R.string.token_stats_group_merge_failed),
                            isError = true,
                        )
                    runCatching { AppLogger.e(tag, "合并分组失败", e) }
                }
        }
    }

    /** 恢复默认规范分组：组内每个身份按其自身模型名归回默认组。 */
    fun restoreDefaultGroup(displayModelId: String) {
        viewModelScope.launch(dispatcher) {
            runCatching { manager.restoreDefaultGroups(displayModelId) }
                .onSuccess { load() }
                .onFailure { e ->
                    _actionMessage.value =
                        TokenStatsActionMessage(
                            text = stringResolver(R.string.token_stats_group_restore_failed),
                            isError = true,
                        )
                    runCatching { AppLogger.e(tag, "恢复默认分组失败", e) }
                }
        }
    }

    // ==== 阶段 5：删除（范围/模型/全部，危险操作由 UI 两步确认） ====
    // 删除后统一 load() 全量重查：生命周期、范围、图表与模型明细全部刷新，
    // 不留任何缓存旧数据。删除语义（baseline 只随“全部/模型 + 用户确认”删除）：
    // - 范围删除：只删有时间戳的事件（RANGE tombstone），绝不触碰 baseline；
    // - 模型删除：完整展示分组（identity 全表解析成员，不依赖当前筛选），
    //   baseline 是否删除由 UI 第二步确认；确认删除时经 outbox 清理旧 DataStore
    //   累计键（否则下次启动迁移会按旧快照把已删 baseline 重新导入）；
    // - 全部删除：FULL tombstone + 全部事件；baseline 是否删除由 UI 第二步
    //   确认，确认时走 resetAllProviderModelTokenCounts（Room 先删 + ALL cleanup
    //   operation 排空旧计数，保持已确认语义）。
    // 所有删 baseline 的路径都满足 P1 闭环：删除事务是唯一线性化点，operation
    // 持久化在同一事务，DataStore 清理在事务外排空且 marker 幂等。

    /**
     * 删除当前时间范围的事件（[TokenStatsUiState.currentRange]，与显示同界）。
     * 只删事件，绝不删除 baseline；失败时通过 [actionMessage] 提示。
     */
    fun deleteRangeEvents() {
        val range = _state.value.currentRange ?: return
        viewModelScope.launch(dispatcher) {
            runCatching {
                TokenStatsResetCoordinator.deleteEventsInRange(appContext, range.startMs, range.endMs)
            }.onSuccess {
                loadActivity()
                load()
            }.onFailure { e ->
                _actionMessage.value =
                    TokenStatsActionMessage(
                        text = stringResolver(R.string.token_stats_delete_range_failed),
                        isError = true,
                    )
                runCatching { AppLogger.e(tag, "删除时间范围统计失败", e) }
            }
        }
    }

    /**
     * 删除指定展示分组的全部事件（完整组成员，DAO 事务内从 identity 全表解析）。
     * [deleteBaselines] 为 true 时同时删除该组成员的 baseline，并清理这些成员
     * 中**确实对应 legacy 身份**（configId 为空串）的 provider:model 旧 DataStore
     * 累计键（防迁移重导复活）；为 false 时 baseline 与旧键一律保留。身份行/分组/
     * 价格覆盖不删除。
     *
     * P1 闭环：成员解析、tombstone、删除与 cleanup operation 持久化全部在 DAO
     * **同一事务**内线性化（不再 VM 事务外预读），事务提交后由
     * [TokenStatsResetCoordinator] 立即排空 DataStore 累计键（marker 幂等，
     * 失败保持 PENDING 由下次启动重试并向上报错）。
     */
    fun deleteDisplayModel(displayModelId: String, deleteBaselines: Boolean) {
        viewModelScope.launch(dispatcher) {
            try {
                TokenStatsResetCoordinator.deleteDisplayModel(appContext, displayModelId, deleteBaselines)
                loadActivity()
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionMessage.value =
                    TokenStatsActionMessage(
                        text = stringResolver(R.string.token_stats_delete_model_failed),
                        isError = true,
                    )
                runCatching { AppLogger.e(tag, "删除模型统计失败", e) }
            }
        }
    }

    /**
     * 删除全部统计事件。 [deleteBaselines] 为 true 时走既有
     * [ApiPreferences.resetAllProviderModelTokenCounts]（旧 DataStore 累计键 +
     * 新账本事件与 baseline 一并清空）；为 false 时只删新账本事件，baseline 与
     * 旧累计键保留。失败时通过 [actionMessage] 提示。
     */
    fun deleteAllStatistics(deleteBaselines: Boolean) {
        viewModelScope.launch(dispatcher) {
            try {
                val ok =
                    if (deleteBaselines) {
                        ApiPreferences.getInstance(appContext).resetAllProviderModelTokenCounts()
                    } else {
                        TokenStatsResetCoordinator.deleteAllEvents(appContext, deleteBaselines = false)
                        true
                    }
                if (ok) {
                    loadActivity()
                    load()
                } else {
                    _actionMessage.value =
                        TokenStatsActionMessage(
                            text = stringResolver(R.string.settings_token_stats_reset_failed),
                            isError = true,
                        )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionMessage.value =
                    TokenStatsActionMessage(
                        text = stringResolver(R.string.token_stats_delete_all_failed),
                        isError = true,
                    )
                runCatching { AppLogger.e(tag, "删除全部统计失败", e) }
            }
        }
    }

    /**
     * 生产构造（P1-3）：只持有 applicationContext；VM 由路由级 ViewModelStore
     * 管理（AppContent 按 screenKey 提供 owner）——配置变化保留实例，
     * 路由出栈/替换/清栈时 store.clear() 触发 onCleared，viewModelScope
     * 取消，正在进行的 load 一并取消。
     */
    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext: Context = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TokenUsageStatisticsViewModel(appContext) as T
    }

    companion object {
        /** 自定义范围时长上限（天）：与聚合器 10k 桶上限留出余量。 */
        const val MAX_CUSTOM_RANGE_DAYS = 3 * 366L
    }
}

/** 一次 load 的并发查询结果（P1-5）：模型菜单可用项可能复用主范围结果。 */
private data class QueryLoadResult(
    val lifetime: TokenStatsLifetimeOverview,
    val range: TokenStatsRangeData?,
    val available: TokenStatsRangeData?,
    val overrides: List<TokenStatPriceOverrideEntity>,
    val groups: List<TokenStatsGroupModelInfo>,
)

private data class ActivityLoadResult(
    val years: List<Int>,
    val year: Int,
    val recent: Boolean,
    val data: TokenActivityYearData,
    val insights: TokenActivityInsights,
)

/** 保存范围时用：无效自定义边界返回 null（防御损坏状态）。 */
internal fun TokenStatsTimeSelection.customRangeOrNull(): TokenStatsTimeRange? {
    if (preset != TokenStatsPreset.CUSTOM) return null
    val start = customStartMs ?: return null
    val end = customEndMs ?: return null
    if (end <= start) return null
    return TokenStatsTimeRanges.customRange(start, end)
}
