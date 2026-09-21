package com.ai.assistance.operit.data.repository

import com.ai.assistance.operit.data.dao.ChatContentDao
import com.ai.assistance.operit.data.dao.ChatRecallPart
import com.ai.assistance.operit.data.dao.ChatRecallHit
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
                whenever(dao.readRecallMessagePart(eq(1L), any(), any())).thenAnswer {
                    ChatRecallPart(1,"chat","ai",0,raw.take(it.getArgument(2)),raw.length + 10,nul)
                }
                val message = ChatRecallRepository(dao).execute(
                    mapOf("mode" to "message", "message_id" to "1"), true).getJSONObject("message")
                assertEquals("visible", message.getString("content"))
                assertTrue(message.getBoolean("source_truncated"))
            }
        }
    }
    @Test fun learningMessageOffsetsReferToVisibleTextAfterLongThinking() = runBlocking {
        val dao = mock<ChatContentDao>()
        val raw = "<think>" + "secret".repeat(2000) + "</think>" + "answer".repeat(2000)
        whenever(dao.readRecallMessagePart(eq(1L), any(), any())).thenAnswer {
            val start = it.getArgument<Int>(1)
            val limit = it.getArgument<Int>(2)
            ChatRecallPart(1,"chat","ai",0,raw.drop(start).take(limit),raw.length)
        }
        val repo = ChatRecallRepository(dao)
        val first = repo.execute(mapOf("mode" to "message", "message_id" to "1"), true).getJSONObject("message")
        assertFalse(first.getString("content").contains("secret"))
        assertEquals(12000, first.getInt("total_chars"))
        assertEquals(8000, first.getInt("next_char_offset"))
        val second = repo.execute(mapOf("mode" to "message", "message_id" to "1", "char_offset" to "8000"), true)
            .getJSONObject("message")
        assertEquals("answer".repeat(2000).drop(8000),second.getString("content"))
        assertTrue(second.isNull("next_char_offset"))
        assertTrue(repo.execute(mapOf("mode" to "message", "message_id" to "1")).toString().contains("secret"))
    }

    @Test fun excerptsStartingInsideThinkingAreRebuiltFromWholePrefix() = runBlocking {
        val dao = mock<ChatContentDao>()
        whenever(dao.readRecallContext(1, 11)).thenReturn(
            listOf(ChatRecallHit(1,"chat","title","ai",0,"secret without opening tag")))
        whenever(dao.readRecallMessagePart(1, 0, 128_000)).thenReturn(
            ChatRecallPart(1,"chat","ai",0,"<think>secret</think>visible",26))
        val json = ChatRecallRepository(dao).execute(mapOf("message_id" to "1"), true)
        assertEquals("visible",json.getJSONArray("messages").getJSONObject(0).getString("excerpt"))
        assertFalse(json.toString().contains("secret"))
    }
}
