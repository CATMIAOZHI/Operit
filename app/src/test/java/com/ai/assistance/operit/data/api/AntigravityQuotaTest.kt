package com.ai.assistance.operit.data.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityQuotaTest {
    @Test fun oneRowPerFamilyFromTheModelList() {
        val body = JSONObject(
            "{\"models\": {" +
                "\"claude-sonnet-4-6\": {\"quotaInfo\": {\"remainingFraction\": 0.25, \"resetTime\": \"2026-11-25T12:00:00Z\"}}," +
                "\"gemini-3-pro\": {\"displayName\": \"Gemini 3 Pro\", \"quotaInfo\": {\"remainingFraction\": 1}}," +
                "\"gemini-3-flash\": {\"displayName\": \"no quota here\"}," +
                "\"chat_20706\": {\"quotaInfo\": {\"remainingFraction\": 0.5}}" +
                "}}"
        )
        val windows = AntigravityQuota.windows(body)
        assertEquals(listOf("Gemini", "Claude"), windows.map { it.label })
        assertTrue(windows.all { it.window == QuotaWindow.MODEL })
        // A Gemini model that reports decides the row; one without a share is passed over.
        assertEquals(0.0, windows[0].percent, 0.001)
        assertNull(windows[0].resetAtEpochMillis)
        assertEquals(75.0, windows[1].percent, 0.001)
        assertEquals(java.time.Instant.parse("2026-11-25T12:00:00Z").toEpochMilli(), windows[1].resetAtEpochMillis)
    }

    @Test fun listsAndTiersAreRead() {
        val body = JSONObject(
            "{\"models\": {" +
                "\"x\": {\"quotaInfoByTier\": {\"claude-opus\": [{\"remainingPercentage\": 0.4}]}}," +
                "\"y\": {\"quotaInfos\": [{\"note\": \"none\"}, {\"tier\": \"gemini\", \"remainingFraction\": 0.9}]}" +
                "}}"
        )
        val windows = AntigravityQuota.windows(body)
        assertEquals(listOf("Gemini", "Claude"), windows.map { it.label })
        assertEquals(10.0, windows[0].percent, 0.001)
        assertEquals(60.0, windows[1].percent, 0.001)
    }

    @Test fun nothingWithoutModels() {
        assertTrue(AntigravityQuota.windows(JSONObject("{}")).isEmpty())
        assertTrue(AntigravityQuota.windows(null).isEmpty())
    }

    @Test fun shareIsExactAndOnlyTextNamesCount() {
        val body = JSONObject(
            "{\"models\": {" +
                "\"m1\": {\"displayName\": {\"name\": \"Gemini\"}, \"quotaInfo\": {\"remainingFraction\": 0.1}}," +
                "\"claude-x\": {\"quotaInfos\": [{\"remainingFraction\": 0.55}, {\"remainingFraction\": 0.9, \"resetTime\": 1764000000}," +
                " {\"remainingFraction\": 0.55, \"resetTime\": 1764072000}]}" +
                "}}"
        )
        val windows = AntigravityQuota.windows(body)
        assertEquals(listOf("Claude"), windows.map { it.label })
        assertEquals(45.0, windows[0].percent, 0.0)
        // The first share stands; only an entry with the same share fills in its reset time.
        assertEquals(1764072000000L, windows[0].resetAtEpochMillis)
    }
}
