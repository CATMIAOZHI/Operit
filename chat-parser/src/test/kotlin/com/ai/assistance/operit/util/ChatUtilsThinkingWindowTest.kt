package com.ai.assistance.operit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The windowed scan exists so a caller that shows a few thousand characters never copies a turn that
 * grew to megabytes. Its head, its tail, and its length therefore have to describe exactly the text
 * the unwindowed scan returns, for every shape the fail-closed state machine can be left in, and for
 * whitespace runs longer than the window itself.
 */
class ChatUtilsThinkingWindowTest {

    private companion object {
        /** Assembled, not spelled out: this file's own samples are the reason not to trust literals. */
        private const val REASONING_CLOSE = "<" + "/think>"
    }

    private val samples =
        listOf(
            "no tags at all",
            "  padded text  ",
            "\u00a0non breaking space padding\u00a0",
            "\u2028line separator padding\u2028",
            "\u0085next line padding\u0085",
            "\n\nonly newlines\n\n",
            "",
            "   ",
            "before<think />after",
            "before<think />after</thinking>orphan",
            "<think>closed</think>visible",
            "<thinking>closed</thinking\n>visible",
            "<think type=\"analysis\">draft</thinking >answer",
            "<think>unclosed tail is dropped",
            "<thinking>outer<think>inner</think>still hidden</thinking>answer",
            "<think>cross</thinking>answer</think>tail",
            "<think!foo>lookalike</think!foo>answer",
            "<thin",
            "<think",
            "<think>hidden</think>  trailing spaces  ",
            "   <think>hidden</think>   ",
            "<search>query</search>answer",
            "<think>思考内容</think>可见回答",
            ChatUtils.PROVIDER_REASONING_OPEN_TAG + "hidden</think>answer",
        )

    private fun assertWindowMatches(content: String, windowChars: Int) {
        val expected = ChatUtils.removeThinkingContent(content)
        val window = ChatUtils.removeThinkingContentWindow(content, windowChars)
        assertEquals("length of <$content>", expected.length, window.length)
        assertEquals(
            "head of <$content> at $windowChars",
            expected.take(windowChars),
            window.headWindow,
        )
        assertEquals(
            "tail of <$content> at $windowChars",
            expected.takeLast(windowChars),
            window.tailWindow,
        )
    }

    @Test
    fun windowMatchesTheUnwindowedScanForEveryShape() {
        for (sample in samples) {
            for (windowChars in listOf(1, 2, 5, 16, 4_000)) {
                assertWindowMatches(sample, windowChars)
            }
        }
    }

    @Test
    fun aWindowWiderThanTheInputStillSamplesTheWholeText() {
        for (sample in samples) {
            // The caller asked for more than the input could ever hold, so both ends have to be the
            // whole visible text rather than what the wider window would have asked the rings to
            // allocate.
            assertWindowMatches(sample, 10_000)
            assertEquals(
                "budget of <$sample>",
                10_000,
                ChatUtils.removeThinkingContentWindow(sample, 10_000).windowChars,
            )
        }
    }

    @Test
    fun windowKeepsWhitespaceRunsLongerThanTheWindowOutOfTheTrimmedEnds() {
        assertWindowMatches("abc" + "\n".repeat(20_000), 4_000)
        assertWindowMatches("\n".repeat(20_000) + "abc", 4_000)
        // The interior run survives trimming, so both ends have to describe it exactly even though
        // the run is longer than the window the sink can hold.
        assertWindowMatches("a" + " \t\n".repeat(20_000) + "b", 4_000)
        assertWindowMatches("\n".repeat(20_000), 4_000)
    }

    @Test
    fun windowStaysBoundedOnAMultiMegabyteTurn() {
        // One closed reasoning envelope per round, which is the shape a tool-heavy turn really has:
        // most of the turn is reasoning, and the answers between the envelopes stay visible.
        val turn = StringBuilder()
        repeat(30_000) { round ->
            turn.append(ChatUtils.PROVIDER_REASONING_OPEN_TAG)
                .append("reasoning ").append(round)
                .append(REASONING_CLOSE)
                .append("answer ").append(round).append('\n')
        }
        val content = turn.toString()
        assertTrue("turn is long enough to matter", content.length > 1_000_000)
        val visible = ChatUtils.removeThinkingContent(content)
        assertTrue("the reasoning really was cut", visible.length < content.length / 2)
        assertTrue("the visible answers are large too", visible.length > 100_000)
        assertWindowMatches(content, 4_000)
    }

    @Test
    fun boundaryVisibilityIsUnchangedByTheSinkThatKeepsNothing() {
        val content = "keep<tool name=\"a\" />visible<think data-operit-provider-reasoning=\"html-v1\">hidden<tool name=\"b\" /></think>tail"
        val toolStarts = listOf(content.indexOf("<tool"), content.lastIndexOf("<tool"))
        assertEquals(
            listOf(true, false),
            ChatUtils.displayOnlyBoundaryVisibility(
                    content = content,
                    orderedBoundaries = toolStarts.toIntArray(),
                    protectedRanges = IntArray(0),
                )
                .toList(),
        )
    }
}
