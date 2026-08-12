package com.ai.assistance.operit.data.repository

import android.content.Context
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatTodo
import com.ai.assistance.operit.data.model.ChatTodoStatus
import com.ai.assistance.operit.data.model.toChatTodo
import com.ai.assistance.operit.data.model.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatTodoRepository private constructor(context: Context) {
    private val dao = AppDatabase.getDatabase(context.applicationContext).chatTodoDao()

    fun observe(chatId: String): Flow<List<ChatTodo>> =
        dao.observeByChatId(chatId).map { rows -> rows.map { it.toChatTodo() } }

    suspend fun replace(chatId: String, todos: List<ChatTodo>) {
        require(chatId.isNotBlank()) { "Chat ID is required" }
        require(todos.all { it.content.isNotBlank() }) { "Todo content cannot be blank" }
        val inProgressCount = todos.count { it.status == ChatTodoStatus.IN_PROGRESS }
        val hasUnfinished =
            todos.any {
                it.status == ChatTodoStatus.PENDING || it.status == ChatTodoStatus.IN_PROGRESS
            }
        require(inProgressCount <= 1) { "Only one todo may be in progress" }
        require(!hasUnfinished || inProgressCount == 1) {
            "Exactly one todo must be in progress while unfinished todos remain"
        }
        dao.replaceForChat(
            chatId,
            todos.mapIndexed { index, todo -> todo.toEntity(chatId, index) },
        )
    }

    companion object {
        @Volatile private var INSTANCE: ChatTodoRepository? = null

        fun getInstance(context: Context): ChatTodoRepository =
            INSTANCE
                ?: synchronized(this) {
                    INSTANCE ?: ChatTodoRepository(context).also { INSTANCE = it }
                }
    }
}
