package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterCategory
import com.ai.assistance.operit.data.model.ParameterValueType
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.util.stream.Stream

internal data class FunctionalReasoningRequest(
    val enableThinking: Boolean,
    val modelParameters: List<ModelParameter<*>>,
)

internal const val SUPPRESS_AUTOMATIC_REASONING_API_NAME =
    "__operit_internal_suppress_automatic_reasoning"

internal data class AutomaticReasoningRequestParameters(
    val suppressAutomaticReasoning: Boolean,
    val modelParameters: List<ModelParameter<*>>,
)

internal fun consumeAutomaticReasoningSuppression(
    modelParameters: List<ModelParameter<*>>,
): AutomaticReasoningRequestParameters =
    AutomaticReasoningRequestParameters(
        suppressAutomaticReasoning =
            modelParameters.any {
                it.apiName == SUPPRESS_AUTOMATIC_REASONING_API_NAME && it.isEnabled
            },
        modelParameters =
            modelParameters.filterNot {
                it.apiName == SUPPRESS_AUTOMATIC_REASONING_API_NAME
            },
    )

/**
 * Applies a function-model reasoning choice at the service boundary.
 *
 * Functional features obtain their service through MultiServiceManager, but their call sites are
 * intentionally heterogeneous (summary, media analysis, memory, grep, UI automation, and so on).
 * Keeping the override here makes the persisted function mapping effective for every call path,
 * including callers that rely on AIService.sendMessage defaults.
 */
internal class FunctionalReasoningAIService(
    private val delegate: AIService,
    private val providerType: ApiProviderType?,
    private val modelName: String,
    private val thinkingQualityLevel: Int,
) : AIService by delegate {

    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onUsageReported: (suspend (ProviderUsageSnapshot, attempt: Int) -> Unit)?,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean,
        statsCategory: TokenStatCategory?,
    ): Stream<String> {
        val request =
            buildFunctionalReasoningRequest(
                providerType = providerType,
                modelName = modelName,
                modelParameters = modelParameters,
                thinkingQualityLevel = thinkingQualityLevel,
            )
        return delegate.sendMessage(
            context = context,
            chatHistory = chatHistory,
            modelParameters = request.modelParameters,
            enableThinking = request.enableThinking,
            stream = stream,
            availableTools = availableTools,
            preserveThinkInHistory = preserveThinkInHistory,
            onTokensUpdated = onTokensUpdated,
            onUsageReported = onUsageReported,
            onNonFatalError = onNonFatalError,
            enableRetry = enableRetry,
            statsCategory = statsCategory,
        )
    }
}

internal fun AIService.withFunctionalReasoning(
    config: ModelConfigData,
    thinkingQualityLevel: Int,
): AIService {
    val providerType = ApiProviderType.fromProviderTypeId(config.apiProviderTypeId)
    return FunctionalReasoningAIService(
        delegate = this,
        providerType = providerType,
        modelName = config.modelName,
        thinkingQualityLevel = thinkingQualityLevel,
    )
}

internal fun buildFunctionalReasoningRequest(
    providerType: ApiProviderType?,
    modelName: String,
    modelParameters: List<ModelParameter<*>>,
    thinkingQualityLevel: Int,
): FunctionalReasoningRequest {
    val effectiveLevel = normalizeFunctionalThinkingQualityLevel(thinkingQualityLevel)
    val parametersWithoutConflictingControls =
        modelParameters.filterNot {
            it.apiName in
                functionalReasoningApiNames(
                    providerType = providerType,
                    modelName = modelName,
                    effectiveLevel = effectiveLevel,
                    modelParameters = modelParameters,
                )
        }
    val overrideParameters =
        buildFunctionalReasoningParameters(
            providerType = providerType,
            modelName = modelName,
            effectiveLevel = effectiveLevel,
            modelParameters = parametersWithoutConflictingControls,
            sourceModelParameters = modelParameters,
        )
    val enableThinking =
        when {
            providerType in
                setOf(
                    ApiProviderType.OPENAI,
                    ApiProviderType.OPENAI_RESPONSES,
                ) -> supportsOpenAiReasoningEffortModel(modelName)
            providerType in
                setOf(
                    ApiProviderType.OPENAI_GENERIC,
                    ApiProviderType.OPENAI_RESPONSES_GENERIC,
                ) -> false
            providerType in setOf(ApiProviderType.GOOGLE, ApiProviderType.GEMINI_GENERIC) ->
                supportsGeminiFunctionalThinking(modelName)
            providerType == ApiProviderType.ALIYUN ->
                aliyunFunctionalReasoningStrategy(modelName) != null
            providerType == ApiProviderType.SILICONFLOW ->
                siliconFlowSupportsThinkingBudget(modelName)
            providerType in
                setOf(ApiProviderType.ANTHROPIC, ApiProviderType.ANTHROPIC_GENERIC) ->
                when (claudeFunctionalThinkingMode(modelName)) {
                    ClaudeFunctionalThinkingMode.ADAPTIVE -> true
                    ClaudeFunctionalThinkingMode.MANUAL ->
                        anthropicFunctionalBudget(
                            effectiveLevel,
                            providerType,
                            modelName,
                            parametersWithoutConflictingControls,
                        ) != null
                    null -> false
                }
            else -> true
        }

    return FunctionalReasoningRequest(
        enableThinking = enableThinking,
        modelParameters = parametersWithoutConflictingControls + overrideParameters,
    )
}

