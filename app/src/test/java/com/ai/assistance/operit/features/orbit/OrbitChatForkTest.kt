package com.ai.assistance.operit.features.orbit

import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import com.ai.assistance.operit.data.dao.OrbitForkSource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrbitChatForkTest {
    @Test fun referenceStopsAtSelectedMessageAndKeepsOnlyRecentVisibleWords() {
        val messages = (1L..12L).map { ChatMessage("user", "message $it", timestamp = it) } +
            ChatMessage("user", "hidden notice", timestamp = 9, displayMode = ChatMessageDisplayMode.HIDDEN_PLACEHOLDER) +
            ChatMessage("summary", "old summary", timestamp = 10)
        val preview = OrbitChatFork.excerpts(messages, 10)
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L, 10L), preview.map { it.messageId })
        assertTrue(preview.none { it.text == "hidden notice" || it.text == "old summary" })
        assertEquals("message 10", preview.last().text)
    }

    @Test fun longMessagesHaveABoundedPreviewWithoutChangingSourceHistory() {
        val original = ChatMessage("user", "前".repeat(12_000), timestamp = 1)
        val preview = OrbitChatFork.excerpts(listOf(original), 1).single()
        assertEquals(OrbitChatFork.MAX_MESSAGE_CHARS, preview.text.length)
        assertTrue(preview.truncated)
        assertEquals(12_000, original.content.length)
        assertEquals(original.timestamp, preview.messageId)
    }

    @Test fun inheritedAssistantWordsExcludeThinkingAndToolResults() {
        val source = ChatMessage("ai",
            "<think>private thought</think><tool name=\"shell\">hidden command</tool>" +
                "<tool_result name=\"shell\"><content>hidden output</content></tool_result>Keep this answer",
            timestamp = 1)
        val preview = OrbitChatFork.excerpts(listOf(source), 1).single()
        assertEquals("Keep this answer", preview.text)
        assertFalse(preview.truncated)
    }

    @Test fun anEmptyOrHiddenSourceCannotAppearAsAnInheritedMessage() {
        assertTrue(OrbitChatFork.excerpts(listOf(
            ChatMessage("ai", "<think>thinking only</think>", timestamp = 1),
            ChatMessage("user", "hidden", timestamp = 2, displayMode = ChatMessageDisplayMode.HIDDEN_PLACEHOLDER),
        ), 2).isEmpty())
        assertFalse(OrbitChatFork.canFork(ChatMessage("summary", "summary", timestamp = 3)))
    }

    @Test fun visibleAssistantWordsBeforeASteeringMessageRemainForkable() {
        val source = ChatMessage("ai", "This is where we left off.", timestamp = 1,
            displayMode = ChatMessageDisplayMode.ASSISTANT_INTERMEDIATE)
        assertTrue(OrbitChatFork.canFork(source))
        assertEquals(source.content, OrbitChatFork.excerpts(listOf(source), 1).single().text)
    }

    @Test fun bodiesOlderThanTheSixVisibleMessagesAreNeverLoaded() = runBlocking {
        val sources = (80L downTo 1L).map { OrbitForkSource(it, "user", "NORMAL", 0) }
        val read = mutableListOf<Long>()
        val preview = OrbitChatFork.collectExcerpts(sources, 80, true) {
            check(it.timestamp >= 75) { "Must not load an unused large historical body" }
            read += it.timestamp
            ChatMessage(it.sender, "short message", timestamp = it.timestamp)
        }
        assertEquals((80L downTo 75L).toList(), read)
        assertEquals((75L..80L).toList(), preview.map { it.messageId })
    }

    @Test fun cardPreviewUsesTheRequestedLanguage() {
        val source = ChatMessage("ai", "```a2ui\n{\"title\":\"card\"}\n```", timestamp = 1)
        assertEquals("[交互卡片]", OrbitChatFork.excerpts(listOf(source), 1, false).single().text)
        assertEquals("[Interactive card]", OrbitChatFork.excerpts(listOf(source), 1, true).single().text)
    }

    @Test fun aMovedCardReturnsToTheRowThatActuallyDisplaysIt() {
        val card = JSONObject().put("kind", OrbitChatCards.APPROVAL).put("title", "Approve")
            .put(OrbitChatCards.PIECE_KEY, "card:10:0")
        val shown = listOf(ChatMessage("ai", "", timestamp = 10),
            ChatMessage("ai", "Done\n```orbit-card\n$card\n```", timestamp = 20))
        assertEquals(20L, OrbitChatFork.displayTimestamp(shown, 10))
        assertEquals(20L, OrbitChatFork.displayTimestamp(shown, 20))
        assertEquals(30L, OrbitChatFork.displayTimestamp(shown, 30))
    }
}
