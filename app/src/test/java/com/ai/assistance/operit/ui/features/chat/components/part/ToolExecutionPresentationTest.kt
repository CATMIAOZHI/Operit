package com.ai.assistance.operit.ui.features.chat.components.part

import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutionLimits
import com.ai.assistance.operit.core.tools.ToolExecutionTimingRepository
import com.ai.assistance.operit.core.tools.ToolExecutionTimingSnapshot
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.ToolExecutionState
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.ui.common.markdown.toolInvocationIndices
import com.ai.assistance.operit.util.markdown.MarkdownNodeStable
import com.ai.assistance.operit.util.markdown.MarkdownProcessorType
import com.ai.assistance.operit.util.stream.StreamLogger
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionPresentationTest {
    @Test
    fun steeredAssistantUsesNewScopeAndMessageLocalResultIndices() {
        val sequence = com.ai.assistance.operit.core.chat.AssistantToolSequence("before")
        repeat(4) { sequence.nextIndex.getAndIncrement() }
        sequence.startMessage("after")
        assertEquals("after", sequence.scopeId)
        val index = sequence.nextIndex.getAndIncrement()
        val result = ToolResult(
            toolName = "grep_code", success = true, result = StringResultData("matching lines"),
            callId = "after-call", invocationIndex = index, isFinal = true,
            executionState = ToolExecutionState.COMPLETED,
        )
        val markup = com.ai.assistance.operit.api.chat.enhance.ConversationMarkupManager
            .formatToolResultForMessage(result)
        val content = """<tool name="grep_code"></tool>""" + markup
        assertEquals(0, index)
        assertEquals("matching lines", parsePersistedToolExecutions(content).getValue(0).resultText)
        assertFalse(shouldRenderStandaloneToolResult(markup))
        sequence.startMessage("third")
        assertEquals(0, sequence.nextIndex.getAndIncrement())
        assertEquals("third", sequence.scopeId)
    }

    @Test
    fun previouslyStoredSteeredResultsAreMatchedWithoutChangingMessageText() {
        val content = """
            <tool name="grep_code"></tool>
            <tool name="grep_code"></tool>
            <tool_result name="grep_code" final="true" status="success" invocation_index="5"><content>B</content></tool_result>
            <tool_result name="grep_code" final="true" status="success" invocation_index="4"><content>A</content></tool_result>
        """.trimIndent()
        val executions = parsePersistedToolExecutions(content)
        assertEquals("A", executions.getValue(0).resultText)
        assertEquals("B", executions.getValue(1).resultText)
    }

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
    fun timedFileResult_keepsStructuredDiffPresentation() {
        val result =
            """
            [android] Updated file
            <file-diff details="Updated file" path="/workspace/src/Main.kt"><![CDATA[
            @@
            -old
            +new
            ]]></file-diff>
            """.trimIndent()

        val diff =
            requireNotNull(
                parseFileDiffResult(
                    toolName = "edit_file",
                    isSuccess = true,
                    result = result,
                )
            )

        assertEquals("/workspace/src/Main.kt", diff.path)
        assertEquals("Updated file", diff.details)
        assertEquals("@@\n-old\n+new", diff.diffContent)
    }

    @Test
    fun timedFileResult_includesDurationOnlyOnceInSummary() {
        assertEquals(
            "1.2 秒 · 3 insertions(+), 1 deletions(-)",
            buildFileDiffSummary(
                summaryPrefix = "1.2 秒",
                changeSummary = "3 insertions(+), 1 deletions(-)",
            ),
        )
    }

    @Test
    fun longFinishedFileResult_keepsFullTextForStreamingDiffDisplay() {
        val scopeId = "test-scope-${System.nanoTime()}"
        try {
            val bigDiff =
                buildString {
                    append("""<file-diff details="Updated file" path="/workspace/src/Main.kt"><![CDATA[""")
                    repeat(2_000) { append("+line $it\n") }
                    append("]]></file-diff>")
                }
            assertTrue(bigDiff.length > ToolExecutionLimits.MAX_TEXT_RESULT_LENGTH)
            val invocation =
                ToolInvocation(
                    tool = AITool(name = "edit_file"),
                    rawText = """<tool name="edit_file"></tool>""",
                    responseLocation = 0..0,
                    callId = "call-1",
                    invocationIndex = 0,
                )
            ToolExecutionTimingRepository.register(scopeId, invocation)
            ToolExecutionTimingRepository.markFinished(
                scopeId,
                invocation,
                ToolResult(
                    toolName = "edit_file",
                    success = true,
                    result = StringResultData(bigDiff),
                ),
                durationMs = 2_000L,
                state = ToolExecutionState.COMPLETED,
            )

            // 流式窗口内 persisted 结果不可用，UI 依赖 live 快照展示结果文本；
            // 若 live 快照仍按 MAX_TEXT_RESULT_LENGTH 截断，<file-diff> 块会被切断，
            // 导致 diff 展示回退为普通工具结果。
            val snapshot = ToolExecutionTimingRepository.get(scopeId, 0)
            assertNotNull(snapshot)
            assertEquals(bigDiff, snapshot?.resultText)
            val diff =
                parseFileDiffResult(
                    toolName = "edit_file",
                    result = snapshot!!.resultText,
                    isSuccess = true,
                )
            assertNotNull(diff)
            assertEquals("/workspace/src/Main.kt", diff?.path)
            assertEquals("Updated file", diff?.details)
            assertEquals(true, diff?.diffContent?.endsWith("+line 1999"))
        } finally {
            ToolExecutionTimingRepository.clearScope(scopeId)
        }
    }

    @Test
    fun subagentTaskResult_extractsFullDecodedFinalText() {
        val result =
            """
            <task id="task-1" state="completed">
              <summary>Inspect auth</summary>
              <task_result>Found &lt;AuthManager&gt; &amp; its callers.
            Second line.</task_result>
            </task>
            """.trimIndent()

        assertEquals(
            "Found <AuthManager> & its callers.\nSecond line.",
            extractSubagentTaskResult(result),
        )
    }

    @Test
    fun subagentTaskRow_separatesAgentNameFromStatusSummary() {
        val content =
            buildSubagentTaskRowContent(
                agentName = "explore",
                durationText = "21.5 秒",
                statusText = "已完成 · 调用了 10 次工具",
            )

        assertEquals("explore", content.title)
        assertEquals("21.5 秒 · 已完成 · 调用了 10 次工具", content.summary)
    }

    @Test
    fun continuedSubagentCard_resolvesRunByStableTaskIdInsteadOfNewCallId() {
        assertEquals(
            SubagentRunLookup.TaskId(taskId = "task-original", parentChatId = "parent"),
            resolveSubagentRunLookup(
                requestedTaskId = "task-original",
                parentChatId = "parent",
                callId = "call-new",
            ),
        )
        assertEquals(
            SubagentRunLookup.ParentCall(parentChatId = "parent", callId = "call-first"),
            resolveSubagentRunLookup(
                requestedTaskId = null,
                parentChatId = "parent",
                callId = "call-first",
            ),
        )
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

        assertNull(toolInvocationIndices(nodes)[0])
        assertEquals(0, toolInvocationIndices(nodes)[1])
        assertNull(toolInvocationIndices(nodes)[2])
        assertEquals(1, toolInvocationIndices(nodes)[3])
    }

    @Test
    fun subagentToolDisplay_keepsLastInvocationWhileThinking() {
        assertEquals(
            "grep_code",
            resolveSubagentDisplayedTool(
                childProcessingState = InputProcessingState.Processing("Thinking"),
                lastToolName = "grep_code",
            ),
        )
    }

    @Test
    fun proxyTaskPresentation_usesForwardedSubagentMetadata() {
        val resolved =
            resolveToolRequestPresentation(
                rawToolName = "proxy",
                params =
                    mapOf(
                        "tool_name" to "task",
                        "params" to
                            """{&quot;subagent_type&quot;:&quot;explore&quot;,&quot;title&quot;:&quot;Trace auth&quot;,&quot;task_id&quot;:&quot;task-1&quot;}""",
                    ),
            )

        assertEquals("task", resolved.toolName)
        assertEquals("explore", resolved.forwardedParams["subagent_type"])
        assertEquals("Trace auth", resolved.forwardedParams["title"])
        assertEquals("task-1", resolved.forwardedParams["task_id"])
    }

    @Test
    fun proxyFileEditPresentation_usesResolvedToolName() {
        val resolved =
            resolveToolRequestPresentation(
                rawToolName = "proxy",
                params = mapOf("tool_name" to "edit_file", "params" to "{}"),
            )

        assertEquals("edit_file", resolved.toolName)
    }

    @Test
    fun toolOrdinal_ignoresMalformedToolRequests() {
        val nodes =
            listOf(
                node("""<tool><param name="path">/bad</param></tool>"""),
                node("""<tool name="read_file"><param name="path">/real</param></tool>"""),
            )

        assertNull(toolInvocationIndices(nodes)[0])
        assertEquals(0, toolInvocationIndices(nodes)[1])
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
    fun subagentToolDisplay_prefersCurrentToolOverPreviousInvocation() {
        assertEquals(
            "read_file",
            resolveSubagentDisplayedTool(
                childProcessingState = InputProcessingState.ExecutingTool("read_file"),
                lastToolName = "grep_code",
            ),
        )
    }

    @Test
    fun subagentToolDisplay_hasNoToolBeforeFirstInvocation() {
        assertNull(
            resolveSubagentDisplayedTool(
                childProcessingState = InputProcessingState.Processing("Thinking"),
                lastToolName = null,
            ),
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
    fun reloadedRunningTaskStillAcceptsItsExactLiveTiming() {
        val live =
            ToolExecutionTimingSnapshot(
                callId = "task-call",
                toolName = "task",
                state = ToolExecutionState.RUNNING,
            )

        assertTrue(shouldAllowUnmatchedTaskExecution("task", live))
        assertFalse(shouldAllowUnmatchedTaskExecution("read_file", live))

        val proxyWrappedTask = live.copy(toolName = "proxy")
        assertTrue(shouldAllowUnmatchedTaskExecution("task", proxyWrappedTask))
    }

    @Test
    fun reloadedInFlightCall_keepsLiveTimingWithoutPersistedResult() {
        val running =
            ToolExecutionTimingSnapshot(
                callId = "terminal-call",
                toolName = "super_admin:terminal",
                state = ToolExecutionState.RUNNING,
                startedAtElapsedMs = 1_000L,
            )

        // 静态重载（聊天切换后无流、无最终结果）时，运行中的调用仍应展示审核横幅与耗时。
        assertSame(
            running,
            resolveLiveToolExecution(
                liveExecution = running,
                persistedExecution = null,
                allowUnmatchedLiveExecution = false,
            ),
        )
        assertEquals(
            running.copy(state = ToolExecutionState.WAITING_EXECUTION),
            resolveLiveToolExecution(
                liveExecution = running.copy(state = ToolExecutionState.WAITING_EXECUTION),
                persistedExecution = null,
                allowUnmatchedLiveExecution = false,
            ),
        )
        assertEquals(
            running.copy(state = ToolExecutionState.WAITING_AUTHORIZATION),
            resolveLiveToolExecution(
                liveExecution = running.copy(state = ToolExecutionState.WAITING_AUTHORIZATION),
                persistedExecution = null,
                allowUnmatchedLiveExecution = false,
            ),
        )

        // 终止状态仍不允许无匹配快照，防止误配其他变体。
        assertNull(
            resolveLiveToolExecution(
                liveExecution = running.copy(state = ToolExecutionState.COMPLETED),
                persistedExecution = null,
                allowUnmatchedLiveExecution = false,
            )
        )
        assertNull(
            resolveLiveToolExecution(
                liveExecution = running.copy(state = ToolExecutionState.NOT_EXECUTED),
                persistedExecution = null,
                allowUnmatchedLiveExecution = false,
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
            <tool><param name="path">/malformed</param></tool>
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
                node("""<tool><param name="path">/malformed</param></tool>"""),
                node("""<tool name="invalidated_repaired"></tool>"""),
                node("""<tool name="read_file"></tool>"""),
            )

        assertEquals(2, invalidatedInvocationCount)
        assertEquals(validInvocationIndex, toolInvocationIndices(nodes)[6])
        assertEquals(2, validInvocationIndex)
    }

    @Test
    fun fileEditResults_useStructuredDiffPresentation() {
        listOf("apply_file", "create_file", "edit_file").forEach { toolName ->
            val diff =
                parseFileDiffResult(
                    toolName = toolName,
                    result =
                        """<file-diff path="src/Test.kt" details="updated"><![CDATA[+added
-removed]]></file-diff>""",
                    isSuccess = true,
                )

            assertNotNull(diff)
            assertEquals("src/Test.kt", diff?.path)
            assertEquals("updated", diff?.details)
            assertEquals("+added\n-removed", diff?.diffContent)
        }
        assertNull(
            parseFileDiffResult(
                toolName = "read_file",
                result = """<file-diff path="src/Test.kt"><![CDATA[+added]]></file-diff>""",
                isSuccess = true,
            )
        )
        assertNull(
            parseFileDiffResult(
                toolName = "apply_file",
                result = """<file-diff path="src/Test.kt"><![CDATA[+added]]></file-diff>""",
                isSuccess = false,
            )
        )
        // 尾部截断（截断点落在 CDATA 或 </file-diff> 内）时仍渲染可用的部分 diff。
        val truncatedMidCdata =
            parseFileDiffResult(
                toolName = "apply_file",
                result = """<file-diff path="src/Test.kt"><![CDATA[+truncated""",
                isSuccess = true,
            )
        assertNotNull(truncatedMidCdata)
        assertEquals("+truncated", truncatedMidCdata?.diffContent)
        val truncatedClosingTag =
            parseFileDiffResult(
                toolName = "apply_file",
                result = """<file-diff path="src/Test.kt"><![CDATA[+changed]]>""",
                isSuccess = true,
            )
        assertNotNull(truncatedClosingTag)
        assertEquals("+changed", truncatedClosingTag?.diffContent)
        assertNull(
            parseFileDiffResult(
                toolName = "apply_file",
                result = """<file-diff><![CDATA[+added]]></file-diff>""",
                isSuccess = true,
            )
        )
    }

    @Test
    fun longToolTranscriptBuildsInvocationIndicesInOnePass() {
        val tool = node("""<tool name="read_file"><param name="path">a</param></tool>""")
        val result = node("""<tool_result name="read_file"><content>done</content></tool_result>""")
        var reads = 0
        val nodes = object : AbstractList<MarkdownNodeStable>() {
            override val size = 2_000
            override fun get(index: Int): MarkdownNodeStable {
                reads++
                return if (index % 2 == 0) tool else result
            }
        }
        val indices = toolInvocationIndices(nodes)
        assertEquals(0, indices.first())
        assertEquals(999, indices[1998])
        assertNull(indices.last())
        assertEquals(nodes.size, reads)
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
