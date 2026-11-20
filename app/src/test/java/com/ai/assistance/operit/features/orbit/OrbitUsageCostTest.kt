package com.ai.assistance.operit.features.orbit

import com.ai.assistance.operit.data.collects.PricingCurrency
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitUsageCostTest {
    private val me = "orbit-a"
    private val day1 = LocalDate.parse("2026-11-18")
    private val day2 = LocalDate.parse("2026-11-19")
    private val cny = OrbitUsageCost.Money(PricingCurrency.CNY, 7.0, rateEstimated = true)

    private fun row(day: String, orbit: String = me, purpose: OrbitQuota.Purpose = OrbitQuota.Purpose.CHAT,
        input: Long, output: Long, calls: Long, cached: Long = 0, costCny: Double = 0.0, costUsd: Double = 0.0,
        priced: Long = 0, tracked: Long = 0, trackedTokens: Long = 0) =
        OrbitQuota.Usage(day, orbit, purpose, input, output, calls, cached, costCny, costUsd, priced, tracked, trackedTokens)

    @Test
    fun knownCostsAddUpAndConvertAtTheRate() {
        val data = OrbitQuota.UsageData(
            rows = listOf(
                row("2026-11-19", input = 900, output = 100, calls = 3, cached = 600, costCny = 1.0, costUsd = 0.5,
                    priced = 2, tracked = 3, trackedTokens = 1000),
                row("2026-11-19", orbit = "orbit-b", input = 100, output = 0, calls = 1, costCny = 0.25,
                    priced = 1, tracked = 1, trackedTokens = 100),
            ),
            hours = emptyList(),
            models = listOf(
                OrbitQuota.ModelUsage("2026-11-19", me, "flash", 900, 100, 3, 600, 1.0, 0.5, 2, cacheKnownInput = 800),
                // Its provider never said what came from cache: left out of the hit rate.
                OrbitQuota.ModelUsage("2026-11-19", "orbit-b", "flash", 100, 0, 1, 0, 0.25, 0.0, 1),
            ),
        )
        val view = OrbitUsageCost.view(data, day2, day2, me, emptyMap(), cny)
        // 1.0 + 0.5 USD at 7 + 0.25 from the other Orbit.
        assertEquals(4.75, view.total, 1e-9)
        assertEquals(0.0, view.estimated, 1e-9)
        assertEquals(3L, view.pricedCalls)
        assertEquals(1L, view.unpricedCalls)
        assertTrue(view.converted)
        assertEquals(4.5, view.byLine.getValue("CHAT"), 1e-9)
        assertEquals(0.25, view.byLine.getValue("others"), 1e-9)
        assertEquals(0.75, view.cacheRate!!, 1e-9)
        assertEquals(4L, view.calls)
        assertEquals(3L, view.coveredCalls)
        assertEquals(1, view.models.size)
        assertEquals(1L, view.models.single().unpricedCalls)
    }

    @Test
    fun olderUseIsEstimatedAtTheDayPriceOrLeftOutWithoutOne() {
        val data = OrbitQuota.UsageData(
            rows = listOf(
                // Partly older: 2,000 of its 3,000 tokens and 2 of its 3 calls have no cost.
                row("2026-11-18", input = 2500, output = 500, calls = 3, costCny = 0.1, priced = 1, tracked = 1,
                    trackedTokens = 1000),
                row("2026-11-19", purpose = OrbitQuota.Purpose.TASK, input = 400, output = 0, calls = 4),
            ),
            hours = emptyList(),
        )
        val prices = mapOf("2026-11-18" to OrbitUsageCost.DayPrice(cnyPerToken = 0.0001, usdPerToken = 0.0))
        val view = OrbitUsageCost.view(data, day1, day2, me, prices, cny)
        assertEquals(0.2, view.estimated, 1e-9)
        assertEquals(0.3, view.total, 1e-9)
        assertEquals(2L, view.estimatedCalls)
        assertEquals(4L, view.unknownCalls)
        assertEquals(setOf("CHAT"), view.estimatedLines)
        assertEquals(setOf("CHAT"), view.coveredLines)
        assertEquals(0.0, view.byLine.getValue("TASK"), 1e-9)
        assertNull(view.cacheRate)
    }

    @Test
    fun callsWithoutAPriceThenArePricedAtTodaysPrice() {
        val data = OrbitQuota.UsageData(
            rows = listOf(
                row("2026-11-19", input = 1_000_000, output = 500_000, calls = 2, tracked = 2, trackedTokens = 1_500_000),
                // Half its calls were priced; the other half share the row's tokens by count.
                row("2026-11-19", purpose = OrbitQuota.Purpose.TASK, input = 1_000_000, output = 0, calls = 4,
                    costCny = 0.5, priced = 2, tracked = 4, trackedTokens = 1_000_000),
                row("2026-11-19", orbit = "orbit-b", input = 100, output = 0, calls = 1, tracked = 1, trackedTokens = 100),
            ),
            hours = emptyList(),
            models = listOf(
                OrbitQuota.ModelUsage("2026-11-19", me, "flash", 1_000_000, 500_000, 2, 400_000, 0.0, 0.0, 0,
                    cacheKnownInput = 1_000_000),
                OrbitQuota.ModelUsage("2026-11-19", me, "pro", 1_000_000, 0, 4, 0, 0.5, 0.0, 2),
                // No price for it today either.
                OrbitQuota.ModelUsage("2026-11-19", "orbit-b", "mystery", 100, 0, 1, 0, 0.0, 0.0, 0),
            ),
        )
        val today = com.ai.assistance.operit.data.stats.ResolvedPricing(
            billingMode = com.ai.assistance.operit.data.model.BillingMode.TOKEN, currency = PricingCurrency.CNY,
            inputPricePerMillion = 1.0, cachedInputPricePerMillion = 0.1, outputPricePerMillion = 2.0,
            source = com.ai.assistance.operit.data.stats.PricingSource.DEFAULT, known = true,
        )
        val price = OrbitUsageCost.ModelPrice(listOf { _: Long? -> today })
        val view = OrbitUsageCost.view(data, day2, day2, me, emptyMap(), cny,
            modelPrices = mapOf("flash" to price, "pro" to price))
        // flash: 600k uncached + 400k cached + 500k output = 0.6 + 0.04 + 1.0; pro: 1.0 for 4 calls, 2 unpriced.
        assertEquals(1.64 + 0.5, view.backfilled, 1e-9)
        assertEquals(4L, view.backfilledCalls)
        assertEquals(1L, view.unpricedCalls)
        assertEquals(0.5 + 1.64 + 0.5, view.total, 1e-9)
        assertTrue(view.approximate)
        // Each day's amount goes to that Orbit's lines by their calls without a price: 2 and 2 of 4.
        assertEquals((1.64 + 0.5) / 2, view.byLine.getValue("CHAT"), 1e-9)
        assertEquals(0.5 + (1.64 + 0.5) / 2, view.byLine.getValue("TASK"), 1e-9)
        assertTrue("others" !in view.coveredLines)
        val flash = view.models.single { it.model == "flash" }
        assertEquals(2L, flash.backfilledCalls)
        assertEquals(0L, flash.unpricedCalls)
        assertEquals(1.0, view.models.single { it.model == "pro" }.cost, 1e-9)
        assertEquals(1L, view.models.single { it.model == "mystery" }.unpricedCalls)
    }

    private fun perMillion(input: Double, currency: PricingCurrency = PricingCurrency.CNY, known: Boolean = true) =
        com.ai.assistance.operit.data.stats.ResolvedPricing(
            billingMode = com.ai.assistance.operit.data.model.BillingMode.TOKEN, currency = currency,
            inputPricePerMillion = input, cachedInputPricePerMillion = input, outputPricePerMillion = input,
            source = com.ai.assistance.operit.data.stats.PricingSource.DEFAULT, known = known,
        )

    @Test
    fun aModelIsPricedOnlyWhenEveryConnectionHasTheSamePrice() {
        val one = perMillion(1.0)
        val sameElsewhere = one.copy(source = com.ai.assistance.operit.data.stats.PricingSource.CONFIG_OVERRIDE)
        assertEquals(one, OrbitUsageCost.ModelPrice(listOf({ _: Long? -> one }, { _: Long? -> sameElsewhere })).at(10))
        assertNull(OrbitUsageCost.ModelPrice(listOf({ _: Long? -> one }, { _: Long? -> perMillion(2.0) })).at(10))
        // A connection still without a price could be where these calls went.
        assertNull(OrbitUsageCost.ModelPrice(listOf({ _: Long? -> one }, { _: Long? -> perMillion(0.0, known = false) })).at(10))
        assertNull(OrbitUsageCost.ModelPrice(emptyList()).at(10))
    }

    @Test
    fun perRequestPricesDollarsAndPriceTiersAreHonoured() {
        val perRequest = com.ai.assistance.operit.data.stats.ResolvedPricing(
            billingMode = com.ai.assistance.operit.data.model.BillingMode.COUNT, currency = PricingCurrency.USD,
            pricePerRequest = 0.01, source = com.ai.assistance.operit.data.stats.PricingSource.DEFAULT, known = true,
        )
        // Three calls, one priced at an explicit zero: that one stays as it was.
        val counted = OrbitQuota.ModelUsage("2026-11-19", me, "count", 30, 0, 3, 0, 0.0, 0.0, 1)
        val (amount, currency) = OrbitUsageCost.backfill(counted, OrbitUsageCost.ModelPrice(listOf { _: Long? -> perRequest }))!!
        assertEquals(0.02, amount, 1e-12)
        assertEquals(PricingCurrency.USD, currency)
        val data = OrbitQuota.UsageData(
            rows = listOf(row("2026-11-19", input = 30, output = 0, calls = 3, priced = 1, tracked = 3, trackedTokens = 30)),
            hours = emptyList(), models = listOf(counted),
        )
        val view = OrbitUsageCost.view(data, day2, day2, me, emptyMap(), cny,
            modelPrices = mapOf("count" to OrbitUsageCost.ModelPrice(listOf { _: Long? -> perRequest })))
        assertEquals(0.14, view.total, 1e-12)
        assertTrue(view.converted)
        // The tier goes by the average input per call: 1,000,000 over 2 calls is 500,000 each.
        val tiered = OrbitUsageCost.ModelPrice(listOf { tokens: Long? ->
            if ((tokens ?: 0L) > 200_000L) perMillion(2.0) else perMillion(1.0)
        })
        val long = OrbitQuota.ModelUsage("2026-11-19", me, "long", 1_000_000, 0, 2, 0, 0.0, 0.0, 0)
        assertEquals(2.0, OrbitUsageCost.backfill(long, tiered)!!.first, 1e-9)
        // A price that would make one call cost more than any real call stays unknown, as when recording.
        val huge = OrbitUsageCost.ModelPrice(listOf { _: Long? -> perMillion(1e308) })
        assertNull(OrbitUsageCost.backfill(long, huge))
    }

    @Test
    fun rangeLeavesOutOtherDays() {
        val data = OrbitQuota.UsageData(
            rows = listOf(row("2026-11-18", input = 10, output = 0, calls = 1, costCny = 9.0, priced = 1, tracked = 1,
                trackedTokens = 10)),
            hours = emptyList(),
        )
        assertEquals(0.0, OrbitUsageCost.view(data, day2, day2, me, emptyMap(), cny).total, 1e-9)
    }

    @Test
    fun formatKeepsTinyAmountsVisible() {
        assertEquals("¥0", OrbitUsageCost.format(0.0, PricingCurrency.CNY))
        assertEquals("<$0.01", OrbitUsageCost.format(0.004, PricingCurrency.USD))
        assertEquals("¥3.42", OrbitUsageCost.format(3.4249, PricingCurrency.CNY))
        assertEquals("¥12,346", OrbitUsageCost.format(12345.6, PricingCurrency.CNY))
    }
}
