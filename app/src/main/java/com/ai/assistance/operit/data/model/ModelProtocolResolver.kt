package com.ai.assistance.operit.data.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Resolve only the selected model; the stored account configuration stays unchanged. */
internal fun ModelConfigData.withModelProtocol(): ModelConfigData {
    if (!supportsModelProtocolOverrides(apiProviderTypeId)) return this
    val settings = protocolSettingsForModel(modelName)
    val provider = settings.protocol.providerType ?: return this
    val endpoint = settings.endpoint.trim().ifEmpty {
        protocolEndpoint(apiEndpoint, settings.protocol)
    }
    return copy(
        apiProviderType = provider,
        apiProviderTypeId = provider.name,
        apiEndpoint = endpoint,
    )
}

internal fun protocolEndpoint(endpoint: String, protocol: ModelProtocol): String {
    // A trailing # is the existing opt-out from automatic endpoint completion.
    if (endpoint.trim().endsWith("#") || protocol == ModelProtocol.INHERIT) return endpoint
    val url = endpoint.trim().toHttpUrlOrNull() ?: return endpoint
    val path = url.encodedPath.trimEnd('/')
    val suffix = listOf("/chat/completions", "/responses", "/messages")
        .firstOrNull { path.endsWith(it) } ?: return endpoint
    val basePath = path.removeSuffix(suffix)
    val target = when (protocol) {
        ModelProtocol.RESPONSES -> "/responses"
        ModelProtocol.ANTHROPIC -> "/messages"
        ModelProtocol.GEMINI -> ""
        else -> "/chat/completions"
    }
    return url.newBuilder().encodedPath(basePath + target).build().toString()
}
