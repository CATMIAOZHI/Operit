package com.ai.assistance.operit.ui.theme

import androidx.compose.ui.graphics.Color
import com.ai.assistance.operit.data.preferences.ThemePreferenceSnapshot
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorSchemeResolverTest {
    @Test
    fun `custom colors off resolves Rainy light scheme despite stale colors`() {
        val snapshot = snapshot(
            useCustomColors = false,
            primary = 0xFF0000FF.toInt(),
            secondary = 0xFF00FF00.toInt(),
        )

        val scheme = resolveThemeColorScheme(snapshot, darkTheme = false)

        assertEquals(RainyPinkHover, scheme.primary)
        assertEquals(RainyPink, scheme.secondary)
        assertEquals(RainyLightHover, scheme.primaryContainer)
        assertEquals(RainyLightText, scheme.onPrimaryContainer)
        assertEquals(RainyLightBackground, scheme.background)
        assertEquals(RainyLightPanel, scheme.surface)
        assertEquals(RainyLightElement, scheme.surfaceVariant)
    }

    @Test
    fun `custom colors off resolves Rainy dark scheme`() {
        val scheme = resolveThemeColorScheme(snapshot(), darkTheme = true)

        assertEquals(RainySakura, scheme.primary)
        assertEquals(RainyPink, scheme.secondary)
        assertEquals(RainyDarkElement, scheme.primaryContainer)
        assertEquals(RainySakura, scheme.onPrimaryContainer)
        assertEquals(RainyDarkBackground, scheme.background)
        assertEquals(RainyDarkPanel, scheme.surface)
        assertEquals(RainyDarkElement, scheme.surfaceVariant)
    }

    @Test
    fun `custom colors on resolves selected colors`() {
        // Both are readable on the light surfaces, so they have to come through untouched.
        val primary = 0xFF336699.toInt()
        val secondary = 0xFF7B1FA2.toInt()

        val scheme = resolveThemeColorScheme(
            snapshot(useCustomColors = true, primary = primary, secondary = secondary),
            darkTheme = false,
        )

        assertEquals(Color(primary), scheme.primary)
        assertEquals(Color(secondary), scheme.secondary)
    }

    @Test
    fun `pale custom accent is strengthened until it separates from the light surfaces`() {
        // 0xFFFFCDE8 is what left the statistics page blank: 1.24:1 on the statistics card and
        // 1.02:1 on the card behind it.
        val pale = 0xFFFFCDE8.toInt()

        val scheme = resolveThemeColorScheme(
            snapshot(useCustomColors = true, primary = pale, secondary = pale),
            darkTheme = false,
        )

        assertTrue(contrastRatio(scheme.primary, RainyLightHover) >= CONTRAST_TARGET)
        assertTrue(contrastRatio(scheme.secondary, RainyLightHover) >= CONTRAST_TARGET)
        // The pick is deepened, not discarded: the accent keeps its hue.
        assertEquals(hueOf(Color(pale)), hueOf(scheme.primary), HUE_TOLERANCE)
        // The container tint is still a light tint, so the accent and the tint stay distinguishable.
        assertTrue(scheme.primaryContainer.red > scheme.primary.red)
        assertTrue(scheme.primaryContainer.green > scheme.primary.green)
        assertTrue(scheme.primaryContainer.blue > scheme.primary.blue)
    }

    @Test
    fun `pale custom accent is strengthened in the composed app scheme as well`() {
        val pale = Color(0xFFFFCDE8.toInt())

        val scheme =
            generateLightColorScheme(pale, pale, UserPreferencesManager.ON_COLOR_MODE_AUTO)

        assertTrue(contrastRatio(scheme.primary, RainyLightHover) >= CONTRAST_TARGET)
        assertTrue(contrastRatio(scheme.secondary, RainyLightHover) >= CONTRAST_TARGET)
    }

    @Test
    fun `every unreadable pick reaches the target, including achromatic and worst case ones`() {
        // 0xFF20C634 is the worst input found by sweeping all 24-bit colours; the greys and white
        // have no hue at all, and the rest are pastels in the range the guard has to rescue.
        val lightInputs = listOf(0xFFFFCDE8, 0xFFFFFFFF, 0xFF808080, 0xFF20C634, 0xFFF5E6EA, 0xFFA5D6A7)
        for (argb in lightInputs) {
            val accent = ensureResolvedLightAccentContrast(Color(argb.toInt()))
            assertTrue(
                "light $argb resolved to an unreadable accent",
                contrastRatio(accent, RainyLightHover) >= CONTRAST_TARGET,
            )
        }
    }

    @Test
    fun `a very dark custom accent is strengthened on the dark surfaces too`() {
        // The fixed 0.2 lightening in the dark generator is not enough for these.
        val darkInputs = listOf(0xFF102030, 0xFF000000, 0xFF1A1A5E, 0xFF0A2A0A)
        for (argb in darkInputs) {
            val accent = ensureResolvedDarkAccentContrast(Color(argb.toInt()))
            assertTrue(
                "dark $argb resolved to an unreadable accent",
                contrastRatio(accent, RainyDarkBorder) >= CONTRAST_TARGET,
            )
        }

        val scheme =
            generateDarkColorScheme(
                Color(0xFF102030.toInt()),
                Color(0xFF102030.toInt()),
                UserPreferencesManager.ON_COLOR_MODE_AUTO,
            )
        assertTrue(contrastRatio(scheme.primary, RainyDarkBorder) >= CONTRAST_TARGET)
        assertTrue(contrastRatio(scheme.secondary, RainyDarkBorder) >= CONTRAST_TARGET)
    }

    /** Independent WCAG contrast check, so the assertion does not reuse the production maths. */
    private fun contrastRatio(first: Color, second: Color): Double {
        fun channel(value: Float): Double =
            if (value <= 0.03928f) value.toDouble() / 12.92
            else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4)

        fun luminance(color: Color): Double =
            0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (maxOf(firstLuminance, secondLuminance) + 0.05) /
            (minOf(firstLuminance, secondLuminance) + 0.05)
    }

    /** Independent hue read-out, so "the pick keeps its hue" is checked without the production HSL. */
    private fun hueOf(color: Color): Double {
        val red = color.red.toDouble()
        val green = color.green.toDouble()
        val blue = color.blue.toDouble()
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min
        if (delta == 0.0) return 0.0
        val hue =
            when (max) {
                red -> (green - blue) / delta + if (green < blue) 6.0 else 0.0
                green -> (blue - red) / delta + 2.0
                else -> (red - green) / delta + 4.0
            } * 60.0
        return hue
    }

    private companion object {
        /** WCAG AA for large text; the guard is a floor, not a goal. */
        const val CONTRAST_TARGET = 3.0
        const val HUE_TOLERANCE = 1.0
    }

    @Test
    fun `custom colors on without primary falls back to Rainy`() {
        val scheme = resolveThemeColorScheme(
            snapshot(useCustomColors = true, primary = null, secondary = 0xFF00FF00.toInt()),
            darkTheme = false,
        )

        assertEquals(RainyPinkHover, scheme.primary)
        assertEquals(RainyPink, scheme.secondary)
    }

    private fun snapshot(
        useCustomColors: Boolean = false,
        primary: Int? = null,
        secondary: Int? = null,
    ) = ThemePreferenceSnapshot(
        source = "test",
        themeMode = UserPreferencesManager.THEME_MODE_LIGHT,
        useSystemTheme = false,
        useCustomColors = useCustomColors,
        customPrimaryColor = primary,
        customSecondaryColor = secondary,
        onColorMode = UserPreferencesManager.ON_COLOR_MODE_AUTO,
        useBackgroundImage = false,
        backgroundMediaType = UserPreferencesManager.MEDIA_TYPE_IMAGE,
        backgroundImageOpacity = 0.3f,
        chatHeaderTransparent = false,
        chatHeaderOverlayMode = false,
        chatInputTransparent = false,
        chatInputFloating = true,
        chatInputLiquidGlass = false,
        chatInputWaterGlass = false,
        chatStyle = UserPreferencesManager.CHAT_STYLE_BUBBLE,
        inputStyle = UserPreferencesManager.INPUT_STYLE_CLASSIC,
        bubbleShowAvatar = true,
        bubbleWideLayoutEnabled = true,
        cursorUserBubbleFollowTheme = true,
        bubbleUserUseImage = false,
        bubbleAiUseImage = false,
        bubbleImageRenderMode = UserPreferencesManager.BUBBLE_IMAGE_RENDER_MODE_TILED_NINE_SLICE,
        bubbleUserRoundedCornersEnabled = false,
        bubbleAiRoundedCornersEnabled = false,
        bubbleUserContentPaddingLeft = 12f,
        bubbleUserContentPaddingRight = 12f,
        bubbleAiContentPaddingLeft = 12f,
        bubbleAiContentPaddingRight = 12f,
        avatarShape = UserPreferencesManager.AVATAR_SHAPE_CIRCLE,
        avatarCornerRadius = 8f,
        fontType = UserPreferencesManager.FONT_TYPE_SYSTEM,
        fontScale = 1f,
        showThinkingProcess = true,
        showStatusTags = true,
        showModelProvider = true,
        showModelName = true,
        showRoleName = true,
        showUserName = true,
        showMessageTokenStats = true,
        showMessageTimingStats = true,
        showMessageTimestamp = true,
        showInputProcessingStatus = true,
    )
}
