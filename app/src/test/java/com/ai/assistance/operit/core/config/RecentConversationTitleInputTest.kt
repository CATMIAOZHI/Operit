package com.ai.assistance.operit.core.config

import org.junit.Assert.*
import org.junit.Test

class RecentConversationTitleInputTest {
    @Test fun `keeps the latest twelve dialogue messages in chronological order`() {
        val messages = (1..15).map { "user" to "topic-$it" } +
            listOf("tool" to "irrelevant", "system" to "instructions")
        val input = RecentConversationTitleInput.build(messages)
        assertFalse(input.contains("topic-3\n"))
        assertTrue(input.startsWith("User: topic-4"))
        assertTrue(input.endsWith("User: topic-15"))
        assertFalse(input.contains("irrelevant"))
    }

    @Test fun `ignores hidden thinking and bounds each message without losing latest intent`() {
        val input = RecentConversationTitleInput.build(listOf(
            "ai" to "<think>private reasoning</think>visible reply",
            "user" to "x".repeat(5000) + "latest request",
        ))
        assertFalse(input.contains("private reasoning"))
        assertTrue(input.contains("Assistant: visible reply"))
        assertTrue(input.endsWith("latest request"))
        assertTrue(input.length < 600)
    }

    @Test fun `empty conversations remain empty`() {
        assertEquals("", RecentConversationTitleInput.build(listOf("user" to "  ", "tool" to "data")))
    }

    @Test fun `recent mode uses latest topic while automatic first title keeps first message prompt`() {
        assertTrue(FunctionalPrompts.conversationTitleSystemPrompt(false, true).contains("最新"))
        assertTrue(FunctionalPrompts.conversationTitleSystemPrompt(false).contains("第一条"))
        assertTrue(FunctionalPrompts.conversationTitleSystemPrompt(true, true).contains("recent conversation"))
    }
}
