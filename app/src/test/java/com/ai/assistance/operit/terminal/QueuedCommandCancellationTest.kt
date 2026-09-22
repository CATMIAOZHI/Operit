package com.ai.assistance.operit.terminal

import android.util.Log
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.QueuedCommand
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Uses the app's Android/JDK-compatible mocking test runtime. */
class QueuedCommandCancellationTest {
    @Test
    fun `queued timeout does not wait for cancellation of running command`() = runBlocking {
        Mockito.mockStatic(Log::class.java).use {
            // Avoid the Android singleton's environment startup. These are the real manager
            // methods, with only its state/event dependencies installed for this scenario.
            val manager = Mockito.mock(TerminalManager::class.java, Mockito.CALLS_REAL_METHODS)
            val sessions = SessionManager(manager)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            fun install(name: String, value: Any) {
                TerminalManager::class.java.getDeclaredField(name).apply {
                    isAccessible = true
                    set(manager, value)
                }
            }
            install("sessionManager", sessions)
            install("coroutineScope", scope)
            install("_commandExecutionEvents", MutableSharedFlow<CommandExecutionEvent>())
            val session = sessions.createNewSession("test", TerminalType.LOCAL)
            val running = CommandHistoryItem("a", "$ ", "less file", "", true)
            session.currentExecutingCommand = running
            session.commandQueue.add(QueuedCommand("b", "touch must-not-run"))
            session.commandLifecycle.cancellingCommandId = "a"
            session.commandLifecycle.cancellationMutex.lock()

            val cancel = async { manager.cancelCommand(session.id, "b") }
            val finishedBeforeUnlock = try {
                withTimeoutOrNull(1_000) { cancel.await(); true } ?: false
            } finally {
                session.commandLifecycle.cancellationMutex.unlock()
            }
            cancel.await()
            scope.cancel()

            assertTrue("A queued timeout must not wait for the running cancellation", finishedBeforeUnlock)
            assertTrue(session.commandQueue.isEmpty())
            assertSame(running, session.currentExecutingCommand)
            assertTrue(running.isExecuting)
            assertEquals("a", session.commandLifecycle.cancellingCommandId)
        }
    }
}
