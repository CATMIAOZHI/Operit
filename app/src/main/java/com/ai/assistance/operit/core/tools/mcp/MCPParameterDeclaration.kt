package com.ai.assistance.operit.core.tools.mcp

import org.json.JSONObject

/** Only diagnose undeclared names when the server provides a concrete property list. */
internal fun mcpDeclaredParameterNames(schema: JSONObject?): Set<String>? {
    if (schema == null) return null
    // Open/dynamic schemas can intentionally accept names absent from properties.
    if (schema.opt("additionalProperties") == true ||
        schema.opt("additionalProperties") is JSONObject ||
        listOf("patternProperties", "allOf", "anyOf", "oneOf", "\$ref").any { schema.has(it) }
    ) return null
    return schema.optJSONObject("properties")?.keys()?.asSequence()?.toSet()
}
