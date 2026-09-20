package com.ai.assistance.operit.core.tools.system

import com.ai.assistance.operit.terminal.CommandExecutionEvent
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.data.TerminalState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class TerminalCommandFlowTest {
    @Test
    fun `immediate completion is subscribed before command submission`() = runBlocking {
        val events = MutableSharedFlow<CommandExecutionEvent>(extraBufferCapacity = 1)
        val manager = mock<TerminalManager>()
        whenever(manager.commandExecutionEvents).thenReturn(events)
        whenever(manager.terminalState).thenReturn(MutableStateFlow(TerminalState()))
        whenever(manager.sendCommandToSession("s", "true", "a")).thenAnswer {
            assertEquals(1, events.subscriptionCount.value)
            assertTrue(events.tryEmit(CommandExecutionEvent("a", "s", "done", true)))
            "a"
        }
        val result = withTimeout(1_000) {
            Terminal(manager).executeCommandFlow("s", "true", "a").toList()
        }
        assertEquals("done", result.single().outputChunk)
        verify(manager, never()).cancelCommand(any(), any(), any())
    }

    @Test
    fun `caller cancellation cleans up the exact command even after parent job is cancelled`() = runBlocking {
        val events = MutableSharedFlow<CommandExecutionEvent>()
        val submitted = CompletableDeferred<Unit>()
        val manager = mock<TerminalManager>()
        whenever(manager.commandExecutionEvents).thenReturn(events)
        whenever(manager.terminalState).thenReturn(MutableStateFlow(TerminalState()))
        whenever(manager.sendCommandToSession("s", "less file", "a")).thenAnswer {
            submitted.complete(Unit)
            "a"
        }
        val job = launch {
            Terminal(manager).executeCommandFlow("s", "less file", "a").collect()
        }
        withTimeout(1_000) {
            submitted.await()
            job.cancelAndJoin()
        }
        verify(manager).cancelCommand("s", "a", 3_000L)
        assertEquals(0, events.subscriptionCount.value)
    }
}
