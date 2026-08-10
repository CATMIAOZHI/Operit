package com.ai.assistance.operit.data.stats

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenActivityAggregatorTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `activity row honors total input and reasoning inclusion contract`() {
        val included = TokenActivityEventRow(
            startedAtMs = 1L,
            uncachedInputTokens = null,
            cachedInputTokens = null,
            cacheWriteTokens = null,
            totalInputTokens = 100L,
            outputTokens = 40L,
            reasoningTokens = 30L,
            reasoningIncludedInOutput = true,
        )
        val separate = TokenActivityEventRow(
            startedAtMs = 2L,
            uncachedInputTokens = 50L,
            cachedInputTokens = 20L,
            cacheWriteTokens = 10L,
            totalInputTokens = null,
            outputTokens = 40L,
            reasoningTokens = 30L,
            reasoningIncludedInOutput = false,
        )
        // OpenAI 非独立计费：cacheWrite 已含在输入内，不得重复计入
        val openai = TokenActivityEventRow(
            startedAtMs = 3L,
            uncachedInputTokens = 50L,
            cachedInputTokens = 20L,
            cacheWriteTokens = 10L,
            totalInputTokens = null,
            outputTokens = 40L,
            reasoningTokens = null,
            reasoningIncludedInOutput = true,
            cacheWriteSeparateBilling = false,
        )

        assertEquals(140L, included.toActivityRecord().tokens)
        assertEquals(150L, separate.toActivityRecord().tokens)
        assertEquals(110L, openai.toActivityRecord().tokens) // (50+20)+40，不含 cacheWrite
    }

    @Test
    fun `activity row with unknown canonical total maps to zero tokens`() {
        // 输入未知（无权威 totalInput 且拆分缺失）→ canonical 总 token 未知，
        // 热力图按 0 展示（不把 null 当 0），请求计数仍由记录本身承载
        val unknownInput = TokenActivityEventRow(
            startedAtMs = 1L,
            uncachedInputTokens = null,
            cachedInputTokens = null,
            cacheWriteTokens = null,
            totalInputTokens = null,
            outputTokens = 40L,
            reasoningTokens = null,
            reasoningIncludedInOutput = true,
        )
        // 独立计费下 cacheWrite 未知 → 输入 unknown
        val unknownCacheWrite = TokenActivityEventRow(
            startedAtMs = 2L,
            uncachedInputTokens = 50L,
            cachedInputTokens = 20L,
            cacheWriteTokens = null,
            totalInputTokens = null,
            outputTokens = 40L,
            reasoningTokens = null,
            reasoningIncludedInOutput = true,
            cacheWriteSeparateBilling = true,
        )
        assertEquals(0L, unknownInput.toActivityRecord().tokens)
        assertEquals(0L, unknownCacheWrite.toActivityRecord().tokens)
    }

    @Test
    fun `recent data contains exactly the latest 365 calendar days`() {
        val today = LocalDate.of(2026, 8, 8)
        val records = listOf(
            record(today.minusDays(364).toString(), 10),
            record(today.toString(), 20),
            record(today.minusDays(365).toString(), 40),
        )

        val result = TokenActivityAggregator.recentData(
            records = records,
            zone = zone,
            nowMs = today.atTime(18, 0).atZone(zone).toInstant().toEpochMilli(),
        )

        assertEquals(365, result.daily.size)
        assertEquals(today.minusDays(364), result.daily.first().date)
        assertEquals(today, result.daily.last().date)
        assertEquals(30L, result.stats.totalTokens)
    }

    @Test
    fun `year data computes totals peaks and streaks`() {
        val records = listOf(
            record("2026-01-01", 10),
            record("2026-01-02", 20),
            record("2026-01-04", 30),
            record("2026-01-05", 40),
        )

        val result = TokenActivityAggregator.yearData(
            records = records,
            zone = zone,
            year = 2026,
            // 尾日 01-05 有活动：currentStreak 从尾部连续正值（01-05/01-04）
            nowMs = LocalDate.of(2026, 1, 5).atTime(18, 0).atZone(zone).toInstant().toEpochMilli(),
        )

        assertEquals(100L, result.stats.totalTokens)
        assertEquals(40L, result.stats.peakTokens)
        assertEquals(2, result.stats.currentStreak)
        assertEquals(2, result.stats.longestStreak)
        assertEquals(100L, result.cumulative.last().tokens)
    }

    @Test
    fun `current streak counts only consecutive positive days at the tail`() {
        // 尾部连续正值只有 01-04/01-05 两天；01-03 的 0 断开更早的 01-01/01-02
        val records = listOf(
            record("2026-01-01", 10),
            record("2026-01-02", 20),
            record("2026-01-03", 0),
            record("2026-01-04", 30),
            record("2026-01-05", 40),
        )

        val result = TokenActivityAggregator.yearData(
            records = records,
            zone = zone,
            year = 2026,
            nowMs = LocalDate.of(2026, 1, 5).atTime(18, 0).atZone(zone).toInstant().toEpochMilli(),
        )

        assertEquals(2, result.stats.currentStreak)
        assertEquals(2, result.stats.longestStreak)
    }

    @Test
    fun `current streak is zero when the tail day has no activity`() {
        // 尾日 01-05 无活动（01-03/01-04 同样为空）→ currentStreak 必须为 0
        val records = listOf(
            record("2026-01-01", 10),
            record("2026-01-02", 20),
        )

        val result = TokenActivityAggregator.yearData(
            records = records,
            zone = zone,
            year = 2026,
            nowMs = LocalDate.of(2026, 1, 5).atTime(18, 0).atZone(zone).toInstant().toEpochMilli(),
        )

        assertEquals(0, result.stats.currentStreak)
        // longest 只看全区间最长连续段，不受尾部影响
        assertEquals(2, result.stats.longestStreak)
    }

    @Test
    fun `longest streak is independent of the tail and reflects the longest run anywhere`() {
        // 中部连续 3 天（01-01..01-03），尾部仅 01-05 一天（01-04 为 0 断开）
        val records = listOf(
            record("2026-01-01", 10),
            record("2026-01-02", 10),
            record("2026-01-03", 10),
            record("2026-01-05", 20),
        )

        val result = TokenActivityAggregator.yearData(
            records = records,
            zone = zone,
            year = 2026,
            nowMs = LocalDate.of(2026, 1, 5).atTime(18, 0).atZone(zone).toInstant().toEpochMilli(),
        )

        assertEquals(1, result.stats.currentStreak)
        assertEquals(3, result.stats.longestStreak)
    }

    @Test
    fun `insights use all requests and rank peak hours`() {
        val records = listOf(
            record("2026-01-01", 1, 9),
            record("2026-01-02", 1, 9),
            record("2026-01-03", 1, 20),
            record("2026-01-04", 1, 8),
        )

        val insights = TokenActivityAggregator.insights(records, zone)

        assertEquals(4L, insights.totalRequests)
        assertEquals(listOf(9, 8, 20), insights.topHours)
    }

    @Test
    fun `available years span earliest event through current year`() {
        val records = listOf(record("2024-06-01", 1), record("2026-01-01", 1))
        val now = LocalDate.of(2026, 8, 1).atStartOfDay(zone).toInstant().toEpochMilli()

        assertEquals(listOf(2026, 2025, 2024), TokenActivityAggregator.availableYears(records, zone, now))
    }

    private fun record(date: String, tokens: Long, hour: Int = 12): TokenActivityRecord {
        val timestamp = LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        return TokenActivityRecord(timestamp, tokens)
    }
}
