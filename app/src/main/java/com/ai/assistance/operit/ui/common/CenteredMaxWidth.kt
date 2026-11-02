package com.ai.assistance.operit.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight

/**
 * On a wide screen (a tablet, or a phone turned sideways) keeps what follows at most [maxWidth]
 * wide, in the middle; on a narrower screen it changes nothing. Placed after `verticalScroll`, the
 * whole screen still scrolls while only the content stays in the middle.
 */
fun Modifier.centeredMaxWidth(maxWidth: Dp): Modifier = layout { measurable, constraints ->
    val limit = maxWidth.roundToPx()
    if (!constraints.hasBoundedWidth || constraints.maxWidth <= limit) {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    } else {
        val placeable = measurable.measure(constraints.copy(minWidth = limit, maxWidth = limit))
        val width = constraints.maxWidth
        val height = constraints.constrainHeight(placeable.height)
        layout(width, height) { placeable.place((width - placeable.width) / 2, 0) }
    }
}
