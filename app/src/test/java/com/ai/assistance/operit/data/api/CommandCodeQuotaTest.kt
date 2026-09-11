package com.ai.assistance.operit.data.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Command Code's two silent-regression traps: the query string must join correctly with and without
 * an org id, and a USD figure must vanish entirely when no billing period scopes it.
 */
class CommandCodeQuotaTest {

    @Test
    fun orgQueryIsOptional() {
        assertEquals("", CommandCodeQuota.orgIdQuery(null))
        assertEquals("", CommandCodeQuota.orgIdQuery(""))
        assertEquals("?orgId=abc", CommandCodeQuota.orgIdQuery("abc"))
    }

    @Test
    fun queryParametersJoinOntoUrlsWithAndWithoutAQuery() {
        assertEquals(
            "https://api.commandcode.ai/alpha/usage/summary?since=2026-09-01T00%3A00%3A00.000Z",
            CommandCodeQuota.appendQuery(
                "https://api.commandcode.ai/alpha/usage/summary",
                "since=2026-09-01T00%3A00%3A00.000Z",
            ),
        )
        assertEquals(
            "https://api.commandcode.ai/alpha/usage/summary?orgId=abc&since=x",
            CommandCodeQuota.appendQuery(
                "https://api.commandcode.ai/alpha/usage/summary?orgId=abc",
                "since=x",
            ),
        )
    }

    @Test
    fun windowsComeFromCapAndUsedAndDropMalformedRows() {
        val limits = JSONObject(
            """{"fiveHour":{"cap":35,"used":34.9522404823,"resetAt":0},
                "weekly":{"cap":0,"used":5}}""",
        )
        val windows = CommandCodeQuota.windows(limits)
        assertEquals(1, windows.size)
        assertEquals(QuotaWindow.FIVE_HOUR, windows[0].window)
        assertEquals(99.86354423514285, windows[0].percent, 1e-9)
        assertNull(windows[0].resetAtEpochMillis)
        assertTrue(CommandCodeQuota.windows(null).isEmpty())
    }

    @Test
    fun creditsAreOmittedWithoutABillingPeriod() {
        val pools = JSONObject("""{"monthlyCredits":5,"purchasedCredits":2}""")
        val summary = JSONObject("""{"totalCost":3}""")
        // No period start: the summary would be lifetime spend, so there is no honest denominator.
        assertNull(CommandCodeQuota.spend(pools, null, summary))
        assertNull(CommandCodeQuota.spend(pools, "", summary))
        assertNull(CommandCodeQuota.spend(pools, "   ", summary))
    }

    @Test
    fun creditsSumTheRemainingPoolsAgainstThePerPeriodSpend() {
        val pools = JSONObject("""{"monthlyCredits":5,"purchasedCredits":2,"freeCredits":1}""")
        val spend = CommandCodeQuota.spend(
            pools,
            "2026-09-01T00:00:00.000Z",
            JSONObject("""{"totalCost":4}"""),
        )!!
        assertEquals(4.0, spend.used, 0.0)
        assertEquals(12.0, spend.limit, 0.0)
        assertEquals(8.0, spend.remaining, 0.0)

        // An exhausted pool is still a pool; a negative one contributes nothing.
        val exhausted = CommandCodeQuota.spend(
            JSONObject("""{"monthlyCredits":0}"""),
            "2026-09-01T00:00:00.000Z",
            JSONObject("""{"totalCost":4}"""),
        )!!
        assertEquals(4.0, exhausted.limit, 0.0)
        assertEquals(0.0, exhausted.remaining, 0.0)
        assertEquals(
            1.0,
            CommandCodeQuota.spend(
                JSONObject("""{"monthlyCredits":-3,"freeCredits":1}"""),
                "2026-09-01T00:00:00.000Z",
                JSONObject("""{"totalCost":4}"""),
            )!!.remaining,
            0.0,
        )
    }

    @Test
    fun creditsFallBackToMonthlyUnitsAndRejectUnusablePayloads() {
        val pools = JSONObject("""{"monthlyCredits":5}""")
        val period = "2026-09-01T00:00:00.000Z"
        val byUnits = CommandCodeQuota.spend(pools, period, JSONObject("""{"totalMonthlyCredits":2}"""))!!
        assertEquals(2.0, byUnits.used, 0.0)
        assertEquals(7.0, byUnits.limit, 0.0)
        // totalCost wins when both are present.
        assertEquals(
            2.0,
            CommandCodeQuota.spend(
                pools,
                period,
                JSONObject("""{"totalCost":2,"totalMonthlyCredits":9}"""),
            )!!.used,
            0.0,
        )
        assertNull(CommandCodeQuota.spend(pools, period, null))
        assertNull(CommandCodeQuota.spend(pools, period, JSONObject("""{"totalCost":-1}""")))
        // A pool field that is present but not a number is not a balance to meter.
        assertNull(CommandCodeQuota.spend(JSONObject("""{"monthlyCredits":"n/a"}"""), period, JSONObject("""{"totalCost":1}""")))
        assertNull(CommandCodeQuota.spend(null, period, JSONObject("""{"totalCost":1}""")))
        // An untouched period with empty pools is a real zero, not missing data.
        val untouched = CommandCodeQuota.spend(
            JSONObject("""{"monthlyCredits":0}"""),
            period,
            JSONObject("""{"totalCost":0}"""),
        )!!
        assertEquals(0.0, untouched.used, 0.0)
        assertEquals(0.0, untouched.limit, 0.0)
        assertEquals(0.0, untouched.remaining, 0.0)
    }
}
