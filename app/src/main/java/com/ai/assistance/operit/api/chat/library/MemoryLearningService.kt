package com.ai.assistance.operit.api.chat.library

import android.content.Context
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.FunctionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Explicit UI review uses the same extractor, without waiting for the background queue threshold. */
object MemoryLearningService {
    suspend fun extract(context: Context, profileId: String, chatId: String) = withContext(Dispatchers.IO) {
        withTimeout(120_000) {
            val db = AppDatabase.getDatabase(context)
            val chat = db.chatDao().getChatById(chatId)
            require(chat != null && !chat.isHidden && chat.parentChatId == null && chat.chatKind == "NORMAL")
            val messages = db.chatContentDao().getMessagesForChatDesc(chatId, 48).asReversed()
                .filter { it.sender in setOf("user", "ai") && it.content.isNotBlank() }
                .map { (if (it.sender == "ai") "assistant" else "user") to it.content }
            val answer = messages.lastOrNull { it.first == "assistant" }?.second
            require(answer != null && messages.any { it.first == "user" })
            MemoryLibrary.saveMemoryNow(
                context, AIToolHandler.getInstance(context), messages, answer,
                EnhancedAIService.getAIServiceForFunction(context, FunctionType.MEMORY),
                profileIdOverride = profileId, includeNotes = true, includeSkills = true,
                sourceChatId = chatId, propagateFailure = true, includeGraph = false
            )
        }
    }
}
