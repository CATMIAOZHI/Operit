package com.ai.assistance.operit.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.ChatKind
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
                        """<tool_result name="task" call_id="call-1"></tool_result>""",
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
        assertEquals(3, copiedRun.toolInvocationCount)
        assertNotEquals(child.id, copiedRun.childChatId)
        val copiedChild = database.chatDao().getChatById(copiedRun.childChatId)
        assertNotNull(copiedChild)
        assertEquals(branch.id, copiedChild?.parentChatId)
        assertEquals(ChatKind.SUBAGENT.name, copiedChild?.chatKind)
        assertEquals(
            "child result",
            database.messageDao().getMessagesForChat(copiedRun.childChatId).single().content,
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
