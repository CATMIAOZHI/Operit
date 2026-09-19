package com.ai.assistance.operit.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.ai.assistance.operit.data.preferences.ThemePreferenceSnapshot
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager.Companion.ON_COLOR_MODE_AUTO
import com.ai.assistance.operit.data.preferences.UserPreferencesManager.Companion.ON_COLOR_MODE_DARK
import com.ai.assistance.operit.data.preferences.UserPreferencesManager.Companion.ON_COLOR_MODE_LIGHT
import kotlin.math.abs
import kotlin.math.pow

private val RainyDarkColorScheme =
    darkColorScheme(
        primary = RainySakura,
        onPrimary = RainyDarkBackground,
        primaryContainer = RainyDarkElement,
        onPrimaryContainer = RainySakura,
        inversePrimary = RainyPinkHover,
        secondary = RainyPink,
        onSecondary = RainyDarkBackground,
        secondaryContainer = RainyDarkElement,
        onSecondaryContainer = RainyDarkText,
        tertiary = RainyPinkHover,
        onTertiary = RainyDarkBackground,
        tertiaryContainer = Color(0xFF512035),
        onTertiaryContainer = RainySakura,
        error = RainyRose,
        onError = RainyDarkBackground,
        errorContainer = Color(0xFF3A1B28),
        onErrorContainer = RainySakura,
        background = RainyDarkBackground,
        onBackground = RainyDarkText,
        surface = RainyDarkPanel,
        onSurface = RainyDarkText,
        surfaceVariant = RainyDarkElement,
        onSurfaceVariant = RainyDarkMuted,
        surfaceTint = RainySakura,
        inverseSurface = RainyLightPanel,
        inverseOnSurface = RainyLightText,
        outline = RainyDarkBorder,
        outlineVariant = RainyDarkElement,
        surfaceBright = RainyDarkElement,
        surfaceDim = RainyDarkBackground,
        surfaceContainerLowest = RainyDarkBackground,
        surfaceContainerLow = RainyDarkPanel,
        surfaceContainer = RainyDarkPanel,
        surfaceContainerHigh = RainyDarkElement,
        surfaceContainerHighest = RainyDarkBorder,
    )

private val RainyLightColorScheme =
    lightColorScheme(
        primary = RainyPinkHover,
        onPrimary = RainyLightBackground,
        primaryContainer = RainyLightHover,
        onPrimaryContainer = RainyLightText,
        inversePrimary = RainySakura,
        secondary = RainyPink,
        onSecondary = RainyLightText,
        secondaryContainer = RainyLightHover,
        onSecondaryContainer = RainyLightText,
        tertiary = RainyRose,
        onTertiary = RainyLightBackground,
        tertiaryContainer = Color(0xFFFDE8EF),
        onTertiaryContainer = RainyLightText,
        error = RainyRose,
        onError = RainyLightBackground,
        errorContainer = Color(0xFFFDE8EF),
        onErrorContainer = RainyLightText,
        background = RainyLightBackground,
        onBackground = RainyLightText,
        surface = RainyLightPanel,
        onSurface = RainyLightText,
        surfaceVariant = RainyLightElement,
        onSurfaceVariant = RainyLightMuted,
        surfaceTint = RainyPinkHover,
        inverseSurface = RainyDarkPanel,
        inverseOnSurface = RainyDarkText,
        outline = RainyLightBorder,
        outlineVariant = RainyLightHover,
        surfaceBright = RainyLightPanel,
        surfaceDim = RainyLightBackground,
        surfaceContainerLowest = RainyLightPanel,
        surfaceContainerLow = RainyLightPanel,
        surfaceContainer = RainyLightElement,
        surfaceContainerHigh = RainyLightBackground,
        surfaceContainerHighest = RainyLightHover,
    )

internal fun rainyBaseColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) RainyDarkColorScheme else RainyLightColorScheme

/**
 * The tone for something that stopped without failing, such as a Subagent run an app restart cut
 * short. It is deliberately neither `error` nor `tertiary`: this palette paints the light theme's
 * tertiary with the very rose it paints `error`, so a stopped run would still read as one that went
 * wrong. The warning hue says "look at this" without saying "this broke".
 */
val ColorScheme.stoppedAttention: Color
    get() = if (background.luminance() > 0.5f) RainyWarningTextLight else RainyWarning

fun resolveThemeColorScheme(
    context: Context,
    snapshot: ThemePreferenceSnapshot
): ColorScheme {
    val systemDarkTheme =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val darkTheme =
        if (snapshot.useSystemTheme) {
            systemDarkTheme
        } else {
            snapshot.themeMode == UserPreferencesManager.THEME_MODE_DARK
        }

    return resolveThemeColorScheme(snapshot, darkTheme)
}

