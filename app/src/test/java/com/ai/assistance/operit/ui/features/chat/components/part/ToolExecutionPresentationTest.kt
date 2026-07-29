package com.ai.assistance.operit.ui.features.chat.components.part

import com.ai.assistance.operit.data.model.ToolExecutionState
import com.ai.assistance.operit.ui.common.markdown.toolInvocationIndexAt
import com.ai.assistance.operit.util.markdown.MarkdownNodeStable
import com.ai.assistance.operit.util.markdown.MarkdownProcessorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("A", executions.getValue(0).resultText)
        assertEquals(2_200L, executions.getValue(1).durationMs)
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

    private fun node(content: String) =
        MarkdownNodeStable(
            type = MarkdownProcessorType.XML_BLOCK,
            content = content,
            children = emptyList(),
        )
}
