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
        val folderIdsByLegacyBucket = mutableMapOf<Pair<String, String?>, String>()
        val reordered =
            items.map { item ->
                val history = historiesById.getValue(item.chatId)
                val stableFolderId = item.folderId?.trim()?.takeIf { it.isNotBlank() }
                val folderId =
                    stableFolderId
                        ?: item.group
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let {
                                val bucket = it to history.characterCardName
                                folderIdsByLegacyBucket[bucket]
                                    ?: chatHistoryManager.resolveOrCreateLegacyFolderId(
                                        groupName = it,
                                        characterCardName = history.characterCardName,
                                    ).also { folderId ->
                                        folderIdsByLegacyBucket[bucket] = folderId
                                    }
                            }
                history.copy(
                    displayOrder = item.displayOrder,
                    group = null,
                    folderId = folderId,
                )
            }
        return chatHistoryManager.updateChatOrderAndFolders(reordered)
    }

    suspend fun renameGroup(request: WebRenameGroupRequest): Boolean {
        val folderId =
            chatHistoryManager.findLegacyFolderId(
                groupName = request.oldName,
                characterCardName = request.characterCardName,
            ) ?: return false
        chatHistoryManager.renameFolder(folderId, request.newName)
        return true
    }

    suspend fun deleteGroup(request: WebDeleteGroupRequest): Boolean {
        val folderId =
            chatHistoryManager.findLegacyFolderId(
                groupName = request.groupName,
                characterCardName = request.characterCardName,
            ) ?: return false
        if (request.deleteChats) {
            val normalizedCharacterCardName =
                request.characterCardName?.trim()?.takeIf { it.isNotBlank() }
            chatHistoryManager.chatHistoriesFlow.first()
                .asSequence()
                .filter { it.folderId == folderId }
                .filter {
                    normalizedCharacterCardName == null ||
                        it.characterCardName == normalizedCharacterCardName
                }
                .map { it.id }
                .toList()
                .forEach { chatHistoryManager.deleteChatHistory(it) }
        }
        chatHistoryManager.deleteFolder(folderId)
        return true
    }

}
