package com.ai.assistance.operit.ui.permissions

import com.ai.assistance.operit.util.ChatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reviewer reads a bounded slice of every entry, so the sanitizing and the cutting happen in one
 * windowed pass. What it reads must not change: the slice has to be the one the unwindowed
 * sanitizing followed by [truncateTranscriptMessage] produced, character for character, because the
 * reviewer decides what to allow from this text.
 */
class PermissionReviewTranscriptContentTest {

    /**
     * Assembled rather than spelled out. A display-only tag written literally into this file has been
     * swallowed by the editor path before, which silently turned the samples into ordinary text, so
     * the tags are built from halves and
     * [everySampleThatCarriesADisplayOnlyTagIsReallyRewritten] guards them.
     */
    private companion object {
        private const val LT = "<"
        private const val THINK_OPEN = LT + "think>"
        private const val THINK_SELF_CLOSING = LT + "think />"
        private const val THINK_CLOSE = LT + "/think>"
        private const val THINKING_OPEN = LT + "thinking>"
        private const val THINKING_CLOSE = LT + "/thinking>"
        private const val THINK_FOO_OPEN = LT + "think!foo>"
        private const val THINK_FOO_CLOSE = LT + "/think!foo>"
    }

    private val samples =
        listOf(
            "plain answer",
            "  padded answer  ",
            "\u00a0non breaking space padding\u00a0",
            "",
            "   ",
            THINK_OPEN + "closed" + THINK_CLOSE + "visible",
            THINK_OPEN + "unclosed tail is dropped",
            "before" + THINK_SELF_CLOSING + "after" + THINKING_CLOSE + "orphan",
            THINK_OPEN + "cross" + THINKING_CLOSE + "answer" + THINK_CLOSE + "tail",
            THINK_FOO_OPEN + "lookalike" + THINK_FOO_CLOSE + "answer",
            "   " + THINKING_OPEN + "hidden" + THINKING_CLOSE + "   ",
            THINK_OPEN + "思考内容" + THINK_CLOSE + "回答",
            ChatUtils.PROVIDER_REASONING_OPEN_TAG + "hidden" + THINK_CLOSE + "answer",
        )

    /**
     * The samples the scan really rewrites, either by hiding a block or by dropping the tag itself.
     * A tag the editor path eats would leave them untouched, so this is what keeps the corpus honest.
     */
    private val samplesTheScanRewrites =
        listOf(
            THINK_OPEN + "closed" + THINK_CLOSE + "visible",
            THINK_OPEN + "unclosed tail is dropped",
            "before" + THINK_SELF_CLOSING + "after" + THINKING_CLOSE + "orphan",
            "   " + THINKING_OPEN + "hidden" + THINKING_CLOSE + "   ",
            THINK_OPEN + "思考内容" + THINK_CLOSE + "回答",
            ChatUtils.PROVIDER_REASONING_OPEN_TAG + "hidden" + THINK_CLOSE + "answer",
        )

    /** Visible markup that only looks like a display-only block, which the scan has to keep. */
    private val lookalikeSample = THINK_FOO_OPEN + "lookalike" + THINK_FOO_CLOSE + "answer"

    private val budgets = listOf(1, 2, 45, 200, 4_000)

    @Test
    fun everySampleThatCarriesADisplayOnlyTagIsReallyRewritten() {
        for (content in samplesTheScanRewrites) {
            assertNotEquals(
                "sample lost its display-only tag: $content",
                content,
                ChatUtils.removeThinkingContent(content),
            )
        }
        assertEquals(
            "a lookalike tag is visible markup, not a block",
            lookalikeSample,
            ChatUtils.removeThinkingContent(lookalikeSample),
        )
    }

    @Test
    fun assistantEntryReadsWhatTheUnwindowedStripAndCutProduced() {
        for (content in samples) {
            for (maxMessageChars in budgets) {
                assertEquals(
                    "assistant entry for $content at $maxMessageChars",
                    truncateTranscriptMessage(
                        ChatUtils.removeThinkingContent(content),
                        maxMessageChars,
                    ),
                    permissionReviewTranscriptContent(
                        sender = "ai",
                        roleName = "assistant",
                        content = content,
                        maxMessageChars = maxMessageChars,
                    ),
                )
            }
        }
    }

    @Test
    fun otherEntriesReadTheTextAsDeliveredCutToTheSameBudget() {
        for (content in samples) {
            for (maxMessageChars in budgets) {
                assertEquals(
                    "user entry for $content at $maxMessageChars",
                    truncateTranscriptMessage(content, maxMessageChars),
                    permissionReviewTranscriptContent(
                        sender = "user",
                        roleName = "user",
                        content = content,
                        maxMessageChars = maxMessageChars,
                    ),
                )
            }
        }
    }

    @Test
    fun aTurnThatGrewToMegabytesStillReadsTheSameSlice() {
        // One closed envelope per round, which is the shape a tool-heavy turn really has. One opening
        // tag followed by many closers would not do: the first closer ends the block and everything
        // after it stays visible, so almost nothing would be cut.
        val turn = StringBuilder(ChatUtils.PROVIDER_REASONING_OPEN_TAG)
        repeat(30_000) { round ->
            turn.append("reasoning ")
                .append(round)
                .append(THINK_CLOSE)
                .append("answer ")
                .append(round)
                .append('\n')
        }
        val content = turn.toString()
        assertTrue("the turn is long enough to matter", content.length > 1_000_000)
        val slice =
            permissionReviewTranscriptContent(
                sender = "ai",
                roleName = "assistant",
                content = content,
                maxMessageChars = 4_000,
            )
        assertEquals(
            truncateTranscriptMessage(ChatUtils.removeThinkingContent(content), 4_000),
            slice,
        )
        assertTrue("the visible answer survived the cut", slice.length > 1_000)
    }
}
