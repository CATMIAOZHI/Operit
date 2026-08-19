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
    private val _states = MutableStateFlow<Map<String, PendingMessageQueueState>>(emptyMap())
    val states: StateFlow<Map<String, PendingMessageQueueState>> = _states.asStateFlow()

    fun enqueue(chatId: String, text: String, isQueueBlocked: Boolean) {
        synchronized(lock) {
            updateState(chatId) { state ->
                state.copy(
                    messages = state.messages + PendingQueueMessageItem(nextMessageId.getAndIncrement(), text),
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
            updateState(chatId) { state ->
                if (state.messages.any { it.id == message.id }) state
                else state.copy(messages = listOf(message) + state.messages)
            }
        }
    }

    fun setExpanded(chatId: String, expanded: Boolean) {
        synchronized(lock) { updateState(chatId) { it.copy(isExpanded = expanded) } }
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
            state.wasBlocked && state.messages.isNotEmpty()
        }

    fun takeNextAutoDequeue(chatId: String): PendingQueueMessageItem? =
        synchronized(lock) {
            val state = _states.value[chatId] ?: return@synchronized null
            if (!state.wasBlocked || state.suppressNextAutoDequeue) return@synchronized null
            val message = state.messages.firstOrNull() ?: return@synchronized null
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
            if (chatId in _states.value) _states.value = _states.value - chatId
        }
    }

    private fun updateState(
        chatId: String,
        transform: (PendingMessageQueueState) -> PendingMessageQueueState,
    ) {
        val updated = _states.value.toMutableMap()
        updated[chatId] = transform(updated[chatId] ?: PendingMessageQueueState())
        _states.value = updated
    }
}
