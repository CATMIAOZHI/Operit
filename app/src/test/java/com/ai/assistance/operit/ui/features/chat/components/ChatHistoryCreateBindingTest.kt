package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatHistoryDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryCreateBindingTest {
    @Test
    fun characterGroupBindingIsPreservedForNewChats() {
        assertEquals(
            Pair(null, "group-id"),
            resolveBindingForCreate(
                historyDisplayMode = ChatHistoryDisplayMode.BY_FOLDER,
                activePrompt = ActivePrompt.CharacterGroup("group-id"),
                activeCharacterCardName = null,
            ),
        )
    }

    @Test
    fun characterCardBindingUsesTheActiveCardName() {
        assertEquals(
            Pair("Rainy", null),
            resolveBindingForCreate(
                historyDisplayMode = ChatHistoryDisplayMode.BY_CHARACTER_CARD,
                activePrompt = ActivePrompt.CharacterCard("card-id"),
                activeCharacterCardName = "Rainy",
            ),
        )
    }

    @Test
    fun folderViewDefersCharacterCardResolutionToPreserveOpeningStatementBehavior() {
        assertEquals(
            Pair(null, null),
            resolveBindingForCreate(
                historyDisplayMode = ChatHistoryDisplayMode.BY_FOLDER,
                activePrompt = ActivePrompt.CharacterCard("card-id"),
                activeCharacterCardName = "Rainy",
            ),
        )
    }
}
