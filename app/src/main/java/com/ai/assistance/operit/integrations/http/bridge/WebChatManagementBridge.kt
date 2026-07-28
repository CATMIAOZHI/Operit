package com.ai.assistance.operit.integrations.http.bridge

import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.integrations.http.WebChatReorderItem
import com.ai.assistance.operit.integrations.http.WebChatSummary
import com.ai.assistance.operit.integrations.http.WebDeleteGroupRequest
import com.ai.assistance.operit.integrations.http.WebRenameGroupRequest
import com.ai.assistance.operit.integrations.http.WebUpdateChatRequest
import com.ai.assistance.operit.services.ChatServiceCore
import kotlinx.coroutines.flow.first

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
        val normalizedGroup = request.group?.trim()?.takeIf { it.isNotBlank() }
        val normalizedCharacterCardName = request.characterCardName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedCharacterGroupId = request.characterGroupId?.trim()?.takeIf { it.isNotBlank() }
        val currentCharacterCardName =
            if (request.updateGroup && normalizedCharacterCardName == null) {
                currentChatMeta(chatId)?.characterCardName
            } else {
                normalizedCharacterCardName
            }
        val resolvedFolderId =
            if (request.updateGroup) {
                normalizedGroup?.let {
                    chatHistoryManager.resolveOrCreateLegacyFolderId(
                        groupName = it,
                        characterCardName = currentCharacterCardName,
                    )
                }
            } else {
                normalizedFolderId
            }

        val updated =
            chatHistoryManager.updateChatFromWeb(
                chatId = chatId,
                title = normalizedTitle,
                updateFolder = request.updateFolder || request.updateGroup,
                folderId = resolvedFolderId,
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

    suspend fun reorderChats(items: List<WebChatReorderItem>): Boolean {
        val historiesById = chatHistoryManager.chatHistoriesFlow.first().associateBy { it.id }
        if (
            items.map { it.chatId }.distinct().size != items.size ||
                items.any { it.chatId !in historiesById }
        ) {
            return false
        }
        val legacyGroupNamesByChatId = mutableMapOf<String, String>()
        val reordered =
            items.map { item ->
                val history = historiesById.getValue(item.chatId)
                val stableFolderId = item.folderId?.trim()?.takeIf { it.isNotBlank() }
                if (stableFolderId == null) {
                    item.group
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { legacyGroupNamesByChatId[item.chatId] = it }
                }
                history.copy(
                    displayOrder = item.displayOrder,
                    group = null,
                    folderId = stableFolderId,
                )
            }
        return chatHistoryManager.updateChatOrderAndFolders(
            histories = reordered,
            legacyGroupNamesByChatId = legacyGroupNamesByChatId,
        )
    }

    suspend fun renameGroup(request: WebRenameGroupRequest): Boolean {
        val folderId = resolveGroupMutationFolderId(
            stableFolderId = request.folderId,
            legacyGroupName = request.oldName,
            characterCardName = request.characterCardName,
        ) ?: return false
        return chatHistoryManager.renameFolderIfExists(folderId, request.newName)
    }

    suspend fun deleteGroup(request: WebDeleteGroupRequest): Boolean {
        val folderId = resolveGroupMutationFolderId(
            stableFolderId = request.folderId,
            legacyGroupName = request.groupName,
            characterCardName = request.characterCardName,
        ) ?: return false
        if (request.deleteChats) {
            return chatHistoryManager.deleteFolderWithChatsIfExists(
                folderId = folderId,
                characterCardName = request.characterCardName,
            )
        }
        return chatHistoryManager.deleteFolderIfExists(folderId)
    }

    private suspend fun resolveGroupMutationFolderId(
        stableFolderId: String?,
        legacyGroupName: String,
        characterCardName: String?,
    ): String? {
        val normalizedFolderId = stableFolderId?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedFolderId != null) {
            return normalizedFolderId
        }
        return chatHistoryManager.findLegacyFolderId(
            groupName = legacyGroupName,
            characterCardName = characterCardName,
        )
    }

}
