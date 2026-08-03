package com.ai.assistance.operit.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.ChatKind
import com.ai.assistance.operit.data.model.SubagentRunEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubagentRunRepositoryAndroidTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: SubagentRunRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = SubagentRunRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun deletingNonRootSubagentSubtreeKeepsParentAndCascadesRuns() = runBlocking {
        insertChat("root", ChatKind.NORMAL)
        insertChat("subagent", ChatKind.SUBAGENT, parentChatId = "root")
        insertChat("nested-reviewer", ChatKind.SUBAGENT, parentChatId = "subagent")
        insertSubagentRun("run-subagent", "root", "subagent")
        insertSubagentRun("run-reviewer", "subagent", "nested-reviewer")

        val subtree = repository.getChatSubtreeChatIds("subagent").toSet()

        assertEquals(setOf("subagent", "nested-reviewer"), subtree)
        assertTrue(repository.deleteChatSubtree("subagent", subtree))
        assertEquals("root", database.chatDao().getChatById("root")?.id)
        assertNull(database.chatDao().getChatById("subagent"))
        assertNull(database.chatDao().getChatById("nested-reviewer"))
        assertNull(database.subagentRunDao().getById("run-subagent"))
        assertNull(database.subagentRunDao().getById("run-reviewer"))
    }

    @Test
    fun deletingBranchSubtreeKeepsParentAndRemovesNestedSubagent() = runBlocking {
        insertChat("root", ChatKind.NORMAL)
        insertChat("branch", ChatKind.BRANCH, parentChatId = "root")
        insertChat("nested-subagent", ChatKind.SUBAGENT, parentChatId = "branch")
        insertSubagentRun("run-nested", "branch", "nested-subagent")

        val subtree = repository.getChatSubtreeChatIds("branch").toSet()

        assertEquals(setOf("branch", "nested-subagent"), subtree)
        assertTrue(repository.deleteChatSubtree("branch", subtree))
        assertEquals("root", database.chatDao().getChatById("root")?.id)
        assertNull(database.chatDao().getChatById("branch"))
        assertNull(database.chatDao().getChatById("nested-subagent"))
        assertNull(database.subagentRunDao().getById("run-nested"))
    }

    @Test
    fun lockedDescendantRefusesWholeSubtree() = runBlocking {
        insertChat("root", ChatKind.NORMAL)
        insertChat("subagent", ChatKind.SUBAGENT, parentChatId = "root")
        insertChat("locked-reviewer", ChatKind.SUBAGENT, parentChatId = "subagent", locked = true)
        insertSubagentRun("run", "root", "subagent")

        val subtree = repository.getChatSubtreeChatIds("subagent").toSet()

        assertFalse(repository.deleteChatSubtree("subagent", subtree))
        assertEquals(
            setOf("root", "subagent", "locked-reviewer"),
            database.chatDao().getAllChatsDirectly().map { it.id }.toSet(),
        )
        assertEquals("run", database.subagentRunDao().getById("run")?.id)
    }

    @Test
    fun staleExpectedSubtreeAbortsDeletion() = runBlocking {
        insertChat("root", ChatKind.NORMAL)
        insertChat("subagent", ChatKind.SUBAGENT, parentChatId = "root")
        val stale = setOf("subagent")

        database.chatDao().insertChat(
            ChatEntity(
                id = "late-branch",
                title = "late-branch",
                parentChatId = "subagent",
                chatKind = ChatKind.BRANCH.name,
            )
        )

        try {
            repository.deleteChatSubtree("subagent", stale)
            fail("Expected IllegalStateException for stale subtree")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        assertEquals(
            setOf("subagent", "late-branch"),
            database.chatDao().getAllChatsDirectly()
                .filter { it.parentChatId == "root" || it.parentChatId == "subagent" }
                .map { it.id }
                .toSet(),
        )
        assertEquals("subagent", database.chatDao().getChatById("subagent")?.id)
        assertEquals("late-branch", database.chatDao().getChatById("late-branch")?.id)
    }

    @Test
    fun deletingRootSubagentChatAlsoDeletesGrandchildren() = runBlocking {
        insertChat("root", ChatKind.NORMAL)
        insertChat("subagent", ChatKind.SUBAGENT, parentChatId = "root")
        insertChat("reviewer", ChatKind.SUBAGENT, parentChatId = "subagent")
        insertChat("deep-branch", ChatKind.BRANCH, parentChatId = "reviewer")

        val subtree = repository.getChatSubtreeChatIds("subagent").toSet()

        assertEquals(setOf("subagent", "reviewer", "deep-branch"), subtree)
        assertTrue(repository.deleteChatSubtree("subagent", subtree))
        assertEquals("root", database.chatDao().getChatById("root")?.id)
        assertEquals(1, database.chatDao().getTotalChatCount())
    }

    private suspend fun insertChat(
        id: String,
        chatKind: ChatKind,
        parentChatId: String? = null,
        locked: Boolean = false,
    ) {
        database.chatDao().insertChat(
            ChatEntity(
                id = id,
                title = id,
                parentChatId = parentChatId,
                chatKind = chatKind.name,
                locked = locked,
            )
        )
    }

    private suspend fun insertSubagentRun(id: String, parentChatId: String, childChatId: String) {
        database.subagentRunDao().insert(
            SubagentRunEntity(
                id = id,
                parentChatId = parentChatId,
                childChatId = childChatId,
                agentProfileId = "general",
                title = id,
            )
        )
    }
}
