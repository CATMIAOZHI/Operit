package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Orbit's touch feedback: one kind per meaning, so the same thing always feels the same. They go
 * through the system, which leaves them out when touch feedback is turned off.
 */
internal object OrbitHaptics {
    /** A tapback put on a message, by the user or by Orbit. */
    fun reaction(haptic: HapticFeedback) = haptic.performHapticFeedback(HapticFeedbackType.Confirm)

    /** A tapback taken off again. */
    fun reactionRemoved(haptic: HapticFeedback) = haptic.performHapticFeedback(HapticFeedbackType.ToggleOff)

    /** A swipe pulled past the point where letting go does something (reply, vote). */
    fun threshold(haptic: HapticFeedback) = haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)

    /** A switch turned on or off. */
    fun toggle(haptic: HapticFeedback, on: Boolean) =
        haptic.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
}

/** [onChange] for a switch, felt as it flips like the system's own switches. */
@Composable
internal fun orbitToggle(onChange: (Boolean) -> Unit): (Boolean) -> Unit {
    val haptic = LocalHapticFeedback.current
    return { on -> OrbitHaptics.toggle(haptic, on); onChange(on) }
}
