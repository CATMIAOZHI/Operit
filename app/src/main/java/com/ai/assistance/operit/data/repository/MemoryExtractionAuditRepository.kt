package com.ai.assistance.operit.data.repository

import android.content.Context
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.MessageEntity
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.data.preferences.MemoryExtractionLog

/** Reuse the actual hidden run transcript, rather than storing a second copy of the source chat. */
class MemoryExtractionAuditRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    data class Page(val run: SubagentRunEntity?, val messages: List<MessageEntity>, val more: Boolean)

    suspend fun load(log: MemoryExtractionLog, offset: Int): Page {
        require(offset >= 0)
        // External-owner lookup also recovers links for logs written before runId was stored.
        val run = db.subagentRunDao().getByExternalOwner("memory-learning", log.id)
            .lastOrNull { it.parentChatId == log.sourceChatId && (log.runId.isBlank() || it.id == log.runId) }
            ?: return Page(null, emptyList(), false)
        val chat = db.chatDao().getChatById(run.childChatId)
        if (chat == null || !chat.isHidden || chat.parentChatId != log.sourceChatId) {
            return Page(null, emptyList(), false)
        }
        val messages = db.chatContentDao().getMessagesForChatAscRange(run.childChatId, offset, 11)
        return Page(run, messages.take(10), messages.size > 10)
    }
}
