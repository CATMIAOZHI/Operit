package com.ai.assistance.operit.ui.features.chat.components.part

import com.ai.assistance.operit.util.stream.stream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomXmlRendererDisplayClosingTest {
    @Test
    fun renderingHelpersAcceptTheDisplayClosingGrammarUsedByStreamXml() {
        listOf(
            Triple("<thinking>draft</thinking >", "thinking", "draft"),
            Triple("<thinking>draft</think>", "thinking", "draft"),
            Triple("<THINK>draft</thinking\n>", "think", "draft"),
            Triple("<SEARCH>source</search >", "search", "source"),
            Triple("<search>source</SEARCH>", "search", "source"),
        ).forEach { (content, tagName, expectedBody) ->
            assertEquals(content, tagName, resolveXmlTagNameForRendering(content))
            assertTrue(content, isXmlFullyClosedForRendering(content))
            assertEquals(content, expectedBody, extractXmlContentForRendering(content, tagName))
        }

        val malformed = "<think>draft</think bogus>"
        assertFalse(isXmlFullyClosedForRendering(malformed))
        assertEquals("draft</think bogus>", extractXmlContentForRendering(malformed, "think"))
    }

    @Test
    fun streamingThinkBodyStopsAtWhitespaceFamilyCloserAcrossChunks() = runBlocking {
        val output = StringBuilder()
        val source = stream {
            emit("<THINK>visible </thi")
            emit("nking\n")
            emit(">ignored")
        }

        createThinkMarkdownCharStreamForRendering(source, "think").collect { output.append(it) }

        assertEquals("visible ", output.toString())
    }
}
