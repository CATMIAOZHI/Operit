package com.ai.assistance.operit.features.reading

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterKnowledgeJsonTest {
    @Test
    fun `structured chapter knowledge round trips without mixing fields`() {
        val raw =
            """
            {
              "summary":"主角在旧车站找到了信。",
              "characters":[
                {
                  "name":"林遥",
                  "aliases":["小林"],
                  "facts":["在旧车站找到一封信"]
                }
              ],
              "events":["找到信"],
              "locations":["旧车站"],
              "items":["信"],
              "relationship_changes":["暂无明确变化"],
              "possible_foreshadowing":["信封没有署名"],
              "keywords":["林遥","旧车站","信"]
            }
            """.trimIndent()

        val parsed = ChapterKnowledgeJson.parse(raw)
        val encoded = JSONObject(ChapterKnowledgeJson.encode(parsed))

        assertEquals("主角在旧车站找到了信。", parsed.summary)
        assertEquals("林遥", parsed.characters.single().name)
        assertEquals(listOf("小林"), parsed.characters.single().aliases)
        assertEquals("旧车站", parsed.locations.single())
        assertEquals("信封没有署名", parsed.possibleForeshadowing.single())
        assertEquals("林遥", encoded.getJSONArray("characters").getJSONObject(0).getString("name"))
    }

    @Test
    fun `blank and duplicate list items are removed`() {
        val parsed = ChapterKnowledgeJson.parse(
            """
            {
              "summary":"已读范围摘要",
              "characters":[],
              "events":["相遇","相遇","  "],
              "locations":[],
              "items":[],
              "relationship_changes":[],
              "possible_foreshadowing":[],
              "keywords":["线索","线索"]
            }
            """.trimIndent()
        )

        assertEquals(listOf("相遇"), parsed.events)
        assertEquals(listOf("线索"), parsed.keywords)
        assertFalse(parsed.summary.isBlank())
        assertTrue(parsed.characters.isEmpty())
    }
}
