package com.ai.assistance.operit.ui.features.tokenstats

import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.stats.TokenStatsBaselineTotals
import com.ai.assistance.operit.data.stats.TokenStatsCostMode
import com.ai.assistance.operit.data.stats.TokenStatsCostSummary
import com.ai.assistance.operit.data.stats.TokenStatsLifetimeOverview
import com.ai.assistance.operit.data.stats.TokenStatsTokenAggregate
import com.ai.assistance.operit.data.stats.TokenStatsTotals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UI 总 Token 展示口径测试（阶段 3/4 修复）：
 * - headline 采用聚合器 canonical 总 token（不再从原始分量重组）；
 * - legacy baseline 只能按 input + output 合计（旧数据无细分字段）；
 * - includeLegacy 开关：关 = 只算事件 canonical；开 = 追加 baseline input+output。
 */
class TokenStatsCanonicalTotalsTest {

    private fun aggregate(sum: Long, known: Long, unknown: Long, total: Long) =
        TokenStatsTokenAggregate(
            knownSum = sum,
            knownEventCount = known,
            unknownEventCount = unknown,
            totalEventCount = total,
        )

    private fun cost() =
        TokenStatsCostSummary(
            currency = PricingCurrency.CNY,
            knownAmount = 0.0,
            unknownContributionCount = 0L,
            totalContributionCount = 0L,
            rateUsed = 7.0,
            rateIsEstimated = true,
            mode = TokenStatsCostMode.HISTORICAL,
            originalCurrencyAmounts = emptyMap(),
        )

    private fun totals(totalTokens: TokenStatsTokenAggregate): TokenStatsTotals =
        TokenStatsTotals(
            requests = totalTokens.totalEventCount,
            uncachedInput = aggregate(0L, 0L, 0L, totalTokens.totalEventCount),
            cachedInput = aggregate(0L, 0L, 0L, totalTokens.totalEventCount),
            cacheWrite = aggregate(0L, 0L, 0L, totalTokens.totalEventCount),
            totalInput = aggregate(0L, 0L, 0L, totalTokens.totalEventCount),
            output = aggregate(0L, 0L, 0L, totalTokens.totalEventCount),
            reasoning = aggregate(0L, 0L, 0L, totalTokens.totalEventCount),
            totalTokens = totalTokens,
            cost = cost(),
        )

    private fun baselineTotals(input: Long, output: Long): TokenStatsBaselineTotals =
        TokenStatsBaselineTotals(
            identityCount = 1L,
            requests = 1L,
            inputTokens = input,
            cachedInputTokens = 0L,
            outputTokens = output,
            cost = cost(),
            anyEstimated = true,
        )

    @Test
    fun `lifetime headline uses canonical event total and baseline input plus output`() {
        val overview =
            TokenStatsLifetimeOverview(
                eventTotals = totals(aggregate(sum = 3_170L, known = 4L, unknown = 1L, total = 5L)),
                baselineTotals = baselineTotals(input = 1_000L, output = 500L),
                combinedRequests = 6L,
            )
        // includeLegacy 开：事件 canonical（3_170）+ baseline（1_000+500）
        assertEquals(3_170L + 1_500L, knownLifetimeTokenSum(overview, includeLegacy = true))
        // includeLegacy 关：只算事件 canonical
        assertEquals(3_170L, knownLifetimeTokenSum(overview, includeLegacy = false))
    }

    @Test
    fun `baseline legacy totals are input plus output`() {
        assertEquals(1_500L, knownBaselineTokenSum(baselineTotals(input = 1_000L, output = 500L)))
        assertEquals(0L, knownBaselineTokenSum(baselineTotals(input = 0L, output = 0L)))
    }
}