internal fun normalizeFunctionalThinkingQualityLevel(qualityLevel: Int): Int =
    qualityLevel.coerceIn(
        ApiPreferences.MIN_THINKING_QUALITY_LEVEL,
        ApiPreferences.MAX_THINKING_QUALITY_LEVEL,
    )

internal fun siliconFlowSupportsThinkingToggle(modelName: String): Boolean =
    modelName.trim().lowercase().removePrefix("pro/") in SILICONFLOW_TOGGLE_MODELS

private fun buildFunctionalReasoningParameters(
    providerType: ApiProviderType?,
    modelName: String,
    effectiveLevel: Int,
    modelParameters: List<ModelParameter<*>>,
    sourceModelParameters: List<ModelParameter<*>>,
): List<ModelParameter<*>> {
    val effort = ApiPreferences.thinkingQualityEffort(effectiveLevel)
    return when (providerType) {
        ApiProviderType.OPENAI ->
            if (supportsOpenAiReasoningEffortModel(modelName)) {
                listOf(
                    functionalStringParameter(
                        "reasoning_effort",
                        openAiFunctionalEffort(modelName, effectiveLevel),
                    )
                )
            } else {
                listOf(functionalAutomaticReasoningSuppressionParameter())
            }

        ApiProviderType.OPENAI_GENERIC ->
            listOf(functionalAutomaticReasoningSuppressionParameter())

        ApiProviderType.OPENAI_RESPONSES ->
            if (supportsOpenAiReasoningEffortModel(modelName)) {
                listOf(
                    functionalResponsesReasoningParameter(
                        sourceModelParameters,
                        openAiFunctionalEffort(modelName, effectiveLevel),
                    )
                )
            } else {
                listOf(functionalAutomaticReasoningSuppressionParameter())
            }

        ApiProviderType.OPENAI_RESPONSES_GENERIC ->
            listOf(functionalAutomaticReasoningSuppressionParameter())

        ApiProviderType.DEEPSEEK ->
            deepSeekFunctionalEffort(modelName, effectiveLevel)
                ?.let { listOf(functionalStringParameter("reasoning_effort", it)) }
                ?: listOf(functionalAutomaticReasoningSuppressionParameter())

        ApiProviderType.NVIDIA ->
            if (modelName.contains("gpt-oss", ignoreCase = true)) {
                listOf(
                    functionalStringParameter(
                        "reasoning_effort",
                        when (effectiveLevel) {
                            1 -> "low"
                            2 -> "medium"
                            else -> "high"
                        },
                    )
                )
            } else {
                emptyList()
            }

        ApiProviderType.OPENROUTER ->
            listOf(
                functionalMergedObjectParameter(
                    apiName = "reasoning",
                    modelParameters = sourceModelParameters,
                    values = mapOf("effort" to effort),
                    removedKeys = setOf("max_tokens"),
                )
            )

        ApiProviderType.SILICONFLOW ->
            if (siliconFlowSupportsThinkingBudget(modelName)) {
                buildList {
                    if (siliconFlowSupportsThinkingToggle(modelName)) {
                        add(functionalBooleanParameter("enable_thinking", true))
                    }
                    add(
                        functionalIntParameter(
                            "thinking_budget",
                            functionalTokenBudget(
                                effectiveLevel,
                                modelParameters,
                                SILICONFLOW_BUDGETS,
                            ),
                        )
                    )
                }
            } else {
                emptyList()
            }

        ApiProviderType.ALIYUN ->
            when (aliyunFunctionalReasoningStrategy(modelName)) {
                AliyunFunctionalReasoningStrategy.EFFORT ->
                    listOf(
                        functionalBooleanParameter("enable_thinking", true),
                        functionalStringParameter(
                            "reasoning_effort",
                            aliyunFunctionalEffort(modelName, effectiveLevel),
                        )
                    )
                AliyunFunctionalReasoningStrategy.BUDGET ->
                    listOf(
                        functionalBooleanParameter("enable_thinking", true),
                        functionalIntParameter(
                            "thinking_budget",
                            functionalTokenBudget(
                                effectiveLevel,
                                modelParameters,
                                ALIYUN_BUDGETS,
                            ),
                        )
                    )
                null -> emptyList()
            }

        ApiProviderType.ANTHROPIC,
        ApiProviderType.ANTHROPIC_GENERIC ->
            when (claudeFunctionalThinkingMode(modelName)) {
                ClaudeFunctionalThinkingMode.ADAPTIVE ->
                    listOf(
                        functionalMergedObjectParameter(
                            apiName = "thinking",
                            modelParameters = sourceModelParameters,
                            values = mapOf("type" to "adaptive"),
                            defaultValues = mapOf("display" to "summarized"),
                            removedKeys = setOf("budget_tokens"),
                        ),
                        functionalMergedObjectParameter(
                            apiName = "output_config",
                            modelParameters = sourceModelParameters,
                            values =
                                mapOf(
                                    "effort" to
                                        claudeAdaptiveFunctionalEffort(modelName, effectiveLevel)
                                ),
                        ),
                    )
                ClaudeFunctionalThinkingMode.MANUAL ->
                    buildList {
                        if (isClaudeOpus45Model(modelName)) {
                            add(
                                functionalMergedObjectParameter(
                                    apiName = "output_config",
                                    modelParameters = sourceModelParameters,
                                    values =
                                        mapOf(
                                            "effort" to
                                                claudeOpus45FunctionalEffort(effectiveLevel)
                                        ),
                                )
                            )
                        } else {
                            functionalPreservedObjectParameter(
                                    apiName = "output_config",
                                    modelParameters = sourceModelParameters,
                                )
                                ?.let(::add)
                        }
                        anthropicFunctionalBudget(
                                effectiveLevel,
                                providerType,
                                modelName,
                                modelParameters,
                            )
                            ?.let { budgetTokens ->
                                add(
                                    functionalMergedObjectParameter(
                                        apiName = "thinking",
                                        modelParameters = sourceModelParameters,
                                        values =
                                            mapOf(
                                                "type" to "enabled",
                                                "budget_tokens" to budgetTokens,
                                            ),
                                    )
                                )
                            }
                    }
                null -> emptyList()
            }

        ApiProviderType.GOOGLE,
        ApiProviderType.GEMINI_GENERIC ->
            buildGeminiFunctionalReasoningParameters(
                modelName = modelName,
                effectiveLevel = effectiveLevel,
                modelParameters = modelParameters,
            )

        else -> emptyList()
    }
}

