package com.ai.assistance.operit.data.repository

import com.ai.assistance.operit.data.model.ChatFolderEntity
import com.ai.assistance.operit.data.model.ChatFolderScope
import com.ai.assistance.operit.data.model.ChatPlacementEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFolderLifecyclePolicyTest {
    private fun folder(
        id: String,
        parentId: String? = null,
        order: Long = 0,
    ) = ChatFolderEntity(
        id = id,
        scope = ChatFolderScope.ALL,
        name = id,
        parentFolderId = parentId,
        displayOrder = order,
    )

    private fun placement(
        chatId: String,
        folderId: String?,
    ) = ChatPlacementEntity(
        chatId = chatId,
        scope = ChatFolderScope.ALL,
        folderId = folderId,
        displayOrder = 0,
    )

    @Test
    fun parentWithNonEmptyChild_isNotEmpty() {
        val folders = listOf(folder("parent"), folder("child", parentId = "parent"))
        val placements = listOf(placement("chat", folderId = "child"))

        val deleted =
            calculateEmptyFoldersAffectedBySources(
                folders = folders,
                placements = placements,
                sourceFolderIds = setOf("parent"),
            )

        assertEquals(emptyList<String>(), deleted.map { it.id })
    }

    @Test
    fun removingLastDescendantChat_deletesLeafThenEmptyAncestors() {
        val folders = listOf(folder("parent"), folder("child", parentId = "parent"))

        val deleted =
            calculateEmptyFoldersAffectedBySources(
                folders = folders,
                placements = emptyList(),
                sourceFolderIds = setOf("child"),
            )

        assertEquals(listOf("child", "parent"), deleted.map { it.id })
    }

    @Test
    fun chatInSiblingBranch_preservesParentButDeletesEmptyLeaf() {
        val folders =
            listOf(
                folder("parent"),
                folder("empty", parentId = "parent"),
                folder("occupied", parentId = "parent", order = 1),
            )
        val placements = listOf(placement("chat", folderId = "occupied"))

        val deleted =
            calculateEmptyFoldersAffectedBySources(
                folders = folders,
                placements = placements,
                sourceFolderIds = setOf("empty"),
            )

        assertEquals(listOf("empty"), deleted.map { it.id })
    }

    @Test
    fun rootPlacement_doesNotKeepAnEmptyFolderAlive() {
        val folders = listOf(folder("folder"))
        val placements = listOf(placement("chat", folderId = null))

        val deleted =
            calculateEmptyFoldersAffectedBySources(
                folders = folders,
                placements = placements,
                sourceFolderIds = setOf("folder"),
            )

        assertEquals(listOf("folder"), deleted.map { it.id })
    }
}
