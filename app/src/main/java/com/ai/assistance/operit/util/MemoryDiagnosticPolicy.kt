package com.ai.assistance.operit.util

/** Pure numeric policy. Elapsed time, rather than wall clock, controls windows and cooldowns. */
internal class MemoryDiagnosticPolicy {
    data class Point(val elapsedMs: Long, val usedBytes: Long, val maxBytes: Long)
    private val recent = ArrayDeque<Point>()
    private var highSamples = 0
    private var lastAlert = Long.MIN_VALUE
    private var lastCritical = Long.MIN_VALUE
    private var lastSample: Long? = null

    fun observe(point: Point): String? {
        if (point.maxBytes <= 0) return null
        if (lastSample?.let { point.elapsedMs - it > 30_000 } == true) highSamples = 0
        lastSample = point.elapsedMs
        val ratio = point.usedBytes.toDouble() / point.maxBytes
        highSamples = if (ratio >= 0.80) (highSamples + 1).coerceAtMost(3) else 0
        while (recent.isNotEmpty() && point.elapsedMs - recent.first().elapsedMs > 60_000) {
            recent.removeFirst()
        }
        val base = recent.firstOrNull()
        val growing = base != null && point.elapsedMs - base.elapsedMs >= 30_000 &&
            ratio >= 0.65 &&
            point.usedBytes - base.usedBytes >= maxOf(32L * 1024 * 1024, point.maxBytes * 15 / 100)
        recent.addLast(point)
        while (recent.size > 5) recent.removeFirst()
        fun ready(last: Long, interval: Long) =
            last == Long.MIN_VALUE || point.elapsedMs - last >= interval
        if (ratio >= 0.92 && ready(lastCritical, 60_000)) {
            lastCritical = point.elapsedMs
            lastAlert = point.elapsedMs
            return "critical_heap"
        }
        val reason = when {
            highSamples >= 3 -> "sustained_high_heap"
            growing -> "rapid_heap_growth"
            else -> null
        }
        if (reason != null && ready(lastAlert, 300_000)) {
            lastAlert = point.elapsedMs
            return reason
        }
        return null
    }
}
