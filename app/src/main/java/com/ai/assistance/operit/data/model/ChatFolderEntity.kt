package com.ai.assistance.operit.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "chat_folders",
    indices = [
        Index("scope"),
        Index("parentFolderId"),
        Index(value = ["scope", "parentFolderId", "displayOrder"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChatFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentFolderId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class ChatFolderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val scope: ChatFolderScope,
    val name: String,
    val parentFolderId: String?,
    val displayOrder: Long,
    val pinned: Boolean = false,
)
