package com.ai.assistance.operit.ui.features.chat.viewmodel

import com.ai.assistance.operit.ui.features.chat.components.style.input.common.PendingQueueMessageItem
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PendingMessageQueueState(
    val messages: List<PendingQueueMessageItem> = emptyList(),
    val isExpanded: Boolean = true,
    val wasBlocked: Boolean = false,
    val suppressNextAutoDequeue: Boolean = false,
)

/** Keeps transient queues per chat for the lifetime of the ViewModel, across UI navigation. */
internal class PendingMessageQueueStore {
    private val lock = Any()
    private val nextMessageId = AtomicLong(1L)
    private val chatGenerations = mutableMapOf<String, Long>()
    private val inactiveChatIds = mutableSetOf<String>()
    private var lastExistingChatIds: Set<String> = emptySet()
    private val _states = MutableStateFlow<Map<String, PendingMessageQueueState>>(emptyMap())
    val states: StateFlow<Map<String, PendingMessageQueueState>> = _states.asStateFlow()

    fun enqueue(chatId: String, text: String, isQueueBlocked: Boolean) {
        synchronized(lock) {
            if (chatId in inactiveChatIds) return
            val generation = chatGenerations[chatId] ?: 0L
            updateState(chatId) { state ->
                state.copy(
                    messages =
                        state.messages +
                            PendingQueueMessageItem(
                                id = nextMessageId.getAndIncrement(),
                                text = text,
                                chatGeneration = generation,
                            ),
                    isExpanded = true,
                    wasBlocked = isQueueBlocked,
                )
            }
        }
    }

    fun remove(chatId: String, messageId: Long): PendingQueueMessageItem? = synchronized(lock) {
        val state = _states.value[chatId] ?: return@synchronized null
        val message = state.messages.firstOrNull { it.id == messageId } ?: return@synchronized null
        updateState(chatId) { current ->
            current.copy(messages = current.messages.filterNot { it.id == messageId })
        }
        message
    }

    fun restore(chatId: String, message: PendingQueueMessageItem) {
        synchronized(lock) {
            if (chatId in inactiveChatIds) return
            if (message.chatGeneration != (chatGenerations[chatId] ?: 0L)) return
            updateState(chatId) { state ->
                if (state.messages.any { it.id == message.id }) state
                else state.copy(messages = (state.messages + message).sortedBy { it.id })
            }
        }
    }

    fun setExpanded(chatId: String, expanded: Boolean) {
        synchronized(lock) { updateState(chatId) { it.copy(isExpanded = expanded) } }
    }

    fun returnSteer(chatId: String, message: PendingQueueMessageItem) {
        synchronized(lock) {
            if (!isCurrentGeneration(chatId, message.chatGeneration)) return
            updateState(chatId) { state ->
                state.copy(
                    messages = state.messages.map { if (it.id == message.id) message else it },
                    suppressNextAutoDequeue = true,
                )
            }
        }
    }

    fun suppressNextAutoDequeue(chatId: String) {
        synchronized(lock) {
            updateState(chatId) { it.copy(suppressNextAutoDequeue = true) }
        }
    }

    fun hasPendingAutoDequeue(chatId: String, isQueueBlocked: Boolean): Boolean =
        synchronized(lock) {
            val state = _states.value[chatId] ?: return@synchronized false
            if (isQueueBlocked) {
                updateState(chatId) { it.copy(wasBlocked = true) }
                return@synchronized false
            }
            if (state.suppressNextAutoDequeue) {
                updateState(chatId) {
                    it.copy(wasBlocked = false, suppressNextAutoDequeue = false)
                }
                return@synchronized false
            }
            state.wasBlocked && state.messages.any { !it.isSteering }
        }

    fun takeNextAutoDequeue(chatId: String): PendingQueueMessageItem? =
        synchronized(lock) {
            val state = _states.value[chatId] ?: return@synchronized null
            if (!state.wasBlocked || state.suppressNextAutoDequeue) return@synchronized null
            val message = state.messages.firstOrNull { !it.isSteering } ?: return@synchronized null
            updateState(chatId) {
                it.copy(
                    messages = it.messages.filterNot { queued -> queued.id == message.id },
                    wasBlocked = false,
                )
            }
            message
        }

    fun removeChat(chatId: String) {
        synchronized(lock) {
            chatGenerations[chatId] = (chatGenerations[chatId] ?: 0L) + 1L
            inactiveChatIds += chatId
            if (chatId in _states.value) _states.value = _states.value - chatId
        }
    }

    /** Reactivates a deleted ID only after the chat list observes an absent -> present transition. */
    fun syncExistingChatIds(existingChatIds: Set<String>) {
        synchronized(lock) {
            val reintroducedChatIds = existingChatIds - lastExistingChatIds
            inactiveChatIds.removeAll(reintroducedChatIds)
            lastExistingChatIds = existingChatIds.toSet()
        }
    }

    fun isChatInactive(chatId: String): Boolean = synchronized(lock) { chatId in inactiveChatIds }

    fun isCurrentGeneration(chatId: String, generation: Long): Boolean = synchronized(lock) {
        chatId !in inactiveChatIds && generation == (chatGenerations[chatId] ?: 0L)
    }

    private fun updateState(
        chatId: String,
        transform: (PendingMessageQueueState) -> PendingMessageQueueState,
    ) {
        if (chatId in inactiveChatIds) return
        val updated = _states.value.toMutableMap()
        updated[chatId] = transform(updated[chatId] ?: PendingMessageQueueState())
        _states.value = updated
    }
}
