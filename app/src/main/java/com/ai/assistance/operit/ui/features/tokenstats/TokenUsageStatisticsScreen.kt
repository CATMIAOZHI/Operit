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
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.PriceOverrideScope
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import com.ai.assistance.operit.data.stats.TokenStatsDisplayModelBreakdown
import com.ai.assistance.operit.data.stats.TokenStatsRangeData
import com.ai.assistance.operit.ui.components.CustomScaffold
import java.time.ZoneId
import kotlinx.coroutines.delay

/** 性能卡指标切换。 */
internal enum class PerfMetric { TTFT, GENERATION }
private enum class ChartDetailMetric { COST, REQUESTS, TOKENS }

/**
 * Token 统计完整页面（阶段 4）。
 * 沿用 Operit 设置入口与页面框架（Settings → Token使用统计），
 * 升级旧累计页面为账本统计：生命周期总览 + 时间/模型/分类/状态筛选 +
 * 四张图表卡 + 模型明细 + 汇率/币种/价格覆盖/分组管理设置。
 */
@Composable
fun TokenUsageStatisticsScreen(
    onBackPressed: () -> Unit,
    onOpenGroupManagement: () -> Unit,
    onOpenPricingManagement: () -> Unit,
) {
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
    var showDeleteRangeDialog by rememberSaveable { mutableStateOf(false) }
    // 全部删除两步确认：第一步危险确认，第二步 baseline 选择（阶段 5）
    var showDeleteAllConfirm by rememberSaveable { mutableStateOf(false) }
    var showDeleteAllBaseline by rememberSaveable { mutableStateOf(false) }
    // 模型删除两步确认：目标模型 + baseline 选择
    var deleteModel by remember { mutableStateOf<TokenStatsDisplayModelBreakdown?>(null) }
    var showDeleteModelBaseline by rememberSaveable { mutableStateOf(false) }
    var perfMetric by rememberSaveable { mutableStateOf(PerfMetric.TTFT) }

    LaunchedEffect(actionMessage) {
        actionMessage?.let { message ->
            Toast.makeText(context, message.text, Toast.LENGTH_SHORT).show()
            viewModel.consumeActionMessage()
        }
    }
    LaunchedEffect(Unit) { viewModel.loadForEntry() }

    TokenStatsColorsProvider {
        CustomScaffold(
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showDeleteAllConfirm = true },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(id = R.string.token_stats_delete_all_title),
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
                            onDeleteRange = { showDeleteRangeDialog = true },
                            onDeleteModel = { deleteModel = it },
                            onOpenGroupManagement = onOpenGroupManagement,
                            onOpenPricingManagement = onOpenPricingManagement,
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
            maxRangeDays = TokenUsageStatisticsViewModel.MAX_CUSTOM_RANGE_DAYS,
            onConfirm = { start, end -> viewModel.setCustomRange(start, end) },
            onDismiss = { showCustomRange = false },
        )
    }

    // ==== 阶段 5 删除对话框 ====
    // 危险操作明确确认：范围删除单步确认（绝不触碰 baseline）；
    // 模型/全部删除两步确认（第一步危险确认 → 第二步选择是否同时删除 baseline）。

    if (showDeleteRangeDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteRangeDialog = false },
            title = { Text(stringResource(R.string.token_stats_delete_range_title)) },
            text = { Text(stringResource(R.string.token_stats_delete_range_message)) },
            confirmButton = {
                CountdownDeleteButton(
                    onClick = {
                        viewModel.deleteRangeEvents()
                        showDeleteRangeDialog = false
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteRangeDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text(stringResource(R.string.token_stats_delete_all_title)) },
            text = { Text(stringResource(R.string.token_stats_delete_all_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAllConfirm = false
                        showDeleteAllBaseline = true
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.token_stats_delete_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (showDeleteAllBaseline) {
        val baselineRows = state.lifetime?.baselineTotals?.identityCount ?: 0L
        DeleteBaselineDialog(
            title = stringResource(R.string.token_stats_delete_baseline_title),
            message = stringResource(R.string.token_stats_delete_baseline_message_all, baselineRows),
            onEventsOnly = {
                showDeleteAllBaseline = false
                viewModel.deleteAllStatistics(deleteBaselines = false)
            },
            onEventsAndBaseline = {
                showDeleteAllBaseline = false
                viewModel.deleteAllStatistics(deleteBaselines = true)
            },
            onDismiss = { showDeleteAllBaseline = false },
        )
    }

    deleteModel?.let { model ->
        // 成员数取完整分组元数据（state.groupModels），与统计筛选无关（P1 修复）
        val groupMembers =
            state.groupModels.firstOrNull { it.displayModelId == model.displayModelId }
                ?.memberIdentityIds?.size ?: model.identities.size
        if (showDeleteModelBaseline) {
            DeleteBaselineDialog(
                title = stringResource(R.string.token_stats_delete_baseline_title),
                message = stringResource(
                    R.string.token_stats_delete_baseline_message_model,
                    model.displayName,
                ),
                onEventsOnly = {
                    showDeleteModelBaseline = false
                    viewModel.deleteDisplayModel(model.displayModelId, deleteBaselines = false)
                    deleteModel = null
                },
                onEventsAndBaseline = {
                    showDeleteModelBaseline = false
                    viewModel.deleteDisplayModel(model.displayModelId, deleteBaselines = true)
                    deleteModel = null
                },
                onDismiss = { showDeleteModelBaseline = false },
            )
        } else {
            AlertDialog(
                onDismissRequest = { deleteModel = null },
                title = { Text(stringResource(R.string.token_stats_delete_model_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.token_stats_delete_model_message,
                            model.displayName,
                            groupMembers,
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteModelBaseline = true
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.token_stats_delete_continue))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteModel = null }) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                },
            )
        }
    }
}

/**
 * 删除的第二步：是否同时删除迁移的旧统计 baseline（阶段 5）。
 * 选择“仅删除事件”只删事件并保留 baseline；选择“删除事件与 baseline”
 * 才删对应/全部 baseline；取消不做任何删除。
 */
@Composable
private fun DeleteBaselineDialog(
    title: String,
    message: String,
    onEventsOnly: () -> Unit,
    onEventsAndBaseline: () -> Unit,
    onDismiss: () -> Unit,
) {
    var remainingSeconds by rememberSaveable { mutableIntStateOf(DELETE_COUNTDOWN_SECONDS) }
    LaunchedEffect(Unit) {
        while (remainingSeconds > 0) {
            delay(1_000)
            remainingSeconds--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            CountdownDeleteButton(
                onClick = onEventsAndBaseline,
                remainingSeconds = remainingSeconds,
                readyLabel = stringResource(R.string.token_stats_delete_events_and_baseline),
            )
        },
        dismissButton = {
            Row {
                CountdownDeleteButton(
                    onClick = onEventsOnly,
                    remainingSeconds = remainingSeconds,
                    readyLabel = stringResource(R.string.token_stats_delete_events_only),
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        },
    )
}

private const val DELETE_COUNTDOWN_SECONDS = 5

/** 最终危险操作在对话框出现后等待五秒；取消始终保持可用。 */
@Composable
private fun CountdownDeleteButton(
    onClick: () -> Unit,
    remainingSeconds: Int? = null,
    readyLabel: String = stringResource(R.string.token_stats_delete_confirm),
) {
    var localRemaining by rememberSaveable { mutableIntStateOf(DELETE_COUNTDOWN_SECONDS) }
    val remaining = remainingSeconds ?: localRemaining
    if (remainingSeconds == null) {
        LaunchedEffect(Unit) {
            while (localRemaining > 0) {
                delay(1_000)
                localRemaining--
            }
        }
    }
    TextButton(
        enabled = remaining == 0,
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
            disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
        ),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            if (remaining > 0) {
                stringResource(R.string.token_stats_delete_countdown, readyLabel, remaining)
            } else {
                readyLabel
            }
        )
    }
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
    onDeleteRange: () -> Unit,
    onDeleteModel: (TokenStatsDisplayModelBreakdown) -> Unit,
    onOpenGroupManagement: () -> Unit,
    onOpenPricingManagement: () -> Unit,
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
            TokenActivitySection(
                state = state.activity,
                zone = zone,
                onSelectRecent = viewModel::setActivityRecent,
                onSelectYear = viewModel::setActivityYear,
                onSelectMode = viewModel::setActivityViewMode,
            )
        }

        item {
            TokenStatsLifetimeCard(
                overview = lifetime,
                currency = state.targetCurrency,
                manualRate = state.manualRate,
                rateIsEstimated = state.rateIsEstimated,
                includeLegacy = state.includeLegacy,
                onIncludeLegacyChange = viewModel::setIncludeLegacy,
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
                onDeleteRange = onDeleteRange,
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
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(R.string.token_stats_model_count, range.displayModels.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onOpenGroupManagement) {
                                Text(stringResource(R.string.token_stats_group_manage))
                            }
                        }
                        TokenStatsModelCardsSection(
                            models = range.displayModels,
                            currency = state.targetCurrency,
                            costMode = state.costMode,
                            zone = zone,
                            onGroupManage = { onOpenGroupManagement() },
                            onDelete = onDeleteModel,
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
                onSaveRate = { rate ->
                    val ok = viewModel.setManualRate(rate)
                    if (!ok) {
                        Toast.makeText(context, rateInvalidText, Toast.LENGTH_SHORT).show()
                    }
                    ok
                },
            )
        }

        item {
            TokenStatsPricingSection(
                range = range,
                overrides = state.overrides,
                onManage = onOpenPricingManagement,
            )
        }

        item {
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun EmptyStateCard() {
    TokenStatsWhiteCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
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
                tint = TokenStatsCardMuted,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.token_stats_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = TokenStatsCardMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.token_stats_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = TokenStatsCardMuted.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun NoDataCard(text: String) {
    TokenStatsWhiteCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TokenStatsCardMuted,
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
    var detailMetric by rememberSaveable { mutableStateOf<ChartDetailMetric?>(null) }
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
                    CostChartCard(range = range, currency = currency, zone = zone) {
                        detailMetric = ChartDetailMetric.COST
                    }
                    TokenChartCard(range = range, currency = currency, zone = zone) {
                        detailMetric = ChartDetailMetric.TOKENS
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RequestChartCard(range = range, currency = currency, zone = zone) {
                        detailMetric = ChartDetailMetric.REQUESTS
                    }
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
                CostChartCard(range = range, currency = currency, zone = zone) {
                    detailMetric = ChartDetailMetric.COST
                }
                RequestChartCard(range = range, currency = currency, zone = zone) {
                    detailMetric = ChartDetailMetric.REQUESTS
                }
                TokenChartCard(range = range, currency = currency, zone = zone) {
                    detailMetric = ChartDetailMetric.TOKENS
                }
                PerformanceChartCard(
                    range = range,
                    zone = zone,
                    perfMetric = perfMetric,
                    onTogglePerfMetric = onTogglePerfMetric,
                )
            }
        }
    }

    detailMetric?.let { metric ->
        TokenStatsChartDetailDialog(
            metric = metric,
            range = range,
            currency = currency,
            onDismiss = { detailMetric = null },
        )
    }
}

@Composable
private fun TokenStatsChartDetailDialog(
    metric: ChartDetailMetric,
    range: TokenStatsRangeData,
    currency: com.ai.assistance.operit.data.collects.PricingCurrency,
    onDismiss: () -> Unit,
) {
    val title = stringResource(
        when (metric) {
            ChartDetailMetric.COST -> R.string.token_stats_detail_cost
            ChartDetailMetric.REQUESTS -> R.string.token_stats_detail_requests
            ChartDetailMetric.TOKENS -> R.string.token_stats_detail_tokens
        }
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                when (metric) {
                    ChartDetailMetric.COST -> range.displayModels.forEach { model ->
                        if (model.totals.cost.knownAmount > 0.0) {
                            TokenStatsDetailRow(model.displayName, formatMoney(model.totals.cost.knownAmount, currency))
                        }
                    }
                    ChartDetailMetric.REQUESTS -> range.displayModels.forEach { model ->
                        if (model.totals.requests > 0L) {
                            TokenStatsDetailRow(model.displayName, formatCount(model.totals.requests))
                        }
                    }
                    ChartDetailMetric.TOKENS -> {
                        // canonical 总 Token 为权威合计；缓存/非缓存/输出仍是诊断分量
                        TokenStatsDetailRow(
                            stringResource(R.string.token_stats_tokens_total),
                            formatCount(range.summary.totalTokens.knownSum),
                        )
                        TokenStatsDetailRow(
                            stringResource(R.string.token_stats_token_cached),
                            formatCount(range.summary.cachedInput.knownSum),
                        )
                        TokenStatsDetailRow(
                            stringResource(R.string.token_stats_token_uncached),
                            formatCount(range.summary.uncachedInput.knownSum),
                        )
                        TokenStatsDetailRow(
                            stringResource(R.string.token_stats_token_output),
                            formatCount(range.summary.output.knownSum),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.token_stats_detail_close))
            }
        },
    )
}

@Composable
private fun TokenStatsDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = LocalTokenStatsColors.current.chartAccent,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CostChartCard(
    range: TokenStatsRangeData,
    currency: com.ai.assistance.operit.data.collects.PricingCurrency,
    zone: ZoneId,
    onSummaryClick: () -> Unit,
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
        onSummaryClick = onSummaryClick,
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
    onSummaryClick: () -> Unit,
) {
    val chartTitle = stringResource(R.string.token_stats_chart_requests)
    TokenStatsChartCard(
        title = chartTitle,
        summary = formatCount(range.summary.requests),
        onSummaryClick = onSummaryClick,
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
    onSummaryClick: () -> Unit,
) {
    val colors = LocalTokenStatsColors.current
    // 预取模板：chart 回调是非 Composable lambda，不能在回调内解析资源
    val outputLabel = stringResource(R.string.token_stats_token_output)
    val cachedLabel = stringResource(R.string.token_stats_token_cached)
    val uncachedLabel = stringResource(R.string.token_stats_token_uncached)
    val unknownPartsTemplate = stringResource(R.string.token_stats_unknown_parts)
    val chartTitle = stringResource(R.string.token_stats_chart_tokens)

    val totalUnknown = range.summary.totalTokens.unknownEventCount

    TokenStatsChartCard(
        title = chartTitle,
        // canonical 总 Token（聚合器逐事件推导，口径与 headline/detail 一致）
        summary = formatCompactCount(range.summary.totalTokens.knownSum),
        onSummaryClick = onSummaryClick,
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
                    bucket.totals.uncachedInput.knownSum.toDouble() to colors.uncachedInput,
                    bucket.totals.cachedInput.knownSum.toDouble() to colors.cachedInput,
                )
            },
            stackLabels = {
                listOf(outputLabel, uncachedLabel, cachedLabel)
            },
            // 堆叠分量是诊断明细（可能因 provider 口径不完全等于总量），
            // tooltip/无障碍合计必须用 canonical 总 Token
            stackTotalSelector = { bucket -> bucket.totals.totalTokens.knownSum.toDouble() },
            unknownNote = { bucket ->
                val unknown = bucket.totals.totalTokens.unknownEventCount
                if (unknown > 0L) String.format(unknownPartsTemplate, unknown) else null
            },
            legendItems = listOf(
                uncachedLabel to colors.uncachedInput,
                cachedLabel to colors.cachedInput,
                outputLabel to colors.output,
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
    onManage: () -> Unit,
) {
    var showBuiltin by remember { mutableStateOf(false) }

    TokenStatsWhiteCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
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
                        color = TokenStatsCardMuted,
                    )
                }
                IconButton(onClick = onManage) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.token_stats_management_open),
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
                            color = TokenStatsCardMuted,
                        )
                    }
                    if (providerModels.size > 12) {
                        Text(
                            text = stringResource(R.string.token_stats_more_count, providerModels.size - 12),
                            style = MaterialTheme.typography.bodySmall,
                            color = TokenStatsCardMuted,
                        )
                    }
                }
            }

            if (overrides.isEmpty()) {
                Text(
                    text = stringResource(R.string.token_stats_pricing_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = TokenStatsCardMuted,
                )
            } else {
                overrides.forEach { override ->
                    PriceOverrideRow(override = override)
                }
            }
        }
    }
}

