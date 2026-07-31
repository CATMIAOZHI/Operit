package com.ai.assistance.operit.ui.features.chat.components.part

import com.ai.assistance.operit.core.tools.ToolExecutionTimingSnapshot
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.ToolExecutionState
import com.ai.assistance.operit.ui.common.markdown.toolInvocationIndexAt
import com.ai.assistance.operit.util.markdown.MarkdownNodeStable
import com.ai.assistance.operit.util.markdown.MarkdownProcessorType
import com.ai.assistance.operit.util.stream.StreamLogger
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionPresentationTest {
    @Test
    fun reversedSameNameResults_areMatchedByInvocationIndex() {
        val content =
            """
            <tool name="read_file"><param name="path">/a</param></tool>
            <tool name="read_file"><param name="path">/b</param></tool>
            <tool_result_x name="read_file" status="success" call_id="b" invocation_index="1" duration_ms="2200" execution_state="completed" final="true"><content>B</content></tool_result_x>
            <tool_result_y name="read_file" status="success" call_id="a" invocation_index="0" duration_ms="800" execution_state="completed" final="true"><content>A</content></tool_result_y>
            """.trimIndent()

        val executions = parsePersistedToolExecutions(content)

        assertEquals(800L, executions.getValue(0).durationMs)
        assertEquals("a", executions.getValue(0).callId)
        assertEquals("A", executions.getValue(0).resultText)
        assertEquals(2_200L, executions.getValue(1).durationMs)
        assertEquals("b", executions.getValue(1).callId)
        assertEquals("B", executions.getValue(1).resultText)
    }

    @Test
    fun notExecutedResult_hasNoSyntheticDuration() {
        val content =
            """<tool_result_x name="write_file" status="error" call_id="a" invocation_index="0" execution_state="not_executed" final="true"><content><error>User cancelled</error></content></tool_result_x>"""

        val execution = parsePersistedToolExecutions(content).getValue(0)

        assertEquals(ToolExecutionState.NOT_EXECUTED, execution.state)
        assertNull(execution.durationMs)
    }

    @Test
    fun toolOrdinal_countsOnlyEarlierToolRequests() {
        val nodes =
            listOf(
                node("<think>plan</think>"),
                node("""<tool name="read_file"></tool>"""),
                node("""<tool_result_x name="read_file"></tool_result_x>"""),
                node("""<tool name="read_file"></tool>"""),
            )

        assertNull(toolInvocationIndexAt(nodes, 0))
        assertEquals(0, toolInvocationIndexAt(nodes, 1))
        assertNull(toolInvocationIndexAt(nodes, 2))
        assertEquals(1, toolInvocationIndexAt(nodes, 3))
    }

    @Test
    fun legacyResult_bodyMetadataTextDoesNotHideStandaloneResult() {
        val content =
            """<tool_result name="read_file" status="success"><content>source contains invocation_index="2"</content></tool_result>"""

        assertTrue(shouldRenderStandaloneToolResult(content))
    }

    @Test
    fun onlyValidFinalTimedResultHidesStandaloneResult() {
        assertFalse(
            shouldRenderStandaloneToolResult(
                """<tool_result_x name="read_file" status="success" invocation_index="0" final="true"><content>A</content></tool_result_x>"""
            )
        )
        assertTrue(
            shouldRenderStandaloneToolResult(
                """<tool_result_x name="read_file" status="success" invocation_index="0"><content>A</content></tool_result_x>"""
            )
        )
        assertTrue(
            shouldRenderStandaloneToolResult(
                """<tool_result_x name="read_file" status="success" invocation_index="-1" final="true"><content>A</content></tool_result_x>"""
            )
        )
    }

    @Test
    fun persistedCallIdentityRejectsSnapshotFromAnotherVariant() {
        val live =
            ToolExecutionTimingSnapshot(
                callId = "new-call",
                toolName = "read_file",
                state = ToolExecutionState.COMPLETED,
            )
        val currentPersisted = persistedExecution(callId = "new-call")
        val oldPersisted = persistedExecution(callId = "old-call")

        assertSame(
            live,
            resolveLiveToolExecution(
                liveExecution = live,
                persistedExecution = null,
                allowUnmatchedLiveExecution = true,
            ),
        )
        assertNull(
            resolveLiveToolExecution(
                liveExecution = live,
                persistedExecution = null,
                allowUnmatchedLiveExecution = false,
            )
        )
        assertSame(
            live,
            resolveLiveToolExecution(
                liveExecution = live,
                persistedExecution = currentPersisted,
                allowUnmatchedLiveExecution = false,
            ),
        )
        assertNull(
            resolveLiveToolExecution(
                liveExecution = live,
                persistedExecution = oldPersisted,
                allowUnmatchedLiveExecution = true,
            )
        )
    }

    @Test
    fun invalidatedToolReservation_keepsLaterExecutionAlignedWithRenderedOrdinal() = runBlocking {
        val nextInvocationIndex = AtomicInteger(0)
        val invalidatedContent =
            """
            <think>Example only: <tool name="nested_thought"/></think>
            <tool name="invalidated_complete"><param name="content"><![CDATA[<tool name="nested_example"/>]]></param></tool>
            <tool name="invalidated_self_closing"/>
            ```xml
            <tool name="fenced_example"></tool>
            ```
            <tool name="invalidated_repaired"></tool>
            """.trimIndent()
        val invalidatedInvocationCount =
            try {
                StreamLogger.setEnabled(false)
                ToolExecutionManager.countDisplayedToolInvocations(invalidatedContent)
            } finally {
                StreamLogger.setEnabled(true)
            }
        ToolExecutionManager.reserveToolInvocationIndices(
            nextInvocationIndex,
            invalidatedInvocationCount,
        )
        val validInvocationIndex = nextInvocationIndex.getAndIncrement()
        val nodes =
            listOf(
                node("""<think>Example only: <tool name="nested_thought"/></think>"""),
                node("""<tool name="invalidated_complete"><param name="content"><![CDATA[<tool name="nested_example"/>]]></param></tool>"""),
                node("""<tool name="invalidated_self_closing"/>""", MarkdownProcessorType.PLAIN_TEXT),
                node("""<tool name="fenced_example"></tool>""", MarkdownProcessorType.CODE_BLOCK),
                node("""<tool name="invalidated_repaired"></tool>"""),
                node("""<tool name="read_file"></tool>"""),
            )

        assertEquals(2, invalidatedInvocationCount)
        assertEquals(validInvocationIndex, toolInvocationIndexAt(nodes, 5))
        assertEquals(2, validInvocationIndex)
    }

    private fun node(
        content: String,
        type: MarkdownProcessorType = MarkdownProcessorType.XML_BLOCK,
    ) =
        MarkdownNodeStable(
            type = type,
            content = content,
            children = emptyList(),
        )

    private fun persistedExecution(callId: String) =
        PersistedToolExecution(
            callId = callId,
            toolName = "read_file",
            state = ToolExecutionState.COMPLETED,
            durationMs = 100L,
            success = true,
            resultText = "result",
        )
}
