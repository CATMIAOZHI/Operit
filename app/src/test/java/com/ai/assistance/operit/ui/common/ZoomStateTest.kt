package com.ai.assistance.operit.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomStateTest {
    private fun state() = ZoomState().apply { box = Size(1000f, 2000f) }

    /** Where the picture point under screen point [at] lies, measured from the middle of the unzoomed picture. */
    private fun ZoomState.under(at: Offset): Offset = (at - Offset(box.width / 2f, box.height / 2f) - offset) / scale

    @Test fun pinchKeepsThePointUnderTheFingers() {
        val zoom = state()
        val fingers = Offset(700f, 1200f)
        val before = zoom.under(fingers)
        zoom.transform(fingers, Offset.Zero, 2f)
        assertEquals(2f, zoom.scale, 0.001f)
        assertEquals(before.x, zoom.under(fingers).x, 0.01f)
        assertEquals(before.y, zoom.under(fingers).y, 0.01f)
    }

    @Test fun dragMovesAZoomedPictureByTheSameDistance() {
        val zoom = state()
        zoom.transform(Offset(500f, 1000f), Offset.Zero, 2f)
        zoom.transform(Offset(500f, 1000f), Offset(120f, -80f), 1f)
        assertEquals(Offset(120f, -80f), zoom.offset)
    }

    @Test fun pictureStopsAtItsEdges() {
        val zoom = state()
        zoom.transform(Offset(500f, 1000f), Offset.Zero, 2f)
        zoom.transform(Offset(500f, 1000f), Offset(9000f, 9000f), 1f)
        assertEquals(Offset(500f, 1000f), zoom.offset)
        assertFalse(zoom.canMove(10f))
        assertTrue(zoom.canMove(-10f))
    }

    @Test fun wideImageCannotBeDraggedIntoTheEmptyBars() {
        val zoom = state().apply { content = Size(2000f, 1000f) }
        // Fitted it is 1000 x 500; at 2x it is 2000 x 1000, still shorter than the 2000 high box.
        zoom.transform(Offset(500f, 1000f), Offset.Zero, 2f)
        zoom.transform(Offset(500f, 1000f), Offset(0f, 400f), 1f)
        assertEquals(0f, zoom.offset.y, 0.001f)
    }

    @Test fun scaleStaysBetweenWholeAndTheLimit() {
        val zoom = state()
        zoom.transform(Offset(500f, 1000f), Offset.Zero, 0.2f)
        assertEquals(1f, zoom.scale, 0.001f)
        assertFalse(zoom.zoomed)
        zoom.transform(Offset(500f, 1000f), Offset.Zero, 50f)
        assertEquals(5f, zoom.scale, 0.001f)
        zoom.reset()
        assertEquals(1f, zoom.scale, 0.001f)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test fun unzoomedPictureLeavesSidewaysDragsToThePager() {
        val zoom = state()
        assertFalse(zoom.canMove(10f))
        assertFalse(zoom.canMove(-10f))
    }

    @Test fun aGestureWithNoFingerPositionChangesNothing() {
        val zoom = state()
        zoom.transform(Offset.Unspecified, Offset(5f, 5f), 2f)
        assertEquals(1f, zoom.scale, 0.001f)
        assertEquals(Offset.Zero, zoom.offset)
    }
}
