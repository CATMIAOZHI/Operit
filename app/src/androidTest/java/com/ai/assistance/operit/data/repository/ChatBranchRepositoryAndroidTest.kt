package com.ai.assistance.operit.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.ChatKind
import com.ai.assistance.operit.data.model.ChatTodoEntity
import com.ai.assistance.operit.data.model.ChatTodoPriority
import com.ai.assistance.operit.data.model.ChatTodoStatus
import com.ai.assistance.operit.data.model.MessageEntity
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.data.model.SubagentRunStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatBranchRepositoryAndroidTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ChatBranchRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = ChatBranchRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun copiedTaskInvocationKeepsIndependentSubagentConversation() = runBlocking {
        val parent = chat("parent", ChatKind.NORMAL)
        val child = chat("child", ChatKind.SUBAGENT, parentChatId = parent.id)
        database.chatDao().insertChat(parent)
        database.chatDao().insertChat(child)
        database.messageDao().insertMessage(
            message(
                chatId = parent.id,
                timestamp = 10,
                content =
                    """<tool name="task" call_id="call-1"></tool>""" +
                        """<tool_result name="task" call_id="call-1">""" +
                        """<task id="run" state="completed"></task>""" +
                        """</tool_result>""" +
                        """<tool name="task"><param name="task_id">run</param></tool>""",
            )
        )
        database.messageDao().insertMessage(
            message(chatId = child.id, timestamp = 11, content = "child result")
        )
        database.subagentRunDao().insert(
            SubagentRunEntity(
                id = "run",
                parentChatId = parent.id,
                childChatId = child.id,
                parentToolCallId = "call-1",
                agentProfileId = "explore",
                title = "inspect",
                status = SubagentRunStatus.COMPLETED.name,
                toolInvocationCount = 3,
            )
        )
        val branch = chat("branch", ChatKind.BRANCH, parentChatId = parent.id)

        val result =
            repository.copyBranch(
                sourceChatId = parent.id,
                branch = branch,
                upToTimestampInclusive = null,
            )

        assertEquals(1, result.copiedMessageCount)
        assertEquals(1, result.copiedSubagentCount)
        val copiedRun = database.subagentRunDao().getByParentChatId(branch.id).single()
        val copiedParentContent =
            database.chatContentDao().getMessagesForChat(branch.id).single().content
        assertEquals(3, copiedRun.toolInvocationCount)
        assertEquals(false, copiedParentContent.contains("id=\"run\""))
        assertEquals(false, copiedParentContent.contains(">run</param>"))
        assertEquals(true, copiedParentContent.contains("id=\"${copiedRun.id}\""))
        assertEquals(true, copiedParentContent.contains(">${copiedRun.id}</param>"))
        assertNotEquals(child.id, copiedRun.childChatId)
        val copiedChild = database.chatDao().getChatById(copiedRun.childChatId)
        assertNotNull(copiedChild)
        assertEquals(branch.id, copiedChild?.parentChatId)
        assertEquals(ChatKind.SUBAGENT.name, copiedChild?.chatKind)
        assertEquals(
            "child result",
            database.chatContentDao().getMessagesForChat(copiedRun.childChatId).single().content,
        )
    }

    @Test
    fun toolInvocationCountIsIncrementedAtomicallyByChildChatId() = runBlocking {
        val parent = chat("parent", ChatKind.NORMAL)
        val child = chat("child", ChatKind.SUBAGENT, parentChatId = parent.id)
        database.chatDao().insertChat(parent)
        database.chatDao().insertChat(child)
        database.subagentRunDao().insert(
            SubagentRunEntity(
                id = "run",
                parentChatId = parent.id,
                childChatId = child.id,
                parentToolCallId = "call",
                agentProfileId = "explore",
                title = "inspect",
                status = SubagentRunStatus.RUNNING.name,
            )
        )

        assertEquals(1, database.subagentRunDao().incrementToolInvocationCountByChildChatId(child.id))
        assertEquals(1, database.subagentRunDao().incrementToolInvocationCountByChildChatId(child.id))
        assertEquals(0, database.subagentRunDao().incrementToolInvocationCountByChildChatId("normal"))
        assertEquals(2, database.subagentRunDao().getByChildChatId(child.id)?.toolInvocationCount)
    }

    @Test
    fun branchCopiesTodoSnapshotAndThenUpdatesIndependently() = runBlocking {
        val parent = chat("parent", ChatKind.NORMAL)
        val branch = chat("branch", ChatKind.BRANCH, parentChatId = parent.id)
        database.chatDao().insertChat(parent)
        database.chatTodoDao().insertAll(
            listOf(
                ChatTodoEntity(
                    chatId = parent.id,
                    position = 0,
                    content = "inspect",
                    status = ChatTodoStatus.IN_PROGRESS.name,
                    priority = ChatTodoPriority.HIGH.name,
                ),
                ChatTodoEntity(
                    chatId = parent.id,
                    position = 1,
                    content = "verify",
                    status = ChatTodoStatus.PENDING.name,
                    priority = ChatTodoPriority.MEDIUM.name,
                ),
            )
        )

        repository.copyBranch(parent.id, branch, upToTimestampInclusive = null)
        database.chatTodoDao().replaceForChat(
            parent.id,
            database.chatTodoDao().getByChatId(parent.id).map {
                it.copy(status = ChatTodoStatus.COMPLETED.name)
            },
        )

        val branchTodos = database.chatTodoDao().getByChatId(branch.id)
        assertEquals(listOf("inspect", "verify"), branchTodos.map { it.content })
        assertEquals(
            listOf(ChatTodoStatus.IN_PROGRESS.name, ChatTodoStatus.PENDING.name),
            branchTodos.map { it.status },
        )
        assertEquals(
            listOf(ChatTodoStatus.COMPLETED.name, ChatTodoStatus.COMPLETED.name),
            database.chatTodoDao().getByChatId(parent.id).map { it.status },
        )
    }

    @Test
    fun branchCutoffDoesNotCopySubagentReferencedOnlyByLaterMessages() = runBlocking {
        val parent = chat("parent", ChatKind.NORMAL)
        val child = chat("child", ChatKind.SUBAGENT, parentChatId = parent.id)
        database.chatDao().insertChat(parent)
        database.chatDao().insertChat(child)
        database.messageDao().insertMessages(
            listOf(
                message(chatId = parent.id, timestamp = 10, content = "before"),
                message(
                    chatId = parent.id,
                    timestamp = 20,
                    content = """<tool name="task" call_id="call-later"></tool>""",
                ),
            )
        )
        database.subagentRunDao().insert(
            SubagentRunEntity(
                id = "run",
                parentChatId = parent.id,
                childChatId = child.id,
                parentToolCallId = "call-later",
                agentProfileId = "explore",
                title = "later",
                status = SubagentRunStatus.COMPLETED.name,
            )
        )
        val branch = chat("branch", ChatKind.BRANCH, parentChatId = parent.id)

        val result =
            repository.copyBranch(
                sourceChatId = parent.id,
                branch = branch,
                upToTimestampInclusive = 10,
            )

        assertEquals(1, result.copiedMessageCount)
        assertEquals(0, result.copiedSubagentCount)
        assertEquals(emptyList<SubagentRunEntity>(), database.subagentRunDao().getByParentChatId(branch.id))
    }

    @Test
    fun activeSubagentRunsBecomeInterruptedBranchSnapshots() = runBlocking {
        val parent = chat("parent", ChatKind.NORMAL)
        database.chatDao().insertChat(parent)
        val activeStatuses =
            listOf(
                SubagentRunStatus.CREATED,
                SubagentRunStatus.QUEUED,
                SubagentRunStatus.RUNNING,
            )
        val callIds = activeStatuses.indices.map { "call-$it" }
        database.messageDao().insertMessage(
            message(
                chatId = parent.id,
                timestamp = 10,
                content =
                    callIds.joinToString(separator = "") { callId ->
                        """<tool name="task" call_id="$callId"></tool>"""
                    },
            )
        )
        activeStatuses.forEachIndexed { index, status ->
            val child = chat("child-$index", ChatKind.SUBAGENT, parentChatId = parent.id)
            database.chatDao().insertChat(child)
            database.subagentRunDao().insert(
                SubagentRunEntity(
                    id = "run-$index",
                    parentChatId = parent.id,
                    childChatId = child.id,
                    parentToolCallId = callIds[index],
                    agentProfileId = "explore",
                    title = status.name,
                    status = status.name,
                    createdAt = 5,
                )
            )
        }
        val branch = chat("branch", ChatKind.BRANCH, parentChatId = parent.id)

        val result =
            repository.copyBranch(
                sourceChatId = parent.id,
                branch = branch,
                upToTimestampInclusive = null,
            )

        assertEquals(activeStatuses.size, result.copiedSubagentCount)
        val copiedRuns = database.subagentRunDao().getByParentChatId(branch.id)
        assertEquals(activeStatuses.size, copiedRuns.size)
        copiedRuns.forEach { copiedRun ->
            assertEquals(SubagentRunStatus.INTERRUPTED.name, copiedRun.status)
            assertNotNull(copiedRun.completedAt)
            assertEquals(
                "This Subagent run was interrupted because its parent chat was branched.",
                copiedRun.error,
            )
        }
    }

    private fun chat(
        id: String,
        kind: ChatKind,
        parentChatId: String? = null,
    ) =
        ChatEntity(
            id = id,
            title = id,
            createdAt = 1,
            updatedAt = 1,
            displayOrder = 0,
            parentChatId = parentChatId,
            chatKind = kind.name,
        )

    private fun message(
        chatId: String,
        timestamp: Long,
        content: String,
    ) =
        MessageEntity(
            chatId = chatId,
            sender = "assistant",
            content = content,
            timestamp = timestamp,
            orderIndex = timestamp.toInt(),
        )
}
