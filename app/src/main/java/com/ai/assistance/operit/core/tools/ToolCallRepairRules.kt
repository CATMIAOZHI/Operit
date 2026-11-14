package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.data.model.ToolParameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class ToolRepairArgument(val name: String, val value: JsonElement)

internal data class ToolRepairCall(
    val targetName: String,
    val arguments: List<ToolRepairArgument>,
    val proxyParameters: List<ToolParameter>? = null,
)

/** Rules are pure: no IO, permission decisions, model calls or mutation of the input. */
internal data class ToolCallRepairRule(
    val id: String,
    val apply: (ToolRepairCall) -> ToolRepairCall?,
)

internal object ToolCallRepairRules {
    private val repeatedTerminalSeparator = Regex("""^super_admin::+terminal$""")
    private val actionSuffix = Regex("""^([a-z][a-z0-9_]*):([a-z][a-z0-9_]*)$""")

    /**
     * Built-in tools that take an `action`. A model that reads "orbit_reading: where" writes
     * `orbit_reading:where`, which then looks like a package tool. Only repaired when no package
     * or MCP server has that name ([isPackageName]); the tool itself still checks the action.
     */
    private val actionTools: Set<String> by lazy {
        runCatching {
            com.ai.assistance.operit.core.config.SystemToolPrompts
                .getAllCategoriesEn(includeSubagentTools = false)
                .flatMap { it.tools }
                .filter { tool -> tool.parametersStructured.orEmpty().any { it.name == "action" } }
                .mapTo(hashSetOf()) { it.name }
        }.getOrDefault(emptySet())
    }

    /**
     * Whether a JS package or MCP server (installed, active or not) is called this, so its
     * `name:tool` is real. Set by the app once tools are registered; unset, nothing is repaired.
     */
    @Volatile
    internal var isPackageName: (String) -> Boolean = { true }

