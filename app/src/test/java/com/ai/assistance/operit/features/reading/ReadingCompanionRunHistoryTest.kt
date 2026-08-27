package com.ai.assistance.operit.features.reading

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingCompanionRunHistoryTest {
    @Test
    fun stageEventReportsPersistedDuration() {
        val event = AutoCommentRunStageEvent(
            id = 4L,
            runId = 9L,
            stage = AutoCommentRunStages.WAITING_MODEL,
            startedAt = 1_000L,
            finishedAt = 4_250L,
        )

        assertEquals(3_250L, event.durationMs)
    }

    @Test
    fun stageOrderingIncludesTerminalCompletionStage() {
        assertEquals(
            listOf(
                AutoCommentRunStages.READING_TARGET,
                AutoCommentRunStages.PREPARING_CONTEXT,
                AutoCommentRunStages.RESOLVING_MODEL,
                AutoCommentRunStages.WAITING_MODEL,
                AutoCommentRunStages.VALIDATING_RESPONSE,
                AutoCommentRunStages.SAVING_COMMENTS,
                AutoCommentRunStages.COMPLETED,
            ),
            AutoCommentRunStages.ordered,
        )
    }

    @Test
    fun immutableCommentSnapshotRetainsAnchorAndRoleAfterRegeneration() {
        val snapshot = AutoCommentRunCommentSnapshot(
            id = 1L,
            runId = 7L,
            bookId = "book",
            chapterIndex = 2,
            chapterTitle = "第三章",
            contentHash = "hash",
            paragraphIndex = 18,
            text = "坏了",
            kind = "reaction",
            roleCardId = "rainy",
            roleCardName = "Rainy",
            evidenceJson = """{"anchorId":"p0019","evidenceIds":["p0019"]}""",
            createdAt = 10L,
        )

        assertEquals(7L, snapshot.runId)
        assertEquals(2, snapshot.chapterIndex)
        assertEquals(18, snapshot.paragraphIndex)
        assertEquals("坏了", snapshot.text)
        assertEquals("Rainy", snapshot.roleCardName)
        assertEquals("""{"anchorId":"p0019","evidenceIds":["p0019"]}""", snapshot.evidenceJson)
    }

    @Test
    fun traceEventReportsPersistedDurationAndOperation() {
        val event = AutoCommentRunTraceEvent(
            id = 2L,
            runId = 7L,
            operation = "model_direct_call",
            status = "completed",
            startedAt = 2_000L,
            finishedAt = 6_500L,
            metadataJson = """{"availableTools":[]}""",
        )

        assertEquals(4_500L, event.durationMs)
        assertEquals("model_direct_call", event.operation)
        assertEquals("""{"availableTools":[]}""", event.metadataJson)
    }
}
