package com.ai.assistance.operit.data.repository

import com.ai.assistance.operit.data.model.ChatFolderScope
import com.ai.assistance.operit.data.model.ChatPlacementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatPlacementReorderPolicyTest {
    private fun placement(
        chatId: String,
        scope: ChatFolderScope = ChatFolderScope.ALL,
        folderId: String? = "folder",
        order: Long,
    ) = ChatPlacementEntity(
        chatId = chatId,
        scope = scope,
        folderId = folderId,
        displayOrder = order,
    )

    @Test
    fun movingMiddleChatUp_preservesFolderAndScope() {
        val reordered =
            reorderPlacementWithinCurrentFolder(
                placements =
                    listOf(
                        placement("a", order = 10),
                        placement("b", order = 20),
                        placement("c", order = 30),
                    ),
                chatId = "b",
                delta = -1,
            )

        assertEquals(listOf("b", "a", "c"), reordered?.map { it.chatId })
        assertEquals(listOf(0L, 1L, 2L), reordered?.map { it.displayOrder })
        assertEquals(setOf("folder"), reordered?.map { it.folderId }?.toSet())
        assertEquals(setOf(ChatFolderScope.ALL), reordered?.map { it.scope }?.toSet())
    }

    @Test
    fun movingMiddleChatDown_preservesFavoritePlacement() {
        val reordered =
            reorderPlacementWithinCurrentFolder(
                placements =
                    listOf(
                        placement(
                            "a",
                            scope = ChatFolderScope.FAVORITE,
                            folderId = "favorite-folder",
                            order = 0,
                        ),
                        placement(
                            "b",
                            scope = ChatFolderScope.FAVORITE,
                            folderId = "favorite-folder",
                            order = 1,
                        ),
                        placement(
                            "c",
                            scope = ChatFolderScope.FAVORITE,
                            folderId = "favorite-folder",
                            order = 2,
                        ),
                    ),
                chatId = "b",
                delta = 1,
            )

        assertEquals(listOf("a", "c", "b"), reordered?.map { it.chatId })
        assertEquals(
            setOf("favorite-folder"),
            reordered?.map { it.folderId }?.toSet(),
        )
        assertEquals(
            setOf(ChatFolderScope.FAVORITE),
            reordered?.map { it.scope }?.toSet(),
        )
    }

    @Test
    fun movingBeyondSiblingBounds_isNoOp() {
        val placements =
            listOf(
                placement("a", order = 0),
                placement("b", order = 1),
            )

        assertNull(
            reorderPlacementWithinCurrentFolder(
                placements = placements,
                chatId = "a",
                delta = -1,
            )
        )
        assertNull(
            reorderPlacementWithinCurrentFolder(
                placements = placements,
                chatId = "b",
                delta = 1,
            )
        )
    }

    @Test
    fun missingPlacement_isNoOp() {
        assertNull(
            reorderPlacementWithinCurrentFolder(
                placements = listOf(placement("a", order = 0)),
                chatId = "missing",
                delta = 1,
            )
        )
    }
}
