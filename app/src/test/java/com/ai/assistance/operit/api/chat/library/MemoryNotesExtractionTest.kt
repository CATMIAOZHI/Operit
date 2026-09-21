package com.ai.assistance.operit.api.chat.library

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryNotesExtractionTest {
    @Test fun `invalid optional notes are ignored without throwing`() {
        assertTrue(parseShortMemoryNotes(JSONObject.NULL).isEmpty())
        assertTrue(parseShortMemoryNotes(JSONObject()).isEmpty())
        val notes = JSONArray().put(JSONObject.NULL).put(12).put(JSONObject())
            .put(" ").put("x".repeat(201)).put(" verified fact ")
        assertEquals(listOf("verified fact"), parseShortMemoryNotes(notes))
    }

    @Test fun `background notes are short deduplicated and bounded`() {
        val notes = JSONArray("""["a","a","b","c","d"]""")
        assertEquals(listOf("a", "b", "c"), parseShortMemoryNotes(notes))
    }
}
