package com.ai.assistance.operit.features.reading

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-source contracts for the manual-only generation boundary.  These checks keep accidental
 * reintroduction of the old direct-summary/background scheduling paths visible without requiring
 * a live Legado provider or an API model.
 */
class ReadingCompanionManualFlowStaticTest {
    private fun source(relativePath: String): String = File(relativePath).readText()

    @Test
    fun `automatic indexing has no summary model call or knowledge-only rescheduling`() {
        val service =
            source(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionService.kt",
            )
        assertFalse(service.contains("modelGateway.summarizeChapter("))
        assertFalse(
            service.contains("scheduleMore && (remainingText > 0 || remainingKnowledge > 0)"),
        )
        assertTrue(service.contains("if (scheduleMore && remainingText > 0)"))
    }

    @Test
    fun `bridge defaults chapter summary lookup to no generation`() {
        val bridge =
            source(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionBridge.kt",
            )
        assertTrue(bridge.contains("optBoolean(\"generate_if_missing\", false)"))
        assertTrue(bridge.contains("\"manual_batch_summaries\""))
        assertTrue(bridge.contains("\"auto_commentary_manual_batch\""))
    }

    @Test
    fun `summary-only subagent path has a dedicated terminal submit`() {
        val coordinator =
            source(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionSubagentCoordinator.kt",
            )
        val tools =
            source(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionSubagentTools.kt",
            )
        assertTrue(coordinator.contains("summaryOnly"))
        assertTrue(coordinator.contains("TOOL_SUBMIT_SUMMARY"))
        assertTrue(tools.contains("session.summaryOnly"))
        assertTrue(tools.contains("\"summary_submitted\""))
    }

}
