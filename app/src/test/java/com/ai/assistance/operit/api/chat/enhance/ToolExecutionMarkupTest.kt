package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
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
    fun imageResult_movesImageLinkOutsideStructuredToolResultForMultimodalInput() {
        val result =
            ToolResult(
                toolName = "read_file",
                success = true,
                result = StringResultData("""<link type="image" id="image-1"></link>"""),
            )

        val markup = ConversationMarkupManager.formatToolResultForMessage(result)
        val toolResultEnd = markup.indexOf("</tool_result")
        val imageLinkIndex = markup.indexOf("""<link type="image" id="image-1"></link>""")

        assertTrue(markup.contains("Image attached as multimodal input."))
        assertTrue(toolResultEnd >= 0)
        assertTrue(imageLinkIndex > toolResultEnd)
        assertEquals(
            1,
            Regex("""<link type="image" id="image-1"></link>""").findAll(markup).count(),
        )
    }

    @Test
    fun persistedImageResult_keepsImageWithToolResultBeforeNextUserTurn() {
        val toolResult =
            PromptTurn(
                kind = PromptTurnKind.TOOL_RESULT,
                content =
                    """<tool_result name="read_file" status="success"><content>Image attached as multimodal input.</content></tool_result>""",
            )
        val imageLink =
            PromptTurn(
                kind = PromptTurnKind.ASSISTANT,
                content = """<link type="image" id="image-1"></link>""",
            )
        val nextUserTurn = PromptTurn(kind = PromptTurnKind.USER, content = "What color is it?")

        val rebuilt =
            mergeToolResultImageLinksForHistory(listOf(toolResult, imageLink, nextUserTurn))

        assertEquals(2, rebuilt.size)
        assertEquals(PromptTurnKind.TOOL_RESULT, rebuilt[0].kind)
        assertTrue(rebuilt[0].content.startsWith("<tool_result"))
        assertTrue(rebuilt[0].content.endsWith("""<link type="image" id="image-1"></link>"""))
        assertEquals(nextUserTurn, rebuilt[1])
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
    fun resultAccumulator_preservesInterruptTurnAcrossResults() {
        val accumulator = ToolExecutionManager.BoundedToolResultAccumulator()
        accumulator.add(result(text = "one"))
        assertFalse(accumulator.anyInterruptTurn)

        accumulator.add(result(text = "two", interruptTurn = true))
        assertTrue("任一结果置位后聚合结果必须保留 interruptTurn", accumulator.anyInterruptTurn)

        accumulator.add(result(text = "three"))
        assertTrue("后续普通结果不得清除已置位的 interruptTurn", accumulator.anyInterruptTurn)
    }

    @Test
    fun resultAccumulator_keepsInterruptTurnFalseWhenNoResultInterrupts() {
        val accumulator = ToolExecutionManager.BoundedToolResultAccumulator()
        accumulator.add(result(text = "one"))
        accumulator.add(result(text = "two", success = false, error = "failed"))
        assertFalse(accumulator.anyInterruptTurn)
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
        interruptTurn: Boolean = false,
    ) =
        ToolResult(
            toolName = "test",
            success = success,
            result = StringResultData(text),
            error = error,
            interruptTurn = interruptTurn,
        )
}
