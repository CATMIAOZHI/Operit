package com.ai.assistance.operit.features.reading

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoilerGuardTest {
    private val state = ReadingState(
        book = ReaderBook(
            id = "book",
            name = "Book",
            author = "Author",
            totalChapterCount = 300,
            lastReadAt = 1L,
        ),
        chapterIndex = 183,
        chapterTitle = "Chapter",
        layoutPosition = 12_600,
        bodyPosition = 12_543,
        capturedAt = 2L,
    )

    @Test
    fun `completed chapters are allowed`() {
        assertTrue(SpoilerGuard.isPositionAllowed(182, 0, 99_999, state))
    }

    @Test
    fun `future chapters are rejected`() {
        assertFalse(SpoilerGuard.isPositionAllowed(184, 0, 1, state))
    }

    @Test
    fun `current chapter must end at or before safe body position`() {
        assertTrue(SpoilerGuard.isPositionAllowed(183, 12_000, 12_543, state))
        assertFalse(SpoilerGuard.isPositionAllowed(183, 12_000, 12_544, state))
    }

    @Test
    fun `missing precise current position fails closed`() {
        assertFalse(
            SpoilerGuard.isPositionAllowed(
                183,
                0,
                1,
                state.copy(bodyPosition = null),
            )
        )
    }

    @Test
    fun `invalid ranges are rejected`() {
        assertFalse(SpoilerGuard.isPositionAllowed(-1, 0, 0, state))
        assertFalse(SpoilerGuard.isPositionAllowed(1, -1, 0, state))
        assertFalse(SpoilerGuard.isPositionAllowed(1, 10, 9, state))
    }

    @Test
    fun `boundary regression includes chapter position and lost precision`() {
        assertTrue(
            ReadingBoundaryGuard.hasRegressed(
                state,
                state.copy(chapterIndex = 182, bodyPosition = 99_999),
            )
        )
        assertTrue(
            ReadingBoundaryGuard.hasRegressed(
                state,
                state.copy(bodyPosition = 12_000),
            )
        )
        assertTrue(
            ReadingBoundaryGuard.hasRegressed(
                state,
                state.copy(bodyPosition = null),
            )
        )
    }

    @Test
    fun `forward reading boundary is accepted`() {
        assertFalse(
            ReadingBoundaryGuard.hasRegressed(
                state,
                state.copy(bodyPosition = 13_000),
            )
        )
        assertFalse(
            ReadingBoundaryGuard.hasRegressed(
                state,
                state.copy(chapterIndex = 184, bodyPosition = 10),
            )
        )
    }
}