private fun buildGeminiFunctionalReasoningParameters(
    modelName: String,
    effectiveLevel: Int,
    modelParameters: List<ModelParameter<*>>,
): List<ModelParameter<*>> {
    val normalizedModelName = modelName.trim().lowercase().substringAfterLast('/')
    return when {
        isGemini25ProThinkingModel(normalizedModelName) ->
            listOf(
                functionalIntParameter(
                    "thinking_budget",
                    functionalTokenBudget(effectiveLevel, modelParameters, GEMINI_25_PRO_BUDGETS),
                )
            )
        isGemini25FlashThinkingModel(normalizedModelName) ->
            listOf(
                functionalIntParameter(
                    "thinking_budget",
                    functionalTokenBudget(effectiveLevel, modelParameters, GEMINI_25_FLASH_BUDGETS),
                )
            )
        supportsGemini3FunctionalThinking(normalizedModelName) ->
            listOf(
                functionalStringParameter(
                    "thinking_level",
                    gemini3ThinkingLevel(normalizedModelName, effectiveLevel),
                )
            )
        else -> emptyList()
    }
}

private fun gemini3ThinkingLevel(modelName: String, qualityLevel: Int): String {
    return when {
        modelName.contains("3.1-flash-image") ||
            modelName.contains("3.1-flash-lite-image") ->
            if (qualityLevel == ApiPreferences.MIN_THINKING_QUALITY_LEVEL) "minimal" else "high"
        modelName.contains("gemini-3-pro") ->
            if (qualityLevel <= ApiPreferences.DEFAULT_THINKING_QUALITY_LEVEL) "low" else "high"
        modelName.contains("pro") ->
            when (qualityLevel) {
                1, 2 -> "low"
                3 -> "medium"
                else -> "high"
            }
        else ->
            when (qualityLevel) {
                1 -> "minimal"
                2 -> "low"
                3 -> "medium"
                else -> "high"
            }
    }
}

private fun supportsGeminiFunctionalThinking(modelName: String): Boolean {
    val normalizedModelName = modelName.trim().lowercase().substringAfterLast('/')
    return isGemini25ProThinkingModel(normalizedModelName) ||
        isGemini25FlashThinkingModel(normalizedModelName) ||
        supportsGemini3FunctionalThinking(normalizedModelName)
}

private fun isGemini25ProThinkingModel(normalizedModelName: String): Boolean =
    normalizedModelName.contains("gemini-2.5-pro") &&
        !normalizedModelName.contains("-image")

private fun isGemini25FlashThinkingModel(normalizedModelName: String): Boolean =
    normalizedModelName.contains("gemini-2.5-flash") &&
        !normalizedModelName.contains("-image")

private fun supportsGemini3FunctionalThinking(normalizedModelName: String): Boolean =
    normalizedModelName in GEMINI_3_FUNCTIONAL_THINKING_MODELS

private enum class AliyunFunctionalReasoningStrategy {
    EFFORT,
    BUDGET,
}

private fun aliyunFunctionalReasoningStrategy(
    modelName: String,
): AliyunFunctionalReasoningStrategy? {
    val normalized = modelName.trim().lowercase()
    return when {
        normalized.contains("deepseek-v4") ||
            normalized.contains("kimi/kimi-k3") ||
            normalized.matches(Regex("(?:.*/)?glm-5(?:\\.[12])?(?:$|[-_].*)")) ||
            normalized.substringAfterLast('/') == "qwen3.8-max-preview" ->
            AliyunFunctionalReasoningStrategy.EFFORT
        aliyunQwenSupportsThinkingBudget(normalized) ->
            AliyunFunctionalReasoningStrategy.BUDGET
        else -> null
    }
}

private fun aliyunQwenSupportsThinkingBudget(normalizedModelName: String): Boolean {
    val modelId = normalizedModelName.substringAfterLast('/')
    return when {
        modelId == "qwen-plus" || modelId == "qwen-plus-latest" -> true
        modelId.isAliyunSnapshotAtLeast("qwen-plus", "2025-04-28") -> true
        modelId == "qwen-flash" -> true
        modelId.isAliyunSnapshotAtLeast("qwen-flash", "2025-07-28") -> true
        modelId == "qwen-turbo" || modelId.matches(ALIYUN_QWEN_TURBO_SNAPSHOT) -> true
        modelId in ALIYUN_QWEN3_THINKING_MODELS -> true
        modelId == "qwq-plus" -> true
        else -> false
    }
}

