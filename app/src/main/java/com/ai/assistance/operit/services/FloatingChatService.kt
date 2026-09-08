package com.ai.assistance.operit.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.View
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import com.ai.assistance.operit.core.application.ForegroundServiceCompat
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.api.speech.SpeechServiceFactory
import com.ai.assistance.operit.api.voice.VoiceServiceFactory
import com.ai.assistance.operit.data.model.AttachmentInfo
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.WakeWordPreferences
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.data.model.SerializableColorScheme
import com.ai.assistance.operit.data.model.SerializableTypography
import com.ai.assistance.operit.data.model.toComposeColorScheme
import com.ai.assistance.operit.data.model.toComposeTypography
import com.ai.assistance.operit.data.model.PromptFunctionType
import com.ai.assistance.operit.services.floating.FloatingWindowCallback
import com.ai.assistance.operit.services.floating.FloatingWindowManager
import com.ai.assistance.operit.services.floating.FloatingWindowState
import com.ai.assistance.operit.services.floating.StatusIndicatorStyle
import com.ai.assistance.operit.ui.floating.FloatingMode
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.FileUtils
import com.ai.assistance.operit.util.WaifuMessageProcessor
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

class FloatingChatService : Service(), FloatingWindowCallback {
    private val TAG = "FloatingChatService"
    private val binder = LocalBinder()

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "floating_chat_channel"

    private val PREF_KEY_STATUS_INDICATOR_STYLE = "status_indicator_style"
    private val PREF_KEY_COLOR_SCHEME = "floating_color_scheme_json"
    private val PREF_KEY_TYPOGRAPHY = "floating_typography_json"

    lateinit var windowState: FloatingWindowState
    private lateinit var windowManager: FloatingWindowManager
    private lateinit var prefs: SharedPreferences
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var lifecycleOwner: ServiceLifecycleOwner
    private val chatMessages = mutableStateOf<List<ChatMessage>>(emptyList())
    private val attachments = mutableStateOf<List<AttachmentInfo>>(emptyList())
    private val inputProcessingState = mutableStateOf<InputProcessingState>(InputProcessingState.Idle)

    // 聊天服务核心 - 整合所有业务逻辑
    private var selectedCore by mutableStateOf<ChatServiceCore?>(null)
    private val boundCore: ChatServiceCore get() = checkNotNull(selectedCore)
    var currentChatSlot: ChatRuntimeSlot = ChatRuntimeSlot.FLOATING
        private set
    private var coreObservation: kotlinx.coroutines.Job? = null

    private var lastCrashTime = 0L
    private var crashCount = 0
    private val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val customExceptionHandler =
            Thread.UncaughtExceptionHandler { thread, throwable ->
                handleServiceCrash(thread, throwable)
            }

    private val colorScheme = mutableStateOf<ColorScheme?>(null)
    private val typography = mutableStateOf<Typography?>(null)
    private val gson = Gson()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var hasHandledStartCommand = false

    companion object {
        private const val ACTION_CLOSE = "com.ai.assistance.operit.action.CLOSE_FLOATING_CHAT"
        @Volatile
        private var instance: FloatingChatService? = null

        const val ACTION_FLOATING_CHAT_SERVICE_STARTED = "com.ai.assistance.operit.action.FLOATING_CHAT_SERVICE_STARTED"
        const val ACTION_FLOATING_CHAT_SERVICE_STOPPED = "com.ai.assistance.operit.action.FLOATING_CHAT_SERVICE_STOPPED"
        const val ACTION_FLOATING_CHAT_WINDOW_SHOWN = "com.ai.assistance.operit.action.FLOATING_CHAT_WINDOW_SHOWN"
        const val ACTION_FLOATING_CHAT_WINDOW_SHOW_FAILED = "com.ai.assistance.operit.action.FLOATING_CHAT_WINDOW_SHOW_FAILED"

        const val EXTRA_AUTO_ENTER_VOICE_CHAT = "AUTO_ENTER_VOICE_CHAT"
        const val EXTRA_WAKE_LAUNCHED = "WAKE_LAUNCHED"
        const val EXTRA_AUTO_EXIT_AFTER_MS = "AUTO_EXIT_AFTER_MS"
        const val EXTRA_CHAT_SLOT = "PET_CHAT_SLOT"
        const val EXTRA_CHAT_ID = "PET_CHAT_ID"
        const val EXTRA_KEEP_IF_EXISTS = "KEEP_IF_EXISTS"

        fun getInstance(): FloatingChatService? = instance
    }

