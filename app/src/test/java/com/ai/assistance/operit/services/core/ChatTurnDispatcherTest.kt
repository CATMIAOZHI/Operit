package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.repository.READING_COMPANION_PERMANENT_HIDDEN_REASON_PREFIX
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnDispatcherTest {
    @Test
    fun permanentHiddenAuditChatRequiresInternalMutationCapability() {
        val auditChat =
            ChatHistory(
                id = "audit-chat",
                title = "audit",
                messages = emptyList(),
                isHidden = true,
                hiddenReason = "${READING_COMPANION_PERMANENT_HIDDEN_REASON_PREFIX}RUN:7",
            )

        assertFalse(
            canDispatchChatTurnTo(
                chat = auditChat,
                allowPermanentHiddenAuditMutation = false,
            )
        )
        assertTrue(
            canDispatchChatTurnTo(
                chat = auditChat,
                allowPermanentHiddenAuditMutation = true,
            )
        )
        assertTrue(
            canDispatchChatTurnTo(
                chat = auditChat.copy(isHidden = false),
                allowPermanentHiddenAuditMutation = false,
            )
        )
    }

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
