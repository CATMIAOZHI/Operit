package com.ai.assistance.operit.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "chat_placements",
    primaryKeys = ["chatId", "scope"],
    indices = [
        Index("chatId"),
        Index("folderId"),
        Index(value = ["scope", "folderId", "displayOrder"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChatFolderEntity::class,
            parentColumns = ["id", "scope"],
            childColumns = ["folderId", "scope"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
)
data class ChatPlacementEntity(
    val chatId: String,
    val scope: ChatFolderScope,
    val folderId: String?,
    val displayOrder: Long,
)
