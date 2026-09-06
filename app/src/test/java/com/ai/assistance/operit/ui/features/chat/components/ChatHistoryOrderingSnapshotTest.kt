package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.ChatFolderEntity
import com.ai.assistance.operit.data.model.ChatKind
import com.ai.assistance.operit.data.repository.HistorySiblingSnapshot
import com.ai.assistance.operit.data.repository.HistorySiblingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryOrderingSnapshotTest {
    @Test
    fun ungroupedDropAnchorsToVisibleChatEvenWhenAuditRootPrecedesFolders() {
        val folder = ChatFolderEntity("folder", "Folder", null, 0L, 0L)
        val audit = ChatEntity(id = "audit", title = "Audit", displayOrder = -1L, isHidden = true)
        val visible = ChatEntity(id = "visible", title = "Visible", displayOrder = 1L)
        val histories = listOf(audit, visible).map { it.toChatHistory(emptyList()) }
        val visibleSnapshot = buildVisibleHistorySiblingSnapshot(null, listOf(folder), histories)

        assertEquals(
            listOf("folder:folder", "chat:visible"),
            visibleSnapshot.map { it.stableKey },
        )
        assertEquals(
            "chat:visible",
            visibleSnapshot.first { it.kind == HistorySiblingKind.CHAT }.stableKey,
        )
    }

    @Test
    fun hiddenOnlyTargetIsVisuallyEmptyButHasMutationSiblings() {
        val target = ChatFolderEntity("target", "Target", null, 0L, 0L)
        val audit =
            ChatEntity(id = "audit", title = "Audit", folderId = target.id, isHidden = true)
        val histories = listOf(audit.toChatHistory(emptyList()))

        assertTrue(buildVisibleHistorySiblingSnapshot(target.id, listOf(target), histories).isEmpty())
        assertEquals(
            listOf(HistorySiblingSnapshot.fromChat(audit)),
            buildHistorySiblingSnapshot(target.id, listOf(target), histories),
        )
        val child = ChatFolderEntity("child", "Child", target.id, 0L, 0L)
        assertEquals(
            listOf(HistorySiblingSnapshot.fromFolder(child)),
            buildVisibleHistorySiblingSnapshot(target.id, listOf(target, child), histories),
        )
    }

    @Test
    fun rootMoveSnapshotIncludesReadingAuditRootButExcludesSubagentChildren() {
        val moving = ChatFolderEntity("moving", "Folder", null, 0L, 0L)
        val auditRoot =
            ChatEntity(
                id = "audit-root",
                title = "Audit",
                displayOrder = -1L,
                isHidden = true,
                hiddenReason = "READING_COMPANION_AUDIT_ROOT:book",
            )
        val subagent = auditRoot.copy(id = "subagent", chatKind = ChatKind.SUBAGENT.name)
        val visible = ChatEntity(id = "visible", title = "Chat", displayOrder = 1L)
        val histories = listOf(visible, subagent, auditRoot).map { it.toChatHistory(emptyList()) }
        val expected =
            listOf(
                HistorySiblingSnapshot.fromChat(auditRoot),
                HistorySiblingSnapshot.fromFolder(moving),
                HistorySiblingSnapshot.fromChat(visible),
            )

        assertEquals(expected, buildHistorySiblingSnapshot(null, listOf(moving), histories))
        // The former UI input permanently disagreed with the repository even without a race.
        assertNotEquals(
            expected,
            buildHistorySiblingSnapshot(null, listOf(moving), histories.filterNot { it.isHidden }),
        )
    }

    @Test
    fun nestedTargetSnapshotPreservesHiddenBranchAndConflictMetadata() {
        val target = ChatFolderEntity("target", "Target", null, 0L, 0L)
        val child = ChatFolderEntity("child", "Child", target.id, 1L, 0L)
        val hiddenBranch =
            ChatEntity(
                id = "branch",
                title = "Hidden branch",
                folderId = target.id,
                displayOrder = 1L,
                chatKind = ChatKind.BRANCH.name,
                isHidden = true,
                pinned = true,
                isFavorite = true,
                characterCardName = "Card",
                characterGroupId = "group",
            )
        val outside = hiddenBranch.copy(id = "outside", folderId = null)
        val histories = listOf(outside, hiddenBranch).map { it.toChatHistory(emptyList()) }
        val snapshot = buildHistorySiblingSnapshot(target.id, listOf(target, child), histories)

        assertEquals(
            listOf(
                HistorySiblingSnapshot.fromFolder(child),
                HistorySiblingSnapshot.fromChat(hiddenBranch),
            ),
            snapshot,
        )
        assertNotEquals(
            snapshot,
            buildHistorySiblingSnapshot(
                target.id,
                listOf(target, child),
                listOf(hiddenBranch.copy(displayOrder = 2L).toChatHistory(emptyList())),
            ),
        )
    }
}
