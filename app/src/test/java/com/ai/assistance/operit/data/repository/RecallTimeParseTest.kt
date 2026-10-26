package com.ai.assistance.operit.data.repository

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RecallTimeParseTest {
    /** The proactive check writes its window this way, and the model copies it into after/before. */
    @Test fun acceptsTheAppsOwnLocalTimeFormat() {
        val expected = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse("2026-10-26 12:12:36")!!.time
        assertEquals(expected, parseRecallTime("2026-10-26 12:12:36", false))
        assertEquals(expected, parseRecallTime("2026-10-26T12:12:36", false))
    }

    @Test fun keepsExactAndOffsetTimes() {
        assertEquals(1_792_989_156_000L, parseRecallTime("2026-10-26T04:32:36Z", false))
        assertEquals(1_792_989_156_000L, parseRecallTime("2026-10-26T12:32:36+08:00", false))
        assertEquals(1_792_989_156_000L, parseRecallTime("1792989156000", true))
    }
}