private fun String.isAliyunSnapshotAtLeast(modelPrefix: String, earliestDate: String): Boolean {
    val snapshotDate =
        Regex("${Regex.escape(modelPrefix)}-(\\d{4}-\\d{2}-\\d{2})")
            .matchEntire(this)
            ?.groupValues
            ?.get(1)
            ?: return false
    return snapshotDate >= earliestDate
}

private fun siliconFlowSupportsThinkingBudget(modelName: String): Boolean {
    val normalized = modelName.trim().lowercase().removePrefix("pro/")
    return siliconFlowSupportsThinkingToggle(normalized) ||
        normalized.matches(Regex("deepseek-ai/deepseek-r1(?:$|[-_].*)")) ||
        normalized.matches(Regex("qwen/qwq(?:$|[-_].*)"))
}

private fun deepSeekFunctionalEffort(modelName: String, qualityLevel: Int): String? {
    val normalized = modelName.trim().lowercase().substringAfterLast('/')
    if (
        !normalized.matches(
            Regex("deepseek-v4-(?:flash|pro)(?:$|[-_].*)")
        )
    ) {
        return null
    }
    return when (qualityLevel) {
        1 -> "low"
        2, 3 -> "high"
        else -> "max"
    }
}

private fun aliyunFunctionalEffort(modelName: String, qualityLevel: Int): String {
    val normalized = modelName.trim().lowercase()
    val requestedEffort = ApiPreferences.thinkingQualityEffort(qualityLevel)
    return when {
        normalized.contains("kimi/kimi-k3") -> "max"
        normalized.contains("qwen3.8-max-preview") -> requestedEffort
        normalized.matches(Regex("(?:.*/)?glm-5\\.2(?:$|[-_].*)")) -> requestedEffort
        normalized.matches(Regex("(?:.*/)?glm-5(?:\\.1)?(?:$|[-_].*)")) ->
            if (qualityLevel == ApiPreferences.MAX_THINKING_QUALITY_LEVEL) {
                "xhigh"
            } else {
                requestedEffort
            }
        else -> if (qualityLevel >= 4) "max" else "high"
    }
}

private fun functionalReasoningApiNames(
    providerType: ApiProviderType?,
    modelName: String,
    effectiveLevel: Int,
    modelParameters: List<ModelParameter<*>>,
): Set<String> {
    return when (providerType) {
        ApiProviderType.OPENAI -> setOf("reasoning_effort")

        ApiProviderType.OPENAI_GENERIC -> emptySet()

        ApiProviderType.DEEPSEEK ->
            if (deepSeekFunctionalEffort(modelName, ApiPreferences.DEFAULT_THINKING_QUALITY_LEVEL) != null) {
                setOf("reasoning_effort")
            } else {
                emptySet()
            }

        ApiProviderType.NVIDIA -> setOf("reasoning_effort")

        ApiProviderType.OPENAI_RESPONSES -> setOf("reasoning", "reasoning_effort")

        ApiProviderType.OPENAI_RESPONSES_GENERIC -> emptySet()

        ApiProviderType.OPENROUTER -> setOf("reasoning")
        ApiProviderType.SILICONFLOW ->
            if (siliconFlowSupportsThinkingBudget(modelName)) {
                setOf("enable_thinking", "thinking_budget")
            } else {
                emptySet()
            }
        ApiProviderType.ALIYUN ->
            if (aliyunFunctionalReasoningStrategy(modelName) != null) {
                setOf("enable_thinking", "thinking_budget", "reasoning_effort")
            } else {
                emptySet()
            }
        ApiProviderType.ANTHROPIC,
        ApiProviderType.ANTHROPIC_GENERIC ->
            when (claudeFunctionalThinkingMode(modelName)) {
                ClaudeFunctionalThinkingMode.ADAPTIVE ->
                    setOf(
                        "thinking",
                        "budget_tokens",
                        "output_config",
                        "temperature",
                        "top_p",
                        "top_k",
                    )
                ClaudeFunctionalThinkingMode.MANUAL -> {
                    val controlNames =
                        mutableSetOf(
                            "thinking",
                            "budget_tokens",
                            "output_config",
                        )
                    if (
                        anthropicFunctionalBudget(
                            effectiveLevel = effectiveLevel,
                            providerType = providerType,
                            modelName = modelName,
                            modelParameters = modelParameters,
                        ) != null
                    ) {
                        controlNames += setOf("temperature", "top_p", "top_k")
                    }
                    controlNames
                }
                null -> emptySet()
            }
        ApiProviderType.GOOGLE,
        ApiProviderType.GEMINI_GENERIC -> setOf("thinking_budget", "thinking_level")

        else -> emptySet()
    }
}

private fun functionalTokenBudget(
    qualityLevel: Int,
    modelParameters: List<ModelParameter<*>>,
    budgets: List<Int>,
): Int {
    val index =
        qualityLevel.coerceIn(
            ApiPreferences.MIN_THINKING_QUALITY_LEVEL,
            ApiPreferences.MAX_THINKING_QUALITY_LEVEL,
        ) - ApiPreferences.MIN_THINKING_QUALITY_LEVEL
    val requestedBudget = budgets[index]
    val maxTokens =
        (modelParameters.lastOrNull { it.apiName == "max_tokens" && it.isEnabled }
            ?.currentValue as? Number)
            ?.toInt()
            ?.takeIf { it > 1 }
    return maxTokens?.let { minOf(requestedBudget, it - 1) } ?: requestedBudget
}

