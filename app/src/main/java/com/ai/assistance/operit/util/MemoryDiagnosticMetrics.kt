package com.ai.assistance.operit.util

import java.lang.ref.WeakReference

interface MemoryMetricSource {
    fun memoryCounters(): MemoryCounters
}

data class MemoryCounters(
    val streams: Long = 0,
    val replayEvents: Long = 0,
    val maxBacklog: Long = 0,
    val subscribers: Long = 0,
    val displayBuffers: Long = 0,
    val displayChars: Long = 0,
)

/** Weak and bounded: instrumentation must not keep a completed conversation or stream alive. */
internal object MemoryDiagnosticMetrics {
    private val sources = ArrayDeque<WeakReference<MemoryMetricSource>>()

    @Synchronized
    fun register(source: MemoryMetricSource) {
        sources.removeAll { it.get() == null }
        if (sources.size >= 512) sources.removeFirst()
        sources.addLast(WeakReference(source))
    }

    fun sample(): MemoryCounters {
        val refs = synchronized(this) { sources.toList() }
        var total = MemoryCounters()
        for (ref in refs) {
            val counters = ref.get()?.memoryCounters() ?: continue
            total = MemoryCounters(
                streams = total.streams + counters.streams,
                replayEvents = total.replayEvents + counters.replayEvents,
                maxBacklog = maxOf(total.maxBacklog, counters.maxBacklog),
                subscribers = total.subscribers + counters.subscribers,
                displayBuffers = total.displayBuffers + counters.displayBuffers,
                displayChars = total.displayChars + counters.displayChars,
            )
        }
        return total
    }
}
