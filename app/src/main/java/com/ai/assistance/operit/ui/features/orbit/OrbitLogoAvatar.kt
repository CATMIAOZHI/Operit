package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.ai.assistance.operit.pet.PetActivity
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** How the logo moves in one state: turns per second, breathing depth and speed, halo strength. */
private class LogoMotion(val turns: Float, val breath: Float, val breathHz: Float, val halo: Float)

/** The states in which Orbit is at work; leaving them for rest is a finish. */
private val busyStates = setOf(PetActivity.THINKING, PetActivity.TOOL, PetActivity.SUMMARIZING)

private fun motionOf(activity: PetActivity): LogoMotion = when (activity) {
    PetActivity.THINKING -> LogoMotion(turns = 0.42f, breath = 0.07f, breathHz = 0.9f, halo = 1f)
    PetActivity.TOOL -> LogoMotion(turns = 0.85f, breath = 0.05f, breathHz = 1.6f, halo = 0.6f)
    PetActivity.SUMMARIZING -> LogoMotion(turns = -0.35f, breath = 0.06f, breathHz = 0.8f, halo = 0.8f)
    else -> LogoMotion(turns = 0.06f, breath = 0.035f, breathHz = 0.25f, halo = 0f)
}

/**
 * Orbit's own logo as its default picture: the core, its orbit and two moons. The core is the user
 * ([userColor]); the ring and moons are Orbit ([orbitColor]), always circling the user. Null colors
 * use the logo's own defaults; like a picture, the logo is the avatar, so the chat theme takes its
 * color from the logo and never the other way round. It follows what Orbit is doing the way a pet
 * does: it turns slowly and breathes at rest, turns faster with a soft halo while thinking, faster
 * still while it uses a tool, and hops twice when it finishes and comes back to rest. Each frame is
 * drawn in the draw phase from the frame clock, so it runs at the display's rate without
 * recomposing; with [animate] false it stays still.
 */
@Composable
internal fun OrbitLogoAvatar(
    activity: PetActivity,
    animate: Boolean,
    modifier: Modifier = Modifier,
    userColor: Color? = null,
    orbitColor: Color? = null,
) {
    // The picture sits on white; a pale chosen color is darkened until it reads on white.
    val core = remember(userColor) { orbitLogoDrawnColor(userColor, OrbitLogoDefaults.user) }
    val ring = remember(orbitColor) { orbitLogoDrawnColor(orbitColor, OrbitLogoDefaults.orbit) }
    val angle = remember { mutableFloatStateOf(0f) }
    val breath = remember { mutableFloatStateOf(0f) }
    val start = remember { motionOf(activity) }
    val halo = remember { Animatable(start.halo) }
    val speed = remember { Animatable(start.turns) }
    val depth = remember { Animatable(start.breath) }
    val hop = remember { Animatable(0f) }
    // The state before the current one, to tell "just finished" (busy, then at rest) from rest.
    val previous = remember { arrayOf(activity) }
    val motion by rememberUpdatedState(motionOf(activity))

    // Speed, breathing and halo ease into the new state, so a change never jumps. A hop cut short
    // by the next state settles back down instead of staying in the air.
    LaunchedEffect(activity) {
        val target = motionOf(activity)
        val finished = previous[0] in busyStates && activity !in busyStates
        previous[0] = activity
        coroutineScope {
            launch { speed.animateTo(target.turns, tween(600)) }
            launch { depth.animateTo(target.breath, tween(600)) }
            launch { halo.animateTo(target.halo, tween(500)) }
            launch {
                if (animate && (finished || activity == PetActivity.COMPLETE)) {
                    // Two hops: a full one, then a smaller one that settles.
                    hop.animateTo(1f, tween(160))
                    hop.animateTo(0f, tween(150))
                    hop.animateTo(0.6f, tween(130))
                    hop.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow))
                } else if (hop.value != 0f) {
                    hop.animateTo(0f, tween(140))
                }
            }
        }
    }
    if (animate) LaunchedEffect(Unit) {
        var last = 0L
        var breathPhase = 0f
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                    angle.floatValue = (angle.floatValue + 360f * speed.value * dt) % 360f
                    breathPhase = (breathPhase + motion.breathHz * dt) % 1f
                    breath.floatValue = sin(breathPhase * 2f * PI.toFloat())
                }
                last = now
            }
        }
    }
    Box(modifier.drawBehind {
        val unit = size.minDimension / 24f
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = Stroke(width = 1.8f * unit, cap = StrokeCap.Round)
        val pulse = 1f + depth.value * breath.floatValue
        translate(top = -hop.value * 2.4f * unit) {
            // A soft halo around the core while thinking or working.
            if (halo.value > 0.01f) {
                val swell = 0.5f + 0.5f * breath.floatValue
                drawCircle(core.copy(alpha = 0.14f * halo.value), radius = (4.6f + 1.4f * swell) * unit, center = center)
            }
            // The orbit and its moons turn around the core.
            rotate(angle.floatValue, center) {
                val arcSize = Size(20f * unit, 20f * unit)
                val arcTopLeft = Offset(center.x - 10f * unit, center.y - 10f * unit)
                drawArc(ring, startAngle = -33.5f, sweepAngle = 133.5f, useCenter = false,
                    topLeft = arcTopLeft, size = arcSize, style = stroke)
                drawArc(ring, startAngle = 146.5f, sweepAngle = 133.5f, useCenter = false,
                    topLeft = arcTopLeft, size = arcSize, style = stroke)
                drawCircle(ring, radius = 2f * unit, center = center + Offset(7f * unit, -7f * unit), style = stroke)
                drawCircle(ring, radius = 2f * unit, center = center + Offset(-7f * unit, 7f * unit), style = stroke)
            }
            scale(pulse, pivot = center) {
                drawCircle(core, radius = 3f * unit, center = center, style = stroke)
            }
        }
    })
}

/** Darkens a color step by step until it stands out on the picture's white background. */
private fun readableOnWhite(color: Color): Color {
    var c = color
    repeat(8) { if (c.luminance() > 0.22f) c = lerp(c, Color(0xFF1C1B1F), 0.25f) }
    return c
}

/**
 * The logo's own colors when Orbit has not chosen any: a warm core like a sun for the user, and a
 * calm blue ring for Orbit going round it.
 */
internal object OrbitLogoDefaults {
    const val USER_HEX = "#D9531F"
    const val ORBIT_HEX = "#3D6FD1"
    val user = Color(0xFFD9531F)
    val orbit = Color(0xFF3D6FD1)
}

/** A saved "#RRGGBB" logo color, or null for the default. */
internal fun orbitLogoColor(hex: String?): Color? =
    hex?.takeIf { it.isNotBlank() }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

/** The color a logo part is really drawn in: the chosen one or [default], darkened to read on white. */
internal fun orbitLogoDrawnColor(chosen: Color?, default: Color): Color = readableOnWhite(chosen ?: default)

/** How a saved "#RRGGBB" logo color is actually drawn: very light colors are darkened to read on white. */
internal fun orbitLogoShownHex(hex: String): String {
    val shown = readableOnWhite(orbitLogoColor(hex) ?: return hex)
    return String.format(java.util.Locale.ROOT, "#%06X", shown.toArgb() and 0xFFFFFF)
}
