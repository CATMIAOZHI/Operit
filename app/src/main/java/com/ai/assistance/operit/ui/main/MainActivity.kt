package com.ai.assistance.operit.ui.main

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import com.ai.assistance.operit.util.AppLogger
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.preferences.AgreementPreferences
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.data.updates.UpdateManager
import com.ai.assistance.operit.data.updates.UpdateStatus
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.features.agreement.screens.AgreementScreen
import com.ai.assistance.operit.ui.features.permission.screens.PermissionGuideScreen
import com.ai.assistance.operit.ui.features.startup.screens.PluginLoadingScreenWithState
import com.ai.assistance.operit.ui.features.startup.screens.PluginLoadingState
import com.ai.assistance.operit.ui.features.startup.screens.LocalPluginLoadingState
import com.ai.assistance.operit.ui.features.startup.screens.PluginLoadingStateRegistry
import com.ai.assistance.operit.ui.theme.OperitTheme
import com.ai.assistance.operit.ui.common.displays.VirtualDisplayOverlay
import com.ai.assistance.operit.util.AnrMonitor
import com.ai.assistance.operit.util.LocaleUtils
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ai.assistance.operit.data.mcp.MCPRepository
import com.ai.assistance.operit.data.backup.MigrationStateStore
import com.ai.assistance.operit.data.backup.MigrationStatePolicy
import com.ai.assistance.operit.data.backup.RawSnapshotBackupManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import com.ai.assistance.operit.ui.features.github.GitHubOAuthCoordinator
import com.ai.assistance.operit.widget.ToolPkgDesktopWidgetHost
import org.json.JSONObject
import kotlin.system.exitProcess

private const val TAG = "MainActivity"

internal fun shouldStartProcessOwnedInitialization(
    hasProcessStartupLease: Boolean,
    showPermissionGuide: Boolean,
    agreementAccepted: Boolean,
): Boolean = hasProcessStartupLease && !showPermissionGuide && agreementAccepted

internal fun shouldRunActivityStartupChecks(
    isFreshActivityLaunch: Boolean,
    hasProcessStartupLease: Boolean,
): Boolean = isFreshActivityLaunch || hasProcessStartupLease

internal class MainProcessStartupGate {
    class Lease internal constructor(val id: Long)

    private sealed interface State {
        data object Idle : State
        data class InProgress(val lease: Lease) : State
        data object Completed : State
    }

    private val nextLeaseId = AtomicLong(0L)
    private val state = AtomicReference<State>(State.Idle)

    fun claim(): Lease? {
        while (true) {
            when (val current = state.get()) {
                State.Completed, is State.InProgress -> return null
                State.Idle -> {
                    val lease = Lease(nextLeaseId.incrementAndGet())
                    if (state.compareAndSet(current, State.InProgress(lease))) return lease
                }
            }
        }
    }

    fun complete(lease: Lease): Boolean = transition(lease, State.Completed)

    fun release(lease: Lease): Boolean = transition(lease, State.Idle)

    private fun transition(lease: Lease, target: State): Boolean {
        while (true) {
            val current = state.get()
            if (current !is State.InProgress || current.lease !== lease) return false
            if (state.compareAndSet(current, target)) return true
        }
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN_SETTINGS_SHORTCUT = "com.ai.assistance.operit.action.OPEN_SETTINGS_SHORTCUT"
        private val processStartupGate = MainProcessStartupGate()
        private val processStartupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val processPluginLoadingState = PluginLoadingState()
    }

    // ======== 屏幕方向变更状态 ========
    private var showOrientationChangeDialog by mutableStateOf(false)

    private var lastOrientation: Int? = null

    // ======== 工具和管理器 ========
    private lateinit var toolHandler: AIToolHandler
    private lateinit var agreementPreferences: AgreementPreferences
    private lateinit var anrMonitor: AnrMonitor
    private lateinit var mcpRepository: MCPRepository

    // ======== MCP插件状态 ========
    private val pluginLoadingState = processPluginLoadingState
    private var pluginLoadingBinding: PluginLoadingStateRegistry.Binding? = null
    private var processStartupLease: MainProcessStartupGate.Lease? = null
    private var processStartupInitializationStarted = false

    // ======== 双击返回退出相关变量 ========
    private var backPressedTime: Long = 0
    private val backPressedInterval: Long = 2000 // 两次点击的时间间隔，单位为毫秒

    // UpdateManager实例
    private lateinit var updateManager: UpdateManager

    // 是否显示权限引导界面
    private var showPermissionGuide by mutableStateOf(false)

    // 存储待处理的分享文件URIs
    private var pendingSharedFileUris: List<Uri>? = null

    private var pendingSharedText: String? = null
    private var pendingGitHubAuthUri: Uri? = null
    private var pendingShortcutNavItem: NavItem? = null
    private var pendingShortcutRequestId: Long = 0L
    private var currentMainNavItem: NavItem = NavItem.AiChat
    private var pendingRouteId: String? = null
    private var pendingRouteArgs: Map<String, Any?> = emptyMap()
    private var pendingRouteRequestId: Long = 0L

