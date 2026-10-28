package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.data.model.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitMessageBreaksTest {
    private fun reply(content: String, timestamp: Long = 100L) = ChatMessage(
        sender = "ai", content = content, timestamp = timestamp,
        inputTokens = 50, outputTokens = 20, sentAt = 90L, completedAt = 99L,
    )

    @Test fun splitPartsGoBackToTheModelWithTheirBreaks() = runBlocking {
        val rows = OrbitMessageBreaks.split(reply("一<message_break/>二\n<message_break/>\n三")) { emptyMap() }
        assertEquals(listOf("一", "二", "三"), rows.map { it.content })

        val joined = OrbitMessageBreaks.rejoin(listOf(ChatMessage("user", "hi", timestamp = 50L)) + rows)
        assertEquals(2, joined.size)
        assertEquals("一\n<message_break/>\n二\n<message_break/>\n三", joined[1].content)
        assertEquals(100L, joined[1].timestamp)
    }

    @Test fun separateRepliesStaySeparate() {
        val first = reply("早上好")
        val second = reply("还在吗", timestamp = 200L).copy(sentAt = 190L, completedAt = 199L)
        assertEquals(listOf(first, second), OrbitMessageBreaks.rejoin(listOf(first, second)))
    }
}
