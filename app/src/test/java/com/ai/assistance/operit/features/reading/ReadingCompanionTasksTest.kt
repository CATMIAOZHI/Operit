package com.ai.assistance.operit.features.reading

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReadingCompanionTasksTest {
    @Test
    fun `task results retain UI counters without duplicating generated chapter prose`() {
        val result = JSONObject()
            .put("status", "completed")
            .put("completedCount", 2)
            .put("remainingMissing", 4)
            .put("targetChapterIndices", JSONArray(listOf(1, 2)))
            .put("results", JSONArray().put(JSONObject().put("summary", "generated chapter prose")))
        val compact = ReadingCompanionTasks.compactResult(result)
        assertEquals(2, compact.getInt("completedCount"))
        assertEquals(4, compact.getInt("remainingMissing"))
        assertEquals("[1,2]", compact.getJSONArray("targetChapterIndices").toString())
        assertFalse(compact.has("results"))
        assertFalse(compact.toString().contains("generated chapter prose"))
    }
}
