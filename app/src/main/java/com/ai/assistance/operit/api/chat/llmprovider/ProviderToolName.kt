package com.ai.assistance.operit.api.chat.llmprovider

import org.json.JSONObject

/**
 * Decode a provider tool name from raw JSON.
 *
 * [JSONObject.optString] coerces JSON null and non-string values into the literal
 * "null". That fake name is later executed as a real tool, so only a non-blank
 * JSON string is accepted.
 */
internal fun decodeProviderToolName(rawValue: Any?): String? {
    if (rawValue !is String) return null
    val name = rawValue.trim()
    if (name.isEmpty() || name.equals("null", ignoreCase = true)) return null
    return name
}

internal fun JSONObject?.optProviderToolName(key: String = "name"): String? {
    if (this == null || !has(key)) return null
    return decodeProviderToolName(opt(key))
}
