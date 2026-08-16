package com.ai.assistance.operit.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class ChatTodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
}

enum class ChatTodoPriority {
    HIGH,
    MEDIUM,
    LOW,
}

@Entity(
    tableName = "chat_todos",
    primaryKeys = ["chatId", "position"],
    indices = [Index(value = ["chatId"])],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChatTodoEntity(
    val chatId: String,
    val position: Int,
    val content: String,
    val status: String,
    val priority: String,
)

data class ChatTodo(
    val content: String,
    val status: ChatTodoStatus,
    val priority: ChatTodoPriority,
)

fun ChatTodoEntity.toChatTodo(): ChatTodo =
    ChatTodo(
        content = content,
        status = ChatTodoStatus.valueOf(status),
        priority = ChatTodoPriority.valueOf(priority),
    )

fun ChatTodo.toEntity(chatId: String, position: Int): ChatTodoEntity =
    ChatTodoEntity(
        chatId = chatId,
        position = position,
        content = content,
        status = status.name,
        priority = priority.name,
    )
