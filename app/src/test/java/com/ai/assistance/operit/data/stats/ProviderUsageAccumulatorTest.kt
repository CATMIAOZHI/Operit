package com.ai.assistance.operit.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The arithmetic the pre-classification page uses to state what one classification call cost. It has
 * to be the ledger's own: a row that disagreed with the usage statistics page would be worse than no
 * row at all.
 */
class ProviderUsageAccumulatorTest {
    private fun usage(
        uncachedInput: Long? = null,
        cachedInput: Long? = null,
        totalInput: Long? = null,
        output: Long? = null,
        completeSnapshot: Boolean = true,
    ) = ProviderUsageSnapshot(
        uncachedInputTokens = uncachedInput,
        cachedInputTokens = cachedInput,
        totalInputTokens = totalInput,
        outputTokens = output,
        cacheWriteSeparateBilling = false,
        completeSnapshot = completeSnapshot,
        source = "test",
    )

    @Test
    fun nothingReportedStaysUnknownRatherThanZero() {
        val accumulator = ProviderUsageAccumulator()

        assertNull(accumulator.aggregatedUsage())
        assertEquals(0, accumulator.attemptCount)
        assertEquals(0, accumulator.reportCount)
        assertNull(accumulator.lastUsage)
    }

    @Test
    fun aPartialUpdateKeepsTheFieldsItOmits() {
        val accumulator = ProviderUsageAccumulator()
        accumulator.onUsage(usage(uncachedInput = 100L, cachedInput = 20L, totalInput = 120L))
        accumulator.onUsage(
            usage(totalInput = 120L, output = 30L, completeSnapshot = false),
        )

        val aggregated = accumulator.aggregatedUsage()!!
        assertEquals(100L, aggregated.uncachedInputTokens)
        assertEquals(20L, aggregated.cachedInputTokens)
        assertEquals(30L, aggregated.outputTokens)
        assertEquals(120L, aggregated.totalInputTokens)
        assertEquals(2, accumulator.reportCount)
    }

    @Test
    fun aCompleteSnapshotWithdrawsWhatItDoesNotCarry() {
        val accumulator = ProviderUsageAccumulator()
        accumulator.onUsage(
            usage(uncachedInput = 100L, cachedInput = 20L, output = 30L, completeSnapshot = false)
        )
        accumulator.onUsage(usage(uncachedInput = 100L))

        val aggregated = accumulator.aggregatedUsage()!!
        assertEquals(100L, aggregated.uncachedInputTokens)
        assertNull(aggregated.cachedInputTokens)
        assertNull(aggregated.outputTokens)
    }

    @Test
    fun aGapInTheAttemptsLeavesTheComponentsUnknown() {
        val accumulator = ProviderUsageAccumulator()
        accumulator.onUsage(usage(uncachedInput = 200L, output = 20L), attempt = 2)

        val aggregated = accumulator.aggregatedUsage()!!
        assertEquals(2, accumulator.attemptCount)
        assertNull(aggregated.uncachedInputTokens)
        assertNull(aggregated.outputTokens)
    }

    @Test
    fun contiguousAttemptsAreSummed() {
        val accumulator = ProviderUsageAccumulator()
        accumulator.onUsage(usage(uncachedInput = 100L, output = 10L), attempt = 1)
        accumulator.onUsage(usage(uncachedInput = 200L, output = 20L), attempt = 2)
        accumulator.onUsage(usage(uncachedInput = 250L, output = 25L), attempt = 2)

        val aggregated = accumulator.aggregatedUsage()!!
        assertEquals(350L, aggregated.uncachedInputTokens)
        assertEquals(35L, aggregated.outputTokens)
    }

    @Test
    fun aNegativeComponentIsRejectedAsUnknown() {
        val accumulator = ProviderUsageAccumulator()
        accumulator.onUsage(usage(uncachedInput = -5L, cachedInput = 10L, output = 1L))

        val aggregated = accumulator.aggregatedUsage()!!
        assertNull(aggregated.uncachedInputTokens)
        assertEquals(10L, aggregated.cachedInputTokens)
        assertEquals(1L, aggregated.outputTokens)
    }
}
