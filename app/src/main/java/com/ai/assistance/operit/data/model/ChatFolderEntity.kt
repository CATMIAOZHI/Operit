package com.ai.assistance.operit.data.model

import androidx.room.ColumnInfo
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
        Index(value = ["scope", "parentKey", "name"], unique = true),
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
    // Non-null mirror used because SQLite UNIQUE permits repeated NULL values.
    // All parent changes must update this through ChatFolderDao.
    @ColumnInfo(defaultValue = "''")
    val parentKey: String = parentFolderId ?: ROOT_PARENT_KEY,
    val displayOrder: Long,
    val pinned: Boolean = false,
) {
    companion object {
        const val ROOT_PARENT_KEY = ""
    }
}
