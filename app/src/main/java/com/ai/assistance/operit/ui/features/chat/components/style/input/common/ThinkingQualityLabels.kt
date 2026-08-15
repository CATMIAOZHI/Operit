package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.llmprovider.ThinkingRequestSummary
import com.ai.assistance.operit.data.preferences.ApiPreferences

/**
 * 思考程度档位的显示标签。
 *
 * 资源映射仍由实际 reasoning_effort 值（ApiPreferences.THINKING_QUALITY_EFFORTS）驱动，
 * 但协议值与用户可见文案分离，以便界面随 locale 正确本地化。
 */
@StringRes
fun thinkingEffortLabelRes(effort: String): Int? =
    when (effort) {
        "low" -> R.string.thinking_quality_level_low
        "medium" -> R.string.thinking_quality_level_medium
        "high" -> R.string.thinking_quality_level_high
        "xhigh" -> R.string.thinking_quality_level_xhigh
        "max" -> R.string.thinking_quality_level_max
        else -> null
    }

@Composable
private fun thinkingEffortLabel(effort: String): String =
    thinkingEffortLabelRes(effort)?.let { stringResource(it) } ?: effort

@Composable
fun thinkingRequestSummaryLabel(summary: ThinkingRequestSummary): String =
    when (summary) {
        is ThinkingRequestSummary.Effort -> thinkingEffortLabel(summary.value)
        is ThinkingRequestSummary.BudgetTokens ->
            stringResource(R.string.thinking_budget_tokens, summary.value)
        is ThinkingRequestSummary.CustomValue ->
            summary.value.takeIf { it.isNotBlank() } ?: stringResource(R.string.custom)
        ThinkingRequestSummary.Enabled -> stringResource(R.string.enabled)
        ThinkingRequestSummary.Disabled -> stringResource(R.string.disabled)
        ThinkingRequestSummary.NotSent -> stringResource(R.string.thinking_strength_not_sent)
    }

@StringRes
fun thinkingQualityLevelLabelRes(level: Int): Int =
    checkNotNull(thinkingEffortLabelRes(ApiPreferences.thinkingQualityEffort(level)))
