package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.ToolExecutionState
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionMarkupTest {
    @Test
    fun finalResult_persistsInvocationIdentityAndDuration() {
        val result =
            ToolResult(
                toolName = "read_file",
                success = true,
                result = StringResultData("file contents"),
                callId = "call-2",
                invocationIndex = 2,
                executionDurationMs = 1_234L,
                executionState = ToolExecutionState.COMPLETED,
                isFinal = true,
            )

        val markup = ConversationMarkupManager.formatToolResultForMessage(result)

        assertTrue(markup.contains("""call_id="call-2""""))
        assertTrue(markup.contains("""invocation_index="2""""))
        assertTrue(markup.contains("""duration_ms="1234""""))
        assertTrue(markup.contains("""execution_state="completed""""))
        assertTrue(markup.contains("""final="true""""))
        assertTrue(markup.contains("file contents"))
    }

    @Test
    fun modelNormalization_removesOnlyExecutionMetadata() {
        val markup =
            """<tool_result_abc name="read_file" status="success" call_id="call-2" invocation_index="2" duration_ms="1234" execution_state="completed" final="true"><content>file contents</content></tool_result_abc>"""

        val normalized = stripToolExecutionMetadataFromToolResults(markup)

        assertFalse(normalized.contains("call_id"))
        assertFalse(normalized.contains("invocation_index"))
        assertFalse(normalized.contains("duration_ms"))
        assertFalse(normalized.contains("execution_state"))
        assertFalse(normalized.contains("""final="true""""))
        assertTrue(normalized.contains("""name="read_file""""))
        assertTrue(normalized.contains("""status="success""""))
        assertTrue(normalized.contains("<content>file contents</content>"))
    }

    @Test
    fun resultAccumulator_boundsOutputAndKeepsLastResultMetadata() {
        val first = result(text = "first")
        val second = result(text = "second", success = false, error = "failed")
        val accumulator = ToolExecutionManager.BoundedToolResultAccumulator(maxChars = 16)

        accumulator.add(first)
        accumulator.add(second)

        assertEquals("first\nStep error", accumulator.combinedResultText())
        assertFalse(requireNotNull(accumulator.lastResultSuccess))
        assertEquals("failed", accumulator.lastResultError)
        assertEquals(2, accumulator.resultCount)
    }

    @Test
    fun cancelledResult_preservesAccumulatedOutput() {
        val invocation =
            ToolInvocation(
                tool = AITool(name = "terminal"),
                rawText = """<tool name="terminal"></tool>""",
                responseLocation = 0..0,
                callId = "call-cancelled",
                invocationIndex = 3,
            )

        val cancelled =
            ToolExecutionManager.createCancelledToolResult(
                displayToolName = "terminal",
                invocation = invocation,
                durationMs = 250L,
                partialResultText = "partial output",
            )

        assertFalse(cancelled.success)
        assertEquals("partial output", cancelled.result.toString())
        assertEquals("Tool execution cancelled.", cancelled.error)
        assertEquals("call-cancelled", cancelled.callId)
        assertEquals(3, cancelled.invocationIndex)
        assertEquals(250L, cancelled.executionDurationMs)
        assertEquals(ToolExecutionState.COMPLETED, cancelled.executionState)
        assertTrue(cancelled.isFinal)
    }

    private fun result(
        text: String,
        success: Boolean = true,
        error: String? = null,
    ) =
        ToolResult(
            toolName = "test",
            success = success,
            result = StringResultData(text),
            error = error,
        )
}
