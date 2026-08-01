package com.ai.assistance.operit.services.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnDispatcherTest {
    @Test
    fun unlimitedTerminalWaitCanExceedFormerThreeMinuteLimit() = runTest {
        val terminalSignal = CompletableDeferred<ChatTurnTerminalSignal>()
        launch {
            delay(180_001L)
            terminalSignal.complete(
                ChatTurnTerminalSignal.Cancelled(
                    turnId = "turn",
                    chatId = "chat",
                )
            )
        }

        val result =
            awaitChatTurnTerminalSignal(
                terminalSignal = terminalSignal,
                responseTimeoutMs = null,
            )

        assertTrue(result is ChatTurnTerminalSignal.Cancelled)
    }
}
