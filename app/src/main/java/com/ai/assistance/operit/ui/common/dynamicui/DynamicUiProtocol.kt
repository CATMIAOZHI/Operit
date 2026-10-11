package com.ai.assistance.operit.ui.common.dynamicui

import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.*

/**
 * Resource limits around the official A2UI parser, not a replacement protocol.
 * The catalog deliberately accepts static child IDs only: template expansion and
 * arbitrary functions/regular expressions are outside this first release.
 */
internal object DynamicUiProtocol {
    const val CATALOG = "https://operit.ai/a2ui/catalog/v1"
    const val MAX_SOURCE = 131_072
    const val MAX_STATE = 32_768
    const val MARKER = "_operit_ready"
    val actions = setOf("local_action", "ai_submit", "tool_action", "open_url", "copy")
    val components = setOf(
        "Text", "Icon", "Row", "Column", "Card", "Divider", "Button", "List",
        "TextField", "CheckBox", "ChoicePicker", "Slider",
        "Heading", "Display", "Badge", "Grid", "Progress", "Table", "Code", "Switch",
        "RadioGroup", "Select", "DatePicker", "TextArea", "Form", "Expandable",
        "Segmented", "Tabs", "Carousel", "Conditional", "Chart", "RichText",
        "Image", "Video", "Audio", "Link", "File", "Product", "Place", "Map", "Draft",
    )
    private val idPattern = Regex("[A-Za-z0-9_-]{1,80}")
    private val json = Json { isLenient = false }

    data class Batch(
        val lines: List<String>,
        val surfaceIds: Set<String>,
        val complete: Boolean,
        val componentsBySurface: Map<String, Map<String, JsonObject>>,
    )

