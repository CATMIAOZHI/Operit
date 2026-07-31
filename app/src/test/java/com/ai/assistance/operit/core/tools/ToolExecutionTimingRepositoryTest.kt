package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolExecutionState
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolExecutionTimingRepositoryTest {
    @Test
    fun sameNameInvocations_keepIndependentStatesAndDurations() {
        val scopeId = "same-name-${System.nanoTime()}"
        val first = invocation(callId = "first", invocationIndex = 0)
        val second = invocation(callId = "second", invocationIndex = 1)

        ToolExecutionTimingRepository.register(scopeId, first)
        ToolExecutionTimingRepository.register(scopeId, second)
        ToolExecutionTimingRepository.markWaitingExecution(scopeId, first)
        ToolExecutionTimingRepository.markRunning(scopeId, first, startedAtElapsedMs = 100L)
        ToolExecutionTimingRepository.markFinished(
            scopeId = scopeId,
            invocation = first,
            result = result("A"),
            durationMs = 800L,
            state = ToolExecutionState.COMPLETED,
        )

        val firstSnapshot = ToolExecutionTimingRepository.get(scopeId, 0)
        val secondSnapshot = ToolExecutionTimingRepository.get(scopeId, 1)

        assertEquals("first", firstSnapshot?.callId)
        assertEquals(ToolExecutionState.COMPLETED, firstSnapshot?.state)
        assertEquals(800L, firstSnapshot?.durationMs)
        assertEquals("A", firstSnapshot?.resultText)
        assertEquals("second", secondSnapshot?.callId)
        assertEquals(ToolExecutionState.WAITING_EXECUTION, secondSnapshot?.state)
        assertNull(secondSnapshot?.durationMs)
    }

    @Test
    fun finishedPayloads_areBoundedAndScopeCanBeCleared() {
        val scopeId = "bounded-${System.nanoTime()}"
        val invocation = invocation(callId = "bounded", invocationIndex = 0)
        val oversizedText = "r".repeat(ToolExecutionLimits.MAX_TEXT_RESULT_LENGTH + 100)
        val oversizedError = "e".repeat(ToolExecutionLimits.MAX_TEXT_RESULT_LENGTH + 100)

        ToolExecutionTimingRepository.register(scopeId, invocation)
        ToolExecutionTimingRepository.markFinished(
            scopeId = scopeId,
            invocation = invocation,
            result =
                ToolResult(
                    toolName = "read_file",
                    success = false,
                    result = StringResultData(oversizedText),
                    error = oversizedError,
                ),
            durationMs = 100L,
            state = ToolExecutionState.COMPLETED,
        )

        val snapshot = ToolExecutionTimingRepository.get(scopeId, 0)
        assertEquals(ToolExecutionLimits.MAX_TEXT_RESULT_LENGTH, snapshot?.resultText?.length)
        assertEquals(ToolExecutionLimits.MAX_TEXT_RESULT_LENGTH, snapshot?.errorText?.length)

        ToolExecutionTimingRepository.clearScope(scopeId)

        assertNull(ToolExecutionTimingRepository.get(scopeId, 0))
    }

    @Test
    fun finishedProxyCall_usesResolvedResultName() {
        val scopeId = "proxy-${System.nanoTime()}"
        val invocation =
            ToolInvocation(
                tool = AITool(name = "package_proxy"),
                rawText = """<tool name="package_proxy"></tool>""",
                responseLocation = 0..0,
                callId = "proxy-call",
                invocationIndex = 0,
            )

        ToolExecutionTimingRepository.register(scopeId, invocation)
        ToolExecutionTimingRepository.markFinished(
            scopeId = scopeId,
            invocation = invocation,
            result =
                ToolResult(
                    toolName = "demo:actual_tool",
                    success = true,
                    result = StringResultData("result"),
                ),
            durationMs = 100L,
            state = ToolExecutionState.COMPLETED,
        )

        assertEquals("demo:actual_tool", ToolExecutionTimingRepository.get(scopeId, 0)?.toolName)
        ToolExecutionTimingRepository.clearScope(scopeId)
    }

    private fun invocation(callId: String, invocationIndex: Int) =
        ToolInvocation(
            tool = AITool(name = "read_file"),
            rawText = """<tool name="read_file"></tool>""",
            responseLocation = 0..0,
            callId = callId,
            invocationIndex = invocationIndex,
        )

    private fun result(text: String) =
        ToolResult(
            toolName = "read_file",
            success = true,
            result = StringResultData(text),
        )
}
