package com.ai.assistance.operit.data.repository

import com.ai.assistance.operit.data.dao.ChatContentDao
import com.ai.assistance.operit.data.dao.ChatRecallHit
import com.ai.assistance.operit.data.dao.ChatRecallPart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class MemoryRecallThinkingTest {
    @Test fun boundedOrNulTerminatedMetadataNeverLeaksPayload() = runBlocking {
        for (provider in listOf("openai:responses_reasoning", "gemini:thought_signature", "gemini:content")) {
            for (nul in listOf(false, true)) {
                val dao = mock<ChatContentDao>()
                val raw = "visible<meta provider=\"$provider\">" + "secret".repeat(30_000)
                stubRecallBytes(dao, raw)
                whenever(dao.readRecallMessagePart(eq(1L), any(), any())).thenReturn(
                    ChatRecallPart(1, "chat", "ai", 0, "", raw.length + 10, nul))
                val result = ChatRecallRepository(dao).execute(
                    mapOf("mode" to "message", "message_id" to "1"), true)
                val message = result.getJSONObject("message")
                assertEquals("visible", message.getString("content"))
                assertEquals("visible".length, message.getInt("total_chars"))
                assertFalse(result.toString().contains("secret"))
            }
        }
    }

    @Test fun learningMessageOffsetsReferToVisibleTextAfterLongThinking() = runBlocking {
        val dao = mock<ChatContentDao>()
        val raw = "<think>" + "secret".repeat(2000) + "</think>" + "answer".repeat(2000)
        stubRecallBytes(dao, raw)
        whenever(dao.readRecallMessagePart(eq(1L), any(), any())).thenAnswer {
            val start = it.getArgument<Int>(1)
            val limit = it.getArgument<Int>(2)
            ChatRecallPart(1, "chat", "ai", 0, raw.drop(start).take(limit), raw.length)
        }
        val repo = ChatRecallRepository(dao)
        val first = repo.execute(mapOf("mode" to "message", "message_id" to "1"), true)
            .getJSONObject("message")
        assertFalse(first.getString("content").contains("secret"))
        assertEquals(12000, first.getInt("total_chars"))
        assertEquals(8000, first.getInt("next_char_offset"))
        val second = repo.execute(mapOf("mode" to "message", "message_id" to "1", "char_offset" to "8000"), true)
            .getJSONObject("message")
        assertEquals("answer".repeat(2000).drop(8000), second.getString("content"))
        assertTrue(second.isNull("next_char_offset"))
        assertTrue(repo.execute(mapOf("mode" to "message", "message_id" to "1")).toString().contains("secret"))
    }

    @Test fun excerptsStartingInsideThinkingAreRebuiltFromWholePrefix() = runBlocking {
        val dao = mock<ChatContentDao>()
        val raw = "<think>secret</think>visible"
        stubRecallBytes(dao, raw)
        whenever(dao.readRecallMessagePart(eq(1L), any(), any())).thenReturn(
            ChatRecallPart(1, "chat", "ai", 0, "", raw.length))
        whenever(dao.readRecallContext(1, 11)).thenReturn(
            listOf(ChatRecallHit(1, "chat", "title", "ai", 0, "secret without opening tag")))
        val json = ChatRecallRepository(dao).execute(mapOf("message_id" to "1"), true)
        assertEquals("visible", json.getJSONArray("messages").getJSONObject(0).getString("excerpt"))
        assertFalse(json.toString().contains("secret"))
    }

    /**
     * The cleaned reader scans whole messages in bounded chunk reads, so the DAO has to hand out
     * SQLite-sized byte pages instead of a single truncated text row.
     */
    private suspend fun stubRecallBytes(dao: ChatContentDao, text: String) {
        val bytes = text.toByteArray()
        whenever(dao.readRecallMessageBytes(eq(1L), any())).thenAnswer {
            val from = it.getArgument<Long>(1).toInt()
            if (from >= bytes.size) ByteArray(0)
            else bytes.copyOfRange(from, minOf(from + 32_768, bytes.size))
        }
    }
}
