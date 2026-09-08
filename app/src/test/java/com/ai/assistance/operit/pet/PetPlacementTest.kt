package com.ai.assistance.operit.pet

import org.junit.Assert.*
import org.junit.Test

class PetPlacementTest {
    @Test fun releaseSnapsToNearestPhysicalEdge() {
        assertEquals(PetAnchor(0f, 0.4f, PetEdge.LEFT), snapPet(360f, 800f, 80f, 0.1f, 0.4f))
        assertEquals(PetAnchor(1f, 0.6f, PetEdge.RIGHT), snapPet(360f, 800f, 80f, 0.9f, 0.6f))
        assertEquals(PetAnchor(0.5f, 0f, PetEdge.TOP), snapPet(360f, 800f, 80f, 0.5f, 0.05f))
        assertEquals(PetAnchor(0.5f, 1f, PetEdge.BOTTOM), snapPet(360f, 800f, 80f, 0.5f, 0.95f))
        // A smaller normalized Y can still be farther away in a tall viewport.
        assertEquals(PetEdge.LEFT, snapPet(360f, 800f, 80f, 0.2f, 0.1f).edge)
        assertEquals(PetEdge.TOP, snapPet(800f, 360f, 80f, 0.2f, 0.1f).edge)
    }

    @Test fun expandingOrHidingBubbleKeepsPetAnchorOnAllFourEdges() {
        for ((viewportWidth, viewportHeight) in listOf(360f to 800f, 800f to 360f)) {
            for (size in listOf(48f, 80f, 128f)) for (edge in PetEdge.entries) {
                for (fraction in listOf(0f, 0.1f, 0.5f, 0.9f, 1f)) {
                    val anchor = dockPet(edge, fraction, fraction)
                    for (bubble in listOf(false, true)) for (bubbleHeight in listOf(64f, 220f)) {
                        val width = petWidth(viewportWidth, size, 232f, edge, bubble)
                        val height = if (!bubble) size else if (edge.vertical) size + bubbleHeight else maxOf(size, bubbleHeight)
                        val window = placePet(viewportWidth, viewportHeight, width, height, anchor.x, anchor.y)
                        val petX = window.left + (width - size) * anchor.x
                        val petY = window.top + (height - size) * anchor.y
                        assertEquals(anchor.x * (viewportWidth - size), petX, 0.001f)
                        assertEquals(anchor.y * (viewportHeight - size), petY, 0.001f)
                        assertTrue(window.left >= 0f && window.top >= 0f)
                        assertTrue(window.left + width <= viewportWidth + 0.001f)
                        assertTrue(window.top + height <= viewportHeight + 0.001f)
                    }
                }
            }
        }
    }

    @Test fun draggingWithoutBubbleUsesPetSizedWindowAnywhereOnScreen() {
        for (edge in PetEdge.entries) {
            val width = petWidth(360f, 128f, 232f, edge, false)
            val position = placePet(360f, 800f, width, 128f, 0.4f, 0.7f)
            assertEquals(128f, position.width, 0f)
            assertEquals(92.8f, position.left, 0.001f)
            assertEquals(470.4f, position.top, 0.001f)
        }
    }

    @Test fun verticalBubblesUseSeparateWidthAndRetainPositionAlongEdge() {
        assertEquals(232f, petWidth(360f, 80f, 232f, PetEdge.TOP, true), 0f)
        assertEquals(312f, petWidth(360f, 80f, 232f, PetEdge.LEFT, true), 0f)
        assertEquals(200f, petWidth(200f, 80f, 232f, PetEdge.BOTTOM, true), 0f)
        assertEquals(PetAnchor(0.7f, 1f, PetEdge.BOTTOM), dockPet(PetEdge.BOTTOM, 0.7f, 0.3f))
        assertEquals(PetAnchor(1f, 0.3f, PetEdge.RIGHT), dockPet(PetEdge.RIGHT, 0.7f, 0.3f))
    }
}
