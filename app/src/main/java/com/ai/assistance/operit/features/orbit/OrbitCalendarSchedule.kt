package com.ai.assistance.operit.features.orbit

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Weekly, monthly and yearly Orbit schedules. The time of day lives in the task's dailyMinute;
 * the day lives in calendarRule:
 * - WEEKLY: ISO weekdays, "1,3,5" (1 = Monday, 7 = Sunday)
 * - MONTHLY: a day of the month, "15"; short months use their last day
 * - YEARLY: month and day, "12-03"; Feb 29 falls on Feb 28 in other years
 */
internal object OrbitCalendarSchedule {
    const val WEEKLY = "WEEKLY"
    const val MONTHLY = "MONTHLY"
    const val YEARLY = "YEARLY"
    val modes = setOf(WEEKLY, MONTHLY, YEARLY)

    /** Same lead time as daily check-ins: a run is never scheduled less than a minute ahead. */
    private const val MIN_LEAD_MS = 60_000L

    private val weekdayNames = mapOf(
        "mon" to 1, "monday" to 1, "周一" to 1, "星期一" to 1,
        "tue" to 2, "tuesday" to 2, "周二" to 2, "星期二" to 2,
        "wed" to 3, "wednesday" to 3, "周三" to 3, "星期三" to 3,
        "thu" to 4, "thursday" to 4, "周四" to 4, "星期四" to 4,
        "fri" to 5, "friday" to 5, "周五" to 5, "星期五" to 5,
        "sat" to 6, "saturday" to 6, "周六" to 6, "星期六" to 6,
        "sun" to 7, "sunday" to 7, "周日" to 7, "周天" to 7, "星期日" to 7, "星期天" to 7,
    )

    /**
     * Turns what the model wrote into the stored rule, or throws a message the model can act on.
     * Weekdays may be numbers or names; the stored form is sorted numbers.
     */
    fun normalize(mode: String, value: String): String {
        val text = value.trim()
        return when (mode) {
            WEEKLY -> {
                val days = text.split(',', '，', '、', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    .map { token -> token.toIntOrNull() ?: weekdayNames[token] ?: throw IllegalArgumentException(WEEKLY_HINT) }
                require(days.isNotEmpty() && days.all { it in 1..7 }) { WEEKLY_HINT }
                days.distinct().sorted().joinToString(",")
            }
            MONTHLY -> {
                val day = text.removeSuffix("日").removeSuffix("号").trim().toIntOrNull()
                require(day != null && day in 1..31) { MONTHLY_HINT }
                day.toString()
            }
            YEARLY -> {
                val match = Regex("""^(\d{1,2})[-/.月](\d{1,2})日?$""").find(text) ?: throw IllegalArgumentException(YEARLY_HINT)
                val month = match.groupValues[1].toInt()
                val day = match.groupValues[2].toInt()
                require(month in 1..12 && day in 1..Month.of(month).maxLength()) { YEARLY_HINT }
                String.format(java.util.Locale.ROOT, "%02d-%02d", month, day)
            }
            else -> throw IllegalArgumentException("repeat_on only works with WEEKLY, MONTHLY or YEARLY")
        }
    }

    /** The first matching day and time at least a minute after [now]. */
    fun next(mode: String, rule: String, minuteOfDay: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        val earliest = Instant.ofEpochMilli(now + MIN_LEAD_MS)
        var date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        // Weekly needs at most 8 days, monthly 2 months and yearly 2 years to reach a match.
        repeat(800) {
            matchOn(mode, rule, date)?.let { day ->
                val candidate = ZonedDateTime.of(day, time, zone).toInstant()
                if (!candidate.isBefore(earliest)) return candidate.toEpochMilli()
            }
            date = when (mode) {
                WEEKLY -> date.plusDays(1)
                MONTHLY -> date.withDayOfMonth(1).plusMonths(1)
                else -> date.withDayOfYear(1).plusYears(1)
            }
        }
        error("No matching date for $mode $rule")
    }

    /** The scheduled day in the week, month or year that contains [date], or null when it has none. */
    private fun matchOn(mode: String, rule: String, date: LocalDate): LocalDate? = when (mode) {
        WEEKLY -> date.takeIf { date.dayOfWeek.value in weekdays(rule) }
        MONTHLY -> date.withDayOfMonth(minOf(rule.toInt(), date.lengthOfMonth()))
            .takeIf { !it.isBefore(date) }
        YEARLY -> {
            val (month, day) = monthDay(rule)
            val target = LocalDate.of(date.year, month, 1).let { it.withDayOfMonth(minOf(day, it.lengthOfMonth())) }
            target.takeIf { !it.isBefore(date) }
        }
        else -> error("Unknown calendar schedule $mode")
    }

    fun weekdays(rule: String): List<Int> = rule.split(',').mapNotNull { it.trim().toIntOrNull() }

    fun dayOfWeek(value: Int): DayOfWeek = DayOfWeek.of(value)

    fun monthDay(rule: String): Pair<Int, Int> =
        rule.split('-').let { it[0].toInt() to it[1].toInt() }

    private const val WEEKLY_HINT = "WEEKLY repeat_on lists weekdays, like 1,3,5 (1 = Monday, 7 = Sunday)"
    private const val MONTHLY_HINT = "MONTHLY repeat_on is a day of the month from 1 to 31, like 15"
    private const val YEARLY_HINT = "YEARLY repeat_on is a month and day, like 12-03"
}
