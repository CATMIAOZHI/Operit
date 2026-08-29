package com.ai.assistance.operit.features.reading

import com.ai.assistance.operit.data.model.ChatEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终审 BLOCKING-3：剪枝挂账的幂等孤儿重建清理。
 *
 * 覆盖审计要求的场景——"临时 Store 实例 prune 后无 flush / 进程在 flush 前退出"：挂账队列
 * 丢失时，hidden 审计 child 与空根仍能借助 reading.db 现存 run 状态与主库 hiddenReason
 * 从现状重建并确定性清理（不依赖任何实例内存）。删除走注入的子树删除；对话内 child 绝不删。
 */
class ReadingCompanionOrphanCleanupTest {

    private fun chat(
        id: String,
        hiddenReason: String?,
        parentChatId: String? = null,
        isHidden: Boolean = true,
    ) = ChatEntity(
        id = id,
        title = "chat-$id",
        parentChatId = parentChatId,
        isHidden = isHidden,
        hiddenReason = hiddenReason,
    )

    @Test
    fun `orphan child with missing run is deleted and empty root is deleted`() = runBlocking {
        val root = chat("root-1", ReadingCompanionAudit.rootHiddenReason("book-1"))
        val orphan =
            chat(
                "child-1",
                ReadingCompanionAudit.runHiddenReason(99),
                parentChatId = "root-1",
            )
        val state = mutableListOf(root, orphan)
        val deleted = mutableListOf<String>()
        val cleanup =
            ReadingCompanionOrphanChatCleanup(
                listHiddenChats = { state.toList() },
                runExists = { false },
                deleteChat = { id ->
                    deleted += id
                    state.removeAll { it.id == id }
                    true
                },
            )
        val outcome = cleanup.run()
        assertEquals(listOf("child-1", "root-1"), deleted)
        assertEquals(listOf("child-1"), outcome.deletedChildChatIds)
        assertEquals(listOf("root-1"), outcome.deletedRootChatIds)
    }

    @Test
    fun `child whose run still exists survives`() = runBlocking {
        val root = chat("root-1", ReadingCompanionAudit.rootHiddenReason("book-1"))
        val child =
            chat(
                "child-1",
                ReadingCompanionAudit.runHiddenReason(7),
                parentChatId = "root-1",
            )
        val deleted = mutableListOf<String>()
        val cleanup =
            ReadingCompanionOrphanChatCleanup(
                listHiddenChats = { listOf(root, child) },
                runExists = { it == 7L },
                deleteChat = { id ->
                    deleted += id
                    true
                },
            )
        val outcome = cleanup.run()
        assertTrue(deleted.isEmpty())
        assertTrue(outcome.deletedChildChatIds.isEmpty())
        assertTrue(outcome.deletedRootChatIds.isEmpty())
    }

    @Test
    fun `conversation child is never deleted even with a run prefix reason`() = runBlocking {
        val conversationChild =
            chat(
                "child-1",
                ReadingCompanionAudit.runHiddenReason(99),
                isHidden = false,
            )
        val deleted = mutableListOf<String>()
        val cleanup =
            ReadingCompanionOrphanChatCleanup(
                listHiddenChats = { listOf(conversationChild) },
                runExists = { false },
                deleteChat = { id ->
                    deleted += id
                    true
                },
            )
        val outcome = cleanup.run()
        assertTrue(deleted.isEmpty())
        assertTrue(outcome.deletedChildChatIds.isEmpty())
        assertTrue(outcome.deletedRootChatIds.isEmpty())
    }

    @Test
    fun `root with remaining hidden children survives`() = runBlocking {
        val root = chat("root-1", ReadingCompanionAudit.rootHiddenReason("book-1"))
        val orphan =
            chat(
                "child-1",
                ReadingCompanionAudit.runHiddenReason(99),
                parentChatId = "root-1",
            )
        val live =
            chat(
                "child-2",
                ReadingCompanionAudit.runHiddenReason(7),
                parentChatId = "root-1",
            )
        val state = mutableListOf(root, orphan, live)
        val deleted = mutableListOf<String>()
        val cleanup =
            ReadingCompanionOrphanChatCleanup(
                listHiddenChats = { state.toList() },
                runExists = { it == 7L },
                deleteChat = { id ->
                    deleted += id
                    state.removeAll { it.id == id }
                    true
                },
            )
        val outcome = cleanup.run()
        assertEquals(listOf("child-1"), deleted)
        assertEquals(listOf("child-1"), outcome.deletedChildChatIds)
        assertTrue(outcome.deletedRootChatIds.isEmpty())
    }

    @Test
    fun `malformed run reason is skipped conservatively`() = runBlocking {
        val weird = chat("child-x", "READING_COMPANION_AUDIT_RUN:not-a-number")
        val deleted = mutableListOf<String>()
        val cleanup =
            ReadingCompanionOrphanChatCleanup(
                listHiddenChats = { listOf(weird) },
                runExists = { false },
                deleteChat = { id ->
                    deleted += id
                    true
                },
            )
        val outcome = cleanup.run()
        assertTrue(deleted.isEmpty())
        assertTrue(outcome.deletedChildChatIds.isEmpty())
    }

    @Test
    fun `orphan cleanup is wired at every queue loss entry point`() {
        val storeSource =
            File(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionStore.kt",
            ).readText()
        val autoCommentarySource =
            File(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionAutoCommentary.kt",
            ).readText()
        assertTrue(
            "启动对账（临时实例 prune 无 flush）必须重建清理",
            run {
                val startupSegment =
                    storeSource.substring(
                        storeSource.indexOf("suspend fun reconcileAfterProcessStart"),
                        storeSource.indexOf("suspend fun reconcileCrossDatabase"),
                    )
                startupSegment.contains("reconcileCrossDatabase()") &&
                    startupSegment.contains("runOrphanChatCleanup()")
            },
        )
        assertTrue(
            "生成 finally / settleInterruptedRuns / Worker 开头必须重建清理",
            autoCommentarySource.split("runOrphanChatCleanup()").size - 1 >= 4,
        )
    }
}