    // 通知权限请求启动器
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            AppLogger.d(TAG, "通知权限已授予")
        } else {
            AppLogger.d(TAG, "通知权限被拒绝")
            Toast.makeText(this, getString(R.string.notification_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    private fun processPendingSharedText() {
        if (pendingSharedFileUris != null) {
            AppLogger.d(TAG, "Pending shared text will be processed with shared files")
            return
        }

        val text = pendingSharedText?.trim()
        if (text.isNullOrBlank()) {
            AppLogger.d(TAG, "No pending shared text to process")
            return
        }

        SharedFileHandler.setSharedText(text)
        AppLogger.d(TAG, "Successfully passed shared text to SharedFileHandler")
        pendingSharedText = null
    }

    private fun restoreRuntimeTaskViewVisibilityIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        if (AIForegroundService.isRunning.get()) return

        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.appTasks?.forEach { task ->
                try {
                    task.setExcludeFromRecents(false)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "恢复最近任务可见性失败", e)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "恢复运行时任务视图可见性失败", e)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // Do not open the main DataStore while migration owns the data directory.
        val code =
            if (MigrationStateStore.isMainDataAccessAllowed(newBase)) {
                LocaleUtils.getCurrentLanguage(newBase)
            } else {
                LocaleUtils.LanguageCodes.AUTO
            }
        val locale = LocaleUtils.getLocaleForLanguageCode(code, newBase)
        val config = Configuration(newBase.resources.configuration)

        // 设置语言配置
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            Locale.setDefault(locale)
        }

        // 使用createConfigurationContext创建新的本地化上下文
        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastOrientation = resources.configuration.orientation

        if (RawSnapshotBackupManager.isProcessRestartRequired()) {
            // If the migration is running in this process (e.g. Activity recreation during
            // PREPARING/REPLACING), do NOT exit: the migration coroutine owns the process and
            // the progress surface must stay alive. Only exit when no in-process migration is
            // active, meaning a previous process required restart and this is a fresh entry.
            if (RawSnapshotBackupManager.isPendingRestoreOperationRunningInProcess()) {
                showMigrationInProgressSurface()
                return
            }
            exitProcess(0)
        }

        // Set window background to solid color to prevent system theme leaking through
        window.setBackgroundDrawableResource(android.R.color.black)

        // Dedicated cold-start migration phase: check the migration state machine BEFORE
        // initializeMainApplication() starts WorkManager, foreground services, schedulers,
        // repositories and DataStore writers. The state lives in noBackupFilesDir, which raw
        // snapshots do not capture, so it survives crashes and is never included in safety
        // snapshots.
        val migrationSnapshot = RawSnapshotBackupManager.officialOperitMigrationState(applicationContext)
        when (
            MigrationStatePolicy.startupAction(
                migrationSnapshot.state,
                RawSnapshotBackupManager.isPendingRestoreOperationRunningInProcess()
            )
        ) {
            MigrationStatePolicy.StartupAction.RUN_PENDING -> {
                runPendingRestoreOperation()
                return
            }
            MigrationStatePolicy.StartupAction.SHOW_IN_PROGRESS -> {
                showMigrationInProgressSurface()
                return
            }
            MigrationStatePolicy.StartupAction.RESET_PREPARING -> {
                val reset = RawSnapshotBackupManager.cancelSafeOfficialOperitMigration(applicationContext)
                showMigrationStartupErrorSurface(
                    message = getString(R.string.backup_official_operit_migration_preparing_interrupted),
                    pendingResetRequired = !reset
                )
                return
            }
            MigrationStatePolicy.StartupAction.COMPLETE_RESTORE_REGISTRATION -> {
                // 原始快照恢复的目录替换已成功完成、受控补导 generation 尚未登记：
                // 自动补登记（不重新替换目录）后退出进程，下一个冷启动正常初始化
                // 并消费 marker 完成补导。
                completePendingRestoreRegistration()
                return
            }
            MigrationStatePolicy.StartupAction.SHOW_RECOVERY -> {
                // The migration was interrupted and the data directory may be partially
                // replaced, or the state file is corrupt. Do NOT initialize normally; show the
                // recovery surface so the user can restore from the pre-migration safety
                // snapshot (path recorded in the state file when available).
                showMigrationRecoverySurface(migrationSnapshot)
                return
            }
            MigrationStatePolicy.StartupAction.INITIALIZE -> {
                // Normal path: initializeMainApplication also checks the state, but it returns
                // Initialized for IDLE/COMPLETED. The redundant check is a defense-in-depth
                // guarantee that services restored before the activity also respect the gate.
            }
        }

        val operitApplication = application as OperitApplication
        val initResult = operitApplication.initializeMainApplication()
        when (initResult) {
            OperitApplication.MainApplicationInitResult.Initialized -> Unit
            is OperitApplication.MainApplicationInitResult.CompatibilityInitializationFailed -> {
                showCompatibilityInitializationErrorSurface(
                    initResult.message
                )
                return
            }
            OperitApplication.MainApplicationInitResult.MigrationInProgress,
            OperitApplication.MainApplicationInitResult.MigrationNeedsRecovery -> {
                // Should not happen here because non-IDLE/COMPLETED states were handled above,
                // but guard against a race where the state changed between the read and call.
                showMigrationRecoverySurface(
                    RawSnapshotBackupManager.officialOperitMigrationState(applicationContext)
                )
                return
            }
        }

        AppLogger.d(TAG, "MainActivity应用语言设置: ${LocaleUtils.getCurrentLanguage(this)}")
        AppLogger.d(TAG, "onCreate: Android SDK version: ${Build.VERSION.SDK_INT}")

        // Intent processing can touch repositories, so it runs only after the migration gate.
        handleIntent(intent)
        restoreRuntimeTaskViewVisibilityIfNeeded()

        // 语言设置已在Application中初始化，这里无需重复

        initializeComponents()
        anrMonitor.start()
        configureDisplaySettings()

        // 设置上下文以便获取插件元数据
        pluginLoadingState.setAppContext(this)
        pluginLoadingBinding = PluginLoadingStateRegistry.bind(pluginLoadingState, lifecycleScope)

        // 设置跳过加载的回调
        val startupApplicationContext = applicationContext
        pluginLoadingState.setOnSkipCallback {
            AppLogger.d(TAG, "用户跳过了插件加载过程")
            Toast.makeText(
                startupApplicationContext,
                startupApplicationContext.getString(R.string.plugin_loading_skipped),
                Toast.LENGTH_SHORT,
            ).show()
        }

        // 设置初始界面
        setAppContent()
        processPendingGitHubAuth()

        // 初始化并设置更新管理器
        setupUpdateManager()

        // Saved activity state can also be restored into a new process. A process-scoped gate
        // skips configuration-change recreation while still running startup after process death.
        processStartupLease = processStartupGate.claim()
        val isFreshActivityLaunch = savedInstanceState == null
        if (shouldRunActivityStartupChecks(
                isFreshActivityLaunch = isFreshActivityLaunch,
                hasProcessStartupLease = processStartupLease != null,
            )
        ) {
            performActivityStartupChecks(prepareStartupChat = isFreshActivityLaunch)
        }

        // 设置双击返回退出
        setupBackPressHandler()
    }

    /**
     * Run the pending official Operit migration in a dedicated cold-start phase.
     *
     * Shows a minimal migration progress surface while the migration runs on a background
     * thread, then exits the process so the next cold start enters normal mode. This runs BEFORE
     * [OperitApplication.initializeMainApplication], so WorkManager, foreground services,
     * schedulers, repositories and DataStore writers are not started yet.
     *
     * The migration state machine transitions PENDING -> PREPARING -> REPLACING -> COMPLETED (or
     * FAILED), persisted to noBackupFilesDir. If the process crashes during PREPARING or
     * REPLACING, the next cold start observes that state and [showMigrationRecoverySurface]
     * guides the user to restore from the safety snapshot instead of initializing normally with
     * partially-replaced data.
     */
    private fun runPendingRestoreOperation() {
        // Claim process ownership before Activity recreation can observe PREPARING and mistake
        // the active run for an interrupted migration.
        GlobalScope.launch(Dispatchers.IO + NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            try {
                RawSnapshotBackupManager.runPendingRestoreOperation(applicationContext)
                AppLogger.i(TAG, "Pending restore operation completed successfully")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Pending restore operation failed: ${e.message}", e)
            }
        }
        showMigrationInProgressSurface()
    }

    /**
     * Complete a pending raw-snapshot restore registration on cold start.
     *
     * Runs BEFORE [OperitApplication.initializeMainApplication], so no background writer can
     * race with the registration. The directories were already replaced by the previous
     * process; this only registers a fresh generation and completes the state machine
     * (RESTORE_REPLACED -> IDLE/COMPLETED), then exits the process so the next cold start
     * initializes normally and consumes the marker.
     *
     * On failure the process is NOT exited (that would create a cold-start retry loop); the
     * user sees an error surface and can relaunch to retry, or exit manually.
     */
    private fun completePendingRestoreRegistration() {
        setContent {
            OperitTheme {
                MigrationInProgressScreen()
            }
        }
        GlobalScope.launch(Dispatchers.IO + NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            try {
                RawSnapshotBackupManager.completePendingRestoreRegistration(applicationContext)
                AppLogger.i(TAG, "Restore registration completed on cold start")
                exitProcess(0)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Restore registration completion failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    showMigrationStartupErrorSurface(
                        message = getString(
                            R.string.backup_raw_snapshot_restore_registration_failed,
                            e.message ?: e.javaClass.name
                        ),
                        pendingResetRequired = false
                    )
                }
            }
        }
    }

    private fun showMigrationInProgressSurface() {
        // Show a minimal migration surface so the user sees something is happening instead of a
        // black screen while the process-owned migration continues across Activity recreation.
        setContent {
            OperitTheme {
                MigrationInProgressScreen()
            }
        }
        lifecycleScope.launch {
            while (RawSnapshotBackupManager.isPendingRestoreOperationRunningInProcess()) {
                delay(100)
            }

            val state = RawSnapshotBackupManager
                .officialOperitMigrationState(applicationContext)
                .state
            if (RawSnapshotBackupManager.isProcessRestartRequired()) {
                exitProcess(0)
            }
            val pendingFailure = RawSnapshotBackupManager.pendingRestoreOperationFailureMessage()
            if (pendingFailure != null &&
                (state == MigrationStateStore.State.IDLE || state == MigrationStateStore.State.COMPLETED)
            ) {
                showMigrationStartupErrorSurface(
                    message = pendingFailure,
                    pendingResetRequired = false,
                )
                return@launch
            }
            when (state) {
                MigrationStateStore.State.IDLE ->
                    showMigrationStartupErrorSurface(
                        message = RawSnapshotBackupManager.pendingRestoreOperationFailureMessage()
                            ?: state.name,
                        pendingResetRequired = false
                    )
                MigrationStateStore.State.PENDING,
                MigrationStateStore.State.PREPARING ->
                    showMigrationStartupErrorSurface(
                        message = RawSnapshotBackupManager.pendingRestoreOperationFailureMessage()
                            ?: state.name,
                        pendingResetRequired = true
                    )
                MigrationStateStore.State.REPLACING,
                MigrationStateStore.State.COMPLETED,
                MigrationStateStore.State.FAILED,
                MigrationStateStore.State.NEEDS_RECOVERY,
                // 官方迁移流程不会写入该状态；防御性退出，让下一个冷启动
                // 走自动补登记路径。
                MigrationStateStore.State.RESTORE_REPLACED -> exitProcess(0)
            }
        }
    }

    private fun showMigrationStartupErrorSurface(
        message: String,
        pendingResetRequired: Boolean
    ) {
        setContent {
            OperitTheme {
                MigrationStartupErrorScreen(message, pendingResetRequired)
            }
        }
    }

    private fun showCompatibilityInitializationErrorSurface(message: String) {
        setContent {
            OperitTheme {
                CompatibilityInitializationErrorScreen(
                    message = message,
                    onRetry = { recreate() },
                )
            }
        }
    }

    /**
     * Show the migration recovery surface when the migration was interrupted (PREPARING /
     * REPLACING / FAILED / NEEDS_RECOVERY). The data directory may be partially replaced or the
     * state file may be corrupt, so the app MUST NOT initialize normally. The user can restore
     * from the pre-migration safety snapshot (whose path is recorded in [snapshot] when
     * available). The state cannot be dismissed without a successful restore.
     */
    private fun showMigrationRecoverySurface(snapshot: MigrationStateStore.Snapshot) {
        AppLogger.w(
            TAG,
            "Migration was interrupted (state=${snapshot.state}, safety=${snapshot.safetyBackupPath}), showing recovery surface"
        )
        setContent {
            OperitTheme {
                MigrationRecoveryScreen(snapshot)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (!MigrationStateStore.isMainDataAccessAllowed(applicationContext)) return
        setIntent(intent) // 重要：更新当前Intent
        AppLogger.d(TAG, "onNewIntent: Received intent with action: ${intent?.action}")
        restoreRuntimeTaskViewVisibilityIfNeeded()
        val handledShortcutIntent = handleIntent(intent)

        if (handledShortcutIntent) {
            processPendingGitHubAuth()
            setAppContent()
            return
        }
        
        // 如果是分享或打开内容，立即处理
        if (intent?.action == Intent.ACTION_VIEW ||
            intent?.action == Intent.ACTION_SEND ||
            intent?.action == Intent.ACTION_SEND_MULTIPLE
        ) {
            processPendingSharedFiles()
            processPendingSharedText()
        }
    }

    private fun handleIntent(intent: Intent?): Boolean {
        if (intent?.action == ACTION_OPEN_SETTINGS_SHORTCUT) {
            pendingShortcutNavItem = NavItem.Settings
            pendingShortcutRequestId = System.currentTimeMillis()
            currentMainNavItem = NavItem.Settings
            AppLogger.d(TAG, "Shortcut requested opening settings")
            return true
        }

        val pendingWidgetRouteId =
            intent?.getStringExtra(ToolPkgDesktopWidgetHost.EXTRA_OPEN_ROUTE_ID)?.trim().orEmpty()
        if (pendingWidgetRouteId.isNotBlank()) {
            pendingRouteId = pendingWidgetRouteId
            pendingRouteArgs =
                parseRouteArgsJson(
                    intent?.getStringExtra(ToolPkgDesktopWidgetHost.EXTRA_OPEN_ROUTE_ARGS_JSON)
                )
            pendingRouteRequestId = System.currentTimeMillis()
            AppLogger.d(TAG, "Shortcut requested opening route: $pendingWidgetRouteId")
            return true
        }

        val intentUri = intent?.data
        if (GitHubAuthPreferences.isOAuthRedirectUri(intentUri)) {
            pendingGitHubAuthUri = intentUri
            // The URI contains a short-lived authorization code; never copy it into logs.
            AppLogger.d(TAG, "Received GitHub OAuth redirect")
            return true
        }
        
        // Handle opened and shared files
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                // Handle "Open with" action
                intent.data?.let { uri ->
                    if (uri.scheme == "http" || uri.scheme == "https") {
                        pendingSharedText = uri.toString()
                        AppLogger.d(TAG, "Received link to open: $uri")
                    } else {
                        pendingSharedFileUris = listOf(uri)
                        AppLogger.d(TAG, "Received file to open: $uri")
                    }
                }
            }
            Intent.ACTION_SEND -> {
                // Handle "Share" action
                @Suppress("DEPRECATION")
                val uri = if (Build.VERSION.SDK_INT >= 33) { // Build.VERSION_CODES.TIRAMISU
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                uri?.let {
                    pendingSharedFileUris = listOf(it)
                    AppLogger.d(TAG, "Received shared file: $it")
                }

                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    pendingSharedText = sharedText
                    AppLogger.d(TAG, "Received shared text")
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) {
                    pendingSharedFileUris = uris
                    AppLogger.d(TAG, "Received shared files: ${uris.size}")
                }

                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    pendingSharedText = sharedText
                    AppLogger.d(TAG, "Received shared text")
                }
            }
        }
        return false
    }

    private fun processPendingGitHubAuth() {
        val authUri = pendingGitHubAuthUri ?: return
        pendingGitHubAuthUri = null

        lifecycleScope.launch {
            val coordinator = GitHubOAuthCoordinator(this@MainActivity)
            val result = coordinator.completeExternalLogin(authUri)
            result.fold(
                onSuccess = { user ->
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.main_github_login_success, user.login),
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = { error ->
                    val message = error.message.orEmpty()
                    if (authUri.getQueryParameter("error") == "access_denied") {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.github_login_external_cancelled),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.main_github_login_failed, message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    AppLogger.e(TAG, "Failed to complete external GitHub login", error)
                }
            )
        }
    }

    private suspend fun prepareStartupChatIfNeeded() {
        try {
            val displayPreferencesManager =
                DisplayPreferencesManager.getInstance(this@MainActivity)
            if (!displayPreferencesManager.startWithNewChat.first()) {
                return
            }

            val chatHistoryManager = ChatHistoryManager.getInstance(this@MainActivity)
            val newChat = chatHistoryManager.createNewChat(
                setAsCurrentChat = false
            )
            chatHistoryManager.setCurrentChatId(newChat.id)
            AppLogger.d(TAG, "启动时已创建新的空白聊天")
        } catch (e: Exception) {
            AppLogger.e(TAG, "启动时创建空白聊天失败", e)
        }
    }

    private fun parseRouteArgsJson(raw: String?): Map<String, Any?> {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) {
            return emptyMap()
        }
        return try {
            val json = JSONObject(text)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.opt(key)
                    put(key, if (value == JSONObject.NULL) null else value)
                }
            }
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to parse pending route args json", error)
            emptyMap()
        }
    }

    // ======== 设置初始占位内容 ========

    // ======== 执行初始化检查 ========
    private fun performActivityStartupChecks(prepareStartupChat: Boolean) {
        lifecycleScope.launch {
            // 1. 检查通知权限（Android 13+）
            checkNotificationPermission()

            // 2. 检查权限级别设置
            checkPermissionLevelSet()

            if (prepareStartupChat) prepareStartupChatIfNeeded()

            // 3. 在协议已接受且无需权限引导时，启动插件加载
            if (shouldStartProcessOwnedInitialization(
                    hasProcessStartupLease = processStartupLease != null,
                    showPermissionGuide = showPermissionGuide,
                    agreementAccepted = agreementPreferences.isAgreementAccepted(),
                )
            ) {
                startPluginLoading()
            }
        }
    }

    // ======== 启动插件加载 ========
    private fun startPluginLoading() {
        if (processStartupInitializationStarted) return
        processStartupInitializationStarted = true

        // Reserve initialization before the first-frame delay so an MCP-only restart cannot
        // slip into the gap and make APP_BOOT appear to have started when it did not.
        val initializationReservation = pluginLoadingState.reserveInitialization()
        if (initializationReservation == null) {
            processStartupLease?.let(processStartupGate::release)
            processStartupLease = null
            processStartupInitializationStarted = false
            return
        }

        // 显示插件加载界面
        pluginLoadingState.show()

        // 启动超时检测（30秒）
        val startupTimeoutOwner =
            pluginLoadingState.startTimeoutCheck(30000L, processStartupScope)

        // 初始化MCP服务器并启动插件
        // 轻微延迟让首帧 Compose 完成，避免启动阶段后台重任务立刻抢占导致掉帧
        val lease = processStartupLease
        processStartupScope.launch {
            delay(500)
            val startedNow = pluginLoadingState.initializeMCPServer(
                applicationContext,
                com.ai.assistance.operit.ui.features.startup.screens.PluginStartupScope.APP_BOOT,
                initialTimeoutOwner = startupTimeoutOwner,
                reservedInitializationLease = initializationReservation,
            ) { completion ->
                if (lease == null) return@initializeMCPServer
                if (completion.completed) processStartupGate.complete(lease)
                else processStartupGate.release(lease)
            }
            if (startedNow == null && lease != null) {
                processStartupGate.release(lease)
            }
        }
    }

    // ======== 处理待处理的分享文件 ========
    private fun processPendingSharedFiles() {
        val uris = pendingSharedFileUris
        if (uris == null) {
            AppLogger.d(TAG, "No pending shared files to process")
            return
        }
        
        AppLogger.d(TAG, "Processing ${uris.size} pending shared file(s)")
        uris.forEachIndexed { index, uri ->
            AppLogger.d(TAG, "  [$index] URI: $uri")
        }
        
        lifecycleScope.launch {
            try {
                // Pass the URIs to the chat screen via SharedFileHandler
                val sharedText = pendingSharedText?.trim()
                SharedFileHandler.setSharedFiles(uris, sharedText)
                AppLogger.d(TAG, "Successfully passed shared files to SharedFileHandler")
                pendingSharedFileUris = null
                pendingSharedText = null
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to process shared files", e)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.chat_process_shared_files_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
                pendingSharedFileUris = null
            }
        }
    }

    // 配置双击返回退出的处理器
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        val currentTime = System.currentTimeMillis()

                        if (currentTime - backPressedTime > backPressedInterval) {
                            // 第一次点击，显示提示
                            backPressedTime = currentTime
                            Toast.makeText(this@MainActivity, getString(R.string.press_back_again_to_exit), Toast.LENGTH_SHORT).show()
                        } else {
                            // 第二次点击，退出应用
                            finish()
                        }
                    }
                }
        )
    }


    override fun onDestroy() {
        if (!processStartupInitializationStarted) {
            processStartupLease?.let(processStartupGate::release)
        }
        processStartupLease = null
        super.onDestroy()
        AppLogger.d(TAG, "onDestroy called")

        val isLastPluginLoadingBinding =
            pluginLoadingBinding?.let(PluginLoadingStateRegistry::unbind) == true
        pluginLoadingBinding = null

        // The process-scoped startup job and its UI state survive Activity recreation.
        if (!isChangingConfigurations && isLastPluginLoadingBinding) {
            pluginLoadingState.hide()
        }

        // 主界面销毁时，确保关闭虚拟屏幕 Overlay 并断开 Shower WebSocket 连接
        try {
            VirtualDisplayOverlay.hideAll()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error hiding VirtualDisplayOverlay in MainActivity.onDestroy", e)
        }

        if (::anrMonitor.isInitialized) {
            anrMonitor.stop()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLogger.d(TAG, "onConfigurationChanged: new orientation=${newConfig.orientation}, last orientation=${lastOrientation}")

        // 屏幕方向变化时，确保加载界面不可见
        if (pluginLoadingBinding?.let(PluginLoadingStateRegistry::isActive) == true) {
            pluginLoadingState.hide()
        }

        // 仅当方向确实发生变化时才处理
        if (newConfig.orientation != lastOrientation) {
            // 记录变化前的方向
            val orientationBeforeChange = lastOrientation
            // 更新最后的方向记录
            lastOrientation = newConfig.orientation

            // 检查是否是“转回去”的操作
            if (showOrientationChangeDialog && newConfig.orientation == orientationBeforeChange) {
                // 如果是，隐藏弹窗并结束
                showOrientationChangeDialog = false
                return
            }
            
            // 如果不是“转回去”，或者弹窗还未显示，则显示弹窗
            showOrientationChangeDialog = true
        }
    }

    // ======== 初始化组件 ========
    private fun initializeComponents() {
        // 初始化工具处理器（工具注册已在Application中完成）
        toolHandler = AIToolHandler.getInstance(this)

        // 初始化MCP仓库
        mcpRepository = MCPRepository(this)

        anrMonitor = AnrMonitor(this, lifecycleScope)

        // 初始化协议偏好管理器
        agreementPreferences = AgreementPreferences(this)

    }

    // ======== 检查通知权限 ========
    private fun checkNotificationPermission() {
        // Android 13 (API 33) 及以上需要请求通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                    AppLogger.d(TAG, "通知权限已授予")
                }
                shouldShowRequestPermissionRationale(permission) -> {
                    // 用户之前拒绝过，显示说明并再次请求
                    AppLogger.d(TAG, "需要显示通知权限说明")
                    Toast.makeText(
                        this,
                        getString(R.string.notification_permission_rationale),
                        Toast.LENGTH_LONG
                    ).show()
                    notificationPermissionLauncher.launch(permission)
                }
                else -> {
                    // 直接请求权限
                    AppLogger.d(TAG, "请求通知权限")
                    notificationPermissionLauncher.launch(permission)
                }
            }
        } else {
            // Android 13 以下不需要运行时通知权限
            AppLogger.d(TAG, "Android 版本 < 13，无需请求通知权限")
        }
    }

    // ======== 检查权限级别设置 ========
    private fun checkPermissionLevelSet() {
        // 检查是否已设置权限级别
        val permissionLevel = androidPermissionPreferences.getPreferredPermissionLevel()
        AppLogger.d(TAG, "当前权限级别: $permissionLevel")
        showPermissionGuide = permissionLevel == null
        AppLogger.d(
                TAG,
                "权限级别检查: 已设置=${!showPermissionGuide}, 将${if(showPermissionGuide) "" else "不"}显示权限引导界面"
        )
    }

    // ======== 显示与性能配置 ========
    private fun configureDisplaySettings() {
        // 1. 请求持续的高性能模式 (API 31+)
        // 这会提示系统为应用提供持续的高性能，避免CPU/GPU降频。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.setSustainedPerformanceMode(true)
                AppLogger.d(TAG, "已成功请求持续高性能模式。")
            } catch (e: Exception) {
                // 在某些设备上，此模式可能不可用或不支持。
                AppLogger.w(TAG, "请求持续高性能模式失败。", e)
            }
        }

        // 2. 设置应用以最高刷新率运行
        // 高刷新率优化：通过设置窗口属性确保流畅
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 为Android 11+设备优化高刷新率
            val highestMode = getHighestRefreshRate()
            if (highestMode > 0) {
                window.attributes.preferredDisplayModeId = highestMode
                AppLogger.d(TAG, "设置窗口首选显示模式ID: $highestMode")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 为Android 6.0-10设备优化高刷新率
            val refreshRate = getDeviceRefreshRate()
            if (refreshRate > 60f) {
                window.attributes.preferredRefreshRate = refreshRate
                AppLogger.d(TAG, "设置窗口首选刷新率: $refreshRate Hz")
            }
        }

        // 启用硬件加速以提高渲染性能
        window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
    }

    // ======== 设置应用内容 ========
    private fun setAppContent() {
        setContent {
            OperitTheme {
                Box {
                    // 检查是否需要显示用户协议
                        if (!agreementPreferences.isAgreementAccepted()) {
                            AgreementScreen(
                                    onAgreementAccepted = {
                                        agreementPreferences.acceptCurrentAgreement()
                                        // 协议接受后，检查权限级别设置
                                        lifecycleScope.launch {
                                            // 确保使用非阻塞方式更新UI
                                            delay(300) // 短暂延迟确保UI状态更新
                                            checkPermissionLevelSet()
                                            if (!showPermissionGuide) {
                                                startPluginLoading()
                                            }
                                            // 重新设置应用内容
                                            setAppContent()
                                        }
                                    }
                            )
                        }
                        // 检查是否需要显示权限引导界面
                        else if (showPermissionGuide) {
                            PermissionGuideScreen(
                                    onComplete = {
                                        showPermissionGuide = false
                                        // 权限设置完成后，启动插件加载并更新内容
                                        startPluginLoading()
                                        setAppContent()
                                    }
                            )
                        }
                        // 显示主应用界面
                        else {
                            // 处理待处理的分享文件
                            processPendingSharedFiles()
                            processPendingSharedText()
                            val shortcutNavItem = pendingShortcutNavItem
                            val shortcutNavRequestId = pendingShortcutRequestId
                            val routeNavRequest = pendingRouteId
                            val routeNavArgs = pendingRouteArgs
                            val routeNavRequestId = pendingRouteRequestId
                            val initialNavItem = when {
                                shortcutNavItem != null -> shortcutNavItem
                                else -> currentMainNavItem
                            }

                            CompositionLocalProvider(LocalPluginLoadingState provides pluginLoadingState) {
                                // 主应用界面 (始终存在于底层)
                                OperitApp(
                                        initialNavItem = initialNavItem,
                                        toolHandler = toolHandler,
                                        shortcutNavRequest = shortcutNavItem,
                                        shortcutNavRequestId = shortcutNavRequestId,
                                        routeNavRequest = routeNavRequest,
                                        routeNavArgs = routeNavArgs,
                                        routeNavRequestId = routeNavRequestId,
                                        onShortcutNavHandled = { handledRequestId ->
                                            if (pendingShortcutRequestId == handledRequestId) {
                                                pendingShortcutNavItem = null
                                                pendingShortcutRequestId = 0L
                                            }
                                        },
                                        onCurrentNavItemChanged = { navItem ->
                                            currentMainNavItem = navItem
                                        },
                                        onRouteNavHandled = { handledRequestId ->
                                            if (pendingRouteRequestId == handledRequestId) {
                                                pendingRouteId = null
                                                pendingRouteArgs = emptyMap()
                                                pendingRouteRequestId = 0L
                                            }
                                        }
                                )
                            }
                        }
                    }
                    // 插件加载界面 (带有淡出效果) - 始终在最上层
                    PluginLoadingScreenWithState(
                            loadingState = pluginLoadingState,
                            modifier = Modifier.zIndex(10f) // 确保加载界面在最上层
                    )
                }

                // 方向改变时显示对话框
                if (showOrientationChangeDialog) {
                    OrientationChangeDialog(
                        onConfirm = {
                            showOrientationChangeDialog = false
                            // 重新创建Activity以重新加载页面
                            recreate()
                        },
                        onDismiss = {
                            showOrientationChangeDialog = false
                        }
                    )
                }
            }
        }


    private fun getHighestRefreshRate(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayModes = display?.supportedModes ?: return 0
            var maxRefreshRate = 60f // Default to 60Hz
            var highestModeId = 0

            for (mode in displayModes) {
                if (mode.refreshRate > maxRefreshRate) {
                    maxRefreshRate = mode.refreshRate
                    highestModeId = mode.modeId
                }
            }
            AppLogger.d(TAG, "Selected display mode with refresh rate: $maxRefreshRate Hz")
            return highestModeId
        }
        return 0
    }

    private fun getDeviceRefreshRate(): Float {
        val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val display =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION") windowManager.defaultDisplay
                }

        var refreshRate = 60f // Default refresh rate

        if (display != null) {
            try {
                @Suppress("DEPRECATION") val modes = display.supportedModes
                for (mode in modes) {
                    if (mode.refreshRate > refreshRate) {
                        refreshRate = mode.refreshRate
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting refresh rate", e)
            }
        }

        AppLogger.d(TAG, "Selected refresh rate: $refreshRate Hz")
        return refreshRate
    }

    // ======== 设置更新管理器 ========
    private fun setupUpdateManager() {
        // 获取UpdateManager实例
        updateManager = UpdateManager.getInstance(this)

        // 观察更新状态（beta / 非 beta 都提示新版本）
        updateManager.updateStatus.observe(
            this,
            Observer { status ->
                when (status) {
                    is UpdateStatus.Available -> showUpdateNotification(status.newVersion)
                    is UpdateStatus.PatchAvailable -> showUpdateNotification(status.newVersion)
                    else -> Unit
                }
            }
        )
    }

    private fun showUpdateNotification(newVersion: String) {
        val currentVersion =
            try {
                packageManager.getPackageInfo(packageName, 0).versionName
                    ?: getString(R.string.unknown_value)
            } catch (e: Exception) {
                getString(R.string.unknown_value)
            }

        AppLogger.d(TAG, "发现新版本: $newVersion，当前版本: $currentVersion")

        // 显示更新提示
        Toast.makeText(
            this,
            getString(R.string.main_update_available_toast, newVersion),
            Toast.LENGTH_LONG
        ).show()
    }

}

