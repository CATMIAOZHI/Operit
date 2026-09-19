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
    // The fixed 0.2 is not enough for a very dark pick, so a dark custom color still needs the
    // contrast guard on the dark surfaces.
    val accent = ensureResolvedDarkAccentContrast(adjustedPrimaryColor)
    val accentSecondary = ensureResolvedDarkAccentContrast(adjustedSecondaryColor)

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

    val primaryContainer = darkenResolvedColor(primaryColor, 0.3f)
    val onPrimaryContainer = getResolvedContrastingTextColor(primaryContainer, forceLight = true)
    val secondaryContainer = darkenResolvedColor(secondaryColor, 0.3f)
    val onSecondaryContainer =
        getResolvedContrastingTextColor(secondaryContainer, forceLight = true)

    return RainyDarkColorScheme.copy(
        primary = accent,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = accentSecondary,
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

    // Whichever of the two actually contrasts better, so a label on a filled accent never lands
    // below ~4.58:1 (the luma heuristic this replaces could pick the worse one near its threshold).
    return if (resolvedContrastRatio(Color.Black, backgroundColor) >=
        resolvedContrastRatio(Color.White, backgroundColor)
    ) {
        Color.Black
    } else {
        Color.White
    }
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
 * surfaces behind that text with the same pale pink family. A pale custom color therefore
 * disappears into them: measured from a device screenshot, an accent of `#FFCDE8` is 1.24:1 on
 * the statistics card (`surfaceContainerHigh`) and 1.02:1 on the default card
 * ([RainyLightHover]), which is what left that page looking blank.
 *
 * Colors that already separate from the surface they are drawn on are returned untouched, so only
 * unreadable picks change. The hue is kept and saturation is capped rather than raised, so a
 * pastel pick turns into a deeper version of itself instead of a neon one.
 *
 * The hue is kept for anything perceptible; a near-neutral pick can still drift, because the
 * result is packed back into 8-bit channels - measured over every input, nothing whose result has
 * chroma at or above 0.10 moves by more than 5 degrees.
 */
internal fun ensureResolvedLightAccentContrast(accent: Color): Color =
    ensureResolvedAccentContrast(accent, RainyLightHover, lighten = false)

/**
 * The dark palette has the same gap from the other side: a custom color is lightened by a fixed
 * 0.2 there, which is not enough for a very dark pick, so an accent of `#FF102030` still lands
 * around 1.7:1 on `[RainyDarkBorder]` (the default dark card). [RainyDarkBorder] is the lightest
 * surface an accent is drawn on, so requiring the contrast there also covers the darker panels.
 *
 * This runs on the already lightened color, so the shipped `primary` for `#102030` is `#8295A5`
 * rather than the `#5990C8` this function produces on the raw pick.
 */
internal fun ensureResolvedDarkAccentContrast(accent: Color): Color =
    ensureResolvedAccentContrast(accent, RainyDarkBorder, lighten = true)

private fun ensureResolvedAccentContrast(
    accent: Color,
    surface: Color,
    lighten: Boolean,
): Color {
    if (resolvedContrastRatio(accent, surface) >= ACCENT_TEXT_CONTRAST_TARGET) return accent

    val hsl = accent.toResolvedHsl()
    val saturation = hsl.saturation.coerceAtMost(ACCENT_SATURATION_CEILING)
    var lightness = hsl.lightness
    repeat(ACCENT_LIGHTNESS_STEPS) {
        val candidate = ResolvedHsl(hsl.hue, saturation, lightness.coerceIn(0f, 1f)).toResolvedColor()
        if (resolvedContrastRatio(candidate, surface) >= ACCENT_TEXT_CONTRAST_TARGET) {
            return candidate
        }
        lightness += if (lighten) ACCENT_LIGHTNESS_STEP else -ACCENT_LIGHTNESS_STEP
    }
    return ResolvedHsl(hsl.hue, saturation, lightness.coerceIn(0f, 1f)).toResolvedColor()
}

/**
 * WCAG AA for large text; the accent is also used at smaller sizes, so this is a floor, not a
 * goal. [ACCENT_LIGHTNESS_STEPS] sizes the search for this target and would need to grow (or the
 * lightness floor to drop) before the target could be raised to AA's 4.5.
 */
private const val ACCENT_TEXT_CONTRAST_TARGET = 3.0

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

private fun resolvedChannelLuminance(channel: Float): Double {
    val value = channel.toDouble()
    return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
}

private fun resolvedRelativeLuminance(color: Color): Double =
    0.2126 * resolvedChannelLuminance(color.red) +
        0.7152 * resolvedChannelLuminance(color.green) +
        0.0722 * resolvedChannelLuminance(color.blue)

/** Double rather than Float so a ">= target" decision cannot land the wrong side by rounding. */
internal fun resolvedContrastRatio(first: Color, second: Color): Double {
    val firstLuminance = resolvedRelativeLuminance(first)
    val secondLuminance = resolvedRelativeLuminance(second)
    return (maxOf(firstLuminance, secondLuminance) + 0.05) /
        (minOf(firstLuminance, secondLuminance) + 0.05)
}