internal fun prefersClaudeAdaptiveThinkingModel(modelName: String): Boolean {
    val normalized = normalizeClaudeModelName(modelName)
    return normalized == "claude-mythos-preview" ||
        claudeFamilyVersion(normalized, "fable") in CLAUDE_ADAPTIVE_FABLE_VERSIONS ||
        claudeFamilyVersion(normalized, "mythos") in CLAUDE_ADAPTIVE_MYTHOS_VERSIONS ||
        claudeFamilyVersion(normalized, "opus") in CLAUDE_ADAPTIVE_OPUS_VERSIONS ||
        claudeFamilyVersion(normalized, "sonnet") in CLAUDE_ADAPTIVE_SONNET_VERSIONS
}

private enum class ClaudeFunctionalThinkingMode {
    ADAPTIVE,
    MANUAL,
}

private fun claudeFunctionalThinkingMode(modelName: String): ClaudeFunctionalThinkingMode? {
    if (prefersClaudeAdaptiveThinkingModel(modelName)) {
        return ClaudeFunctionalThinkingMode.ADAPTIVE
    }

    val normalized = normalizeClaudeModelName(modelName)
    val supportedManualVersions =
        mapOf(
            "haiku" to CLAUDE_MANUAL_HAIKU_VERSIONS,
            "opus" to CLAUDE_MANUAL_OPUS_VERSIONS,
            "sonnet" to CLAUDE_MANUAL_SONNET_VERSIONS,
        )
    val isKnownManualModel =
        supportedManualVersions.any { (family, versions) ->
            claudeFamilyVersion(normalized, family) in versions
        }
    return if (isKnownManualModel) ClaudeFunctionalThinkingMode.MANUAL else null
}

private fun claudeAdaptiveFunctionalEffort(modelName: String, qualityLevel: Int): String {
    val normalized = normalizeClaudeModelName(modelName)
    val supportsXHigh =
        claudeFamilyVersion(normalized, "fable") in CLAUDE_XHIGH_FABLE_VERSIONS ||
            claudeFamilyVersion(normalized, "mythos") in CLAUDE_XHIGH_MYTHOS_VERSIONS ||
            claudeFamilyVersion(normalized, "opus") in CLAUDE_XHIGH_OPUS_VERSIONS ||
            claudeFamilyVersion(normalized, "sonnet") in CLAUDE_XHIGH_SONNET_VERSIONS
    return if (qualityLevel == 4 && !supportsXHigh) {
        "max"
    } else {
        ApiPreferences.thinkingQualityEffort(qualityLevel)
    }
}

private fun isClaudeOpus45Model(modelName: String): Boolean =
    claudeFamilyVersion(normalizeClaudeModelName(modelName), "opus") == (4 to 5)

private fun claudeOpus45FunctionalEffort(qualityLevel: Int): String =
    when (qualityLevel) {
        1 -> "low"
        2 -> "medium"
        else -> "high"
    }

internal fun normalizeClaudeModelName(value: String): String =
    value
        .trim()
        .lowercase()
        .replace(Regex("(?<=[a-z])(?=\\d)|(?<=\\d)(?=[a-z])"), "-")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

internal fun hasClaudeFamilyAtLeast(
    normalizedModelName: String,
    family: String,
    minMajor: Int,
    minMinor: Int,
): Boolean {
    val version = claudeFamilyVersion(normalizedModelName, family) ?: return false
    val (major, minor) = version
    return major > minMajor || major == minMajor && minor >= minMinor
}

private fun claudeFamilyVersion(
    normalizedModelName: String,
    family: String,
): Pair<Int, Int>? {
    val parts = normalizedModelName.split('-').filter(String::isNotEmpty)
    val familyIndex = parts.indexOf(family)
    if (familyIndex == -1) return null

    val beforeFamily =
        parts
            .take(familyIndex)
            .asReversed()
            .takeWhile(String::isShortVersionPart)
            .asReversed()
            .takeLast(2)
    numericClaudeVersion(beforeFamily)?.let { return it }

    val afterFamily =
        parts.drop(familyIndex + 1).takeWhile(String::isShortVersionPart).take(2)
    return numericClaudeVersion(afterFamily)
}

private fun String.isShortVersionPart(): Boolean = all(Char::isDigit) && length < 8

private fun numericClaudeVersion(parts: List<String>): Pair<Int, Int>? {
    val major = parts.firstOrNull()?.toIntOrNull() ?: return null
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return major to minor
}

private fun openAiFunctionalEffort(modelName: String, qualityLevel: Int): String {
    val modelId = modelName.trim().lowercase().substringAfterLast('/')
    val requestedEffort = ApiPreferences.thinkingQualityEffort(qualityLevel)
    return when {
        isGpt56Model(modelId) -> requestedEffort
        isGpt5ProModel(modelId) -> "high"
        isBaseGpt5Model(modelId) || isGpt5MiniNanoModel(modelId) || isGpt51Model(modelId) ->
            when (qualityLevel) {
                1 -> "low"
                2 -> "medium"
                else -> "high"
            }
        isGpt54MiniNanoModel(modelId) || isGpt51To54CodexModel(modelId) ->
            if (qualityLevel == ApiPreferences.MAX_THINKING_QUALITY_LEVEL) {
                "xhigh"
            } else {
                requestedEffort
            }
        isBaseGpt5CodexModel(modelId) ->
            when (qualityLevel) {
                1 -> "low"
                2 -> "medium"
                else -> "high"
            }
        isGpt52To55ProModel(modelId) ->
            when (qualityLevel) {
                1, 2 -> "medium"
                3 -> "high"
                else -> "xhigh"
            }
        isGpt52To55Model(modelId) ->
            if (qualityLevel == ApiPreferences.MAX_THINKING_QUALITY_LEVEL) {
                "xhigh"
            } else {
                requestedEffort
            }
        isOpenAiProOSeriesModel(modelId) -> "high"
        isOpenAiOSeriesModel(modelId) ->
            when (qualityLevel) {
                1 -> "low"
                2 -> "medium"
                else -> "high"
            }
        else -> requestedEffort
    }
}

