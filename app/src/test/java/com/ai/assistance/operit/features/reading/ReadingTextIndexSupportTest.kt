package com.ai.assistance.operit.features.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTextIndexSupportTest {

    @Test
    fun `chunks cover content with bounded overlap`() {
        val content = buildString {
            repeat(6_000) { append(('一'.code + it % 100).toChar()) }
        }
        val chunks = ReadingTextIndexSupport.chunk(content)

        assertTrue(chunks.size > 1)
        assertEquals(0, chunks.first().start)
        assertEquals(content.length, chunks.last().end)
        chunks.zipWithNext().forEach { (left, right) ->
            assertTrue(right.start < left.end)
            assertTrue(right.start > left.start)
        }
    }

    @Test
    fun `chunkFrom keeps absolute positions while rebuilding only the tail`() {
        val content = "前".repeat(4_000) + "后".repeat(2_000)
        val chunks = ReadingTextIndexSupport.chunkFrom(content, 3_500)

        assertEquals(3_500, chunks.first().start)
        assertEquals(content.length, chunks.last().end)
        assertEquals(
            content.substring(chunks.first().start, chunks.first().end),
            chunks.first().text,
        )
    }

    @Test
    fun `Chinese query and index both include bigrams`() {
        val queryTerms = ReadingTextIndexSupport.extractQueryTerms("那个戴眼镜的教授")
        val indexedTerms = ReadingTextIndexSupport.buildSearchTerms("戴眼镜的教授走进教室")

        assertTrue("眼镜" in queryTerms)
        assertTrue(indexedTerms.split(' ').contains("眼镜"))
        assertTrue(indexedTerms.split(' ').contains("教授"))
    }

    @Test
    fun `fts expression escapes quotes and joins terms`() {
        assertEquals(
            "\"眼镜\" OR \"professor\" OR \"a\"\"b\"",
            ReadingTextIndexSupport.buildFtsExpression(
                listOf("眼镜", "professor", "a\"b")
            ),
        )
    }
}
