package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.api.chat.EnhancedAIService
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolBoundarySnapshotPolicyTest {
    @Test
    fun replayPrefixCannotOverwriteCompleteToolBoundarySnapshot() {
        val boundary = "完整的回复，然后调用工具"
        val snapshot =
            EnhancedAIService.ToolExecutionBoundarySnapshot(
                displayContent = boundary,
                replayCharCount = boundary.length,
            )

        assertEquals(boundary, preferToolBoundarySnapshot(snapshot, ""))
        assertEquals(boundary, preferToolBoundarySnapshot(snapshot, "完整的回复"))
        assertEquals(boundary, preferToolBoundarySnapshot(snapshot, boundary))
    }

    @Test
    fun replayContentTakesOverAfterItExtendsPastBoundary() {
        val boundary = "完整的回复，然后调用工具"
        val extended = "$boundary，随后继续回复"
        val snapshot =
            EnhancedAIService.ToolExecutionBoundarySnapshot(
                displayContent = boundary,
                replayCharCount = boundary.length,
            )

        assertEquals(extended, preferToolBoundarySnapshot(snapshot, extended))
        assertEquals("已回滚内容", preferToolBoundarySnapshot(null, "已回滚内容"))
    }

    @Test
    fun normalizedToolBoundaryReplacesRawReplayPrefixWithoutLosingLaterContent() {
        val rawBoundary = "说明\n<tool   name=\"read_file\" />"
        val normalizedBoundary = "说明\n<tool name=\"read_file\" />"
        val snapshot =
            EnhancedAIService.ToolExecutionBoundarySnapshot(
                displayContent = normalizedBoundary,
                replayCharCount = rawBoundary.length,
            )

        assertEquals(normalizedBoundary, preferToolBoundarySnapshot(snapshot, rawBoundary))
        assertEquals(
            "$normalizedBoundary\n工具执行后的回复",
            preferToolBoundarySnapshot(snapshot, "$rawBoundary\n工具执行后的回复"),
        )
    }

    @Test
    fun laterBoundaryKeepsEarlierToolResultsInCanonicalPrefix() {
        val firstRound = "A<tool name=\"first\"></tool>"
        val firstResult = "<tool_result name=\"first\">R1</tool_result>"
        val secondRound = "B<tool name=\"second\"></tool>"
        val canonicalBoundary = firstRound + firstResult + secondRound
        val snapshot =
            EnhancedAIService.ToolExecutionBoundarySnapshot(
                displayContent = canonicalBoundary,
                replayCharCount = canonicalBoundary.length,
            )

        assertEquals(
            canonicalBoundary + "<tool_result name=\"second\">R2</tool_result>C",
            preferToolBoundarySnapshot(
                snapshot,
                canonicalBoundary + "<tool_result name=\"second\">R2</tool_result>C",
            ),
        )
    }

}