@Composable
private fun OrientationChangeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.dialog_title_orientation_change)) },
        text = { Text(text = stringResource(id = R.string.dialog_message_orientation_change)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(id = R.string.dialog_button_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.dialog_button_dismiss))
            }
        }
    )
}

@Composable
private fun MigrationInProgressScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(id = R.string.backup_official_operit_migration_in_progress),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun MigrationStartupErrorScreen(message: String, pendingResetRequired: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cancelling by remember { mutableStateOf(false) }
    var cancellationFailed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = stringResource(
                    id = if (pendingResetRequired) {
                        R.string.backup_official_operit_migration_pending_error_title
                    } else {
                        R.string.backup_official_operit_migration_error_title
                    }
                ),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                text = stringResource(
                    id = if (pendingResetRequired) {
                        R.string.backup_official_operit_migration_pending_error_message
                    } else {
                        R.string.backup_official_operit_migration_startup_error_message
                    },
                    message
                ),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (pendingResetRequired && cancellationFailed) {
                Text(
                    text = stringResource(id = R.string.backup_official_operit_migration_pending_cancel_failed),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (pendingResetRequired && cancelling) {
                CircularProgressIndicator()
            } else if (pendingResetRequired) {
                TextButton(onClick = {
                    cancelling = true
                    cancellationFailed = false
                    scope.launch {
                        val cancelled = withContext(Dispatchers.IO + NonCancellable) {
                            runCatching {
                                RawSnapshotBackupManager.cancelSafeOfficialOperitMigration(context)
                            }.onFailure { error ->
                                AppLogger.e(TAG, "failed to cancel pending migration", error)
                            }.getOrDefault(false)
                        }
                        if (cancelled) {
                            exitProcess(0)
                        } else {
                            cancellationFailed = true
                            cancelling = false
                        }
                    }
                }) {
                    Text(stringResource(id = R.string.backup_official_operit_migration_pending_cancel))
                }
            } else {
                TextButton(onClick = { exitProcess(0) }) {
                    Text(stringResource(id = R.string.backup_official_operit_migration_restart_to_settings))
                }
            }
            TextButton(onClick = { exitProcess(0) }) {
                Text(stringResource(id = R.string.backup_official_operit_migration_recovery_exit))
            }
        }
    }
}

