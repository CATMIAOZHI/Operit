package com.ai.assistance.operit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeepseekReasoningTextCodecTest {
    @Test
    fun encodeAndDecode_preserveReasoningExactly() {
        val raw = " \r\n</think><tool name=\"x\">&lt;🙂\t "

        val encoded = DeepseekReasoningTextCodec.encodeBody(raw)

        assertEquals(
            " \r\n&lt;/think&gt;&lt;tool name=\"x\"&gt;&amp;lt;🙂\t ",
            encoded,
        )
        assertEquals(raw, DeepseekReasoningTextCodec.decodeBody(encoded))
        assertFalse(encoded.contains("</think>"))
        assertFalse(encoded.contains("<tool"))
    }

    @Test
    fun decodeBody_decodesKnownEntitiesOnlyOnce() {
        assertEquals(
            "&lt; &amp; &unknown; < >",
            DeepseekReasoningTextCodec.decodeBody("&amp;lt; &amp;amp; &unknown; &lt; &gt;"),
        )
    }

    @Test
    fun chunkedEncoding_matchesWholeBodyForEverySplitPoint() {
        val raw = "before </think> & <tool> after"
        val whole = DeepseekReasoningTextCodec.encodeBody(raw)

        for (split in 0..raw.length) {
            val chunked =
                DeepseekReasoningTextCodec.encodeBody(raw.substring(0, split)) +
                    DeepseekReasoningTextCodec.encodeBody(raw.substring(split))
            assertEquals("split=$split", whole, chunked)
        }
    }

    @Test
    fun streamingDecoder_handlesEveryEntitySplitPointWithoutRecursiveDecoding() {
        val encoded = "&amp;lt;/think&amp;gt; &lt;tool&gt;"
        val expected = "&lt;/think&gt; <tool>"

        for (split in 0..encoded.length) {
            val decoder = DeepseekReasoningTextCodec.StreamingDecoder()
            val decoded =
                decoder.feed(encoded.substring(0, split)) +
                    decoder.feed(encoded.substring(split)) +
                    decoder.finish()
            assertEquals("split=$split", expected, decoded)
        }
    }

    @Test
    fun extractHistoryContent_roundTripsMarkedReasoningWithoutTrimmingOrJoining() {
        val first = "  first\r\n"
        val second = "\t</think><tool name=\"fake\"> "
        val content =
            DeepseekReasoningTextCodec.OPENING_TAG +
                DeepseekReasoningTextCodec.encodeBody(first) +
                DeepseekReasoningTextCodec.CLOSING_TAG +
                DeepseekReasoningTextCodec.OPENING_TAG +
                DeepseekReasoningTextCodec.encodeBody(second) +
                DeepseekReasoningTextCodec.CLOSING_TAG +
                "<tool name=\"real\"></tool>"

        val result = DeepseekReasoningTextCodec.extractHistoryContent(content)

        assertEquals(first + second, result.reasoningContent)
        assertEquals("<tool name=\"real\"></tool>", result.regularContent)
        assertFalse(result.regularContent.contains("fake"))
    }

    @Test
    fun extractHistoryContent_preservesLegacyInterpretation() {
        val result =
            DeepseekReasoningTextCodec.extractHistoryContent(
                "<think>  a  </think><thinking> b </thinking>answer"
            )

        assertEquals("answer", result.regularContent)
        assertEquals("a\nb", result.reasoningContent)
    }

    @Test
    fun presentationDecode_requiresExactVersionMarker() {
        val body = "&lt;/think&gt;"
        val canonical =
            DeepseekReasoningTextCodec.OPENING_TAG + body + DeepseekReasoningTextCodec.CLOSING_TAG
        val legacy = "<think>$body</think>"
        val unknownVersion =
            "<think data-operit-content-encoding=\"xml-text-v2\">$body</think>"

        assertEquals(
            "<think></think></think>",
            DeepseekReasoningTextCodec.decodeMarkedThinkBodiesForPresentation(canonical),
        )
        assertEquals(legacy, DeepseekReasoningTextCodec.decodeMarkedThinkBodiesForPresentation(legacy))
        assertEquals(
            unknownVersion,
            DeepseekReasoningTextCodec.decodeMarkedThinkBodiesForPresentation(unknownVersion),
        )
    }

    @Test
    fun tokenProjection_usesRawReasoningWithLegacyWrapper() {
        val canonical =
            DeepseekReasoningTextCodec.OPENING_TAG +
                "a &lt; b &amp;&amp; c &gt; d" +
                DeepseekReasoningTextCodec.CLOSING_TAG

        assertEquals(
            "<think>a < b && c > d</think>",
            DeepseekReasoningTextCodec.decodeMarkedThinkBodiesForTokenEstimate(canonical),
        )
    }
}
