package com.ai.assistance.operit.services.core

/** A soft window protects the current viewport and its read-ahead buffer before evicting data. */
internal fun transcriptWindowRange(
    timestamps: List<Long>,
    visible: Set<Long>,
    preferNewer: Boolean,
    targetSize: Int = 160,
    buffer: Int = 24,
): IntRange {
    if (timestamps.isEmpty()) return IntRange.EMPTY
    if (timestamps.size <= targetSize) return timestamps.indices
    val protected = timestamps.indices.filter { timestamps[it] in visible }
    if (protected.isEmpty()) {
        // Without a measured viewport, dropping rows can destroy an in-flight restore.
        return timestamps.indices
    }
    val first = (protected.first() - buffer).coerceAtLeast(0)
    val last = (protected.last() + buffer).coerceAtMost(timestamps.lastIndex)
    val size = maxOf(targetSize, last - first + 1)
    val start = if (preferNewer) {
        maxOf(0, minOf(first, timestamps.size - size))
    } else {
        minOf(first, (last - size + 1).coerceAtLeast(0))
    }
    return start..maxOf(last, (start + size - 1).coerceAtMost(timestamps.lastIndex))
}
