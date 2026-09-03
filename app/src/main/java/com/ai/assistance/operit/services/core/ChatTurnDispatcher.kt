package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.PromptFunctionType
import com.ai.assistance.operit.data.repository.isPermanentHiddenAuditChat
import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.util.stream.SharedStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TURN_CANCELLATION_TIMEOUT_MS = 15_000L

internal suspend fun awaitChatTurnTerminalSignal(
    terminalSignal: CompletableDeferred<ChatTurnTerminalSignal>,
    responseTimeoutMs: Long?,
): ChatTurnTerminalSignal =
    if (responseTimeoutMs == null) {
        terminalSignal.await()
    } else {
        withTimeout(responseTimeoutMs) { terminalSignal.await() }
    }

data class ChatTurnDispatchRequest(
    val chatId: String?,
    val message: String,
    val roleCardId: String?,
    val proxySenderName: String?,
    val turnOptions: ChatTurnOptions,
    val chatModelConfigIdOverride: String? = null,
    val chatModelIndexOverride: Int? = null,
    val responseStreamAcquireTimeoutMs: Long,
    val responseTimeoutMs: Long?,
    val turnId: String = UUID.randomUUID().toString(),
    val allowPermanentHiddenAuditMutation: Boolean = false,
)

internal fun canDispatchChatTurnTo(
    chat: ChatHistory,
    allowPermanentHiddenAuditMutation: Boolean,
): Boolean =
    allowPermanentHiddenAuditMutation || !chat.isPermanentHiddenAuditChat()

sealed interface ChatTurnTerminalSignal {
    val turnId: String
    val chatId: String

    data class Completed(
        override val turnId: String,
        override val chatId: String,
        val assistantMessageTimestamp: Long,
    ) : ChatTurnTerminalSignal

    data class Failed(
        override val turnId: String,
        override val chatId: String,
        val error: String,
    ) : ChatTurnTerminalSignal

    data class Cancelled(
        override val turnId: String,
        override val chatId: String,
    ) : ChatTurnTerminalSignal
}

data class ChatTurnOutcome(
    val turnId: String,
    val chatId: String,
    val finalAssistantText: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val completedAt: Long,
)

data class ChatTurnSession(
    val turnId: String,
    val chatId: String,
    val message: String,
    val responseStream: SharedStream<String>,
    val responseTimeoutMs: Long,
    private val enforceResponseTimeout: Boolean,
    private val terminalSignal: CompletableDeferred<ChatTurnTerminalSignal>,
    private val loadPersistedMessage: suspend (Long) -> ChatMessage?,
    private val currentStateProvider: () -> InputProcessingState,
    private val cancelAction: () -> Unit,
    private val cancelAndAwaitAction: suspend () -> Unit,
    private val timeoutAction: (String) -> Unit,
) {
    fun currentState(): InputProcessingState = currentStateProvider()

    suspend fun awaitOutcome(): ChatTurnOutcome {
        val signal =
            try {
                awaitChatTurnTerminalSignal(
                    terminalSignal = terminalSignal,
                    responseTimeoutMs = responseTimeoutMs.takeIf { enforceResponseTimeout },
                )
            } catch (error: TimeoutCancellationException) {
                if (!currentCoroutineContext().isActive) throw error
                val message = "Turn $turnId timed out waiting for a terminal result"
                withContext(NonCancellable) {
                    runCatching {
                        withTimeout(TURN_CANCELLATION_TIMEOUT_MS) {
                            cancelAndAwaitAction()
                        }
                    }
                    timeoutAction(message)
                }
                throw IllegalStateException(message)
            }
        return when (signal) {
            is ChatTurnTerminalSignal.Completed -> {
                val message =
                    loadPersistedMessage(signal.assistantMessageTimestamp)
                        ?: throw IllegalStateException(
                            "Turn $turnId completed but its persisted assistant message is missing"
                        )
                ChatTurnOutcome(
                    turnId = turnId,
                    chatId = chatId,
                    finalAssistantText = message.content,
                    inputTokens = message.inputTokens,
                    outputTokens = message.outputTokens,
                    completedAt = message.completedAt,
                )
            }
            is ChatTurnTerminalSignal.Failed ->
                throw IllegalStateException("Turn $turnId failed: ${signal.error}")
            is ChatTurnTerminalSignal.Cancelled ->
                throw CancellationException("Turn $turnId was cancelled")
        }
    }

    fun cancel() {
        cancelAction()
    }

    suspend fun cancelAndAwaitTermination() {
        withContext(NonCancellable) {
            try {
                withTimeout(TURN_CANCELLATION_TIMEOUT_MS) {
                    cancelAndAwaitAction()
                    terminalSignal.await()
                }
            } catch (_: TimeoutCancellationException) {
                timeoutAction("Turn $turnId timed out while cancelling")
            }
        }
    }

}

