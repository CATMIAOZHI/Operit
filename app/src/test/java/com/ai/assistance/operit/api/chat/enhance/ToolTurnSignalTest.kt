package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolTurnSignalTest {
    private fun result(success: Boolean, interrupt: Boolean) =
        ToolResult("submit", success, StringResultData(""), interruptTurn = interrupt)

    @Test
    fun `successful submission finishes while retryable validation failure continues`() {
        assertEquals(ToolTurnSignal.COMPLETE, resolveToolTurnSignal(listOf(result(true, true))))
        assertEquals(ToolTurnSignal.CONTINUE, resolveToolTurnSignal(listOf(result(false, false))))
        assertEquals(ToolTurnSignal.CONTINUE, resolveToolTurnSignal(listOf(result(true, false))))
    }

    @Test
    fun `permission interruption wins over a successful completion in either order`() {
        val completed = result(true, true)
        val denied = result(false, true)
        assertEquals(ToolTurnSignal.INTERRUPTED, resolveToolTurnSignal(listOf(completed, denied)))
        assertEquals(ToolTurnSignal.INTERRUPTED, resolveToolTurnSignal(listOf(denied, completed)))
    }

    @Test
    fun `a failed finishing tool goes back to the model only a few times`() {
        val failed = listOf(result(false, false))
        assertEquals(true, terminalToolMayRetry(failed, 0))
        assertEquals(true, terminalToolMayRetry(failed, TERMINAL_TOOL_RETRY_LIMIT - 1))
        assertEquals(false, terminalToolMayRetry(failed, TERMINAL_TOOL_RETRY_LIMIT))
        // A saved result, or a tool that asked to stop, ends the turn as before.
        assertEquals(false, terminalToolMayRetry(listOf(result(true, false)), 0))
        assertEquals(false, terminalToolMayRetry(listOf(result(false, true)), 0))
        assertEquals(false, terminalToolMayRetry(emptyList(), 0))
    }
}
