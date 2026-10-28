package com.ai.assistance.operit.ui.common.dynamicui

import org.junit.Assert.*
import org.junit.Test

class CardDeliveryTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val ref = "```a2ui-ref\n$id\n```"
    private val result = "```a2ui\nDOCUMENT\n```"
    private fun decoder() = CardReferenceExpander({ if (it == id) "DOCUMENT" else null })
    private fun decode(text: String, size: Int = 1): String {
        val decoder = decoder()
        return text.chunked(size).joinToString("") { decoder.push(it) } + decoder.finish()
    }
    @Test fun everyChunkBoundaryProducesSameSelfContainedMessage() {
        val input = "Intro\n$ref\nAfter"
        for (size in 1..input.length) assertEquals("size=$size", "Intro\n$result\nAfter", decode(input, size))
        assertEquals(result, decode(ref))
        assertEquals("``", decode("``"))
        assertEquals("```a2ui-ref", decode("```a2ui-ref"))
        assertEquals("```a2ui-ref\n$id", decode("```a2ui-ref\n$id"))
        assertEquals("hello", decoder().push("hello"))
    }
    @Test fun codeThinkingAndToolArgumentsAreNotExpanded() {
        listOf("````example\n$ref\n````", "~~~\n$ref\n~~~",
            "<think>\n$ref\n</think>", "<think data-operit-provider-reasoning=\"html-v1\">\n$ref\n</think>",
            "<tool name=\"anything\">\n$ref\n</tool>",
            "<tool_ab12 name=\"anything\">\n$ref\n</tool_ab12>",
            "<tool_result_ab12>\n$ref\n</tool_result_ab12>",
            "<ns:tool_ab12>\n$ref\n</ns:tool_ab12>",
            "> $ref", "    $ref").forEach { assertEquals(it, decode(it)) }
        assertEquals("<think>x</think>\n$result", decode("<think>x</think>\n$ref"))
        assertEquals("<tool_ab12>x</tool_ab12>\n$result", decode("<tool_ab12>x</tool_ab12>\n$ref"))
    }
    @Test fun rollbackRestoresHalfReferenceAndOutputQuota() {
        val decoder = decoder()
        assertEquals("", decoder.push("```a2ui-ref\n"))
        decoder.savepoint("point")
        assertEquals(result + "\n", decoder.push("$id\n```\n"))
        decoder.rollback("point")
        assertEquals(result, decoder.push("$id\n```") + decoder.finish())
    }
    @Test fun referencesCannotCrossRunsAndAreRemovedAtEnd() {
        CardDelivery.begin("a")
        CardDelivery.begin("b")
        try {
            val ref = CardDelivery.register("a", "payload")
            assertEquals("payload", CardDelivery.resolve("a", ref))
            assertNull(CardDelivery.resolve("b", ref))
            CardDelivery.end("a")
            assertNull(CardDelivery.resolve("a", ref))
            val decoder = CardReferenceExpander({ CardDelivery.resolve("b", it) })
            // Unresolved references stay verbatim; the renderer shows a notice.
            assertEquals("```a2ui-ref\n$ref\n```\n", decoder.push("```a2ui-ref\n$ref\n```\n"))
        } finally { CardDelivery.end("a"); CardDelivery.end("b") }
    }
    @Test fun modelsSeeShortNamesThatMapToTheirOwnRun() {
        CardDelivery.begin("short")
        try {
            assertEquals("card-1", CardDelivery.register("short", "one"))
            assertEquals("card-2", CardDelivery.register("short", "two"))
            assertEquals("two", CardDelivery.resolve("short", " Card-2 "))
            val decoder = CardReferenceExpander({ CardDelivery.resolve("short", it) })
            assertEquals("```a2ui card-1\none\n```\n", decoder.push("```a2ui-ref\ncard-1\n```\n"))
            assertEquals("```a2ui-ref\ncard-3\n```\n", decoder.push("```a2ui-ref\ncard-3\n```\n"))
            assertNull(CardDelivery.canonical("short", "card-0"))
        } finally { CardDelivery.end("short") }
    }
    @Test fun modelHistoryGivesBackTheReferenceTheModelWrote() {
        CardDelivery.begin("history")
        try {
            CardDelivery.register("history", "one")
            CardDelivery.register("history", "two")
            val written = "给你一张：\n```a2ui-ref\ncard-2\n```\n然后\n```a2ui-ref\ncard-1\n```\n"
            val decoder = CardReferenceExpander({ CardDelivery.resolve("history", it) })
            val stored = written.chunked(3).joinToString("") { decoder.push(it) } + decoder.finish()
            assertEquals("给你一张：\n```a2ui card-2\ntwo\n```\n然后\n```a2ui card-1\none\n```\n", stored)
            // The model sees exactly what it wrote, never an app-written line in its own words.
            assertEquals(written, CardTranscript.forModel(stored))
        } finally { CardDelivery.end("history") }
        // Cards stored before the name was kept are numbered in order, like a turn names them.
        assertEquals("a\n```a2ui-ref\ncard-1\n```\nb\n```a2ui-ref\ncard-2\n```",
            CardTranscript.forModel("a\n```a2ui\nX\n```\nb\n```a2ui\nY\n```"))
        assertSame("plain", CardTranscript.forModel("plain"))
    }
    @Test fun reviewKeepsVisibleCardTextOnly() {
        val document = listOf(
            """{"version":"v0.9","createSurface":{"surfaceId":"card","catalogId":"x"}}""",
            """{"version":"v0.9","updateComponents":{"surfaceId":"card","components":[{"id":"m1","component":"Display","text":"20","label":"Issues"},{"id":"t","component":"Table","columns":["#","Title"],"rows":[["#1","Crash"],["#2","Hang"]],"columnWidths":[64,200]},{"id":"root","component":"Column","children":["m1","t"]}]}}""",
        ).joinToString("\n")
        val message = "Before\n```a2ui card-1\n$document\n```\nAfter\n```a2ui-ref\ncard-9\n```"
        assertEquals(
            "Before\n[Dynamic UI card shown to the user here. Visible content: 20 Issues; #, Title #1, Crash | #2, Hang]\nAfter\n```a2ui-ref\ncard-9\n```",
            CardTranscript.forReview(message),
        )
        assertSame("plain", CardTranscript.forReview("plain"))
    }
    @Test fun htmlCardsKeepTheirTextAndLearningKeepsTheWholeCard() {
        val html = """{"version":"v0.9","updateComponents":{"surfaceId":"c","components":[{"id":"root","component":"Html","html":"<style>.a{color:red}</style><h1>Glass</h1><script>alert(1)</script><p>Live <b>preview</b></p>","css":".b{}"}]}}"""
        assertEquals("[Dynamic UI card shown to the user here. Visible content: Glass Live preview]",
            CardTranscript.forReview("```a2ui\n$html\n```"))
        val long = """{"version":"v0.9","updateComponents":{"surfaceId":"c","components":[{"id":"root","component":"Text","text":"${"x".repeat(3000)}"}]}}"""
        assertTrue(CardTranscript.forReview("```a2ui\n$long\n```").endsWith("x…]"))
        assertTrue(CardTranscript.forReview("```a2ui\n$long\n```", Int.MAX_VALUE).endsWith("x".repeat(3000) + "]"))
    }
    @Test fun olderChatsGoBackUnchanged() {
        // Text an older build let the model copy stays as it was; only shown cards change form.
        val old = "看这里：\n[Dynamic UI card shown to the user here. Visible content: 提醒]\n之后\n"
        assertSame(old, CardTranscript.forModel(old))
        // The chat summary is read by the user, so a card there reads in their words.
        assertEquals("a\n[卡片：hi]\nb", CardTranscript.forReview(
            "a\n```a2ui\n{\"updateDataModel\":{\"value\":\"hi\"}}\n```\nb", label = { "[卡片：$it]" }))
    }

    @Test fun ordinaryBracketLinesAreUntouched() {
        val decoder = decoder()
        val text = "[link](https://a.b)\n[Dynamic UI card shown to the user here]\n"
        assertEquals(text, text.chunked(2).joinToString("") { decoder.push(it) } + decoder.finish())
    }
}
