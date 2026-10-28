package com.ai.assistance.operit.ui.features.orbit

import android.os.Build
import android.view.View
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Only layer properties read progress, so a swipe does not recompose a dialog or its list. */
@Stable
internal class OrbitBackMotion(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
        private set
    private var animation: Job? = null
    private var committing = false
    private var allowed = false
    private var started = false

    fun reset() {
        animation?.cancel()
        animation = null
        progress = 0f
        committing = false
        started = false
    }

    fun start(canDismiss: Boolean) {
        if (committing) return
        animation?.cancel()
        started = true
        allowed = canDismiss
        if (!allowed) progress = 0f
    }

    fun move(value: Float, canDismiss: Boolean) {
        if (committing) return
        allowed = allowed && canDismiss
        progress = if (allowed) value.coerceIn(0f, 1f) else 0f
    }

    fun cancel() {
        if (committing) return
        started = false
        animation?.cancel()
        animation = scope.launch { restore() }
    }

    fun finish(canDismiss: () -> Boolean, onBack: suspend () -> Unit) {
        if (committing) return
        animation?.cancel()
        val permitted = !started || allowed
        started = false
        committing = true
        animation = scope.launch {
            try {
                if (permitted && canDismiss()) {
                    animate(progress, 1f, animationSpec = tween(140, easing = OrbitMotion.Easing)) { value, _ ->
                        progress = value
                    }
                    // Saving can begin while the finger or the completion animation is moving.
                    if (canDismiss()) onBack()
                }
                // A callback may deliberately keep its surface open (for example an unsaved edit).
                restore()
            } finally {
                progress = 0f
                committing = false
            }
        }
    }

    private suspend fun restore() {
        animate(progress, 0f, animationSpec = tween(180, easing = OrbitMotion.Easing)) { value, _ ->
            progress = value
        }
    }
}

@Composable
internal fun rememberOrbitBackMotion(): OrbitBackMotion {
    val scope = rememberCoroutineScope()
    return remember(scope) { OrbitBackMotion(scope) }
}

/** Register in the current window. A blocked dismissal still consumes Back, never the page below. */
@Composable
internal fun OrbitBackHandler(
    motion: OrbitBackMotion,
    enabled: Boolean = true,
    canDismiss: Boolean = true,
    onBack: suspend () -> Unit,
) {
    val owner = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestAllowed by rememberUpdatedState(canDismiss)
    val latestBack by rememberUpdatedState(onBack)
    val callback = remember(motion) {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) = motion.start(latestAllowed)
            override fun handleOnBackProgressed(backEvent: BackEventCompat) = motion.move(backEvent.progress, latestAllowed)
            override fun handleOnBackCancelled() = motion.cancel()
            override fun handleOnBackPressed() = motion.finish({ latestAllowed }, { latestBack() })
        }
    }
    SideEffect { callback.isEnabled = enabled }
    DisposableEffect(owner, lifecycleOwner, callback) {
        owner.onBackPressedDispatcher.addCallback(lifecycleOwner, callback)
        onDispose { callback.remove() }
    }
}

/** Popups have a separate window but no ComponentDialog dispatcher. */
@Composable
internal fun OrbitPopupBack(motion: OrbitBackMotion, onDismiss: () -> Unit, animateWindow: Boolean = false) {
    val view = LocalView.current
    val latestDismiss by rememberUpdatedState(onDismiss)
    if (animateWindow) {
        // Material's DropdownMenu modifier only wraps its inner Column. Transform this popup's
        // Compose view so the surface, outline and shadow follow the rows as one piece.
        LaunchedEffect(view, motion) {
            snapshotFlow { motion.progress }.collect { p ->
                view.scaleX = 1f - 0.06f * p
                view.scaleY = view.scaleX
                view.alpha = 1f - 0.35f * p
            }
        }
        DisposableEffect(view) {
            onDispose {
                view.scaleX = 1f
                view.scaleY = 1f
                view.alpha = 1f
            }
        }
    }
    DisposableEffect(view, motion) {
        if (Build.VERSION.SDK_INT >= 34) {
            var dispatcher: OnBackInvokedDispatcher? = null
            val callback = object : OnBackAnimationCallback {
                override fun onBackStarted(backEvent: BackEvent) = motion.start(true)
                override fun onBackProgressed(backEvent: BackEvent) = motion.move(backEvent.progress, true)
                override fun onBackCancelled() = motion.cancel()
                override fun onBackInvoked() = motion.finish({ true }, { latestDismiss() })
            }
            fun attach() {
                if (dispatcher != null) return
                dispatcher = view.findOnBackInvokedDispatcher()
                dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            }
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = attach()
                override fun onViewDetachedFromWindow(v: View) {
                    dispatcher?.unregisterOnBackInvokedCallback(callback)
                    dispatcher = null
                }
            }
            view.addOnAttachStateChangeListener(listener)
            if (view.isAttachedToWindow) attach()
            onDispose {
                view.removeOnAttachStateChangeListener(listener)
                dispatcher?.unregisterOnBackInvokedCallback(callback)
            }
        } else {
            onDispose { }
        }
    }
}

internal fun Modifier.orbitDismissLayer(motion: OrbitBackMotion, fromBottom: Boolean = false): Modifier =
    graphicsLayer {
        val p = motion.progress
        scaleX = 1f - 0.06f * p
        scaleY = scaleX
        if (fromBottom) {
            transformOrigin = TransformOrigin(0.5f, 1f)
            translationY = size.height * 0.12f * p
        } else {
            alpha = 1f - 0.35f * p
        }
    }

/**
 * Page hosts provide an explicit activity dispatcher. Resolve the dialog's own dispatcher after
 * entering its window. The IME retains the platform's higher back priority.
 */
@Composable
internal fun OrbitModalWindow(
    onDismissRequest: () -> Unit,
    dismissEnabled: Boolean = true,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismissRequest() },
        properties = properties,
    ) {
        OrbitWindowBackScope {
            val motion = rememberOrbitBackMotion()
            OrbitBackHandler(motion, canDismiss = dismissEnabled, onBack = { onDismissRequest() })
            Box(Modifier.orbitDismissLayer(motion)) { content() }
        }
    }
}

/** Orbit's sheets share one gesture and keep Material's drag, insets and hide animation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = rememberOrbitBackMotion()
    val latestAllowed by rememberUpdatedState(dismissEnabled)
    ModalBottomSheet(
        onDismissRequest = { if (latestAllowed) onDismissRequest() },
        modifier = modifier.orbitDismissLayer(motion, fromBottom = true),
        sheetState = sheetState,
        containerColor = containerColor,
        sheetGesturesEnabled = dismissEnabled,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = false,
            shouldDismissOnClickOutside = dismissEnabled,
        ),
    ) {
        OrbitWindowBackScope {
            OrbitBackHandler(motion, canDismiss = dismissEnabled) {
                sheetState.hide()
                if (!sheetState.isVisible && latestAllowed) onDismissRequest()
                else if (!sheetState.isVisible) sheetState.show()
            }
            content()
        }
    }
}

@Composable
private fun OrbitWindowBackScope(content: @Composable () -> Unit) {
    val view = LocalView.current
    val owner = checkNotNull(view.findViewTreeOnBackPressedDispatcherOwner())
    CompositionLocalProvider(
        LocalOnBackPressedDispatcherOwner provides owner,
        LocalOrbitHostedBack provides false,
        content = content,
    )
}
