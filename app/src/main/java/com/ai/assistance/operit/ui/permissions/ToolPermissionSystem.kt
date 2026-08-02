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
    ALLOW,      // Allow automatically without asking
    WORKSPACE,  // Allow only operations proven to stay inside the bound workspace
    WORKSPACE_REVIEWER, // Allow proven workspace operations, review everything else
    REVIEWER,   // Let an independent approval reviewer decide
    ASK,        // Always ask
    FORBID;     // Never allow

    companion object {
        fun fromString(value: String?): PermissionLevel {
            return when (value) {
                "ALLOW" -> ALLOW
                "WORKSPACE" -> WORKSPACE
                "WORKSPACE_REVIEWER" -> WORKSPACE_REVIEWER
                "REVIEWER" -> REVIEWER
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
        val decision =
            AgentToolPermissionReviewer.getInstance(context).review(
                tool = tool,
                operationDescription = getOperationDescription(tool),
                reviewContext = reviewContext,
            )
        AppLogger.i(
            TAG,
            "Independent permission review completed: tool=${tool.name}, decision=${decision.outcome}, " +
                "risk=${decision.riskLevel}, authorization=${decision.userAuthorization}, " +
                "failure=${decision.failureKind}"
        )
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
                PermissionReviewCircuitBreaker.recordNonDenial(
                    reviewContext.callerChatId,
                    reviewContext.timingScopeId,
                )
            }
        }
        return refreshedDecision
            ?: requestManualPermission(
                tool = tool,
                reviewContext = reviewContext,
                reviewFailureKind = null,
                pendingRequestAlreadyCounted = pendingRequestAlreadyCounted,
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
                        current.copy(
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
            if (
                level == PermissionLevel.WORKSPACE ||
                    level == PermissionLevel.WORKSPACE_REVIEWER
            ) {
                WorkspaceToolPermissionPolicy.isAutoApproved(
                    context = context,
                    tool = tool,
                    workspacePath = reviewContext.workspacePath,
                    workspaceEnv = reviewContext.workspaceEnv,
                    callerChatId = reviewContext.callerChatId,
                )
            } else {
                false
            }
        return resolvePermissionRoute(level, workspaceApproved)
    }

    private suspend fun evaluatePermissionLevel(
        level: PermissionLevel,
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
        onAsk: suspend () -> ToolPermissionDecision,
    ): ToolPermissionDecision {
        val workspaceApproved =
            if (
                level == PermissionLevel.WORKSPACE ||
                    level == PermissionLevel.WORKSPACE_REVIEWER
            ) {
                WorkspaceToolPermissionPolicy.isAutoApproved(
                    context = context,
                    tool = tool,
                    workspacePath = reviewContext.workspacePath,
                    workspaceEnv = reviewContext.workspaceEnv,
                    callerChatId = reviewContext.callerChatId,
                )
            } else {
                false
            }

        return when (resolvePermissionRoute(level, workspaceApproved)) {
            PermissionRoute.ALLOW -> ToolPermissionDecision.Allowed
            PermissionRoute.ASK -> onAsk()
            PermissionRoute.REVIEWER -> reviewPermission(tool, reviewContext)
            PermissionRoute.FORBID -> permissionDeniedBySettings()
        }
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
        PermissionLevel.WORKSPACE ->
            if (workspaceApproved) PermissionRoute.ALLOW else PermissionRoute.ASK
        PermissionLevel.WORKSPACE_REVIEWER ->
            if (workspaceApproved) PermissionRoute.ALLOW else PermissionRoute.REVIEWER
        PermissionLevel.REVIEWER -> PermissionRoute.REVIEWER
        PermissionLevel.ASK -> PermissionRoute.ASK
        PermissionLevel.FORBID -> PermissionRoute.FORBID
    }

internal fun resolveApprovalDecisionWithPermanentOverride(
    approvalGranted: Boolean,
    latestToolOverride: PermissionLevel?,
): Boolean =
    when (latestToolOverride) {
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
