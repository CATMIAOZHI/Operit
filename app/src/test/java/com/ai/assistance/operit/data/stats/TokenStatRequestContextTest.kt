package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenStatRequestContextTest {
    private fun context(eventId: String = "evt-context") =
        TokenStatRequestContext(
            eventId = eventId,
            category = TokenStatCategory.CHAT,
            configId = "cfg",
            provider = "TEST",
            model = "model",
            startedAtMs = 1L,
        )

    private fun usage(input: Long, output: Long) =
        ProviderUsageSnapshot(
            uncachedInputTokens = input,
            cachedInputTokens = 0L,
            cacheWriteTokens = 0L,
            totalInputTokens = input,
            outputTokens = output,
            source = "test",
        )

    @Test
    fun `missing earlier attempt makes every usage component unknown`() {
        val request = context()
        request.onUsage(usage(input = 200L, output = 20L), attempt = 2)

        val aggregated = request.aggregatedUsage()!!
        assertEquals(2, request.attemptCount)
        assertNull(aggregated.uncachedInputTokens)
        assertNull(aggregated.cachedInputTokens)
        assertNull(aggregated.cacheWriteTokens)
        assertNull(aggregated.totalInputTokens)
        assertNull(aggregated.outputTokens)
        assertNull(aggregated.reasoningTokens)
    }

    @Test
    fun `contiguous attempts sum and duplicate snapshot semantics stay unchanged`() {
        val request = context()
        request.onUsage(usage(input = 100L, output = 10L), attempt = 1)
        request.onUsage(usage(input = 200L, output = 20L), attempt = 2)
        request.onUsage(usage(input = 250L, output = 25L), attempt = 2)

        val aggregated = request.aggregatedUsage()!!
        assertEquals(350L, aggregated.uncachedInputTokens)
        assertEquals(35L, aggregated.outputTokens)
    }

    @Test
    fun `spool replay preserves already aggregated usage when attempt count exceeds one`() {
        val request = context("evt-replay")
        request.onUsage(usage(input = 100L, output = 10L), attempt = 1)
        request.onUsage(usage(input = 200L, output = 20L), attempt = 2)
        request.finish(TokenStatStatus.COMPLETED, 2L)
        val pricing =
            ResolvedPricing(
                billingMode = BillingMode.TOKEN,
                currency = PricingCurrency.USD,
                inputPricePerMillion = 1.0,
                cachedInputPricePerMillion = 1.0,
                outputPricePerMillion = 1.0,
                source = PricingSource.DEFAULT,
                known = true,
            )

        val replay = TokenStatRequestContext.fromSpoolLine(request.toSpoolLine(pricing, 0.00033))
        val aggregated = replay.aggregatedUsage()!!
        assertEquals(2, replay.attemptCount)
        assertEquals(300L, aggregated.uncachedInputTokens)
        assertEquals(30L, aggregated.outputTokens)
    }
}
