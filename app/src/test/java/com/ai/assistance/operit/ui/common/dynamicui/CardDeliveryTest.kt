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
            assertEquals("```a2ui\none\n```\n", decoder.push("```a2ui-ref\ncard-1\n```\n"))
            assertEquals("```a2ui-ref\ncard-3\n```\n", decoder.push("```a2ui-ref\ncard-3\n```\n"))
            assertNull(CardDelivery.canonical("short", "card-0"))
        } finally { CardDelivery.end("short") }
    }
    @Test fun modelHistoryKeepsVisibleCardTextOnly() {
        val document = listOf(
            """{"version":"v0.9","createSurface":{"surfaceId":"card","catalogId":"x"}}""",
            """{"version":"v0.9","updateComponents":{"surfaceId":"card","components":[{"id":"m1","component":"Display","text":"20","label":"Issues"},{"id":"t","component":"Table","columns":["#","Title"],"rows":[["#1","Crash"],["#2","Hang"]],"columnWidths":[64,200]},{"id":"root","component":"Column","children":["m1","t"]}]}}""",
        ).joinToString("\n")
        val message = "Before\n```a2ui\n$document\n```\nAfter\n```a2ui-ref\ncard-9\n```"
        assertEquals(
            "Before\n[Dynamic UI card shown to the user here. Visible content: 20 Issues; #, Title #1, Crash | #2, Hang]\nAfter\n```a2ui-ref\ncard-9\n```",
            CardTranscript.forModel(message),
        )
        assertSame("plain", CardTranscript.forModel("plain"))
    }
    @Test fun htmlCardsKeepTheirTextAndLearningKeepsTheWholeCard() {
        val html = """{"version":"v0.9","updateComponents":{"surfaceId":"c","components":[{"id":"root","component":"Html","html":"<style>.a{color:red}</style><h1>Glass</h1><script>alert(1)</script><p>Live <b>preview</b></p>","css":".b{}"}]}}"""
        assertEquals("[Dynamic UI card shown to the user here. Visible content: Glass Live preview]",
            CardTranscript.forModel("```a2ui\n$html\n```"))
        val long = """{"version":"v0.9","updateComponents":{"surfaceId":"c","components":[{"id":"root","component":"Text","text":"${"x".repeat(3000)}"}]}}"""
        assertTrue(CardTranscript.forModel("```a2ui\n$long\n```").endsWith("x…]"))
        assertTrue(CardTranscript.forModel("```a2ui\n$long\n```", Int.MAX_VALUE).endsWith("x".repeat(3000) + "]"))
    }
    @Test fun copiedHistorySummaryShowsTheCardPreparedThisTurn() {
        CardDelivery.begin("copy")
        try {
            assertEquals("card-1", CardDelivery.register("copy", "first"))
            // A revision replaces its base, so the base no longer waits for a place.
            assertEquals("card-2", CardDelivery.register("copy", "revised", revises = "card-1"))
            assertEquals("card-3", CardDelivery.register("copy", "other"))
            val copied = "[Dynamic UI card shown to the user here. Visible content: 临时提醒；提醒什么（输入框）]"
            val input = "给你一张：\n\n$copied\n\n拖动滑块\n$copied"
            for (size in listOf(1, 3, input.length)) {
                CardDelivery.end("copy"); CardDelivery.begin("copy")
                CardDelivery.register("copy", "first")
                CardDelivery.register("copy", "revised", revises = "card-1")
                CardDelivery.register("copy", "other")
                val decoder = CardReferenceExpander({ CardDelivery.resolve("copy", it) }, { CardDelivery.nextUnplaced("copy") })
                val output = input.chunked(size).joinToString("") { decoder.push(it) } + decoder.finish()
                assertEquals("size=$size", "给你一张：\n\n```a2ui\nrevised\n```\n\n拖动滑块\n```a2ui\nother\n```", output)
            }
            // Every card already has its place: the user sees the "card unavailable" notice.
            val decoder = CardReferenceExpander({ CardDelivery.resolve("copy", it) }, { CardDelivery.nextUnplaced("copy") })
            assertEquals("```a2ui-ref\nunavailable\n```\n", decoder.push("$copied\n"))
            // The short forms a model may copy from the tool's description are the summary too.
            for (short in listOf("here…]", "here...]")) {
                assertEquals("```a2ui-ref\nunavailable\n```\n", decoder.push("[Dynamic UI card shown to the user $short\n"))
            }
            // Words that only start like the summary are prose.
            val prose = "[Dynamic UI card shown to the user here is what history calls it]\n"
            assertEquals(prose, decoder.push(prose))
        } finally { CardDelivery.end("copy") }
    }
    @Test fun rollingBackGivesThePlacedCardBack() {
        CardDelivery.begin("back")
        try {
            CardDelivery.register("back", "doc")
            val decoder = CardReferenceExpander({ CardDelivery.resolve("back", it) },
                { CardDelivery.nextUnplaced("back") }, { CardDelivery.unplace("back", it) })
            decoder.savepoint("p")
            assertEquals("```a2ui\ndoc\n```\n", decoder.push("```a2ui-ref\ncard-1\n```\n"))
            assertNull(CardDelivery.nextUnplaced("back"))
            decoder.rollback("p")
            assertEquals("card-1", CardDelivery.nextUnplaced("back"))
            assertEquals("```a2ui\ndoc\n```", decoder.push("[Dynamic UI card shown to the user here]") + decoder.finish())
        } finally { CardDelivery.end("back") }
    }
    @Test fun ordinaryBracketLinesAndPlacedCardsAreUntouched() {
        CardDelivery.begin("plain")
        try {
            CardDelivery.register("plain", "doc")
            val decoder = CardReferenceExpander({ CardDelivery.resolve("plain", it) }, { CardDelivery.nextUnplaced("plain") })
            val text = "[link](https://a.b)\n[Dynamic UI cards are nice]\n<think>\n[Dynamic UI card shown to the user here]\n</think>\n"
            assertEquals(text, text.chunked(2).joinToString("") { decoder.push(it) } + decoder.finish())
            assertEquals("```a2ui\ndoc\n```\n", decoder.push("```a2ui-ref\ncard-1\n```\n"))
            assertNull(CardDelivery.nextUnplaced("plain"))
        } finally { CardDelivery.end("plain") }
    }
}
