package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.llmprovider.DeepseekProvider
import com.ai.assistance.operit.api.chat.llmprovider.ThinkingRequestSemantics
import com.ai.assistance.operit.api.chat.llmprovider.ThinkingRequestSummary
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterCategory
import com.ai.assistance.operit.data.model.ParameterValueType
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.thinkingEffortLabelRes
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.thinkingQualityLevelLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 思考程度档位、provider 最终请求语义与显示标签的映射测试。
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

    @Test fun labelResourceDerivesFromEffort_forAllLevels() {
        val expected = listOf(
            R.string.thinking_quality_level_low,
            R.string.thinking_quality_level_medium,
            R.string.thinking_quality_level_high,
            R.string.thinking_quality_level_xhigh,
            R.string.thinking_quality_level_max,
        )
        (ApiPreferences.MIN_THINKING_QUALITY_LEVEL..ApiPreferences.MAX_THINKING_QUALITY_LEVEL)
            .forEach { level ->
                assertEquals(expected[level - ApiPreferences.MIN_THINKING_QUALITY_LEVEL],
                    thinkingQualityLevelLabelRes(level))
            }
    }

    @Test fun labelResourceHandlesXhighSpecialCase() {
        assertEquals(R.string.thinking_quality_level_xhigh, thinkingQualityLevelLabelRes(4))
    }

    @Test fun effectiveEffortUsesLocalizedResourceMapping() {
        assertEquals(R.string.thinking_quality_level_high, thinkingEffortLabelRes("high"))
        assertEquals(R.string.thinking_quality_level_max, thinkingEffortLabelRes("max"))
    }

    @Test fun customEffectiveEffortFallsBackToRawValue() {
        assertNull(thinkingEffortLabelRes("minimal"))
        assertNull(thinkingEffortLabelRes("none"))
    }

    @Test fun deepseekNormalizesMediumToHigh() {
        assertEquals("low", DeepseekProvider.normalizeDeepseekEffort("low"))
        assertEquals("high", DeepseekProvider.normalizeDeepseekEffort("medium"))
        assertEquals("high", DeepseekProvider.normalizeDeepseekEffort("high"))
        assertEquals("max", DeepseekProvider.normalizeDeepseekEffort("xhigh"))
        assertEquals("max", DeepseekProvider.normalizeDeepseekEffort("max"))
    }
    @Test fun requestSummary_usesDeepseekEffectiveEffort() {
        assertEquals(
            ThinkingRequestSummary.Effort("high"),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.DEEPSEEK,
                modelName = "deepseek-chat",
                qualityLevel = 2,
                modelParameters = emptyList(),
            ),
        )
    }

    @Test fun requestSummary_reportsEnabledForProviderWithoutIntensity() {
        assertEquals(
            ThinkingRequestSummary.Enabled,
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.GOOGLE,
                modelName = "gemini-2.5-pro",
                qualityLevel = 5,
                modelParameters = emptyList(),
            ),
        )
    }

    @Test fun requestSummary_reportsGeminiConfiguredThinkingIntensity() {
        assertEquals(
            ThinkingRequestSummary.BudgetTokens(32_768),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.GOOGLE,
                modelName = "gemini-2.5-pro",
                qualityLevel = 5,
                modelParameters = listOf(intParameter("thinking_budget", 32_768)),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.Effort("high"),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.GEMINI_GENERIC,
                modelName = "gemini-3.1-pro-preview",
                qualityLevel = 5,
                modelParameters = listOf(stringParameter("thinking_level", "high")),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.BudgetTokens(8_192),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.GOOGLE,
                modelName = "gemini-2.5-pro",
                qualityLevel = 3,
                modelParameters =
                    listOf(
                        stringParameter("thinking_level", "high"),
                        intParameter("thinking_budget", 8_192),
                    ),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.Effort("low"),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.GEMINI_GENERIC,
                modelName = "gemini-3.1-pro-preview",
                qualityLevel = 1,
                modelParameters =
                    listOf(
                        stringParameter("thinking_budget", "invalid"),
                        stringParameter("thinking_level", "low"),
                    ),
            ),
        )
    }

    @Test fun requestSummary_reportsAliyunConfiguredThinkingIntensity() {
        assertEquals(
            ThinkingRequestSummary.BudgetTokens(16_384),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ALIYUN,
                modelName = "qwen3-235b-a22b-thinking-2507",
                qualityLevel = 4,
                modelParameters =
                    listOf(
                        booleanParameter("enable_thinking", true),
                        intParameter("thinking_budget", 16_384),
                        stringParameter("reasoning_effort", "max"),
                    ),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.Effort("high"),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ALIYUN,
                modelName = "qwen3.5-plus",
                qualityLevel = 3,
                modelParameters = listOf(stringParameter("reasoning_effort", "high")),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.Disabled,
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ALIYUN,
                modelName = "qwen3.5-plus",
                qualityLevel = 5,
                modelParameters =
                    listOf(
                        booleanParameter("enable_thinking", false),
                        intParameter("thinking_budget", 32_768),
                        stringParameter("reasoning_effort", "max"),
                    ),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.CustomValue("invalid"),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ALIYUN,
                modelName = "qwen3-235b-a22b-thinking-2507",
                qualityLevel = 3,
                modelParameters =
                    listOf(
                        stringParameter("thinking_budget", "invalid"),
                        stringParameter("reasoning_effort", "high"),
                    ),
            ),
        )
    }

    @Test fun requestSummary_reportsAnthropicLegacyBudgetSentByClaudeProvider() {
        assertEquals(
            ThinkingRequestSummary.BudgetTokens(1_024),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ANTHROPIC,
                modelName = "claude-3-opus-20240229",
                qualityLevel = 5,
                modelParameters = emptyList(),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.BudgetTokens(2_048),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ANTHROPIC,
                modelName = "claude-3-opus-20240229",
                qualityLevel = 1,
                modelParameters = listOf(intParameter("budget_tokens", 2_048)),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.BudgetTokens(512),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ANTHROPIC_GENERIC,
                modelName = "legacy-claude-proxy",
                qualityLevel = 3,
                modelParameters = listOf(intParameter("max_tokens", 512)),
            ),
        )
    }

    @Test fun requestSummary_reportsEnabledForAnthropicAdaptiveThinking() {
        assertEquals(
            ThinkingRequestSummary.Enabled,
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ANTHROPIC,
                modelName = "claude-opus-4-6",
                qualityLevel = 5,
                modelParameters = listOf(intParameter("budget_tokens", 2_048)),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.Effort("max"),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ANTHROPIC,
                modelName = "claude-opus-4-6",
                qualityLevel = 5,
                modelParameters =
                    listOf(objectParameter("output_config", "{\"effort\":\"max\"}")),
            ),
        )
    }

    @Test fun requestSummary_respectsAnthropicExplicitThinkingObject() {
        assertEquals(
            ThinkingRequestSummary.Disabled,
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ANTHROPIC,
                modelName = "claude-3-opus-20240229",
                qualityLevel = 5,
                modelParameters = listOf(objectParameter("thinking", "{\"type\":\"disabled\"}")),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.BudgetTokens(2_048),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ANTHROPIC,
                modelName = "claude-3-opus-20240229",
                qualityLevel = 1,
                modelParameters =
                    listOf(
                        objectParameter(
                            "thinking",
                            "{\"type\":\"enabled\",\"budget_tokens\":2048}",
                        )
                    ),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.Enabled,
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ANTHROPIC,
                modelName = "claude-3-opus-20240229",
                qualityLevel = 1,
                modelParameters = listOf(objectParameter("thinking", "{\"type\":\"adaptive\"}")),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.Disabled,
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ANTHROPIC,
                modelName = "claude-opus-4-6",
                qualityLevel = 5,
                modelParameters =
                    listOf(
                        objectParameter("thinking", "{\"type\":\"disabled\"}"),
                        objectParameter("output_config", "{\"effort\":\"max\"}"),
                    ),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.BudgetTokens(4_096),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ANTHROPIC,
                modelName = "claude-3-opus-20240229",
                qualityLevel = 3,
                modelParameters =
                    listOf(
                        objectParameter(
                            "thinking",
                            "{\"type\":\"enabled\",\"budget_tokens\":4096}",
                        ),
                        objectParameter("output_config", "{\"effort\":\"max\"}"),
                    ),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.Effort("max"),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ANTHROPIC,
                modelName = "claude-opus-4-6",
                qualityLevel = 5,
                modelParameters =
                    listOf(
                        objectParameter("thinking", "{\"type\":\"adaptive\"}"),
                        objectParameter("output_config", "{\"effort\":\"max\"}"),
                    ),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.CustomValue("custom"),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ANTHROPIC,
                modelName = "claude-opus-4-6",
                qualityLevel = 5,
                modelParameters =
                    listOf(
                        objectParameter("thinking", "{\"type\":\"custom\"}"),
                        objectParameter("output_config", "{\"effort\":\"max\"}"),
                    ),
            ),
        )
    }

    @Test fun requestSummary_ignoresMalformedAnthropicThinkingObjectLikeProvider() {
        assertEquals(
            ThinkingRequestSummary.BudgetTokens(1_024),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ANTHROPIC,
                modelName = "claude-3-opus-20240229",
                qualityLevel = 5,
                modelParameters = listOf(objectParameter("thinking", "not-json")),
            ),
        )
        assertEquals(
            ThinkingRequestSummary.Enabled,
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.ANTHROPIC,
                modelName = "claude-opus-4-6",
                qualityLevel = 5,
                modelParameters = listOf(objectParameter("output_config", "not-json")),
            ),
        )
    }

    @Test fun requestSummary_preservesCustomReasoningEffort() {
        assertEquals(
            ThinkingRequestSummary.Effort("max"),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.OPENAI,
                modelName = "gpt-5.6",
                qualityLevel = 1,
                modelParameters = listOf(stringParameter("reasoning_effort", "max")),
            ),
        )
    }

    @Test fun requestSummary_capsOpenRouterBudgetByMaxTokens() {
        assertEquals(
            ThinkingRequestSummary.BudgetTokens(4_095),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.OPENROUTER,
                modelName = "openrouter/auto",
                qualityLevel = 3,
                modelParameters = listOf(intParameter("max_tokens", 4_096)),
            ),
        )
    }

    @Test fun requestSummary_usesOpenRouterBudgetForNousPortal() {
        assertEquals(
            ThinkingRequestSummary.BudgetTokens(1_024),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.NOUS_PORTAL,
                modelName = "Hermes-4",
                qualityLevel = 2,
                modelParameters = emptyList(),
            ),
        )
    }

    @Test fun requestSummary_reportsEnabledForToolPkgProvider() {
        assertEquals(
            ThinkingRequestSummary.Enabled,
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.OTHER,
                providerTypeId = "sample_toolpkg_provider",
                isToolPkgProvider = true,
                modelName = "plugin-model",
                qualityLevel = 5,
                modelParameters = emptyList(),
            ),
        )
    }

    @Test fun requestSummary_reportsNotSentForUnknownProviderId() {
        assertEquals(
            ThinkingRequestSummary.NotSent,
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.OTHER,
                providerTypeId = "missing_provider",
                isToolPkgProvider = false,
                modelName = "missing-model",
                qualityLevel = 5,
                modelParameters = emptyList(),
            ),
        )
    }

    @Test fun requestSummary_reportsNotSentForBlankProviderId() {
        assertEquals(
            ThinkingRequestSummary.NotSent,
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.OTHER,
                providerTypeId = "",
                isToolPkgProvider = false,
                modelName = "missing-model",
                qualityLevel = 5,
                modelParameters = emptyList(),
            ),
        )
    }

    @Test fun requestSummary_respectsSiliconFlowDisabledOverride() {
        assertEquals(
            ThinkingRequestSummary.Disabled,
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.SILICONFLOW,
                modelName = "deepseek-ai/DeepSeek-R1",
                qualityLevel = 5,
                modelParameters = listOf(booleanParameter("enable_thinking", false)),
            ),
        )
    }

    @Test fun requestSummary_respectsDeepseekThinkingObjectOverride() {
        assertEquals(
            ThinkingRequestSummary.Disabled,
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.DEEPSEEK,
                modelName = "deepseek-chat",
                qualityLevel = 5,
                modelParameters = listOf(objectParameter("thinking", "{\"type\":\"disabled\"}")),
            ),
        )
    }

    @Test fun requestSummary_handlesNonPrimitiveReasoningField() {
        assertEquals(
            ThinkingRequestSummary.CustomValue("{\"custom\":true}"),
            ThinkingRequestSemantics.resolve(
                providerType = ApiProviderType.OPENAI_RESPONSES,
                modelName = "gpt-5.6",
                qualityLevel = 3,
                modelParameters =
                    listOf(objectParameter("reasoning", "{\"effort\":{\"custom\":true}}")),
            ),
        )
    }

    private fun stringParameter(apiName: String, value: String) =
        ModelParameter(
            id = apiName,
            name = apiName,
            apiName = apiName,
            defaultValue = value,
            currentValue = value,
            isEnabled = true,
            valueType = ParameterValueType.STRING,
            category = ParameterCategory.OTHER,
        )

    private fun intParameter(apiName: String, value: Int) =
        ModelParameter(
            id = apiName,
            name = apiName,
            apiName = apiName,
            defaultValue = value,
            currentValue = value,
            isEnabled = true,
            valueType = ParameterValueType.INT,
            category = ParameterCategory.OTHER,
        )

    private fun booleanParameter(apiName: String, value: Boolean) =
        ModelParameter(
            id = apiName,
            name = apiName,
            apiName = apiName,
            defaultValue = value,
            currentValue = value,
            isEnabled = true,
            valueType = ParameterValueType.BOOLEAN,
            category = ParameterCategory.OTHER,
        )

    private fun objectParameter(apiName: String, value: String) =
        ModelParameter(
            id = apiName,
            name = apiName,
            apiName = apiName,
            defaultValue = value,
            currentValue = value,
            isEnabled = true,
            valueType = ParameterValueType.OBJECT,
            category = ParameterCategory.OTHER,
        )
}
