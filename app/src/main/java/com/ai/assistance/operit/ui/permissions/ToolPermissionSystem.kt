package com.ai.assistance.operit.ui.permissions

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import androidx.compose.material3.ColorScheme
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.data.model.AITool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

// Define DataStore
private val Context.toolPermissionsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tool_permissions")

internal object PermissionCircuitBreakerNoticeState {
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount = _pendingCount.asStateFlow()

    fun enqueue() {
        _pendingCount.update { count -> count + 1 }
    }

    fun clear() {
        _pendingCount.value = 0
    }
}

/**
 * Permission levels for tool operations
 */
enum class PermissionLevel {
    ALLOW,       // Allow automatically without asking
    AUTO_REVIEW, // Allow proven workspace operations, review everything else
    ASK,         // Always ask
    FORBID;      // Never allow

    companion object {
        fun fromString(value: String?): PermissionLevel {
            return when (value) {
                "ALLOW" -> ALLOW
                "AUTO_REVIEW" -> AUTO_REVIEW
                // The three former middle levels were merged into AUTO_REVIEW. Preferences stored
                // by an older build are migrated here instead of silently falling back to ASK.
                "WORKSPACE" -> AUTO_REVIEW
                "WORKSPACE_REVIEWER" -> AUTO_REVIEW
                "REVIEWER" -> AUTO_REVIEW
                "CAUTION" -> ASK
                "ASK" -> ASK
                "FORBID" -> FORBID
                else -> ASK  // Default to ASK
            }
        }
    }
}

internal enum class ToolPermissionDenialSource {
    SETTINGS,
    USER,
    AUTOMATIC_REVIEW,
}

internal sealed interface ToolPermissionDecision {
    data object Allowed : ToolPermissionDecision

    data class Denied(
        val source: ToolPermissionDenialSource,
        val rejection: String,
        val interruptTurn: Boolean = false,
    ) : ToolPermissionDecision
}

/**
 * Centralized tool permission system that manages both permission storage and checking
 */
