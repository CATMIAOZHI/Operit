package com.ai.assistance.operit.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.ai.assistance.operit.data.preferences.ThemePreferenceSnapshot
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager.Companion.DEFAULT_CUSTOM_PRIMARY_COLOR
import com.ai.assistance.operit.data.preferences.UserPreferencesManager.Companion.DEFAULT_CUSTOM_SECONDARY_COLOR
import com.ai.assistance.operit.data.preferences.UserPreferencesManager.Companion.ON_COLOR_MODE_AUTO
import com.ai.assistance.operit.data.preferences.UserPreferencesManager.Companion.ON_COLOR_MODE_DARK
import com.ai.assistance.operit.data.preferences.UserPreferencesManager.Companion.ON_COLOR_MODE_LIGHT

private val RainyDarkColorScheme =
    darkColorScheme(primary = Color(DEFAULT_CUSTOM_PRIMARY_COLOR), secondary = Color(DEFAULT_CUSTOM_SECONDARY_COLOR), tertiary = Pink80)

private val RainyLightColorScheme =
    lightColorScheme(
        primary = Color(DEFAULT_CUSTOM_PRIMARY_COLOR),
        secondary = Color(DEFAULT_CUSTOM_SECONDARY_COLOR),
        tertiary = Pink40
    )

internal fun rainyBaseColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) RainyDarkColorScheme else RainyLightColorScheme

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
    val onPrimary =
        when (onColorMode) {
            ON_COLOR_MODE_LIGHT -> Color.White
            ON_COLOR_MODE_DARK -> Color.Black
            else -> getResolvedContrastingTextColor(primaryColor)
        }
    val onSecondary =
        when (onColorMode) {
            ON_COLOR_MODE_LIGHT -> Color.White
            ON_COLOR_MODE_DARK -> Color.Black
            else -> getResolvedContrastingTextColor(secondaryColor)
        }

    val primaryContainer = lightenResolvedColor(primaryColor, 0.7f)
    val onPrimaryContainer = getResolvedContrastingTextColor(primaryContainer)
    val secondaryContainer = lightenResolvedColor(secondaryColor, 0.7f)
    val onSecondaryContainer = getResolvedContrastingTextColor(secondaryContainer)

    return RainyLightColorScheme.copy(
        primary = primaryColor,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondaryColor,
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
