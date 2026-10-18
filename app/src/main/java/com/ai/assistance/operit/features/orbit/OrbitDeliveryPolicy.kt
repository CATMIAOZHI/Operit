package com.ai.assistance.operit.features.orbit

import com.ai.assistance.operit.data.model.OrbitNotificationEntity
import java.time.Instant
import java.time.ZoneId
import java.time.LocalTime

internal object OrbitDeliveryPolicy {
    /**
     * [graceMs]: how long after the digest time something still counts as that morning's. A task
     * coming due right at the digest time is queued a moment later; with a grace it goes that
     * morning instead of the next.
     */
    fun nextAllowed(policy: OrbitNotificationEntity, createdAt: Long, digest: Boolean, now: Long,
        zone: ZoneId = ZoneId.systemDefault(), graceMs: Long = 0L): Long {
        var due = maxOf(now, policy.lastDeliveredAt + policy.intervalMinutes * 60_000L)
        if (digest) {
            val created = Instant.ofEpochMilli(createdAt).atZone(zone)
            var morning = created.toLocalDate().atTime(LocalTime.ofSecondOfDay(policy.digestMinute * 60L)).atZone(zone)
            val at = morning.toInstant().toEpochMilli()
            if (if (graceMs > 0) at + graceMs <= createdAt else at <= createdAt) morning = morning.plusDays(1)
            due = maxOf(due, morning.toInstant().toEpochMilli())
        }
        if (!policy.quietEnabled || policy.quietStart == policy.quietEnd) return due
        val date = Instant.ofEpochMilli(due).atZone(zone)
        val minute = date.hour * 60 + date.minute
        val overnight = policy.quietStart > policy.quietEnd
        val quiet = if (overnight) minute >= policy.quietStart || minute < policy.quietEnd
            else minute in policy.quietStart until policy.quietEnd
        if (!quiet) return due
        var end = date.toLocalDate().atTime(LocalTime.ofSecondOfDay(policy.quietEnd * 60L)).atZone(zone)
        if (overnight && minute >= policy.quietStart) end = end.plusDays(1)
        return end.toInstant().toEpochMilli()
    }
}
