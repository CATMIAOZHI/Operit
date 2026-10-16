package com.ai.assistance.operit.features.orbit

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OrbitCalendarScheduleTest {
    private val zone = ZoneId.of("Asia/Taipei")

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun next(mode: String, rule: String, hhmm: String, now: String): LocalDateTime {
        val (h, m) = hhmm.split(':').map { it.toInt() }
        val millis = OrbitCalendarSchedule.next(mode, rule, h * 60 + m, at(now), zone)
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone)
    }

    @Test
    fun normalizeAcceptsCommonWritings() {
        assertEquals("1,3,5", OrbitCalendarSchedule.normalize("WEEKLY", "周五, mon，3"))
        assertEquals("6,7", OrbitCalendarSchedule.normalize("WEEKLY", "Sunday 星期六 7"))
        assertEquals("31", OrbitCalendarSchedule.normalize("MONTHLY", "31号"))
        assertEquals("12-03", OrbitCalendarSchedule.normalize("YEARLY", "12月3日"))
        assertEquals("02-29", OrbitCalendarSchedule.normalize("YEARLY", "2/29"))
    }

    @Test
    fun normalizeRejectsBadInput() {
        listOf("WEEKLY" to "8", "WEEKLY" to "someday", "WEEKLY" to "", "MONTHLY" to "0", "MONTHLY" to "32",
            "YEARLY" to "13-01", "YEARLY" to "02-30", "DAILY" to "1").forEach { (mode, value) ->
            assertThrows(IllegalArgumentException::class.java) { OrbitCalendarSchedule.normalize(mode, value) }
        }
    }

    @Test
    fun weeklyPicksNextListedWeekday() {
        // 2026-10-16 is a Friday.
        assertEquals(LocalDateTime.parse("2026-10-19T09:00"), next("WEEKLY", "1,5", "09:00", "2026-10-16T10:00"))
        assertEquals(LocalDateTime.parse("2026-10-16T09:00"), next("WEEKLY", "1,5", "09:00", "2026-10-16T08:00"))
        // Inside the one-minute lead time the same day is skipped.
        assertEquals(LocalDateTime.parse("2026-10-23T09:00"), next("WEEKLY", "5", "09:00", "2026-10-16T08:59:30"))
    }

    @Test
    fun monthlyUsesLastDayOfShortMonths() {
        assertEquals(LocalDateTime.parse("2026-11-30T08:00"), next("MONTHLY", "31", "08:00", "2026-10-31T09:00"))
        assertEquals(LocalDateTime.parse("2027-02-28T08:00"), next("MONTHLY", "30", "08:00", "2027-01-30T09:00"))
    }

    @Test
    fun yearlyCrossesYearAndHandlesLeapDay() {
        assertEquals(LocalDateTime.parse("2027-01-05T07:30"), next("YEARLY", "01-05", "07:30", "2026-10-16T10:00"))
        assertEquals(LocalDateTime.parse("2027-02-28T09:00"), next("YEARLY", "02-29", "09:00", "2026-10-16T10:00"))
        assertEquals(LocalDateTime.parse("2028-02-29T09:00"), next("YEARLY", "02-29", "09:00", "2027-03-01T10:00"))
    }
}
