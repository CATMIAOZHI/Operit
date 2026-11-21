package com.ai.assistance.operit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUtilsThinkingEdgeTest {

    @Test fun extractThinkingContent_handlesNoSearchTag() {
        val result = ChatUtils.extractThinkingContent("<think>x</think>")
        assertEquals("", result.first)
        assertEquals("x", result.second)
    }

    @Test fun removeThinkingContent_handlesSearchOnlyContent() {
        assertEquals("", ChatUtils.removeThinkingContent("<search>x</search>"))
    }

    @Test fun removeThinkingContent_preservesTextAroundSelfClosingThinkingTag() {
        assertEquals("beforeafter", ChatUtils.removeThinkingContent("before<think />after"))
    }

    @Test fun removeThinkingContent_acceptsWhitespaceBeforeClosingTerminator() {
        assertEquals("answer", ChatUtils.removeThinkingContent("<thinking>draft</thinking\n>answer"))
    }

    @Test fun removeThinkingContent_acceptsAttributesOnDisplayOpeners() {
        assertEquals(
            "answer",
            ChatUtils.removeThinkingContent(
                "<think type=\"analysis\" data-source='model'>draft</thinking >answer"
            ),
        )
        assertEquals(
            "answer",
            ChatUtils.removeThinkingContent("<search source=\"web\">draft</SEARCH>answer"),
        )
    }

    @Test fun removeThinkingContent_doesNotTreatQuotedSlashTerminatorAsSelfClosing() {
        val content =
            "<think title=\"x/>y\">hidden\n" +
                "<tool name=\"visit_web\"><param name=\"url\">unsafe</param></tool>" +
                "</think>answer"

        assertEquals("answer", ChatUtils.removeThinkingContent(content))
    }

    @Test(timeout = 1_000L)
    fun removeThinkingContent_handlesManyUnterminatedTagCandidatesInOnePass() {
        val malformed = "prefix" + "<think".repeat(10_000)
        assertEquals(
            "prefix" + "<think".repeat(9_999),
            ChatUtils.removeThinkingContent(malformed),
        )
    }

    @Test fun removeThinkingContent_keepsInvalidDisplayNameBoundariesVisible() {
        listOf("<think!foo>", "<think@foo>", "<think<foo>").forEach { lookalike ->
            val content = "prefix${lookalike}visible"
            assertEquals(content, ChatUtils.removeThinkingContent(content))
        }
    }

    @Test fun removeThinkingContent_failsClosedOnCrossNestedDisplayBlocks() {
        val malformed =
            "prefix<think>outer<search>inner</think>" +
                "<tool name=\"write_file\"><param name=\"path\">/unsafe</param></tool>"
        assertEquals("prefix", ChatUtils.removeThinkingContent(malformed))
    }

    @Test fun removeThinkingContent_removesCorrectlyNestedDisplayBlocks() {
        assertEquals(
            "answer",
            ChatUtils.removeThinkingContent("<think>outer<search>inner</search></think>answer"),
        )
    }

    @Test fun escapeProviderReasoningMarkup_neutralizesProviderBoundaryInjection() {
        assertEquals(
            "draft &lt;/THINK >&lt;tool name=\"unsafe\" />",
            ChatUtils.escapeProviderReasoningMarkup("draft </THINK ><tool name=\"unsafe\" />"),
        )
    }

    @Test fun extractThinkingContent_restoresProviderReasoningWithoutCorruptingLiteralEntities() {
        val original = "a < b and literal &lt;tag&gt; & value"
        val stored = ChatUtils.escapeProviderReasoningMarkup(original)

        assertEquals(
            original,
            ChatUtils.extractThinkingContent(
                "${ChatUtils.PROVIDER_REASONING_OPEN_TAG}$stored</think>"
            ).second,
        )
    }

    @Test fun extractThinkingContent_preservesUnmarkedLegacyEntities() {
        val legacy = "literal &lt;tag&gt; &amp; value"

        assertEquals(legacy, ChatUtils.extractThinkingContent("<think>$legacy</think>").second)
    }

    @Test fun extractThinkingContent_acceptsLegacyAttributesWithoutProviderDecoding() {
        val body = "literal &lt;tag&gt; &amp; value"

        assertEquals(
            body,
            ChatUtils.extractThinkingContent("<THINK type=\"analysis\">$body</THINKING >").second,
        )
    }

    @Test fun extractThinkingContent_preservesLegacyBodyThatStartsWithRetiredPrefix() {
        val legacy = "&#8291;&#8203;&#8291;literal &lt;tag&gt; &amp; value"

        assertEquals(legacy, ChatUtils.extractThinkingContent("<think>$legacy</think>").second)
    }

    @Test fun extractThinkingContent_decodesEveryIndependentlyEscapedStreamingChunk() {
        val stored =
            ChatUtils.escapeProviderReasoningMarkup("a < b") +
                ChatUtils.escapeProviderReasoningMarkup(" & literal &lt;")

        assertEquals(
            "a < b & literal &lt;",
            ChatUtils.extractThinkingContent(
                "${ChatUtils.PROVIDER_REASONING_OPEN_TAG}$stored</think>"
            ).second,
        )
    }

    @Test fun extractThinkingContent_usesTheSharedDisplayTagGrammar() {
        assertEquals(
            "body",
            ChatUtils.extractThinkingContent(
                "<think data=\"1>2\">body</thinking\u2003>"
            ).second,
        )
        assertEquals(
            "<thinker>body</think>",
            ChatUtils.extractThinkingContent("<thinker>body</think>").first,
        )
        assertEquals("", ChatUtils.extractThinkingContent("<thinker>body</think>").second)

        val pseudoEnvelope =
            "<thinkdata-operit-provider-reasoning=\"html-v1\">&lt;x&gt;</think>"
        assertEquals(pseudoEnvelope, ChatUtils.extractThinkingContent(pseudoEnvelope).first)
        assertEquals("", ChatUtils.extractThinkingContent(pseudoEnvelope).second)
    }

    @Test(timeout = 2_000L)
    fun extractThinkingContent_handlesManyThinkBlocksInsideDeepSearchInOnePass() {
        val depth = 40_000
        val content =
            buildString {
                repeat(depth) { append("<search>") }
                repeat(depth) { append("<think>x</think>") }
                repeat(depth) { append("</search>") }
            }

        val result = ChatUtils.extractThinkingContent(content)

        assertEquals("", result.first)
        assertEquals(depth, result.second.count { it == 'x' })
    }

    @Test fun providerReasoningDisplayDecode_requiresVersionedEnvelope() {
        val body = "literal &lt;tag&gt; &amp; value"

        assertEquals(
            "literal <tag&gt; & value",
            ChatUtils.decodeProviderReasoningForDisplay(
                "${ChatUtils.PROVIDER_REASONING_OPEN_TAG}$body</think>",
                body,
            ),
        )
        assertEquals(body, ChatUtils.decodeProviderReasoningForDisplay("<think>$body</think>", body))
    }

    @Test fun removeThinkingContent_preservesStrayMalformedClosingSyntax() {
        listOf("</think.foo>", "</think/>", "</think bogus>", "</think").forEach { closingTag ->
            val content = "prefix$closingTag visible"
            assertEquals(content, ChatUtils.removeThinkingContent(content))
        }
    }

    @Test fun removeThinkingContent_failsClosedOnMalformedClosingSyntax() {
        listOf("</think.foo>", "</think/>", "</think bogus>").forEach { closingTag ->
            assertEquals(
                "prefix",
                ChatUtils.removeThinkingContent("prefix<think>hidden$closingTag unsafe"),
            )
        }
    }

    // A tool's own input or output (a log, an old message) can carry an unclosed `<think>`; for a
    // caller that handles tool blocks itself, the rest of the turn stays.
    private val toolCall =
        "<tool_ab12 name=\"super_admin:terminal\"><param name=\"command\">grep -n '<think>' app.log</param></tool_ab12>"
    private val toolResult =
        "<tool_result_ab12 name=\"super_admin:terminal\" status=\"success\"><content>12: <think>half a stored reply" +
            "</content></tool_result_ab12>"

    /** The visible text of [content] with each tool block it reports in « ». */
    private fun outside(content: String): String {
        val out = StringBuilder()
        ChatUtils.visitOutsideThinking(content, object : ChatUtils.OutsideThinkingVisitor {
            override fun text(start: Int, end: Int) {
                out.append(content, start, end)
            }

            override fun toolBlock(start: Int, end: Int) {
                out.append('«').append(content, start, end).append('»')
            }
        })
        return out.toString()
    }

    @Test fun outsideThinking_keepsTheTurnAfterAToolCallCarryingAnUnclosedThink() {
        assertEquals("«$toolCall»\nThe report is ready.", outside("<think>plan</think>$toolCall\nThe report is ready."))
    }

    @Test fun outsideThinking_keepsTheTurnAfterAToolResultCarryingAnUnclosedThink() {
        assertEquals("«$toolResult»\nThe report is ready.", outside("<think>plan</think>$toolResult\nThe report is ready."))
    }

    @Test fun outsideThinking_stillHidesAToolInsideAnUnclosedThinkOfItsOwn() {
        assertEquals("answer", outside("answer<think>draft $toolCall tail"))
    }

    @Test fun outsideThinking_ignoresAToolOpeningMentionedInsideThinking() {
        val step = "<tool_x name=\"b\"><param name=\"p\"><think>log</param></tool_x>"
        assertEquals("answer«$step»done", outside("<think>I'll call <tool_x name=\"a\"> next</think>answer${step}done"))
    }

    @Test fun outsideThinking_doesNotPairASelfClosingToolWithALaterBlock() {
        assertEquals(
            "<tool name=\"x\"/>body«<tool name=\"y\">args</tool>»",
            outside("<tool name=\"x\"/><think>private</think>body<tool name=\"y\">args</tool>"),
        )
    }

    @Test fun outsideThinking_passesOverCdataInAToolCallAsTheExecutorDoes() {
        val step = "<tool name=\"write_file\"><param name=\"content\"><![CDATA[script </tool> with <think>literal]]>" +
            "</param></tool>"
        assertEquals("«$step»answer", outside("${step}answer"))
    }

    @Test fun outsideThinking_acceptsSpaceBeforeTheClosingBracket() {
        val step = "<tool comment=\"log\" name=\"read\"><think>private</think></tool >"
        assertEquals("«$step»answer", outside("${step}answer"))
    }

    @Test fun outsideThinking_leavesAnOpeningThatNeverClosesAsText() {
        val content = "answer<tool_x name=\"cut\"><param name=\"p\">half"
        assertEquals(content, outside(content))
    }

    // A "step" laid out unlike a tool call whose thinking runs on past it may have been cut out of
    // the model's own thinking: it is read as the plain scan reads it, finished or not.
    @Test fun outsideThinking_readsAStepWhoseThinkingRunsOnAsThePlainScanDoes() {
        val cut = "<tool name=\"cut\"><think>planning <tool name=\"example\"><param name=\"p\">v</param></tool>still thinking"
        assertEquals("<tool name=\"cut\">answer", outside("$cut</think>answer"))
        assertEquals("<tool name=\"cut\">", outside(cut))
        assertEquals(
            "<tool name=\"cut\">answer",
            outside("<tool name=\"cut\"><think><think>a</tool> b</think> c <tool name=\"d\">e</tool> f</think>answer"),
        )
        assertEquals("<tool name=\"t\">", outside("<tool name=\"t\"><think>x</tool>y</search>z"))
        assertEquals("<tool name=\"t\">", outside("<tool name=\"t\"><think><search>x</think></tool>private"))
        assertEquals("<tool name=\"t\">", outside("<tool name=\"t\"><think>x</tool>private<search>a</think>answer"))
    }

    // Tags in complete tool results are their output: they pair with nothing outside them.
    @Test fun outsideThinking_keepsTagsInCompleteToolResultsToThemselves() {
        val first = "<tool_result_a name=\"read\" status=\"success\"><content><think>first half of a log</content></tool_result_a>"
        val second = "<tool_result_b name=\"read\" status=\"success\"><content>second half</think></content></tool_result_b>"
        assertEquals("«$first»the answer«$second»", outside("${first}the answer$second"))
        val reply = "Report: look for `</think>` and go on."
        assertEquals("«$toolResult»$reply", outside("$toolResult$reply"))
    }

    @Test fun outsideThinking_readsOnlyWholeToolNames() {
        assertEquals("<tool-example>answer", outside("<tool-example><think>half</tool>still thinking</think>answer"))
    }

    @Test fun outsideThinking_endsAWrappedToolResultAfterItsContent() {
        val step = "<tool_result name=\"read\" status=\"success\"><content>data </tool_result> <think>more" +
            "</content></tool_result>"
        assertEquals("«$step»answer", outside("${step}answer"))
    }

    @Test fun outsideThinking_passesOverCommentsInAToolCall() {
        val step = "<tool name=\"x\"><param name=\"p\">a<!-- </tool> --><think>b</param></tool>"
        assertEquals("«$step»answer", outside("${step}answer"))
        val inValue = "<tool name=\"x\"><param name=\"p\"><!-- </param> --><![CDATA[<think>log]]></param></tool>"
        assertEquals("«$inValue»answer", outside("${inValue}answer"))
    }

    // The executor pairs tags as they nest: a block never ends at the closing of one nested in it.
    @Test fun outsideThinking_doesNotTakeANestedClosingForItsOwn() {
        val call = "<tool name=\"cut\"><param name=\"p\"><think>half <tool name=\"example\"><param name=\"e\">v</param></tool>" +
            "still thinking"
        assertEquals("<tool name=\"cut\"><param name=\"p\">answer", outside("$call</think>answer"))
        assertEquals("<tool name=\"cut\"><param name=\"p\">", outside(call))
        val result = "<tool_result name=\"r\"><content><think>half <tool_result name=\"r\"><content>x</content></tool_result>" +
            "still thinking</think>answer"
        assertEquals("<tool_result name=\"r\"><content>answer", outside(result))
        // An old block of another name (each carries a random suffix) does not end the one holding it.
        val log = "<tool_result_a1 name=\"read\" status=\"success\"><content>log <think>x " +
            "<tool_result_b2 name=\"r\"><content>old</content></tool_result_b2> tail</content></tool_result_a1>"
        assertEquals("«$log»answer", outside("${log}answer"))
        // A `/>` in an attribute does not make an opening a self-closing tag.
        val quoted = "<tool_result name=\"r\"><content><think>half <tool_result name=\"r\" title=\"a /> b\"><content>x" +
            "</content></tool_result>still thinking</think>answer"
        assertEquals("<tool_result name=\"r\"><content>answer", outside(quoted))
    }

    // An opening ends at its `>` outside quotes, as the executor reads it.
    @Test fun outsideThinking_readsQuotesInAToolOpening() {
        val gt = "<tool name=\"x\" description=\"a > b\"><param name=\"p\"><think>log</param></tool>"
        assertEquals("«$gt»answer", outside("${gt}answer"))
        val slash = "<tool name=\"x\" description=\"a /> b\"><param name=\"p\"><![CDATA[<think>log]]></param></tool>"
        assertEquals("«$slash»answer", outside("${slash}answer"))
    }

    // Openings whose quotes never close each read on to the end; once that reading has covered
    // more than the whole text, no tool block is found any more and the plain scan reads the rest.
    @Test fun outsideThinking_stopsFindingToolBlocksOnceReadingOpeningsRunsOut() {
        val unclosed = "<tool_b a='<tool_c b='"
        val step = "<tool name=\"x\"><param name=\"p\"><think>log</param></tool>"
        assertEquals("answer$unclosed<tool name=\"x\"><param name=\"p\">", outside("answer$unclosed${step}private"))
        assertEquals(ChatUtils.removeThinkingContent("answer$unclosed${step}private"), outside("answer$unclosed${step}private"))
        // An opening whose own reading goes past the bound is not taken either.
        val crossing = "<tool_b a='$step" + "private"
        assertEquals(ChatUtils.removeThinkingContent(crossing), outside(crossing))
        assertFalse(outside(crossing).contains("private"))
    }

    // Only openings the executor would read as tool tags start a block.
    @Test fun outsideThinking_readsOnlyOpeningsTheExecutorAccepts() {
        listOf(
            "<tool!><param name=\"p\"><think>draft</param></tool>private</think>answer",
            "<tool name=\"x\"!><param name=\"p\"><think>draft</param></tool>private</think>answer",
            "<tool name=x><param name=\"p\"><think>draft</param></tool>private</think>answer",
            "<tool name=\"x\" / a=\"b\"><param name=\"p\"><think>draft</param></tool>private</think>answer",
        ).forEach { content ->
            assertEquals(content, ChatUtils.removeThinkingContent(content), outside(content))
            assertFalse(content, outside(content).contains("private"))
        }
        val spaced = "<tool name = 'x'\n description=\"a\" ><param name=\"p\"><think>log</param></tool>"
        assertEquals("«$spaced»answer", outside("${spaced}answer"))
    }

    // A display-only tag in an opening's attributes is part of the block it opens.
    @Test fun outsideThinking_countsThinkingInAnOpeningsAttributes() {
        val content = "<tool name=\"x\" note=\"<think>\">raw</tool>private</think>answer"
        assertEquals(ChatUtils.removeThinkingContent(content), outside(content))
        assertFalse(outside(content).contains("private"))
    }

    // A closing tag inside a `<param` opening's quotes does not end the call.
    @Test fun outsideThinking_readsQuotesInAParamOpening() {
        val step = "<tool name=\"x\"><param name=\"</tool>\"><![CDATA[<think>log]]></param></tool>"
        assertEquals("«$step»answer", outside("${step}answer"))
    }

    // Only parameters laid out as the executor reads them make a call's input; a tag in a value
    // whose end the executor could read elsewhere (a `<` in its quotes, or no end) does not either.
    @Test fun outsideThinking_readsOnlyParametersTheExecutorAccepts() {
        listOf(
            "<tool name=\"x\"><param bogus><think>draft</param></tool>private</think>answer",
            "<tool name=\"x\"><param name=p><think>draft</param></tool>private</think>answer",
            "<tool name=\"x\"><param name=\"\"><think>draft</param></tool>private</think>answer",
            "<tool name=\"x\"><param name=\"p\" id=\"1\"><think>draft</param></tool>private</think>answer",
            "<tool name=\"x\"><param name=\"p\"><span a=\"<think>draft</param></tool>\">private</think>answer",
            "<tool name=\"x\"><param name=\"p\"><think>draft <b</param></tool>private</think>answer",
            "<tool name=\"x\"><param name=\"p\"><think>draft</param x></tool>private</think>answer",
            "<tool name=\"x\"><param name=\"p\"><think>draft</param ></tool>private</think>answer",
            "<tool name=\"x\"><param name=\"p\"><tool_b name=\"y\"><think>draft</param></tool>private</think>answer",
            "<tool><param name=\"p\"><think>draft</param></tool>private</think>answer",
            "<tool name=\"\"><param name=\"p\"><think>draft</param></tool>private</think>answer",
            "<tool name=\"x\"><param name=\"p\"><think>draft</param></tool >private</think>answer",
            "<tool name=\"x\"><param name=\"p\">< 1 a=\"<think>draft</param></tool>\">private</think>answer",
            "<tool name=\"x\">raw</think a='</tool><think b=\"'><think>\"/>private</think>answer",
            "<tool_resulted><content><think>x</content></tool_resulted>private</think>answer",
            "<tool name=\"x\"><param name=\"p\"><think>a < b 'q</param></tool>private'></think>answer",
            "<tool name=\"x\"><param name=\"p\"><!--<think>--><bad!></param></tool>private</think>answer",
            "<tool name=\"x\"><param name=\"p\"><!--<think>--><!DOCTYPE x></param></tool>private</think>answer",
            "<tool name=\"x\"><param name=\"p\"><!--<think>--><b>open</param></tool>private</think>answer",
            "<tool_s name=\"x\"><param name=\"p\"><!--<think>--></param></tool_\u017F>private</tool_s></think>answer",
            // A backtick outside the values could make the executor's code mask hide the call's tags.
            "<tool name=\"x\" note=\"`\"><param name=\"p\">`<think>`</param></tool>private</think>answer",
            "<tool name=\"x\"><!-- ` --><param name=\"p\">`<think>`</param></tool>private</think>answer",
            "<tool name=\"x\"><param name=\"p`\">`<think>`</param></tool>private</think>answer",
            // With no end to the value for the executor's mask, the reasoning envelope in the
            // parameter's opening marks the rest.
            "<tool name=\"x\"><param name='${ChatUtils.PROVIDER_REASONING_OPEN_TAG}'><!--<![CDATA[--></param></tool>private</think>answer",
            // A tool's closing outside the values, read as the executor reads it (trimmed of any
            // Unicode space, its name matched whole), changes how the mask reads the code after it.
            "<tool name=\"x\" note='</\u00A0tool>'><param name=\"p\">```<think></param></tool>private</think>answer",
            "<tool name=\"x\"><!-- </\u3000tool> --><param name=\"p\">`<think>draft</param></tool>private</think>answer",
            "<tool name=\"x\"><!-- </tool_\u017F> --><param name=\"p\">`<think>draft</param></tool>private</think>answer",
        ).forEach { content ->
            assertEquals(content, ChatUtils.removeThinkingContent(content), outside(content))
            assertFalse(content, outside(content).contains("private"))
        }
        // A nested parameter in any case, and CDATA in lower case, which the executor does not read.
        val nested = "<tool name=\"x\"><param name=\"p\"><PARAM name=\"q\"><!-- <think>draft --></param></tool>private</think>answer"
        assertEquals(ChatUtils.removeThinkingContent(nested), outside(nested))
        val lowerCdata = "<tool name=\"x\"><param name=\"p\"><![cdata[</param></tool><think>draft]]></param></tool>private</think>answer"
        assertFalse(outside(lowerCdata).contains("private"))
        val spaced = "<tool name=\"x\"><param\n NAME = 'p' ><think>log <b class=\"a>b\">x</b></PARAM></tool>"
        assertEquals("«$spaced»answer", outside("${spaced}answer"))
        // A `<` in a tag's quotes in a value is part of that tag, as the executor reads it.
        val html = "<tool name=\"write_file\"><param name=\"content\"><span title=\"<think>\">示例</span></param></tool>"
        assertEquals("«$html»答复", outside("${html}答复"))
        // A self-closing example of another tool in a value is input too; `a < b` is text.
        val example = "<tool name=\"write_file\"><param name=\"content\"><tool_example/><!-- <think>示例 --><b>正文</b>" +
            " if a < b < c</param></tool>"
        assertEquals("«$example»答复", outside("${example}答复"))
        // A `<` that is not a tag and opens a quote that never closes is text, as the executor reads it.
        val apostrophe = "<tool name=\"write_file\"><param name=\"content\"><!-- <think>示例 -->Compare a < b. It's true." +
            "</param></tool>"
        assertEquals("«$apostrophe»答复", outside("${apostrophe}答复"))
        // Tags in a value pair as the executor pairs them; a closing in a tag's quotes, or a
        // self-closing tag of the call's own name, does not end the call.
        val paired = "<tool name=\"x\"><param name=\"p\"><span title=\"</tool>\"><think>log</span><tool/></param></tool>"
        assertEquals("«$paired»answer", outside("${paired}answer"))
    }

    // Markdown code in a value is text to the executor: its tags pair with nothing, and a code
    // span or fence that does not close ends where the value does.
    @Test fun outsideThinking_readsMarkdownCodeInAValueAsText() {
        listOf(
            "示例：`<b>`、`<think>`。",
            "```html\n<div>\n<think>\n```\n",
            "未闭合 `<b> <think>\n</b>",
            "`<think>",
            "```\n<b><think>",
            "``a`<b>`` x",
            // The call's own closing, or a comment opening, in code does not end the call.
            "示例：`<think>`、`</tool>`。",
            "`<!--` `<think>`",
        ).forEach { value ->
            val call = "<tool name=\"write_file\"><param name=\"content\">$value</param></tool>"
            assertEquals(value, "«$call»答复", outside("${call}答复"))
        }
        // Code that closes in a comment outside the values, or in the opening's quotes, is its own.
        listOf(
            "<tool name=\"write_file\"><!-- `说明` --><param name=\"content\"><!-- <think>示例 -->正文</param></tool>",
            "<tool name=\"write_file\" note=\"`a` <b>\"><!-- ```\nx\n``` --><param name=\"content\">`<think>`</param></tool>",
            "<tool name=\"write_file\"><!-- `</tool>` --><param name=\"content\"><!-- <think>示例 -->正文</param></tool>",
            "<tool name=\"write_file\"><!-- <toolbar> <parameter> --><param name=\"content\">`<think>`</param></tool>",
            // With no code after it, a tool tag outside the values changes nothing that matters.
            "<tool name=\"write_file\"><!-- <tool_y/> --><param name=\"content\"><!-- <think>示例 -->正文</param></tool>",
        ).forEach { call -> assertEquals(call, "«$call»答复", outside("${call}答复")) }
        // Outside code, the same tags still pair.
        val plain = "<tool name=\"write_file\"><param name=\"content\">`a` <b><think></param></tool>private</think>答复"
        assertFalse(outside(plain).contains("private"))
    }

    // The `<bytes>` placeholder old tool output writes after `usage:` or `limit:` holds nothing.
    @Test fun outsideThinking_readsTheUsagePlaceholderAsTheExecutorDoes() {
        listOf(
            "<![CDATA[<think>]]> usage: <bytes>,",
            "<!--<think>-->limit:<BYTES>\u3002",
            "<!--<think>-->Usage: <bytes>\n<tool_b name=\"y\"/>",
        ).forEach { value ->
            val call = "<tool name=\"x\"><param name=\"p\">$value</param></tool>"
            assertEquals(value, "«$call»answer", outside("${call}answer"))
        }
        // Not the placeholder: a space before the colon, another key, or text after it.
        listOf(" usage : <bytes>,", " myusage: <bytes>,", " usage: <bytes> 12").forEach { value ->
            val content = "<tool name=\"x\"><param name=\"p\"><!--<think>-->$value</param></tool>private</think>answer"
            assertFalse(content, outside(content).contains("private"))
        }
    }

    // Many calls whose values read on, from one `<` that is not a tag, to the same far end; few
    // enough that the calls themselves stay within what searches may read.
    @Test(timeout = 3_000L)
    fun outsideThinking_staysLinearOnValuesThatReadToTheEnd() {
        val m = 300
        val content = (0 until m).joinToString("") { "<tool_n$it name=\"x\"><param name=\"p\"><!--" } +
            "--><think>x < b 'q</param>" + (m - 1 downTo 0).joinToString("") { "</tool_n$it>" } + "z".repeat(8_000_000)
        assertTrue(outside(content).length <= content.length)
    }

    // Calls of one name, each closed early in code for the plain scan, that all read on to one far
    // parameter whose quote never closes; few enough that the calls themselves stay within what
    // searches may read.
    @Test(timeout = 3_000L)
    fun outsideThinking_countsReadingPastAFalseClosing() {
        val k = 600
        val content = "<tool_x name=\"x\"><param name=\"p\">`</tool_x>`<!--".repeat(k) +
            "--></param><param name=\"" + "z".repeat(16_000_000) + "</tool_x>"
        assertTrue(outside(content).startsWith("«<tool_x"))
    }

    @Test fun removeThinkingContent_stillStripsThinkingInsideToolBlocks() {
        val content =
            "<tool_result_ab12 name=\"read\" status=\"success\"><content><think>old</think>file</content></tool_result_ab12>"
        assertEquals(
            "<tool_result_ab12 name=\"read\" status=\"success\"><content>file</content></tool_result_ab12>",
            ChatUtils.removeThinkingContent(content),
        )
    }

    @Test(timeout = 3_000L)
    fun outsideThinking_staysLinearOnLongRunsOfToolTags() {
        val n = 20_000
        val longText = "z".repeat(200_000)
        // Openings of one name, and of many names, that never close.
        val sameName = "<tool_ab12 name=\"x\">".repeat(n)
        assertEquals("answer$sameName«<tool_ab12 name=\"y\">v</tool_ab12>»tail",
            outside("answer$sameName<tool_ab12 name=\"y\">v</tool_ab12>tail"))
        val manyNames = "answer" + (0 until n).joinToString("") { "<tool_n$it name=\"x\">" } + longText
        assertEquals(manyNames, outside(manyNames))
        // Complete blocks of many names before a long reply.
        val blocks = (0 until n).map { "<tool_n$it name=\"x\">v</tool_n$it>" }
        assertEquals(blocks.joinToString("") { "«$it»" } + longText, outside(blocks.joinToString("") + longText))
        // Two runs of the same openings around a long text, all closing after the second.
        val run = (0 until 5_000).joinToString("") { "<tool_n$it name=\"x\">" }
        val laterClosings = (1 until 5_000).joinToString("") { "</tool_n$it>" }
        assertTrue(outside("$run$longText$run</tool_n0>$laterClosings").startsWith("<tool_n0 name=\"x\">"))
        // Many CDATA sections in one call, and openings mentioned in many thoughts before a far closing.
        val cdata = "<tool name=\"w\">" + "<![CDATA[a</tool>b]]>".repeat(n) + "</tool>"
        assertEquals("«$cdata»answer", outside("${cdata}answer"))
        val thoughts = "<think><tool_x name=\"a\"></think>".repeat(n) + longText + "</tool_x>"
        assertEquals("$longText</tool_x>", outside(thoughts))
        // Openings of many names whose closings only come inside one far CDATA section.
        val names = 0 until n
        val inCdata = "answer" + names.joinToString("") { "<tool_n$it name=\"x\">" } + "<![CDATA[" +
            names.joinToString("") { "</tool_n$it>" } + "]]>" + longText
        assertTrue(outside(inCdata).startsWith("answer"))
        // Wrapped results of one name whose only closing lacks `</content>`, before a long text,
        // and the same with a long run of spaces inside that closing.
        val unwrapped = "<tool_result_x><content>".repeat(n) + "</tool_result_x>" + longText
        assertTrue(outside(unwrapped).length <= unwrapped.length)
        val spaced = "<tool_result_x><content>".repeat(n) + "</tool_result_x" + " ".repeat(200_000) + ">"
        assertTrue(outside(spaced).length <= spaced.length)
        // Steps whose thinking runs on, each holding the next, all closing at one far tag.
        val nested = "<tool name=\"a\"><think></think>".repeat(n) + "<think>" + longText + "</tool>"
        assertTrue(outside(nested).length <= nested.length)
        // Openings whose quotes never close, and openings inside another's quotes.
        val quotes = "answer" + "<tool name=\"".repeat(n) + "<tool_x a='".repeat(n) + longText + "</tool></tool_x>"
        assertTrue(outside(quotes).startsWith("answer"))
    }
}
