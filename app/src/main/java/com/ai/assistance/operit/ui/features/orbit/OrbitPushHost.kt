package com.ai.assistance.operit.ui.features.orbit

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** True for a page inside an [OrbitPushHost]: the host does its plain back, with the iOS swipe. */
internal val LocalOrbitHostedBack = staticCompositionLocalOf { false }

/**
 * False while a page sits hidden under the page on top: a page that refreshes on its own (polls,
 * a ticking clock) waits until it is on top again, keeping what it last showed for a back swipe.
 */
internal val LocalOrbitPageActive = compositionLocalOf { true }

/**
 * A page's back. Inside an [OrbitPushHost] a plain back is left to the host, which slides the
 * page away with the predictive back gesture; [intercept] keeps it here instead, for a page that
 * must handle back itself first (an unsaved edit, a delete in progress, its own inner view).
 */
@Composable
internal fun OrbitPageBack(intercept: Boolean = false, onBack: () -> Unit) {
    BackHandler(enabled = intercept || !LocalOrbitHostedBack.current, onBack = onBack)
}

/** The host's motion, shared so what is under the host can follow it. */
@Stable
internal class OrbitPushState {
    /** 0: the moving page is in place; 1: it is off to the right. */
    internal var offset by mutableFloatStateOf(0f)
    internal var moving by mutableIntStateOf(MOVE_NONE)
    /** Pages in the stack once the current move is over. */
    internal var depth by mutableIntStateOf(0)

    /** How much the host's pages cover what is under the host, 0 to 1. Read it in a layer block. */
    fun baseCover(): Float = when {
        depth >= 2 -> 1f
        depth == 1 -> if (moving == MOVE_PUSH || moving == MOVE_GESTURE) 1f - offset else 1f
        else -> if (moving == MOVE_POP) 1f - offset else 0f
    }

    internal companion object {
        const val MOVE_NONE = 0
        const val MOVE_PUSH = 1 // the top page slides in
        const val MOVE_POP = 2 // the page that left slides out
        const val MOVE_GESTURE = 3 // the top page follows the back swipe
    }
}

/**
 * iOS-like page stack: a pushed page slides in from the right over the page below, which moves a
 * little to the left; back slides it away, following the finger with the predictive back gesture
 * and settling back if the swipe is cancelled. [entries] is the stack, owned by the caller, and
 * [onPop] removes its top. The page below the top stays composed, so a swipe shows it at once and
 * going back never reloads it. Only the top page receives back.
 *
 * [base] is the page under the first entry when the host owns it; otherwise what is under the host
 * can follow [state] for the same parallax.
 */
