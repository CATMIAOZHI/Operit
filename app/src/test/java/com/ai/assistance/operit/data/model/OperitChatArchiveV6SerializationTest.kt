package com.ai.assistance.operit.data.model

import com.ai.assistance.operit.data.repository.OPERIT_ARCHIVE_CHAT_TODO_SNAPSHOT_COLUMNS
import com.ai.assistance.operit.data.repository.OPERIT_ARCHIVE_SUBAGENT_RUN_SNAPSHOT_COLUMNS
import java.time.LocalDateTime
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperitChatArchiveV6SerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun legacyArchivedChatWithoutTodosDefaultsToEmptySnapshot() {
        val decoded =
            json.decodeFromString<OperitArchivedChat>(
                """{"id":"legacy","title":"Legacy","messages":[]}"""
            )

        assertTrue(decoded.todos.isEmpty())
    }

    @Test
    fun snapshotProjectionsIncludePersistedMetadata() {
        assertTrue("toolInvocationCount" in OPERIT_ARCHIVE_SUBAGENT_RUN_SNAPSHOT_COLUMNS)
        assertTrue("archivedAt" in OPERIT_ARCHIVE_SUBAGENT_RUN_SNAPSHOT_COLUMNS)
        assertEquals(
            listOf("chatId", "position", "content", "status", "priority"),
            OPERIT_ARCHIVE_CHAT_TODO_SNAPSHOT_COLUMNS,
        )
    }

    @Test
    fun roundTripPreservesChatKindSubagentRunAndTodos() {
        val todo =
            OperitArchivedTodo(
                content = "Inspect archive",
                status = ChatTodoStatus.IN_PROGRESS,
                priority = ChatTodoPriority.HIGH,
            )
        val parent =
            OperitArchivedChat(
                id = "parent",
                title = "Parent",
                messages = emptyList(),
                todos = listOf(todo),
                createdAt = LocalDateTime.of(2026, 7, 30, 1, 2),
                updatedAt = LocalDateTime.of(2026, 7, 30, 1, 3),
                chatKind = ChatKind.NORMAL.name,
            )
        val child =
            OperitArchivedChat(
                id = "child",
                title = "Inspect",
                messages = emptyList(),
                createdAt = LocalDateTime.of(2026, 7, 30, 1, 4),
                updatedAt = LocalDateTime.of(2026, 7, 30, 1, 5),
                parentChatId = parent.id,
                chatKind = ChatKind.SUBAGENT.name,
            )
        val run =
            OperitArchivedSubagentRun(
                id = "task",
                parentChatId = parent.id,
                childChatId = child.id,
                parentToolCallId = "call",
                agentProfileId = "explore",
                title = child.title,
                status = SubagentRunStatus.COMPLETED.name,
                createdAt = 10,
                completedAt = 20,
                agentConfigSnapshot = """{"id":"explore"}""",
                modelConfigIdSnapshot = "model-config",
                modelIndexSnapshot = 1,
                toolInvocationCount = 4,
                archivedAt = 25,
            )
        val archive =
            OperitChatArchive(
                exportedAt = 30,
                chats = listOf(parent, child),
                subagentRuns = listOf(run),
            )

        val decoded = json.decodeFromString<OperitChatArchive>(json.encodeToString(archive))

        assertEquals(6, decoded.formatVersion)
        assertEquals(ChatKind.SUBAGENT.name, decoded.chats.single { it.id == "child" }.chatKind)
        assertEquals(todo, decoded.chats.single { it.id == "parent" }.todos.single())
        assertEquals(run, decoded.subagentRuns.single())
    }
}
