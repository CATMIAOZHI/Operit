package com.ai.assistance.operit.data.api

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two quota rules that are easy to get silently wrong: a provider's "no reset" sentinel
 * must not become a 1970 timestamp, and a percent must never leave 0-100.
 */
class ProviderQuotaParsingTest {

    @Test
    fun zeroAndNegativeResetsAreSentinelsNotTheEpoch() {
        // Command Code sends fiveHour.resetAt: 0 and weekly "resetAt": "-1" for absent windows.
        assertNull(QuotaParsing.resetAt(0))
        assertNull(QuotaParsing.resetAt(0.0))
        assertNull(QuotaParsing.resetAt("-1"))
        assertNull(QuotaParsing.resetAt(-1L))
        assertNull(QuotaParsing.resetAt(JSONObject.NULL))
        assertNull(QuotaParsing.resetAt(null))
        assertNull(QuotaParsing.resetAt("not-a-clock"))
    }

    @Test
    fun resetAcceptsSecondsMillisAndIsoText() {
        // Below 1e10 the value is read as epoch seconds, above it as epoch milliseconds.
        assertEquals(1_760_000_000_000L, QuotaParsing.resetAt(1_760_000_000L))
        assertEquals(1_760_000_000_000L, QuotaParsing.resetAt(1_760_000_000_000L))
        val iso = "2026-09-12T04:07:13.000Z"
        assertEquals(Instant.parse(iso).toEpochMilli(), QuotaParsing.resetAt(iso))
    }

    @Test
    fun percentIsClampedAndRejectsNonNumbers() {
        assertEquals(0.0, QuotaParsing.percent(-5)!!, 0.0)
        assertEquals(100.0, QuotaParsing.percent(140.5)!!, 0.0)
        assertEquals(12.0, QuotaParsing.percent("12")!!, 0.0)
        assertNull(QuotaParsing.percent(""))
        assertNull(QuotaParsing.percent(JSONObject.NULL))
        assertNull(QuotaParsing.percent(Double.NaN))
    }

    @Test
    fun windowDerivesPercentFromCapAndUsed() {
        val (percent, resetAt) = QuotaParsing.windowFromUsage(
            JSONObject("""{"cap":100,"used":12,"resetAt":0}"""),
        )!!
        assertEquals(12.0, percent, 0.0)
        assertNull(resetAt)

        // Overuse clamps rather than reporting >100%.
        assertEquals(100.0, QuotaParsing.windowFromUsage(
            JSONObject("""{"cap":10,"used":25}"""),
        )!!.first, 0.0)

        // An unavailable or malformed window is omitted, never rendered as a zero-percent bar.
        assertNull(QuotaParsing.windowFromUsage(null))
        assertNull(QuotaParsing.windowFromUsage(JSONObject("""{"used":5}""")))
        assertNull(QuotaParsing.windowFromUsage(JSONObject("""{"cap":0,"used":5}""")))
        assertNull(QuotaParsing.windowFromUsage(JSONObject("""{"cap":10,"used":-1}""")))
    }

    @Test
    fun usageUrlOnlyRewritesTheOfficialGoEndpoint() {
        assertEquals(
            "https://opencode.ai/zen/go/v1/usage",
            OpenCodeGoQuotaClient.usageUrl("https://opencode.ai/zen/go/v1/chat/completions").toString(),
        )
        // The bare base is what the request path itself also accepts, so it must meter too.
        assertEquals(
            "https://opencode.ai/zen/go/v1/usage",
            OpenCodeGoQuotaClient.usageUrl("https://opencode.ai/zen/go").toString(),
        )
        // A user-edited host or a non-Go path must never receive the configured key.
        assertNull(OpenCodeGoQuotaClient.usageUrl("http://opencode.ai/zen/go/v1/chat/completions"))
        assertNull(OpenCodeGoQuotaClient.usageUrl("https://opencode.ai.evil.test/zen/go/v1/chat/completions"))
        assertNull(OpenCodeGoQuotaClient.usageUrl("https://opencode.ai:8443/zen/go/v1/chat/completions"))
        assertNull(OpenCodeGoQuotaClient.usageUrl("https://example.com/zen/go/v1/chat/completions"))
        assertNull(OpenCodeGoQuotaClient.usageUrl("https://example.com/v1/chat/completions"))
        assertNull(OpenCodeGoQuotaClient.usageUrl(""))
    }

    @Test
    fun emptyQuotaIsReportedAsEmpty() {
        // Only a total absence of data counts as empty; a reported zero-dollar limit is still data.
        assertTrue(ProviderQuota().isEmpty)
        assertFalse(ProviderQuota(windows = emptyList(), creditsLimitUsd = 0.0).isEmpty)
        assertFalse(ProviderQuota(creditsLimitUsd = 12.0).isEmpty)
        assertFalse(
            ProviderQuota(windows = listOf(ProviderQuotaWindow(QuotaWindow.WEEKLY, 8.0))).isEmpty,
        )
    }
}