internal fun resolveThemeColorScheme(
    snapshot: ThemePreferenceSnapshot,
    darkTheme: Boolean
): ColorScheme {
    var colorScheme = rainyBaseColorScheme(darkTheme)

    if (snapshot.useCustomColors) {
        snapshot.customPrimaryColor?.let { primaryArgb ->
            val primary = Color(primaryArgb)
            val secondary = snapshot.customSecondaryColor?.let(::Color) ?: colorScheme.secondary
            colorScheme =
                if (darkTheme) {
                    generateResolvedDarkColorScheme(primary, secondary, snapshot.onColorMode)
                } else {
                    generateResolvedLightColorScheme(primary, secondary, snapshot.onColorMode)
                }
        }
    }

    return colorScheme
}

private fun generateResolvedLightColorScheme(
    primaryColor: Color,
    secondaryColor: Color,
    onColorMode: String
): ColorScheme {
    // A custom color is used verbatim and then drawn as accent *text* all over the UI, so a pale
    // pick has to be strengthened before it is readable on the light surfaces.
    val accent = ensureResolvedLightAccentContrast(primaryColor)
    val accentSecondary = ensureResolvedLightAccentContrast(secondaryColor)
    val onPrimary =
        when (onColorMode) {
            ON_COLOR_MODE_LIGHT -> Color.White
            ON_COLOR_MODE_DARK -> Color.Black
            else -> getResolvedContrastingTextColor(accent)
        }
    val onSecondary =
        when (onColorMode) {
            ON_COLOR_MODE_LIGHT -> Color.White
            ON_COLOR_MODE_DARK -> Color.Black
            else -> getResolvedContrastingTextColor(accentSecondary)
        }

    // Tints keep the value the user picked; only the accent itself is strengthened.
    val primaryContainer = lightenResolvedColor(primaryColor, 0.7f)
    val onPrimaryContainer = getResolvedContrastingTextColor(primaryContainer)
    val secondaryContainer = lightenResolvedColor(secondaryColor, 0.7f)
    val onSecondaryContainer = getResolvedContrastingTextColor(secondaryContainer)

    return RainyLightColorScheme.copy(
        primary = accent,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = accentSecondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        onSurface = Color.Black,
        onSurfaceVariant = Color.Black.copy(alpha = 0.7f),
        onBackground = Color.Black
    )
}

private fun generateResolvedDarkColorScheme(
    primaryColor: Color,
    secondaryColor: Color,
    onColorMode: String
): ColorScheme {
    val adjustedPrimaryColor = lightenResolvedColor(primaryColor, 0.2f)
    val adjustedSecondaryColor = lightenResolvedColor(secondaryColor, 0.2f)

    val onPrimary =
        when (onColorMode) {
            ON_COLOR_MODE_LIGHT -> Color.White
            ON_COLOR_MODE_DARK -> Color.Black
            else -> getResolvedContrastingTextColor(adjustedPrimaryColor)
        }
    val onSecondary =
        when (onColorMode) {
            ON_COLOR_MODE_LIGHT -> Color.White
            ON_COLOR_MODE_DARK -> Color.Black
            else -> getResolvedContrastingTextColor(adjustedSecondaryColor)
        }

    val primaryContainer = darkenResolvedColor(primaryColor, 0.3f)
    val onPrimaryContainer = getResolvedContrastingTextColor(primaryContainer, forceLight = true)
    val secondaryContainer = darkenResolvedColor(secondaryColor, 0.3f)
    val onSecondaryContainer =
        getResolvedContrastingTextColor(secondaryContainer, forceLight = true)

    return RainyDarkColorScheme.copy(
        primary = adjustedPrimaryColor,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = adjustedSecondaryColor,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        onSurface = Color.White,
        onSurfaceVariant = Color.White.copy(alpha = 0.7f),
        onBackground = Color.White
    )
}

private fun getResolvedContrastingTextColor(
    backgroundColor: Color,
    forceDark: Boolean = false,
    forceLight: Boolean = false
): Color {
    if (forceDark) return Color.Black
    if (forceLight) return Color.White

    val luminance =
        0.299 * backgroundColor.red +
            0.587 * backgroundColor.green +
            0.114 * backgroundColor.blue

    return if (luminance > 0.5) Color.Black else Color.White
}

private fun lightenResolvedColor(color: Color, factor: Float): Color {
    val r = color.red + (1f - color.red) * factor
    val g = color.green + (1f - color.green) * factor
    val b = color.blue + (1f - color.blue) * factor
    return Color(r, g, b, color.alpha)
}

