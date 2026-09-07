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
}