@Composable
private fun CompatibilityInitializationErrorScreen(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.legacy_storage_initialization_error_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.legacy_storage_initialization_error_message, message),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.legacy_storage_initialization_retry))
            }
            TextButton(onClick = { exitProcess(0) }) {
                Text(stringResource(R.string.backup_official_operit_migration_recovery_exit))
            }
        }
    }
}

@Composable
private fun MigrationRecoveryScreen(snapshot: MigrationStateStore.Snapshot) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshots by remember {
        mutableStateOf(RawSnapshotBackupManager.listRawSnapshots())
    }
    var selectedPath by remember { mutableStateOf(snapshot.safetyBackupPath ?: snapshots.firstOrNull()?.absolutePath) }
    var restoring by remember { mutableStateOf(false) }
    var restoreError by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = stringResource(id = R.string.backup_official_operit_migration_recovery_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                text = stringResource(id = R.string.backup_official_operit_migration_recovery_message),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (snapshot.state == MigrationStateStore.State.FAILED) {
                Text(
                    text = stringResource(id = R.string.backup_official_operit_migration_recovery_failed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else if (snapshot.state == MigrationStateStore.State.NEEDS_RECOVERY) {
                Text(
                    text = stringResource(id = R.string.backup_official_operit_migration_recovery_corrupt_hint),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            if (restoring) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(id = R.string.backup_official_operit_migration_recovery_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
                restoreError?.let { err ->
                    Text(
                        text = stringResource(
                            id = R.string.backup_official_operit_migration_recovery_restore_failed,
                            err
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (snapshots.isEmpty()) {
                    Text(
                        text = stringResource(id = R.string.backup_official_operit_migration_recovery_no_snapshots),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                } else {
                    val recommendedPath = snapshot.safetyBackupPath
                    if (recommendedPath != null) {
                        Text(
                            text = stringResource(id = R.string.backup_official_operit_migration_recovery_recommended),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = recommendedPath.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = stringResource(id = R.string.backup_official_operit_migration_recovery_available),
                        style = MaterialTheme.typography.labelMedium
                    )
                    snapshots.forEach { file ->
                        val path = file.absolutePath
                        val isSelected = path == selectedPath
                        TextButton(
                            onClick = { selectedPath = path },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = (if (isSelected) "● " else "○ ") + file.name,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Start
                            )
                        }
                    }
                }

                val selectedFile = selectedPath?.let { File(it) }
                val canRestore = selectedFile != null && selectedFile.isFile
                TextButton(
                    enabled = canRestore,
                    onClick = {
                        restoring = true
                        restoreError = null
                        scope.launch(Dispatchers.IO + NonCancellable) {
                            try {
                                RawSnapshotBackupManager.restoreFromBackupFile(context, selectedFile!!)
                                exitProcess(0)
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "recovery restore failed", e)
                                restoreError = e.message ?: e.javaClass.name
                                restoring = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(id = R.string.backup_official_operit_migration_recovery_restore))
                }
            }
            TextButton(onClick = { exitProcess(0) }) {
                Text(stringResource(id = R.string.backup_official_operit_migration_recovery_exit))
            }
        }
    }
}
