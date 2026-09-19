package com.ai.assistance.operit.ui.main.components

import androidx.compose.ui.graphics.Color
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A custom app bar colour is a fill the theme's own label tone was never measured against, so the
 * label is answered for it. The shipped app bar has no custom colour and must keep its own tone.
 */
class AppBarContentColorTest {
    @Test
    fun `without a custom fill the theme label tone is kept`() {
        // The shipped light app bar is #FFF0F5 on #FF6B8E, 2.44:1: soft on purpose, so it survives.
        val themeOnPrimary = Color(0xFFFFF0F5.toInt())

        assertEquals(
            themeOnPrimary,
            appBarColor(fill = null, force = false, mode = UserPreferencesManager.ON_COLOR_MODE_AUTO, themeOnPrimary),
        )
        assertEquals(
            Color.White,
            appBarColor(fill = null, force = true, mode = UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_LIGHT, themeOnPrimary),
        )
        assertEquals(
            Color.Black,
            appBarColor(fill = null, force = true, mode = UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_DARK, themeOnPrimary),
        )
        assertEquals(
            themeOnPrimary,
            appBarColor(fill = null, force = true, mode = "auto", themeOnPrimary),
        )
    }

    @Test
    fun `a custom fill gets a label that reads on it`() {
        val themeOnPrimary = Color(0xFFFFF0F5.toInt())
        val whiteFill = Color(0xFFFFFFFF.toInt())

        val measured =
            appBarColor(whiteFill, force = false, mode = UserPreferencesManager.ON_COLOR_MODE_AUTO, themeOnPrimary)
        assertEquals(Color.Black, measured)
        assertTrue(contrastRatio(measured, whiteFill) >= 4.5)
        // The theme tone would have been invisible there.
        assertTrue(contrastRatio(themeOnPrimary, whiteFill) < 1.5)
    }

    @Test
    fun `a forced tone is kept while it is visible and rescued when it is not`() {
        val themeOnPrimary = Color(0xFFFFF0F5.toInt())

        // White text on a dark custom app bar reads, so the forced tone is honoured.
        val darkFill = Color(0xFF336699.toInt())
        assertEquals(
            Color.White,
            appBarColor(
                darkFill,
                force = true,
                mode = UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_LIGHT,
                themeOnPrimary,
            ),
        )

        // White on a white custom app bar is 1:1, so the measured side takes over.
        assertEquals(
            Color.Black,
            appBarColor(
                Color(0xFFFFFFFF.toInt()),
                force = true,
                mode = UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_LIGHT,
                themeOnPrimary,
            ),
        )

        // The mirror case: black text on a near-black app bar is invisible too.
        assertEquals(
            Color.White,
            appBarColor(
                Color(0xFF102030.toInt()),
                force = true,
                mode = UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_DARK,
                themeOnPrimary,
            ),
        )
    }

    private fun appBarColor(fill: Color?, force: Boolean, mode: String, themeOnPrimary: Color) =
        resolveAppBarContentColor(
            customAppBarFill = fill,
            forceContentColor = force,
            contentColorMode = mode,
            themeOnPrimary = themeOnPrimary,
        )

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
}
