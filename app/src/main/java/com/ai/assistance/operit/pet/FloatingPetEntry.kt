package com.ai.assistance.operit.pet

import kotlinx.coroutines.flow.MutableStateFlow

/** The chat window owns this transient entry state; companion preferences remain independent. */
enum class FloatingPetEntryMode { NONE, HIDDEN, PET_DISABLED, PET, LEGACY_BALL, CHAT_WINDOW }

object FloatingPetEntry {
    val mode = MutableStateFlow(FloatingPetEntryMode.NONE)

    /**
     * Conversation the chat window currently shows. The pet compares it with the task in its
     * bubble, so one button can either open that task or minimize the window already showing it.
     */
    val chatId = MutableStateFlow<String?>(null)
}
