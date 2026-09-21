package com.ai.assistance.operit.api.chat.library

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MemoryLearningOutcomeTest {
    @Test fun `natural completion does not require a finish tool`() {
        assertEquals("success", memoryLearningFinalStatus(null, 3, 0))
    }
    @Test fun `tool errors remain auditable without marking a completed run failed`() {
        assertEquals("warnings", memoryLearningFinalStatus(null, 3, 1))
    }
    @Test fun `failure after writes is distinguishable from no writes and cancellation`() {
        assertEquals("partial", memoryLearningFinalStatus(IllegalStateException(), 3, 1))
        assertEquals("failed", memoryLearningFinalStatus(IllegalStateException(), 0, 1))
        assertEquals("cancelled", memoryLearningFinalStatus(CancellationException(), 3, 1))
    }
    @Test fun `failure detail includes actionable validation message`() {
        assertEquals("skill_create: IllegalArgumentException: underscores are not allowed",
            learningFailureDetail("skill_create", IllegalArgumentException("underscores are not allowed")))
    }
}