@Composable
private fun PriceOverrideRow(
    override: TokenStatPriceOverrideEntity,
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
    val prices =
        if (com.ai.assistance.operit.data.model.BillingMode.fromString(override.billingMode) ==
            com.ai.assistance.operit.data.model.BillingMode.COUNT
        ) {
            listOfNotNull(
                override.pricePerRequest?.let {
                    "${stringResource(R.string.settings_billing_mode_count)} ${formatPricePerRequest(it, currency)}"
                }
            )
        } else {
            buildList {
                override.inputPricePerMillion?.let { add("${stringResource(R.string.token_stats_token_uncached)} ${formatPricePerMillion(it, currency)}") }
                override.cachedInputPricePerMillion?.let { add("${stringResource(R.string.token_stats_token_cached)} ${formatPricePerMillion(it, currency)}") }
                override.cacheWritePricePerMillion?.let { add("${stringResource(R.string.token_stats_token_cache_write)} ${formatPricePerMillion(it, currency)}") }
                override.outputPricePerMillion?.let { add("${stringResource(R.string.token_stats_token_output)} ${formatPricePerMillion(it, currency)}") }
            }
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
                        color = TokenStatsCardMuted,
                    )
                }
                Text(
                    text = if (prices.isEmpty()) {
                        stringResource(R.string.token_stats_unknown_pricing)
                    } else {
                        prices.joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TokenStatsCardMuted,
                )
            }
        }
    }
}
