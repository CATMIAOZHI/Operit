package com.ai.assistance.operit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMarkupRegexMetaTest {

    @Test fun extractSignature_ignoresEmptyBody() {
        assertNull(ChatMarkupRegex.extractGeminiThoughtSignature("<meta provider=\"gemini:thought_signature\">   </meta>"))
    }

    @Test fun removeSignature_preservesTrailingText() {
        assertEquals("prefixsuffix", ChatMarkupRegex.removeGeminiThoughtSignatureMeta("prefix<meta provider=\"gemini:thought_signature\">a</meta>suffix"))
    }

    @Test fun removeSignature_removesMultipleMatchingTags() {
        assertEquals("body", ChatMarkupRegex.removeGeminiThoughtSignatureMeta("<meta provider=\"gemini:thought_signature\">a</meta>body<meta provider=\"gemini:thought_signature\">b</meta>"))
    }

    @Test fun extractSignature_returnsLastAmongMixedMetaTags() {
        assertEquals(
            "target",
            ChatMarkupRegex.extractGeminiThoughtSignature(
                "<meta provider=\"other\">x</meta><meta provider=\"gemini:thought_signature\">target</meta>"
            )
        )
    }

    @Test fun removeOpenAiReasoning_preservesVoidMetaBeforeMatchingTag() {
        val content =
            "<meta charset=\"utf-8\">visible" +
                "<meta provider=\"openai:responses_reasoning\">payload</meta>answer"

        assertEquals(
            "<meta charset=\"utf-8\">visibleanswer",
            ChatMarkupRegex.removeOpenAiResponsesReasoningMeta(content)
        )
    }

    @Test fun removeOpenAiReasoning_ignoresProviderTextInMetaBody() {
        val content = "<meta>provider=\"openai:responses_reasoning\"</meta>"

        assertEquals(content, ChatMarkupRegex.removeOpenAiResponsesReasoningMeta(content))
    }

    // The blocks are found in one pass now; they are the ones the shared pattern finds.
    @Test fun removeMeta_findsTheBlocksThePatternFinds() {
        val g = "<meta provider=\"gemini:thought_signature\">s</meta>"
        val o = "<meta provider='openai:responses_reasoning'>r</meta>"
        listOf(
            "a${g}b", "<meta>x$g z", "<META PROVIDER=\"gemini:content\">s</META>t", "<metadata>$g",
            "<meta provider=\"x\"><meta>$g</meta>", "<meta $o tail", "$o<meta charset=\"utf-8\">$o",
            "<meta provider=\"gemini:thought_signature\" a=\"<\">s</meta>", "x</meta>$g<meta", "<meta/>$o</meta>",
            "$g$o$g  ", "<meta_x>$o", "<meta\n provider=\"openai:responses_reasoning\">a\n<b></meta>c",
        ).forEach { content ->
            assertEquals(content, reference(content, setOf("gemini:thought_signature", "gemini:content")),
                ChatMarkupRegex.removeGeminiThoughtSignatureMeta(content))
            assertEquals(content, reference(content, setOf("openai:responses_reasoning")),
                ChatMarkupRegex.removeOpenAiResponsesReasoningMeta(content))
        }
    }

    @Test(timeout = 2_000L)
    fun removeMeta_readsLongRunsOfUnendedOpeningsOnce() {
        val tail = "z".repeat(200_000)
        listOf("<meta ".repeat(20_000) + tail, "<meta>".repeat(20_000) + tail + "</meta>",
            "<meta a>b".repeat(20_000) + tail).forEach { content ->
            assertEquals(content, ChatMarkupRegex.removeGeminiThoughtSignatureMeta(content))
        }
    }

    // What the pattern-based removal gave: blocks whose provider is one of [providers] go.
    private fun reference(content: String, providers: Set<String>): String {
        var removed = false
        val provider = Regex("""\bprovider\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val result = ChatMarkupRegex.metaTag.replace(content) { match ->
            val name = provider.find(match.value.substringBefore('>'))?.groupValues?.getOrNull(1)
            if (providers.any { it.equals(name, ignoreCase = true) }) {
                removed = true
                ""
            } else {
                match.value
            }
        }
        return if (removed) result.trimEnd() else result
    }
}
