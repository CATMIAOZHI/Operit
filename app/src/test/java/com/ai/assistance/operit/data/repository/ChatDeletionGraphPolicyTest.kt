package com.ai.assistance.operit.data.repository

import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.ChatKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDeletionGraphPolicyTest {
    @Test
    fun lockedNodeProtectsItsWholeGraph() {
        val parent = chat("parent", locked = true)
        val child = chat("child", parentId = parent.id)
        val sibling = chat("sibling", parentId = parent.id)

        val selected =
            ChatDeletionGraphPolicy.selectDeletableChats(
                listOf(parent, child, sibling),
                setOf(parent.id, child.id, sibling.id),
            )

        assertEquals(emptyList<ChatEntity>(), selected)
    }

    @Test
    fun nodeOutsideScopeProtectsItsWholeGraph() {
        val parent = chat("parent")
        val child = chat("child", parentId = parent.id)

        val selected =
            ChatDeletionGraphPolicy.selectDeletableChats(
                listOf(parent, child),
                setOf(parent.id),
            )

        assertEquals(emptyList<ChatEntity>(), selected)
    }

    @Test
    fun completeUnlockedGraphIsOrderedChildFirst() {
        val parent = chat("parent")
        val child = chat("child", parentId = parent.id)
        val grandchild = chat("grandchild", parentId = child.id)
        val selected =
            ChatDeletionGraphPolicy.selectDeletableChats(
                listOf(parent, child, grandchild),
                setOf(parent.id, child.id, grandchild.id),
            )

        assertEquals(
            listOf("grandchild", "child", "parent"),
            ChatDeletionGraphPolicy.orderChildFirst(selected).map(ChatEntity::id),
        )
    }

    @Test
    fun descendantClosureCoversMixedBranchAndSubagentNesting() {
        val childrenById =
            mapOf<String?, List<String>>(
                "root" to listOf("subagent-a", "branch-b"),
                "branch-b" to listOf("subagent-c"),
                "subagent-c" to listOf("nested-branch-d"),
                null to listOf("unrelated"),
            )

        assertEquals(
            setOf("root", "subagent-a", "branch-b", "subagent-c", "nested-branch-d"),
            ChatDeletionGraphPolicy.descendantClosure("root", childrenById),
        )
        assertEquals(setOf("unrelated"), ChatDeletionGraphPolicy.descendantClosure("unrelated", childrenById))
        assertEquals(setOf("missing"), ChatDeletionGraphPolicy.descendantClosure("missing", childrenById))
    }

    @Test
    fun descendantClosureTerminatesOnCycles() {
        val childrenById =
            mapOf<String?, List<String>>(
                "a" to listOf("b"),
                "b" to listOf("c"),
                "c" to listOf("a"),
            )

        assertEquals(
            setOf("a", "b", "c"),
            ChatDeletionGraphPolicy.descendantClosure("a", childrenById),
        )
    }

    private fun chat(
        id: String,
        parentId: String? = null,
        locked: Boolean = false,
    ) =
        ChatEntity(
            id = id,
            title = id,
            parentChatId = parentId,
            chatKind = if (parentId == null) ChatKind.NORMAL.name else ChatKind.SUBAGENT.name,
            locked = locked,
        )
}
