package com.ai.assistance.operit.features.orbit

import com.ai.assistance.operit.data.model.OrbitNotificationEntity
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitDeliveryPolicyTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private fun at(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()

    @Test fun `quiet night delays until morning rather than repeatedly waking`() {
        val now = at("2026-10-16T23:00:00+08:00")
        assertEquals(at("2026-10-17T08:00:00+08:00"), OrbitDeliveryPolicy.nextAllowed(
            OrbitNotificationEntity("orbit", quietEnabled = true), now, false, now, zone))
    }

    @Test fun `digest and frequency both constrain delivery`() {
        val created = at("2026-10-16T10:00:00+08:00")
        val now = at("2026-10-17T09:00:00+08:00")
        assertEquals(at("2026-10-17T09:10:00+08:00"), OrbitDeliveryPolicy.nextAllowed(
            OrbitNotificationEntity("orbit", lastDeliveredAt = at("2026-10-17T08:55:00+08:00")),
            created, true, now, zone))
    }

    @Test fun `quiet hours use wall time across daylight saving`() {
        val now = at("2026-03-08T01:00:00-05:00")
        assertEquals(at("2026-03-08T08:00:00-04:00"), OrbitDeliveryPolicy.nextAllowed(
            OrbitNotificationEntity("orbit", quietEnabled = true), now, false, now, ZoneId.of("America/New_York")))
    }
}
