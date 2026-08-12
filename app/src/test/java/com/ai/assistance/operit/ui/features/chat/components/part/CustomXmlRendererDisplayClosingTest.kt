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
            Triple("<think title=\"a>b\">reason</think>", "think", "reason"),
        ).forEach { (content, tagName, expectedBody) ->
            assertEquals(content, tagName, resolveXmlTagNameForRendering(content))
            assertTrue(content, isXmlFullyClosedForRendering(content))
            assertEquals(content, expectedBody, extractXmlContentForRendering(content, tagName))
        }

        val malformed = "<think>draft</think bogus>"
        assertFalse(isXmlFullyClosedForRendering(malformed))
        assertEquals("draft</think bogus>", extractXmlContentForRendering(malformed, "think"))

        val quotedSelfCloseWithoutTerminator = "<think title=\"quoted/>"
        assertEquals("think", resolveXmlTagNameForRendering(quotedSelfCloseWithoutTerminator))
        assertFalse(isXmlFullyClosedForRendering(quotedSelfCloseWithoutTerminator))

        listOf("<think!foo>visible</think!foo>", "<think@foo>visible</think@foo>").forEach {
            assertEquals(it, null, resolveXmlTagNameForRendering(it))
            assertFalse(it, isXmlFullyClosedForRendering(it))
        }
    }

    @Test
    fun renderingHelpersKeepQualifiedXmlNamesOutOfDisplayDispatch() {
        listOf(
            Triple("<search-results>visible</search-results>", "search-results", "visible"),
            Triple("<think-step>visible</think-step>", "think-step", "visible"),
            Triple("<thinking.phase>visible</thinking.phase>", "thinking.phase", "visible"),
            Triple("<search:result>visible</search:result>", "search:result", "visible"),
        ).forEach { (content, tagName, expectedBody) ->
            assertEquals(content, tagName, resolveXmlTagNameForRendering(content))
            assertTrue(content, isXmlFullyClosedForRendering(content))
            assertEquals(content, expectedBody, extractXmlContentForRendering(content, tagName))
        }
    }

    @Test
    fun streamingThinkBodyStopsAtWhitespaceFamilyCloserAcrossChunks() = runBlocking {
        val output = StringBuilder()
        val source = stream {
            emit("<THINK title=\"a>")
            emit("b\">visible </thi")
            emit("nking\n")
            emit(">ignored")
        }

        createThinkMarkdownCharStreamForRendering(source, "think").collect { output.append(it) }

        assertEquals("visible ", output.toString())
    }
}
