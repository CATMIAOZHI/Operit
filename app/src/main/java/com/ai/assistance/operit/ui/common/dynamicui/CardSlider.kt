package com.ai.assistance.operit.ui.common.dynamicui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A card's number slider: a thin track, a round thumb and the range written under it. A tap
 * moves the thumb there; a drag only starts once it goes sideways, so the chat still scrolls.
 */
@Composable
internal fun CardSlider(
    title: String,
    value: Float,
    min: Float,
    max: Float,
    steps: Int,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val change by rememberUpdatedState(onChange)
    val shown = value.coerceIn(min, max)
    val label = cardNumberLabel(shown)
    val stops = remember(min, max, steps) { CardSliderStops(min, max, steps) }
    val currentValue by rememberUpdatedState(shown)
    var dragging by remember { mutableStateOf(false) }
    // The thumb follows the finger at once; a tap glides it to the new place.
    val fraction by animateFloatAsState(
        (shown - min) / (max - min),
        if (dragging) spring(stiffness = 10_000f) else spring(stiffness = 800f),
        label = "cardSlider",
    )
    // A tick under the finger on every stop, unless the stops are too close together to feel.
    val feelStops = stops.count <= 60
    // Compared by value: an old in-between value (0.3 on a whole-number slider) can still go to 0.
    var lastSent by remember { mutableStateOf(Float.NaN) }
    fun send(index: Int) {
        val next = stops.valueAt(index.coerceIn(0, stops.count))
        if (next == lastSent) return
        lastSent = next
        if (feelStops) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        change(next)
    }
    Column(modifier.alpha(if (enabled) 1f else .45f)) {
        // Read once, on the slider itself, with its title and value.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(label, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = scheme.primary)
        }
        val thumb = 24.dp
        // A number line runs left to right in every language.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(48.dp)
                    .semantics {
                        contentDescription = title
                        stateDescription = label
                        progressBarRangeInfo = ProgressBarRangeInfo(shown, min..max, (stops.count - 1).coerceAtLeast(0))
                        if (enabled) setProgress { target ->
                            // Always move at least one stop toward the target, so stepping never sticks.
                            var index = stops.indexAt((target - min) / (max - min))
                            if (stops.valueAt(index) == currentValue && target != currentValue) {
                                index += if (target > currentValue) 1 else -1
                            }
                            index = index.coerceIn(0, stops.count)
                            if (stops.valueAt(index) == currentValue) false
                            else { lastSent = currentValue; send(index); true }
                        } else disabled()
                    }
                    .pointerInput(enabled, stops) {
                        if (!enabled) return@pointerInput
                        val inset = thumb.toPx() / 2
                        fun position(x: Float) = (x - inset) / (size.width - inset * 2).coerceAtLeast(1f)
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            lastSent = currentValue
                            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { move, _ -> move.consume() }
                            if (drag == null) {
                                // No sideways move: a tap if the finger lifted here, else the page scrolls.
                                if (currentEvent.changes.all { !it.pressed } && !currentEvent.changes.any { it.isConsumed }) {
                                    send(stops.indexAt(position(down.position.x)))
                                }
                                return@awaitEachGesture
                            }
                            dragging = true
                            try {
                                send(stops.indexAt(position(drag.position.x)))
                                horizontalDrag(drag.id) { move ->
                                    move.consume(); send(stops.indexAt(position(move.position.x)))
                                }
                            } finally {
                                dragging = false
                            }
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                val track = scheme.onSurface.copy(alpha = .1f)
                val fill = scheme.primary
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val inset = thumb.toPx() / 2
                    val radius = CornerRadius(size.height / 2)
                    val width = size.width - inset * 2
                    drawRoundRect(track, Offset(inset, 0f), Size(width, size.height), radius)
                    drawRoundRect(fill, Offset(inset, 0f), Size(width * fraction, size.height), radius)
                }
                val travel = maxWidth - thumb
                Surface(
                    Modifier.offset { IntOffset((travel * fraction).roundToPx(), 0) }.size(thumb)
                        .scale(if (dragging) 1.12f else 1f),
                    shape = CircleShape, color = scheme.surface, shadowElevation = 3.dp,
                ) {
                    Box(Modifier.size(thumb), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(10.dp)) { drawCircle(fill) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
                Text(cardNumberLabel(min), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(cardNumberLabel(max), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Where a slider can stop: [count] equal gaps from min to max, both ends included. Without set
 * steps it stops at round numbers: every whole number when both ends are whole (a 0 to 100
 * slider never shows 37.48), else a round step near a hundredth of the range that divides it.
 */
internal class CardSliderStops(private val min: Float, private val max: Float, steps: Int) {
    private val range = (max - min).toDouble()
    private val increment: Double = when {
        steps > 0 -> range / (steps + 1)
        min == kotlin.math.floor(min) && max == kotlin.math.floor(max) && range >= 2 -> 1.0
        else -> roundIncrement(range)
    }
    val count: Int = (range / increment).roundToInt().coerceAtLeast(1)
    // Enough decimals for the gap, so 0.1 + 0.2 style noise never reaches the saved number.
    private val decimals = (kotlin.math.ceil(-kotlin.math.log10(range / count)).toInt() + 2).coerceIn(0, 12)

    fun indexAt(position: Float): Int = (position.coerceIn(0f, 1f) * count).roundToInt()
    fun indexOf(value: Float): Int = indexAt((value - min) / (max - min))
    fun valueAt(index: Int): Float = when {
        index <= 0 -> min
        index >= count -> max
        else -> java.math.BigDecimal(min + index * (range / count)).setScale(decimals, java.math.RoundingMode.HALF_UP)
            .toFloat().coerceIn(min, max)
    }

    override fun equals(other: Any?) = other is CardSliderStops && other.min == min && other.max == max && other.count == count
    override fun hashCode() = (min.hashCode() * 31 + max.hashCode()) * 31 + count

    private companion object {
        /** The largest round step (1, 2 or 5 of a power of ten) at most a hundredth of [range] that divides it. */
        fun roundIncrement(range: Double): Double {
            val fine = range / 100
            var power = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(fine)) + 1)
            repeat(12) {
                for (m in listOf(5.0, 2.0, 1.0)) {
                    val step = m * power / 10
                    if (step <= fine * 1.000001) {
                        val gaps = range / step
                        if (kotlin.math.abs(gaps - gaps.roundToInt()) < 1e-6 * gaps) return step
                    }
                }
                power /= 10
            }
            return range / kotlin.math.ceil(range / fine)
        }
    }
}
