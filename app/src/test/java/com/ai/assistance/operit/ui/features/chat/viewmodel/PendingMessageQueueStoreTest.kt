package com.ai.assistance.operit.ui.features.chat.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMessageQueueStoreTest {
    @Test
    fun queueRemainsAvailableWhenAnotherChatIsVisited() {
        val store = PendingMessageQueueStore()

        store.enqueue(chatId = "chat-a", text = "send after reply", isQueueBlocked = true)

        assertFalse(store.hasPendingAutoDequeue(chatId = "chat-b", isQueueBlocked = false))
        assertEquals(listOf("send after reply"), store.states.value.getValue("chat-a").messages.map { it.text })
        assertTrue(store.hasPendingAutoDequeue(chatId = "chat-a", isQueueBlocked = false))
        assertEquals("send after reply", store.takeNextAutoDequeue("chat-a")?.text)
    }

    @Test
    fun cancellingCurrentTurnConsumesOnlyTheNextAutoDequeue() {
        val store = PendingMessageQueueStore()

        store.enqueue(chatId = "chat-a", text = "first", isQueueBlocked = true)
        store.suppressNextAutoDequeue("chat-a")

        assertFalse(store.hasPendingAutoDequeue(chatId = "chat-a", isQueueBlocked = false))
        assertFalse(store.hasPendingAutoDequeue(chatId = "chat-a", isQueueBlocked = false))
    }

    @Test
    fun autoDequeueSignalSurvivesUntilMessageIsActuallyTaken() {
        val store = PendingMessageQueueStore()

        store.enqueue(chatId = "chat-a", text = "deferred", isQueueBlocked = true)

        assertTrue(store.hasPendingAutoDequeue(chatId = "chat-a", isQueueBlocked = false))
        assertTrue(store.hasPendingAutoDequeue(chatId = "chat-a", isQueueBlocked = false))
        assertEquals("deferred", store.takeNextAutoDequeue("chat-a")?.text)
        assertFalse(store.hasPendingAutoDequeue(chatId = "chat-a", isQueueBlocked = false))
    }
}
