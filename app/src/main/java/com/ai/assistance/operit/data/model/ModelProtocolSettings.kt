package com.ai.assistance.operit.data.model

import kotlinx.serialization.Serializable

/** Request/response handling, independent of the account that supplies the API key. */
@Serializable
enum class ModelProtocol(val providerType: ApiProviderType?) {
    INHERIT(null),
    CHAT_COMPLETIONS(ApiProviderType.OPENAI_GENERIC),
    CHAT_REASONING(ApiProviderType.OPENAI_GENERIC),
    RESPONSES(ApiProviderType.OPENAI_RESPONSES_GENERIC),
    ANTHROPIC(ApiProviderType.ANTHROPIC_GENERIC),
    GEMINI(ApiProviderType.GEMINI_GENERIC),
    DEEPSEEK(ApiProviderType.DEEPSEEK),
    KIMI(ApiProviderType.MOONSHOT),
    MIMO(ApiProviderType.MIMO),
}

@Serializable
data class ModelProtocolSettings(
    val protocol: ModelProtocol = ModelProtocol.INHERIT,
    val endpoint: String = "",
    val reasoningEfforts: List<String>? = null,
)

fun ModelConfigData.protocolSettingsForModel(model: String): ModelProtocolSettings =
    modelProtocolSettings[model.trim()] ?: ModelProtocolSettings()

fun supportsModelProtocolOverrides(providerTypeId: String): Boolean =
    ApiProviderType.fromProviderTypeId(providerTypeId)?.let {
        it != ApiProviderType.MNN && it != ApiProviderType.LLAMA_CPP &&
            it !in setOf(ApiProviderType.OPENAI_CODEX, ApiProviderType.GROK_ACCOUNT, ApiProviderType.GOOGLE_ANTIGRAVITY)
    } == true
