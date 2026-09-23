package com.ai.assistance.operit.data.repository

import com.ai.assistance.operit.data.dao.ChatContentDao
import com.ai.assistance.operit.data.dao.ChatRecallHit
import com.ai.assistance.operit.data.dao.ChatRecallPart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class ChatRecallRepositoryTest {
    @Test fun learningSearchAndReadUseTheSameCleanedCoordinatesBeyond128k() = runBlocking {
        val dao=mock<ChatContentDao>()
        val text="<think>private</think>"+"x".repeat(160000)+" actualInputTokens "+"z".repeat(10000)
        val bytes=text.toByteArray()
        whenever(dao.readRecallMessagePart(eq(1L),any(),any())).thenReturn(
            ChatRecallPart(1,"chat","ai",1,"",text.length))
        whenever(dao.readRecallMessageBytes(eq(1L),any())).thenAnswer {
            val from=it.getArgument<Long>(1).toInt()
            bytes.copyOfRange(from,minOf(from+32768,bytes.size))
        }
        whenever(dao.searchRecallIndex(any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(
            listOf(ChatRecallHit(1,"chat","Title","ai",1,"[actualInputTokens]")))
        val repo=ChatRecallRepository(dao)
        val found=repo.execute(mapOf("query" to "actualInputTokens"),true)
            .getJSONArray("messages").getJSONObject(0)
        assertTrue(found.getString("excerpt").contains("actualInputTokens"))
        assertTrue(found.getInt("char_offset")>150000)
        assertFalse(found.getBoolean("source_truncated"))
        val read=repo.execute(mapOf("mode" to "message","message_id" to "1",
            "char_offset" to found.getInt("char_offset").toString()),true).getJSONObject("message")
        assertTrue(read.getString("content").startsWith(found.getString("excerpt")))
        assertEquals(160000+" actualInputTokens ".length+10000,read.getInt("total_chars"))
    }
}
