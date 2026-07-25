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
        Index(value = ["id", "scope"], unique = true),
        Index(value = ["scope", "parentFolderId", "displayOrder"]),
        Index(value = ["scope", "parentKey", "name"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChatFolderEntity::class,
            parentColumns = ["id", "scope"],
            childColumns = ["parentFolderId", "scope"],
            onDelete = ForeignKey.NO_ACTION,
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
    @ColumnInfo(defaultValue = "'root:'")
    val parentKey: String = parentKeyFor(parentFolderId),
    val displayOrder: Long,
    val pinned: Boolean = false,
) {
    companion object {
        const val ROOT_PARENT_KEY = "root:"

        fun parentKeyFor(parentFolderId: String?): String =
            parentFolderId?.let { "id:$it" } ?: ROOT_PARENT_KEY
    }
}
