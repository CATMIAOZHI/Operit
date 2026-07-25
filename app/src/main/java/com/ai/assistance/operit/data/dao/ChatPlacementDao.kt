package com.ai.assistance.operit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.assistance.operit.data.model.ChatFolderScope
import com.ai.assistance.operit.data.model.ChatPlacementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatPlacementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlacement(placement: ChatPlacementEntity)

    @Query("SELECT * FROM chat_placements WHERE scope = :scope AND folderId IS :folderId ORDER BY displayOrder ASC")
    suspend fun getPlacementsInFolder(scope: ChatFolderScope, folderId: String?): List<ChatPlacementEntity>

    @Query("SELECT * FROM chat_placements WHERE scope = :scope AND folderId IS NULL ORDER BY displayOrder ASC")
    suspend fun getPlacementsInRoot(scope: ChatFolderScope): List<ChatPlacementEntity>

    @Query("SELECT * FROM chat_placements WHERE scope = :scope ORDER BY displayOrder ASC")
    suspend fun getAllPlacements(scope: ChatFolderScope): List<ChatPlacementEntity>

    @Query("SELECT * FROM chat_placements WHERE scope = :scope ORDER BY displayOrder ASC")
    fun observeAllPlacements(scope: ChatFolderScope): Flow<List<ChatPlacementEntity>>

    @Query("SELECT * FROM chat_placements WHERE chatId = :chatId AND scope = :scope LIMIT 1")
    suspend fun getPlacement(chatId: String, scope: ChatFolderScope): ChatPlacementEntity?

    @Query("SELECT * FROM chat_placements WHERE chatId = :chatId")
    suspend fun getPlacementsForChat(chatId: String): List<ChatPlacementEntity>

    @Query("SELECT chatId FROM chat_placements WHERE scope = :scope")
    suspend fun getAllChatIdsInScope(scope: ChatFolderScope): List<String>

    @Query("SELECT * FROM chat_placements WHERE scope = :scope AND folderId IS :folderId ORDER BY displayOrder ASC")
    fun observePlacementsInFolder(scope: ChatFolderScope, folderId: String?): Flow<List<ChatPlacementEntity>>

    @Query("SELECT * FROM chat_placements WHERE scope = :scope AND folderId IS NULL ORDER BY displayOrder ASC")
    fun observePlacementsInRoot(scope: ChatFolderScope): Flow<List<ChatPlacementEntity>>

    @Query("UPDATE chat_placements SET folderId = :folderId, displayOrder = :displayOrder WHERE chatId = :chatId AND scope = :scope")
    suspend fun movePlacement(chatId: String, scope: ChatFolderScope, folderId: String?, displayOrder: Long)

    @Query("UPDATE chat_placements SET displayOrder = :displayOrder WHERE chatId = :chatId AND scope = :scope")
    suspend fun updatePlacementOrder(chatId: String, scope: ChatFolderScope, displayOrder: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlacements(placements: List<ChatPlacementEntity>)

    @Query("DELETE FROM chat_placements WHERE chatId = :chatId AND scope = :scope")
    suspend fun deletePlacement(chatId: String, scope: ChatFolderScope)

    @Query("UPDATE chat_placements SET folderId = :newFolderId WHERE folderId = :oldFolderId")
    suspend fun reparentPlacementsToFolder(oldFolderId: String, newFolderId: String?)
}
