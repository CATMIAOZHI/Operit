package com.ai.assistance.operit.features.reading

import com.ai.assistance.operit.data.model.ChatEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 4 prune 联动（隐藏 child 删除 / 对话内 child 保留 / 空根删除）的纯 JVM 切片测试。
 *
 * 生产路径：ReadingCompanionStore.pruneAutoCommentRuns 先收集被删 run 的
 * child_chat_id/book_id 挂账 -> flushPrunedRunChatCleanup 用 ChatHistoryManager 子树删除。
 * 本测试直接驱动与生产同一份决策类 [ReadingCompanionPruneCleanup]。
 */
class ReadingCompanionPruneCleanupTest {

    private fun hiddenChat(
        id: String,
        hiddenReason: String,
        parentChatId: String? = null,
    ) = ChatEntity(
        id = id,
        title = "chat-$id",
        parentChatId = parentChatId,
        isHidden = true,
        hiddenReason = hiddenReason,
    )

    @Test
    fun `hidden audit child is deleted and conversation child is kept`() = runBlocking {
        val hiddenChild = hiddenChat("child-1", ReadingCompanionAudit.runHiddenReason(11))
        val conversationChild =
            ChatEntity(
                id = "child-2",
                title = "conversation child",
                parentChatId = "user-chat",
                isHidden = false,
                hiddenReason = null,
            )
        val deleted = mutableListOf<String>()
        val cleanup =
            ReadingCompanionPruneCleanup(
                listHiddenChats = { listOf(hiddenChild, conversationChild) },
                deleteChat = { chatId ->
                    deleted += chatId
                    true
                },
            )
        val outcome =
            cleanup.run(
                listOf(
                    PrunedRunChatRef("child-1", "book-1"),
                    PrunedRunChatRef("child-2", "book-1"),
                ),
            )
        assertEquals(listOf("child-1"), deleted)
        assertEquals(listOf("child-1"), outcome.deletedChildChatIds)
        assertTrue(outcome.deletedRootChatIds.isEmpty())
    }

    @Test
    fun `unrelated hidden chat and unknown ids are never deleted`() = runBlocking {
        val hiddenChild = hiddenChat("child-1", ReadingCompanionAudit.runHiddenReason(11))
        val userHidden =
            hiddenChat("user-hidden", "some_other_reason")
        val deleted = mutableListOf<String>()
        val cleanup =
            ReadingCompanionPruneCleanup(
                listHiddenChats = { listOf(hiddenChild, userHidden) },
                deleteChat = { chatId ->
                    deleted += chatId
                    true
                },
            )
        val outcome =
            cleanup.run(
                listOf(
                    PrunedRunChatRef("child-1", "book-1"),
                    PrunedRunChatRef("user-hidden", "book-1"),
                    PrunedRunChatRef("never-existed", "book-1"),
                ),
            )
        assertEquals(listOf("child-1"), deleted)
        assertEquals(listOf("child-1"), outcome.deletedChildChatIds)
        assertTrue(outcome.deletedRootChatIds.isEmpty())
    }

    @Test
    fun `root is deleted only after its last hidden child is gone`() = runBlocking {
        val root = hiddenChat("root-1", ReadingCompanionAudit.rootHiddenReason("book-1"))
        val childA = hiddenChat("child-a", ReadingCompanionAudit.runHiddenReason(21), "root-1")
        val childB = hiddenChat("child-b", ReadingCompanionAudit.runHiddenReason(22), "root-1")
        val state = mutableListOf(root, childA, childB)
        val deleted = mutableListOf<String>()
        val cleanup =
            ReadingCompanionPruneCleanup(
                listHiddenChats = { state.toList() },
                deleteChat = { chatId ->
                    deleted += chatId
                    state.removeAll { it.id == chatId }
                    true
                },
            )
        // 只删 child-a：root 下仍有 child-b（例如 generating 行未剪），root 必须保留。
        val first = cleanup.run(listOf(PrunedRunChatRef("child-a", "book-1")))
        assertEquals(listOf("child-a"), first.deletedChildChatIds)
        assertTrue("还有剩余 hidden child 时不得删根", first.deletedRootChatIds.isEmpty())
        // 再删 child-b：root 下已无 hidden child，根应被删。
        val second = cleanup.run(listOf(PrunedRunChatRef("child-b", "book-1")))
        assertEquals(listOf("child-b"), second.deletedChildChatIds)
        assertEquals(listOf("root-1"), second.deletedRootChatIds)
    }

    @Test
    fun `empty pending list short-circuits without db access`() = runBlocking {
        var listCalls = 0
        val cleanup =
            ReadingCompanionPruneCleanup(
                listHiddenChats = {
                    listCalls += 1
                    emptyList()
                },
                deleteChat = { true },
            )
        val outcome = cleanup.run(emptyList())
        assertEquals(0, listCalls)
        assertTrue(outcome.deletedChildChatIds.isEmpty())
        assertTrue(outcome.deletedRootChatIds.isEmpty())
    }
}
