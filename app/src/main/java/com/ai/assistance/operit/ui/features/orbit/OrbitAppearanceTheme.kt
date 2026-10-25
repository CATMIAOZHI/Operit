package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import coil.request.ImageRequest
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.data.preferences.OrbitChatPalette
import com.ai.assistance.operit.data.preferences.OrbitColorMode
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.ui.theme.LocalOpaquePageAppearance
import com.ai.assistance.operit.ui.theme.OpaquePageAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalOrbitBubbleColor = staticCompositionLocalOf { Color(0xFFDDE6F2) }
internal val LocalOrbitAvatarColor = staticCompositionLocalOf { Color(0xFFDDE6F2) }

internal fun orbitPaletteColor(palette: OrbitChatPalette, avatar: Color): Color = when (palette) {
    OrbitChatPalette.AVATAR -> avatar
    OrbitChatPalette.SKY -> Color(0xFFD2E5FC)
    OrbitChatPalette.INK -> Color(0xFF171719)
    OrbitChatPalette.SAND -> Color(0xFFE5DDD5)
    OrbitChatPalette.BLUE -> Color(0xFFBFD9FA)
    OrbitChatPalette.LAVENDER -> Color(0xFFD9C9ED)
    OrbitChatPalette.PINK -> Color(0xFFF2C2DE)
    OrbitChatPalette.PEACH -> Color(0xFFFAD9BC)
    OrbitChatPalette.GREEN -> Color(0xFFE0EBC6)
}

/**
 * Extract once per avatar, off the UI thread, from a bounded still thumbnail (never pet frames).
 * Without a picture the avatar is Orbit's logo, so the color comes from the logo's core: the core
 * is the user, and the theme, including the user's own message bubbles, is the user's color.
 */
@Composable
internal fun OrbitAppearanceTheme(orbit: OrbitProfile, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val avatar = orbitPictureUri(orbit)
    val logoCore = orbit.appearance.logoUserColor
    val logoColor = if (avatar == null) {
        remember(logoCore) { orbitAvatarTone(orbitLogoColor(logoCore) ?: OrbitLogoDefaults.user) }
    } else null
    // Each extracted color is tied to the picture it came from, so a new picture never borrows the
    // last one's color; until it is ready the theme keeps the color it already shows.
    val pictureColor by produceState<Pair<String, Color>?>(null, avatar) {
        if (avatar != null) {
            value = avatar to try {
                val result = OperitApplication.globalImageLoader.execute(
                    ImageRequest.Builder(context).data(avatar).size(96).allowHardware(false).build()
                )
                result.drawable?.let { drawable ->
                    withContext(Dispatchers.Default) {
                        val bitmap = drawable.toBitmap(96, 96)
                        val pixels = IntArray(96 * 96)
                        bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96)
                        orbitAvatarTone(Color(orbitDominantColor(pixels)))
                    }
                } ?: NEUTRAL_AVATAR_TONE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An unavailable image keeps a neutral palette and does not prevent opening settings.
                NEUTRAL_AVATAR_TONE
            }
        }
    }
    val lastAvatarColor = remember { LastColor() }
    val avatarColor = logoColor ?: pictureColor?.takeIf { it.first == avatar }?.second ?: lastAvatarColor.color
    // Remembered only once this frame is really shown, so an abandoned composition cannot leave it.
    SideEffect { lastAvatarColor.color = avatarColor }
    val dark = when (orbit.appearance.mode) {
        OrbitColorMode.SYSTEM -> isSystemInDarkTheme()
        OrbitColorMode.LIGHT -> false
        OrbitColorMode.DARK -> true
    }
    val palette = orbitPaletteColor(orbit.appearance.palette, avatarColor)
    val bubble = if (dark) lerp(palette, Color.Black, 0.65f) else palette
    val scheme = remember(palette, dark) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(palette.toArgb(), hsl)
        val accent = if (orbit.appearance.palette == OrbitChatPalette.INK) {
            if (dark) Color(0xFFDDDDDF) else Color(0xFF242426)
        } else if (dark) {
            Color.hsl(hsl[0], hsl[1].coerceIn(0.25f, 0.65f), 0.76f)
        } else orbitLightAccent(hsl[0], hsl[1])
        val onAccent = if (accent.luminance() > 0.179f) Color.Black else Color.White
        if (dark) darkColorScheme(
            primary = accent, onPrimary = onAccent, primaryContainer = bubble, onPrimaryContainer = Color.White,
            secondary = accent, onSecondary = onAccent, secondaryContainer = bubble, onSecondaryContainer = Color.White,
            tertiary = accent, onTertiary = onAccent, tertiaryContainer = bubble, onTertiaryContainer = Color.White,
            background = Color(0xFF151517), onBackground = Color(0xFFF1F1F3),
            surface = Color(0xFF232326), onSurface = Color(0xFFF1F1F3),
            surfaceVariant = Color(0xFF303034), onSurfaceVariant = Color(0xFFBDBDC4),
            surfaceContainer = Color(0xFF27272B), surfaceContainerLow = Color(0xFF202023),
            surfaceContainerHigh = Color(0xFF303034), surfaceContainerHighest = Color(0xFF38383D),
            surfaceContainerLowest = Color(0xFF111113), surfaceBright = Color(0xFF38383D), surfaceDim = Color(0xFF171719),
            outline = Color(0xFF727279), outlineVariant = Color(0xFF3B3B40), surfaceTint = accent,
        ) else lightColorScheme(
            primary = accent, onPrimary = onAccent, primaryContainer = bubble,
            onPrimaryContainer = if (bubble.luminance() > 0.5f) Color(0xFF242426) else Color.White,
            secondary = accent, onSecondary = onAccent, secondaryContainer = Color(0xFFF0F1F3),
            onSecondaryContainer = Color(0xFF242426),
            tertiary = accent, onTertiary = onAccent, tertiaryContainer = bubble,
            onTertiaryContainer = if (bubble.luminance() > 0.5f) Color(0xFF242426) else Color.White,
            background = Color(0xFFFAFBFC), onBackground = Color(0xFF202023),
            surface = Color.White, onSurface = Color(0xFF202023),
            surfaceVariant = Color(0xFFF0F1F3), onSurfaceVariant = Color(0xFF717176),
            surfaceContainer = Color(0xFFF3F4F6), surfaceContainerLow = Color(0xFFF7F8FA),
            surfaceContainerHigh = Color(0xFFEFF0F2), surfaceContainerHighest = Color(0xFFE9EAED),
            surfaceContainerLowest = Color.White, surfaceBright = Color.White, surfaceDim = Color(0xFFE7E8EB),
            outline = Color(0xFF86868E), outlineVariant = Color(0xFFE4E5E8), surfaceTint = accent,
        )
    }
    val pageAppearance = LocalOpaquePageAppearance.current
    DisposableEffect(pageAppearance) {
        onDispose { pageAppearance?.value = null }
    }
    SideEffect { pageAppearance?.value = OpaquePageAppearance(scheme.background, dark) }
    CompositionLocalProvider(LocalOrbitBubbleColor provides bubble, LocalOrbitAvatarColor provides avatarColor) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
    }
}

