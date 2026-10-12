package com.ai.assistance.operit.ui.common.dynamicui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import com.ai.assistance.operit.ui.theme.ensureAccentReadableOn
import com.ai.assistance.operit.ui.theme.getResolvedContrastingTextColor

/** White light-mode cards with the app's ink and border colors. Blocks are now embedded without a
 * frame, so insets are a translucent ink wash: they stay visible on any chat bubble color.
 * [ink] is the message text color. A custom chat bubble can be dark in a light theme (or the
 * reverse), so light ink means the card sits on a dark backdrop, whatever the app theme says. */
internal fun cardColorScheme(base: ColorScheme, ink: Color = Color.Unspecified): ColorScheme {
    val themeDark = base.surface.luminance() < .5f
    val dark = if (ink.isSpecified) ink.luminance() > .5f else themeDark
    // The theme's own ink and outlines only fit when the backdrop matches the theme.
    val flipped = dark != themeDark
    val surface = if (dark) Color(0xFF202124) else Color.White
    val onSurface = if (dark) Color(0xFFF1F1F1) else if (flipped) Color(0xFF1F1F1F) else base.onSurface
    val inset = if (dark) Color.White.copy(alpha = .08f) else onSurface.copy(alpha = .055f)
    val primary = if (flipped) ensureAccentReadableOn(base.primary, surface) else base.primary
    return base.copy(
        primary = primary,
        // Buttons and toggles fill with primary, so their label must follow the adjusted fill.
        onPrimary = if (flipped) getResolvedContrastingTextColor(primary) else base.onPrimary,
        error = if (flipped) ensureAccentReadableOn(base.error, surface) else base.error,
        surface = surface,
        onSurface = onSurface,
        onSurfaceVariant = if (dark) Color(0xFFBFC0C4) else if (flipped) Color(0xFF5F6368) else base.onSurfaceVariant,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = inset,
        surfaceContainerHigh = inset,
        surfaceContainerHighest = inset,
        outline = if (dark) Color(0xFF77797E) else if (flipped) Color(0xFF80868B) else base.outline,
        outlineVariant = if (dark) Color(0xFF55575C) else if (flipped) Color(0xFFC4C7C5) else base.outlineVariant,
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
