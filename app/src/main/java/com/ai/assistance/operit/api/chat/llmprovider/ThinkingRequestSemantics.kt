package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.preferences.ApiPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

sealed interface ThinkingRequestSummary {
    data class Effort(val value: String) : ThinkingRequestSummary

    data class BudgetTokens(val value: Int) : ThinkingRequestSummary

    data class CustomValue(val value: String) : ThinkingRequestSummary

    data object Enabled : ThinkingRequestSummary

    data object Disabled : ThinkingRequestSummary

    data object NotSent : ThinkingRequestSummary
}

/**
 * Resolves the effective thinking control that request builders send for the active provider.
 *
 * Provider request builders use the same effort/budget helpers below. The menu uses [resolve]
 * with the same persisted model parameters, so it does not claim that every provider receives the
 * global Low/Medium/High/X-High/Max preference unchanged.
 */
object ThinkingRequestSemantics {
    private val openRouterBudgets = listOf<Int?>(null, 1_024, 16_000, 32_000, 64_000)
    private val siliconFlowBudgets = listOf<Int?>(null, 4_096, 8_192, 16_384, 32_768)

    fun resolve(
        providerType: ApiProviderType,
        providerTypeId: String = providerType.name,
        isToolPkgProvider: Boolean = false,
        modelName: String,
        qualityLevel: Int,
        modelParameters: List<ModelParameter<*>>,
    ): ThinkingRequestSummary {
        if (isToolPkgProvider) {
            return ThinkingRequestSummary.Enabled
        }
        val effectiveProviderType =
            ApiProviderType.fromProviderTypeId(providerTypeId)
                ?: return ThinkingRequestSummary.NotSent
        return when (effectiveProviderType) {
            ApiProviderType.OPENAI,
            ApiProviderType.OPENAI_GENERIC ->
                enabledTextParameter(modelParameters, "reasoning_effort")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(ThinkingRequestSummary::Effort)
                    ?: ThinkingRequestSummary.Effort(
                        defaultReasoningEffort(effectiveProviderType, qualityLevel)!!,
                    )

            ApiProviderType.OPENAI_RESPONSES,
            ApiProviderType.OPENAI_RESPONSES_GENERIC ->
                resolveResponsesOverride(modelParameters)
                    ?: ThinkingRequestSummary.Effort(
                        defaultReasoningEffort(effectiveProviderType, qualityLevel)!!,
                    )

            ApiProviderType.DEEPSEEK -> {
                when (val override = resolveThinkingObjectOverride(modelParameters)) {
                    ThinkingRequestSummary.Disabled,
                    is ThinkingRequestSummary.CustomValue -> return override
                    else -> Unit
                }
                enabledTextParameter(modelParameters, "reasoning_effort")
                    ?.let(::textSummary)
                    ?: ThinkingRequestSummary.Effort(
                        defaultReasoningEffort(effectiveProviderType, qualityLevel)!!,
                    )
            }

            ApiProviderType.NVIDIA ->
                enabledTextParameter(modelParameters, "reasoning_effort")
                    ?.let(::textSummary)
                    ?: if (modelName.contains("gpt-oss", ignoreCase = true)) {
                        ThinkingRequestSummary.Effort(
                            defaultReasoningEffort(effectiveProviderType, qualityLevel)!!,
                        )
                    } else {
                        ThinkingRequestSummary.Enabled
                    }

            ApiProviderType.SILICONFLOW -> {
                when (val override = resolveBooleanParameter(modelParameters, "enable_thinking")) {
                    ThinkingRequestSummary.Disabled,
                    is ThinkingRequestSummary.CustomValue -> return override
                    else -> Unit
                }
                enabledParameter(modelParameters, "thinking_budget")
                    ?.currentValue
                    ?.let(::valueSummary)
                    ?: defaultBudgetTokens(
                        effectiveProviderType,
                        qualityLevel,
                        enabledMaxTokens(modelParameters),
                    )?.let(ThinkingRequestSummary::BudgetTokens)
                    ?: ThinkingRequestSummary.Enabled
            }

            ApiProviderType.OPENROUTER,
            ApiProviderType.NOUS_PORTAL ->
                resolveOpenRouterOverride(modelParameters)
                    ?: defaultBudgetTokens(
                        effectiveProviderType,
                        qualityLevel,
                        enabledMaxTokens(modelParameters),
                    )?.let(ThinkingRequestSummary::BudgetTokens)
                    ?: ThinkingRequestSummary.Enabled

            ApiProviderType.ALIYUN ->
                enabledParameter(modelParameters, "enable_thinking")
                    ?.currentValue
                    ?.let(::booleanSummary)
                    ?: ThinkingRequestSummary.Enabled

            ApiProviderType.ANTHROPIC,
            ApiProviderType.ANTHROPIC_GENERIC,
            ApiProviderType.GOOGLE,
            ApiProviderType.GEMINI_GENERIC -> ThinkingRequestSummary.Enabled

            ApiProviderType.MOONSHOT,
            ApiProviderType.MIMO ->
                resolveThinkingObjectOverride(modelParameters) ?: ThinkingRequestSummary.Enabled

            ApiProviderType.DOUBAO,
            ApiProviderType.MNN -> ThinkingRequestSummary.Enabled

            else ->
                enabledTextParameter(modelParameters, "reasoning_effort")
                    ?.let(::textSummary)
                    ?: ThinkingRequestSummary.NotSent
        }
    }

