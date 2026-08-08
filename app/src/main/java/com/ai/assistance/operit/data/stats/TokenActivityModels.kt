package com.ai.assistance.operit.data.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

enum class TokenActivityViewMode { DAILY, WEEKLY, CUMULATIVE }

data class TokenActivityRecord(
    val startedAtMs: Long,
    val tokens: Long,
)

data class TokenActivityEventRow(
    val startedAtMs: Long,
    val uncachedInputTokens: Long?,
    val cachedInputTokens: Long?,
    val cacheWriteTokens: Long?,
    val totalInputTokens: Long?,
    val outputTokens: Long?,
    val reasoningTokens: Long?,
    val reasoningIncludedInOutput: Boolean?,
)

data class TokenActivityDay(
    val date: LocalDate,
    val tokens: Long,
    val level: Int,
)

data class TokenActivityWeek(
    val startDate: LocalDate,
    val tokens: Long,
    val level: Int,
    val barHeight: Int,
)

data class TokenActivityStats(
    val totalTokens: Long = 0L,
    val peakTokens: Long = 0L,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
)

data class TokenActivityInsights(
    val totalRequests: Long = 0L,
    val topHours: List<Int> = emptyList(),
)

data class TokenActivityYearData(
    val daily: List<TokenActivityDay>,
    val weekly: List<TokenActivityWeek>,
    val cumulative: List<TokenActivityDay>,
    val stats: TokenActivityStats,
)

internal fun TokenActivityEventRow.toActivityRecord(): TokenActivityRecord {
    val input = totalInputTokens ?: listOf(uncachedInputTokens, cachedInputTokens, cacheWriteTokens)
        .fold(0L) { total, value ->
            if (value != null && value > 0L) saturatedAdd(total, value) else total
        }
    var total = input.coerceAtLeast(0L)
    outputTokens?.takeIf { it > 0L }?.let { total = saturatedAdd(total, it) }
    if (reasoningIncludedInOutput == false) {
        reasoningTokens?.takeIf { it > 0L }?.let { total = saturatedAdd(total, it) }
    }
    return TokenActivityRecord(startedAtMs = startedAtMs, tokens = total)
}

