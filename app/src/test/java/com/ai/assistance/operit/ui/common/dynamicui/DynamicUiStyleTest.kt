package com.ai.assistance.operit.ui.common.dynamicui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class DynamicUiStyleTest {
    @Test fun cardsUseNeutralSurfacesButKeepUserAccentsInBothModes() {
        val accent = Color(0xFFE85488)
        val light = cardColorScheme(lightColorScheme(primary = accent))
        assertEquals(Color.White, light.surface)
        assertEquals(accent, light.primary)
        assertTrue(light.onSurface.luminance() < .1f)
        val dark = cardColorScheme(darkColorScheme(primary = accent))
        assertEquals(accent, dark.primary)
        assertTrue(dark.surface.luminance() < .1f)
        assertTrue(dark.onSurface.luminance() > .8f)
    }

    @Test fun customBubbleTextColorDecidesTheCardBackdrop() {
        // Light theme with a dark custom AI bubble: the message ink is light.
        val onDarkBubble = cardColorScheme(lightColorScheme(primary = Color(0xFFCA5079)), Color.White)
        assertTrue(onDarkBubble.surface.luminance() < .1f)
        assertTrue(onDarkBubble.onSurface.luminance() > .8f)
        val errorOnDark = onDarkBubble.error.luminance()
        assertTrue((errorOnDark + .05f) / (onDarkBubble.surface.luminance() + .05f) >= 3f)
        // Dark theme with a light custom AI bubble: the message ink is dark.
        val onLightBubble = cardColorScheme(darkColorScheme(primary = Color(0xFFFFB3C6)), Color.Black)
        assertEquals(Color.White, onLightBubble.surface)
        assertTrue(onLightBubble.onSurface.luminance() < .1f)
        val accent = onLightBubble.primary.luminance()
        assertTrue((1.05f) / (accent + .05f) >= 3f)
        val label = onLightBubble.onPrimary.luminance()
        assertTrue((maxOf(label, accent) + .05f) / (minOf(label, accent) + .05f) >= 4.5f)
        // Matching ink keeps the theme scheme untouched.
        val plain = cardColorScheme(lightColorScheme())
        val matched = cardColorScheme(lightColorScheme(), Color.Black)
        assertEquals(listOf(plain.surface, plain.onSurface, plain.primary, plain.outline),
            listOf(matched.surface, matched.onSurface, matched.primary, matched.outline))
    }

    @Test fun gridSpansPackInOrderAndPreserveIncompleteRows() {
        val keypad = cardGridRows(4, List(16) { 1 } + listOf(2, 1, 1))
        assertEquals(5, keypad.size)
        assertEquals(listOf(CardGridCell(16, 2), CardGridCell(17, 1), CardGridCell(18, 1)), keypad.last())
        assertEquals(listOf(listOf(CardGridCell(0, 3)), listOf(CardGridCell(1, 2), CardGridCell(2, 1))),
            cardGridRows(4, listOf(3, 2, 1)))
        assertEquals(1, cardGridRows(2, listOf(1, 1, 1)).last().sumOf { it.span })
        assertTrue(cardGridRows(2, emptyList()).isEmpty())
    }

    @Test fun statusTextHasReadableContrastInBothModes() {
        for (base in listOf(lightColorScheme(), darkColorScheme())) {
            val scheme = cardColorScheme(base)
            for (tone in listOf("neutral", "success", "info", "warning", "error")) {
                val (ink, rawFill) = cardToneColors(tone, scheme)
                // Neutral insets are translucent washes; measure what is actually drawn.
                val fill = rawFill.compositeOver(scheme.surface)
                val lighter = maxOf(ink.luminance(), fill.luminance())
                val darker = minOf(ink.luminance(), fill.luminance())
                assertTrue(tone, (lighter + .05f) / (darker + .05f) >= 4.5f)
            }
        }
    }
}
