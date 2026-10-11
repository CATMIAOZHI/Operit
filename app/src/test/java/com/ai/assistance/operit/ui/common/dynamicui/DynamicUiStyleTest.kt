package com.ai.assistance.operit.ui.common.dynamicui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
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
                val (ink, fill) = cardToneColors(tone, scheme)
                val lighter = maxOf(ink.luminance(), fill.luminance())
                val darker = minOf(ink.luminance(), fill.luminance())
                assertTrue(tone, (lighter + .05f) / (darker + .05f) >= 4.5f)
            }
        }
    }
}
