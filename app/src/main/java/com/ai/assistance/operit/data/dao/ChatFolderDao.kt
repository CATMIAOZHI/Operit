package com.ai.assistance.operit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.assistance.operit.data.model.ChatFolderEntity
import com.ai.assistance.operit.data.model.ChatFolderScope
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatFolderDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFolder(folder: ChatFolderEntity)

    @Query("SELECT * FROM chat_folders WHERE scope = :scope ORDER BY pinned DESC, displayOrder ASC")
    fun observeAllFolders(scope: ChatFolderScope): Flow<List<ChatFolderEntity>>

    @Query("SELECT * FROM chat_folders WHERE scope = :scope AND parentFolderId IS :parentFolderId ORDER BY pinned DESC, displayOrder ASC")
    suspend fun getChildFolders(scope: ChatFolderScope, parentFolderId: String?): List<ChatFolderEntity>

    @Query("SELECT * FROM chat_folders WHERE id = :folderId")
    suspend fun getFolderById(folderId: String): ChatFolderEntity?

    @Query("SELECT * FROM chat_folders WHERE scope = :scope")
    suspend fun getAllFolders(scope: ChatFolderScope): List<ChatFolderEntity>

    @Query("SELECT * FROM chat_folders WHERE scope = :scope AND parentFolderId IS NULL")
    suspend fun getRootFolders(scope: ChatFolderScope): List<ChatFolderEntity>

    @Query("UPDATE chat_folders SET name = :name WHERE id = :folderId")
    suspend fun renameFolder(folderId: String, name: String)

    @Query("UPDATE chat_folders SET pinned = :pinned WHERE id = :folderId")
    suspend fun setFolderPinned(folderId: String, pinned: Boolean)

    @Query("UPDATE chat_folders SET parentFolderId = :parentFolderId, parentKey = :parentKey, displayOrder = :displayOrder WHERE id = :folderId")
    suspend fun moveFolder(folderId: String, parentFolderId: String?, parentKey: String, displayOrder: Long)

    suspend fun moveFolder(folderId: String, parentFolderId: String?, displayOrder: Long) =
        moveFolder(folderId, parentFolderId, parentFolderId ?: ChatFolderEntity.ROOT_PARENT_KEY, displayOrder)

    @Query("DELETE FROM chat_folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: String)

    @Query("UPDATE chat_folders SET parentFolderId = :newParentFolderId, parentKey = :parentKey, displayOrder = :displayOrder WHERE id = :folderId")
    suspend fun reparentFolder(folderId: String, newParentFolderId: String?, parentKey: String, displayOrder: Long)

    suspend fun reparentFolder(folderId: String, newParentFolderId: String?, displayOrder: Long) =
        reparentFolder(folderId, newParentFolderId, newParentFolderId ?: ChatFolderEntity.ROOT_PARENT_KEY, displayOrder)

    @Query("SELECT EXISTS(SELECT 1 FROM chat_folders WHERE scope = :scope AND parentFolderId IS :parentFolderId AND name = :name)")
    suspend fun folderNameExistsInParent(scope: ChatFolderScope, parentFolderId: String?, name: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM chat_folders WHERE scope = :scope AND parentFolderId IS :parentFolderId AND name = :name AND id != :excludedFolderId)")
    suspend fun folderNameExistsInParentExcluding(
        scope: ChatFolderScope,
        parentFolderId: String?,
        name: String,
        excludedFolderId: String,
    ): Boolean

    @Query("SELECT * FROM chat_folders WHERE scope = :scope AND parentFolderId IS :parentFolderId AND name = :name LIMIT 1")
    suspend fun getFolderByNameInParent(
        scope: ChatFolderScope,
        parentFolderId: String?,
        name: String,
    ): ChatFolderEntity?
}
