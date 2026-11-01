package com.ai.assistance.operit.api.chat.compaction

import com.ai.assistance.operit.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactionTest {
    private fun message(sender: String, at: Long, text: String = "x") =
        ChatMessage(sender = sender, content = text, timestamp = at)

    @Test
    fun keepsTheNewestExchangesAfterTheSummary() {
        val long = "a".repeat(30_000) // about 9k tokens
        val messages = listOf(
            message("user", 10, long), message("ai", 20, long),
            message("user", 30), message("ai", 40),
            message("user", 50), message("ai", 60),
        )
        // The two short exchanges fit; the long one before them is summarized.
        assertEquals(2, ContextCompaction.summaryPosition(messages, keepTokens = 1_000))
    }

    @Test
    fun fallsBackToAfterTheLastReplyWhenNothingFits() {
        val long = "a".repeat(30_000)
        val messages = listOf(message("user", 10), message("ai", 20), message("user", 30, long))
        assertEquals(2, ContextCompaction.summaryPosition(messages, keepTokens = 1_000))
    }

    @Test
    fun needsRoomForTheSummaryTimestamp() {
        val messages = listOf(
            message("user", 10), message("ai", 20),
            message("user", 21), message("ai", 30),
        )
        // 21 directly follows 20, so the summary cannot go before it.
        assertEquals(4, ContextCompaction.summaryPosition(messages, keepTokens = 1_000))
    }

    @Test
    fun leavesSomethingBeforeTheKeptPartAfterAnEarlierSummary() {
        val messages = listOf(message("summary", 5), message("user", 10), message("ai", 20))
        assertEquals(3, ContextCompaction.summaryPosition(messages, keepTokens = 1_000))
    }

    @Test
    fun shrinkingClipsThenDropsTheOldest() {
        val transcript = List(10) { "user" to "b".repeat(20_000) }
        val shrunk = ContextCompaction.shrinkTranscript(transcript, targetTokens = 1_000)
        assertTrue(shrunk.isNotEmpty())
        assertEquals(transcript.last().first, shrunk.last().first)
        assertTrue(shrunk.sumOf { ContextCompaction.estimateTokens(it.second) } <= 1_000 || shrunk.size == 1)
    }

    @Test
    fun aLoneMessageKeepsItsNewestPart() {
        val text = "start" + "c".repeat(50_000) + "end"
        val shrunk = ContextCompaction.shrinkTranscript(listOf("user" to text), targetTokens = 1_000).single().second
        assertTrue(shrunk.endsWith("end"))
        assertTrue(shrunk.length < text.length)
    }

    @Test
    fun fingerprintSeesEdits() {
        val messages = listOf(message("user", 10, "hi"), message("ai", 20, "hello"))
        val edited = listOf(message("user", 10, "hi"), message("ai", 20, "hellO"))
        assertNotEquals(ContextCompaction.fingerprint(messages), ContextCompaction.fingerprint(edited))
    }

    @Test
    fun inStreamOverflowIsToldApartFromRateLimits() {
        assertTrue(ContextCompaction.isOverflowError("context_length_exceeded", "too big"))
        assertTrue(ContextCompaction.isOverflowError(null, "This model's maximum context length is 128000 tokens"))
        assertFalse(ContextCompaction.isOverflowError("rate_limit_exceeded", "Request too large: tokens per min limit"))
        assertTrue(ContextCompaction.isContextOverflow(java.io.IOException("x", ContextOverflowException("too long"))))
    }
}
