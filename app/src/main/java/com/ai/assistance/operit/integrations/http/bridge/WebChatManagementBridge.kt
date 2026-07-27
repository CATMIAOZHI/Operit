package com.ai.assistance.operit.integrations.http.bridge

import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.integrations.http.WebChatSummary
import com.ai.assistance.operit.integrations.http.WebUpdateChatRequest
import com.ai.assistance.operit.services.ChatServiceCore

internal class WebChatManagementBridge(
    private val core: ChatServiceCore,
    private val chatHistoryManager: ChatHistoryManager,
    private val activePromptManager: ActivePromptManager
) {
    suspend fun updateChat(
        chatId: String,
        request: WebUpdateChatRequest,
        currentChatMeta: suspend (String) -> ChatHistory?,
        buildChatSummary: suspend (ChatHistory) -> WebChatSummary
    ): WebChatSummary? {
        val normalizedTitle = request.title?.trim()?.takeIf { it.isNotBlank() }
        val normalizedFolderId = request.folderId?.trim()?.takeIf { it.isNotBlank() }
        val normalizedCharacterCardName = request.characterCardName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedCharacterGroupId = request.characterGroupId?.trim()?.takeIf { it.isNotBlank() }

        val updated =
            chatHistoryManager.updateChatFromWeb(
                chatId = chatId,
                title = normalizedTitle,
                updateFolder = request.updateFolder,
                folderId = normalizedFolderId,
                locked = request.locked.takeIf { request.updateLocked },
                pinned = request.pinned.takeIf { request.updatePinned },
                updateBinding = request.updateBinding,
                characterCardName = normalizedCharacterCardName,
                characterGroupId = normalizedCharacterGroupId,
            )
        if (!updated) {
            return null
        }
        if (request.updateBinding) {
            if (core.currentChatId.value == chatId) {
                activePromptManager.activateForChatBinding(
                    characterCardName = normalizedCharacterCardName,
                    characterGroupId = normalizedCharacterGroupId
                )
            }
        }
        return currentChatMeta(chatId)?.let { buildChatSummary(it) }
    }

}