    fun defaultReasoningEffort(providerType: ApiProviderType, qualityLevel: Int): String? {
        val effort = ApiPreferences.thinkingQualityEffort(qualityLevel)
        return when (providerType) {
            ApiProviderType.DEEPSEEK -> normalizeDeepseekEffort(effort)
            ApiProviderType.OPENAI,
            ApiProviderType.OPENAI_GENERIC,
            ApiProviderType.OPENAI_RESPONSES,
            ApiProviderType.OPENAI_RESPONSES_GENERIC,
            ApiProviderType.NVIDIA -> effort
            else -> null
        }
    }

    fun normalizeDeepseekEffort(effort: String): String =
        if (effort == "medium") "high" else effort

    fun defaultBudgetTokens(
        providerType: ApiProviderType,
        qualityLevel: Int,
        maxTokens: Int?,
    ): Int? {
        val budgets =
            when (providerType) {
                ApiProviderType.OPENROUTER,
                ApiProviderType.NOUS_PORTAL -> openRouterBudgets
                ApiProviderType.SILICONFLOW -> siliconFlowBudgets
                else -> return null
            }
        val index =
            qualityLevel.coerceIn(
                ApiPreferences.MIN_THINKING_QUALITY_LEVEL,
                ApiPreferences.MAX_THINKING_QUALITY_LEVEL,
            ) - ApiPreferences.MIN_THINKING_QUALITY_LEVEL
        val requestedBudget = budgets[index] ?: return null
        val cappedBudget =
            maxTokens?.takeIf { it > 1 }?.let { minOf(requestedBudget, it - 1) }
                ?: requestedBudget
        return cappedBudget.takeIf { it > 0 }
    }

    fun enabledMaxTokens(modelParameters: List<ModelParameter<*>>): Int? =
        (modelParameters.firstOrNull { it.apiName == "max_tokens" && it.isEnabled }
            ?.currentValue as? Number)
            ?.toInt()
            ?.takeIf { it > 1 }

    private fun resolveResponsesOverride(
        modelParameters: List<ModelParameter<*>>,
    ): ThinkingRequestSummary? {
        val reasoningParameter = enabledParameter(modelParameters, "reasoning")
        if (reasoningParameter != null) {
            val raw = reasoningParameter.currentValue.toString().trim()
            val reasoningObject = parseObject(raw)
                ?: return ThinkingRequestSummary.CustomValue(raw)
            reasoningObject["effort"]?.let { effortElement ->
                val effort = (effortElement as? JsonPrimitive)?.contentOrNull?.trim()
                return if (!effort.isNullOrEmpty()) {
                    ThinkingRequestSummary.Effort(effort)
                } else {
                    ThinkingRequestSummary.CustomValue(effortElement.toString())
                }
            }
        }

        return enabledTextParameter(modelParameters, "reasoning_effort")
            ?.takeIf { it.isNotBlank() }
            ?.let(ThinkingRequestSummary::Effort)
    }

