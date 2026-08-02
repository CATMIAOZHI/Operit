package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.ui.permissions.PermissionReviewInspectionRegistry

/** A capability-bound, read-only evidence channel available only during an active review. */
object PermissionReviewInspectionTool {
    const val NAME = "inspect_permission_review_context"

    val prompt =
        ToolPrompt(
            name = NAME,
            description =
                "Inspect bounded, read-only evidence for the active permission review. " +
                    "This tool cannot execute commands, access the network, or modify files.",
            parametersStructured =
                listOf(
                    ToolParameterSchema(
                        name = "review_id",
                        description = "Opaque capability from the review prompt.",
                    ),
                    ToolParameterSchema(
                        name = "operation",
                        description = "One of: path_metadata, read_text, git_context.",
                    ),
                    ToolParameterSchema(
                        name = "path",
                        description = "Path to inspect for path_metadata or read_text.",
                        required = false,
                    ),
                ),
        )

    fun inspect(tool: AITool): ToolResult {
        val values = tool.parameters.associate { parameter -> parameter.name to parameter.value }
        val reviewId = values["review_id"].orEmpty()
        val operation = values["operation"].orEmpty()
        val output =
            PermissionReviewInspectionRegistry.inspect(
                reviewId = reviewId,
                operation = operation,
                requestedPath = values["path"],
            )
        return ToolResult(
            toolName = NAME,
            success = !output.startsWith("Inspection rejected"),
            result = StringResultData(output),
            error = output.takeIf { it.startsWith("Inspection rejected") },
        )
    }
}

object PermissionReviewInternalTools {
    val names = setOf(PermissionReviewSubmissionTool.NAME, PermissionReviewInspectionTool.NAME)
    val prompts = listOf(PermissionReviewInspectionTool.prompt, PermissionReviewSubmissionTool.prompt)
}
