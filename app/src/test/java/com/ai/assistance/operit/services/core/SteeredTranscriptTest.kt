package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.data.model.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class SteeredTranscriptTest {
    @Test fun rollbackAfterSteeringKeepsCanonicalPrefixAndNewReply() {
        val rawPrefix = "first<tool_result>result</tool_result>second"
        val canonicalPrefix = "first<tool_result>result</tool_result>\nsecond"
        val boundary = com.ai.assistance.operit.api.chat.EnhancedAIService.ToolExecutionBoundarySnapshot(
            canonicalPrefix, rawPrefix.length,
        )
        val tracker = com.ai.assistance.operit.util.stream.TextStreamRevisionTracker()
        tracker.append(rawPrefix)
        tracker.savepoint("new-response")
        tracker.append("discarded response")
        tracker.rollback("new-response")
        tracker.append("complete replacement")
        val merged = preferToolBoundarySnapshot(boundary, tracker.currentContent().toString())
        val transcript = SteeredTranscript()
        transcript.add(canonicalPrefix, 3, 1)
        val rows = transcript.project(ChatMessage(sender = "ai", timestamp = 1, content = merged))
        assertEquals(listOf(canonicalPrefix, "complete replacement"), rows.map { it.content })
        assertEquals(listOf(1L, 3L), rows.map { it.timestamp })
    }
    @Test fun multipleSteersKeepAssistantSegmentsAfterTheirUserMessages() {
        val transcript = SteeredTranscript()
        transcript.add("tool call\n<tool_result>done</tool_result>", 3, 1)
        transcript.add("tool call\n<tool_result>done</tool_result>\nsecond reply", 5, 1)
        val rows = transcript.project(ChatMessage(
            sender = "ai", timestamp = 1,
            content = "tool call\n<tool_result>done</tool_result>\nsecond reply\nlast reply",
        ))
        assertEquals(listOf(1L, 3L, 5L), rows.map { it.timestamp })
        assertEquals(5L, transcript.finalTimestamp(1))
        assertEquals(10L, transcript.finalTimestamp(10))
        assertEquals(listOf("tool call\n<tool_result>done</tool_result>", "\nsecond reply", "\nlast reply"),
            rows.map { it.content })
    }

    @Test fun staleReplaySnapshotCannotOverwriteSealedDisplayOrNewAssistant() {
        val transcript = SteeredTranscript()
        transcript.add("first\nsecond", 3, 1)
        val rows = transcript.project(ChatMessage(sender = "ai", timestamp = 1, content = "firstsecond"))
        assertEquals(1, rows.size)
        assertEquals("first\nsecond", rows.single().content)
        assertEquals(1L, rows.single().timestamp)
    }

    @Test fun aggregateUsageIsAssignedOnlyToTheLastAssistantSegment() {
        val transcript = SteeredTranscript()
        transcript.add("first", 3, 1)
        val rows = transcript.project(ChatMessage(
            sender = "ai", timestamp = 1, content = "firstsecond", inputTokens = 100, outputTokens = 20,
        ))
        assertEquals(100, rows.sumOf { it.inputTokens })
        assertEquals(20, rows.sumOf { it.outputTokens })
        assertEquals(0, rows.first().outputTokens)
    }

    @Test fun anotherResponseIsNotSplitUsingAPreviousTurnsBoundaries() {
        val transcript = SteeredTranscript()
        transcript.add("first", 3, 1)
        val regenerated = ChatMessage(sender = "ai", timestamp = 10, content = "new response")
        assertEquals(listOf(regenerated), transcript.project(regenerated))
    }
}
