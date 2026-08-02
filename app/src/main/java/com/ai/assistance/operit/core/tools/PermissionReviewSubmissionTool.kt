package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Internal, side-effect-free result channel exposed only to permission-review turns.
 *
 * The executor merely acknowledges persistence of the model-generated tool call. The permission
 * decision is parsed and enforced by the host after the reviewer turn completes.
 */
object PermissionReviewSubmissionTool {
    const val NAME = "submit_permission_review"

    val prompt =
        ToolPrompt(
            name = NAME,
            description =
                "Submit the final permission-review decision. Call this exactly once after " +
                    "reviewing the proposed action. This tool does not execute the reviewed action.",
            parametersStructured =
                listOf(
                    ToolParameterSchema(
                        name = "review_id",
                        description = "Opaque capability from the review prompt.",
                    ),
                    ToolParameterSchema(
                        name = "outcome",
                        description = "Final decision: allow or deny.",
                    ),
                    ToolParameterSchema(
                        name = "risk_level",
                        description = "Intrinsic risk: low, medium, high, or critical.",
                    ),
                    ToolParameterSchema(
                        name = "user_authorization",
                        description = "Authorization specificity: unknown, low, medium, or high.",
                    ),
                    ToolParameterSchema(
                        name = "rationale",
                        description = "Concise reason for the decision.",
                        required = false,
                    ),
                ),
        )

    fun acknowledge(context: Context, tool: AITool): ToolResult {
        val reviewId =
            tool.parameters.singleOrNull { parameter -> parameter.name == "review_id" }?.value
                ?.trim()
                .orEmpty()
        val accepted = PermissionReviewSubmissionRegistry.submit(reviewId, tool)
        return ToolResult(
            toolName = tool.name,
            success = accepted,
            result =
                StringResultData(
                    if (accepted) {
                        context.getString(R.string.permission_review_submitted)
                    } else {
                        "Permission review submission rejected."
                    }
                ),
            error = "Permission review submission rejected.".takeUnless { accepted },
        )
    }
}

/** Records only submissions that reached the capability-bound tool executor. */
object PermissionReviewSubmissionRegistry {
    private val activeReviewIds = ConcurrentHashMap.newKeySet<String>()
    private val submissions = ConcurrentHashMap<String, AITool>()

    fun register(reviewId: String) {
        submissions.remove(reviewId)
        activeReviewIds.add(reviewId)
    }

    fun unregister(reviewId: String) {
        activeReviewIds.remove(reviewId)
        submissions.remove(reviewId)
    }

    fun submit(reviewId: String, tool: AITool): Boolean {
        if (reviewId.isBlank() || reviewId !in activeReviewIds) return false
        return submissions.putIfAbsent(reviewId, tool) == null
    }

    fun consume(reviewId: String): AITool? = submissions.remove(reviewId)
}
