package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.data.model.ChatMessageProcessMetadata
import org.junit.Assert.*
import org.junit.Test

class TranscriptStructureTest {
    @Test fun retainedPageKeepsItsLoadedProcessButDropsEvictedTurn() {
        val structure = TranscriptStructure(listOf(
            row(1), row(2, mode = "NORMAL", completedAt = 500),
            row(3, sentAt = 20), row(4, mode = "NORMAL", sentAt = 20, completedAt = 600),
        ))
        assertEquals(setOf(1L, 2L), structure.retainedTimestamps(setOf(2L), listOf(1L, 2L, 3L, 4L)))
        assertEquals(setOf(3L, 4L), structure.retainedTimestamps(setOf(4L), listOf(1L, 2L, 3L, 4L)))
    }
    private fun row(
        timestamp: Long,
        sender: String = "ai",
        mode: String = "ASSISTANT_INTERMEDIATE",
        sentAt: Long = 10,
        completedAt: Long = 0,
    ) = ChatMessageProcessMetadata(timestamp, sender, mode, sentAt, completedAt, 20, 30)

    @Test fun completedTurnAlwaysLeavesFinalInTopLevel() {
        val rows = listOf(row(1, "user", "NORMAL")) +
            (2L..200L).map { row(it) } + row(201, mode = "NORMAL", completedAt = 500)
        val structure = TranscriptStructure(rows)
        assertEquals(listOf(1L, 201L), structure.topLevelRows.map { it.timestamp })
        assertEquals(201L, structure.turnByTimestamp.getValue(50).finalTimestamp)
        assertEquals(200, structure.turns.single().memberTimestamps.size)
    }

    @Test fun humanSteeringRemainsOutsideProcessButAgentMailBelongsInside() {
        val rows = listOf(
            row(1, "user", "COLLABORATION_TASK"),
            row(2),
            row(3, "user", "NORMAL"),
            row(4, "user", "COLLABORATION_EVENT"),
            row(5, mode = "NORMAL", completedAt = 500),
        )
        val structure = TranscriptStructure(rows)
        assertEquals(listOf(3L, 5L), structure.topLevelRows.map { it.timestamp })
        assertFalse(structure.turns.single().memberTimestamps.contains(3))
    }

    @Test fun unfinishedAndDifferentTurnsAreNotHidden() {
        val unfinished = listOf(row(1), row(2, mode = "NORMAL"))
        assertEquals(unfinished, TranscriptStructure(unfinished).topLevelRows)
        val different = listOf(row(1), row(2, mode = "NORMAL", sentAt = 20, completedAt = 500))
        assertEquals(different, TranscriptStructure(different).topLevelRows)
    }
}
