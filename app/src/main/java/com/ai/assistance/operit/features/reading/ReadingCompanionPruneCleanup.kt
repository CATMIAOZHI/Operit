package com.ai.assistance.operit.features.reading

import com.ai.assistance.operit.data.model.ChatEntity

/** 剪枝中被删除的 reading.db run 的弱关联聊天挂账（child_chat_id + 所属书）。 */
internal data class PrunedRunChatRef(
    val childChatId: String,
    val bookId: String?,
)

/** 剪枝联动实际删除的聊天 id（日志与测试用）。 */
data class PruneCleanupOutcome(
    val deletedChildChatIds: List<String>,
    val deletedRootChatIds: List<String>,
)

/**
 * 段评 run 剪枝（保留 generating 行 + 最近 50）后的主库聊天联动删除（纯逻辑，JVM 可测）。
 *
 * 规则（施工图阶段 4）：
 * - 只删 hidden 审计 child（isHidden=true 且 hiddenReason 为 READING_COMPANION_AUDIT_ 前缀），
 *   删除走注入的子树删除（生产为 [ChatHistoryManager.deleteChatHistory]）；
 * - isHidden=false 的对话内 child（用户可见）绝不删除；
 * - 某书隐藏根下已无剩余 hidden child 再删该根（根仍走子树删除）；
 * - 不认识的 chatId / 已不存在的聊天直接跳过。
 *
 * 所有数据库/删除动作经注入 lambda 完成，纯 slice 可在 JVM 上以假实现验证。
 */
internal class ReadingCompanionPruneCleanup(
    private val listHiddenChats: suspend () -> List<ChatEntity>,
    private val deleteChat: suspend (chatId: String) -> Boolean,
) {
    suspend fun run(pending: List<PrunedRunChatRef>): PruneCleanupOutcome {
        if (pending.isEmpty()) {
            return PruneCleanupOutcome(deletedChildChatIds = emptyList(), deletedRootChatIds = emptyList())
        }
        val initialSnapshot = listHiddenChats()
        if (initialSnapshot.isEmpty()) {
            return PruneCleanupOutcome(deletedChildChatIds = emptyList(), deletedRootChatIds = emptyList())
        }
        val chatsById = initialSnapshot.associateBy { it.id }
        val deletedChildChatIds = mutableListOf<String>()
        val deletedBookIds = LinkedHashSet<String>()
        for (ref in pending) {
            val chat = chatsById[ref.childChatId] ?: continue
            if (!chat.isHidden || !ReadingCompanionAudit.isPermanentHiddenReason(chat.hiddenReason)) {
                // 对话内 child（isHidden=false）或非审计隐藏聊天：剪枝不删。
                continue
            }
            if (deleteChat(chat.id)) {
                deletedChildChatIds += chat.id
                if (!ref.bookId.isNullOrBlank()) {
                    deletedBookIds += ref.bookId
                }
            }
        }
        val deletedRootChatIds = mutableListOf<String>()
        for (bookId in deletedBookIds) {
            // 每次重新读取，避免快照在删除后失真。
            val fresh = listHiddenChats()
            val root =
                fresh.firstOrNull { chat ->
                    chat.isHidden && chat.hiddenReason == ReadingCompanionAudit.rootHiddenReason(bookId)
                } ?: continue
            val remainingHiddenChildren =
                fresh.count { chat ->
                    chat.id != root.id && chat.parentChatId == root.id && chat.isHidden
                }
            if (remainingHiddenChildren == 0 && deleteChat(root.id)) {
                deletedRootChatIds += root.id
            }
        }
        return PruneCleanupOutcome(deletedChildChatIds, deletedRootChatIds)
    }
}
