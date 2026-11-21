package com.ai.assistance.operit.ui.features.websession.browser

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout

/** True when the browser has its own activity, so the keyboard's size reaches it. */
internal val LocalBrowserFollowsKeyboard = staticCompositionLocalOf { false }

/** Keeps a dialog or sheet above the keyboard; the page's own bars stay behind it. */
@Composable
internal fun Modifier.browserKeyboardPadding(): Modifier =
    if (LocalBrowserFollowsKeyboard.current) windowInsetsPadding(WindowInsets.ime) else this

/**
 * The page above, a bar at the bottom. When the keyboard opens the page ends at the keyboard's top
 * and the bar stays where it is, behind the keyboard, as in other browsers.
 */
@Composable
internal fun BrowserKeyboardLayout(
    modifier: Modifier,
    body: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    // Measured from the page's bottom, which sits above a small window's handle bar.
    val keyboard = WindowInsets.ime.exclude(WindowInsets.captionBar)
    val followsKeyboard = LocalBrowserFollowsKeyboard.current
    Layout(contents = listOf(body, bottomBar), modifier = modifier) { (bodyParts, barParts), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val bars = barParts.map { it.measure(constraints.copy(minWidth = width, minHeight = 0)) }
        val barHeight = bars.maxOfOrNull { it.height } ?: 0
        val keyboardHeight = if (followsKeyboard) keyboard.getBottom(this) else 0
        val bodyHeight = (height - maxOf(barHeight, keyboardHeight)).coerceAtLeast(0)
        val bodies = bodyParts.map {
            it.measure(constraints.copy(minWidth = width, minHeight = bodyHeight, maxHeight = bodyHeight))
        }
        layout(width, height) {
            bodies.forEach { it.place(0, 0) }
            bars.forEach { it.place(0, height - barHeight) }
        }
    }
}
