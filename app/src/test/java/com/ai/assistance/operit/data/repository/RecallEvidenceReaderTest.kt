package com.ai.assistance.operit.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecallEvidenceReaderTest {
    private suspend fun page(text: String, thinking: Boolean=false, start: Int=0,
        query: List<String> = emptyList(), assistant: Boolean=true): RecallEvidenceReader.Page {
        val bytes=text.toByteArray(Charsets.UTF_8)
        return RecallEvidenceReader.read({ offset ->
            val from=offset.toInt()
            bytes.copyOfRange(from,minOf(from+32768,bytes.size))
        },assistant,thinking,start,8000,query)!!
    }
    @Test fun longThoughtDoesNotHideTheFinalResultAndProtocolNeverLeaks() = runBlocking {
        val text="<think>${"推测🙂".repeat(40000)}</think>" +
            "<meta provider=\"openai:responses_reasoning\">${"secret".repeat(30000)}</meta>验证通过"
        val result=page(text)
        assertEquals("验证通过",result.text)
        assertEquals(4,result.total)
        assertNull(result.next)
        assertFalse(page(text,true).text.contains("secret"))
    }
    @Test fun canReadPast128kWithUnicodeAndEmbeddedNull() = runBlocking {
        val text="🙂".repeat(140000)+"\u0000最终方案"
        val result=page(text,true,139998)
        assertEquals("🙂🙂\u0000最终方案",result.text)
        assertEquals(140005,result.total)
        assertNull(result.next)
    }
    @Test fun searchReturnsTheHitInsteadOfTheBeginningAndOffsetsContinue() = runBlocking {
        val text="a".repeat(180000)+"采用服务端token"+"z".repeat(10000)
        val found=page(text,true,query=listOf("服务端token"))
        assertTrue(found.matched)
        assertTrue(found.start>170000)
        assertTrue(found.text.contains("服务端token"))
        assertEquals(8000,found.text.codePointCount(0,found.text.length))
        assertEquals(text.substring(found.start,found.next!!),found.text)
        assertEquals(text.substring(found.next!!),page(text,true,found.next!!).text)
    }
    @Test fun hiddenOnlyMatchesAreNotReplacedByUnrelatedText() = runBlocking {
        val result=page("<think>needle</think>finished",query=listOf("needle"))
        assertFalse(result.matched)
        assertEquals("",result.text)
    }
    @Test fun ftsMatchesWordsPhrasesAndAccentsRatherThanOnlyAsciiSubstrings() = runBlocking {
        val bytes=("catalog "+"z".repeat(140000)+" café and alpha_beta").toByteArray()
        val result=RecallEvidenceReader.read({ offset ->
            bytes.copyOfRange(offset.toInt(),minOf(offset.toInt()+32768,bytes.size))
        },true,true,queries=listOf("cat","cafe","missing"),fts=true)!!
        assertTrue(result.matched)
        assertTrue(result.start>130000)
        assertTrue(result.text.contains("café"))
        val phrase="alpha_beta".toByteArray()
        val end=RecallEvidenceReader.read({ offset ->
            phrase.copyOfRange(offset.toInt(),phrase.size)
        },true,true,queries=listOf("alpha.beta"),fts=true)!!
        assertTrue(end.matched)
        assertEquals("alpha_beta",end.text)
    }
    @Test fun stateSurvivesChunkBoundariesAndUserQuotesStayLiteral() = runBlocking {
        val text="x".repeat(32765)+"<thinking key=\"a>b\">private</thinking>visible"
        assertEquals("x".repeat(32765)+"visible", page(text,start=32000).let {
            "x".repeat(32000)+it.text
        })
        assertEquals("<think>quote</think>",page("<think>quote</think>",assistant=false).text)
        assertEquals("before",page("before<think>never closed").text)
        assertEquals("a < b "+"z".repeat(7994),page("a < b "+"z".repeat(20000)).text)
    }
}
