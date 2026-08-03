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
                "Inspect read-only evidence for the active permission review. This tool cannot " +
                    "execute commands, access the network, or modify files. Local file and grep " +
                    "access is unrestricted: read any path on the device or in the workspace, " +
                    "search any directory with grep, and perform as many inspection calls as " +
                    "needed.",
            parametersStructured =
                listOf(
                    ToolParameterSchema(
                        name = "review_id",
                        description = "Opaque capability from the review prompt.",
                    ),
                    ToolParameterSchema(
                        name = "operation",
                        description =
                            "One of: path_metadata, read_text, grep, git_context. " +
                                "path_metadata lists directory entries (max 80) with size metadata. " +
                                "read_text reads text content (max 64K chars), optionally a line " +
                                "range with start_line/end_line. grep searches a directory with a " +
                                "local regex engine (never calls a remote model) and returns up to " +
                                "100 matches. git_context reports the .git HEAD and path.",
                    ),
                    ToolParameterSchema(
                        name = "path",
                        description =
                            "File or directory to inspect for path_metadata, read_text, or grep. " +
                                "Absolute paths are resolved directly; relative paths resolve " +
                                "against the active workspace when one exists.",
                        required = false,
                    ),
                    ToolParameterSchema(
                        name = "environment",
                        description =
                            "Workspace environment of the path when provided by the review " +
                                "context (for example android or linux). Omit for the default.",
                        required = false,
                    ),
                    ToolParameterSchema(
                        name = "start_line",
                        description =
                            "First 1-based line to read for read_text with a line range.",
                        required = false,
                    ),
                    ToolParameterSchema(
                        name = "end_line",
                        description =
                            "Last 1-based line (inclusive) to read for read_text with a line range.",
                        required = false,
                    ),
                    ToolParameterSchema(
                        name = "pattern",
                        description = "Regex pattern to search for grep.",
                        required = false,
                    ),
                    ToolParameterSchema(
                        name = "case_insensitive",
                        description =
                            "Whether grep should match case-insensitively. Default false.",
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
                environment = values["environment"],
                startLine = values["start_line"]?.toIntOrNull(),
                endLine = values["end_line"]?.toIntOrNull(),
                pattern = values["pattern"],
                caseInsensitive = values["case_insensitive"]?.toBoolean() ?: false,
            )
        return ToolResult(
            toolName = NAME,
            success = !output.startsWith("Inspection rejected") && !output.startsWith("grep error"),
            result = StringResultData(output),
            error =
                output.takeIf {
                    it.startsWith("Inspection rejected") || it.startsWith("grep error")
                },
        )
    }
}

object PermissionReviewInternalTools {
    val names = setOf(PermissionReviewSubmissionTool.NAME, PermissionReviewInspectionTool.NAME)
    val prompts = listOf(PermissionReviewInspectionTool.prompt, PermissionReviewSubmissionTool.prompt)
}
