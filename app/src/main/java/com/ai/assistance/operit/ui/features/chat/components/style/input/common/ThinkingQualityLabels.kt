package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import com.ai.assistance.operit.data.preferences.ApiPreferences

/**
 * 思考程度档位的显示标签。
 *
 * 标签是档位的语义名，由实际 reasoning_effort 值（ApiPreferences.THINKING_QUALITY_EFFORTS）
 * 派生（首字母大写）。发送 reasoning_effort 的 provider（OpenAI/Nvidia/Deepseek 等）会发送
 * 与该标签一一对应的值；发送 token 预算的 provider（OpenRouter/Qwen）按档位映射为推理预算，
 * 标签仍表示档位语义。例如：low → Low，medium → Medium，high → High，xhigh → X-High，max → Max。
 */
fun thinkingQualityLevelLabel(level: Int): String {
    return thinkingEffortLabel(ApiPreferences.thinkingQualityEffort(level))
}

fun thinkingEffortLabel(effort: String): String {
    return if (effort == "xhigh") {
        "X-High"
    } else {
        effort.replaceFirstChar { it.uppercase() }
    }
}