internal fun supportsOpenAiReasoningEffortModel(modelName: String): Boolean {
    val modelId = modelName.trim().lowercase().substringAfterLast('/')
    return isGpt56Model(modelId) ||
        isGpt5ProModel(modelId) ||
        isBaseGpt5Model(modelId) ||
        isGpt5MiniNanoModel(modelId) ||
        isGpt51Model(modelId) ||
        isGpt54MiniNanoModel(modelId) ||
        isBaseGpt5CodexModel(modelId) ||
        isGpt51To54CodexModel(modelId) ||
        isGpt52To55ProModel(modelId) ||
        isGpt52To55Model(modelId) ||
        isOpenAiOSeriesModel(modelId)
}

private fun isGpt56Model(modelId: String): Boolean =
    modelId.matches(
        Regex("gpt-5\\.6(?:-(?:sol|terra|luna))?(?:-\\d{4}-\\d{2}-\\d{2})?")
    )

private fun isGpt51Model(modelId: String): Boolean =
    modelId.matches(
        Regex("gpt-5\\.1(?:-\\d{4}-\\d{2}-\\d{2})?")
    )

private fun isGpt52To55Model(modelId: String): Boolean =
    modelId.matches(Regex("gpt-5\\.(?:2|4|5)(?:-\\d{4}-\\d{2}-\\d{2})?"))

private fun isGpt52To55ProModel(modelId: String): Boolean =
    modelId.matches(Regex("gpt-5\\.(?:2|4|5)-pro(?:-\\d{4}-\\d{2}-\\d{2})?"))

private fun isBaseGpt5Model(modelId: String): Boolean =
    modelId.matches(Regex("gpt-5(?:-\\d{4}-\\d{2}-\\d{2})?"))

private fun isGpt5ProModel(modelId: String): Boolean =
    modelId.matches(Regex("gpt-5-pro(?:-\\d{4}-\\d{2}-\\d{2})?"))

private fun isGpt5MiniNanoModel(modelId: String): Boolean =
    modelId.matches(Regex("gpt-5-(?:mini|nano)(?:-\\d{4}-\\d{2}-\\d{2})?"))

private fun isGpt54MiniNanoModel(modelId: String): Boolean =
    modelId.matches(Regex("gpt-5\\.4-(?:mini|nano)(?:-\\d{4}-\\d{2}-\\d{2})?"))

private fun isBaseGpt5CodexModel(modelId: String): Boolean =
    modelId.matches(Regex("gpt-5-codex(?:-\\d{4}-\\d{2}-\\d{2})?"))

private fun isGpt51To54CodexModel(modelId: String): Boolean =
    modelId.matches(
        Regex("gpt-5\\.(?:1|2|3|4)-codex(?:-\\d{4}-\\d{2}-\\d{2})?")
    )

private fun isOpenAiOSeriesModel(modelId: String): Boolean =
    modelId.matches(
        Regex("(?:o1|o3|o4)(?:-(?:mini|pro))?(?:-\\d{4}-\\d{2}-\\d{2})?")
    )

private fun isOpenAiProOSeriesModel(modelId: String): Boolean =
    modelId.matches(Regex("(?:o1|o3|o4)-pro(?:-\\d{4}-\\d{2}-\\d{2})?"))

private fun functionalResponsesReasoningParameter(
    modelParameters: List<ModelParameter<*>>,
    effort: String,
): ModelParameter<String> {
    val existingReasoning =
        modelParameters
            .lastOrNull { it.apiName == "reasoning" && it.isEnabled }
            ?.currentValue
            ?.toString()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { raw -> runCatching { org.json.JSONObject(raw) }.getOrNull() }
            ?: org.json.JSONObject()
    existingReasoning.put("effort", effort)
    return functionalObjectParameter("reasoning", existingReasoning.toString())
}

private fun anthropicFunctionalBudget(
    effectiveLevel: Int?,
    providerType: ApiProviderType?,
    modelName: String,
    modelParameters: List<ModelParameter<*>>,
): Int? {
    val level = effectiveLevel ?: return null
    val requestedBudget =
        ANTHROPIC_BUDGETS[
            level.coerceIn(
                ApiPreferences.MIN_THINKING_QUALITY_LEVEL,
                ApiPreferences.MAX_THINKING_QUALITY_LEVEL,
            ) - ApiPreferences.MIN_THINKING_QUALITY_LEVEL
        ]
    val maxTokens =
        resolveClaudeEffectiveMaxTokens(
            providerType = providerType,
            modelName = modelName,
            modelParameters = modelParameters,
        )
    if (maxTokens <= MIN_ANTHROPIC_THINKING_BUDGET) {
        return null
    }
    return minOf(requestedBudget, maxTokens - 1)
        .coerceAtLeast(MIN_ANTHROPIC_THINKING_BUDGET)
}

