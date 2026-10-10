package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.max
import kotlinx.coroutines.flow.StateFlow

/**
 * Live microphone level, drawn as a bar strip.
 *
 * The level comes from the speech provider, which only refreshes it per audio chunk, so it is
 * smoothed between updates. Providers also zero it while they are finishing a phrase, hence the
 * breathing floor: a strip that falls flat reads as "it stopped listening".
 */
@Composable
fun VoiceLevelBars(
    volumeLevelFlow: StateFlow<Float>,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
) {
    val level by volumeLevelFlow.collectAsState()
    val smoothed by
        animateFloatAsState(
            targetValue = level.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 120),
            label = "voiceLevel",
        )
    val breath by
        rememberInfiniteTransition(label = "voiceBreath").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "voiceBreath",
        )
    val color = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val step = size.width / barCount
        val barWidth = (step * 0.42f).coerceAtLeast(1f)
        val centerY = size.height / 2f
        val floor = 0.1f + 0.3f * breath
        for (index in 0 until barCount) {
            // A fixed per-bar shape keeps the strip reading as audio instead of one pulsing blob.
            val shape = 0.3f + 0.7f * abs(sin(index * 1.7f))
            val amplitude = max(smoothed, floor) * shape
            val half = (size.height / 2f) * amplitude.coerceIn(0.06f, 1f)
            val x = index * step + step / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(x - barWidth / 2f, centerY - half),
                size = Size(barWidth, half * 2f),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
