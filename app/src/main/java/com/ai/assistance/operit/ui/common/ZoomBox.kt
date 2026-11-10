package com.ai.assistance.operit.ui.common

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Whether the last gesture on a [ZoomBox] travelled past the touch slop, so its lift is no tap. */
private class Travelled {
    var far = false
    /** The same for the gesture before, which a double tap counts as its first tap. */
    var before = false
}

/**
 * Zoom and position of a picture shown full screen. A pinch zooms around the fingers and follows
 * them from the first movement; a drag moves the picture once it is zoomed, never past its edges.
 */
@Stable
class ZoomState(private val maxScale: Float = 5f) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    private var contentSize by mutableStateOf(Size.Unspecified)

    /**
     * The picture's own size once it is loaded, so it stops at its real edges, not at the empty bars
     * around it. A picture moved while still loading is brought back inside them.
     */
    var content: Size
        get() = contentSize
        set(value) {
            if (contentSize == value) return
            contentSize = value
            place(scale, offset)
        }

    /** The size of the area the picture is fitted into. When it changes (the screen turned) the picture stays inside it. */
    var box = Size.Zero
        set(value) {
            if (field == value) return
            field = value
            place(scale, offset)
        }

    /** True from the moment a finger touches the picture until the last one is lifted. */
    var touching by mutableStateOf(false)
        internal set

    val zoomed: Boolean get() = scale > 1.01f

    /** The double tap zoom still playing, stopped by the next touch that moves the picture. */
    private var animation: Job? = null

    /** How far the picture may sit from the middle at [scale], each way. */
    private fun limit(scale: Float): Offset {
        val own = content
        val fitted = if (own.isSpecified && own.width > 0f && own.height > 0f && box.width > 0f && box.height > 0f) {
            val fit = minOf(box.width / own.width, box.height / own.height)
            Size(own.width * fit, own.height * fit)
        } else box
        return Offset(
            ((fitted.width * scale - box.width) / 2f).coerceAtLeast(0f),
            ((fitted.height * scale - box.height) / 2f).coerceAtLeast(0f),
        )
    }

    private fun place(scale: Float, offset: Offset) {
        val max = limit(scale)
        this.scale = scale
        this.offset = Offset(offset.x.coerceIn(-max.x, max.x), offset.y.coerceIn(-max.y, max.y))
    }

    /** Fingers around [centroid] moved by [pan] and spread by [zoom]: what was under them stays under them. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        if (!centroid.isSpecified || !zoom.isFinite()) return
        val next = (scale * zoom).coerceIn(1f, maxScale)
        val from = centroid - Offset(box.width / 2f, box.height / 2f)
        place(next, from + pan - (from - offset) * (next / scale))
    }

    /** Whether a sideways drag by [dx] still moves the picture. At its edge the drag is left to a pager around it. */
    fun canMove(dx: Float): Boolean {
        val max = limit(scale).x
        return if (dx > 0f) offset.x < max - 0.5f else offset.x > -max + 0.5f
    }

    fun reset() {
        stop()
        scale = 1f
        offset = Offset.Zero
    }

    /** Stops a double tap zoom still playing, so fingers take over from where it is. */
    fun stop() {
        animation?.cancel()
        animation = null
    }

    /** A double tap at [at]: zooms in on that point, or back out to the whole picture, played in [scope]. */
    fun toggle(scope: CoroutineScope, at: Offset) {
        stop()
        animation = scope.launch { animateToggle(at) }
    }

    private suspend fun animateToggle(at: Offset) {
        val fromScale = scale
        val fromOffset = offset
        val toScale = if (zoomed) 1f else DOUBLE_TAP_SCALE.coerceAtMost(maxScale)
        val from = at - Offset(box.width / 2f, box.height / 2f)
        val toOffset = if (toScale == 1f) Offset.Zero else from - (from - fromOffset) * (toScale / fromScale)
        animate(0f, 1f, animationSpec = tween(220)) { t, _ ->
            place(lerp(fromScale, toScale, t), lerp(fromOffset, toOffset, t))
        }
    }

    private companion object {
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

/**
 * Shows [content] (a picture filling the box) so it can be zoomed and moved through [state].
 * Fingers are read on the box itself, which never moves, and only the content inside is scaled;
 * reading them on the scaled picture makes it lag and jump under the fingers.
 *
 * Inside a pager a one-finger drag turns the page while the picture is not zoomed, and again once
 * a zoomed picture has reached its edge on that side: in the same drag, what the picture cannot
 * move any more is passed on to the pager (through nested scrolling), and so is the fling at the end.
 */
@Composable
fun ZoomBox(
    state: ZoomState,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    val dispatcher = remember { NestedScrollDispatcher() }
    val connection = remember { object : NestedScrollConnection {} }
    // A drag that nothing took (up and down on a picture that is not zoomed, or sideways with no
    // pager around it) still ends in a lift; it must not count as a tap that closes the picture.
    val travelled = remember { Travelled() }
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { state.box = it.toSize() }
            .nestedScroll(connection, dispatcher)
            .pointerInput(state) {
                detectTapGestures(
                    // After a drag the next quick tap is paired with it as a "double tap"; it is only a tap.
                    onDoubleTap = { at ->
                        when {
                            travelled.far -> Unit
                            travelled.before -> tap?.invoke()
                            else -> state.toggle(scope, at)
                        }
                    },
                    onTap = { if (!travelled.far) tap?.invoke() },
                )
            }
            // Last in the chain, so it sees the fingers first: what it takes is no tap and turns no page.
            .pointerInput(state) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    state.touching = true
                    travelled.before = travelled.far
                    travelled.far = false
                    var travel = Offset.Zero
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(down)
                    // The finger whose speed is the fling; when it is lifted the speed follows another one.
                    var followed = down.id
                    var dragged = Offset.Zero
                    // null until the first finger has moved far enough to tell what it is doing.
                    var ours: Boolean? = null
                    // Whether one finger is dragging sideways, null until it has moved far enough to tell
                    // (again after a pinch). Only a sideways drag may go on to the pager, so reading a tall
                    // picture up and down never turns the page.
                    var sideways: Boolean? = null
                    // Whether part of this drag moved the pager; it then gets the fling to settle on a page.
                    var handed = false
                    // How far the pager is out of its page because of this drag. Moved back to zero, the
                    // fling at the end is not passed on, so it cannot turn the page the picture is still on.
                    var shifted = 0f
                    // Whether the fingers were lifted, rather than the gesture being taken away.
                    var lifted = false
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            val pan = event.calculatePan()
                            travel += pan
                            if (travel.getDistance() > slop) travelled.far = true
                            val follow = event.changes.firstOrNull { it.id == followed && it.pressed }
                                ?: event.changes.firstOrNull { it.pressed }?.also {
                                    followed = it.id
                                    tracker.resetTracking()
                                }
                            // The last lift counts too: a finger held still before letting go has no speed left.
                            (follow ?: event.changes.firstOrNull { it.id == followed })?.let { tracker.addPointerInputChange(it) }
                            if (pressed > 1) {
                                travelled.far = true
                                ours = true
                                sideways = null
                                dragged = Offset.Zero
                            } else if (ours == null) {
                                dragged += pan
                                if (dragged.getDistance() > slop) {
                                    sideways = abs(dragged.x) >= abs(dragged.y)
                                    ours = state.zoomed && (sideways == false || state.canMove(dragged.x))
                                }
                            } else if (pressed == 1 && sideways == null) {
                                dragged += pan
                                if (dragged.getDistance() > slop) sideways = abs(dragged.x) >= abs(dragged.y)
                            }
                            if (ours == true) {
                                state.stop()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if (pressed > 1) {
                                    state.transform(centroid, pan, event.calculateZoom())
                                } else if (pressed == 1 && sideways != true) {
                                    state.transform(centroid, pan, 1f)
                                } else if (pressed == 1) {
                                    // A page half turned comes back first; what the picture cannot move goes on to the pager.
                                    val pre = dispatcher.dispatchPreScroll(Offset(pan.x, 0f), NestedScrollSource.UserInput)
                                    val left = pan - pre
                                    val before = state.offset.x
                                    state.transform(centroid, left, 1f)
                                    val moved = state.offset.x - before
                                    val rest = left.x - moved
                                    val post = if (rest != 0f) {
                                        dispatcher.dispatchPostScroll(Offset(moved, 0f), Offset(rest, 0f), NestedScrollSource.UserInput)
                                    } else Offset.Zero
                                    if (pre.x != 0f || post.x != 0f) handed = true
                                    shifted += pre.x + post.x
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (sideways == false) {
                                // An up and down drag on a picture that is not zoomed moves nothing, and it is
                                // taken here so the pager does not turn the page on its sideways part.
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        lifted = true
                    } finally {
                        state.touching = false
                        if (handed) {
                            // A gesture taken away, or one that brought the pager back to its page, only settles it there.
                            val fling = if (lifted && abs(shifted) > 0.5f) Velocity(tracker.calculateVelocity().x, 0f) else Velocity.Zero
                            scope.launch {
                                val used = dispatcher.dispatchPreFling(fling)
                                dispatcher.dispatchPostFling(used, fling - used)
                            }
                        }
                    }
                }
            },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = state.scale
                scaleY = state.scale
                translationX = state.offset.x
                translationY = state.offset.y
            },
            content = content,
        )
    }
}
