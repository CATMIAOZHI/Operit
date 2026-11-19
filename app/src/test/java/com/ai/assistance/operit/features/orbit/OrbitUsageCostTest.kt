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
