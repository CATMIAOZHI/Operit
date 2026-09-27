package com.ai.assistance.operit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A turn that carries no display-only block is already its own visible text, so the scan has nothing
 * to cut and the result must be the input itself. Only the whitespace around it is ever removed, and
 * that removal follows the rule the scanning path uses, so both ends of the same turn agree.
 */
class ChatUtilsThinkingFastPathTest {

    @Test
    fun contentWithoutADisplayOnlyBlockIsReturnedWithoutACopy() {
        val content = "plain answer\nwith <tool name=\"a\" /> markup but no display-only block"
        assertSame(content, ChatUtils.removeThinkingContent(content))
    }

    @Test
    fun aMultiMegabyteTurnWithoutDisplayOnlyBlocksIsReturnedWithoutACopy() {
        val content =
            StringBuilder(1_500_000).apply {
                repeat(30_000) { round ->
                    append("<tool_result>chunk ").append(round).append("</tool_result>\n")
                }
                append("end")
            }.toString()
        assertTrue("turn is long enough to matter", content.length > 1_000_000)
        assertSame(content, ChatUtils.removeThinkingContent(content))
    }

    @Test
    fun surroundingWhitespaceIsStillRemovedWithoutADisplayOnlyBlock() {
        assertEquals("answer", ChatUtils.removeThinkingContent("\n\n  answer \t\n"))
        // The fast path has to trim exactly what the scanning path trims, including the non-ASCII
        // separators both call whitespace.
        assertEquals("answer", ChatUtils.removeThinkingContent("\u2028answer\u2028"))
        assertEquals("answer", ChatUtils.removeThinkingContent("\u2029answer\u2029"))
        assertEquals("answer", ChatUtils.removeThinkingContent("\u00a0answer\u00a0"))
        assertEquals("answer", ChatUtils.removeThinkingContent("\u3000answer\u3000"))
        // ...while a separator neither of them counts as whitespace has to survive.
        assertEquals("\u0085answer\u0085", ChatUtils.removeThinkingContent("\u0085answer\u0085"))
        assertEquals("", ChatUtils.removeThinkingContent("   \n\t  "))
        assertEquals("", ChatUtils.removeThinkingContent(""))
    }

    @Test
    fun contentWithADisplayOnlyBlockStillCutsItOut() {
        val content = "before<thinking>hidden</thinking>after"
        assertEquals("beforeafter", ChatUtils.removeThinkingContent(content))
        assertNotSame(content, ChatUtils.removeThinkingContent(content))
    }
}
