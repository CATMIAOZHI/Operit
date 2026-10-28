package com.ai.assistance.operit.features.orbit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Asks the Orbit page to show one of its chats: a tapped report notification or something shared
 * to Orbit. In memory only; the page takes the request once it is on screen.
 */
internal object OrbitNavigation {
    private val pending = MutableStateFlow<String?>(null)
    val pendingChat: StateFlow<String?> = pending

    fun openChat(chatId: String) { pending.value = chatId }

    fun consume(chatId: String) { pending.compareAndSet(chatId, null) }
}