private fun darkenResolvedColor(color: Color, factor: Float): Color {
    val r = color.red * (1f - factor)
    val g = color.green * (1f - factor)
    val b = color.blue * (1f - factor)
    return Color(r, g, b, color.alpha)
}

/**
 * `primary` is not only a fill: this UI also paints it as *text* in many places (statistics
 * numbers, outcome labels, link-like accents). Material's own schemes keep that accent at a
 * legible tone, but a custom color is applied as the user picked it, and this palette paints the
 * light surfaces with the same pale pink family. A pale custom color therefore disappears into
 * them: an accent of `#FFCDE8` on the default card (`RainyLightHover`) measures 1.0:1, which is
 * what left the statistics page looking blank.
 *
 * Colors that already separate from that surface are returned untouched, so only unreadable picks
 * change. [RainyLightHover] is the darkest surface an accent is drawn on, so requiring the
 * contrast there also covers the lighter panels. The hue is kept and saturation is capped rather
 * than raised, so a pastel pick turns into a deeper version of itself instead of a neon one.
 *
 * The dark palette is deliberately untouched: it already lightens a custom color, and a dark
 * custom color on the dark surfaces is a separate case nobody has hit yet.
 */
internal fun ensureResolvedLightAccentContrast(accent: Color): Color {
    if (resolvedContrastRatio(accent, RainyLightHover) >= ACCENT_TEXT_CONTRAST_TARGET) return accent

    val hsl = accent.toResolvedHsl()
    val saturation = hsl.saturation.coerceAtMost(ACCENT_SATURATION_CEILING)
    var lightness = hsl.lightness
    repeat(ACCENT_LIGHTNESS_STEPS) {
        val candidate = ResolvedHsl(hsl.hue, saturation, lightness.coerceIn(0f, 1f)).toResolvedColor()
        if (resolvedContrastRatio(candidate, RainyLightHover) >= ACCENT_TEXT_CONTRAST_TARGET) {
            return candidate
        }
        lightness -= ACCENT_LIGHTNESS_STEP
    }
    return ResolvedHsl(hsl.hue, saturation, lightness.coerceIn(0f, 1f)).toResolvedColor()
}

/** WCAG AA for large text; the accent is also used at smaller sizes, so this is a floor, not a goal. */
private const val ACCENT_TEXT_CONTRAST_TARGET = 3f

private const val ACCENT_SATURATION_CEILING = 0.75f
private const val ACCENT_LIGHTNESS_STEP = 0.04f
private const val ACCENT_LIGHTNESS_STEPS = 20

/** Hue in degrees, saturation and lightness in 0..1. */
private data class ResolvedHsl(val hue: Float, val saturation: Float, val lightness: Float)

private fun Color.toResolvedHsl(): ResolvedHsl {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val lightness = (max + min) / 2f
    val delta = max - min
    if (delta == 0f) return ResolvedHsl(0f, 0f, lightness)

    val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val hue =
        when (max) {
            red -> (green - blue) / delta + if (green < blue) 6f else 0f
            green -> (blue - red) / delta + 2f
            else -> (red - green) / delta + 4f
        } * 60f
    return ResolvedHsl(hue, saturation, lightness)
}

private fun ResolvedHsl.toResolvedColor(): Color {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val second = chroma * (1f - abs((hue / 60f) % 2f - 1f))
    val (r, g, b) =
        when ((hue / 60f).let { if (it < 0f) it + 6f else it }) {
            in 0f..1f -> Triple(chroma, second, 0f)
            in 1f..2f -> Triple(second, chroma, 0f)
            in 2f..3f -> Triple(0f, chroma, second)
            in 3f..4f -> Triple(0f, second, chroma)
            in 4f..5f -> Triple(second, 0f, chroma)
            else -> Triple(chroma, 0f, second)
        }
    val match = lightness - chroma / 2f
    return Color(r + match, g + match, b + match, 1f)
}

private fun resolvedChannelLuminance(channel: Float): Float =
    if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

private fun resolvedRelativeLuminance(color: Color): Float =
    0.2126f * resolvedChannelLuminance(color.red) +
        0.7152f * resolvedChannelLuminance(color.green) +
        0.0722f * resolvedChannelLuminance(color.blue)

private fun resolvedContrastRatio(first: Color, second: Color): Float {
    val firstLuminance = resolvedRelativeLuminance(first)
    val secondLuminance = resolvedRelativeLuminance(second)
    return (maxOf(firstLuminance, secondLuminance) + 0.05f) /
        (minOf(firstLuminance, secondLuminance) + 0.05f)
}
