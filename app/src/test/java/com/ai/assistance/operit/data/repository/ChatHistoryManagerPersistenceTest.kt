package com.ai.assistance.operit.data.repository

import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.SYSTEM_UNGROUPED_FOLDER_ID
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryManagerPersistenceTest {
    @Test
    fun `system ungrouped folder id is normalized to null`() {
        assertEquals(null, normalizeChatFolderId(SYSTEM_UNGROUPED_FOLDER_ID))
        assertEquals("regular", normalizeChatFolderId("regular"))
    }

    @Test
    fun `normal save preserves repository owned folder placement`() {
        val existing =
            ChatEntity(
                id = "chat",
                title = "before",
                folderId = "new-folder",
                displayOrder = 7,
                group = "legacy",
            )
        val staleIncoming =
            existing.copy(
                title = "after",
                folderId = null,
                displayOrder = 0,
                group = "stale-group",
            )

        val merged =
            mergePersistedChatEntity(
                incoming = staleIncoming,
                existing = existing,
                preserveStructure = true,
            )

        assertEquals("after", merged.title)
        assertEquals("new-folder", merged.folderId)
        assertEquals(7, merged.displayOrder)
        assertEquals("legacy", merged.group)
    }

    @Test
    fun `archive import may intentionally replace folder placement`() {
        val existing =
            ChatEntity(
                id = "chat",
                title = "before",
                folderId = "local-folder",
                displayOrder = 7,
                group = "legacy",
            )
        val imported =
            existing.copy(
                folderId = "archive-folder",
                displayOrder = 2,
                group = null,
            )

        val merged =
            mergePersistedChatEntity(
                incoming = imported,
                existing = existing,
                preserveStructure = false,
            )

        assertEquals("archive-folder", merged.folderId)
        assertEquals(2, merged.displayOrder)
        assertEquals("legacy", merged.group)
    }
}
