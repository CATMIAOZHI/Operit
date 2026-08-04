package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.api.chat.llmprovider.DeepseekProvider
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.thinkingQualityLevelLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 思考程度档位 → reasoning_effort → 显示标签 映射测试。
 * 保证 UI 显示的档位与实际发送给 provider 的思考程度永远一致。
 */
class ThinkingQualityTest {

    @Test fun effortMapping_coversAllLevels() {
        val expected = listOf("low", "medium", "high", "xhigh", "max")
        (ApiPreferences.MIN_THINKING_QUALITY_LEVEL..ApiPreferences.MAX_THINKING_QUALITY_LEVEL)
            .forEach { level ->
                assertEquals(expected[level - ApiPreferences.MIN_THINKING_QUALITY_LEVEL],
                    ApiPreferences.thinkingQualityEffort(level))
            }
    }

    @Test fun effortMapping_clampsOutOfRangeLevels() {
        assertEquals("low", ApiPreferences.thinkingQualityEffort(0))
        assertEquals("max", ApiPreferences.thinkingQualityEffort(6))
    }

    @Test fun labelDerivesFromEffort_forAllLevels() {
        val expected = listOf("Low", "Medium", "High", "X-High", "Max")
        (ApiPreferences.MIN_THINKING_QUALITY_LEVEL..ApiPreferences.MAX_THINKING_QUALITY_LEVEL)
            .forEach { level ->
                assertEquals(expected[level - ApiPreferences.MIN_THINKING_QUALITY_LEVEL],
                    thinkingQualityLevelLabel(level))
            }
    }

    @Test fun labelHandlesXhighSpecialCase() {
        assertEquals("X-High", thinkingQualityLevelLabel(4))
    }

    @Test fun deepseekNormalizesMediumToHigh() {
        assertEquals("low", DeepseekProvider.normalizeDeepseekEffort("low"))
        assertEquals("high", DeepseekProvider.normalizeDeepseekEffort("medium"))
        assertEquals("high", DeepseekProvider.normalizeDeepseekEffort("high"))
        assertEquals("xhigh", DeepseekProvider.normalizeDeepseekEffort("xhigh"))
        assertEquals("max", DeepseekProvider.normalizeDeepseekEffort("max"))
    }
}