package com.ai.assistance.operit.pet

enum class PetEdge {
    LEFT, RIGHT, TOP, BOTTOM;
    val vertical: Boolean get() = this == TOP || this == BOTTOM
}

internal data class PetAnchor(val x: Float, val y: Float, val edge: PetEdge)
internal data class PetPlacement(val left: Float, val top: Float, val width: Float)

internal fun dockPet(edge: PetEdge, x: Float, y: Float): PetAnchor = PetAnchor(
    when (edge) { PetEdge.LEFT -> 0f; PetEdge.RIGHT -> 1f; else -> x.coerceIn(0f, 1f) },
    when (edge) { PetEdge.TOP -> 0f; PetEdge.BOTTOM -> 1f; else -> y.coerceIn(0f, 1f) },
    edge,
)

/** Compare physical distances: normalized coordinates alone bias tall screens toward the top. */
internal fun snapPet(width: Float, height: Float, petSize: Float, x: Float, y: Float): PetAnchor {
    val travelX = (width - petSize).coerceAtLeast(0f)
    val travelY = (height - petSize).coerceAtLeast(0f)
    val edge = listOf(
        PetEdge.LEFT to x.coerceIn(0f, 1f) * travelX,
        PetEdge.RIGHT to (1f - x.coerceIn(0f, 1f)) * travelX,
        PetEdge.TOP to y.coerceIn(0f, 1f) * travelY,
        PetEdge.BOTTOM to (1f - y.coerceIn(0f, 1f)) * travelY,
    ).minBy { it.second }.first
    return dockPet(edge, x, y)
}

internal fun petWidth(viewport: Float, petSize: Float, bubbleSize: Float, edge: PetEdge, hasBubble: Boolean): Float =
    (if (!hasBubble) petSize else if (edge.vertical) maxOf(petSize, bubbleSize) else petSize + bubbleSize)
        .coerceAtMost(viewport)

/**
 * Window and child use the same fractional alignment. Their offsets add up to
 * x * (viewport - petSize), so showing or expanding a bubble never moves the pet.
 */
internal fun placePet(
    viewportWidth: Float, viewportHeight: Float, width: Float, height: Float, x: Float, y: Float,
): PetPlacement = PetPlacement(
    x.coerceIn(0f, 1f) * (viewportWidth - width).coerceAtLeast(0f),
    y.coerceIn(0f, 1f) * (viewportHeight - height).coerceAtLeast(0f),
    width,
)
