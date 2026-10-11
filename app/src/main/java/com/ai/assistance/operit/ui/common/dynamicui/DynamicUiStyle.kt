package com.ai.assistance.operit.ui.common.dynamicui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Neutral card surfaces, retaining the user's accent colors and app-selected light/dark mode. */
internal fun cardColorScheme(base: ColorScheme): ColorScheme {
    val dark = base.surface.luminance() < .5f
    val surface = if (dark) Color(0xFF202124) else Color.White
    val inset = if (dark) Color(0xFF2C2D30) else Color(0xFFF5F5F5)
    return base.copy(
        surface = surface,
        onSurface = if (dark) Color(0xFFF1F1F1) else Color(0xFF202124),
        onSurfaceVariant = if (dark) Color(0xFFBFC0C4) else Color(0xFF616368),
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = inset,
        surfaceContainerHigh = inset,
        surfaceContainerHighest = inset,
        outline = if (dark) Color(0xFF77797E) else Color(0xFFA0A2A6),
        outlineVariant = if (dark) Color(0xFF494B50) else Color(0xFFE1E2E5),
    )
}

internal data class CardGridCell(val index: Int, val span: Int)

/** Pack in source order; never redistribute an incomplete row into wider columns. */
internal fun cardGridRows(count: Int, spans: List<Int>): List<List<CardGridCell>> {
    require(count in 1..4 && spans.all { it in 1..count })
    val rows = mutableListOf<List<CardGridCell>>()
    var row = mutableListOf<CardGridCell>()
    var used = 0
    spans.forEachIndexed { index, span ->
        if (used + span > count) {
            rows += row
            row = mutableListOf()
            used = 0
        }
        row += CardGridCell(index, span)
        used += span
    }
    if (row.isNotEmpty()) rows += row
    return rows
}