/**
 * The light theme's accent (thinking slider, send button, model name): the chosen color's own hue,
 * kept as light as it can be while it still reads on white, so it stays close to the palette swatch
 * instead of turning a dark shade of it.
 */
internal fun orbitLightAccent(hue: Float, saturation: Float): Color {
    val s = saturation.coerceIn(0.35f, 0.75f)
    var lightness = 0.62f
    while (lightness > 0.30f) {
        val color = Color.hsl(hue, s, lightness)
        if (1.05f / (color.luminance() + 0.05f) >= ORBIT_ACCENT_MIN_CONTRAST) return color
        lightness -= 0.02f
    }
    return Color.hsl(hue, s, 0.30f)
}

/** Contrast against white the light accent keeps, enough for its icons, slider and short labels. */
private const val ORBIT_ACCENT_MIN_CONTRAST = 3.2f

/** The chat tone used when a picture gives no color. */
private val NEUTRAL_AVATAR_TONE = Color(0xFFDDE6F2)

/** The avatar tone shown last, kept while a new picture's color is still being read. */
private class LastColor(var color: Color = NEUTRAL_AVATAR_TONE)

/** The soft chat tone taken from an avatar's main color: its hue, gently saturated and light. */
private fun orbitAvatarTone(color: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    return Color.hsl(hsl[0], hsl[1].coerceIn(0.12f, 0.45f), 0.86f)
}

/** A small RGB histogram ignores transparent padding and plain white/black avatar backgrounds. */
internal fun orbitDominantColor(pixels: IntArray): Int {
    val counts = IntArray(4096)
    val red = LongArray(4096)
    val green = LongArray(4096)
    val blue = LongArray(4096)
    for (pixel in pixels) {
        if ((pixel ushr 24) < 128) continue
        val r = pixel ushr 16 and 255
        val g = pixel ushr 8 and 255
        val b = pixel and 255
        if (minOf(r, g, b) > 240 || maxOf(r, g, b) < 25) continue
        val index = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        val weight = 1 + (maxOf(r, g, b) - minOf(r, g, b)) / 64
        counts[index] += weight
        red[index] += r * weight
        green[index] += g * weight
        blue[index] += b * weight
    }
    val best = counts.indices.maxByOrNull { counts[it] } ?: return 0xFFDDE6F2.toInt()
    val count = counts[best]
    if (count == 0) return 0xFFDDE6F2.toInt()
    return (0xFF000000 or ((red[best] / count) shl 16) or ((green[best] / count) shl 8) or (blue[best] / count)).toInt()
}
