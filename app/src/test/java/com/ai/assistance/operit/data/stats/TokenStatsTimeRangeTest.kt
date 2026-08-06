package com.ai.assistance.operit.data.stats

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间预设/范围/桶边界测试（阶段 3）：
 * 所有边界用 java.time 日历运算，覆盖滚动窗口、自然日、自然月、跨月、
 * DST 春令（23 小时日）与冬令（25 小时日/重复小时）、自定义范围校验、
 * [start, end) 半开语义、桶对齐与归属。
 */
class TokenStatsTimeRangeTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")

    private fun localMs(dateTime: String, zone: ZoneId): Long =
        LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

    private fun local(epochMs: Long, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), zone)

    // ==== 滚动窗口 ====

    @Test
    fun `rolling presets are now minus duration half-open`() {
        val now = localMs("2026-08-07T15:00:00", shanghai)
        val fiveHour = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_5H, now, shanghai)
        assertEquals(now - 5L * TokenStatsTimeRanges.HOUR_MS, fiveHour.startMs)
        assertEquals(now, fiveHour.endMs)

        val twelveHour = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_12H, now, shanghai)
        assertEquals(now - 12L * TokenStatsTimeRanges.HOUR_MS, twelveHour.startMs)

        val twentyFour = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_24H, now, shanghai)
        assertEquals(now - 24L * TokenStatsTimeRanges.HOUR_MS, twentyFour.startMs)
    }

    // ==== 自然日 ====

    @Test
    fun `today is local midnight to next midnight`() {
        val now = localMs("2026-08-07T15:00:00", shanghai)
        val today = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.TODAY, now, shanghai)
        assertEquals(localMs("2026-08-07T00:00:00", shanghai), today.startMs)
        assertEquals(localMs("2026-08-08T00:00:00", shanghai), today.endMs)
    }

    @Test
    fun `yesterday is previous natural day`() {
        val now = localMs("2026-08-07T02:00:00", shanghai)
        val yesterday = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.YESTERDAY, now, shanghai)
        assertEquals(localMs("2026-08-06T00:00:00", shanghai), yesterday.startMs)
        assertEquals(localMs("2026-08-07T00:00:00", shanghai), yesterday.endMs)
    }

    @Test
    fun `last 7 and 30 days are natural days including today`() {
        val now = localMs("2026-08-07T23:59:00", shanghai)
        val seven = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_7D, now, shanghai)
        assertEquals(localMs("2026-08-01T00:00:00", shanghai), seven.startMs)
        assertEquals(localMs("2026-08-08T00:00:00", shanghai), seven.endMs)
        assertEquals(7L * TokenStatsTimeRanges.DAY_MS, seven.durationMs)

        val thirty = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_30D, now, shanghai)
        assertEquals(localMs("2026-07-09T00:00:00", shanghai), thirty.startMs)
        assertEquals(localMs("2026-08-08T00:00:00", shanghai), thirty.endMs)
        assertEquals(30L * TokenStatsTimeRanges.DAY_MS, thirty.durationMs)
    }

    // ==== 自然月 ====

    @Test
    fun `this and last month use calendar month boundaries`() {
        val now = localMs("2026-08-07T15:00:00", shanghai)
        val thisMonth = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.THIS_MONTH, now, shanghai)
        assertEquals(localMs("2026-08-01T00:00:00", shanghai), thisMonth.startMs)
        assertEquals(localMs("2026-09-01T00:00:00", shanghai), thisMonth.endMs)

        val lastMonth = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_MONTH, now, shanghai)
        assertEquals(localMs("2026-07-01T00:00:00", shanghai), lastMonth.startMs)
        assertEquals(localMs("2026-08-01T00:00:00", shanghai), lastMonth.endMs)
    }

    @Test
    fun `february month boundaries handle 28 and leap 29 days`() {
        // 2026-03-01 时的上月 = 2026 年 2 月（28 天）
        val nowFeb = localMs("2026-03-01T01:00:00", shanghai)
        val feb = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_MONTH, nowFeb, shanghai)
        assertEquals(localMs("2026-02-01T00:00:00", shanghai), feb.startMs)
        assertEquals(localMs("2026-03-01T00:00:00", shanghai), feb.endMs)
        assertEquals(28L * TokenStatsTimeRanges.DAY_MS, feb.durationMs)

        // 2028-03-01 时的上月 = 2028 年 2 月（闰年 29 天）
        val leapNow = localMs("2028-03-01T01:00:00", shanghai)
        val leapFeb = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.LAST_MONTH, leapNow, shanghai)
        assertEquals(29L * TokenStatsTimeRanges.DAY_MS, leapFeb.durationMs)

        // 本月 = 3 月（31 天）
        val march = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.THIS_MONTH, nowFeb, shanghai)
        assertEquals(localMs("2026-03-01T00:00:00", shanghai), march.startMs)
        assertEquals(localMs("2026-04-01T00:00:00", shanghai), march.endMs)
        assertEquals(31L * TokenStatsTimeRanges.DAY_MS, march.durationMs)
    }

    // ==== 自定义与校验 ====

    @Test
    fun `custom range requires end after start`() {
        val range = TokenStatsTimeRanges.customRange(1000L, 2000L)
        assertEquals(1000L, range.startMs)
        assertEquals(2000L, range.endMs)
        try {
            TokenStatsTimeRanges.customRange(2000L, 2000L)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
        try {
            TokenStatsTimeRanges.rangeFor(TokenStatsPreset.CUSTOM, 1000L, shanghai)
            throw AssertionError("expected IllegalArgumentException for CUSTOM preset")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    // ==== DST ====

    @Test
    fun `spring forward day is 23 hours`() {
        // 美东 2026-03-08 02:00 -> 03:00 拨快 1 小时
        val now = localMs("2026-03-08T15:00:00", newYork)
        val today = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.TODAY, now, newYork)
        assertEquals(localMs("2026-03-08T00:00:00", newYork), today.startMs)
        assertEquals(localMs("2026-03-09T00:00:00", newYork), today.endMs)
        assertEquals(23L * TokenStatsTimeRanges.HOUR_MS, today.durationMs)

        val yesterday = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.YESTERDAY, now, newYork)
        assertEquals(24L * TokenStatsTimeRanges.HOUR_MS, yesterday.durationMs)
    }

    @Test
    fun `fall back day is 25 hours`() {
        // 美东 2026-11-01 02:00 EDT -> 01:00 EST 拨慢 1 小时
        val now = localMs("2026-11-01T15:00:00", newYork)
        val today = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.TODAY, now, newYork)
        assertEquals(localMs("2026-11-01T00:00:00", newYork), today.startMs)
        assertEquals(localMs("2026-11-02T00:00:00", newYork), today.endMs)
        assertEquals(25L * TokenStatsTimeRanges.HOUR_MS, today.durationMs)
    }

    @Test
    fun `month range across dst transition is exact calendar span`() {
        // 2026-03 月：包含 23 小时日的自然月
        val now = localMs("2026-03-15T12:00:00", newYork)
        val march = TokenStatsTimeRanges.rangeFor(TokenStatsPreset.THIS_MONTH, now, newYork)
        assertEquals(localMs("2026-03-01T00:00:00", newYork), march.startMs)
        assertEquals(localMs("2026-04-01T00:00:00", newYork), march.endMs)
        assertEquals(
            31L * TokenStatsTimeRanges.DAY_MS - TokenStatsTimeRanges.HOUR_MS,
            march.durationMs,
        )
    }

    // ==== 粒度选择 ====

    @Test
    fun `granularity is chosen by range duration`() {
        fun granularityOf(hours: Long) =
            TokenStatsTimeRanges.granularityFor(TokenStatsTimeRanges.customRange(0L, hours * TokenStatsTimeRanges.HOUR_MS))
        assertEquals(TokenStatsGranularity.TEN_MINUTES, granularityOf(5))
        assertEquals(TokenStatsGranularity.TEN_MINUTES, granularityOf(12))
        assertEquals(TokenStatsGranularity.HOURLY, granularityOf(13))
        assertEquals(TokenStatsGranularity.HOURLY, granularityOf(24))
        assertEquals(TokenStatsGranularity.HOURLY, granularityOf(48))
        assertEquals(TokenStatsGranularity.DAILY, granularityOf(49))
        assertEquals(TokenStatsGranularity.DAILY, granularityOf(7 * 24))
        assertEquals(TokenStatsGranularity.DAILY, granularityOf(31 * 24))
    }

    // ==== 桶对齐与归属 ====

    @Test
    fun `ten minute buckets align to local clock boundaries`() {
        val range = TokenStatsTimeRanges.customRange(
            localMs("2026-08-07T13:07:00", shanghai),
            localMs("2026-08-07T18:07:00", shanghai),
        )
        val starts = TokenStatsTimeRanges.bucketStarts(range, TokenStatsGranularity.TEN_MINUTES, shanghai)
        // 首个桶边界为本地 13:00（早于范围起点，属正常：桶是日历对齐的）
        assertEquals(localMs("2026-08-07T13:00:00", shanghai), starts.first())
        assertEquals(31, starts.size)
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a == TokenStatsTimeRanges.TEN_MINUTES_MS })
    }

    @Test
    fun `hourly buckets across spring forward skip the missing hour`() {
        val range = TokenStatsTimeRanges.customRange(
            localMs("2026-03-08T00:00:00", newYork),
            localMs("2026-03-09T00:00:00", newYork),
        )
        val starts = TokenStatsTimeRanges.bucketStarts(range, TokenStatsGranularity.HOURLY, newYork)
        assertEquals(23, starts.size)
        // 单调递增且没有 02:00 本地小时的桶
        assertTrue(starts.zipWithNext().all { (a, b) -> b > a })
        assertTrue(starts.none { local(it, newYork).hour == 2 })
        // 事件归属：01:30 EST -> 01:00 桶；03:30 EDT -> 03:00 桶
        val early = localMs("2026-03-08T01:30:00", newYork)
        val late = localMs("2026-03-08T03:30:00", newYork)
        val earlyIndex = TokenStatsTimeRanges.bucketIndexOf(early, starts, TokenStatsGranularity.HOURLY, newYork)!!
        val lateIndex = TokenStatsTimeRanges.bucketIndexOf(late, starts, TokenStatsGranularity.HOURLY, newYork)!!
        assertEquals(localMs("2026-03-08T01:00:00", newYork), starts[earlyIndex])
        assertEquals(localMs("2026-03-08T03:00:00", newYork), starts[lateIndex])
        // 02:00 不存在：03:00 桶紧跟在 01:00 桶之后（无空洞）
        assertEquals(earlyIndex + 1, lateIndex)
    }

    @Test
    fun `hourly buckets across fall back produce both repeated hour buckets`() {
        val range = TokenStatsTimeRanges.customRange(
            localMs("2026-11-01T00:00:00", newYork),
            localMs("2026-11-02T00:00:00", newYork),
        )
        val starts = TokenStatsTimeRanges.bucketStarts(range, TokenStatsGranularity.HOURLY, newYork)
        assertEquals(25, starts.size)
        assertTrue(starts.zipWithNext().all { (a, b) -> b > a })
        // 重复的本地 01:00 出现两次：01:00 EDT 与 01:00 EST（不同 epoch）
        val hourOneBuckets = starts.filter { local(it, newYork).hour == 1 }
        assertEquals(2, hourOneBuckets.size)
        val first = localMs("2026-11-01T01:30:00", newYork) // 第一次 01:30（EDT）
        // 第二次 01:30 是 EST（epoch 多 1 小时）
        val secondEpoch = first + TokenStatsTimeRanges.HOUR_MS
        val firstIndex = TokenStatsTimeRanges.bucketIndexOf(first, starts, TokenStatsGranularity.HOURLY, newYork)!!
        val secondIndex = TokenStatsTimeRanges.bucketIndexOf(secondEpoch, starts, TokenStatsGranularity.HOURLY, newYork)!!
        assertEquals(hourOneBuckets[0], starts[firstIndex])
        assertEquals(hourOneBuckets[1], starts[secondIndex])
    }

    @Test
    fun `daily buckets across dst have exact 23 and 24 hour spans`() {
        val range = TokenStatsTimeRanges.customRange(
            localMs("2026-03-08T00:00:00", newYork),
            localMs("2026-03-10T00:00:00", newYork),
        )
        val starts = TokenStatsTimeRanges.bucketStarts(range, TokenStatsGranularity.DAILY, newYork)
        assertEquals(2, starts.size)
        assertEquals(localMs("2026-03-08T00:00:00", newYork), starts[0])
        assertEquals(localMs("2026-03-09T00:00:00", newYork), starts[1])
        assertEquals(23L * TokenStatsTimeRanges.HOUR_MS,
            TokenStatsTimeRanges.bucketEndMs(starts, 0, TokenStatsGranularity.DAILY, newYork) - starts[0])
        assertEquals(24L * TokenStatsTimeRanges.HOUR_MS,
            TokenStatsTimeRanges.bucketEndMs(starts, 1, TokenStatsGranularity.DAILY, newYork) - starts[1])
        // 23:30 EDT 属于 03-08 的桶
        val lateEvent = localMs("2026-03-08T23:30:00", newYork)
        assertEquals(0, TokenStatsTimeRanges.bucketIndexOf(lateEvent, starts, TokenStatsGranularity.DAILY, newYork))
    }

    @Test
    fun `bucket boundaries partition events exactly once`() {
        val range = TokenStatsTimeRanges.customRange(
            localMs("2026-08-07T00:00:00", shanghai),
            localMs("2026-08-09T00:00:00", shanghai),
        )
        val starts = TokenStatsTimeRanges.bucketStarts(range, TokenStatsGranularity.HOURLY, shanghai)
        // 逐小时采样：范围内每个整点恰好属于一个桶，桶序号随事件时间单调递增
        var previousIndex = -1
        for (hour in 0 until 48) {
            val ts = range.startMs + hour * TokenStatsTimeRanges.HOUR_MS
            val index = TokenStatsTimeRanges.bucketIndexOf(ts, starts, TokenStatsGranularity.HOURLY, shanghai)
            assertTrue("ts=$ts must belong to a bucket", index != null)
            assertTrue("bucket index must be monotonic", index!! >= previousIndex)
            previousIndex = index
        }
        // 范围终点本身不属于任何桶（半开语义）
        assertNull(
            TokenStatsTimeRanges.bucketIndexOf(range.endMs, starts, TokenStatsGranularity.HOURLY, shanghai)
        )
        // 范围起点之前的事件不属于任何桶
        assertNull(
            TokenStatsTimeRanges.bucketIndexOf(range.startMs - 1, starts, TokenStatsGranularity.HOURLY, shanghai)
        )
    }
}