    // Name normalization precedes canonical-name rules. One pass, no retry loop.
    val ordered = listOf(
        ToolCallRepairRule(ToolCallRepairRouter.SPILLED_PARAMETER) { call -> splitSpilledParameter(call) },
        ToolCallRepairRule(ToolCallRepairRouter.ACTION_IN_TOOL_NAME) { call ->
            val match = actionSuffix.matchEntire(call.targetName) ?: return@ToolCallRepairRule null
            val (tool, action) = match.destructured
            if (tool !in actionTools) return@ToolCallRepairRule null
            if (runCatching { isPackageName(tool) }.getOrDefault(true)) return@ToolCallRepairRule null
            val given = call.arguments.filter { it.name == "action" }
            when {
                given.isEmpty() -> call.copy(targetName = tool,
                    arguments = listOf(ToolRepairArgument("action", JsonPrimitive(action))) + call.arguments)
                given.size == 1 && (given.single().value as? JsonPrimitive)?.content == action ->
                    call.copy(targetName = tool)
                else -> null
            }
        },
        ToolCallRepairRule(ToolCallRepairRouter.MEMORY_FINISH_ALIAS) { call ->
            if (call.targetName != "memory_learning_action" || call.proxyParameters != null ||
                call.arguments.any { it.name !in setOf("action","arguments") } ||
                call.arguments.groupBy { it.name }.any { it.value.size != 1 } ||
                (call.arguments.singleOrNull { it.name == "action" }?.value as? JsonPrimitive)?.content != "memory_learning_finish")
                return@ToolCallRepairRule null
            val field = call.arguments.singleOrNull { it.name == "arguments" }
            if (field != null) {
                val raw = (field.value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return@ToolCallRepairRule null
                if (ToolCallRepairRouter.parseStrictJsonObject(raw)?.isEmpty() != true)
                    return@ToolCallRepairRule null
            }
            call.copy(targetName = "memory_learning_finish", arguments = emptyList())
        },
        ToolCallRepairRule(ToolCallRepairRouter.TERMINAL_SEPARATOR) { call ->
            // Any run of separators is the same typo; every other name stays untouched.
            if (repeatedTerminalSeparator.matches(call.targetName))
                call.copy(targetName = "super_admin:terminal") else null
        },
        ToolCallRepairRule(ToolCallRepairRouter.REDUNDANT_PACKAGE_NAME) { call ->
            val outer = call.proxyParameters
            val packageName = outer?.singleOrNull { it.name == "package_name" }?.value
            if (packageName != null && ':' in call.targetName &&
                packageName == call.targetName.substringBefore(':')) {
                call.copy(proxyParameters = checkNotNull(outer).filterNot { it.name == "package_name" })
            } else null
        },
        ToolCallRepairRule(ToolCallRepairRouter.READ_FILE_LINE_RANGE) { call ->
            if (call.targetName == "read_file" &&
                call.arguments.any { it.name == "start_line" || it.name == "end_line" })
                call.copy(targetName = "read_file_part") else null
        },
        ToolCallRepairRule(ToolCallRepairRouter.TERMINAL_PROXY_YIELD) { call ->
            if (call.targetName != "super_admin:terminal") return@ToolCallRepairRule null
            val outer = call.proxyParameters ?: return@ToolCallRepairRule null
            val misplaced = outer.singleOrNull { it.name == "yieldMs" } ?: return@ToolCallRepairRule null
            val value = misplaced.value.toLongOrNull()?.takeIf { it in 0..30_000 }
                ?: return@ToolCallRepairRule null
            val existing = call.arguments.filter { it.name == "yieldMs" }
            if (existing.size > 1 || existing.any {
                (it.value as? JsonPrimitive)?.content?.toLongOrNull() != value
            }) return@ToolCallRepairRule null
            call.copy(
                proxyParameters = outer.filterNot { it.name == "yieldMs" },
                arguments = if (existing.isNotEmpty()) call.arguments else
                    call.arguments + ToolRepairArgument("yieldMs", JsonPrimitive(value)),
            )
        },
        ToolCallRepairRule(ToolCallRepairRouter.TERMINAL_TIMEOUT) { call ->
            if (call.targetName != "super_admin:terminal") return@ToolCallRepairRule null
            val alias = call.arguments.singleOrNull { it.name == "timeout" }
                ?: return@ToolCallRepairRule null
            val rawTimeout = (alias.value as? JsonPrimitive)?.content?.toLongOrNull()
                ?: return@ToolCallRepairRule null
            // Legacy millisecond aliases remain unchanged. Small integral values have an explicit
            // seconds compatibility range; the ambiguous gap is deliberately not guessed.
            val timeout = when (rawTimeout) {
                in 3..300 -> rawTimeout * 1_000
                in 3_000..Int.MAX_VALUE.toLong() -> rawTimeout
                else -> return@ToolCallRepairRule null
            }
            val canonical = call.arguments.filter { it.name == "timeoutMs" }
            if (canonical.size > 1 || (canonical.size == 1 &&
                (canonical.single().value as? JsonPrimitive)?.content?.toLongOrNull() != timeout)) {
                return@ToolCallRepairRule null
            }
            call.copy(arguments = if (canonical.isEmpty()) {
                call.arguments.map {
                    if (it.name != "timeout") it else it.copy(name = "timeoutMs",
                        value = if (rawTimeout == timeout) it.value
                            else if ((it.value as JsonPrimitive).isString) JsonPrimitive(timeout.toString())
                            else JsonPrimitive(timeout))
                }
            } else {
                call.arguments.filterNot { it.name == "timeout" }
            })
        },
        ToolCallRepairRule(ToolCallRepairRouter.MEMORY_ARGUMENT_ALIAS) { call ->
            if (call.targetName !in MEMORY_REVIEW_TOOLS) return@ToolCallRepairRule null
            val action = (call.arguments.singleOrNull { it.name == MEMORY_ACTION_FIELD }?.value
                as? JsonPrimitive)?.content
            if (action == null || action !in MEMORY_CONTENT_ACTIONS) return@ToolCallRepairRule null
            val field = call.arguments.singleOrNull { it.name == MEMORY_ARGUMENT_FIELD }
                ?: return@ToolCallRepairRule null
            val raw = (field.value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return@ToolCallRepairRule null
            val renamed = renameMemoryArgumentAlias(raw) ?: return@ToolCallRepairRule null
            call.copy(arguments = call.arguments.map {
                if (it.name == MEMORY_ARGUMENT_FIELD) it.copy(value = JsonPrimitive(renamed)) else it
            })
        },
    )
}

/**
 * Tools this was seen on. Their values are short notes written by the model, never file content
 * or commands, where such tags can be the text itself. Add a tool only with the same evidence.
 */
private val SPILLED_PARAMETER_TOOLS = setOf("orbit_submit_monitor_result")

/** The value's own closing tag, then the next parameter opened, and nothing after it. */
private fun spilledParameterTail(name: String) = Regex(
    "</" + Regex.escape(name) + ">\\s*<parameter\\s+name=\"([A-Za-z_][A-Za-z0-9_]{0,63})\"" +
        "(?:\\s+[A-Za-z_][A-Za-z0-9_-]*=\"[^\"<>]*\")*\\s*>\\s*\\z")

/**
 * A model that loses the call format at the end of a long value closes the value itself and opens
 * the next parameter as text: `...</observation>\n<parameter name="userMessage">`. That parameter
 * then never arrives. Only this exact end is repaired: the tags come off the value, and the
 * parameter they name, which must not be in the call, is added empty, as it arrived. Text after
 * the opening tag, the tags anywhere else in a value, or the same end twice over is left alone.
 */
private fun splitSpilledParameter(call: ToolRepairCall): ToolRepairCall? {
    if (call.targetName !in SPILLED_PARAMETER_TOOLS) return null
    if (call.arguments.groupBy { it.name }.any { it.value.size != 1 }) return null
    val names = call.arguments.mapTo(hashSetOf()) { it.name }
    val arguments = ArrayList<ToolRepairArgument>(call.arguments.size + 1)
    for (argument in call.arguments) {
        val text = (argument.value as? JsonPrimitive)?.takeIf { it.isString }?.content
        // Nearly every value stops at the first check, before a pattern is built for it.
        val pattern = text?.takeIf { "<parameter" in it }?.let { spilledParameterTail(argument.name) }
        val tail = pattern?.find(checkNotNull(text))
        val head = tail?.let { checkNotNull(text).substring(0, it.range.first).trimEnd() }
        // One repair leaves nothing for a second one: a value that would still end this way is not touched.
        if (tail == null || head == null || pattern.find(head) != null || !names.add(tail.groupValues[1])) {
            arguments += argument
            continue
        }
        arguments += argument.copy(value = JsonPrimitive(head))
        arguments += ToolRepairArgument(tail.groupValues[1], JsonPrimitive(""))
    }
    return if (arguments.size == call.arguments.size) null else call.copy(arguments = arguments)
}

private const val MEMORY_ACTION_FIELD = "action"
private const val MEMORY_ARGUMENT_FIELD = "arguments"
private const val MEMORY_ALIASED_KEY = "new_text"
private const val MEMORY_CANONICAL_KEY = "content"

/**
 * Both model-visible entry points carry the same contract: `action` plus one opaque `arguments`
 * object, executed by the same [com.ai.assistance.operit.api.chat.library.MemoryLearningActions].
 * `memory_review` is deliberately absent: its parameters are flat, with no `arguments` object.
 */
private val MEMORY_REVIEW_TOOLS = setOf("memory_learning_action", "learning_manage")

/** Only these actions read the argument object's `content`, so only these gain from the rename. */
private val MEMORY_CONTENT_ACTIONS = setOf(
    "memory_change", "skill_create", "skill_write", "skill_patch", "skill_remove_file",
)

/**
 * The review tool takes one opaque argument object, so a model can reach for the old_text/new_text
 * pair that diff-style editors use. `content` is the only key the tool reads, and an edit sent as
 * `new_text` is applied as an empty replacement, which deletes the matched text instead of
 * rewriting it. Sending both keys is ambiguous, so nothing is rewritten in that case, and the same
 * strict object parse the proxy path uses rejects duplicate keys instead of collapsing them.
 */
private fun renameMemoryArgumentAlias(raw: String): String? {
    val target = ToolCallRepairRouter.parseStrictJsonObject(raw) ?: return null
    if (target.containsKey(MEMORY_CANONICAL_KEY)) return null
    val alias = target[MEMORY_ALIASED_KEY] as? JsonPrimitive ?: return null
    if (!alias.isString) return null
    return JsonObject(target.map { (key, value) ->
        if (key == MEMORY_ALIASED_KEY) MEMORY_CANONICAL_KEY to value else key to value
    }.toMap()).toString()
}