internal fun resolveClaudeEffectiveMaxTokens(
    providerType: ApiProviderType?,
    modelName: String,
    modelParameters: List<ModelParameter<*>>,
): Int {
    val enabledMaxTokens =
        (modelParameters.lastOrNull { it.apiName == "max_tokens" && it.isEnabled }
            ?.currentValue as? Number)
            ?.toInt()
            ?.takeIf { it > 0 }
    return enabledMaxTokens
        ?: resolveOfficialClaudeMaxTokens(modelName).takeIf {
            providerType == ApiProviderType.ANTHROPIC
        }
        ?: DEFAULT_CLAUDE_MAX_TOKENS
}

internal fun applyClaudeEffectiveMaxTokensParameter(
    requestJson: org.json.JSONObject,
    providerType: ApiProviderType?,
    modelName: String,
    modelParameters: List<ModelParameter<*>>,
): Int {
    val maxTokens =
        resolveClaudeEffectiveMaxTokens(
            providerType = providerType,
            modelName = modelName,
            modelParameters = modelParameters,
        )
    requestJson.put("max_tokens", maxTokens)
    return maxTokens
}

private fun resolveOfficialClaudeMaxTokens(modelName: String): Int {
    val normalizedModelName = normalizeClaudeModelName(modelName)
    return when {
        normalizedModelName == "claude-mythos-preview" -> 128_000
        claudeFamilyVersion(normalizedModelName, "fable") in
            CLAUDE_ADAPTIVE_FABLE_VERSIONS -> 128_000
        claudeFamilyVersion(normalizedModelName, "mythos") in
            CLAUDE_ADAPTIVE_MYTHOS_VERSIONS -> 128_000
        claudeFamilyVersion(normalizedModelName, "opus") in
            CLAUDE_ADAPTIVE_OPUS_VERSIONS -> 128_000
        claudeFamilyVersion(normalizedModelName, "sonnet") in
            CLAUDE_ADAPTIVE_SONNET_VERSIONS -> 128_000
        claudeFamilyVersion(normalizedModelName, "haiku") == (4 to 5) -> 64_000
        claudeFamilyVersion(normalizedModelName, "opus") == (4 to 5) -> 64_000
        claudeFamilyVersion(normalizedModelName, "sonnet") in
            setOf(3 to 7, 4 to 0, 4 to 5) -> 64_000
        claudeFamilyVersion(normalizedModelName, "opus") in
            setOf(4 to 0, 4 to 1) -> 32_000
        claudeFamilyVersion(normalizedModelName, "sonnet") == (3 to 5) -> 8_192
        claudeFamilyVersion(normalizedModelName, "haiku") == (3 to 5) -> 8_192
        claudeFamilyVersion(normalizedModelName, "haiku") == (3 to 0) -> 4_096
        else -> DEFAULT_CLAUDE_MAX_TOKENS
    }
}

private fun functionalStringParameter(apiName: String, value: String): ModelParameter<String> {
    return ModelParameter(
        id = "__operit_functional_$apiName",
        name = apiName,
        apiName = apiName,
        defaultValue = value,
        currentValue = value,
        isEnabled = true,
        valueType = ParameterValueType.STRING,
        category = ParameterCategory.OTHER,
    )
}

private fun functionalObjectParameter(apiName: String, value: String): ModelParameter<String> {
    return ModelParameter(
        id = "__operit_functional_$apiName",
        name = apiName,
        apiName = apiName,
        defaultValue = value,
        currentValue = value,
        isEnabled = true,
        valueType = ParameterValueType.OBJECT,
        category = ParameterCategory.OTHER,
    )
}

private fun functionalMergedObjectParameter(
    apiName: String,
    modelParameters: List<ModelParameter<*>>,
    values: Map<String, Any>,
    defaultValues: Map<String, Any> = emptyMap(),
    removedKeys: Set<String> = emptySet(),
): ModelParameter<String> {
    val mergedObject =
        modelParameters
            .lastOrNull { it.apiName == apiName && it.isEnabled }
            ?.currentValue
            ?.toString()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { raw -> runCatching { org.json.JSONObject(raw) }.getOrNull() }
            ?: org.json.JSONObject()
    removedKeys.forEach(mergedObject::remove)
    defaultValues.forEach { (key, value) ->
        if (!mergedObject.has(key)) {
            mergedObject.put(key, value)
        }
    }
    values.forEach(mergedObject::put)
    return functionalObjectParameter(apiName, mergedObject.toString())
}

private fun functionalPreservedObjectParameter(
    apiName: String,
    modelParameters: List<ModelParameter<*>>,
): ModelParameter<String>? {
    val preservedObject =
        modelParameters
            .lastOrNull { it.apiName == apiName && it.isEnabled }
            ?.currentValue
            ?.toString()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { raw -> runCatching { org.json.JSONObject(raw) }.getOrNull() }
            ?: return null
    return functionalObjectParameter(apiName, preservedObject.toString())
}

private fun functionalIntParameter(apiName: String, value: Int): ModelParameter<Int> {
    return ModelParameter(
        id = "__operit_functional_$apiName",
        name = apiName,
        apiName = apiName,
        defaultValue = value,
        currentValue = value,
        isEnabled = true,
        valueType = ParameterValueType.INT,
        category = ParameterCategory.OTHER,
    )
}

