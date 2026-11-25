package com.ai.assistance.operit.features.orbit

import com.ai.assistance.operit.ui.features.chat.components.OrbitPiece
import com.ai.assistance.operit.ui.features.chat.components.OrbitSpeech
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitPieceTest {
    private val approval = JSONObject().put("kind", OrbitChatCards.APPROVAL).put("title", "给张三发邮件")
        .put("approval_id", "a1").toString()

    @Test fun cardsAndDynamicUiAreTheirOwnBubbles() {
        val card = OrbitChatCards.withPieceKey(approval, OrbitReactions.pieceKey("card", 100, 0))
        val shown = "先说一句\n\n```a2ui\n{\"x\":1}\n```\n\n再说一句\n\n```orbit-card\n$card\n```"
        val pieces = OrbitSpeech.pieceList(shown, 200, english = false)
        assertEquals(listOf("200", "ui:200:0", "200", "card:100:0"), pieces.map { it.key })
        // Only the last words carry the message's own reaction; each card carries its own.
        assertEquals(listOf(false, true, true, true), pieces.map { it.showsBadge })
        assertNull(pieces[0].summary)
        assertEquals("[交互卡片]", pieces[1].summary)
        assertEquals("[批准卡片：给张三发邮件]", pieces[3].summary)
    }

    @Test fun aCardKeepsItsKeyWhenTheChatMovesIt() {
        val keyed = OrbitChatCards.withPieceKey(approval, "card:5:1")
        // Marking twice changes nothing.
        assertEquals(keyed, OrbitChatCards.withPieceKey(keyed, "card:9:9"))
        val pieces = OrbitSpeech.pieceList("```orbit-card\n$keyed\n```", 9, english = true)
        assertEquals("card:5:1", pieces.single().key)
        assertEquals("[Approval card: 给张三发邮件]", pieces.single().summary)
    }

    @Test fun plainWordsStayOneBubbleWithTheMessageKey() {
        val pieces = OrbitSpeech.pieceList("你好", 7, english = false)
        assertEquals("7", pieces.single().key)
        assertTrue(pieces.single().showsBadge)
    }

    @Test fun wordsAfterAToolStepAreANewBubbleLikeAMessageBreak() {
        val step = "<tool name=\"trade\"><param name=\"side\">buy</param></tool>\n" +
            "<tool_result name=\"trade\" status=\"success\"><content>ok</content></tool_result>"
        val raw = "$step\n我挂一张多单。\n$step\n$step\n开了。\n<think>想一下</think>\n止损挂上了。\n$step"
        val shown = OrbitSpeech.of(raw)
        // A step before the first words or after the last ones splits nothing; thinking never splits.
        assertEquals(listOf("我挂一张多单。", "开了。\n\n止损挂上了。"),
            OrbitSpeech.pieceList(shown, 5, english = false).map { it.content })
        assertEquals("我挂一张多单。\n\n开了。\n\n止损挂上了。", OrbitSpeech.words(raw, ""))
        assertEquals("先说\n\n后说", OrbitSpeech.of("先说\n<think>x</think>\n后说"))
    }

    @Test fun anAutoBreakNeedsWordsOnBothSidesAndNeverSplitsCode() {
        val step = "<tool name=\"trade\"></tool><tool_result name=\"trade\" status=\"success\"><content>ok</content></tool_result>"
        // Only markup on one side, cleaned away later: no break, so the row stays empty and hidden.
        assertEquals("", OrbitSpeech.of("<emotion>开心</emotion>$step<emotion>开心</emotion>"))
        assertEquals("说完了", OrbitSpeech.of("说完了$step<status type=\"done\">完成</status>"))
        // A step written as an example inside a code block is not a place to split.
        val code = "看这个：\n```\n<foo>\n<tool name=\"demo\"><param name=\"x\">x</param></tool>\n</foo>\n```\n完"
        assertFalse(OrbitSpeech.of(code).contains("message_break"))
        assertFalse(OrbitSpeech.of("看 `前<tool name=\"demo\"><param name=\"x\">x</param></tool>后` 完").contains("message_break"))
        assertFalse(OrbitSpeech.of("看 ``a ` 前${step}后`` 完").contains("message_break"))
        // An unclosed backtick run is plain text, so the split still happens after it.
        assertTrue(OrbitSpeech.of("看 ``` 没闭合 前${step}后").contains("message_break"))
        // A backtick before a code block never pairs with one after it.
        assertTrue(OrbitSpeech.of("有个 ` 号\n```txt\n代码\n```\n先查。\n$step\n查好了，是 `。").contains("message_break"))
        // Nor across a line break: inline code ends at the end of its line.
        assertTrue(OrbitSpeech.of("前文有个 ` 号。\n$step\n查好了，是 `。").contains("message_break"))
        // Orbit's own break is kept exactly as written, and wins over the chat's right next to it.
        assertEquals("甲<message_break/>乙", OrbitSpeech.tidyBreaks("甲<message_break/>乙"))
        assertEquals("甲<message_break/>\n乙", OrbitSpeech.of("甲<message_break/>${step}乙"))
        // A channel cleans without the chat's breaks: exactly the text it sent before.
        assertEquals("甲乙", OrbitSpeech.of("甲${step}乙", splitSteps = false))
        assertEquals("甲<message_break/>乙", OrbitSpeech.of("甲<message_break/>${step}乙", splitSteps = false))
        // Private-use text Orbit wrote is left alone.
        assertEquals("甲\uE002乙", OrbitSpeech.of("甲\uE002${step}乙", splitSteps = false))
    }

    @Test fun aVoiceLineAfterCodeClosedByALongerFenceIsItsOwnBubble() {
        val voice = "![语音](file:///data/files/voice/a.wav)"
        val shown = "看这段\n```\n$voice\n````\n$voice"
        val pieces = OrbitSpeech.pieceList(shown, 3, english = false, splitVoice = true)
        // The voice inside the code stays code; the one after the code is a bubble of its own.
        assertEquals(listOf("看这段\n```\n$voice\n````", voice), pieces.map { it.content })
    }

    // A reply to a voice bubble quotes its caption; one to the words beside it quotes only them.
    @Test fun aVoiceBubbleIsToldApartFromTheWordsBesideIt() {
        val pieces = OrbitSpeech.pieceList("好，我改了\n![录音室版](file:///data/files/voice/a.wav)\n记下了", 3, english = false, splitVoice = true)
        assertEquals(listOf(null, "录音室版", null), pieces.map { OrbitSpeech.voiceCaption(it) })
        assertEquals("", OrbitSpeech.voiceCaption(OrbitPiece("![](file:///data/files/voice/b.mp3)", "3", null, false)))
        assertEquals(null, OrbitSpeech.voiceCaption(OrbitPiece("![图](file:///data/files/a.png)", "3", null, false)))
    }

    @Test fun summariesNeverCarryAPathOrCode() {
        val file = JSONObject().put("kind", OrbitChatCards.FILE).put("name", "报告.pdf").put("path", "/data/secret/报告.pdf").toString()
        val summary = OrbitChatCards.summary(file, english = false)
        assertEquals("[文件：报告.pdf]", summary)
        assertFalse(summary.contains("/data"))
        assertEquals("[卡片]", OrbitChatCards.summary("not json", english = false))
    }

    @Test fun oldReactionKeysStillSortByTime() {
        assertEquals(1700L, OrbitReactions.timeOf("1700"))
        assertEquals(1700L, OrbitReactions.timeOf("card:1700:2"))
        assertEquals(1700L, OrbitReactions.timeOf("ui:1700:0"))
    }

    @Test fun aKeyInThePathKeepsTheCharactersAPathAllows() {
        assertEquals("123:ABC-_x", OrbitVaultBroker.encodePathSegment("123:ABC-_x"))
        assertEquals("a%2Fb%20c%3F", OrbitVaultBroker.encodePathSegment("a/b c?"))
        assertEquals("%E4%B8%AD", OrbitVaultBroker.encodePathSegment("中"))
    }

    @Test fun eachDynamicUiCardKnowsItsPlaceAndTheWholeViewAgrees() {
        val shown = "看\n\n```a2ui\n{\"a\":1}\n```\n\n```a2ui\n{\"a\":1}\n```\n\n```a2ui\n{\"b\":2}\n```"
        val ui = OrbitSpeech.pieceList(shown, 3, english = false).filter { it.uiIndex != null }
        assertEquals(listOf(0, 1, 2), ui.map { it.uiIndex })
        // Drawn from the whole message, a card finds the same number.
        assertEquals(2, OrbitSpeech.uiIndexOf(shown, "{\"b\":2}"))
        // Two cards with the same content: the second one is the second of them.
        assertEquals(0, OrbitSpeech.uiIndexOf(shown, "{\"a\":1}", earlier = 0))
        assertEquals(1, OrbitSpeech.uiIndexOf(shown, "{\"a\":1}", earlier = 1))
        assertNull(OrbitSpeech.uiIndexOf(shown, "{\"c\":3}"))
    }

    @Test fun aReplyOfOnlyCardsShowsAndChangesItsOlderWholeReaction() {
        val pieces = OrbitSpeech.pieceList("```orbit-card\n$approval\n```", 4, english = false)
        val first = pieces.first()
        assertEquals("4", first.fallbackBadgeKey)
        // Only the old whole-message reaction: it is the one shown, changed and removed.
        assertEquals("4", first.reactionKey(mapOf("4" to "👍")))
        // Once the card has its own, the card's wins.
        assertEquals(first.key, first.reactionKey(mapOf("4" to "👍", first.key to "❤️")))
        assertEquals(first.key, first.reactionKey(emptyMap()))
    }

    // Orbit grepped a log holding half a stored reply; its answer after that still shows in the chat.
    @Test fun anUnclosedThinkInAToolCallDoesNotHideTheReply() {
        val content = "<think>先查日志</think>" +
            "<tool_ab12 name=\"super_admin:terminal\"><param name=\"command\">grep -n '<think>' app.log</param></tool_ab12>" +
            "\n报告在下面，可以直接发给 Codex。"
        assertEquals("报告在下面，可以直接发给 Codex。", OrbitSpeech.of(content))
    }

    @Test fun anUnclosedThinkInAToolResultDoesNotHideTheReply() {
        val content = "<tool_result_ab12 name=\"super_admin:terminal\" status=\"success\"><content>12: <think>半条旧回复" +
            "</content></tool_result_ab12>\n报告在下面，可以直接发给 Codex。"
        assertEquals("报告在下面，可以直接发给 Codex。", OrbitSpeech.of(content))
    }

    // The steps found while reading past the thinking are the ones taken out; nothing reads them again.
    @Test fun aSelfClosingStepDoesNotTakeTheWordsAfterItWithALaterStep() {
        val content = "<tool name=\"old\"/>已说过的正文\n<tool name=\"run\"><param name=\"p\"><think>log</param></tool>"
        assertEquals("已说过的正文", OrbitSpeech.of(content))
    }

    @Test fun aStepClosingWithASpaceGoesWholeAndKeepsTheReply() {
        assertEquals("正常回答", OrbitSpeech.of("<tool comment=\"log\" name=\"read\"><think>私有</think></tool >正常回答"))
    }

    @Test fun aClosingTagInsideCdataDoesNotEndTheStep() {
        val content = "<tool name=\"write_file\"><param name=\"content\"><![CDATA[script </tool> with <think>literal]]>" +
            "</param></tool>真正的回答"
        assertEquals("真正的回答", OrbitSpeech.of(content))
    }

    @Test fun aStepMentionedInThinkingDoesNotSwallowTheReply() {
        val content = "<think>准备调用 <tool_x name=\"a\"> 看看</think>先说一句" +
            "<tool_x name=\"b\"><param name=\"p\"><think>log</param></tool_x>结论"
        // The words after the step are a bubble of their own.
        assertEquals("先说一句\n<message_break auto/>\n结论", OrbitSpeech.of(content))
    }

    // A broken step whose thinking runs on came out of Orbit's own thinking: finished or cut off,
    // none of that thinking shows.
    @Test fun thinkingAfterABrokenStepStaysHidden() {
        // With a parameter open too, a nested example's `</param></tool>` must not close the step.
        listOf("<tool name=\"cut\">", "<tool name=\"cut\"><param name=\"q\">").forEach { step ->
            val cut = "$step<think>想想 <tool name=\"example\"><param name=\"p\">v</param></tool>这里是隐藏思考"
            listOf(cut, "$cut</think>答复").forEach { content ->
                val shown = OrbitSpeech.of(content)
                assertFalse(shown, shown.contains("想想") || shown.contains("隐藏思考"))
            }
        }
    }

    // Two tool results each holding half of a log's thinking tags; the reply between them stays.
    @Test fun tagsInToolResultsPairWithNothingOutside() {
        val content = "<tool_result_a name=\"read\" status=\"success\"><content><think>前半日志</content></tool_result_a>" +
            "真正答复<tool_result_b name=\"read\" status=\"success\"><content>后半日志</think></content></tool_result_b>"
        assertEquals("真正答复", OrbitSpeech.of(content))
    }

    // A card's text can hold anything (a file named "<think>a.md"); it never reads as markup.
    @Test fun aCardsTextNeverReadsAsMarkup() {
        val card = JSONObject().put("kind", "file").put("name", "<think>a`\uE000.md")
        val content = "<tool_result_ab12 name=\"orbit_file\" status=\"success\"><content>" +
            JSONObject().put(OrbitChatCards.KEY, card) + "</content></tool_result_ab12>发好了"
        val shown = OrbitSpeech.history(listOf(com.ai.assistance.operit.data.model.ChatMessage("ai", content, timestamp = 100L)))
            .single().content
        val pieces = OrbitSpeech.pieceList(shown, 100L, english = false)
        assertEquals(listOf("发好了"), pieces.filter { it.summary == null }.map { it.content })
        val cardPiece = pieces.single { it.summary != null }
        assertEquals("card:100:0", cardPiece.key)
        val json = cardPiece.content.removePrefix("```orbit-card\n").removeSuffix("\n```")
        assertFalse(json, json.any { it == '<' || it == '>' || it == '`' || it == '\uE000' })
        assertEquals("<think>a`\uE000.md", JSONObject(json).getString("name"))
    }

    // Long runs of tags that never end (no `>`, no closing) cost one pass, not one per tag.
    @Test(timeout = 3_000L)
    fun longRunsOfUnendedTagsStayFast() {
        val n = 20_000
        val tail = "z".repeat(200_000)
        listOf("<tool_x ", "<tool_result_x ", "<status>", "<emotion>").forEach { tag ->
            val shown = OrbitSpeech.of("答复" + tag.repeat(n) + tail)
            assertTrue(shown.startsWith("答复"))
        }
        assertEquals("a b", OrbitSpeech.of("a<status type=\"x\">s</status> b<tool_x name=\"y\"/>"))
    }

    // A self-closing tag ends at the first `>` after its name, even with a `<` in an attribute.
    @Test fun selfClosingTagsWithALessThanInAnAttributeStillGo() {
        assertEquals("前文后文", OrbitSpeech.of("前文<status type=\"queued\" title=\"<queued\"/>后文"))
        assertEquals("实际答复", OrbitSpeech.of("<tool name=\"x\" description=\"a<b\"/>实际答复"))
    }

    // A tool result's opening is read once to find its card, however often it repeats a name.
    @Test(timeout = 3_000L)
    fun aLongToolResultOpeningIsReadOnce() {
        val card = JSONObject().put(OrbitChatCards.KEY, JSONObject().put("kind", "file").put("name", "a.md"))
        val opening = "<tool_result_ab12" + " name=\"orbit_file\"".repeat(50_000)
        fun shown(status: String) = OrbitSpeech.history(listOf(com.ai.assistance.operit.data.model.ChatMessage(
            "ai", "$opening status=\"$status\"><content>$card</content></tool_result_ab12>发好了", timestamp = 100L,
        ))).single().content
        assertEquals("发好了", shown("error"))
        assertTrue(shown("success").contains("```orbit-card"))
    }
}
