package com.ai.assistance.operit.services.core

import org.junit.Assert.*
import org.junit.Test

class TranscriptWindowPolicyTest {
    @Test fun protectsViewportOnEitherDirection() {
        val timestamps = (0L..399L).toList()
        for (newer in listOf(false, true)) {
            val range = transcriptWindowRange(timestamps, setOf(210, 220), newer)
            assertTrue(186 in range)
            assertTrue(244 in range)
            assertEquals(160, range.count())
        }
    }

    @Test fun doesNotEvictBeforeViewportIsMeasured() {
        val timestamps = (0L..399L).toList()
        assertEquals(timestamps.indices, transcriptWindowRange(timestamps, emptySet(), false))
    }

    @Test fun viewportCanExceedSoftBudget() {
        val range = transcriptWindowRange((0L..399L).toList(), setOf(20, 370), false)
        assertTrue(20 in range && 370 in range)
    }

    @Test fun staleFailureCannotClearNewLoadingState() {
        val controller = CurrentChatWindowController()
        val old = controller.generation()
        assertTrue(controller.beginLoadingDisplayWindow())
        controller.reset()
        assertTrue(controller.beginLoadingDisplayWindow())
        controller.finishLoadingDisplayWindowFailure(old)
        assertTrue(controller.isLoadingDisplayWindow.value)
        assertFalse(controller.isCurrent(old))
    }
}
