package com.ai.assistance.operit.api.chat.llmprovider

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val GEMINI_37_FLASH_MODEL = "gemini-3.7-flash"

internal fun normalizedGeminiModelName(modelName: String): String =
    modelName
        .trim()
        .removePrefix("models/")
        .lowercase()
        .substringAfterLast('/')

internal fun isGemini3Model(modelName: String): Boolean =
    normalizedGeminiModelName(modelName).startsWith("gemini-3")

internal fun isGemini37FlashModel(modelName: String): Boolean =
    normalizedGeminiModelName(modelName).startsWith(GEMINI_37_FLASH_MODEL)

internal fun buildGeminiGenerateContentUrl(
    apiEndpoint: String,
    modelName: String,
    streaming: Boolean,
    apiKey: String,
): HttpUrl {
    val endpoint = parseGeminiEndpoint(apiEndpoint)
    val operation = if (streaming) "streamGenerateContent" else "generateContent"
    val normalizedModel = modelName.trim().removePrefix("models/")
    require(normalizedModel.isNotEmpty()) { "Gemini model name must not be blank" }

    return endpoint
        .newBuilder()
        .fragment(null)
        .encodedPath(geminiModelsPath(endpoint))
        .addPathSegment("$normalizedModel:$operation")
        .apply {
            if (streaming) {
                setQueryParameter("alt", "sse")
            } else {
                removeAllQueryParameters("alt")
            }
            if (apiKey.isNotBlank()) {
                setQueryParameter("key", apiKey)
            }
        }
        .build()
}

internal fun buildGeminiModelsListUrl(apiEndpoint: String): HttpUrl {
    val endpoint = parseGeminiEndpoint(apiEndpoint)
    return endpoint
        .newBuilder()
        .fragment(null)
        .encodedPath(geminiModelsPath(endpoint))
        .removeAllQueryParameters("alt")
        .removeAllQueryParameters("pageToken")
        .build()
}

private fun parseGeminiEndpoint(apiEndpoint: String): HttpUrl {
    val normalizedEndpoint = apiEndpoint.trim().removeSuffix("#")
    return requireNotNull(normalizedEndpoint.toHttpUrlOrNull()) {
        "Invalid Gemini API endpoint: $apiEndpoint"
    }
}

private fun geminiModelsPath(endpoint: HttpUrl): String {
    val path = endpoint.encodedPath.trimEnd('/')
    val lowerPath = path.lowercase()
    val modelsMarker = "/models"
    val modelsIndex = lowerPath.lastIndexOf(modelsMarker)
    if (modelsIndex >= 0) {
        val markerEnd = modelsIndex + modelsMarker.length
        val hasSegmentBoundary =
            markerEnd == lowerPath.length || lowerPath.getOrNull(markerEnd) == '/'
        if (hasSegmentBoundary) {
            return path.substring(0, markerEnd)
        }
    }

    if (path.isEmpty()) {
        return "/v1beta/models"
    }

    val versionPath = Regex(""".*/v\d+(?:(?:alpha|beta)\d*)?$""", RegexOption.IGNORE_CASE)
    if (versionPath.matches(path)) {
        return "$path/models"
    }

    return "$path/v1beta/models"
}
