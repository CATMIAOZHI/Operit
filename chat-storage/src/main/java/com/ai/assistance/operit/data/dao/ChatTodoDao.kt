package com.ai.assistance.operit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ai.assistance.operit.data.model.ChatTodoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatTodoDao {
    @Query("SELECT * FROM chat_todos WHERE chatId = :chatId ORDER BY position ASC")
    fun observeByChatId(chatId: String): Flow<List<ChatTodoEntity>>

    @Query("SELECT * FROM chat_todos WHERE chatId = :chatId ORDER BY position ASC")
    suspend fun getByChatId(chatId: String): List<ChatTodoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(todos: List<ChatTodoEntity>)

    @Query("DELETE FROM chat_todos WHERE chatId = :chatId")
    suspend fun deleteByChatId(chatId: String)

    @Transaction
    suspend fun replaceForChat(chatId: String, todos: List<ChatTodoEntity>) {
        deleteByChatId(chatId)
        if (todos.isNotEmpty()) insertAll(todos)
    }

    @Transaction
    suspend fun copyForChat(sourceChatId: String, targetChatId: String) {
        val snapshot =
            getByChatId(sourceChatId).map { todo -> todo.copy(chatId = targetChatId) }
        replaceForChat(targetChatId, snapshot)
    }
}
