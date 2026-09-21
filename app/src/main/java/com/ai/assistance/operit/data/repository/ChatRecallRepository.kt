package com.ai.assistance.operit.data.repository

import android.content.Context
import com.ai.assistance.operit.data.dao.ChatRecallHit
import com.ai.assistance.operit.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatRecallRepository(context: Context) {
    private val dao = AppDatabase.getDatabase(context).chatContentDao()
    suspend fun search(query: String, offset: Int = 0): List<ChatRecallHit> = withContext(Dispatchers.IO) {
        require(query.trim().length in 1..200)
        dao.searchRecallMessages(query.trim(), 20, offset.coerceAtLeast(0))
    }
    suspend fun context(messageId: Long): List<ChatRecallHit> = withContext(Dispatchers.IO) {
        require(messageId > 0)
        dao.readRecallContext(messageId).sortedWith(compareBy<ChatRecallHit> { it.timestamp }.thenBy { it.messageId })
    }
}