sealed interface ChatTurnDispatchResult {
    data class Started(val session: ChatTurnSession) : ChatTurnDispatchResult

    data class Failed(
        val chatId: String,
        val message: String,
        val error: String,
    ) : ChatTurnDispatchResult
}

/**
 * Starts a chat turn without changing the visible chat selection.
 *
 * This is the shared transport used by chat tools and, later, Subagent execution. It deliberately
 * exposes the raw response stream only; the stable terminal outcome is added on top of this path.
 */
class ChatTurnDispatcher {
    private suspend fun cancelRegisteredTurnWithinTimeout(
        core: ChatServiceCore,
        turnId: String,
        chatId: String,
    ) {
        withContext(NonCancellable) {
            try {
                withTimeout(TURN_CANCELLATION_TIMEOUT_MS) {
                    core.cancelRegisteredChatTurnAndAwait(turnId, chatId)
                }
            } catch (_: TimeoutCancellationException) {
                core.failRegisteredChatTurn(
                    turnId = turnId,
                    chatId = chatId,
                    error = "Turn $turnId timed out while cancelling",
                )
            }
        }
    }

    suspend fun dispatch(
        core: ChatServiceCore,
        request: ChatTurnDispatchRequest,
    ): ChatTurnDispatchResult {
        val timeoutDeadlineMs =
            request.responseTimeoutMs?.let { timeoutMs ->
                System.currentTimeMillis() + timeoutMs
            }

        fun remainingTimeoutMs(defaultTimeoutMs: Long): Long {
            val deadline = timeoutDeadlineMs ?: return defaultTimeoutMs
            return (deadline - System.currentTimeMillis()).coerceAtMost(defaultTimeoutMs)
                .coerceAtLeast(1L)
        }

        val hasTargetChat = !request.chatId.isNullOrBlank()
        var targetChat: ChatHistory? = null
        if (hasTargetChat) {
            val targetChatId = requireNotNull(request.chatId)
            targetChat = core.getChatMetadata(targetChatId)
            if (targetChat == null) {
                return ChatTurnDispatchResult.Failed(
                    chatId = targetChatId,
                    message = request.message,
                    error = "Specified chat does not exist: $targetChatId",
                )
            }
        }

        val preflightChatId = request.chatId ?: core.currentChatId.value
        val preflightChat =
            targetChat
                ?: preflightChatId?.let { chatId -> core.getChatMetadata(chatId) }
        if (
            preflightChat != null &&
                !canDispatchChatTurnTo(
                    chat = preflightChat,
                    allowPermanentHiddenAuditMutation =
                        request.allowPermanentHiddenAuditMutation,
                )
        ) {
            return ChatTurnDispatchResult.Failed(
                chatId = preflightChat.id,
                message = request.message,
                error = "Reading companion audit chats are read-only",
            )
        }
        val preflightResponseStream = preflightChatId?.let(core::getResponseStream)

        try {
            preflightChatId?.let { chatId ->
                withTimeout(
                    remainingTimeoutMs(
                        request.responseTimeoutMs ?: request.responseStreamAcquireTimeoutMs
                    )
                ) {
                    core.activeStreamingChatIds.first { activeChatIds ->
                        chatId !in activeChatIds
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw error
            return ChatTurnDispatchResult.Failed(
                chatId = preflightChatId.orEmpty(),
                message = request.message,
                error = "Previous message is still being processed",
            )
        }

        val terminalSignal = core.registerChatTurn(request.turnId)
        val effectiveTurnOptions = request.turnOptions.copy(turnId = request.turnId)

        try {
            if (hasTargetChat) {
                core.sendUserMessage(
                    promptFunctionType = PromptFunctionType.CHAT,
                    roleCardIdOverride = request.roleCardId,
                    chatIdOverride = preflightChatId,
                    messageTextOverride = request.message,
                    proxySenderNameOverride = request.proxySenderName,
                    chatModelConfigIdOverride = request.chatModelConfigIdOverride,
                    chatModelIndexOverride = request.chatModelIndexOverride,
                    turnOptions = effectiveTurnOptions,
                )
            } else {
                core.sendUserMessage(
                    promptFunctionType = PromptFunctionType.CHAT,
                    roleCardIdOverride = request.roleCardId,
                    messageTextOverride = request.message,
                    proxySenderNameOverride = request.proxySenderName,
                    chatModelConfigIdOverride = request.chatModelConfigIdOverride,
                    chatModelIndexOverride = request.chatModelIndexOverride,
                    turnOptions = effectiveTurnOptions,
                )
            }
        } catch (error: Throwable) {
            core.failRegisteredChatTurn(
                turnId = request.turnId,
                chatId = preflightChatId.orEmpty(),
                error = error.message ?: error.javaClass.simpleName,
            )
            throw error
        }

        val resolvedChatId =
            if (hasTargetChat) {
                preflightChatId
            } else {
                try {
                    withTimeout(remainingTimeoutMs(request.responseStreamAcquireTimeoutMs)) {
                        var id = core.currentChatId.value
                        while (id == null) {
                            delay(50)
                            id = core.currentChatId.value
                        }
                        id
                    }
                } catch (error: TimeoutCancellationException) {
                    if (!currentCoroutineContext().isActive) throw error
                    null
                }
            }

        if (resolvedChatId == null) {
            core.failRegisteredChatTurn(
                turnId = request.turnId,
                chatId = preflightChatId.orEmpty(),
                error = "Unable to get current chat ID",
            )
            return ChatTurnDispatchResult.Failed(
                chatId = preflightChatId.orEmpty(),
                message = request.message,
                error = "Unable to get current chat ID",
            )
        }

        val responseStream =
            try {
                var stream: SharedStream<String>? = core.getResponseStream(resolvedChatId)
                withTimeout(remainingTimeoutMs(request.responseStreamAcquireTimeoutMs)) {
                    while (stream == null || stream === preflightResponseStream) {
                        val state =
                            core.inputProcessingStateByChatId.value[resolvedChatId]
                                ?: InputProcessingState.Idle
                        if (state is InputProcessingState.Error) {
                            val terminal = terminalSignal.await()
                            when (terminal) {
                                is ChatTurnTerminalSignal.Failed ->
                                    throw IllegalStateException(terminal.error)
                                is ChatTurnTerminalSignal.Cancelled ->
                                    throw CancellationException(
                                        "Turn ${request.turnId} was cancelled"
                                    )
                                is ChatTurnTerminalSignal.Completed ->
                                    throw IllegalStateException(
                                        "Turn ${request.turnId} completed before its response stream was available"
                                    )
                            }
                        }
                        delay(50)
                        stream = core.getResponseStream(resolvedChatId)
                    }
                }
                requireNotNull(stream)
            } catch (error: TimeoutCancellationException) {
                if (!currentCoroutineContext().isActive) {
                    cancelRegisteredTurnWithinTimeout(
                        core = core,
                        turnId = request.turnId,
                        chatId = resolvedChatId,
                    )
                    throw error
                }
                runCatching {
                    core.cancelRegisteredChatTurn(request.turnId, resolvedChatId)
                }
                core.failRegisteredChatTurn(
                    turnId = request.turnId,
                    chatId = resolvedChatId,
                    error = "Timeout waiting for AI response",
                )
                return ChatTurnDispatchResult.Failed(
                    chatId = resolvedChatId,
                    message = request.message,
                    error = "Timeout waiting for AI response",
                )
            } catch (error: CancellationException) {
                cancelRegisteredTurnWithinTimeout(
                    core = core,
                    turnId = request.turnId,
                    chatId = resolvedChatId,
                )
                throw error
            }

        return ChatTurnDispatchResult.Started(
            ChatTurnSession(
                turnId = request.turnId,
                chatId = resolvedChatId,
                message = request.message,
                responseStream = responseStream,
                responseTimeoutMs =
                    request.responseTimeoutMs?.let(::remainingTimeoutMs) ?: Long.MAX_VALUE,
                enforceResponseTimeout = request.responseTimeoutMs != null,
                terminalSignal = terminalSignal,
                loadPersistedMessage = { timestamp ->
                    core.getChatHistoryDelegate()
                        .getChatHistory(resolvedChatId)
                        .firstOrNull { message ->
                            message.sender == "ai" && message.timestamp == timestamp
                        }
                },
                currentStateProvider = {
                    core.inputProcessingStateByChatId.value[resolvedChatId]
                        ?: InputProcessingState.Idle
                },
                cancelAction = {
                    runCatching {
                        core.cancelRegisteredChatTurn(request.turnId, resolvedChatId)
                    }
                },
                cancelAndAwaitAction = {
                    core.cancelRegisteredChatTurnAndAwait(request.turnId, resolvedChatId)
                },
                timeoutAction = { error ->
                    core.failRegisteredChatTurn(request.turnId, resolvedChatId, error)
                },
            )
        )
    }
}
