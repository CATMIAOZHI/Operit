package com.ai.assistance.operit.ui.features.settings.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The reset label ages on its own, so its unit rollovers are worth pinning. */
class ProviderQuotaCountdownTest {

    @Test
    fun countdownRollsMinutesIntoHoursIntoDays() {
        // A sub-minute remainder must not read as "resets in 0m", which looks already-spent.
        assertEquals("<1m", formatQuotaCountdown(0L))
        assertEquals("<1m", formatQuotaCountdown(30_000L))
        assertEquals("<1m", formatQuotaCountdown(59_999L))
        assertEquals("1m", formatQuotaCountdown(60_000L))
        assertEquals("59m", formatQuotaCountdown(59 * 60_000L))
        assertEquals("1h 0m", formatQuotaCountdown(60 * 60_000L))
        assertEquals("1h 30m", formatQuotaCountdown(90 * 60_000L))
        assertEquals("1d 1h", formatQuotaCountdown(25 * 3_600_000L))
        assertEquals("2d 0h", formatQuotaCountdown(48 * 3_600_000L))
    }
}
