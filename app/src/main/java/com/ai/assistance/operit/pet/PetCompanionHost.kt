package com.ai.assistance.operit.pet

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ai.assistance.operit.R
import kotlin.math.roundToInt

@Composable
fun PetCompanionHost() {
    val context = LocalContext.current
    val preferences = remember { PetPreferences.get(context) }
    val model = remember { PetTasks.get(context) }
    val settings by preferences.settings.collectAsState()
    val tasks by model.visibleTasks.collectAsState()
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    SideEffect { PetTheme.colors = colors; PetTheme.typography = typography }
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        model.appVisible.value = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.appVisible.value = true
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                Lifecycle.Event.ON_STOP -> model.appVisible.value = false
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            model.appVisible.value = false
        }
    }
    // Start while foreground; starting a foreground service for the first time in onStop is
    // restricted by Android. The service hides its window while this host is visible.
    LaunchedEffect(settings.overlay, settings.isReady, resumed) {
        if (resumed && settings.overlay && settings.isReady) {
            if (!Settings.canDrawOverlays(context)) {
                preferences.update { it.copy(overlay = false) }
            } else {
                try {
                    ContextCompat.startForegroundService(context, Intent(context, PetCompanionService::class.java))
                } catch (error: RuntimeException) {
                    com.ai.assistance.operit.util.AppLogger.e("PetCompanion", "Unable to start overlay", error)
                    preferences.update { it.copy(overlay = false) }
                    Toast.makeText(context, R.string.pet_overlay_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    if (!settings.inApp || !resumed || !settings.isReady) return
    BoxWithConstraints(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding()
    ) {
        val density = LocalDensity.current
        val viewport = with(density) { maxWidth.toPx() }
        val petSize = with(density) { settings.sizeDp.dp.toPx() }
        val viewportHeight = with(density) { maxHeight.toPx() }
        val travelY = (viewportHeight - petSize).coerceAtLeast(1f)
        var x by remember { mutableFloatStateOf(settings.x) }
        var y by remember { mutableFloatStateOf(settings.y) }
        var dragging by remember { mutableStateOf(false) }
        val docked = dockPet(settings.edge, settings.x, settings.y)
        LaunchedEffect(settings.x, settings.y, settings.edge) { x = docked.x; y = docked.y }
        val anchorX = if (dragging) x else docked.x
        val anchorY = if (dragging) y else docked.y
        val hasBubble = tasks.isNotEmpty() && settings.showBubble && !dragging
        val width = petWidth(viewport, petSize, with(density) { PET_BUBBLE_WIDTH_DP.dp.toPx() }, settings.edge, hasBubble)
        Layout(modifier = Modifier.fillMaxSize(), content = {
        PetCompanion(
            settings = settings,
            onToggleBubble = { preferences.update { it.copy(showBubble = !it.showBubble) } },
            anchorX = anchorX, anchorY = anchorY, dragging = dragging,
            onDragStart = { x = docked.x; y = docked.y; dragging = true },
            onDrag = { amount ->
                x = (x + amount.x / (viewport - petSize).coerceAtLeast(1f)).coerceIn(0f, 1f)
                y = (y + amount.y / travelY).coerceIn(0f, 1f)
            },
            onDragEnd = {
                val snapped = snapPet(viewport, viewportHeight, petSize, x, y)
                preferences.update { it.copy(x = snapped.x, y = snapped.y, edge = snapped.edge) }
                dragging = false
            },
            modifier = Modifier.width(with(density) { width.toDp() }).heightIn(max = maxHeight),
        )
        }) { measurables, constraints ->
            val child = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val placement = placePet(
                constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(),
                child.width.toFloat(), child.height.toFloat(), anchorX, anchorY,
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                child.place(placement.left.roundToInt(), placement.top.roundToInt())
            }
        }
    }
}
