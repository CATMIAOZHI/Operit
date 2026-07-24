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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

// Define DataStore
private val Context.toolPermissionsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tool_permissions")

/**
 * Permission levels for tool operations
 */
enum class PermissionLevel {
    ALLOW,      // Allow automatically without asking
    ASK,        // Always ask
    FORBID;     // Never allow

    companion object {
        fun fromString(value: String?): PermissionLevel {
            return when (value) {
                "ALLOW" -> ALLOW
                "CAUTION" -> ASK
                "ASK" -> ASK
                "FORBID" -> FORBID
                else -> ASK  // Default to ASK
            }
        }
    }
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
    private var currentPermissionCallback: ((PermissionRequestResult) -> Unit)? = null
    private var permissionRequestInfo: Pair<AITool, String>? = null
    private var currentRequestToken: Long = -1L
    private var timeoutRunnable: Runnable? = null
    private var timeoutDisabled = false

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
    }
    
    // Permission request state flow
    private val _permissionRequestState = MutableStateFlow<Pair<AITool, String>?>(null)
    val permissionRequestState = _permissionRequestState.asStateFlow()
    
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
    suspend fun checkToolPermission(tool: AITool): Boolean {
        AppLogger.d(TAG, "Starting permission check: ${tool.name}")
        
        val preferences = context.toolPermissionsDataStore.data.first()
        val masterSwitch = PermissionLevel.fromString(preferences[MASTER_SWITCH] ?: DEFAULT_MASTER_SWITCH)
        val key = toolPermissionKey(tool.name)
        val overrideLevel = preferences[key]?.let { PermissionLevel.fromString(it) }
        
        val permissionLevel = overrideLevel ?: masterSwitch
        
        return when (permissionLevel) {
            PermissionLevel.ALLOW -> true
            PermissionLevel.ASK -> requestPermission(tool)
            PermissionLevel.FORBID -> false
        }
    }

    /**
     * Cleans up shared state for a request. Returns true if token matched the active request.
     * Does NOT resume the continuation — caller is responsible for that.
     */
    private fun resolveRequest(token: Long): Boolean {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null

        if (token != currentRequestToken) {
            AppLogger.w(TAG, "resolveRequest token mismatch: expected=$currentRequestToken actual=$token")
            return false
        }

        currentRequestToken = -1L
        currentPermissionCallback = null
        permissionRequestInfo = null
        _permissionRequestState.value = null
        timeoutDisabled = false
        return true
    }
    
    /**
     * Request permission from the user to execute a tool.
     * ASK requests are serialized via askMutex.
     */
    private suspend fun requestPermission(tool: AITool): Boolean {
        // Fast-path: ALLOW and FORBID don't need the mutex; only ASK does.
        return askMutex.withLock {
            requestPermissionInternal(tool)
        }
    }

    private suspend fun requestPermissionInternal(tool: AITool): Boolean {
        // Get operation description
        val operationDescription = getOperationDescription(tool)
        
        AppLogger.d(TAG, "Requesting permission: ${tool.name}")
        
        // Clear existing request
        currentPermissionCallback = null
        permissionRequestInfo = null
        _permissionRequestState.value = null
        currentRequestToken = -1L
        timeoutDisabled = false
        
        // Set up new request
        val requestInfo = Pair(tool, operationDescription)
        val token = requestTokenGenerator.incrementAndGet()
        currentRequestToken = token
        permissionRequestInfo = requestInfo
        _permissionRequestState.value = requestInfo
        
        AppLogger.d(TAG, "Permission request state updated: ${tool.name} token=$token")
        
        return suspendCancellableCoroutine { continuation ->
            // Set callback — invoked when user clicks Allow/Deny/Always Allow via handlePermissionResult
            currentPermissionCallback = callback@{ result ->
                AppLogger.d(TAG, "Permission result received: $result for ${tool.name} token=$token")
                if (!resolveRequest(token)) return@callback // stale request
                
                // Handle result
                when (result) {
                    PermissionRequestResult.ALLOW -> continuation.resume(true)
                    PermissionRequestResult.DENY -> continuation.resume(false)
                    PermissionRequestResult.ALWAYS_ALLOW -> {
                        // Save the permission asynchronously
                        val toolScope = CoroutineScope(Dispatchers.IO)
                        toolScope.launch {
                            saveToolPermission(tool.name, PermissionLevel.ALLOW)
                        }
                        continuation.resume(true)
                    }
                }
            }
            
            // Schedule timeout — runs on main thread
            val timeoutTask = Runnable {
                AppLogger.d(TAG, "Timeout runnable fired for ${tool.name} token=$token timeoutDisabled=$timeoutDisabled")
                if (token != currentRequestToken || timeoutDisabled) {
                    return@Runnable
                }
                // Dismiss the overlay (may be fullscreen or minimized)
                permissionRequestOverlay.dismiss()
                if (resolveRequest(token)) {
                    AppLogger.d(TAG, "Permission request timed out: ${tool.name} token=$token")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
            timeoutRunnable = timeoutTask
            mainHandler.postDelayed(timeoutTask, PERMISSION_REQUEST_TIMEOUT_MS)
            
            // Register cancellation handler — all shared-state access must be on main thread
            continuation.invokeOnCancellation {
                AppLogger.d(TAG, "Permission request cancelled: ${tool.name} token=$token")
                mainHandler.post {
                    if (token == currentRequestToken) {
                        permissionRequestOverlay.dismiss()
                        resolveRequest(token)
                    } else {
                        // Still clean up timeout even if this is a stale token
                        resolveRequest(token)
                    }
                    mainHandler.removeCallbacks(timeoutTask)
                    if (timeoutRunnable === timeoutTask) {
                        timeoutRunnable = null
                    }
                }
            }
            
            // Start permission request on main thread
            mainHandler.post {
                if (!permissionRequestOverlay.hasOverlayPermission()) {
                    AppLogger.w(TAG, "No overlay permission, requesting...")
                    permissionRequestOverlay.requestOverlayPermission()
                    // Resolve without overlay
                    resolveRequest(token)
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                } else {
                    permissionRequestOverlay.show(
                        tool,
                        operationDescription,
                        onResult = { result ->
                            handlePermissionResult(result)
                        },
                        onMinimized = {
                            // First-time minimize permanently cancels timeout for this request
                            if (token == currentRequestToken && !timeoutDisabled) {
                                AppLogger.d(TAG, "Request minimized — cancelling timeout token=$token")
                                timeoutDisabled = true
                                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                                timeoutRunnable = null
                            }
                        }
                    )
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
