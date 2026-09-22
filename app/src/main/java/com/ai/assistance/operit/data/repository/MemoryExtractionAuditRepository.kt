package com.ai.assistance.operit.data.repository

import android.content.Context
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.data.preferences.MemoryExtractionLog

/** Reuse the actual hidden run transcript, rather than storing a second copy of the source chat. */
class MemoryExtractionAuditRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    suspend fun resolve(log: MemoryExtractionLog): SubagentRunEntity? {
        // External-owner lookup also recovers links for logs written before runId was stored.
        val run = db.subagentRunDao().getByExternalOwner("memory-learning", log.id)
            .lastOrNull { it.parentChatId == log.sourceChatId && (log.runId.isBlank() || it.id == log.runId) }
            ?: return null
        val chat = db.chatDao().getChatById(run.childChatId)
        return run.takeIf { isAuthorizedMemoryAuditChat(log, run, chat) }
    }
}

internal fun isAuthorizedMemoryAuditChat(log: MemoryExtractionLog, run: SubagentRunEntity, chat: ChatEntity?): Boolean =
    run.externalOwnerType == "memory-learning" && run.externalOwnerId == log.id &&
        run.parentChatId == log.sourceChatId && (log.runId.isBlank() || log.runId == run.id) &&
        (log.childChatId.isBlank() || log.childChatId == run.childChatId) &&
        chat?.id == run.childChatId && chat.isHidden && chat.hiddenReason == "MEMORY_LEARNING" &&
        chat.chatKind == "SUBAGENT" && chat.parentChatId == log.sourceChatId