object TokenActivityAggregator {
    fun availableYears(
        records: List<TokenActivityRecord>,
        zone: ZoneId,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Int> {
        val currentYear = Instant.ofEpochMilli(nowMs).atZone(zone).year
        val firstYear = records.minOfOrNull { Instant.ofEpochMilli(it.startedAtMs).atZone(zone).year }
            ?.coerceAtMost(currentYear) ?: currentYear
        return (firstYear..currentYear).toList().reversed()
    }

    fun insights(records: List<TokenActivityRecord>, zone: ZoneId): TokenActivityInsights {
        val hourCounts = LongArray(24)
        records.forEach { record ->
            val hour = Instant.ofEpochMilli(record.startedAtMs).atZone(zone).hour
            hourCounts[hour]++
        }
        return TokenActivityInsights(
            totalRequests = records.size.toLong(),
            topHours = hourCounts.indices
                .filter { hourCounts[it] > 0L }
                .sortedWith(compareByDescending<Int> { hourCounts[it] }.thenBy { it })
                .take(3),
        )
    }

    fun yearData(
        records: List<TokenActivityRecord>,
        zone: ZoneId,
        year: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): TokenActivityYearData {
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val start = LocalDate.of(year, 1, 1)
        val end = if (year == nowDate.year) nowDate else LocalDate.of(year, 12, 31)
        return rangeData(records, zone, start, end)
    }

    /** 默认活动窗口：包含今天在内的最近 365 个自然日。 */
    fun recentData(
        records: List<TokenActivityRecord>,
        zone: ZoneId,
        nowMs: Long = System.currentTimeMillis(),
    ): TokenActivityYearData {
        val end = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return rangeData(records, zone, end.minusDays(364), end)
    }

    private fun rangeData(
        records: List<TokenActivityRecord>,
        zone: ZoneId,
        start: LocalDate,
        end: LocalDate,
    ): TokenActivityYearData {
        val dayTotals = HashMap<LocalDate, Long>()
        records.forEach { record ->
            val date = Instant.ofEpochMilli(record.startedAtMs).atZone(zone).toLocalDate()
            if (!date.isBefore(start) && !date.isAfter(end)) {
                dayTotals[date] = saturatedAdd(dayTotals[date] ?: 0L, record.tokens)
            }
        }

        val days = ChronoUnit.DAYS.between(start, end).toInt() + 1
        val raw = List(days) { index ->
            val date = start.plusDays(index.toLong())
            TokenActivityDay(date, dayTotals[date] ?: 0L, 0)
        }
        val dailyLevels = QuantileLevels.from(raw.map { it.tokens })
        val daily = raw.map { it.copy(level = dailyLevels.level(it.tokens)) }

        var cumulativeTotal = 0L
        val cumulativeRaw = raw.map {
            cumulativeTotal = saturatedAdd(cumulativeTotal, it.tokens)
            it.copy(tokens = cumulativeTotal)
        }
        val cumulativeLevels = QuantileLevels.from(cumulativeRaw.map { it.tokens })
        val cumulative = cumulativeRaw.map { it.copy(level = cumulativeLevels.level(it.tokens)) }

        val firstWeek = start.minusDays((start.dayOfWeek.value % 7).toLong())
        val lastWeek = end.minusDays((end.dayOfWeek.value % 7).toLong())
        val weekCount = ChronoUnit.WEEKS.between(firstWeek, lastWeek).toInt() + 1
        val weekTotals = LongArray(weekCount)
        raw.forEach { day ->
            val index = ChronoUnit.WEEKS.between(firstWeek, day.date.minusDays((day.date.dayOfWeek.value % 7).toLong())).toInt()
            weekTotals[index] = saturatedAdd(weekTotals[index], day.tokens)
        }
        val weekLevels = QuantileLevels.from(weekTotals.toList())
        val heights = barHeights(weekTotals.toList())
        val weekly = List(weekCount) { index ->
            TokenActivityWeek(
                startDate = firstWeek.plusWeeks(index.toLong()),
                tokens = weekTotals[index],
                level = weekLevels.level(weekTotals[index]),
                barHeight = heights[index],
            )
        }

        return TokenActivityYearData(
            daily = daily,
            weekly = weekly,
            cumulative = cumulative,
            stats = stats(raw),
        )
    }

    private fun stats(days: List<TokenActivityDay>): TokenActivityStats {
        var total = 0L
        var peak = 0L
        var run = 0
        var longest = 0
        days.forEach { day ->
            total = saturatedAdd(total, day.tokens)
            peak = maxOf(peak, day.tokens)
            run = if (day.tokens > 0L) run + 1 else 0
            longest = maxOf(longest, run)
        }
        var index = days.lastIndex
        while (index >= 0 && days[index].tokens == 0L) index--
        var current = 0
        while (index >= 0 && days[index].tokens > 0L) {
            current++
            index--
        }
        return TokenActivityStats(total, peak, current, longest)
    }

    private fun barHeights(values: List<Long>): IntArray {
        val distinct = values.filter { it > 0L }.distinct().sorted()
        return IntArray(values.size) { index ->
            when {
                values[index] <= 0L -> 1
                distinct.size == 1 -> 7
                else -> 2 + distinct.indexOf(values[index]) * 5 / (distinct.size - 1)
            }
        }
    }
}

private class QuantileLevels(private val thresholds: LongArray) {
    fun level(value: Long): Int {
        if (value <= 0L) return 0
        for (level in 1..5) if (value <= thresholds[level]) return level
        return 5
    }

    companion object {
        fun from(values: List<Long>): QuantileLevels {
            val nonZero = values.filter { it > 0L }.sorted()
            if (nonZero.size < 2 || nonZero.firstOrNull() == nonZero.lastOrNull()) {
                return QuantileLevels(LongArray(6).also { it[3] = Long.MAX_VALUE })
            }
            fun nearest(percentile: Double): Long {
                val index = (ceil(nonZero.size * percentile).toInt() - 1).coerceIn(0, nonZero.lastIndex)
                return nonZero[index]
            }
            return QuantileLevels(
                longArrayOf(0L, nearest(0.25), nearest(0.50), nearest(0.75), nearest(0.95), Long.MAX_VALUE)
            )
        }
    }
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
