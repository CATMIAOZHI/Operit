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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredHeightIn
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
import com.ai.assistance.operit.util.disableMoveAnimation
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
    private var bubbleView: ComposeView? = null
    private var bubbleHeight = 0
    private var bubbleWidth = 0
    private var bubblePosition = Offset.Zero
    private var dragBubbleOffset: Offset? = null
    private var x by mutableFloatStateOf(0f)
    private var y by mutableFloatStateOf(0.55f)
    private var dragging by mutableStateOf(false)
    private var viewport by mutableStateOf(0f to 0f)
    private var rowWidth = 0
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
        rowWidth = rowHeight
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
            combine(preferences.settings, model.appVisible, model.visibleTasks, FloatingPetEntry.mode) { settings, _, _, _ -> settings }
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
        if (intent?.action == ACTION_HIDE) {
            preferences.update { it.copy(overlay = false) }
            if (FloatingPetEntry.mode.value == FloatingPetEntryMode.PET) preferences.setUsePetEntry(false)
        }
        reconcile()
        return START_NOT_STICKY
    }

    private fun reconcile() {
        val settings = preferences.settings.value
        val entry = FloatingPetEntry.mode.value
        if ((!settings.overlay && entry != FloatingPetEntryMode.PET) || !Settings.canDrawOverlays(this)) {
            removeWindow()
            stopSelf()
            return
        }
        if (!screenOn || model.appVisible.value || !settings.isReady || entry == FloatingPetEntryMode.LEGACY_BALL || entry == FloatingPetEntryMode.HIDDEN) {
            removeWindow()
            return
        }
        updatePlacement()
        if (view != null) {
            reconcileBubble()
            return
        }
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val currentSettings by preferences.settings.collectAsState()
                val density = resources.displayMetrics.density
                val contentWidth = minOf(viewport.first, viewport.second, currentSettings.sizeDp * density)
                MaterialTheme(colorScheme = PetTheme.colors, typography = PetTheme.typography) {
                    PetCompanion(
                        currentSettings.copy(showBubble = false),
                        onToggleBubble = { preferences.update { it.copy(showBubble = !it.showBubble) } },
                        dragging = dragging,
                        onDragStart = ::startDrag,
                        onDrag = ::movePet,
                        onDragEnd = ::savePosition,
                        // The pet owns a fixed surface, independent of bubble visibility.
                        modifier = Modifier.requiredWidth((contentWidth / density).dp)
                            .requiredHeight((contentWidth / density).dp)
                            .onSizeChanged {
                                if (rowWidth != it.width || rowHeight != it.height) {
                                    rowWidth = it.width
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
            reconcileBubble()
        } catch (error: RuntimeException) {
            composeView.disposeComposition()
            AppLogger.e("PetCompanion", "Unable to attach overlay", error)
            preferences.update { it.copy(overlay = false) }
            if (FloatingPetEntry.mode.value == FloatingPetEntryMode.PET) preferences.setUsePetEntry(false)
            android.widget.Toast.makeText(this, R.string.pet_overlay_failed, android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun startDrag() {
        dragBubbleOffset = bubbleView?.let { bubblePosition - Offset(placement.left, placement.top) }
        dragging = true
        updatePlacement()
    }

    private fun reconcileBubble() {
        if (!preferences.settings.value.showBubble) {
            removeBubble()
            return
        }
        if (bubbleView != null || view == null) return
        val bubble = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val settings by preferences.settings.collectAsState()
                val density = resources.displayMetrics.density
                val size = minOf(viewport.first, viewport.second, settings.sizeDp * density)
                val width = minOf(PET_BUBBLE_WIDTH_DP * density,
                    (viewport.first - if (settings.edge.vertical) 0f else size).coerceAtLeast(0f))
                val height = (viewport.second - if (settings.edge.vertical) size else 0f).coerceAtLeast(0f)
                MaterialTheme(colorScheme = PetTheme.colors, typography = PetTheme.typography) {
                    PetCompanion(
                        settings.copy(showBubble = true),
                        bubbleOnly = true,
                        dragging = dragging,
                        onToggleBubble = {},
                        onDragStart = ::startDrag,
                        onDrag = ::movePet,
                        onDragEnd = ::savePosition,
                        modifier = Modifier.requiredWidth((width / density).dp)
                            .requiredHeightIn(min = 0.dp, max = (height / density).dp)
                            .onSizeChanged {
                                bubbleWidth = it.width
                                bubbleHeight = it.height
                                updateBubblePlacement()
                            },
                    )
                }
            }
        }
        try {
            // Keep it invisible until its first measurement supplies the anchored position.
            windows.addView(bubble, bubbleLayoutParams())
            bubbleView = bubble
            updateBubblePlacement()
        } catch (error: RuntimeException) {
            bubble.disposeComposition()
            AppLogger.e("PetCompanion", "Unable to attach task bubble", error)
            android.widget.Toast.makeText(this, R.string.pet_overlay_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun bubbleLayoutParams(): WindowManager.LayoutParams {
        val settings = preferences.settings.value
        val width = minOf(PET_BUBBLE_WIDTH_DP * resources.displayMetrics.density,
            (viewport.first - if (settings.edge.vertical) 0f else placement.width).coerceAtLeast(0f))
        val frozen = dragBubbleOffset
        bubblePosition = if (dragging && frozen != null) {
            Offset(placement.left, placement.top) + frozen
        } else {
            Offset(
                when (settings.edge) {
                    PetEdge.LEFT -> placement.left + placement.width
                    PetEdge.RIGHT -> placement.left - width
                    else -> x * (viewport.first - width).coerceAtLeast(0f)
                },
                when (settings.edge) {
                    PetEdge.TOP -> placement.top + rowHeight
                    PetEdge.BOTTOM -> placement.top - bubbleHeight
                    else -> y * (viewport.second - bubbleHeight).coerceAtLeast(0f)
                },
            )
        }
        return WindowManager.LayoutParams(
            width.roundToInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (dragging) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            disableMoveAnimation()
            gravity = Gravity.TOP or Gravity.LEFT
            x = bubblePosition.x.roundToInt()
            y = bubblePosition.y.roundToInt()
            alpha = if (bubbleHeight > 0 && bubbleWidth > 0) 1f else 0f
        }
    }

    private fun updateBubblePlacement() {
        val bubble = bubbleView ?: return
        val next = bubbleLayoutParams()
        val previous = bubble.layoutParams as WindowManager.LayoutParams
        if (previous.x != next.x || previous.y != next.y || previous.width != next.width ||
            previous.flags != next.flags || previous.alpha != next.alpha
        ) {
            try {
                windows.updateViewLayout(bubble, next)
            } catch (error: IllegalArgumentException) {
                AppLogger.w("PetCompanion", "Task bubble detached", error)
                removeBubble()
            }
        }
    }

    private fun removeBubble() {
        val bubble = bubbleView ?: return
        bubbleView = null
        bubbleHeight = 0
        bubbleWidth = 0
        bubble.disposeComposition()
        try {
            windows.removeViewImmediate(bubble)
        } catch (error: IllegalArgumentException) {
            AppLogger.w("PetCompanion", "Task bubble already detached", error)
        }
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
        dragBubbleOffset = null
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
        val size = minOf(petSize, viewport.first, viewport.second)
        rowWidth = size.roundToInt()
        rowHeight = rowWidth
        placement = placePet(viewport.first, viewport.second, size, size, x, y)
        view?.let {
            try {
                val next = layoutParams()
                val previous = it.layoutParams as WindowManager.LayoutParams
                if (previous.width != next.width || previous.height != next.height ||
                    previous.x != next.x || previous.y != next.y || previous.flags != next.flags
                ) {
                    windows.updateViewLayout(it, next)
                }
            } catch (error: IllegalArgumentException) {
                AppLogger.w("PetCompanion", "Overlay window detached", error)
                removeWindow()
                stopSelf()
            }
        }
        updateBubblePlacement()
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        placement.width.roundToInt(), rowHeight,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            // Keep the group rigid while dragging; snap it back into bounds on release.
            (if (dragging) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS else 0),
        PixelFormat.TRANSLUCENT,
    ).apply {
        disableMoveAnimation()
        gravity = Gravity.TOP or Gravity.LEFT
        x = placement.left.roundToInt()
        y = placement.top.roundToInt()
    }

    private fun removeWindow() {
        val existing = view ?: return
        view = null
        // Disposing pointerInput does not guarantee onDragCancel. A hidden window must not
        // retain a free-floating drag position on its return.
        if (dragging) {
            dragging = false
            dragBubbleOffset = null
            val settings = preferences.settings.value
            val docked = dockPet(settings.edge, settings.x, settings.y)
            x = docked.x
            y = docked.y
        }
        removeBubble()
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
        if (dragging) savePosition()
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
