package com.ai.assistance.operit.ui.permissions

import android.content.Context
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.agent.AgentProfileRepository
import com.ai.assistance.operit.core.agent.SubagentCoordinator
import com.ai.assistance.operit.core.agent.SubagentExecutionException
import com.ai.assistance.operit.core.agent.SubagentTaskRequest
import com.ai.assistance.operit.core.agent.SubagentTaskResult
import com.ai.assistance.operit.core.tools.PermissionReviewSubmissionTool
import com.ai.assistance.operit.core.tools.PermissionReviewSubmissionRegistry
import com.ai.assistance.operit.core.tools.PermissionReviewInspectionTool
import com.ai.assistance.operit.core.tools.PermissionReviewInternalTools
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.preferences.FunctionConfigMapping
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class PermissionReviewOutcome {
    ALLOW,
    DENY,
}

@kotlinx.serialization.Serializable
enum class PermissionReviewRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

@kotlinx.serialization.Serializable
enum class PermissionReviewAuthorization {
    UNKNOWN,
    LOW,
    MEDIUM,
    HIGH,
}

@kotlinx.serialization.Serializable
enum class PermissionReviewFailureKind {
    INVALID_OUTPUT,
    TIMED_OUT,
    REVIEWER_ERROR,
}

data class PermissionReviewDecision(
    val outcome: PermissionReviewOutcome,
    val riskLevel: PermissionReviewRiskLevel,
    val userAuthorization: PermissionReviewAuthorization,
    val rationale: String,
    val failureKind: PermissionReviewFailureKind? = null,
    val attemptCount: Int = 0,
    val reviewerTaskId: String? = null,
)

internal object PermissionReviewResponsePolicy {
    fun parseAndEnforce(
        tool: AITool,
        exactOverride: Boolean = false,
        expectedReviewId: String? = null,
    ): PermissionReviewDecision? {
        if (tool.name != PermissionReviewSubmissionTool.NAME) return null
        val expectedNames =
            setOf("review_id", "outcome", "risk_level", "user_authorization", "rationale")
        if (tool.parameters.any { it.name !in expectedNames }) return null
        val grouped = tool.parameters.groupBy { it.name }
        if (grouped.values.any { it.size != 1 }) return null
        val reviewId = grouped["review_id"]?.singleOrNull()?.value?.trim()
        // The review prompt always instructs the reviewer to echo the review_id, and the runtime
        // rejects submissions that omit it. Requiring it here keeps historical reconstruction
        // (where the expected value is unavailable) from ever reporting a malformed review as
        // allowed.
        if (reviewId.isNullOrBlank()) return null
        if (expectedReviewId != null && reviewId != expectedReviewId) {
            return null
        }
        val outcome = grouped["outcome"]?.singleOrNull()?.value ?: return null
        val riskLevel = grouped["risk_level"]?.singleOrNull()?.value ?: return null
        val userAuthorization =
            grouped["user_authorization"]?.singleOrNull()?.value ?: return null
        val rationale =
            grouped["rationale"]?.singleOrNull()?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: defaultRationale(outcome)
        return enforceFields(outcome, riskLevel, userAuthorization, rationale, exactOverride)
    }

    suspend fun extractToolCallAndEnforce(
        response: String,
        exactOverride: Boolean = false,
        expectedReviewId: String? = null,
    ): PermissionReviewDecision? {
        val invocations = ToolExecutionManager.extractToolInvocations(response)
        if (invocations.any { invocation ->
                invocation.tool.name !in PermissionReviewInternalTools.names
            }
        ) {
            return null
        }
        return invocations
            .filter { invocation -> invocation.tool.name == PermissionReviewSubmissionTool.NAME }
            .singleOrNull()
            ?.tool
            ?.let { tool -> parseAndEnforce(tool, exactOverride, expectedReviewId) }
    }