class ToolPermissionSystem private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ToolPermissionSystem"
        private const val PERMISSION_REQUEST_TIMEOUT_MS = 60000L // 60 seconds timeout
        private const val FAST_REVIEW_RATIONALE =
            "Answered from the asynchronous risk score of the recent course of action."
        internal const val FAST_REVIEW_RESOLUTION_SOURCE = "fast_review_low_risk_score"
        
        // DataStore keys
        private val MASTER_SWITCH = stringPreferencesKey("master_switch")
        
        // Default permission setting
        private val DEFAULT_MASTER_SWITCH = PermissionLevel.ASK.name
        
        @Volatile
        private var INSTANCE: ToolPermissionSystem? = null
        
        fun getInstance(context: Context): ToolPermissionSystem {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ToolPermissionSystem(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 工具权限存储：使用 "tool_permission_<tool_name>" 作为key
    private fun toolPermissionKey(toolName: String) = stringPreferencesKey("tool_permission_$toolName")
    
    // Permission request management
    private val mainHandler = Handler(Looper.getMainLooper())
    private val permissionRequestOverlay = PermissionRequestOverlay(context)
    private val circuitBreakerWarningOverlay = PermissionRequestOverlay(context)
    private val reviewPolicyStore = PermissionReviewPolicyStore(context)
    private var currentPermissionCallback: ((PermissionRequestResult) -> Unit)? = null
    private var permissionRequestInfo: Pair<AITool, String>? = null
    private var currentRequestToken: Long = -1L

    // Request token generator
    private val requestTokenGenerator = AtomicLong(0)

    // Mutex for serializing ASK requests
    private val askMutex = Mutex()
    
    // 存储当前颜色方案
    private var currentColorScheme: ColorScheme? = null
    
    /**
     * 设置当前使用的颜色方案
     */
    fun setColorScheme(colorScheme: ColorScheme?) {
        this.currentColorScheme = colorScheme
        permissionRequestOverlay.setColorScheme(colorScheme)
        circuitBreakerWarningOverlay.setColorScheme(colorScheme)
    }

    internal fun showAutomaticReviewCircuitBreakerWarning() {
        mainHandler.post {
            circuitBreakerWarningOverlay.showCircuitBreakerWarning(
                onUnavailable = PermissionCircuitBreakerNoticeState::enqueue,
            )
        }
    }
    
    // Permission request state flow
    private val _permissionRequestState = MutableStateFlow<Pair<AITool, String>?>(null)
    val permissionRequestState = _permissionRequestState.asStateFlow()

    private val _pendingPermissionRequestCount = MutableStateFlow(0)
    private val pendingPermissionRequestCount = _pendingPermissionRequestCount.asStateFlow()
    
    // Permission level flows
    val masterSwitchFlow: Flow<PermissionLevel> = context.toolPermissionsDataStore.data.map { preferences ->
        PermissionLevel.fromString(preferences[MASTER_SWITCH] ?: DEFAULT_MASTER_SWITCH)
    }
    
    /**
     * Get permission level flow for a specific tool
     * If no permission is set for the tool, returns ASK as default
     */
    fun getToolPermissionFlow(toolName: String): Flow<PermissionLevel> {
        return context.toolPermissionsDataStore.data.map { preferences ->
            val key = toolPermissionKey(toolName)
            PermissionLevel.fromString(preferences[key] ?: PermissionLevel.ASK.name)
        }
    }
    
    // Registry of operation descriptions by tool name
    private val operationDescriptionRegistry = mutableMapOf<String, (AITool) -> String>()
    
    /**
     * Register a description generator for a tool
     */
    fun registerOperationDescription(toolName: String, descriptionGenerator: (AITool) -> String) {
        operationDescriptionRegistry[toolName] = descriptionGenerator
    }
    
    /**
     * Save permission level settings
     */
    suspend fun saveMasterSwitch(level: PermissionLevel) {
        context.toolPermissionsDataStore.edit { preferences ->
            preferences[MASTER_SWITCH] = level.name
        }
    }
    
    /**
     * Save permission level for a specific tool
     */
    suspend fun saveToolPermission(toolName: String, level: PermissionLevel) {
        context.toolPermissionsDataStore.edit { preferences ->
            val key = toolPermissionKey(toolName)
            preferences[key] = level.name
        }
    }
    
    suspend fun clearToolPermission(toolName: String) {
        context.toolPermissionsDataStore.edit { preferences ->
            val key = toolPermissionKey(toolName)
            preferences.remove(key)
        }
    }
    
    /**
     * Save permission levels for multiple tools at once
     */
    suspend fun saveToolPermissions(toolPermissions: Map<String, PermissionLevel>) {
        context.toolPermissionsDataStore.edit { preferences ->
            toolPermissions.forEach { (toolName, level) ->
                val key = toolPermissionKey(toolName)
                preferences[key] = level.name
            }
        }
    }
    
    /**
     * Get permission level for a specific tool (synchronous, for one-time reads)
     * If no permission is set for the tool, returns ASK as default
     */
    suspend fun getToolPermission(toolName: String): PermissionLevel {
        val preferences = context.toolPermissionsDataStore.data.first()
        val key = toolPermissionKey(toolName)
        return PermissionLevel.fromString(preferences[key] ?: PermissionLevel.ASK.name)
    }
    
    suspend fun getToolPermissionOverride(toolName: String): PermissionLevel? {
        val preferences = context.toolPermissionsDataStore.data.first()
        val key = toolPermissionKey(toolName)
        val stored = preferences[key]
        return stored?.let { PermissionLevel.fromString(it) }
    }
    
    /**
     * Get human-readable description of an operation
     */
    fun getOperationDescription(tool: AITool): String {
        return operationDescriptionRegistry[tool.name]?.invoke(tool) ?: context.getString(R.string.tool_permission_operation, tool.name)
    }
    
    /**
     * Check if a tool is allowed to execute
     */
    internal suspend fun checkToolPermission(
        tool: AITool,
        conversationLabel: String? = null,
        workspacePath: String? = null,
        workspaceEnv: String? = null,
        callerChatId: String? = null,
        parentModelConfigId: String? = null,
        parentModelIndex: Int? = null,
        timingScopeId: String? = null,
        targetId: String? = null,
        invocationIndex: Int = -1,
        batchPosition: Int = 1,
        batchSize: Int = 1,
        deferCircuitBreaker: Boolean = false,
        liveAssistantContent: String? = null,
    ): ToolPermissionDecision {
        AppLogger.d(TAG, "Starting permission check: ${tool.name}")

        val duplicateParameterNames =
            findDuplicateToolParameterNames(tool)
        if (duplicateParameterNames.isNotEmpty()) {
            return ToolPermissionDecision.Denied(
                source = ToolPermissionDenialSource.SETTINGS,
                rejection =
                    "Tool execution rejected because duplicate parameter names are ambiguous: " +
                        duplicateParameterNames.sorted().joinToString(", "),
            )
        }

        val reviewContext =
            ToolPermissionReviewContext(
                callerChatId = callerChatId,
                conversationLabel = conversationLabel,
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv,
                parentModelConfigId = parentModelConfigId,
                parentModelIndex = parentModelIndex,
                timingScopeId = timingScopeId,
                targetId = targetId,
                invocationIndex = invocationIndex,
                batchPosition = batchPosition,
                batchSize = batchSize,
                deferCircuitBreaker = deferCircuitBreaker,
                liveAssistantContent = liveAssistantContent,
            )

        val effectiveLevel = getEffectivePermissionLevel(tool.name)
        val currentRoute = resolveCurrentPermissionRoute(tool, reviewContext)
        if (
            !callerChatId.isNullOrBlank() &&
                currentRoute == PermissionRoute.REVIEWER &&
                PermissionReviewCircuitBreaker.isInterrupted(callerChatId, timingScopeId)
        ) {
            PermissionReviewEventRepository.initialize(context)
            val skippedReviewId = PermissionReviewInspectionRegistry.newReviewId()
            val skippedAction =
                PermissionReviewAction.fromTool(
                    tool = tool,
                    operationDescription = getOperationDescription(tool),
                    reviewContext = reviewContext,
                    targetId = targetId ?: skippedReviewId,
                )
            val now = System.currentTimeMillis()
            PermissionReviewEventRepository.publish(
                PermissionReviewEvent(
                    id = skippedReviewId,
                    parentChatId = callerChatId,
                    timingScopeId = timingScopeId,
                    invocationIndex = invocationIndex,
                    batchPosition = batchPosition,
                    batchSize = batchSize,
                    action = skippedAction,
                    actionFingerprint = skippedAction.fingerprint(),
                    status = PermissionReviewStatus.ABORTED,
                    startedAt = now,
                    completedAt = now,
                    rationale = "Skipped because repeated denials stopped this model turn.",
                )
            )
            return permissionDeniedByAutomaticReview(
                rationale = "This model turn was stopped after repeated denied actions.",
                interruptTurn = true,
            )
        }

        return evaluatePermissionLevel(
            level = effectiveLevel,
            tool = tool,
            reviewContext = reviewContext,
            onAsk = { requestPermission(tool, reviewContext) },
        )
    }

    suspend fun getEffectivePermissionLevel(toolName: String): PermissionLevel {
        val preferences = context.toolPermissionsDataStore.data.first()
        val masterSwitch = PermissionLevel.fromString(preferences[MASTER_SWITCH] ?: DEFAULT_MASTER_SWITCH)
        val key = toolPermissionKey(toolName)
        val overrideLevel = preferences[key]?.let { PermissionLevel.fromString(it) }
        return resolveEffectivePermissionLevel(masterSwitch, overrideLevel)
    }
    
    /**
     * Request permission from the user to execute a tool.
     * ASK requests are serialized via askMutex.
     */
    private suspend fun requestPermission(
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
    ): ToolPermissionDecision {
        _pendingPermissionRequestCount.update { it + 1 }
        try {
            while (true) {
                when (resolveCurrentPermissionRoute(tool, reviewContext)) {
                    PermissionRoute.ALLOW -> return ToolPermissionDecision.Allowed
                    PermissionRoute.FORBID -> return permissionDeniedBySettings()
                    PermissionRoute.REVIEWER ->
                        return reviewPermission(
                            tool = tool,
                            reviewContext = reviewContext,
                            pendingRequestAlreadyCounted = true,
                        )
                    PermissionRoute.ASK -> {
                        val lockedResult: ToolPermissionDecision? =
                            askMutex.withLock {
                                // Settings may have changed while this request waited in the queue.
                                when (resolveCurrentPermissionRoute(tool, reviewContext)) {
                                    PermissionRoute.ALLOW -> ToolPermissionDecision.Allowed
                                    PermissionRoute.FORBID -> permissionDeniedBySettings()
                                    PermissionRoute.ASK ->
                                        if (
                                            requestPermissionInternal(
                                                tool,
                                                reviewContext.conversationLabel,
                                            )
                                        ) {
                                            ToolPermissionDecision.Allowed
                                        } else {
                                            invalidateStoredRiskScores(reviewContext)
                                            permissionDeniedByUser()
                                        }
                                    PermissionRoute.REVIEWER -> null
                                }
                            }
                        if (lockedResult != null) return lockedResult
                    }
                }
            }
        } finally {
            _pendingPermissionRequestCount.update { count -> (count - 1).coerceAtLeast(0) }
        }
    }

    private suspend fun reviewPermission(
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
        pendingRequestAlreadyCounted: Boolean = false,
    ): ToolPermissionDecision {
        // Only the fast level reads a stored score, so the strict level keeps the previous behavior
        // exactly and runs the blocking reviewer for every call.
        val fastPathDecision = claimFastPathApproval(tool, reviewContext)
        val decision =
            fastPathDecision
                ?: AgentToolPermissionReviewer.getInstance(context).review(
                    tool = tool,
                    operationDescription = getOperationDescription(tool),
                    reviewContext = reviewContext,
                )
        if (fastPathDecision == null) {
            AppLogger.i(
                TAG,
                "Independent permission review completed: tool=${tool.name}, decision=${decision.outcome}, " +
                    "risk=${decision.riskLevel}, authorization=${decision.userAuthorization}, " +
                    "failure=${decision.failureKind}"
            )
        }
        if (decision.failureKind != null) {
            return requestManualPermission(
                tool = tool,
                reviewContext = reviewContext,
                reviewFailureKind = decision.failureKind,
                pendingRequestAlreadyCounted = pendingRequestAlreadyCounted,
            )
        }
        val refreshedDecision =
            resolveReviewDecisionAfterSettingsRefresh(
                approvalGranted = decision.outcome == PermissionReviewOutcome.ALLOW,
                latestRoute = resolveCurrentPermissionRoute(tool, reviewContext),
                reviewerRationale = decision.rationale,
            )
        if (!reviewContext.callerChatId.isNullOrBlank()) {
            // Settings can change while the reviewer turn is in flight. The event was finalized
            // with the reviewer's own result; if the refreshed route overrides it, record the
            // outcome that was actually enforced so the UI and audit history do not report the
            // opposite of what happened.
            PermissionReviewEventRepository.findForInvocation(
                reviewContext.callerChatId,
                reviewContext.timingScopeId,
                reviewContext.invocationIndex,
            )?.let { event ->
                val enforcedStatus =
                    if (refreshedDecision != null) {
                        reviewEventStatusForEnforcedDecision(refreshedDecision)
                    } else {
                        event.status
                    }
                if (enforcedStatus != event.status) {
                    PermissionReviewEventRepository.update(event.id) { current ->
                        current.copy(
                            status = enforcedStatus,
                            resolutionSource =
                                if (refreshedDecision is ToolPermissionDecision.Allowed) {
                                    "settings_refreshed_allow"
                                } else {
                                    "settings_refreshed_deny"
                                }
                        )
                    }
                }
            }
        }
        if (!reviewContext.deferCircuitBreaker &&
            !reviewContext.callerChatId.isNullOrBlank()
        ) {
            if (
                refreshedDecision is ToolPermissionDecision.Denied &&
                    refreshedDecision.source == ToolPermissionDenialSource.AUTOMATIC_REVIEW
            ) {
                val circuit =
                    PermissionReviewCircuitBreaker.recordDenial(
                        reviewContext.callerChatId,
                        reviewContext.timingScopeId,
                    )
                return refreshedDecision.copy(interruptTurn = circuit.interruptTurn)
            }
            if (refreshedDecision is ToolPermissionDecision.Allowed) {
                // A stored score is not a fresh judgement of what the model is doing right now, so
                // it must not clear the repeated-denial circuit breaker.
                if (fastPathDecision == null) {
                    PermissionReviewCircuitBreaker.recordNonDenial(
                        reviewContext.callerChatId,
                        reviewContext.timingScopeId,
                    )
                }
            }
        }
        if (refreshedDecision is ToolPermissionDecision.Denied) {
            // A refusal changes what the agent's authorization means for everything that follows,
            // so a stored low-risk score must not answer the next calls.
            invalidateStoredRiskScores(reviewContext)
        }
        return refreshedDecision
            ?: requestManualPermission(
                tool = tool,
                reviewContext = reviewContext,
                reviewFailureKind = null,
                pendingRequestAlreadyCounted = pendingRequestAlreadyCounted,
            )
    }

    /**
     * Answers this call from the asynchronous risk score when the fast level produced a recent
     * low-risk verdict under the current authorization.
     *
     * The synthetic decision is deliberately shaped like a reviewer approval so the shared
     * reconciliation below still applies: if the level changed to ASK or FORBID while the score was
     * stored, that change wins and the call is prompted or denied instead of answered from the
     * score. Anything else — a high-risk verdict, a stale or missing score, a failed classifier, or
     * a changed authorization — leaves the call to the blocking reviewer, so the score can only ever
     * remove a review, never widen a permission.
     */
    private suspend fun claimFastPathApproval(
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
    ): PermissionReviewDecision? {
        if (reviewPolicyStore.getReviewMode() != PermissionReviewMode.FAST) return null
        val deferral =
            PermissionRiskScorer.getInstance(context)
                .resolveFastPath(
                    callerChatId = reviewContext.callerChatId,
                    turnScopeId = reviewContext.timingScopeId,
                )
        if (deferral != null) {
            AppLogger.d(
                TAG,
                "Automatic review deferred to the blocking reviewer: tool=${tool.name}, reason=$deferral"
            )
            return null
        }
        AppLogger.i(TAG, "Fast automatic review answered from a recent low-risk score: tool=${tool.name}")
        publishFastPathApprovalEvent(tool, reviewContext)
        return PermissionReviewDecision(
            outcome = PermissionReviewOutcome.ALLOW,
            riskLevel = PermissionReviewRiskLevel.LOW,
            userAuthorization = PermissionReviewAuthorization.UNKNOWN,
            rationale = FAST_REVIEW_RATIONALE,
        )
    }

    /**
     * Records the reuse so the review history explains why the call was allowed. The event is
     * published as already approved with its own resolution source; the reconciliation below only
     * rewrites it when a settings change enforced a different outcome.
     */
    private fun publishFastPathApprovalEvent(
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
    ) {
        val chatId = reviewContext.callerChatId?.trim().orEmpty()
        if (chatId.isEmpty()) return
        PermissionReviewEventRepository.initialize(context)
        val reviewId = PermissionReviewInspectionRegistry.newReviewId()
        val action =
            PermissionReviewAction.fromTool(
                tool = tool,
                operationDescription = getOperationDescription(tool),
                reviewContext = reviewContext,
                targetId = reviewContext.targetId ?: reviewId,
            )
        val now = System.currentTimeMillis()
        PermissionReviewEventRepository.publish(
            PermissionReviewEvent(
                id = reviewId,
                parentChatId = chatId,
                timingScopeId = reviewContext.timingScopeId,
                invocationIndex = reviewContext.invocationIndex,
                batchPosition = reviewContext.batchPosition,
                batchSize = reviewContext.batchSize,
                action = action,
                actionFingerprint = action.fingerprint(),
                status = PermissionReviewStatus.APPROVED,
                startedAt = now,
                completedAt = now,
                riskLevel = PermissionReviewRiskLevel.LOW,
                rationale = FAST_REVIEW_RATIONALE,
                resolutionSource = FAST_REVIEW_RESOLUTION_SOURCE,
            )
        )
    }

    private suspend fun requestManualPermission(
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
        reviewFailureKind: PermissionReviewFailureKind?,
        pendingRequestAlreadyCounted: Boolean,
    ): ToolPermissionDecision {
        if (!pendingRequestAlreadyCounted) {
            _pendingPermissionRequestCount.update { it + 1 }
        }
        try {
            val manualDecision = askMutex.withLock {
                when (resolveCurrentPermissionRoute(tool, reviewContext)) {
                    PermissionRoute.ALLOW -> ToolPermissionDecision.Allowed
                    PermissionRoute.FORBID -> permissionDeniedBySettings()
                    else ->
                        if (
                            requestPermissionInternal(
                                tool = tool,
                                conversationLabel = reviewContext.conversationLabel,
                                reviewFailureKind = reviewFailureKind,
                            )
                        ) {
                            ToolPermissionDecision.Allowed
                        } else {
                            invalidateStoredRiskScores(reviewContext)
                            permissionDeniedByUser()
                        }
                }
            }
            if (!reviewContext.callerChatId.isNullOrBlank()) {
                PermissionReviewEventRepository.findForInvocation(
                    reviewContext.callerChatId,
                    reviewContext.timingScopeId,
                    reviewContext.invocationIndex,
                )?.let { event ->
                    PermissionReviewEventRepository.update(event.id) { current ->
                        // The reviewer may have concluded before settings changed to ASK (or the
                        // review failed/timed out); the manual or setting decision below is what
                        // was actually enforced, so the event must not keep the opposite status.
                        current.copy(
                            status = reviewEventStatusForEnforcedDecision(manualDecision),
                            resolutionSource =
                                if (manualDecision is ToolPermissionDecision.Allowed) {
                                    "manual_or_setting_allow"
                                } else {
                                    "manual_or_setting_deny"
                                }
                        )
                    }
                }
            }
            return manualDecision
        } finally {
            if (!pendingRequestAlreadyCounted) {
                _pendingPermissionRequestCount.update { count -> (count - 1).coerceAtLeast(0) }
            }
        }
    }

    private suspend fun resolveCurrentPermissionRoute(
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
    ): PermissionRoute {
        val level = getEffectivePermissionLevel(tool.name)
        val workspaceApproved =
            level == PermissionLevel.AUTO_REVIEW &&
                WorkspaceToolPermissionPolicy.isAutoApproved(
                    context = context,
                    tool = tool,
                    workspacePath = reviewContext.workspacePath,
                    workspaceEnv = reviewContext.workspaceEnv,
                    callerChatId = reviewContext.callerChatId,
                )
        return resolvePermissionRoute(level, workspaceApproved)
    }

    private suspend fun evaluatePermissionLevel(
        level: PermissionLevel,
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
        onAsk: suspend () -> ToolPermissionDecision,
    ): ToolPermissionDecision {
        val workspaceApproved =
            level == PermissionLevel.AUTO_REVIEW &&
                WorkspaceToolPermissionPolicy.isAutoApproved(
                    context = context,
                    tool = tool,
                    workspacePath = reviewContext.workspacePath,
                    workspaceEnv = reviewContext.workspaceEnv,
                    callerChatId = reviewContext.callerChatId,
                )

        return when (resolvePermissionRoute(level, workspaceApproved)) {
            PermissionRoute.ALLOW -> ToolPermissionDecision.Allowed
            PermissionRoute.ASK -> onAsk()
            PermissionRoute.REVIEWER -> reviewPermission(tool, reviewContext)
            PermissionRoute.FORBID -> permissionDeniedBySettings()
        }
    }

    /**
     * Prepares the asynchronous risk score for one dispatched tool batch.
     *
     * Scoring starts when the batch is dispatched rather than when an approval is requested, so the
     * verdict for the actions the agent is about to run is already being computed while the reviews
     * of the same batch are still running. Every batch advances one scoring step, including batches
     * that need no review at all, so a stored score ages out after
     * [PermissionRiskScorer.MAX_LAG_STEPS] batches, exactly like the Codex adaptive scorer.
     *
     * Nothing here blocks the batch or decides anything: the tools that would be answered by the
     * workspace policy or by a permanent setting are only counted, not scored, and the score itself
     * can only ever remove a review.
     */
    internal suspend fun prepareBatchRiskScores(
        tools: List<AITool>,
        callerChatId: String?,
        conversationLabel: String? = null,
        workspacePath: String? = null,
        workspaceEnv: String? = null,
        parentModelConfigId: String? = null,
        parentModelIndex: Int? = null,
        timingScopeId: String? = null,
        liveAssistantContent: String? = null,
    ) {
        val reviewContext =
            ToolPermissionReviewContext(
                callerChatId = callerChatId,
                conversationLabel = conversationLabel,
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv,
                parentModelConfigId = parentModelConfigId,
                parentModelIndex = parentModelIndex,
                timingScopeId = timingScopeId,
                batchSize = tools.size,
                liveAssistantContent = liveAssistantContent,
            )
        val actions =
            tools.map { tool ->
                PermissionRiskAction(
                    canonical =
                        PermissionReviewAction.fromTool(
                            tool = tool,
                            operationDescription = getOperationDescription(tool),
                            reviewContext = reviewContext,
                            targetId = "",
                        ),
                    scorable =
                        resolveCurrentPermissionRoute(tool, reviewContext) ==
                            PermissionRoute.REVIEWER,
                )
            }
        PermissionRiskScorer.getInstance(context)
            .beginBatch(
                callerChatId = callerChatId,
                turnScopeId = timingScopeId,
                actions = actions,
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv,
                liveAssistantContent = liveAssistantContent,
            )
    }

    /** A refusal changes what the agent may assume for everything that follows it. */
    private fun invalidateStoredRiskScores(reviewContext: ToolPermissionReviewContext) {
        PermissionRiskScorer.getInstance(context)
            .invalidate(reviewContext.callerChatId, reviewContext.timingScopeId)
    }

    /**
     * Shows a one-shot review even when the tool itself is normally allowed.
     *
     * This does not expose or persist "always allow", so an approval applies only to the current
     * guarded invocation. Requests share the normal serialized permission queue.
     */
    suspend fun requestExplicitApproval(
        tool: AITool,
        operationDescription: String,
        conversationLabel: String?,
    ): Boolean {
        _pendingPermissionRequestCount.update { it + 1 }
        try {
            return askMutex.withLock {
                requestPermissionInternal(
                    tool = tool,
                    conversationLabel = conversationLabel,
                    operationDescriptionOverride = operationDescription,
                    persistPermanentChoice = false,
                    allowPermanentChoice = false,
                )
            }
        } finally {
            _pendingPermissionRequestCount.update { count -> (count - 1).coerceAtLeast(0) }
        }
    }

    private suspend fun requestPermissionInternal(
        tool: AITool,
        conversationLabel: String?,
        operationDescriptionOverride: String? = null,
        persistPermanentChoice: Boolean = true,
        allowPermanentChoice: Boolean = true,
        reviewFailureKind: PermissionReviewFailureKind? = null,
    ): Boolean {
        return withContext(Dispatchers.Main.immediate) {
            val operationDescription =
                operationDescriptionOverride ?: getOperationDescription(tool)
            AppLogger.d(TAG, "Requesting permission: ${tool.name}")

            val requestInfo = Pair(tool, operationDescription)
            val token = requestTokenGenerator.incrementAndGet()
            currentRequestToken = token
            permissionRequestInfo = requestInfo
            _permissionRequestState.value = requestInfo
            AppLogger.d(TAG, "Permission request state updated: ${tool.name} token=$token")

            var timeoutTask: Runnable? = null
            var timeoutDisabled = false

            try {
                val result = suspendCancellableCoroutine { continuation ->
                    currentPermissionCallback = callback@{ permissionResult ->
                        if (token != currentRequestToken || !continuation.isActive) {
                            return@callback
                        }
                        AppLogger.d(
                            TAG,
                            "Permission result received: $permissionResult for ${tool.name} token=$token"
                        )
                        currentPermissionCallback = null
                        continuation.resume(permissionResult)
                    }

                    val requestTimeoutTask = Runnable {
                        AppLogger.d(
                            TAG,
                            "Timeout runnable fired for ${tool.name} token=$token timeoutDisabled=$timeoutDisabled"
                        )
                        if (token == currentRequestToken && !timeoutDisabled && continuation.isActive) {
                            AppLogger.d(TAG, "Permission request timed out: ${tool.name} token=$token")
                            currentPermissionCallback = null
                            permissionRequestOverlay.dismiss()
                            continuation.resume(PermissionRequestResult.DENY)
                        }
                    }
                    timeoutTask = requestTimeoutTask
                    mainHandler.postDelayed(requestTimeoutTask, PERMISSION_REQUEST_TIMEOUT_MS)

                    if (!permissionRequestOverlay.hasOverlayPermission()) {
                        AppLogger.w(TAG, "No overlay permission, requesting...")
                        permissionRequestOverlay.requestOverlayPermission()
                        currentPermissionCallback = null
                        if (continuation.isActive) {
                            continuation.resume(PermissionRequestResult.DENY)
                        }
                        return@suspendCancellableCoroutine
                    }

                    permissionRequestOverlay.show(
                        tool,
                        operationDescription,
                        conversationLabel = conversationLabel,
                        pendingRequestCount = pendingPermissionRequestCount,
                        reviewFailureKind = reviewFailureKind,
                        onResult = { permissionResult ->
                            handlePermissionResult(permissionResult)
                        },
                        onMinimized = {
                            if (token == currentRequestToken && !timeoutDisabled) {
                                AppLogger.d(TAG, "Request minimized - cancelling timeout token=$token")
                                timeoutDisabled = true
                                mainHandler.removeCallbacks(requestTimeoutTask)
                            }
                        },
                        allowPermanentChoice = allowPermanentChoice,
                    )
                }

                when (result) {
                    PermissionRequestResult.ALLOW -> true
                    PermissionRequestResult.DENY -> false
                    PermissionRequestResult.ALWAYS_ALLOW -> {
                        if (persistPermanentChoice) {
                            // Persist the choice before releasing the mutex to queued requests.
                            withContext(NonCancellable + Dispatchers.IO) {
                                saveToolPermission(tool.name, PermissionLevel.ALLOW)
                            }
                        }
                        true
                    }
                    PermissionRequestResult.ALWAYS_DENY -> {
                        if (persistPermanentChoice) {
                            // Persist before releasing the mutex so queued calls observe the deny.
                            withContext(NonCancellable + Dispatchers.IO) {
                                saveToolPermission(tool.name, PermissionLevel.FORBID)
                            }
                        }
                        false
                    }
                }
            } finally {
                timeoutTask?.let { mainHandler.removeCallbacks(it) }
                if (token == currentRequestToken) {
                    permissionRequestOverlay.dismiss()
                    currentRequestToken = -1L
                    currentPermissionCallback = null
                    permissionRequestInfo = null
                    _permissionRequestState.value = null
                }
            }
        }
    }
    
    /**
     * Handle permission request result
     */
    fun handlePermissionResult(result: PermissionRequestResult) {
        currentPermissionCallback?.invoke(result)
    }
    
    /**
     * Get current permission request info
     */
    fun getCurrentPermissionRequest(): Pair<AITool, String>? {
        return permissionRequestInfo
    }
    
    /**
     * Check if there is an active permission request
     */
    fun hasActivePermissionRequest(): Boolean {
        return permissionRequestInfo != null && currentPermissionCallback != null
    }
    
    /**
     * Refresh permission request state
     */
    fun refreshPermissionRequestState(): Boolean {
        return hasActivePermissionRequest()
    }
}

internal fun resolveEffectivePermissionLevel(
    masterLevel: PermissionLevel,
    toolOverride: PermissionLevel?,
): PermissionLevel = toolOverride ?: masterLevel

internal fun findDuplicateToolParameterNames(tool: AITool): Set<String> =
    tool.parameters.groupingBy { parameter -> parameter.name }.eachCount()
        .filterValues { count -> count > 1 }
        .keys

internal enum class PermissionRoute {
    ALLOW,
    ASK,
    REVIEWER,
    FORBID,
}

internal fun resolvePermissionRoute(
    level: PermissionLevel,
    workspaceApproved: Boolean,
): PermissionRoute =
    when (level) {
        PermissionLevel.ALLOW -> PermissionRoute.ALLOW
        PermissionLevel.AUTO_REVIEW ->
            if (workspaceApproved) PermissionRoute.ALLOW else PermissionRoute.REVIEWER
        PermissionLevel.ASK -> PermissionRoute.ASK
        PermissionLevel.FORBID -> PermissionRoute.FORBID
    }

internal fun resolveApprovalDecisionWithPermanentOverride(
    approvalGranted: Boolean,
    latestEffectiveLevel: PermissionLevel,
): Boolean =
    when (latestEffectiveLevel) {
        PermissionLevel.ALLOW -> true
        PermissionLevel.FORBID -> false
        else -> approvalGranted
    }

internal fun permissionDeniedBySettings(): ToolPermissionDecision.Denied =
    ToolPermissionDecision.Denied(
        source = ToolPermissionDenialSource.SETTINGS,
        rejection = "Tool execution denied by permission settings.",
    )

internal fun permissionDeniedByUser(): ToolPermissionDecision.Denied =
    ToolPermissionDecision.Denied(
        source = ToolPermissionDenialSource.USER,
        rejection = "Tool execution denied by user.",
    )

internal fun permissionDeniedByAutomaticReview(
    rationale: String,
    interruptTurn: Boolean = false,
): ToolPermissionDecision.Denied {
    val normalizedRationale = rationale.trim().take(1_000)
    val suffix = normalizedRationale.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty()
    return ToolPermissionDecision.Denied(
        source = ToolPermissionDenialSource.AUTOMATIC_REVIEW,
        rejection =
            "Automatic permission review denied the action$suffix. Do not retry, rephrase, " +
                "split, encode, delegate, or use another tool or path to work around this denial. " +
                "Ask the user for explicit authorization or choose a genuinely different safe action.",
        interruptTurn = interruptTurn,
    )
}

/** Null means the latest setting now requires a manual prompt. */
internal fun resolveReviewDecisionAfterSettingsRefresh(
    approvalGranted: Boolean,
    latestRoute: PermissionRoute,
    reviewerRationale: String,
): ToolPermissionDecision? =
    when (latestRoute) {
        PermissionRoute.ALLOW -> ToolPermissionDecision.Allowed
        PermissionRoute.FORBID -> permissionDeniedBySettings()
        PermissionRoute.REVIEWER ->
            if (approvalGranted) {
                ToolPermissionDecision.Allowed
            } else {
                permissionDeniedByAutomaticReview(reviewerRationale)
            }
        PermissionRoute.ASK -> null
    }

/**
 * The event status must mirror the decision that was actually enforced. A manual or setting
 * decision reached after the reviewer finished may differ from the reviewer's own result, and a
 * stale APPROVED/DENIED status would misreport the outcome in the UI and audit history.
 */
internal fun reviewEventStatusForEnforcedDecision(
    decision: ToolPermissionDecision,
): PermissionReviewStatus =
    if (decision is ToolPermissionDecision.Allowed) {
        PermissionReviewStatus.APPROVED
    } else {
        PermissionReviewStatus.DENIED
    }
