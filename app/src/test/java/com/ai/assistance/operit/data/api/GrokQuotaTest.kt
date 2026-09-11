package com.ai.assistance.operit.data.api

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Grok's weekly credits envelope is the meter that gates prompting, so its shape rules are pinned
 * here: a non-weekly period is not a weekly meter, and a missing percentage means "nothing used".
 */
class GrokQuotaTest {

    private fun jwtWithSubject(subject: String?): String {
        val payload = JSONObject().apply { if (subject != null) put("sub", subject) }.toString()
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        return "header.$encoded.signature"
    }

    private fun jwtWithRawPayload(rawPayload: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(rawPayload.toByteArray(Charsets.UTF_8))
        return "header.$encoded.signature"
    }

    @Test
    fun userIdComesFromTheJwtSubject() {
        assertEquals("user-abc", GrokQuota.userIdFromAccessToken(jwtWithSubject("user-abc")))
        // The stored account row has no user id, so an unreadable token must simply yield no weekly.
        assertNull(GrokQuota.userIdFromAccessToken("not-a-jwt"))
        assertNull(GrokQuota.userIdFromAccessToken("header..sig"))
        assertNull(GrokQuota.userIdFromAccessToken(jwtWithSubject(null)))
        // A JSON null or a non-string subject is not a user id, and must not stringify into one.
        assertNull(GrokQuota.userIdFromAccessToken(jwtWithRawPayload("""{"sub":null}""")))
        assertNull(GrokQuota.userIdFromAccessToken(jwtWithRawPayload("""{"sub":123}""")))
        assertNull(GrokQuota.userIdFromAccessToken(jwtWithRawPayload("""{"sub":"  "}""")))
    }

    @Test
    fun weeklyCreditsReadPercentAndPeriodEnd() {
        val (percent, resetAt) = GrokQuota.weeklyCredits(
            JSONObject(
                """{"config":{"creditUsagePercent":12.5,
                    "currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","end":"2026-08-12T20:00:00.000Z"}}}""",
            ),
        )!!
        assertEquals(12.5, percent, 0.0)
        assertEquals(java.time.Instant.parse("2026-08-12T20:00:00.000Z").toEpochMilli(), resetAt)
    }

    @Test
    fun anAbsentPercentIsZeroButANonWeeklyPeriodIsNoMeter() {
        val (percent, _) = GrokQuota.weeklyCredits(
            JSONObject(
                """{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY",
                    "end":"2026-08-12T20:00:00.000Z"}}}""",
            ),
        )!!
        assertEquals(0.0, percent, 0.0)

        // A monthly period must not be reported as a weekly window.
        assertNull(
            GrokQuota.weeklyCredits(
                JSONObject(
                    """{"config":{"creditUsagePercent":40,
                        "currentPeriod":{"type":"USAGE_PERIOD_TYPE_MONTHLY","end":"2026-08-12T20:00:00.000Z"}}}""",
                ),
            ),
        )
        // No usable period end means no reset clock, so the weekly meter is not shown.
        assertNull(
            GrokQuota.weeklyCredits(
                JSONObject(
                    """{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY"}}}""",
                ),
            ),
        )
        // Present but unusable percentage drops the row, rather than silently reading as zero.
        listOf("n/a", null).forEach { unusable ->
            assertNull(
                GrokQuota.weeklyCredits(
                    JSONObject(
                        """{"config":{"creditUsagePercent":${
                            if (unusable == null) "null" else "\"$unusable\""
                        },"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY",
                            "end":"2026-08-12T20:00:00.000Z"}}}""",
                    ),
                ),
            )
        }
        assertNull(GrokQuota.weeklyCredits(null))
        assertNull(GrokQuota.weeklyCredits(JSONObject("""{"config":{}}""")))
    }

    @Test
    fun monthlyDollarsDerivePercentFromCents() {
        val (percent, resetAt) = GrokQuota.monthlyDollars(
            JSONObject(
                """{"config":{"monthlyLimit":{"val":2000},"used":{"val":500},
                    "billingPeriodEnd":"2026-08-12T20:00:00.000Z"}}""",
            ),
        )!!
        assertEquals(25.0, percent, 0.0)
        assertEquals(java.time.Instant.parse("2026-08-12T20:00:00.000Z").toEpochMilli(), resetAt)

        // Overuse clamps at 100 rather than reporting more than the pool.
        assertEquals(
            100.0,
            GrokQuota.monthlyDollars(
                JSONObject("""{"config":{"monthlyLimit":{"val":2000},"used":{"val":3000}}}"""),
            )!!.first,
            0.0,
        )
        // A zero or absent limit is not a meter to divide by.
        assertNull(
            GrokQuota.monthlyDollars(
                JSONObject("""{"config":{"monthlyLimit":{"val":0},"used":{"val":1}}}"""),
            ),
        )
        assertNull(
            GrokQuota.monthlyDollars(JSONObject("""{"config":{"used":{"val":1}}}""")),
        )
        assertNull(GrokQuota.monthlyDollars(null))
    }
}
