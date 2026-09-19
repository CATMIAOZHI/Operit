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
        // 0xFFFFCDE8 is what left the statistics page blank: 1.0:1 against the card behind it.
        val pale = 0xFFFFCDE8.toInt()

        val scheme = resolveThemeColorScheme(
            snapshot(useCustomColors = true, primary = pale, secondary = pale),
            darkTheme = false,
        )

        assertTrue(contrastRatio(scheme.primary, RainyLightHover) >= 3f)
        assertTrue(contrastRatio(scheme.secondary, RainyLightHover) >= 3f)
        // The pick is deepened, not discarded: the accent keeps its hue.
        assertTrue(scheme.primary.red > scheme.primary.green)
        assertTrue(scheme.primary.blue > scheme.primary.green)
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

        assertTrue(contrastRatio(scheme.primary, RainyLightHover) >= 3f)
        assertTrue(contrastRatio(scheme.secondary, RainyLightHover) >= 3f)
    }

    /** Independent WCAG contrast check, so the assertion does not reuse the production maths. */
    private fun contrastRatio(first: Color, second: Color): Float {
        fun channel(value: Float): Double =
            if (value <= 0.03928f) value.toDouble() / 12.92
            else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4)

        fun luminance(color: Color): Double =
            0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return ((maxOf(firstLuminance, secondLuminance) + 0.05) /
            (minOf(firstLuminance, secondLuminance) + 0.05))
            .toFloat()
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
