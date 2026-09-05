package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ToolErrorRecord(
    val id: String,
    val occurredAt: Long,
    val toolName: String,
    val parameters: List<ToolParameter>,
    val error: String,
    val executionFailed: Boolean,
    val undeclaredParameters: List<String>,
) {
    fun toJson(): String = Json.encodeToString(this)
}

internal fun undeclaredToolParameters(tool: AITool, declaredNames: Set<String>?): List<String> =
    if (declaredNames == null) emptyList()
    else tool.parameters.map { it.name }.filter { it !in declaredNames }.distinct()

/** A fresh observer per invocation; declarations come from the executor actually being called. */
class ToolParameterObservation(
    private val rawTool: AITool,
    private val targetTool: AITool,
    private val onFailure: (Exception) -> Unit,
) {
    private val issues = linkedSetOf<String>()

    @Synchronized
    fun inspect(tool: AITool, declaration: () -> Set<String>?) {
        // Nested plugin calls must not attribute their parameters to the outer invocation.
        val original = when (tool.name) {
            rawTool.name -> rawTool
            targetTool.name -> targetTool
            else -> return
        }
        try {
            val prefix = if (original.name != rawTool.name) "params." else ""
            undeclaredToolParameters(original, declaration()).forEach { issues += prefix + it }
        } catch (e: Exception) {
            onFailure(e)
        }
    }

    @Synchronized
    fun snapshot(): List<String> = issues.toList()
}

/** Called once on the final result, including successful calls with undeclared parameters. */
internal fun createToolErrorRecord(
    id: String,
    invocation: ToolInvocation,
    result: ToolResult,
    occurredAt: Long,
    undeclaredParameters: List<String> = emptyList(),
): ToolErrorRecord? {
    if (result.success && undeclaredParameters.isEmpty()) return null
    return ToolErrorRecord(
        id = id,
        occurredAt = occurredAt,
        toolName = result.toolName.ifBlank { invocation.tool.name },
        parameters = invocation.tool.parameters,
        error = buildList {
            if (undeclaredParameters.isNotEmpty()) add("Undeclared parameters: ${undeclaredParameters.joinToString(", ")}")
            if (!result.success) add(result.error?.takeIf { it.isNotBlank() } ?: result.result.toString())
        }.joinToString("\n"),
        executionFailed = !result.success,
        undeclaredParameters = undeclaredParameters,
    )
}
