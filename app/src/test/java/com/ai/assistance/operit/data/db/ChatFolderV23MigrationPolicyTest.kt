package com.ai.assistance.operit.data.db

import com.ai.assistance.operit.data.model.ChatFolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFolderV23MigrationPolicyTest {
    @Test
    fun repairV23Folders_repairsScopesParentsCyclesNamesAndOrders() {
        val repaired =
            repairV23Folders(
                listOf(
                    folder("favorite-parent", scope = "FAVORITE"),
                    folder("cross-scope", parentId = "favorite-parent"),
                    folder("cycle-a", parentId = "cycle-b"),
                    folder("cycle-b", parentId = "cycle-a"),
                    folder("root-a", name = "Work", order = 9),
                    folder("root-b", name = "Work", order = 9),
                    folder("invalid-scope", scope = "UNKNOWN", name = " "),
                )
            )
        val byId = repaired.associateBy(V23FolderRecord::id)

        assertNull(byId.getValue("cross-scope").parentFolderId)
        assertEquals("ALL", byId.getValue("invalid-scope").scope)
        assertEquals("Folder", byId.getValue("invalid-scope").name)
        assertEquals(setOf("Work", "Work (2)"), setOf(byId.getValue("root-a").name, byId.getValue("root-b").name))

        val cycleParents =
            setOf(
                byId.getValue("cycle-a").parentFolderId,
                byId.getValue("cycle-b").parentFolderId,
            )
        assertTrue(null in cycleParents)

        val rootOrders =
            repaired
                .filter { it.scope == "ALL" && it.parentFolderId == null }
                .map { it.displayOrder }
        assertEquals(rootOrders.indices.map(Int::toLong), rootOrders)
    }

    @Test
    fun parentKeyEncoding_cannotCollideWithAnEmptyFolderId() {
        assertNotEquals(
            ChatFolderEntity.parentKeyFor(null),
            ChatFolderEntity.parentKeyFor(""),
        )
    }

    private fun folder(
        id: String,
        scope: String = "ALL",
        name: String = id,
        parentId: String? = null,
        order: Long = 0,
    ) = V23FolderRecord(
        id = id,
        scope = scope,
        name = name,
        parentFolderId = parentId,
        displayOrder = order,
        pinned = false,
    )
}
