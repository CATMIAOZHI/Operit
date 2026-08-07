package com.ai.assistance.operit.ui.features.tokenstats

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.PriceOverrideScope
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import com.ai.assistance.operit.data.stats.TokenStatsDisplayModelBreakdown
import com.ai.assistance.operit.data.stats.TokenStatsGroupModelInfo
import com.ai.assistance.operit.data.stats.TokenStatsRangeData
import com.ai.assistance.operit.ui.components.CustomScaffold
import java.time.ZoneId

/** 性能卡指标切换。 */
internal enum class PerfMetric { TTFT, GENERATION }

/**
 * Token 统计完整页面（阶段 4）。
 * 沿用 Operit 设置入口与页面框架（Settings → Token使用统计），
 * 升级旧累计页面为账本统计：生命周期总览 + 时间/模型/分类/状态筛选 +
 * 四张图表卡 + 模型明细 + 汇率/币种/价格覆盖/分组管理设置。
 */
@Composable
fun TokenUsageStatisticsScreen(onBackPressed: () -> Unit) {
    val context = LocalContext.current
    // P1-3：VM 由路由级 ViewModelStore 管理（AppContent 为该 route 提供
    // LocalViewModelStoreOwner，键 = screenKey）——配置变化保留实例，
    // 路由出栈/替换/清栈时 store.clear() 触发 onCleared，viewModelScope
    // 取消；Factory 只持有 applicationContext。
    val viewModel: TokenUsageStatisticsViewModel =
        viewModel(factory = TokenUsageStatisticsViewModel.Factory(context))
    val state by viewModel.state.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()

    // 瞬态 UI 状态：可存 rememberSaveable 的在配置变化后保留（P1-3）；
    // 筛选已在 VM state 中，天然跨配置变化保留。
    var showCustomRange by rememberSaveable { mutableStateOf(false) }
    var showResetAllDialog by rememberSaveable { mutableStateOf(false) }
    var resetModel by remember { mutableStateOf<TokenStatsDisplayModelBreakdown?>(null) }
    var pricingTarget by remember { mutableStateOf<PricingTarget?>(null) }
    var groupTarget by remember { mutableStateOf<TokenStatsDisplayModelBreakdown?>(null) }
    var perfMetric by rememberSaveable { mutableStateOf(PerfMetric.TTFT) }

    LaunchedEffect(actionMessage) {
        actionMessage?.let { message ->
            Toast.makeText(context, message.text, Toast.LENGTH_SHORT).show()
            viewModel.consumeActionMessage()
        }
    }

    TokenStatsColorsProvider {
        CustomScaffold(
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showResetAllDialog = true },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = stringResource(id = R.string.settings_reset_all_counts),
                    )
                }
            },
        ) { paddingValues ->
            val content: @Composable () -> Unit = {
                when {
                    state.loading && (state.range == null || state.lifetime == null) -> {
                        LoadingState()
                    }
                    state.errorMessage != null && state.range == null -> {
                        ErrorState(
                            message = state.errorMessage.orEmpty(),
                            onRetry = viewModel::load,
                        )
                    }
                    else -> {
                        TokenStatsPageContent(
                            state = state,
                            viewModel = viewModel,
                            zone = viewModel.zone,
                            perfMetric = perfMetric,
                            onTogglePerfMetric = { perfMetric = it },
                            onCustomRange = { showCustomRange = true },
                            onResetModel = { resetModel = it },
                            onEditPricing = { pricingTarget = PricingTarget.Edit(it) },
                            onAddPricing = { pricingTarget = PricingTarget.New },
                            onGroupManage = { groupTarget = it },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                content()
            }
        }
    }

    if (showCustomRange) {
        CustomRangeDialog(
            zone = viewModel.zone,
            onConfirm = { start, end -> viewModel.setCustomRange(start, end) },
            onDismiss = { showCustomRange = false },
        )
    }

    pricingTarget?.let { target ->
        PriceOverrideDialog(
            existing = (target as? PricingTarget.Edit)?.override,
            onSave = { draft ->
                // P1-7：编辑走键校验入口（业务键只读），新增走 upsert
                val existingOverride = (target as? PricingTarget.Edit)?.override
                if (existingOverride == null) {
                    viewModel.upsertPriceOverride(draft)
                } else {
                    viewModel.updatePriceOverride(existingOverride, draft)
                }
            },
            onDelete = (target as? PricingTarget.Edit)?.let { edit ->
                {
                    val override = edit.override
                    PriceOverrideScope.fromNameOrNull(override.scope)?.let { scope ->
                        viewModel.deletePriceOverride(
                            scope = scope,
                            provider = override.provider,
                            model = override.model,
                            configId = override.configId,
                        )
                    }
                }
            },
            onDismiss = { pricingTarget = null },
        )
    }

    groupTarget?.let { model ->
        // P1 修复：成员与目标来自独立于统计筛选的完整分组元数据（state.groupModels，
        // 全量身份/展示模型表）——范围明细只含当前筛选下有事件的身份，直接用它
        // 做合并会把无事件成员漏掉；无事件的目标组也不可选出。
        val group =
            state.groupModels.firstOrNull { it.displayModelId == model.displayModelId }
                ?: TokenStatsGroupModelInfo(
                    displayModelId = model.displayModelId,
                    displayName = model.displayName,
                    memberIdentityIds = model.identities.map { it.identityId },
                )
        GroupManageDialog(
            groupInfo = group,
            otherGroups = state.groupModels.filter { it.displayModelId != group.displayModelId },
            onRename = { name -> viewModel.renameDisplayGroup(group.displayModelId, name) },
            onCreateAndMerge = { name ->
                viewModel.createGroupAndMerge(name, group.memberIdentityIds)
            },
            onMergeInto = { targetId ->
                viewModel.mergeIntoGroup(group.memberIdentityIds, targetId)
            },
            onRestoreDefault = { viewModel.restoreDefaultGroup(group.displayModelId) },
            onDismiss = { groupTarget = null },
        )
    }

    if (showResetAllDialog) {
        val resetFailedMessage = stringResource(id = R.string.settings_token_stats_reset_failed)
        AlertDialog(
            onDismissRequest = { showResetAllDialog = false },
            title = { Text(stringResource(R.string.settings_reset_confirmation)) },
            text = { Text(stringResource(R.string.settings_reset_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetAllStatistics { error ->
                            if (error != null) {
                                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            }
                        }
                        showResetAllDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.settings_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetAllDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    resetModel?.let { model ->
        AlertDialog(
            onDismissRequest = { resetModel = null },
            title = { Text(stringResource(R.string.settings_reset_model_confirmation)) },
            text = { Text(stringResource(R.string.settings_reset_model_warning, model.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetDisplayModel(model.displayModelId) { error ->
                            if (error != null) {
                                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            }
                        }
                        resetModel = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.settings_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetModel = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

private sealed interface PricingTarget {
    data object New : PricingTarget
    data class Edit(val override: TokenStatPriceOverrideEntity) : PricingTarget
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.token_stats_retry))
            }
        }
    }
}

@Composable
private fun TokenStatsPageContent(
    state: TokenStatsUiState,
    viewModel: TokenUsageStatisticsViewModel,
    zone: ZoneId,
    perfMetric: PerfMetric,
    onTogglePerfMetric: (PerfMetric) -> Unit,
    onCustomRange: () -> Unit,
    onResetModel: (TokenStatsDisplayModelBreakdown) -> Unit,
    onEditPricing: (TokenStatPriceOverrideEntity) -> Unit,
    onAddPricing: () -> Unit,
    onGroupManage: (TokenStatsDisplayModelBreakdown) -> Unit,
) {
    val lifetime = state.lifetime ?: return
    val hasAnyData = lifetime.eventTotals.requests > 0L || lifetime.baselineTotals.identityCount > 0L
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TokenStatsLifetimeCard(
                overview = lifetime,
                currency = state.targetCurrency,
                manualRate = state.manualRate,
                rateIsEstimated = state.rateIsEstimated,
            )
        }

        item {
            TokenStatsFilterBar(
                selectedPreset = state.selectedPreset,
                selectedModels = state.selectedModels,
                availableModels = state.availableDisplayModels,
                knownModelNames = state.knownModelNames,
                selectedCategories = state.selectedCategories,
                selectedStatuses = state.selectedStatuses,
                costMode = state.costMode,
                targetCurrency = state.targetCurrency,
                onSelectPreset = viewModel::selectPreset,
                onCustomRange = onCustomRange,
                onToggleModel = viewModel::toggleModel,
                onSelectAllModels = viewModel::selectAllModels,
                onToggleCategory = viewModel::toggleCategory,
                onClearAllCategories = viewModel::clearCategories,
                onToggleStatus = viewModel::toggleStatus,
                onClearAllStatuses = viewModel::clearStatuses,
                onSetCostMode = viewModel::setCostMode,
                onSetCurrency = viewModel::setTargetCurrency,
            )
        }

        val range = state.range
        if (range == null) {
            item { NoDataCard(text = stringResource(R.string.token_stats_no_data_in_range)) }
        } else if (!hasAnyData) {
            item {
                EmptyStateCard()
            }
        } else {
            if (range.eventCount == 0L) {
                item {
                    NoDataCard(text = stringResource(R.string.token_stats_no_data_in_range))
                }
            } else {
                item {
                    TokenStatsChartsSection(
                        range = range,
                        currency = state.targetCurrency,
                        zone = zone,
                        perfMetric = perfMetric,
                        onTogglePerfMetric = onTogglePerfMetric,
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_model_details),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = stringResource(R.string.token_stats_model_count, range.displayModels.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TokenStatsModelCardsSection(
                            models = range.displayModels,
                            currency = state.targetCurrency,
                            costMode = state.costMode,
                            zone = zone,
                            onGroupManage = onGroupManage,
                            onReset = onResetModel,
                        )
                    }
                }
            }
        }

        item {
            val rateInvalidText = stringResource(R.string.token_stats_rate_invalid)
            TokenStatsRateCard(
                manualRate = state.manualRate,
                rateIsEstimated = state.rateIsEstimated,
                currency = state.targetCurrency,
                onSaveRate = { rate ->
                    val ok = viewModel.setManualRate(rate)
                    if (!ok) {
                        Toast.makeText(context, rateInvalidText, Toast.LENGTH_SHORT).show()
                    }
                    ok
                },
                onSetCurrency = viewModel::setTargetCurrency,
            )
        }

        item {
            TokenStatsPricingSection(
                range = range,
                overrides = state.overrides,
                onAdd = onAddPricing,
                onEdit = onEditPricing,
                onDelete = { override ->
                    val scope =
                        PriceOverrideScope.fromNameOrNull(override.scope)
                            ?: return@TokenStatsPricingSection
                    viewModel.deletePriceOverride(
                        scope = scope,
                        provider = override.provider,
                        model = override.model,
                        configId = override.configId,
                    )
                },
            )
        }

        item {
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun EmptyStateCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Analytics,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.token_stats_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.token_stats_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun NoDataCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

// ==== 四张图表卡（手机纵向；宽屏 2x2） ====

@Composable
private fun TokenStatsChartsSection(
    range: TokenStatsRangeData,
    currency: com.ai.assistance.operit.data.collects.PricingCurrency,
    zone: ZoneId,
    perfMetric: PerfMetric,
    onTogglePerfMetric: (PerfMetric) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val wide = maxWidth > 700.dp
        if (wide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CostChartCard(range = range, currency = currency, zone = zone)
                    TokenChartCard(range = range, currency = currency, zone = zone)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RequestChartCard(range = range, currency = currency, zone = zone)
                    PerformanceChartCard(
                        range = range,
                        zone = zone,
                        perfMetric = perfMetric,
                        onTogglePerfMetric = onTogglePerfMetric,
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CostChartCard(range = range, currency = currency, zone = zone)
                RequestChartCard(range = range, currency = currency, zone = zone)
                TokenChartCard(range = range, currency = currency, zone = zone)
                PerformanceChartCard(
                    range = range,
                    zone = zone,
                    perfMetric = perfMetric,
                    onTogglePerfMetric = onTogglePerfMetric,
                )
            }
        }
    }
}

@Composable
private fun CostChartCard(
    range: TokenStatsRangeData,
    currency: com.ai.assistance.operit.data.collects.PricingCurrency,
    zone: ZoneId,
) {
    val colors = LocalTokenStatsColors.current
    val models = range.displayModels
    val colorFor: (String) -> androidx.compose.ui.graphics.Color = { modelId ->
        val index = models.indexOfFirst { it.displayModelId == modelId }
        colors.modelPalette[index.coerceAtLeast(0) % colors.modelPalette.size]
    }
    // 预取模板：chart 回调是非 Composable lambda，不能在回调内解析资源
    val unknownCostTemplate = stringResource(R.string.token_stats_unknown_cost)
    val chartTitle = stringResource(R.string.token_stats_chart_cost)

    TokenStatsChartCard(
        title = chartTitle,
        summary = formatMoney(range.summary.cost.knownAmount, currency),
    ) {
        if (range.summary.cost.unknownContributionCount > 0L) {
            RangeUnknownHint(
                stringResource(R.string.token_stats_unknown_cost, range.summary.cost.unknownContributionCount)
            )
        }
        TokenStatsStackedBarChart(
            buckets = range.buckets,
            granularity = range.granularity,
            zone = zone,
            formatValue = { formatMoney(it, currency) },
            emptyText = stringResource(R.string.token_stats_no_data_in_range),
            chartLabel = chartTitle,
            stackSelector = { bucket ->
                models.mapNotNull { model ->
                    val cost = bucket.byModel[model.displayModelId]?.cost ?: return@mapNotNull null
                    if (cost.knownAmount <= 0.0) null else cost.knownAmount to colorFor(model.displayModelId)
                }
            },
            stackLabels = { bucket ->
                models.mapNotNull { model ->
                    val cost = bucket.byModel[model.displayModelId]?.cost ?: return@mapNotNull null
                    if (cost.knownAmount <= 0.0) null else model.displayName
                }
            },
            unknownNote = { bucket ->
                val unknown = bucket.totals.cost.unknownContributionCount
                if (unknown > 0L) String.format(unknownCostTemplate, unknown) else null
            },
            legendItems = models.take(8).map { it.displayName to colorFor(it.displayModelId) },
        )
    }
}

@Composable
private fun RequestChartCard(
    range: TokenStatsRangeData,
    currency: com.ai.assistance.operit.data.collects.PricingCurrency,
    zone: ZoneId,
) {
    val chartTitle = stringResource(R.string.token_stats_chart_requests)
    TokenStatsChartCard(
        title = chartTitle,
        summary = formatCount(range.summary.requests),
    ) {
        TokenStatsLineChart(
            buckets = range.buckets,
            granularity = range.granularity,
            zone = zone,
            formatValue = { formatCount(it.toLong()) },
            emptyText = stringResource(R.string.token_stats_no_data_in_range),
            chartLabel = chartTitle,
            valueSelector = { it.totals.requests.toDouble() },
        )
    }
}

@Composable
private fun TokenChartCard(
    range: TokenStatsRangeData,
    currency: com.ai.assistance.operit.data.collects.PricingCurrency,
    zone: ZoneId,
) {
    val colors = LocalTokenStatsColors.current
    // 预取模板：chart 回调是非 Composable lambda，不能在回调内解析资源
    val outputLabel = stringResource(R.string.token_stats_token_output)
    val cacheWriteLabel = stringResource(R.string.token_stats_token_cache_write)
    val cachedLabel = stringResource(R.string.token_stats_token_cached)
    val uncachedLabel = stringResource(R.string.token_stats_token_uncached)
    val reasoningLabel = stringResource(R.string.token_stats_token_reasoning)
    val unknownPartsTemplate = stringResource(R.string.token_stats_unknown_parts)
    val chartTitle = stringResource(R.string.token_stats_chart_tokens)

    val totalUnknown =
        range.summary.uncachedInput.unknownEventCount +
            range.summary.cachedInput.unknownEventCount +
            range.summary.output.unknownEventCount

    TokenStatsChartCard(
        title = chartTitle,
        summary = formatCompactCount(
            range.summary.uncachedInput.knownSum +
                range.summary.cachedInput.knownSum +
                range.summary.cacheWrite.knownSum +
                range.summary.output.knownSum +
                range.summary.reasoning.knownSum
        ),
    ) {
        if (totalUnknown > 0L) {
            RangeUnknownHint(stringResource(R.string.token_stats_unknown_parts, totalUnknown))
        }
        TokenStatsStackedBarChart(
            buckets = range.buckets,
            granularity = range.granularity,
            zone = zone,
            formatValue = { formatCompactCount(it.toLong()) },
            emptyText = stringResource(R.string.token_stats_no_data_in_range),
            chartLabel = chartTitle,
            stackSelector = { bucket ->
                listOf(
                    bucket.totals.output.knownSum.toDouble() to colors.output,
                    bucket.totals.cacheWrite.knownSum.toDouble() to colors.cacheWrite,
                    bucket.totals.cachedInput.knownSum.toDouble() to colors.cachedInput,
                    bucket.totals.uncachedInput.knownSum.toDouble() to colors.uncachedInput,
                    bucket.totals.reasoning.knownSum.toDouble() to colors.reasoning,
                )
            },
            stackLabels = {
                listOf(outputLabel, cacheWriteLabel, cachedLabel, uncachedLabel, reasoningLabel)
            },
            unknownNote = { bucket ->
                val unknown =
                    bucket.totals.uncachedInput.unknownEventCount +
                        bucket.totals.cachedInput.unknownEventCount +
                        bucket.totals.output.unknownEventCount
                if (unknown > 0L) String.format(unknownPartsTemplate, unknown) else null
            },
            legendItems = listOf(
                uncachedLabel to colors.uncachedInput,
                cachedLabel to colors.cachedInput,
                cacheWriteLabel to colors.cacheWrite,
                outputLabel to colors.output,
                reasoningLabel to colors.reasoning,
            ),
        )
    }
}

@Composable
private fun PerformanceChartCard(
    range: TokenStatsRangeData,
    zone: ZoneId,
    perfMetric: PerfMetric,
    onTogglePerfMetric: (PerfMetric) -> Unit,
) {
    val colors = LocalTokenStatsColors.current
    val aggregate =
        if (perfMetric == PerfMetric.TTFT) range.performance.ttft
        else range.performance.generationDuration
    // 预取模板：chart 回调是非 Composable lambda，不能在回调内解析资源
    val perfNoDataText = stringResource(R.string.token_stats_perf_no_data)
    val durationUnknownTemplate = stringResource(R.string.token_stats_duration_unknown)
    val chartTitle = stringResource(R.string.token_stats_chart_performance)

    TokenStatsChartCard(
        title = chartTitle,
        summary = durationSummaryText(aggregate),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = perfMetric == PerfMetric.TTFT,
                onClick = { onTogglePerfMetric(PerfMetric.TTFT) },
                label = { Text(stringResource(R.string.token_stats_perf_ttft)) },
            )
            FilterChip(
                selected = perfMetric == PerfMetric.GENERATION,
                onClick = { onTogglePerfMetric(PerfMetric.GENERATION) },
                label = { Text(stringResource(R.string.token_stats_perf_generation)) },
            )
        }
        Spacer(Modifier.height(8.dp))
        TokenStatsLineChart(
            buckets = range.buckets,
            granularity = range.granularity,
            zone = zone,
            formatValue = { formatDuration(it) },
            emptyText = perfNoDataText,
            chartLabel = chartTitle,
            valueSelector = { bucket ->
                val agg =
                    if (perfMetric == PerfMetric.TTFT) bucket.performance.ttft
                    else bucket.performance.generationDuration
                if (agg.hasData) agg.averageMs else null
            },
            unknownNote = { bucket ->
                val agg =
                    if (perfMetric == PerfMetric.TTFT) bucket.performance.ttft
                    else bucket.performance.generationDuration
                when {
                    !agg.hasData -> perfNoDataText
                    agg.unknownCount > 0L -> String.format(durationUnknownTemplate, agg.unknownCount)
                    else -> null
                }
            },
        )
    }
}

@Composable
private fun RangeUnknownHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalTokenStatsColors.current.unknownHint,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

// ==== 价格覆盖管理区 ====

@Composable
private fun TokenStatsPricingSection(
    range: TokenStatsRangeData?,
    overrides: List<TokenStatPriceOverrideEntity>,
    onAdd: () -> Unit,
    onEdit: (TokenStatPriceOverrideEntity) -> Unit,
    onDelete: (TokenStatPriceOverrideEntity) -> Unit,
) {
    var showBuiltin by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.token_stats_pricing_manage),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.token_stats_pricing_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onAdd) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.token_stats_pricing_add),
                    )
                }
            }

            // 内置默认（只读）
            val countBillingText = stringResource(R.string.settings_billing_mode_count)
            val providerModels =
                range?.displayModels
                    ?.flatMap { it.identities }
                    ?.map { "${it.provider}:${it.model}" }
                    ?.distinct()
                    .orEmpty()
            if (providerModels.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.token_stats_pricing_builtin),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showBuiltin = !showBuiltin }) {
                        Icon(
                            imageVector = if (showBuiltin) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = stringResource(R.string.token_stats_model_expand),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (showBuiltin) {
                    providerModels.take(12).forEach { providerModel ->
                        val defaults =
                            com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
                                .getDefaultPricing(providerModel)
                        Text(
                            text = buildString {
                                append(providerModel)
                                append("  ")
                                append("${defaults.currency.symbol}${defaults.inputPricePerMillion}/1M")
                                append(" · ")
                                append("${defaults.currency.symbol}${defaults.outputPricePerMillion}/1M")
                                if (defaults.billingMode == com.ai.assistance.operit.data.model.BillingMode.COUNT) {
                                    append(" · $countBillingText")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (providerModels.size > 12) {
                        Text(
                            text = stringResource(R.string.token_stats_more_count, providerModels.size - 12),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (overrides.isEmpty()) {
                Text(
                    text = stringResource(R.string.token_stats_pricing_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                overrides.forEach { override ->
                    PriceOverrideRow(
                        override = override,
                        onEdit = { onEdit(override) },
                        onDelete = { onDelete(override) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceOverrideRow(
    override: TokenStatPriceOverrideEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val scopeText =
        if (override.scope == PriceOverrideScope.CONFIG.name) {
            stringResource(R.string.token_stats_pricing_scope_config)
        } else {
            stringResource(R.string.token_stats_pricing_scope_provider)
        }
    val currency =
        if (override.pricingCurrency.equals("CNY", ignoreCase = true)) {
            com.ai.assistance.operit.data.collects.PricingCurrency.CNY
        } else {
            com.ai.assistance.operit.data.collects.PricingCurrency.USD
        }
    val prices = buildList {
        override.inputPricePerMillion?.let { add("${stringResource(R.string.token_stats_token_uncached)} ${formatPricePerMillion(it, currency)}") }
        override.cachedInputPricePerMillion?.let { add("${stringResource(R.string.token_stats_token_cached)} ${formatPricePerMillion(it, currency)}") }
        override.cacheWritePricePerMillion?.let { add("${stringResource(R.string.token_stats_token_cache_write)} ${formatPricePerMillion(it, currency)}") }
        override.outputPricePerMillion?.let { add("${stringResource(R.string.token_stats_token_output)} ${formatPricePerMillion(it, currency)}") }
        override.pricePerRequest?.let { add("${stringResource(R.string.settings_billing_mode_count)} ${formatPricePerRequest(it, currency)}") }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$scopeText · ${override.provider}:${override.model}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                if (override.configId.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.token_stats_config_id, override.configId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (prices.isEmpty()) {
                        stringResource(R.string.token_stats_unknown_pricing)
                    } else {
                        prices.joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 触摸目标保持 IconButton 默认 48dp（P1-8：不低于 48dp）
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.token_stats_pricing_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.token_stats_pricing_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
