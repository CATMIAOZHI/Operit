package com.ai.assistance.operit.pet

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowInsets
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.application.ForegroundServiceCompat
import com.ai.assistance.operit.services.ServiceLifecycleOwner
import com.ai.assistance.operit.ui.main.MainActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import com.ai.assistance.operit.ui.theme.rainyBaseColorScheme
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlin.math.roundToInt

internal object PetTheme {
    var colors by mutableStateOf(rainyBaseColorScheme(false))
    var typography by mutableStateOf(Typography())
}

/** A content-sized, non-focusable window; no chat UI bridge, microphone or wake lock. */
class PetCompanionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var owner: ServiceLifecycleOwner
    private lateinit var preferences: PetPreferences
    private lateinit var model: PetTasks
    private lateinit var windows: WindowManager
    private var view: ComposeView? = null
    private var x by mutableFloatStateOf(0f)
    private var y by mutableFloatStateOf(0.55f)
    private var dragging by mutableStateOf(false)
    private var viewport by mutableStateOf(0f to 0f)
    private var rowHeight = 0
    private var savedPosition: PetAnchor? = null
    private var placement by mutableStateOf(PetPlacement(0f, 0f, 80f))
    private var screenOn = true
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
            reconcile()
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = PetPreferences.get(this)
        model = PetTasks.get(this)
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        rowHeight = (preferences.settings.value.sizeDp * resources.displayMetrics.density).roundToInt()
        owner = ServiceLifecycleOwner()
        owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.pet_title), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hide = PendingIntent.getService(
            this, 1, Intent(this, PetCompanionService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_simple_foreground)
            .setContentTitle(getString(R.string.pet_title))
            .setContentText(getString(R.string.pet_notification))
            .setContentIntent(open)
            .addAction(0, getString(R.string.pet_disable_overlay), hide)
            .setOngoing(true).setSilent(true).build()
        ForegroundServiceCompat.startForeground(
            this, 1027, notification,
            ForegroundServiceCompat.buildTypes(dataSync = false, specialUse = true),
        )
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            combine(preferences.settings, model.appVisible, model.visibleTasks) { settings, _, _ -> settings }
                .collect { settings ->
                    val position = dockPet(settings.edge, settings.x, settings.y)
                    if (position != savedPosition) {
                        savedPosition = position
                        x = position.x
                        y = position.y
                    }
                    reconcile()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) preferences.update { it.copy(overlay = false) }
        reconcile()
        return START_NOT_STICKY
    }

    private fun reconcile() {
        val settings = preferences.settings.value
        if (!settings.overlay || !Settings.canDrawOverlays(this)) {
            removeWindow()
            stopSelf()
            return
        }
        if (!screenOn || model.appVisible.value || !settings.isReady) {
            removeWindow()
            return
        }
        updatePlacement()
        if (view != null) return
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val currentSettings by preferences.settings.collectAsState()
                MaterialTheme(colorScheme = PetTheme.colors, typography = PetTheme.typography) {
                    PetCompanion(
                        currentSettings,
                        onToggleBubble = { preferences.update { it.copy(showBubble = !it.showBubble) } },
                        anchorX = this@PetCompanionService.x,
                        anchorY = this@PetCompanionService.y,
                        dragging = dragging,
                        onDragStart = ::startDrag,
                        onDrag = ::movePet,
                        onDragEnd = ::savePosition,
                        modifier = Modifier.width((placement.width / resources.displayMetrics.density).dp)
                            .heightIn(max = (viewport.second / resources.displayMetrics.density).dp)
                            .onSizeChanged {
                                if (rowHeight != it.height) {
                                    rowHeight = it.height
                                    updatePlacement()
                                }
                            },
                    )
                }
            }
        }
        try {
            windows.addView(composeView, layoutParams())
            view = composeView
            owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        } catch (error: RuntimeException) {
            composeView.disposeComposition()
            AppLogger.e("PetCompanion", "Unable to attach overlay", error)
            preferences.update { it.copy(overlay = false) }
            android.widget.Toast.makeText(this, R.string.pet_overlay_failed, android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun startDrag() {
        dragging = true
        updatePlacement()
    }

    private fun movePet(amount: Offset) {
        val density = resources.displayMetrics.density
        val metrics = availableBounds()
        val petSize = preferences.settings.value.sizeDp * density
        x = (x + amount.x / (metrics.first - petSize).coerceAtLeast(1f)).coerceIn(0f, 1f)
        y = (y + amount.y / (metrics.second - petSize).coerceAtLeast(1f)).coerceIn(0f, 1f)
        updatePlacement()
    }

    private fun savePosition() {
        if (!dragging) return
        val bounds = availableBounds()
        val snapped = snapPet(bounds.first, bounds.second, preferences.settings.value.sizeDp * resources.displayMetrics.density, x, y)
        x = snapped.x
        y = snapped.y
        preferences.update { it.copy(x = snapped.x, y = snapped.y, edge = snapped.edge) }
        dragging = false
        updatePlacement()
    }

    private fun availableBounds(): Pair<Float, Float> {
        // Non-layout-in-screen windows are positioned in the usable display frame.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windows.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return (metrics.bounds.width() - insets.left - insets.right).toFloat() to
                (metrics.bounds.height() - insets.top - insets.bottom).toFloat()
        }
        val metrics = resources.displayMetrics
        return metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
    }

    private fun updatePlacement() {
        val density = resources.displayMetrics.density
        val settings = preferences.settings.value
        viewport = availableBounds()
        val petSize = settings.sizeDp * density
        val hasBubble = model.visibleTasks.value.isNotEmpty() && settings.showBubble && !dragging
        placement = placePet(
            viewport.first, viewport.second,
            petWidth(viewport.first, petSize, PET_BUBBLE_WIDTH_DP * density, settings.edge, hasBubble),
            if (hasBubble) rowHeight.toFloat() else petSize, x, y,
        )
        view?.let {
            try {
                windows.updateViewLayout(it, layoutParams())
            } catch (error: IllegalArgumentException) {
                AppLogger.w("PetCompanion", "Overlay window detached", error)
                removeWindow()
                stopSelf()
            }
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        placement.width.roundToInt(), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = placement.left.roundToInt()
        y = placement.top.roundToInt()
    }

    private fun removeWindow() {
        val existing = view ?: return
        view = null
        // Disposing pointerInput does not guarantee onDragCancel. A hidden window must not
        // retain a free-floating drag position or keep task bubbles suppressed on its return.
        if (dragging) {
            dragging = false
            val settings = preferences.settings.value
            val docked = dockPet(settings.edge, settings.x, settings.y)
            x = docked.x
            y = docked.y
        }
        owner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        existing.disposeComposition()
        try {
            windows.removeViewImmediate(existing)
        } catch (error: IllegalArgumentException) {
            AppLogger.w("PetCompanion", "Overlay already detached", error)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePlacement()
    }

    override fun onDestroy() {
        removeWindow()
        unregisterReceiver(screenReceiver)
        scope.cancel()
        owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        owner.viewModelStore.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "pet_companion"
        private const val ACTION_HIDE = "pet_hide_overlay"
    }
}
