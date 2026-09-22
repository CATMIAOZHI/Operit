package com.ai.assistance.operit.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The black-or-white label for a fill the user picked (drawer background, bubbles, previews). */
class ThemeUtilsTextColorTest {
    @Test
    fun `a fill the luma threshold got wrong now reads`() {
        // 0xFF00D901 sits just under the old 0.5 luma line, so it used to get white text at 1.92:1
        // where black measures 10.93:1. It is the worst of 14.4% of the colour space.
        val brightGreen = Color(0xFF00D901.toInt())

        assertEquals(Color.Black, getTextColorForBackground(brightGreen))
        assertTrue(contrastRatio(Color.White, brightGreen) < 2.0)
        assertTrue(contrastRatio(Color.Black, brightGreen) >= 4.5)
    }

    @Test
    fun `the fills the app ships keep the tone they already had`() {
        // Chat bubbles use primaryContainer, the AI bubble uses surface; the drawer uses surface.
        assertEquals(Color.Black, getTextColorForBackground(RainyLightHover))
        assertEquals(Color.Black, getTextColorForBackground(RainyLightPanel))
        assertEquals(Color.White, getTextColorForBackground(RainyDarkElement))
        assertEquals(Color.White, getTextColorForBackground(RainyDarkPanel))
    }

    @Test
    fun `the extremes pick the only side that reads`() {
        assertEquals(Color.Black, getTextColorForBackground(Color.White))
        assertEquals(Color.White, getTextColorForBackground(Color.Black))
    }

    @Test
    fun `the picker rating agrees with the sample text next to it`() {
        // The rating used to threshold a luma, so these picks read "low contrast" while the sample
        // text drawn on them measured 10.93:1 and 16.52:1.
        val brightGreen = Color(0xFF00D901.toInt())
        val nearBlack = Color(0xFF102030.toInt())
        assertTrue(contrastRatio(Color.Black, brightGreen) >= 7.0)
        assertTrue(isHighContrast(brightGreen))
        assertTrue(contrastRatio(Color.White, nearBlack) >= 7.0)
        assertTrue(isHighContrast(nearBlack))

        // A mid tone keeps the colour picker honest: readable, but no AAA headroom.
        val midGrey = Color(0xFF808080.toInt())
        assertTrue(contrastRatio(Color.Black, midGrey) >= 4.5)
        assertFalse(isHighContrast(midGrey))

        assertTrue(isHighContrast(Color.White))
        assertTrue(isHighContrast(Color.Black))
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
}
