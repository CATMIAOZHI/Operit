package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.data.model.ToolParameter
import kotlinx.serialization.json.JsonElement
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
    // Name normalization precedes canonical-name rules. One pass, no retry loop.
    val ordered = listOf(
        ToolCallRepairRule(ToolCallRepairRouter.TERMINAL_SEPARATOR) { call ->
            if (call.targetName == "super_admin::terminal")
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
        ToolCallRepairRule(ToolCallRepairRouter.TERMINAL_TIMEOUT) { call ->
            if (call.targetName != "super_admin:terminal") return@ToolCallRepairRule null
            val alias = call.arguments.singleOrNull { it.name == "timeout" }
                ?: return@ToolCallRepairRule null
            val timeout = (alias.value as? JsonPrimitive)?.content?.toLongOrNull()
                ?.takeIf { it in 3_000..Int.MAX_VALUE.toLong() } ?: return@ToolCallRepairRule null
            val canonical = call.arguments.filter { it.name == "timeoutMs" }
            if (canonical.size > 1 || (canonical.size == 1 &&
                (canonical.single().value as? JsonPrimitive)?.content?.toLongOrNull() != timeout)) {
                return@ToolCallRepairRule null
            }
            call.copy(arguments = if (canonical.isEmpty()) {
                call.arguments.map { if (it.name == "timeout") it.copy(name = "timeoutMs") else it }
            } else {
                call.arguments.filterNot { it.name == "timeout" }
            })
        },
    )
}
