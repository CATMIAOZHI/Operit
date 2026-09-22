package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.data.model.ToolInvocation
import org.json.JSONTokener
import kotlinx.serialization.json.*

/** Decode once, apply each deterministic rule once, then restore the original call envelope. */
internal object ToolCallRepairRouter {
    const val READ_FILE_LINE_RANGE = "read_file_line_range"
    const val TERMINAL_SEPARATOR = "terminal_single_separator"
    const val TERMINAL_TIMEOUT = "terminal_timeout_alias"
    const val REDUNDANT_PACKAGE_NAME = "redundant_proxy_package_name"

    data class Repair(
        val original: ToolInvocation,
        val invocation: ToolInvocation,
        val originalToolName: String,
        val targetToolName: String,
        val parameterNames: List<String>,
        val rules: List<String>,
        val originalParameterNames: List<String>,
    ) {
        val rule: String get() = rules.first()
    }

    fun route(original: ToolInvocation): Repair? {
        val tool = original.tool
        val isProxy = tool.name == "proxy" || tool.name == "package_proxy"
        val initial = if (isProxy) {
            if (tool.parameters.map { it.name }.distinct().size != tool.parameters.size) return null
            val name = tool.parameters.singleOrNull { it.name == "tool_name" }?.value?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
            if (tool.name == "package_proxy" && ':' !in name) return null
            val raw = tool.parameters.singleOrNull { it.name == "params" }?.value ?: return null
            val args = parseArguments(raw) ?: return null
            ToolRepairCall(name, args, tool.parameters)
        } else {
            ToolRepairCall(tool.name, tool.parameters.map { ToolRepairArgument(it.name, JsonPrimitive(it.value)) })
        }
        var current = initial
        val applied = mutableListOf<String>()
        for (rule in ToolCallRepairRules.ordered) {
            val next = rule.apply(current) ?: continue
            if (next == current) continue
            current = next
            applied += rule.id
        }
        if (applied.isEmpty()) return null
        val routed = if (isProxy) {
            val encoded = if (current.arguments == initial.arguments) null else
                JsonObject(current.arguments.associate { it.name to it.value }).toString()
            tool.copy(parameters = checkNotNull(current.proxyParameters).map {
                when {
                    it.name == "tool_name" && current.targetName != initial.targetName ->
                        it.copy(value = current.targetName)
                    it.name == "params" && encoded != null -> it.copy(value = encoded)
                    else -> it
                }
            })
        } else {
            tool.copy(name = current.targetName, parameters = current.arguments.map {
                com.ai.assistance.operit.data.model.ToolParameter(it.name, it.value.jsonPrimitive.content)
            })
        }
        return Repair(original, original.copy(tool = routed), initial.targetName, current.targetName,
            current.arguments.map { it.name }, applied, initial.arguments.map { it.name })
    }

    /** Reject duplicate keys before a repair can rewrite an argument object. */
    private fun parseArguments(raw: String): List<ToolRepairArgument>? = runCatching {
        // kotlinx accepts bare literals and raw controls in strings; validate those explicitly.
        var inString = false
        var escaped = false
        raw.forEach { character ->
            if (inString) {
                require(character.code >= 0x20)
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                if (character == '"') inString = true
                if (character.isWhitespace()) require(character in " \t\r\n")
            }
        }
        val parsed = Json.parseToJsonElement(raw) as? JsonObject ?: error("Expected object")
        validateLiterals(parsed)
        val input = JSONTokener(raw)
        consumeUniqueJsonValue(input)
        require(input.nextClean() == '\u0000')
        parsed.map { ToolRepairArgument(it.key, it.value) }
    }.getOrNull()

    private fun consumeUniqueJsonValue(input: JSONTokener) {
        when (input.nextClean()) {
            '{' -> {
                val names = hashSetOf<String>()
                if (input.nextClean() == '}') return
                input.back()
                while (true) {
                    require(input.nextClean() == '"')
                    require(names.add(input.nextString('"')))
                    require(input.nextClean() == ':')
                    consumeUniqueJsonValue(input)
                    when (input.nextClean()) {
                        '}' -> return
                        ',' -> Unit
                        else -> error("Invalid argument object")
                    }
                }
            }
            '[' -> {
                if (input.nextClean() == ']') return
                input.back()
                while (true) {
                    consumeUniqueJsonValue(input)
                    when (input.nextClean()) {
                        ']' -> return
                        ',' -> Unit
                        else -> error("Invalid argument array")
                    }
                }
            }
            else -> {
                input.back()
                input.nextValue()
            }
        }
    }

    private val jsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    private fun validateLiterals(value: JsonElement) {
        when (value) {
            is JsonObject -> value.values.forEach(::validateLiterals)
            is JsonArray -> value.forEach(::validateLiterals)
            is JsonPrimitive -> if (!value.isString) {
                require(value.content in setOf("true", "false", "null") || jsonNumber.matches(value.content))
            }
        }
    }
}
