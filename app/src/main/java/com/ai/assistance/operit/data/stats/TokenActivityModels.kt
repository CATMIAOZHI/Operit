package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.model.TokenStatEventEntity
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
    /** null = 旧行未声明，按保守默认 true（独立计费）处理。 */
    val cacheWriteSeparateBilling: Boolean? = null,
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

/**
 * 逐事件 canonical token 总量推导（聚合器与活动热力图共用同一纯 helper）。
 *
 * - 输入：权威 [totalInputTokens]（provider 明确上报的总输入，含缓存命中/写入）
 *   已知则直接使用；未知时按 [cacheWriteSeparateBilling] 决定 fallback：
 *   true（Anthropic：缓存写入独立计费，输入总量 = uncached + cached + cacheWrite，
 *   漏加即漏算）；false（OpenAI/Gemini/本地/ToolPkg：写入成本已包含在输入单价内，
 *   输入总量 = uncached + cached，再加 cacheWrite 即重复）。null（旧行未声明）
 *   按 true 保守默认，与费用重估（[TokenCostCalculator]）同一边界。
 * - 输出：outputTokens +（[reasoningIncludedInOutput] == false 时的 reasoningTokens）；
 *   推理已包含在输出（true/null）时不再加，避免双重计数。
 * - 任一所必需分量未知（null）→ 整体 unknown（返回 null），绝不把 null 当作 0；
 *   使用饱和加法（[TokenCostCalculator.saturatedAdd]），Long 溢出钳制不回绕。
 * - 旧 baseline 无上述细分字段，只能按 input + output 合计（见
 *   [com.ai.assistance.operit.ui.features.tokenstats.knownBaselineTokenSum]）。
 */
internal fun canonicalTotalTokens(
    totalInputTokens: Long?,
    uncachedInputTokens: Long?,
    cachedInputTokens: Long?,
    cacheWriteTokens: Long?,
    cacheWriteSeparateBilling: Boolean?,
    outputTokens: Long?,
    reasoningTokens: Long?,
    reasoningIncludedInOutput: Boolean?,
): Long? {
    val input =
        totalInputTokens ?: run {
            val uncached = uncachedInputTokens ?: return null
            val cached = cachedInputTokens ?: return null
            val sum = TokenCostCalculator.saturatedAdd(uncached, cached)
            if (cacheWriteSeparateBilling ?: true) {
                val cacheWrite = cacheWriteTokens ?: return null
                TokenCostCalculator.saturatedAdd(sum, cacheWrite)
            } else {
                sum
            }
        }
    val output = outputTokens ?: return null
    val billedOutput =
        if (reasoningIncludedInOutput == false) {
            val reasoning = reasoningTokens ?: return null
            TokenCostCalculator.saturatedAdd(output, reasoning)
        } else {
            output
        }
    return TokenCostCalculator.saturatedAdd(input, billedOutput)
}

/** [canonicalTotalTokens] 的事件实体重载（聚合器按事件列表逐条推导）。 */
internal fun canonicalTotalTokens(event: TokenStatEventEntity): Long? =
    canonicalTotalTokens(
        totalInputTokens = event.totalInputTokens,
        uncachedInputTokens = event.uncachedInputTokens,
        cachedInputTokens = event.cachedInputTokens,
        cacheWriteTokens = event.cacheWriteTokens,
        cacheWriteSeparateBilling = event.cacheWriteSeparateBilling,
        outputTokens = event.outputTokens,
        reasoningTokens = event.reasoningTokens,
        reasoningIncludedInOutput = event.reasoningIncludedInOutput,
    )

internal fun TokenActivityEventRow.toActivityRecord(): TokenActivityRecord {
    // 复用与聚合器相同的 canonical 推导；活动热力图只展示“已知 token 活动”，
    // canonical 未知（必需分量缺失）的事件按 0 计，不假装精确——请求计数
    // 仍然准确（记录不因 0 被丢弃），未知明细由统计页的 unknown 计数表达。
    val tokens =
        canonicalTotalTokens(
            totalInputTokens = totalInputTokens,
            uncachedInputTokens = uncachedInputTokens,
            cachedInputTokens = cachedInputTokens,
            cacheWriteTokens = cacheWriteTokens,
            cacheWriteSeparateBilling = cacheWriteSeparateBilling,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
            reasoningIncludedInOutput = reasoningIncludedInOutput,
        ) ?: 0L
    return TokenActivityRecord(startedAtMs = startedAtMs, tokens = tokens)
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
        // currentStreak 只看 days **尾部**：从最后一天起连续正值；尾日 0 则 0
        // （绝不跳过尾部零日——今天无活动就是断更，不能用更早的活跃日续算）。
        var current = 0
        var index = days.lastIndex
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
