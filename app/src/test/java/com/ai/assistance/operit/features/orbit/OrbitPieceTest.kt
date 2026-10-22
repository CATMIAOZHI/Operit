package com.ai.assistance.operit.features.orbit

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
}