    private fun enforceFields(
        outcome: String,
        riskLevel: String,
        userAuthorization: String,
        rationale: String,
        exactOverride: Boolean,
    ): PermissionReviewDecision? {
        val requestedOutcome =
            when (outcome.trim().lowercase()) {
                "allow" -> PermissionReviewOutcome.ALLOW
                "deny" -> PermissionReviewOutcome.DENY
                else -> return null
            }
        val risk =
            when (riskLevel.trim().lowercase()) {
                "low" -> PermissionReviewRiskLevel.LOW
                "medium" -> PermissionReviewRiskLevel.MEDIUM
                "high" -> PermissionReviewRiskLevel.HIGH
                "critical" -> PermissionReviewRiskLevel.CRITICAL
                else -> return null
            }
        val authorization =
            when (userAuthorization.trim().lowercase()) {
                "unknown" -> PermissionReviewAuthorization.UNKNOWN
                "low" -> PermissionReviewAuthorization.LOW
                "medium" -> PermissionReviewAuthorization.MEDIUM
                "high" -> PermissionReviewAuthorization.HIGH
                else -> return null
            }
        val normalizedRationale = rationale.trim()
        if (normalizedRationale.isEmpty()) return null

        val enforcedOutcome =
            when {
                requestedOutcome == PermissionReviewOutcome.DENY -> PermissionReviewOutcome.DENY
                risk == PermissionReviewRiskLevel.CRITICAL -> PermissionReviewOutcome.DENY
                exactOverride -> PermissionReviewOutcome.ALLOW
                risk == PermissionReviewRiskLevel.HIGH &&
                    authorization < PermissionReviewAuthorization.MEDIUM ->
                    PermissionReviewOutcome.DENY
                else -> PermissionReviewOutcome.ALLOW
            }
        return PermissionReviewDecision(
            outcome = enforcedOutcome,
            riskLevel = risk,
            userAuthorization = authorization,
            rationale = normalizedRationale,
        )
    }

    private fun defaultRationale(outcome: String): String =
        if (outcome.trim().equals("allow", ignoreCase = true)) {
            "The reviewer submitted an allow decision without a rationale."
        } else {
            "The reviewer submitted a deny decision without a rationale."
        }

    fun failed(reason: String, failureKind: PermissionReviewFailureKind): PermissionReviewDecision =
        PermissionReviewDecision(
            outcome = PermissionReviewOutcome.DENY,
            riskLevel = PermissionReviewRiskLevel.HIGH,
            userAuthorization = PermissionReviewAuthorization.UNKNOWN,
            rationale = reason,
            failureKind = failureKind,
        )
}

internal data class PermissionReviewerModelSelection(
    val configId: String,
    val modelIndex: Int,
    val reentrantParentModelConfigId: String?,
)

internal fun resolvePermissionReviewerModelSelection(
    configuredMapping: FunctionConfigMapping,
    parentModelConfigId: String?,
    @Suppress("UNUSED_PARAMETER") parentModelIndex: Int?,
): PermissionReviewerModelSelection {
    val canReuseParentLease =
        !parentModelConfigId.isNullOrBlank() &&
            configuredMapping.configId == parentModelConfigId
    return PermissionReviewerModelSelection(
        configId = configuredMapping.configId,
        modelIndex = configuredMapping.modelIndex,
        reentrantParentModelConfigId = parentModelConfigId.takeIf { canReuseParentLease },
    )
}

