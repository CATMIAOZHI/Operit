package com.ai.assistance.operit.ui.features.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitChatTimeRowsTest {
    private val minute = 60_000L

    @Test fun aTimeShowsOnTheFirstMessageAndAfterAPause() {
        val rows = listOf(
            "a" to 0L,
            "a2" to 0L, // a second row of the same message
            "b" to 2 * minute,
            "hidden" to null, // a row without a message
            "c" to 8 * minute, // 6 minutes after b
            "d" to 8 * minute + 5, // a split reply, saved right after
        )
        assertEquals(mapOf("a" to 0L, "c" to 8 * minute), orbitChatTimeRows(rows))
    }
}
