package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ChatKind

enum class ChatHistoryCategory {
    ALL,
    RECENT,
    FAVORITES,
}

internal fun selectChatHistoriesForCategory(
    histories: List<ChatHistory>,
    category: ChatHistoryCategory,
): List<ChatHistory> {
    val visibleHistories = histories.filter { it.chatKind != ChatKind.SUBAGENT.name }
    return when (category) {
        ChatHistoryCategory.ALL -> visibleHistories
        ChatHistoryCategory.RECENT ->
            visibleHistories.sortedWith(
                compareByDescending<ChatHistory> { it.lastMessageAt ?: it.createdAt }
                    .thenByDescending { it.createdAt }
                    .thenBy { it.id }
            )
        ChatHistoryCategory.FAVORITES -> visibleHistories.filter { it.isFavorite }
    }
}

internal fun canReorderChatHistory(
    category: ChatHistoryCategory,
    searchQuery: String,
): Boolean {
    return category != ChatHistoryCategory.RECENT && searchQuery.isBlank()
}

internal fun canManageChatFolders(category: ChatHistoryCategory): Boolean {
    return category == ChatHistoryCategory.ALL
}

/**
 * 将过滤视图的新顺序合并回完整列表。
 *
 * 未显示的聊天保留在原槽位，只有可见子集彼此交换位置，避免收藏页拖拽时用过滤列表
 * 覆盖或删除未收藏聊天。
 */
internal fun mergeVisibleChatOrder(
    fullHistories: List<ChatHistory>,
    reorderedVisibleHistories: List<ChatHistory>,
): List<ChatHistory> {
    if (reorderedVisibleHistories.isEmpty()) {
        return fullHistories
    }

    val visibleIds = reorderedVisibleHistories.mapTo(linkedSetOf()) { it.id }
    val fullIds = fullHistories.mapTo(hashSetOf()) { it.id }
    if (
        visibleIds.size != reorderedVisibleHistories.size ||
            !fullIds.containsAll(visibleIds) ||
            fullHistories.count { it.id in visibleIds } != visibleIds.size
    ) {
        return fullHistories
    }

    val reorderedIterator = reorderedVisibleHistories.iterator()
    return fullHistories.map { history ->
        if (history.id in visibleIds) reorderedIterator.next() else history
    }
}