/** Runs a separate Subagent turn with only the internal review-submission result tool. */
class AgentToolPermissionReviewer private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val coordinator = SubagentCoordinator.getInstance(appContext)
    private val chatCore =
        ChatRuntimeHolder.getInstance(appContext).getCore(ChatRuntimeSlot.MAIN)
    private val functionalConfigManager = FunctionalConfigManager(appContext)
    private val policyStore = PermissionReviewPolicyStore(appContext)
    private val requestJson = Json { encodeDefaults = true }

    init {
        PermissionReviewEventRepository.initialize(appContext)
    }

    suspend fun review(
        tool: AITool,
        operationDescription: String,
        reviewContext: ToolPermissionReviewContext,
    ): PermissionReviewDecision {
        val parentChatId = reviewContext.callerChatId?.trim().orEmpty()
        if (parentChatId.isEmpty()) {
            return PermissionReviewResponsePolicy.failed(
                reason = "The approval reviewer requires an originating chat.",
                failureKind = PermissionReviewFailureKind.REVIEWER_ERROR,
            )
        }

        val reviewId = PermissionReviewInspectionRegistry.newReviewId()
        val action =
            PermissionReviewAction.fromTool(
                tool = tool,
                operationDescription = operationDescription,
                reviewContext = reviewContext,
                targetId = reviewContext.targetId ?: reviewId,
            )
        val actionFingerprint = action.fingerprint()
        val startedAt = System.currentTimeMillis()
        val transcript =
            buildTranscript(
                parentChatId = parentChatId,
                timingScopeId = reviewContext.timingScopeId,
                liveAssistantContent = reviewContext.liveAssistantContent,
            )
        val policySnapshot = policyStore.getSnapshot()
        val reviewerModel =
            resolvePermissionReviewerModelSelection(
                configuredMapping =
                    functionalConfigManager.getConfigMappingForFunction(
                        FunctionType.PERMISSION_REVIEWER
                    ),
                parentModelConfigId = reviewContext.parentModelConfigId,
                parentModelIndex = reviewContext.parentModelIndex,
            )
        val exactOverride =
            PermissionReviewExactOverrideStore.reserve(parentChatId, actionFingerprint, reviewId)
        var latestReviewerTaskId =
            exactOverride
                ?.originalReviewId
                ?.let(PermissionReviewEventRepository::findById)
                ?.reviewerTaskId
        var exactOverrideCommitted = false
        try {
            PermissionReviewEventRepository.publish(
            PermissionReviewEvent(
                id = reviewId,
                parentChatId = parentChatId,
                timingScopeId = reviewContext.timingScopeId,
                invocationIndex = reviewContext.invocationIndex,
                batchPosition = reviewContext.batchPosition,
                batchSize = reviewContext.batchSize,
                action = action,
                actionFingerprint = actionFingerprint,
                status = PermissionReviewStatus.IN_PROGRESS,
                startedAt = startedAt,
                exactOverrideApplied = exactOverride != null,
                reviewerTaskId = latestReviewerTaskId,
            )
        )
        val initialPrompt =
            buildReviewPrompt(
                reviewId = reviewId,
                action = action,
                reviewContext = reviewContext,
                transcript = transcript,
                policySnapshot = policySnapshot,
                exactOverrideReviewId = exactOverride?.originalReviewId,
            )
        PermissionReviewInspectionRegistry.register(
            reviewId,
            reviewContext.workspacePath,
            reviewContext.workspaceEnv,
            action,
        )
        PermissionReviewSubmissionRegistry.register(reviewId)
        val decision =
            try {
                withTimeoutOrNull(REVIEW_TIMEOUT_MS) {
                    var prompt = initialPrompt
                    for (attempt in 1..MAX_PARSE_ATTEMPTS) {
                        val request =
                            SubagentTaskRequest(
                                parentChatId = parentChatId,
                                parentToolCallId = reviewContext.targetId,
                                parentAgentName = reviewContext.conversationLabel,
                                title =
                                    appContext.getString(
                                        com.ai.assistance.operit.R.string.permission_review_task_title,
                                        "[${reviewContext.batchPosition}/${reviewContext.batchSize}] ${action.summary}",
                                    ),
                                prompt = prompt,
                                subagentType = REVIEWER_PROFILE_ID,
                                taskId = latestReviewerTaskId,
                                parentModelConfigId = reviewerModel.configId,
                                parentModelIndex = reviewerModel.modelIndex,
                                toolsEnabled = true,
                                isolatedToolPrompts = PermissionReviewInternalTools.prompts,
                                terminalToolNames = setOf(PermissionReviewSubmissionTool.NAME),
                                promptHooksEnabled = false,
                                reentrantParentModelConfigId =
                                    reviewerModel.reentrantParentModelConfigId,
                            )
                        val result =
                            try {
                                coordinator.runTask(request)
                            } catch (error: Exception) {
                                if (error is SubagentExecutionException) {
                                    latestReviewerTaskId = error.taskId
                                }
                                if (attempt < MAX_PARSE_ATTEMPTS && isTransientReviewerError(error)) {
                                    // A failed turn may have reached the terminal tool before the
                                    // child task failed. Never let that stale submission satisfy a
                                    // later retry.
                                    PermissionReviewSubmissionRegistry.consume(reviewId)
                                    delay(RETRY_BACKOFF_MS * attempt)
                                    continue
                                }
                                throw error
                            }
                        when (result) {
                            is SubagentTaskResult.Completed -> {
                                latestReviewerTaskId = result.run.id
                                PermissionReviewSubmissionRegistry.consume(reviewId)?.let { submission ->
                                    PermissionReviewResponsePolicy.parseAndEnforce(
                                    tool = submission,
                                    exactOverride = exactOverride != null,
                                    expectedReviewId = reviewId,
                                )
                                }?.let { parsed ->
                                    return@withTimeoutOrNull parsed.copy(
                                        attemptCount = attempt,
                                        reviewerTaskId = latestReviewerTaskId,
                                    )
                                }
                                prompt =
                                    "Your previous response did not contain exactly one valid " +
                                    "${PermissionReviewSubmissionTool.NAME} call. Keep the original " +
                                    "decision, correct only the submission format, and call that tool " +
                                        "exactly once. Do not return JSON or call any other tool. This is " +
                                        "attempt ${attempt + 1} of $MAX_PARSE_ATTEMPTS."
                            }
                            is SubagentTaskResult.AlreadyRunning -> {
                                // A simultaneous review never shares another action's transcript.
                                latestReviewerTaskId = null
                                delay(RETRY_BACKOFF_MS)
                            }
                        }
                    }
                    PermissionReviewResponsePolicy.failed(
                        reason = "The approval reviewer returned invalid structured output.",
                        failureKind = PermissionReviewFailureKind.INVALID_OUTPUT,
                    ).copy(
                        attemptCount = MAX_PARSE_ATTEMPTS,
                        reviewerTaskId = latestReviewerTaskId,
                    )
                }
            } catch (cancelled: CancellationException) {
                completeEvent(
                    reviewId,
                    PermissionReviewStatus.ABORTED,
                    "Permission review was cancelled.",
                )
                PermissionReviewExactOverrideStore.release(reviewId)
                throw cancelled
            } catch (error: Exception) {
                AppLogger.e(TAG, "Permission reviewer failed closed", error)
                PermissionReviewResponsePolicy.failed(
                    reason = "The approval reviewer failed.",
                    failureKind = PermissionReviewFailureKind.REVIEWER_ERROR,
                ).copy(reviewerTaskId = latestReviewerTaskId)
            } finally {
                PermissionReviewInspectionRegistry.unregister(reviewId)
                PermissionReviewSubmissionRegistry.unregister(reviewId)
            }

        val finalDecision =
            decision ?: PermissionReviewResponsePolicy.failed(
                reason = "The approval reviewer timed out.",
                failureKind = PermissionReviewFailureKind.TIMED_OUT,
            ).copy(reviewerTaskId = latestReviewerTaskId)
        if (exactOverride != null && finalDecision.failureKind == null &&
            finalDecision.outcome == PermissionReviewOutcome.ALLOW
        ) {
            PermissionReviewExactOverrideStore.commit(reviewId)
            exactOverrideCommitted = true
        } else if (exactOverride != null) {
            PermissionReviewExactOverrideStore.release(reviewId)
        }
        completeEvent(
            reviewId = reviewId,
            status =
                when {
                    finalDecision.failureKind == PermissionReviewFailureKind.TIMED_OUT ->
                        PermissionReviewStatus.TIMED_OUT
                    finalDecision.failureKind != null -> PermissionReviewStatus.FAILED
                    finalDecision.outcome == PermissionReviewOutcome.ALLOW ->
                        PermissionReviewStatus.APPROVED
                    else -> PermissionReviewStatus.DENIED
                },
            rationale = finalDecision.rationale,
            decision = finalDecision,
        )
        return finalDecision
        } finally {
            if (exactOverride != null && !exactOverrideCommitted) {
                PermissionReviewExactOverrideStore.release(reviewId)
            }
        }
    }

    private suspend fun buildTranscript(
        parentChatId: String,
        timingScopeId: String?,
        liveAssistantContent: String?,
    ): String {
        val messages =
            runCatching {
                    chatCore.getChatHistoryDelegate().getChatHistory(parentChatId)
                }
                .getOrElse { emptyList() }
                .takeLast(MAX_TRANSCRIPT_CANDIDATES)
        val newestFirst = mutableListOf<String>()
        var selectedChars = 0
        val sanitizedLiveAssistant =
            liveAssistantContent
                ?.let { content ->
                    permissionReviewTranscriptContent(
                        sender = "ai",
                        roleName = "assistant",
                        content = content,
                    )
                }
                ?.takeIf(String::isNotBlank)
        val persistedLiveAssistant =
            sanitizedLiveAssistant?.let {
                messages.lastOrNull { message ->
                    isAssistantTranscriptMessage(message.sender, message.roleName) &&
                        message.timestamp.toString() == timingScopeId
                }
            }
        sanitizedLiveAssistant?.let { liveContent ->
            val role =
                persistedLiveAssistant
                    ?.let { message -> message.roleName.ifBlank { message.sender } }
                    ?: "assistant"
            val entry = "[$role]\n${truncateTranscriptMessage(liveContent)}\n"
            newestFirst += entry
            selectedChars += entry.length
        }
        for (message in messages.asReversed()) {
            if (persistedLiveAssistant?.timestamp == message.timestamp) continue
            val role = message.roleName.ifBlank { message.sender }
            val content =
                truncateTranscriptMessage(
                    permissionReviewTranscriptContent(
                        sender = message.sender,
                        roleName = message.roleName,
                        content = message.content,
                    )
                )
            if (content.isBlank()) continue
            val entry = "[$role]\n$content\n"
            if (selectedChars + entry.length > MAX_TRANSCRIPT_CHARS) continue
            newestFirst += entry
            selectedChars += entry.length
            if (newestFirst.size >= MAX_TRANSCRIPT_MESSAGES) break
        }
        val selected = newestFirst.asReversed().toMutableList()
        val hasUserAnchor = selected.any { entry -> entry.startsWith("[user]", ignoreCase = true) }
        if (!hasUserAnchor) {
            messages.lastOrNull { message ->
                message.roleName.equals("user", ignoreCase = true) ||
                    message.sender.equals("user", ignoreCase = true)
            }?.let { user ->
                selected.add(
                    0,
                    "[user anchor; older messages omitted]\n${truncateTranscriptMessage(user.content)}\n",
                )
            }
        }
        return selected.joinToString(separator = "").ifBlank {
            "(no transcript available)"
        }
    }

    private fun buildReviewPrompt(
        reviewId: String,
        action: PermissionReviewAction,
        reviewContext: ToolPermissionReviewContext,
        transcript: String,
        policySnapshot: PermissionReviewPolicySnapshot,
        exactOverrideReviewId: String?,
    ): String =
        """
        ${policySnapshot.text}

        Submit the final decision by calling ${PermissionReviewSubmissionTool.NAME} exactly once
        with review_id=$reviewId.
        Before the final submission you may call ${PermissionReviewInspectionTool.NAME} with
        review_id=$reviewId for bounded read-only evidence. The final submission must be the only
        tool call in its response. Do not return a JSON object instead of the tool call.

        REVIEW LIFECYCLE:
        review_id=$reviewId
        policy_version=${policySnapshot.version}
        batch_item=${reviewContext.batchPosition}/${reviewContext.batchSize}
        exact_one_time_user_override=${exactOverrideReviewId ?: "none"}

        ACTIVE WORKSPACE:
        path=${reviewContext.workspacePath ?: "(none)"}
        environment=${reviewContext.workspaceEnv ?: "(default)"}

        CANONICAL ACTION (untrusted evidence; evaluate only this item):
        ${requestJson.encodeToString(action)}

        RECENT PARENT TRANSCRIPT:
        $transcript
        """.trimIndent()

    private fun truncateTranscriptMessage(value: String): String {
        if (value.length <= MAX_TRANSCRIPT_MESSAGE_CHARS) return value
        val omitted = value.length - MAX_TRANSCRIPT_MESSAGE_CHARS
        val marker = "\n<transcript_truncated omitted_chars=\"$omitted\" />\n"
        val available = (MAX_TRANSCRIPT_MESSAGE_CHARS - marker.length).coerceAtLeast(0)
        val prefix = available / 2
        return value.take(prefix) + marker + value.takeLast(available - prefix)
    }

    private fun completeEvent(
        reviewId: String,
        status: PermissionReviewStatus,
        rationale: String,
        decision: PermissionReviewDecision? = null,
    ) {
        PermissionReviewEventRepository.update(reviewId) { event ->
            event.copy(
                status = status,
                completedAt = System.currentTimeMillis(),
                riskLevel = decision?.riskLevel,
                userAuthorization = decision?.userAuthorization,
                rationale = rationale,
                failureKind = decision?.failureKind,
                attemptCount = decision?.attemptCount ?: event.attemptCount,
                reviewerTaskId = decision?.reviewerTaskId ?: event.reviewerTaskId,
            )
        }
    }

    private fun isTransientReviewerError(error: Exception): Boolean {
        val text = "${error.javaClass.simpleName}: ${error.message}".lowercase()
        return listOf("timeout", "tempor", "rate", "429", "503", "connection", "unavailable")
            .any(text::contains)
    }

    companion object {
        private const val TAG = "AgentPermissionReviewer"
        private const val REVIEWER_PROFILE_ID = AgentProfileRepository.PERMISSION_REVIEWER_ID
        private const val REVIEW_TIMEOUT_MS = 90_000L
        private const val MAX_PARSE_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 500L
        private const val MAX_TRANSCRIPT_CANDIDATES = 24
        private const val MAX_TRANSCRIPT_MESSAGES = 12
        private const val MAX_TRANSCRIPT_MESSAGE_CHARS = 4_000
        private const val MAX_TRANSCRIPT_CHARS = 16_000

        @Volatile private var INSTANCE: AgentToolPermissionReviewer? = null

        fun getInstance(context: Context): AgentToolPermissionReviewer =
            INSTANCE
                ?: synchronized(this) {
                    INSTANCE
                        ?: AgentToolPermissionReviewer(context.applicationContext).also {
                            INSTANCE = it
                            AppLogger.d(TAG, "Independent permission reviewer initialized")
                        }
                }
    }
}

internal fun permissionReviewTranscriptContent(
    sender: String,
    roleName: String,
    content: String,
): String =
    if (sender.equals("ai", ignoreCase = true) ||
        roleName.equals("assistant", ignoreCase = true)
    ) {
        ChatUtils.removeThinkingContent(content)
    } else {
        content
    }

private fun isAssistantTranscriptMessage(sender: String, roleName: String): Boolean =
    sender.equals("ai", ignoreCase = true) || roleName.equals("assistant", ignoreCase = true)