private fun functionalBooleanParameter(apiName: String, value: Boolean): ModelParameter<Boolean> {
    return ModelParameter(
        id = "__operit_functional_$apiName",
        name = apiName,
        apiName = apiName,
        defaultValue = value,
        currentValue = value,
        isEnabled = true,
        valueType = ParameterValueType.BOOLEAN,
        category = ParameterCategory.OTHER,
    )
}

private fun functionalAutomaticReasoningSuppressionParameter(): ModelParameter<Boolean> =
    functionalBooleanParameter(SUPPRESS_AUTOMATIC_REASONING_API_NAME, true)

private val SILICONFLOW_BUDGETS = listOf(1_024, 4_096, 8_192, 16_384, 32_768)
private val ALIYUN_BUDGETS = listOf(1_024, 4_096, 8_192, 16_384, 32_768)
private val ANTHROPIC_BUDGETS = listOf(1_024, 4_096, 8_192, 16_384, 32_768)
private val GEMINI_25_FLASH_BUDGETS = listOf(512, 2_048, 8_192, 16_384, 24_576)
private val GEMINI_25_PRO_BUDGETS = listOf(128, 2_048, 8_192, 16_384, 32_768)
private const val MIN_ANTHROPIC_THINKING_BUDGET = 1_024
private const val DEFAULT_CLAUDE_MAX_TOKENS = 4_096

private val ALIYUN_QWEN_TURBO_SNAPSHOT = Regex("qwen-turbo-\\d{4}-\\d{2}-\\d{2}")
private val ALIYUN_QWEN3_THINKING_MODELS =
    setOf(
        "qwen3-max",
        "qwen3-max-preview",
        "qwen3-max-2026-01-23",
        "qwen3-235b-a22b",
        "qwen3-32b",
        "qwen3-30b-a3b",
        "qwen3-14b",
        "qwen3-8b",
        "qwen3-next-80b-a3b-thinking",
        "qwen3-235b-a22b-thinking-2507",
        "qwen3-30b-a3b-thinking-2507",
        "qwen3.5-plus",
        "qwen3.5-plus-2026-02-15",
        "qwen3.5-flash",
        "qwen3.5-flash-2026-02-23",
        "qwen3.5-397b-a17b",
        "qwen3.5-122b-a10b",
        "qwen3.5-35b-a3b",
        "qwen3.5-27b",
        "qwen3.6-max-preview",
        "qwen3.6-plus",
        "qwen3.6-plus-2026-04-02",
        "qwen3.6-flash",
        "qwen3.6-flash-2026-04-16",
        "qwen3.6-35b-a3b",
        "qwen3.7-max",
        "qwen3.7-max-us",
        "qwen3.7-max-preview",
        "qwen3.7-max-2026-05-17",
        "qwen3.7-max-2026-05-20",
        "qwen3.7-max-2026-06-08",
        "qwen3.7-plus",
        "qwen3.7-plus-us",
        "qwen3.7-plus-2026-05-26",
        "qwen3.7-flash",
        "qwen3.7-flash-2026-07-15",
        "qwen3.8-max",
    )

private val GEMINI_3_FUNCTIONAL_THINKING_MODELS =
    setOf(
        "gemini-3-flash-preview",
        "gemini-3-pro-preview",
        "gemini-3.1-pro-preview",
        "gemini-3.1-flash-lite",
        "gemini-3.1-flash-image",
        "gemini-3.1-flash-lite-image",
        "gemini-3.5-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.6-flash",
    )

private val CLAUDE_ADAPTIVE_FABLE_VERSIONS = setOf(5 to 0)
private val CLAUDE_ADAPTIVE_MYTHOS_VERSIONS = setOf(5 to 0)
private val CLAUDE_ADAPTIVE_OPUS_VERSIONS = setOf(4 to 6, 4 to 7, 4 to 8, 5 to 0)
private val CLAUDE_ADAPTIVE_SONNET_VERSIONS = setOf(4 to 6, 5 to 0)
private val CLAUDE_MANUAL_HAIKU_VERSIONS = setOf(4 to 5)
private val CLAUDE_MANUAL_OPUS_VERSIONS = setOf(4 to 0, 4 to 1, 4 to 5)
private val CLAUDE_MANUAL_SONNET_VERSIONS = setOf(3 to 7, 4 to 0, 4 to 5)
private val CLAUDE_XHIGH_FABLE_VERSIONS = setOf(5 to 0)
private val CLAUDE_XHIGH_MYTHOS_VERSIONS = setOf(5 to 0)
private val CLAUDE_XHIGH_OPUS_VERSIONS = setOf(4 to 7, 4 to 8, 5 to 0)
private val CLAUDE_XHIGH_SONNET_VERSIONS = setOf(5 to 0)

private val SILICONFLOW_TOGGLE_MODELS =
    setOf(
        "zai-org/glm-5",
        "zai-org/glm-4.7",
        "zai-org/glm-4.6",
        "deepseek-ai/deepseek-v3.2",
        "deepseek-ai/deepseek-v3.1-terminus",
        "qwen/qwen3-8b",
        "qwen/qwen3-14b",
        "qwen/qwen3-32b",
        "qwen/qwen3-30b-a3b",
        "qwen/qwen3.5-397b-a17b",
        "qwen/qwen3.5-122b-a10b",
        "qwen/qwen3.5-35b-a3b",
        "qwen/qwen3.5-27b",
        "qwen/qwen3.5-9b",
        "qwen/qwen3.5-4b",
        "tencent/hunyuan-a13b-instruct",
        "zai-org/glm-4.5v",
    )
