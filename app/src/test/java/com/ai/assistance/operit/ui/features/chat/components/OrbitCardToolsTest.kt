package com.ai.assistance.operit.ui.features.chat.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** A card from a tool not in [OrbitSpeech.CARD_TOOLS] is dropped, so the tool says "sent" but nothing shows. */
class OrbitCardToolsTest {
    @Test
    fun everyToolThatSendsACardIsListed() {
        val dir = listOf(File("src/main/java/com/ai/assistance/operit/core/tools"),
            File("app/src/main/java/com/ai/assistance/operit/core/tools")).first { it.isDirectory }
        val name = Regex("const val NAME = \"(orbit_[a-z_]+)\"")
        val senders = dir.listFiles().orEmpty().filter { it.name.endsWith(".kt") }.mapNotNull { file ->
            val text = file.readText()
            if (!text.contains("OrbitChatCards.KEY")) null else name.find(text)?.groupValues?.get(1)
        }
        assertTrue("no card tools found in $dir", senders.isNotEmpty())
        val missing = senders.filter { it !in OrbitSpeech.CARD_TOOLS }
        assertTrue("card tools missing from OrbitSpeech.CARD_TOOLS: $missing", missing.isEmpty())
    }
}