    private fun resolveOpenRouterOverride(
        modelParameters: List<ModelParameter<*>>,
    ): ThinkingRequestSummary? {
        val parameter = enabledParameter(modelParameters, "reasoning") ?: return null
        val raw = parameter.currentValue.toString().trim()
        val reasoningObject = parseObject(raw)
            ?: return ThinkingRequestSummary.CustomValue(raw)

        reasoningObject["effort"]?.let { effortElement ->
            val effort = (effortElement as? JsonPrimitive)?.contentOrNull?.trim()
            return if (!effort.isNullOrEmpty()) {
                ThinkingRequestSummary.Effort(effort)
            } else {
                ThinkingRequestSummary.CustomValue(effortElement.toString())
            }
        }
        reasoningObject["max_tokens"]?.let { budgetElement ->
            return (budgetElement as? JsonPrimitive)?.intOrNull
                ?.let(ThinkingRequestSummary::BudgetTokens)
                ?: ThinkingRequestSummary.CustomValue(budgetElement.toString())
        }
        reasoningObject["enabled"]?.let { enabledElement ->
            return (enabledElement as? JsonPrimitive)?.booleanOrNull?.let { enabled ->
                if (enabled) ThinkingRequestSummary.Enabled else ThinkingRequestSummary.Disabled
            } ?: ThinkingRequestSummary.CustomValue(enabledElement.toString())
        }
        return null
    }

    private fun resolveThinkingObjectOverride(
        modelParameters: List<ModelParameter<*>>,
    ): ThinkingRequestSummary? {
        val parameter = enabledParameter(modelParameters, "thinking") ?: return null
        val raw = parameter.currentValue.toString().trim()
        val thinkingObject = parseObject(raw)
            ?: return ThinkingRequestSummary.CustomValue(raw)
        val typeElement = thinkingObject["type"]
            ?: return ThinkingRequestSummary.CustomValue(raw)
        val type = (typeElement as? JsonPrimitive)?.contentOrNull?.trim()
            ?: return ThinkingRequestSummary.CustomValue(typeElement.toString())
        return when (type.lowercase()) {
            "enabled" -> ThinkingRequestSummary.Enabled
            "disabled" -> ThinkingRequestSummary.Disabled
            else -> ThinkingRequestSummary.CustomValue(type)
        }
    }

    private fun resolveBooleanParameter(
        modelParameters: List<ModelParameter<*>>,
        apiName: String,
    ): ThinkingRequestSummary? {
        val value = enabledParameter(modelParameters, apiName)?.currentValue ?: return null
        return booleanSummary(value)
    }

    private fun parseObject(raw: String) =
        runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()

    private fun enabledParameter(
        modelParameters: List<ModelParameter<*>>,
        apiName: String,
    ): ModelParameter<*>? =
        modelParameters.lastOrNull { it.apiName == apiName && it.isEnabled }

    private fun enabledTextParameter(
        modelParameters: List<ModelParameter<*>>,
        apiName: String,
    ): String? = enabledParameter(modelParameters, apiName)?.currentValue?.toString()?.trim()

    private fun valueSummary(value: Any): ThinkingRequestSummary =
        (value as? Number)?.toInt()?.let(ThinkingRequestSummary::BudgetTokens)
            ?: ThinkingRequestSummary.CustomValue(value.toString())

    private fun booleanSummary(value: Any): ThinkingRequestSummary =
        when (value) {
            true -> ThinkingRequestSummary.Enabled
            false -> ThinkingRequestSummary.Disabled
            else -> ThinkingRequestSummary.CustomValue(value.toString())
        }

    private fun textSummary(value: String): ThinkingRequestSummary =
        value.takeIf { it.isNotBlank() }?.let(ThinkingRequestSummary::Effort)
            ?: ThinkingRequestSummary.CustomValue(value)
}
