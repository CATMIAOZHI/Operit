package com.ai.assistance.operit.features.reading

import com.ai.assistance.operit.data.model.ChatEntity

/**
 * 剪枝挂账的幂等孤儿重建清理（纯逻辑，JVM 可测）。
 *
 * 场景：pruneAutoCommentRuns 在事务内删掉 reading.db 旧 run 后，挂账队列是 Store 实例
 * 内存字段；若进程在 flush 前退出（或启动对账的临时 Store 实例从未 flush），hidden 审计
 * child / 空根会永久残留在主库并继续出现在隐藏聊天列表。
 *
 * 本清理不依赖任何实例内存状态，完全从数据库现状重建：hiddenReason 为
 * READING_COMPANION_AUDIT_RUN:<runId> 且对应 reading run 已不存在（被剪/被删）的 hidden
 * 子聊天即孤儿，走注入的子树删除；删除后某书隐藏根下已无剩余 hidden child 再删该根。
 *
 * 规则与 [ReadingCompanionPruneCleanup] 一致：只删 hidden 审计 child；isHidden=false 的
 * 对话内 child 绝不删（即使 reason 含 RUN 前缀）；无法解析 runId 的异常 reason 保守跳过。
 * 幂等：每轮只删 run 已不存在的 child，多轮并发/重复执行无附加副作用。
 */
internal class ReadingCompanionOrphanChatCleanup(
    private val listHiddenChats: suspend () -> List<ChatEntity>,
    private val runExists: suspend (runId: Long) -> Boolean,
    private val deleteChat: suspend (chatId: String) -> Boolean,
) {
    suspend fun run(): PruneCleanupOutcome {
        val chats = listHiddenChats()
        if (chats.isEmpty()) {
            return PruneCleanupOutcome(
                deletedChildChatIds = emptyList(),
                deletedRootChatIds = emptyList(),
            )
        }
        val deletedChildChatIds = mutableListOf<String>()
        val rootsWithDeletedChildren = LinkedHashSet<String>()
        for (chat in chats) {
            if (!chat.isHidden) continue
            val runId = parseRunId(chat.hiddenReason) ?: continue
            if (runExists(runId)) continue
            if (deleteChat(chat.id)) {
                deletedChildChatIds += chat.id
                chat.parentChatId?.let(rootsWithDeletedChildren::add)
            }
        }
        if (deletedChildChatIds.isEmpty()) {
            return PruneCleanupOutcome(
                deletedChildChatIds = emptyList(),
                deletedRootChatIds = emptyList(),
            )
        }
        val deletedRootChatIds = mutableListOf<String>()
        for (rootId in rootsWithDeletedChildren) {
            // 每次重新读取，避免快照在删除后失真。
            val fresh = listHiddenChats()
            val root = fresh.firstOrNull { it.id == rootId } ?: continue
            if (
                !root.isHidden ||
                    !ReadingCompanionAudit.isPermanentHiddenReason(root.hiddenReason)
            ) {
                continue
            }
            val remainingHiddenChildren =
                fresh.count { it.id != rootId && it.parentChatId == rootId && it.isHidden }
            if (remainingHiddenChildren == 0 && deleteChat(rootId)) {
                deletedRootChatIds += rootId
            }
        }
        return PruneCleanupOutcome(deletedChildChatIds, deletedRootChatIds)
    }

    private fun parseRunId(hiddenReason: String?): Long? {
        if (hiddenReason == null || !hiddenReason.startsWith(ReadingCompanionAudit.HIDDEN_RUN_PREFIX)) {
            return null
        }
        return hiddenReason
            .removePrefix(ReadingCompanionAudit.HIDDEN_RUN_PREFIX)
            .toLongOrNull()
    }
}
