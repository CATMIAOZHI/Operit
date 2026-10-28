package com.ai.assistance.operit.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Red for warning text that stays easy to read: on a dark surface the theme's error red is
 * too dim for normal-size text, so it is lifted toward white there. Light themes keep it as is.
 */
@Composable
internal fun readableErrorColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.surface.luminance() < 0.5f) lerp(scheme.error, Color.White, 0.35f) else scheme.error
}

/** A solid red button: the lifted red with dark text on a dark surface, full red otherwise. */
@Composable
internal fun destructiveButtonColors(): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.surface.luminance() < 0.5f) readableErrorColor() to Color(0xFF410E0B)
    else scheme.error to scheme.onError
}
