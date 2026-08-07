package com.ai.assistance.operit.ui.features.tokenstats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CurrencyYen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.PriceOverrideScope
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import com.ai.assistance.operit.data.stats.TokenStatsGroupModelInfo
import com.ai.assistance.operit.data.stats.TokenStatsPriceOverrideDraft
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// ==== 自定义时间范围（两步日期选择，设备时区自然日边界） ====

/**
 * DatePicker 返回所选日期当日的 **UTC 0 点**；按 UTC 日历解析出日期本身
 * （P1-6：若用设备时区解析，西半球（如 New York）会因 UTC 日期尚在前一日
 * 20:00 而回退一天，导致选择 8/7 实际落在 8/6）。
 */
internal fun datePickerMillisToLocalDate(utcMidnightMs: Long): java.time.LocalDate =
    Instant.ofEpochMilli(utcMidnightMs).atZone(java.time.ZoneOffset.UTC).toLocalDate()

/**
 * 由“开始日 + 结束日（**包含**结束日当天）”构造半开区间范围：
 * `[startDay 0 点, endDay+1 天 0 点)`。同日合法（一天范围）。
 * 结束日早于开始日抛 [IllegalArgumentException]。
 */
internal fun customRangeInclusiveEnd(
    startDate: java.time.LocalDate,
    endDate: java.time.LocalDate,
    zone: ZoneId,
): com.ai.assistance.operit.data.stats.TokenStatsTimeRange {
    require(!endDate.isBefore(startDate)) { "end date must not be before start date" }
    val startMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val endMs = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return com.ai.assistance.operit.data.stats.TokenStatsTimeRanges.customRange(startMs, endMs)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomRangeDialog(
    zone: ZoneId,
    onConfirm: (startMs: Long, endMs: Long) -> Boolean,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var startDate by remember { mutableStateOf<java.time.LocalDate?>(null) }

    // 步骤切换时重建 picker（rememberDatePickerState 只取首帧初始值）；
    // DatePicker 的毫秒语义是“UTC 当日 0 点”，初始值同样按 UTC 日历生成。
    val pickerState =
        androidx.compose.runtime.key(step, startDate) {
            rememberDatePickerState(
                initialSelectedDateMillis = startDate?.atStartOfDay(java.time.ZoneOffset.UTC)
                    ?.toInstant()?.toEpochMilli()
            )
        }

    val title =
        if (step == 0) {
            stringResource(R.string.token_stats_custom_range_pick_start)
        } else {
            stringResource(R.string.token_stats_custom_range_pick_end)
        }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = pickerState.selectedDateMillis
                    if (selected != null) {
                        val date = datePickerMillisToLocalDate(selected)
                        if (step == 0) {
                            startDate = date
                            step = 1
                        } else {
                            val start = startDate ?: return@TextButton
                            // 结束日包含当天：+1 天 0 点作为半开区间终点（P1-6），
                            // 同日合法；结束早于开始产生的非法边界由 onConfirm
                            // （VM 校验）拒绝并提示。
                            val startMs = start.atStartOfDay(zone).toInstant().toEpochMilli()
                            val endMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                            if (onConfirm(startMs, endMs)) {
                                onDismiss()
                            }
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.token_stats_custom_range_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { if (step == 0) onDismiss() else step = 0 }) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

// ==== 价格覆盖新增/编辑 ====

@Composable
internal fun PriceOverrideDialog(
    existing: TokenStatPriceOverrideEntity?,
    initialDraft: TokenStatsPriceOverrideDraft? = null,
    onSave: (TokenStatsPriceOverrideDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var scope by remember(existing, initialDraft) {
        mutableStateOf(
            existing?.let { PriceOverrideScope.fromNameOrNull(it.scope) }
                ?: initialDraft?.scope
                ?: PriceOverrideScope.PROVIDER_MODEL
        )
    }
    var provider by remember(existing, initialDraft) {
        mutableStateOf(existing?.provider ?: initialDraft?.provider.orEmpty())
    }
    var model by remember(existing, initialDraft) {
        mutableStateOf(existing?.model ?: initialDraft?.model.orEmpty())
    }
    var configId by remember(existing, initialDraft) {
        mutableStateOf(existing?.configId ?: initialDraft?.configId.orEmpty())
    }
    var billingMode by remember(existing, initialDraft) {
        mutableStateOf(
            existing?.let { BillingMode.fromString(it.billingMode) }
                ?: initialDraft?.billingMode
                ?: BillingMode.TOKEN
        )
    }
    var currency by remember(existing, initialDraft) {
        mutableStateOf(
            existing?.let {
                if (it.pricingCurrency.equals("CNY", ignoreCase = true)) PricingCurrency.CNY else PricingCurrency.USD
            } ?: initialDraft?.currency ?: PricingCurrency.CNY
        )
    }
    var inputPrice by remember(existing, initialDraft) {
        mutableStateOf(formatEditablePrice(existing?.inputPricePerMillion ?: initialDraft?.inputPricePerMillion))
    }
    var cachedInputPrice by remember(existing, initialDraft) {
        mutableStateOf(formatEditablePrice(existing?.cachedInputPricePerMillion ?: initialDraft?.cachedInputPricePerMillion))
    }
    var cacheWritePrice by remember(existing, initialDraft) {
        mutableStateOf(formatEditablePrice(existing?.cacheWritePricePerMillion ?: initialDraft?.cacheWritePricePerMillion))
    }
    var outputPrice by remember(existing, initialDraft) {
        mutableStateOf(formatEditablePrice(existing?.outputPricePerMillion ?: initialDraft?.outputPricePerMillion))
    }
    var pricePerRequest by remember(existing, initialDraft) {
        mutableStateOf(formatEditablePrice(existing?.pricePerRequest ?: initialDraft?.pricePerRequest))
    }
    var inlineError by remember { mutableStateOf<String?>(null) }
    val pricingInvalidText = stringResource(R.string.token_stats_pricing_invalid)
    // P1-7：编辑已有覆盖时业务键（scope/provider/model/configId）只读，
    // 只允许修改价格/币种/计费方式，防止键被改掉产生第二行或误覆盖。
    val editing = existing != null
    val targetLocked = editing || initialDraft != null

    val priceFields =
        if (billingMode == BillingMode.TOKEN) {
            listOf(inputPrice, cachedInputPrice, cacheWritePrice, outputPrice)
        } else {
            listOf(pricePerRequest)
        }
    val allParsed = priceFields.all {
        it.isBlank() || it.toDoubleOrNull() != null
    }
    // CONFIG 作用域必须填写配置 ID（P1-7）；新增时同样强制
    val configIdValid = scope != PriceOverrideScope.CONFIG || configId.isNotBlank()

    val builtinReference =
        if (scope == PriceOverrideScope.PROVIDER_MODEL && provider.isNotBlank() && model.isNotBlank()) {
            DefaultModelPricingCollect.getDefaultPricing("$provider:$model")
        } else {
            null
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.token_stats_pricing_add
                    else R.string.token_stats_pricing_edit
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = scope == PriceOverrideScope.PROVIDER_MODEL,
                        onClick = { if (!targetLocked) scope = PriceOverrideScope.PROVIDER_MODEL },
                        enabled = !targetLocked,
                        label = { Text(stringResource(R.string.token_stats_pricing_scope_provider)) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = scope == PriceOverrideScope.CONFIG,
                        onClick = { if (!targetLocked) scope = PriceOverrideScope.CONFIG },
                        enabled = !targetLocked,
                        label = { Text(stringResource(R.string.token_stats_pricing_scope_config)) },
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = provider,
                    onValueChange = { if (!targetLocked) provider = it },
                    label = { Text(stringResource(R.string.token_stats_pricing_provider_label)) },
                    singleLine = true,
                    enabled = !targetLocked,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { if (!targetLocked) model = it },
                    label = { Text(stringResource(R.string.token_stats_pricing_model_label)) },
                    singleLine = true,
                    enabled = !targetLocked,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (scope == PriceOverrideScope.CONFIG) {
                    OutlinedTextField(
                        value = configId,
                        onValueChange = { if (!targetLocked) configId = it },
                        label = { Text(stringResource(R.string.token_stats_pricing_config_label)) },
                        singleLine = true,
                        enabled = !targetLocked,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = billingMode == BillingMode.TOKEN,
                        onClick = { billingMode = BillingMode.TOKEN },
                        label = { Text(stringResource(R.string.settings_billing_mode_token)) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = billingMode == BillingMode.COUNT,
                        onClick = { billingMode = BillingMode.COUNT },
                        label = { Text(stringResource(R.string.settings_billing_mode_count)) },
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = currency == PricingCurrency.CNY,
                        onClick = { currency = PricingCurrency.CNY },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CurrencyYen, null, modifier = Modifier.padding(end = 2.dp))
                                Text(stringResource(R.string.token_stats_currency_cny))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = currency == PricingCurrency.USD,
                        onClick = { currency = PricingCurrency.USD },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AttachMoney, null, modifier = Modifier.padding(end = 2.dp))
                                Text(stringResource(R.string.token_stats_currency_usd))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                HorizontalDivider()

                if (billingMode == BillingMode.TOKEN) {
                    PriceField(
                        label = stringResource(R.string.token_stats_pricing_input),
                        value = inputPrice,
                        onChange = { inputPrice = it },
                    )
                    PriceField(
                        label = stringResource(R.string.token_stats_pricing_cached),
                        value = cachedInputPrice,
                        onChange = { cachedInputPrice = it },
                    )
                    PriceField(
                        label = stringResource(R.string.token_stats_pricing_cache_write),
                        value = cacheWritePrice,
                        onChange = { cacheWritePrice = it },
                    )
                    PriceField(
                        label = stringResource(R.string.token_stats_pricing_output),
                        value = outputPrice,
                        onChange = { outputPrice = it },
                    )
                } else {
                    PriceField(
                        label = stringResource(R.string.token_stats_pricing_per_request),
                        value = pricePerRequest,
                        onChange = { pricePerRequest = it },
                    )
                }

                builtinReference?.let { defaults ->
                    val referenceText =
                        buildString {
                            append("${defaults.currency.symbol}${defaults.inputPricePerMillion}/1M")
                            append(" · ")
                            append("${defaults.currency.symbol}${defaults.outputPricePerMillion}/1M")
                            if (defaults.billingMode == BillingMode.COUNT) {
                                append(" · ${stringResource(R.string.settings_billing_mode_count)}")
                            }
                        }
                    Text(
                        text = stringResource(R.string.token_stats_pricing_reference, referenceText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                inlineError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = provider.isNotBlank() && model.isNotBlank() && allParsed && configIdValid,
                onClick = {
                    val parse = { raw: String -> raw.trim().toDoubleOrNull() }
                    val draft =
                        TokenStatsPriceOverrideDraft(
                            scope = scope,
                            provider = provider,
                            model = model,
                            configId = configId.ifBlank { null },
                            billingMode = billingMode,
                            currency = currency,
                            inputPricePerMillion = parse(inputPrice),
                            cachedInputPricePerMillion = parse(cachedInputPrice),
                            cacheWritePricePerMillion = parse(cacheWritePrice),
                            outputPricePerMillion = parse(outputPrice),
                            pricePerRequest = parse(pricePerRequest),
                        )
                    runCatching { onSave(draft) }
                        .onSuccess { onDismiss() }
                        .onFailure {
                            inlineError = pricingInvalidText
                        }
                },
            ) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = {
            Row {
                if (existing != null && onDelete != null) {
                    TextButton(
                        onClick = {
                            onDelete()
                            onDismiss()
                        },
                    ) {
                        Text(
                            stringResource(R.string.token_stats_pricing_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        },
    )
}

@Composable
private fun PriceField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatEditablePrice(value: Double?): String =
    value?.let {
        String.format(Locale.US, "%.6f", it).trimEnd('0').trimEnd('.')
    } ?: ""

// ==== 分组管理（别名/合并） ====

/**
 * 分组管理对话框（阶段 4 P1 修复）：[groupInfo] 与 [otherGroups] 必须来自
 * 独立于统计筛选的完整分组元数据（[TokenStatsGroupModelInfo]）——当前筛选范围
 * 的明细只包含有事件的身份/分组，作为成员或目标会把无事件组成员漏掉。
 */
@Composable
internal fun GroupManageDialog(
    groupInfo: TokenStatsGroupModelInfo,
    otherGroups: List<TokenStatsGroupModelInfo>,
    onRename: (String) -> Unit,
    onCreateAndMerge: (String) -> Unit,
    onMergeInto: (String) -> Unit,
    onRestoreDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    var renameInput by remember(groupInfo.displayModelId) { mutableStateOf(groupInfo.displayName) }
    var newGroupInput by remember { mutableStateOf("") }
    var mergeTarget by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.token_stats_group_manage)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = groupInfo.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        R.string.token_stats_group_members,
                        groupInfo.memberIdentityIds.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // 重命名
                Text(
                    text = stringResource(R.string.token_stats_group_rename),
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = renameInput.isNotBlank(),
                    onClick = {
                        onRename(renameInput.trim())
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.settings_save))
                }

                HorizontalDivider()

                // 新建分组并合并
                Text(
                    text = stringResource(R.string.token_stats_group_new),
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = newGroupInput,
                    onValueChange = { newGroupInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = newGroupInput.isNotBlank(),
                    onClick = {
                        onCreateAndMerge(newGroupInput.trim())
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.token_stats_group_create))
                }

                HorizontalDivider()

                // 合并到其他分组
                Text(
                    text = stringResource(R.string.token_stats_group_merge),
                    style = MaterialTheme.typography.titleSmall,
                )
                var targetExpanded by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = mergeTarget?.let { id ->
                            otherGroups.firstOrNull { it.displayModelId == id }?.displayName ?: id
                        } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.token_stats_group_merge_into)) },
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        TextButton(onClick = { targetExpanded = true }) {
                            Text(stringResource(R.string.token_stats_group_pick))
                        }
                        DropdownMenu(expanded = targetExpanded, onDismissRequest = { targetExpanded = false }) {
                            otherGroups.forEach { group ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = group.displayName,
                                            maxLines = 1,
                                        )
                                    },
                                    onClick = {
                                        mergeTarget = group.displayModelId
                                        targetExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                TextButton(
                    enabled = mergeTarget != null,
                    onClick = {
                        onMergeInto(mergeTarget!!)
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.token_stats_group_merge_confirm))
                }

                HorizontalDivider()

                // 恢复默认分组（两次点击确认）
                TextButton(
                    onClick = {
                        if (confirmRestore) {
                            onRestoreDefault()
                            onDismiss()
                        } else {
                            confirmRestore = true
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(
                        text = stringResource(
                            if (confirmRestore) R.string.token_stats_group_restore_confirm
                            else R.string.token_stats_group_restore
                        ),
                        color = if (confirmRestore) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}