    private val autoEnterVoiceChat = mutableStateOf(false)
    private val wakeLaunched = mutableStateOf(false)

    private val autoExitHandler = Handler(Looper.getMainLooper())
    private var autoExitRunnable: Runnable? = null

    private val wakePrefs by lazy { WakeWordPreferences(applicationContext) }

    fun hasPendingVoiceChat(): Boolean = autoEnterVoiceChat.value

    fun consumeAutoEnterVoiceChat(): Boolean {
        val value = autoEnterVoiceChat.value
        if (value) {
            autoEnterVoiceChat.value = false
        }
        return value
    }

    fun isWakeLaunched(): Boolean = wakeLaunched.value

    private fun scheduleAutoExit(timeoutMs: Long?) {
        val previous = autoExitRunnable
        if (previous != null) {
            autoExitHandler.removeCallbacks(previous)
        }
        autoExitRunnable = null

        val effectiveTimeout = timeoutMs?.takeIf { it > 0 }
        if (effectiveTimeout != null) {
            val r = Runnable {
                AppLogger.d(TAG, "Auto exit triggered after ${effectiveTimeout}ms")
                onClose()
            }
            autoExitRunnable = r
            autoExitHandler.postDelayed(r, effectiveTimeout)
        }
    }

    inner class LocalBinder : Binder() {
        private val closeCallbacks = mutableListOf<() -> Unit>()

        fun getService(): FloatingChatService = this@FloatingChatService
        fun getChatCore(): ChatServiceCore = boundCore

        fun setCloseCallback(callback: () -> Unit) {
            closeCallbacks.add(callback)
        }

        fun notifyClose() {
            closeCallbacks.toList().forEach { it.invoke() }
        }

        fun clearCallbacks() {
            closeCallbacks.clear()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun handleServiceCrash(thread: Thread, throwable: Throwable) {
        try {
            AppLogger.e(TAG, "Service crashed: ${throwable.message}", throwable)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCrashTime > 60000) {
                crashCount = 0
            }
            lastCrashTime = currentTime
            crashCount++

            if (crashCount > 3) {
                AppLogger.e(TAG, "Too many crashes in short time, stopping service")
                prefs.edit().putBoolean("service_disabled_due_to_crashes", true).apply()
                stopSelf()
                return
            }

            saveState()
            val intent = Intent(applicationContext, FloatingChatService::class.java)
            intent.setPackage(packageName)
            startService(intent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error handling crash", e)
        } finally {
            defaultExceptionHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Global migration gate: if a migration is pending or was interrupted, do NOT initialize
        // the application. Stop the service and exit the process so the next cold start routes
        // through MainActivity, which drives the migration or recovery surface.
        val initResult = (application as OperitApplication).initializeMainApplication()
        when (initResult) {
            OperitApplication.MainApplicationInitResult.Initialized -> { /* proceed */ }
            OperitApplication.MainApplicationInitResult.MigrationInProgress -> {
                // This process may own the migration. Killing it when Android restores this
                // sticky service would terminate the migration mid-run.
                AppLogger.w(TAG, "Migration in progress, stopping service without killing process")
                stopSelf()
                return
            }
            OperitApplication.MainApplicationInitResult.MigrationNeedsRecovery -> {
                // Data may be partially replaced or corrupt; exit so the next cold start routes
                // through MainActivity's recovery surface.
                AppLogger.w(TAG, "Migration needs recovery, stopping service and exiting process")
                stopSelf()
                exitProcess(0)
                return
            }
            is OperitApplication.MainApplicationInitResult.CompatibilityInitializationFailed -> {
                AppLogger.e(TAG, "Legacy compatibility initialization failed; stopping service")
                stopSelf()
                return
            }
        }
        AppLogger.d(TAG, "onCreate")

        instance = this

        try {
            sendBroadcast(
                Intent(ACTION_FLOATING_CHAT_SERVICE_STARTED)
                    .setPackage(packageName)
            )
        } catch (_: Exception) {
        }

        Thread.setDefaultUncaughtExceptionHandler(customExceptionHandler)

        prefs = getSharedPreferences("floating_chat_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("service_disabled_due_to_crashes", false)) {
            AppLogger.w(TAG, "Service was disabled due to frequent crashes")
            stopSelf()
            return
        }

        try {

            bindChatCore(ChatRuntimeSlot.FLOATING)

            lifecycleOwner = ServiceLifecycleOwner()
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            windowState = FloatingWindowState(this)
            windowManager =
                    FloatingWindowManager(
                            this,
                            windowState,
                            lifecycleOwner,
                            lifecycleOwner,
                            lifecycleOwner,
                            this
                    )
            serviceScope.launch {
                val holder = ChatRuntimeHolder.getInstance(applicationContext)
                val activeRuns = combine(ChatRuntimeSlot.entries.map { holder.getCore(it).activeStreamingChatIds }) { runs ->
                    runs.any { it.isNotEmpty() }
                }
                combine(activeRuns, snapshotFlow { windowState.currentMode.value }) { active, mode ->
                    active || mode == FloatingMode.VOICE_BALL || mode == FloatingMode.FULLSCREEN
                }.collect { needsWakeLock ->
                    if (needsWakeLock) acquireWakeLock() else releaseWakeLock()
                }
            }
            createNotificationChannel()
            val notification = createNotification()
            ForegroundServiceCompat.startForeground(
                service = this,
                notificationId = NOTIFICATION_ID,
                notification = notification,
                types = ForegroundServiceCompat.buildTypes(dataSync = true)
            )

        } catch (e: Exception) {
            AppLogger.e(TAG, "Error in onCreate", e)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock =
                        powerManager.newWakeLock(
                                PowerManager.PARTIAL_WAKE_LOCK,
                                "OperitApp:FloatingChatServiceWakeLock"
                        )
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L)
                AppLogger.d(TAG, "WakeLock acquired")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error acquiring WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                AppLogger.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error releasing WakeLock", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.floating_chat_window_title)
            val descriptionText = getString(R.string.floating_service_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel =
                    NotificationChannel(CHANNEL_ID, name, importance).apply {
                        description = descriptionText
                        setShowBadge(false)
                    }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification() =
            NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(getString(R.string.floating_chat_window_title))
                    .setContentText(getString(R.string.floating_chat_running_in_background))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setContentIntent(getPendingIntent())
                    .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.floating_close_floating_window),
                        PendingIntent.getService(
                            this, 2, Intent(this, FloatingChatService::class.java).setAction(ACTION_CLOSE),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .build()

    private fun getPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        return PendingIntent.getActivity(
                this,
                0,
                intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE
                else 0
        )
    }

    /** Observe the existing runtime; opening a window must not restart or copy an active turn. */
    private fun bindChatCore(slot: ChatRuntimeSlot) {
        val core = ChatRuntimeHolder.getInstance(applicationContext).getCore(slot)
        if (selectedCore === core) return
        coreObservation?.cancel()
        selectedCore = core
        currentChatSlot = slot
        inputProcessingState.value = core.inputProcessingStateByChatId.value[core.currentChatId.value] ?: InputProcessingState.Idle
        chatMessages.value = core.chatHistory.value
        attachments.value = core.attachments.value
        coreObservation = serviceScope.launch {
            launch { core.chatHistory.collect { chatMessages.value = it } }
            launch { core.attachments.collect { attachments.value = it } }
            launch {
                combine(core.currentChatId, core.inputProcessingStateByChatId) { id, states ->
                    states[id] ?: InputProcessingState.Idle
                }.collect { inputProcessingState.value = it }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            onClose()
            return START_NOT_STICKY
        }
        AppLogger.d(TAG, "onStartCommand")
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        try {

            intent?.getStringExtra(EXTRA_CHAT_SLOT)?.let { name ->
                ChatRuntimeSlot.entries.firstOrNull { it.name == name }?.let(::bindChatCore)
                intent.getStringExtra(EXTRA_CHAT_ID)?.takeIf { it != boundCore.currentChatId.value }?.let {
                    boundCore.switchChatLocal(it)
                }
                if (intent.getStringExtra(EXTRA_CHAT_ID).isNullOrBlank() && boundCore.currentChatId.value == null) boundCore.createNewChat()
            }

            val keepIfExists = intent?.getBooleanExtra(EXTRA_KEEP_IF_EXISTS, false) == true
            val isFirstStart = !hasHandledStartCommand
            if (keepIfExists && instance != null && !isFirstStart) {
                AppLogger.d(TAG, "Service already running; keep_if_exists=true, skip mode change")
            } else {
                // Handle initial mode from intent
                intent?.getStringExtra("INITIAL_MODE")?.let { modeName ->
                    try {
                        val mode = FloatingMode.valueOf(modeName)
                        if (isFirstStart) windowState.currentMode.value = mode
                        else switchToMode(mode)
                        AppLogger.d(TAG, "Set mode from intent: $mode")
                    } catch (e: IllegalArgumentException) {
                        AppLogger.w(TAG, "Invalid mode name in intent: $modeName")
                    }
                }
            }

            hasHandledStartCommand = true

            val isFullscreenMode =
                windowState.currentMode.value == FloatingMode.FULLSCREEN ||
                    windowState.currentMode.value == FloatingMode.SCREEN_OCR
            AIForegroundService.setWakeListeningSuspendedForFloatingFullscreen(
                applicationContext,
                isFullscreenMode
            )

            val autoEnterVoiceChatExtra = intent?.getBooleanExtra(EXTRA_AUTO_ENTER_VOICE_CHAT, false) == true
            if (autoEnterVoiceChatExtra) {
                autoEnterVoiceChat.value = true
            }
            val wakeLaunchedExtra = if (intent?.hasExtra(EXTRA_WAKE_LAUNCHED) == true) {
                intent.getBooleanExtra(EXTRA_WAKE_LAUNCHED, false)
            } else {
                false
            }
            if (intent?.hasExtra(EXTRA_WAKE_LAUNCHED) == true) {
                wakeLaunched.value = wakeLaunchedExtra
            }

            if (wakeLaunchedExtra) {
                serviceScope.launch {
                    val enabled = wakePrefs.wakeCreateNewChatOnWakeEnabledFlow.first()
                    if (enabled) {
                        val currentChatId = boundCore.currentChatId.value
                        if (currentChatId != null) {
                            var history = boundCore.chatHistory.value
                            var waitCount = 0
                            while (history.isEmpty() && waitCount < 6) {
                                kotlinx.coroutines.delay(80)
                                waitCount++
                                history = boundCore.chatHistory.value
                            }

                            val hasAnyUserMessage = history.any { it.sender == "user" }
                            if (!hasAnyUserMessage) {
                                AppLogger.d(
                                    TAG,
                                    "Skip auto createNewChat on wake: current chat has no user messages"
                                )
                                return@launch
                            }
                        }

                        val group = wakePrefs.autoNewChatGroupFlow.first().trim().ifBlank {
                            WakeWordPreferences.DEFAULT_AUTO_NEW_CHAT_GROUP
                        }
                        val activePrompt =
                            ActivePromptManager.getInstance(applicationContext).getActivePrompt()
                        val activeCard =
                            when (activePrompt) {
                                is ActivePrompt.CharacterCard ->
                                    CharacterCardManager.getInstance(applicationContext)
                                        .getCharacterCard(activePrompt.id)
                                is ActivePrompt.CharacterGroup -> null
                            }
                        val folderId =
                            ChatHistoryManager.getInstance(applicationContext)
                                .resolveOrCreateLegacyFolderId(group, activeCard?.name)
                        boundCore.createNewChat(
                            folderId = folderId,
                            inheritGroupFromCurrent = false,
                            characterCardId = activeCard?.id,
                        )
                    }
                }
            }

            if (intent?.hasExtra(EXTRA_AUTO_EXIT_AFTER_MS) == true) {
                val timeoutMs = intent.getLongExtra(EXTRA_AUTO_EXIT_AFTER_MS, -1L)
                scheduleAutoExit(timeoutMs)
            } else {
                scheduleAutoExit(null)
            }

            val hasColorSchemeExtra = intent?.hasExtra("COLOR_SCHEME") == true
            if (hasColorSchemeExtra) {
                val serializableColorScheme =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent?.getParcelableExtra(
                            "COLOR_SCHEME",
                            SerializableColorScheme::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent?.getParcelableExtra<SerializableColorScheme>("COLOR_SCHEME")
                    }
                serializableColorScheme?.let {
                    colorScheme.value = it.toComposeColorScheme()
                    try {
                        prefs.edit().putString(PREF_KEY_COLOR_SCHEME, gson.toJson(it)).apply()
                    } catch (_: Exception) {
                    }
                }
            } else {
                val saved = prefs.getString(PREF_KEY_COLOR_SCHEME, null)
                if (!saved.isNullOrBlank()) {
                    try {
                        val restored = gson.fromJson(saved, SerializableColorScheme::class.java)
                        colorScheme.value = restored.toComposeColorScheme()
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to restore COLOR_SCHEME", e)
                    }
                }
            }

            val hasTypographyExtra = intent?.hasExtra("TYPOGRAPHY") == true
            if (hasTypographyExtra) {
                val serializableTypography =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent?.getParcelableExtra(
                            "TYPOGRAPHY",
                            SerializableTypography::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent?.getParcelableExtra<SerializableTypography>("TYPOGRAPHY")
                    }
                serializableTypography?.let {
                    typography.value = it.toComposeTypography()
                    try {
                        prefs.edit().putString(PREF_KEY_TYPOGRAPHY, gson.toJson(it)).apply()
                    } catch (_: Exception) {
                    }
                }
            } else {
                val saved = prefs.getString(PREF_KEY_TYPOGRAPHY, null)
                if (!saved.isNullOrBlank()) {
                    try {
                        val restored = gson.fromJson(saved, SerializableTypography::class.java)
                        typography.value = restored.toComposeTypography()
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to restore TYPOGRAPHY", e)
                    }
                }
            }
            val windowShown = windowManager.show()
            if (windowShown) {
                windowManager.setFloatingWindowPersistentHidden(false)
                windowManager.setFloatingWindowVisible(true)
            }
            sendLifecycleBroadcast(
                if (windowShown) ACTION_FLOATING_CHAT_WINDOW_SHOWN
                else ACTION_FLOATING_CHAT_WINDOW_SHOW_FAILED
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error in onStartCommand", e)
            sendLifecycleBroadcast(ACTION_FLOATING_CHAT_WINDOW_SHOW_FAILED)
        }
        return START_STICKY
    }

    private fun sendLifecycleBroadcast(action: String) {
        try {
            sendBroadcast(Intent(action).setPackage(packageName))
        } catch (_: Exception) {
        }
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        super.onTaskRemoved(rootIntent)
        AppLogger.d(TAG, "onTaskRemoved")
        val restartServiceIntent =
                Intent(applicationContext, this.javaClass).apply { setPackage(packageName) }
        startService(restartServiceIntent)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppLogger.d(TAG, "onLowMemory: 系统内存不足")
        saveState()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLogger.d(TAG, "onTrimMemory: level=$level")
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
        ) {
            saveState()
        }
    }

    private fun handleAttachmentRequest(request: String) {
        AppLogger.d(TAG, "Attachment request received: $request")
        serviceScope.launch {
            try {
                // 直接使用 boundCore 的 AttachmentDelegate 处理附件
                boundCore.handleAttachment(request)
                AppLogger.d(TAG, "附件已添加: $request")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error handling attachment request", e)
            }
        }
    }

    fun removeAttachment(filePath: String) {
        AppLogger.d(TAG, "移除附件: $filePath")
        // 直接使用 boundCore 的 AttachmentDelegate 移除附件
        boundCore.removeAttachment(filePath)
    }

    override fun onDestroy() {
        try {
            AIForegroundService.setWakeListeningSuspendedForFloatingFullscreen(
                applicationContext,
                false
            )
            scheduleAutoExit(null)
            releaseWakeLock()

            try {
                binder.clearCallbacks()
            } catch (_: Exception) {
            }


            try {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        try {
                            SpeechServiceFactory.getInstance(applicationContext).cancelRecognition()
                        } catch (_: Exception) {
                        }
                        try {
                            VoiceServiceFactory.getInstance(applicationContext).stop()
                        } catch (_: Exception) {
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
            
            serviceScope.cancel()
            saveState()
            super.onDestroy()
            AppLogger.d(TAG, "onDestroy")
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            windowManager.destroy()
            Thread.setDefaultUncaughtExceptionHandler(defaultExceptionHandler)
            prefs.edit().putInt("view_creation_retry", 0).apply()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error in onDestroy", e)
        }

        try {
            sendBroadcast(
                Intent(ACTION_FLOATING_CHAT_SERVICE_STOPPED)
                    .setPackage(packageName)
            )
        } catch (_: Exception) {
        }
        instance = null
    }

    override fun onClose() {
        AppLogger.d(TAG, "Close request from window manager")
        try {
            AIForegroundService.setWakeListeningSuspendedForFloatingFullscreen(
                applicationContext,
                false
            )
        } catch (_: Exception) {
        }
        try {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    try {
                        SpeechServiceFactory.getInstance(applicationContext).cancelRecognition()
                    } catch (_: Exception) {
                    }
                    VoiceServiceFactory.getInstance(applicationContext).stop()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                windowManager.prepareForExit()
            } else {
                Handler(Looper.getMainLooper()).post {
                    try {
                        windowManager.prepareForExit()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        binder.notifyClose()
        stopSelf()
    }

    override fun onSendMessage(message: String, promptType: PromptFunctionType) {
        AppLogger.d(TAG, "onSendMessage: $message, promptType: $promptType")
        
        // 直接使用 boundCore 发送消息，不再通过 SharedFlow
        val targetCore = boundCore
        serviceScope.launch {
            try {
                // Capture the selected runtime before suspending.
                targetCore.sendUserMessage(
                    promptFunctionType = promptType,
                    messageTextOverride = message,
                    turnOptions = com.ai.assistance.operit.data.model.ChatTurnOptions(useComposerReply = false),
                )
                
                AppLogger.d(TAG, "消息已通过 boundCore 发送")
            } catch (e: Exception) {
                AppLogger.e(TAG, "发送消息时出错", e)
            }
        }
    }

    override fun onCancelMessage() {
        AppLogger.d(TAG, "onCancelMessage")
        
        // 直接使用 boundCore 取消消息，不再通过 SharedFlow
        boundCore.cancelCurrentMessage()
    }

    override fun onAttachmentRequest(request: String) {
        handleAttachmentRequest(request)
    }

    override fun onRemoveAttachment(filePath: String) {
        removeAttachment(filePath)
    }

    override fun getMessages(): List<ChatMessage> = chatMessages.value

    override fun getAttachments(): List<AttachmentInfo> = attachments.value

    override fun getInputProcessingState(): State<InputProcessingState> = inputProcessingState

    override fun getColorScheme(): ColorScheme? = colorScheme.value

    override fun getTypography(): Typography? = typography.value

    override fun saveState() {
        windowState.saveState()
    }

    override fun getStatusIndicatorStyle(): StatusIndicatorStyle {
        val defaultStyleName = StatusIndicatorStyle.FULLSCREEN_RAINBOW.name
        val stored = prefs.getString(PREF_KEY_STATUS_INDICATOR_STYLE, defaultStyleName)
        return try {
            StatusIndicatorStyle.valueOf(stored ?: defaultStyleName)
        } catch (e: IllegalArgumentException) {
            AppLogger.e(TAG, "Invalid status indicator style in prefs: $stored, fallback to default", e)
            StatusIndicatorStyle.FULLSCREEN_RAINBOW
        }
    }

    fun setStatusIndicatorStyle(style: StatusIndicatorStyle) {
        prefs.edit().putString(PREF_KEY_STATUS_INDICATOR_STYLE, style.name).apply()
        AppLogger.d(TAG, "Status indicator style set to: $style")
    }

    /**
     * 获取悬浮窗的ComposeView实例，用于申请输入法焦点
     * @return ComposeView? 当前悬浮窗的ComposeView实例，如果未初始化则返回null
     */
    fun getComposeView(): View? {
        return if (::windowManager.isInitialized) {
            windowManager.getComposeView()
        } else {
            null
        }
    }

    fun switchToMode(mode: FloatingMode) {
        windowManager.switchMode(mode)
        AppLogger.d(TAG, "Switching to mode: $mode")
    }

    fun minimizeToPet() {
        windowManager.prepareForExit()
        windowManager.minimizeToPet()
    }

    suspend fun setFloatingWindowVisible(visible: Boolean) {
        if (::windowManager.isInitialized) {
            withContext(Dispatchers.Main) {
                windowManager.setFloatingWindowVisible(visible)
                AppLogger.d(TAG, "Floating window visible set to: $visible")
            }
        } else {
            AppLogger.w(TAG, "WindowManager not initialized, cannot set floating window visibility.")
        }
    }

    suspend fun setFloatingWindowPersistentHidden(hidden: Boolean) {
        if (::windowManager.isInitialized) {
            withContext(Dispatchers.Main) {
                windowManager.setFloatingWindowPersistentHidden(hidden)
                AppLogger.d(TAG, "Floating window persistent hidden set to: $hidden")
            }
        } else {
            AppLogger.w(TAG, "WindowManager not initialized, cannot set floating window persistent hidden.")
        }
    }

    suspend fun setStatusIndicatorVisible(visible: Boolean) {
        if (::windowManager.isInitialized) {
            withContext(Dispatchers.Main) {
                windowManager.setStatusIndicatorVisible(visible)
                AppLogger.d(TAG, "Status indicator visible set to: $visible")
            }
        } else {
            AppLogger.w(TAG, "WindowManager not initialized, cannot set status indicator visibility.")
        }
    }

    suspend fun setStatusIndicatorPersistentVisible(visible: Boolean) {
        if (::windowManager.isInitialized) {
            withContext(Dispatchers.Main) {
                windowManager.setStatusIndicatorPersistentVisible(visible)
                AppLogger.d(TAG, "Status indicator persistent visible set to: $visible")
            }
        } else {
            AppLogger.w(TAG, "WindowManager not initialized, cannot set persistent status indicator visibility.")
        }
    }

    /**
     * 获取 ChatServiceCore 实例
     * @return ChatServiceCore 聊天服务核心实例
     */
    fun getChatCore(): ChatServiceCore = boundCore

}
