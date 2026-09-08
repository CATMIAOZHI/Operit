package com.ai.assistance.operit.pet

import kotlinx.coroutines.flow.MutableStateFlow

/** The chat window owns this transient entry state; companion preferences remain independent. */
enum class FloatingPetEntryMode { NONE, HIDDEN, PET_DISABLED, PET, LEGACY_BALL, CHAT_WINDOW }

object FloatingPetEntry {
    val mode = MutableStateFlow(FloatingPetEntryMode.NONE)
}