@Composable
internal fun <T : Any> OrbitPushHost(
    entries: List<T>,
    onPop: () -> Unit,
    modifier: Modifier = Modifier,
    state: OrbitPushState = remember { OrbitPushState() },
    base: (@Composable () -> Unit)? = null,
    page: @Composable (T) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    // The stack as drawn, and a page still sliding out after a back (with its depth).
    var drawn by remember { mutableStateOf(entries) }
    var leaving by remember { mutableStateOf<Pair<Int, T>?>(null) }
    var gestureDone by remember { mutableStateOf(false) }
    val currentEntries by rememberUpdatedState(entries)
    val currentOnPop by rememberUpdatedState(onPop)
    remember { state.depth = entries.size; Unit }

    LaunchedEffect(entries) {
        val before = drawn
        if (entries == before) return@LaunchedEffect
        val spec = tween<Float>(OrbitMotion.PUSH_MS, easing = OrbitMotion.Easing)
        // Each group of writes below happens before the next frame, so nothing flashes between them.
        when {
            gestureDone -> {
                // The swipe already took the page off screen: drop it.
                gestureDone = false
                drawn = entries
                state.depth = entries.size
                state.moving = OrbitPushState.MOVE_NONE
                state.offset = 0f
            }
            entries.size > before.size && entries.subList(0, before.size) == before -> {
                drawn = entries
                state.depth = entries.size
                state.moving = OrbitPushState.MOVE_PUSH
                state.offset = 1f
                try {
                    animate(1f, 0f, animationSpec = spec) { v, _ -> state.offset = v }
                } finally {
                    state.offset = 0f
                    state.moving = OrbitPushState.MOVE_NONE
                }
            }
            entries.size < before.size && before.subList(0, entries.size) == entries -> {
                leaving = before.lastIndex to before.last()
                drawn = entries
                state.depth = entries.size
                state.moving = OrbitPushState.MOVE_POP
                state.offset = 0f
                try {
                    animate(0f, 1f, animationSpec = spec) { v, _ -> state.offset = v }
                } finally {
                    leaving = null
                    state.moving = OrbitPushState.MOVE_NONE
                    state.offset = 0f
                }
            }
            else -> {
                drawn = entries
                state.depth = entries.size
                state.moving = OrbitPushState.MOVE_NONE
                state.offset = 0f
            }
        }
        forget(holder, before, entries)
    }

    // Registered before the pages, so a page that intercepts back still comes first.
    PredictiveBackHandler(enabled = entries.isNotEmpty() || state.moving != OrbitPushState.MOVE_NONE) { progress ->
        // A back during a slide is swallowed, so it cannot reach what is under the host.
        if (state.moving != OrbitPushState.MOVE_NONE || drawn != currentEntries || currentEntries.isEmpty()) {
            progress.collect { }
            return@PredictiveBackHandler
        }
        val started = currentEntries
        try {
            state.moving = OrbitPushState.MOVE_GESTURE
            progress.collect { event -> state.offset = event.progress }
            val rest = (OrbitMotion.PUSH_MS * (1f - state.offset)).toInt().coerceAtLeast(120)
            animate(state.offset, 1f, animationSpec = tween(rest, easing = OrbitMotion.Easing)) { v, _ -> state.offset = v }
            if (currentEntries == started) {
                gestureDone = true
                currentOnPop()
            } else {
                state.moving = OrbitPushState.MOVE_NONE
                state.offset = 0f
            }
        } catch (e: CancellationException) {
            // The swipe was let go before the edge: the page settles back in place.
            scope.launch {
                try {
                    animate(state.offset, 0f, animationSpec = tween(220, easing = OrbitMotion.Easing)) { v, _ ->
                        state.offset = v
                    }
                } finally {
                    state.offset = 0f
                    state.moving = OrbitPushState.MOVE_NONE
                }
            }
            throw e
        }
    }

    val realOwner = LocalOnBackPressedDispatcherOwner.current
    val inert = rememberInertBackOwner()
    val hosted = LocalOrbitHostedBack.current
    val out = leaving
    val top = drawn.lastIndex
    // The page that will be on top once the slide is over; the base when there is none.
    val activeLevel = top
    Box(modifier.fillMaxSize()) {
        // Bottom to top: the page under the moving one, then the moving one. -1 is the base.
        val under = if (out != null) top else top - 1
        val layers = listOf(under to false, (if (out != null) out.first else top) to true)
        val underShown = if (under >= 0) drawn.getOrNull(under) != null else under == -1 && base != null
        for ((level, isMoving) in layers) {
            val entry: T? = when {
                isMoving && out != null -> out.second
                level >= 0 -> drawn.getOrNull(level)
                else -> null
            }
            if (level >= 0 && entry == null) continue
            if (level < 0 && (base == null || level < -1)) continue
            val stateKey = if (level < 0) "base" else "$level"
            val onTop = isMoving && out == null
            // A page left behind by a back stays fully drawn while the other slides off it.
            val revealed = !isMoving && out != null
            // The dim over the page below is its own layer, so a swipe only redraws that layer.
            if (isMoving && underShown) {
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        val p = state.offset
                        val dim = 0.1f * (1f - p)
                        if (dim > 0f && (out != null || p > 0f)) drawRect(Color.Black.copy(alpha = dim))
                    }
                )
            }
            key(stateKey) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            val p = state.offset
                            if (isMoving) {
                                translationX = size.width * p
                            } else {
                                // The page below waits a little to the left, like iOS.
                                translationX = -size.width * 0.3f * (1f - p)
                                alpha = if (revealed || p > 0f) 1f else 0f
                            }
                        }
                        // The moving page takes every touch on it, so blank parts of it never let a
                        // tap through to the page below; the hidden page below is not read out.
                        .then(
                            if (isMoving) Modifier.pointerInput(Unit) {
                                awaitPointerEventScope { while (true) awaitPointerEvent() }
                            }
                            else if (!revealed) Modifier.clearAndSetSemantics { }
                            else Modifier
                        )
                ) {
                    CompositionLocalProvider(
                        LocalOnBackPressedDispatcherOwner provides (if (onTop) realOwner ?: inert else inert),
                        LocalOrbitHostedBack provides (if (level < 0) hosted else true),
                        LocalOrbitPageActive provides (level == activeLevel),
                    ) {
                        holder.SaveableStateProvider(stateKey) {
                            if (level < 0) base?.invoke() else entry?.let { page(it) }
                        }
                    }
                }
            }
        }
        // While a page slides, a tap anywhere waits: the page uncovered underneath is not ready
        // yet, and a page on its way out can no longer be used.
        if (state.moving != OrbitPushState.MOVE_NONE) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                }
            )
        }
    }
}

private fun <T> forget(holder: SaveableStateHolder, before: List<T>, after: List<T>) {
    // Pages that are gone, or replaced at the same depth, start fresh next time.
    for (i in before.indices) {
        if (i >= after.size || before[i] != after[i]) holder.removeState("$i")
    }
}

/** A back dispatcher nothing sends to, for pages on screen but not on top. */
@Composable
private fun rememberInertBackOwner(): OnBackPressedDispatcherOwner {
    val lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle
    return remember(lifecycle) {
        object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
            override val lifecycle: Lifecycle = lifecycle
        }
    }
}
