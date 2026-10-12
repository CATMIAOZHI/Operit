package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.dp

/**
 * Remembers where the draft field sits inside the composer, so a hold can start on the field and
 * still be followed after the dictation panel has taken the field's place.
 */
internal class HoldToTalkArea {
    var container: LayoutCoordinates? = null
    var field: LayoutCoordinates? = null

    fun fieldBounds(): Rect? {
        val outer = container?.takeIf { it.isAttached } ?: return null
        val inner = field?.takeIf { it.isAttached } ?: return null
        return outer.localBoundingBoxOf(inner)
    }
}

@Composable
internal fun rememberKeyboardDown(): Boolean {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val down by remember(ime, density) { derivedStateOf { ime.getBottom(density) == 0 } }
    return down
}

/**
 * Hold to talk, for the composer node that also reports [HoldToTalkArea.container] at the same
 * point of its modifier chain. A hold that starts on the field calls [onStart]; while the finger
 * stays down, dragging right past a short distance flips [onTargetChange] to send, and letting go
 * reports the last target to [onRelease]. Taps and scrolling are only watched, never taken, until
 * the hold fires.
 */
@Composable
internal fun Modifier.holdToTalk(
    enabled: Boolean,
    area: HoldToTalkArea,
    onStart: () -> Boolean,
    onTargetChange: (send: Boolean) -> Unit,
    onRelease: (send: Boolean) -> Unit,
): Modifier {
    val latestEnabled by rememberUpdatedState(enabled)
    val latestStart by rememberUpdatedState(onStart)
    val latestTarget by rememberUpdatedState(onTargetChange)
    val latestRelease by rememberUpdatedState(onRelease)
    val haptic = LocalHapticFeedback.current
    return pointerInput(area, haptic) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!latestEnabled || area.fieldBounds()?.contains(down.position) != true) {
                return@awaitEachGesture
            }
            val endedEarly =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (
                            event.changes.size > 1 ||
                                change == null ||
                                !change.pressed ||
                                (change.position - down.position).getDistance() >
                                    viewConfiguration.touchSlop
                        ) {
                            break
                        }
                    }
                    true
                }
            if (endedEarly != null || !latestEnabled) return@awaitEachGesture
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val started = latestStart()
            val sendDistance = 48.dp.toPx()
            var send = false
            var released = false
            var cancelled = false
            try {
                // The rest of the press belongs to the hold: the field must not take it as a tap.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    // Read before consuming: a system cancel arrives already consumed, a real lift
                    // does not.
                    val cancel = change == null || change.isConsumed
                    event.changes.forEach { it.consume() }
                    if (change == null || !change.pressed) {
                        cancelled = cancel
                        break
                    }
                    val nowSend = change.position.x - down.position.x > sendDistance
                    if (started && nowSend != send) {
                        send = nowSend
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        latestTarget(send)
                    }
                }
                released = true
                if (started) latestRelease(send && !cancelled)
            } finally {
                // A cancelled gesture only stops the recording; it never sends.
                if (started && !released) latestRelease(false)
            }
        }
    }
}

/**
 * While [active], the field's own long press is pushed out of reach, so it cannot focus the field
 * and raise the keyboard under the dictation panel when a hold starts.
 */
@Composable
internal fun SuppressFieldLongPress(active: Boolean, content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val suppressed =
        remember(base) {
            object : ViewConfiguration by base {
                override val longPressTimeoutMillis: Long = Long.MAX_VALUE
            }
        }
    CompositionLocalProvider(
        LocalViewConfiguration provides if (active) suppressed else base,
        content = content,
    )
}
