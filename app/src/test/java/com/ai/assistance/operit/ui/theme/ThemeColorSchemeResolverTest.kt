package com.ai.assistance.operit.ui.theme

import androidx.compose.ui.graphics.Color
import com.ai.assistance.operit.data.preferences.ThemePreferenceSnapshot
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import org.junit.Assert.assertEquals
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

        assertEquals(Color(UserPreferencesManager.DEFAULT_CUSTOM_PRIMARY_COLOR), scheme.primary)
        assertEquals(Color(UserPreferencesManager.DEFAULT_CUSTOM_SECONDARY_COLOR), scheme.secondary)
    }

    @Test
    fun `custom colors off resolves Rainy dark scheme`() {
        val scheme = resolveThemeColorScheme(snapshot(), darkTheme = true)

        assertEquals(Color(UserPreferencesManager.DEFAULT_CUSTOM_PRIMARY_COLOR), scheme.primary)
        assertEquals(Color(UserPreferencesManager.DEFAULT_CUSTOM_SECONDARY_COLOR), scheme.secondary)
    }

    @Test
    fun `custom colors on resolves selected colors`() {
        val primary = 0xFF336699.toInt()
        val secondary = 0xFF669933.toInt()

        val scheme = resolveThemeColorScheme(
            snapshot(useCustomColors = true, primary = primary, secondary = secondary),
            darkTheme = false,
        )

        assertEquals(Color(primary), scheme.primary)
        assertEquals(Color(secondary), scheme.secondary)
    }

    @Test
    fun `custom colors on without primary falls back to Rainy`() {
        val scheme = resolveThemeColorScheme(
            snapshot(useCustomColors = true, primary = null, secondary = 0xFF00FF00.toInt()),
            darkTheme = false,
        )

        assertEquals(Color(UserPreferencesManager.DEFAULT_CUSTOM_PRIMARY_COLOR), scheme.primary)
        assertEquals(Color(UserPreferencesManager.DEFAULT_CUSTOM_SECONDARY_COLOR), scheme.secondary)
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
