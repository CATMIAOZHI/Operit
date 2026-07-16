package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.util.DeepseekReasoningTextCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEditorReasoningCodecTest {
    @Test
    fun visualEditor_roundTripsCanonicalReasoning() {
        val raw = "before </think> & <tool> after"
        val canonical =
            DeepseekReasoningTextCodec.OPENING_TAG +
                DeepseekReasoningTextCodec.encodeBody(raw) +
                DeepseekReasoningTextCodec.CLOSING_TAG

        val parts = parseMessageContentForEditor(canonical)

        assertEquals(1, parts.size)
        assertTrue(parts.single().isEncodedReasoning)
        assertEquals(raw, parts.single().content)
        assertEquals(canonical, recomposeMessageFromParts(parts))
    }

    @Test
    fun visualEditor_keepsLegacyThinkUnmarkedAndUndecoded() {
        val legacy = "<think>&lt;/think&gt;</think>"

        val parts = parseMessageContentForEditor(legacy)

        assertFalse(parts.single().isEncodedReasoning)
        assertEquals("&lt;/think&gt;", parts.single().content)
        assertEquals(legacy, recomposeMessageFromParts(parts))
    }

    @Test
    fun visualEditor_preservesWhitespaceBetweenStructuredBlocks() {
        val canonical =
            DeepseekReasoningTextCodec.OPENING_TAG +
                "reasoning" +
                DeepseekReasoningTextCodec.CLOSING_TAG +
                "\n\n  \t" +
                "<tool name=\"real\"></tool>"

        assertEquals(
            canonical,
            recomposeMessageFromParts(parseMessageContentForEditor(canonical)),
        )
    }
}