    fun fingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Same bounded validator used by the renderer. No rendering, persistence or actions. */
    fun validationReport(source: String): JsonObject = try {
        val batch = parse(source, true)
        buildJsonObject {
            put("valid", true)
            put("surfaces", batch.surfaceIds.size)
            put("message", "Protocol checks passed. This does not verify visual layout or arithmetic intent.")
        }
    } catch (e: Exception) {
        val line = diagnosticLine(e)
        val code = diagnosticCode(e)
        val column = if (code == "invalid_json") generateSequence<Throwable>(e) { it.cause }
            .take(8).filterIsInstance<kotlinx.serialization.SerializationException>()
            .mapNotNull { Regex("\\boffset (\\d+)").find(it.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() }
            .firstOrNull()?.plus(1) else null
        val reason = if (code == "invalid_json")
            "Check closing braces, brackets, commas and quotes. Nested initial data is supported."
        else safeDiagnosticReason(e)
        buildJsonObject {
            put("valid", false)
            line?.let { put("line", it) }
            column?.let { put("column", it) }
            put("code", code)
            put("message", buildString {
                append(code)
                line?.let { append(" at line $it") }
                column?.let { append(", column $it") }
                append(": $reason")
            })
        }
    }

    // Only relay our fixed diagnostics, never parser excerpts or arbitrary model text.
    private fun safeDiagnosticReason(error: Exception): String {
        val known = setOf(
            "Card exceeds 128 KiB character limit", "Too many updates", "Expected A2UI v0.9",
            "Expected one A2UI event per line", "Invalid surface ID", "Unsupported catalog",
            "Duplicate or too many surfaces", "Use the app theme", "Create the surface first",
            "Too many components", "Invalid or duplicate component ID", "Unsupported component",
            "Initial data must not contain function calls", "Data model too large", "Unknown surface",
            "Unsupported A2UI event", "Missing root component", "Functions cannot change component structure",
            "Invalid weight", "Invalid slider range", "Invalid slider steps", "Grid columns must be 1 to 4",
            "Grid spans require explicit columns and one integer span per child",
            "Use up to 32 static child IDs", "Duplicate children", "Only event actions are allowed",
            "Unsupported action", "Table too large", "Invalid table rows", "Cyclic or deeply nested components",
            "Too many rendered components", "Invalid data path", "Reserved or array-index data path",
            "JSON too deeply nested", "Object too large", "Too many local calculations",
            "Unsupported expression or reserved field", "Array too large", "Text too long",
            "Unsupported function property", "Unsupported local function", "Function args must be an object",
            "Invalid function arguments", "Incorrect return type", "Decimal places must be 0 to 6",
            "Too many strings", "Unknown calculator key",
        )
        return generateSequence<Throwable>(error) { it.cause }.take(8)
            .mapNotNull { it.message?.takeIf(known::contains) }.firstOrNull()
            ?: "Invalid field type or missing required field/reference. Check read_guide and submit the complete document."
    }

    fun diagnosticLine(error: Exception): Int? =
        Regex("^Line (\\d+):").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    fun diagnosticCode(error: Exception): String =
        if (generateSequence<Throwable>(error) { it.cause }.take(8).any { it is kotlinx.serialization.SerializationException })
            "invalid_json" else "invalid_card"

    fun parse(source: String, closed: Boolean): Batch {
        require(source.length <= MAX_SOURCE) { "Card exceeds 128 KiB character limit" }
        val lines = source.lines().toMutableList()
        if (!closed && !source.endsWith('\n')) lines.removeLastOrNull()
        val completeLines = lines.filter { it.isNotBlank() }
        require(completeLines.size <= 256) { "Too many updates" }
        val surfaces = linkedMapOf<String, MutableMap<String, JsonObject>>()
        val callCount = intArrayOf(0)
        lines.withIndex().filter { it.value.isNotBlank() }.forEach { (index, line) ->
            try {
            checkJsonDepth(line)
            val event = json.parseToJsonElement(line).jsonObject
            require(event["version"]?.jsonPrimitive?.content == "v0.9") { "Expected A2UI v0.9" }
            require(event.size == 2) { "Expected one A2UI event per line" }
            val kind = event.keys.single { it != "version" }
            val body = event[kind]!!.jsonObject
            val surfaceId = body["surfaceId"]?.jsonPrimitive?.content.orEmpty()
            require(idPattern.matches(surfaceId)) { "Invalid surface ID" }
            checkValue(body, callCount = callCount)
            when (kind) {
                "createSurface" -> {
                    require(body["catalogId"]?.jsonPrimitive?.content == CATALOG) { "Unsupported catalog" }
                    require(surfaceId !in surfaces && surfaces.size < 4) { "Duplicate or too many surfaces" }
                    require(body["theme"] == null) { "Use the app theme" }
                    surfaces[surfaceId] = linkedMapOf()
                }
                "updateComponents" -> {
                    val graph = requireNotNull(surfaces[surfaceId]) { "Create the surface first" }
                    val updates = body["components"]!!.jsonArray
                    require(updates.size <= 128) { "Too many components" }
                    val ids = mutableSetOf<String>()
                    updates.forEach { item ->
                        val component = item.jsonObject
                        val id = component["id"]?.jsonPrimitive?.content.orEmpty()
                        require(idPattern.matches(id) && ids.add(id)) { "Invalid or duplicate component ID" }
                        require(component["component"]?.jsonPrimitive?.content in components) {
                            "Unsupported component"
                        }
                        validateComponent(component)
                        graph[id] = component
                    }
                    require(graph.size <= 128) { "Too many components" }
                    validateGraph(graph, requireReferences = false)
                }
                "updateDataModel" -> {
                    require(!containsCall(body)) { "Initial data must not contain function calls" }
                    require(surfaceId in surfaces) { "Create the surface first" }
                    validatePath(body["path"]?.jsonPrimitive?.content ?: "/")
                    require(body["value"].toString().length <= MAX_STATE) { "Data model too large" }
                }
                "deleteSurface" -> require(surfaces.remove(surfaceId) != null) { "Unknown surface" }
                else -> error("Unsupported A2UI event")
            }
            } catch (e: Exception) {
                // Keep the line number for diagnostics without copying model output into logs.
                throw IllegalArgumentException("Line ${index + 1}: ${e.message?.take(160)}", e)
            }
        }
        if (closed) surfaces.values.forEach {
            require("root" in it) { "Missing root component" }
            validateGraph(it, requireReferences = true)
        }
        return Batch(completeLines, surfaces.keys.toSet(), closed, surfaces.mapValues { it.value.toMap() })
    }

    private fun validateComponent(c: JsonObject) {
        c.forEach { (key, value) ->
            if (key in setOf("id", "component", "child", "children", "weight", "min", "max", "type", "columnCount", "columnSpans", "steps")) {
                require(!containsCall(value)) { "Functions cannot change component structure" }
            }
        }
        c["weight"]?.jsonPrimitive?.double?.let {
            require(it.isFinite() && it > 0 && it <= 100) { "Invalid weight" }
        }
        if (c["component"]?.jsonPrimitive?.content == "Slider") {
            val min = c["min"]?.jsonPrimitive?.double ?: 0.0
            val max = c["max"]?.jsonPrimitive?.double ?: 100.0
            require(min.isFinite() && max.isFinite() && min >= -1_000_000 &&
                max <= 1_000_000 && max > min && max - min <= 1000) { "Invalid slider range" }
            c["steps"]?.let { require((it as? JsonPrimitive)?.intOrNull in 0..999) { "Invalid slider steps" } }
        }
        if (c["component"]?.jsonPrimitive?.content == "Grid") c["columnCount"]?.let {
            require((it as? JsonPrimitive)?.intOrNull in 1..4) { "Grid columns must be 1 to 4" }
        }
        if (c["component"]?.jsonPrimitive?.content == "Grid") c["columnSpans"]?.let { spans ->
            val count = (c["columnCount"] as? JsonPrimitive)?.intOrNull
            val refs = c["children"] as? JsonArray
            require(count != null && count in 1..4 && refs != null && spans is JsonArray &&
                spans.size == refs.size && spans.all {
                    it is JsonPrimitive && !it.isString && it.intOrNull in 1..count
                }) { "Grid spans require explicit columns and one integer span per child" }
        }
        c["children"]?.let {
            require(it is JsonArray && it.size <= 32 && it.all { child -> child is JsonPrimitive && child.isString }) {
                "Use up to 32 static child IDs"
            }
            require(it.distinct().size == it.size) { "Duplicate children" }
        }
        c["action"]?.jsonObject?.let {
            require(it.keys == setOf("event")) { "Only event actions are allowed" }
            require(it["event"]!!.jsonObject["name"]?.jsonPrimitive?.content in actions) { "Unsupported action" }
        }
        if (c["component"]?.jsonPrimitive?.content == "Table") {
            val columns = c["columns"]!!.jsonArray
            val rows = c["rows"]!!.jsonArray
            require(columns.size in 1..8 && rows.size <= 32) { "Table too large" }
            require(rows.all { it is JsonArray && it.size == columns.size }) { "Invalid table rows" }
        }
    }

    private fun references(c: JsonObject): List<String> =
        c["children"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty() +
            listOfNotNull(c["child"]?.jsonPrimitive?.content)

    private fun validateGraph(graph: Map<String, JsonObject>, requireReferences: Boolean) {
        // Count expanded occurrences, not just unique IDs: a small DAG can render exponentially.
        fun visit(id: String, ancestors: Set<String>, depth: Int): Int {
            require(depth <= 12 && id !in ancestors) { "Cyclic or deeply nested components" }
            val node = graph[id]
            if (node == null) {
                require(!requireReferences) { "Missing child: $id" }
                return 1
            }
            var count = 1
            references(node).forEach {
                count += visit(it, ancestors + id, depth + 1)
                require(count <= 512) { "Too many rendered components" }
            }
            return count
        }
        graph.keys.forEach { visit(it, emptySet(), 0) }
    }

    fun validatePath(path: String) {
        require(path.startsWith("/") && path.length <= 256 && path.count { it == '/' } <= 12) { "Invalid data path" }
        require(path.split('/').none { it == MARKER || it.all(Char::isDigit) && it.isNotEmpty() }) {
            "Reserved or array-index data path"
        }
    }

    private fun containsCall(value: JsonElement): Boolean = when (value) {
        is JsonObject -> "call" in value || value.values.any(::containsCall)
        is JsonArray -> value.any(::containsCall)
        else -> false
    }

    private fun checkValue(value: JsonElement, depth: Int = 0, callDepth: Int = 0, callCount: IntArray = intArrayOf(0)) {
        require(depth <= 20) { "JSON too deeply nested" }
        when (value) {
            is JsonObject -> {
                require(value.size <= 128) { "Object too large" }
                val nextCallDepth = callDepth + if ("call" in value) 1 else 0
                if ("call" in value) {
                    require(nextCallDepth <= 6 && ++callCount[0] <= 128) { "Too many local calculations" }
                    CardFunctions.validate(value)
                }
                value.forEach { (key, child) ->
                    require(key.length <= 256 && key !in setOf(MARKER, "validationRegexp", "callableFrom")) {
                        "Unsupported expression or reserved field"
                    }
                    if (key == "path") validatePath(child.jsonPrimitive.content)
                    checkValue(child, depth + 1, nextCallDepth, callCount)
                }
            }
            is JsonArray -> {
                require(value.size <= 128) { "Array too large" }
                value.forEach { checkValue(it, depth + 1, callDepth, callCount) }
            }
            is JsonPrimitive -> require(value.content.length <= 8192) { "Text too long" }
        }
    }

    // Bound nesting before any recursive JSON parser sees untrusted input.
    private fun checkJsonDepth(text: String) {
        var depth = 0
        var quoted = false
        var escape = false
        for (char in text) {
            if (quoted) {
                if (escape) escape = false
                else if (char == '\\') escape = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 24) { "JSON too deeply nested" } }
                '}', ']' -> depth--
            }
        }
    }

    fun safeUrl(text: String): Boolean = runCatching {
        val uri = URI(text)
        text.length <= 2048 && uri.scheme in setOf("https", "http") &&
            !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    fun toJson(value: Any?, depth: Int = 0): JsonElement {
        require(depth <= 16) { "State too deeply nested" }
        return when (value) {
            null -> JsonNull
            is Map<*, *> -> {
                require(value.size <= 128) { "State object too large" }
                JsonObject(value.entries.associate { (key, item) ->
                    require(key is String && key.length <= 256)
                    key to toJson(item, depth + 1)
                })
            }
            is List<*> -> {
                require(value.size <= 128) { "State array too large" }
                JsonArray(value.map { toJson(it, depth + 1) })
            }
            is String -> { require(value.length <= 8192); JsonPrimitive(value) }
            is Boolean -> JsonPrimitive(value)
            is Number -> { require(value.toDouble().isFinite()); JsonPrimitive(value) }
            else -> error("Unsupported state value")
        }
    }

    fun toValue(value: JsonElement): Any? = when (value) {
        is JsonObject -> value.mapValues { toValue(it.value) }
        is JsonArray -> value.map(::toValue)
        JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content
            else value.booleanOrNull ?: value.longOrNull ?: value.double
    }
}
