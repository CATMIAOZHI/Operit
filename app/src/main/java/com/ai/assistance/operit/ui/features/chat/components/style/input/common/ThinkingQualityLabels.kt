package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.annotation.StringRes
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.ApiPreferences

/**
 * 思考程度档位的显示标签。
 *
 * 资源映射仍由实际 reasoning_effort 值（ApiPreferences.THINKING_QUALITY_EFFORTS）驱动，
 * 但协议值与用户可见文案分离，以便界面随 locale 正确本地化。
 */
@StringRes
fun thinkingQualityLevelLabelRes(level: Int): Int =
    when (ApiPreferences.thinkingQualityEffort(level)) {
        "low" -> R.string.thinking_quality_level_low
        "medium" -> R.string.thinking_quality_level_medium
        "high" -> R.string.thinking_quality_level_high
        "xhigh" -> R.string.thinking_quality_level_xhigh
        "max" -> R.string.thinking_quality_level_max
        else -> error("Unsupported thinking quality effort")
    }
