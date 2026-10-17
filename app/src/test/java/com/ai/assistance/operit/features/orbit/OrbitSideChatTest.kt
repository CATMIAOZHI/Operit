package com.ai.assistance.operit.features.orbit

import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.OrbitSettings
import com.ai.assistance.operit.data.preferences.OrbitSideChat
import org.junit.Assert.*
import org.junit.Test

class OrbitSideChatTest {
    @Test fun sideChatsBelongToTheirOrbitLikeTheMainChat() {
        val orbit = OrbitProfile("o1", "card", "main", "Rainy", sideChats = listOf(OrbitSideChat("side", "Trip", 1)))
        val settings = OrbitSettings(orbits = listOf(orbit, OrbitProfile("o2", "card2", "other", "B")))
        assertEquals("o1", settings.orbitForChat("main")?.id)
        assertEquals("o1", settings.orbitForChat("side")?.id)
        assertEquals("o2", settings.orbitForChat("other")?.id)
        assertNull(settings.orbitForChat("task-chat"))
        assertEquals(listOf("main", "side"), orbit.talkChatIds)
    }

    @Test fun reactionsAcceptOneEmojiOnly() {
        listOf("😂", "❤️", "🫂", "👍🏽", "🇯🇵").forEach { assertTrue(it, OrbitReactions.isEmoji(it)) }
        listOf("", "ok", "好", "😂😂😂", "1", "👍 nice", ":)").forEach { assertFalse(it, OrbitReactions.isEmoji(it)) }
    }
}