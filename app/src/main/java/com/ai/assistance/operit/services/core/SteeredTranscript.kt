package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.data.model.ChatMessage

/** Boundaries are canonical display prefixes, never offsets into the raw replay stream. */
internal class SteeredTranscript {
    private data class Boundary(val displayPrefix: String, val nextTimestamp: Long)
    private val boundaries = mutableListOf<Boundary>()
    private var rootTimestamp: Long? = null
    private var segmentStream: SteeredSegmentStream? = null
    fun finalTimestamp(original: Long): Long =
        if (rootTimestamp == original) boundaries.lastOrNull()?.nextTimestamp ?: original else original
    fun closeStream() = segmentStream?.close()

    fun clear() {
        segmentStream?.close()
        segmentStream = null
        boundaries.clear()
        rootTimestamp = null
    }

    fun add(displayPrefix: String, nextTimestamp: Long, assistantTimestamp: Long) {
        if (rootTimestamp == null) rootTimestamp = assistantTimestamp
        require(rootTimestamp == assistantTimestamp)
        require(boundaries.lastOrNull()?.let { displayPrefix.startsWith(it.displayPrefix) } != false)
        boundaries += Boundary(displayPrefix, nextTimestamp)
        segmentStream?.close()
        segmentStream = SteeredSegmentStream()
    }

    fun project(message: ChatMessage): List<ChatMessage> {
        if (boundaries.isEmpty() || message.timestamp != rootTimestamp) return listOf(message)
        val result = mutableListOf<ChatMessage>()
        var start = 0
        var timestamp = message.timestamp
        boundaries.forEach { boundary ->
            result += message.copy(
                content = boundary.displayPrefix.substring(start),
                timestamp = timestamp,
                contentStream = null,
                inputTokens = 0, outputTokens = 0, cachedInputTokens = 0,
            )
            start = boundary.displayPrefix.length
            timestamp = boundary.nextTimestamp
        }
        // A collector can have captured an older snapshot before the boundary was sealed.
        // It may refresh the sealed rows, but cannot overwrite the new segment with stale text.
        if (message.content.startsWith(boundaries.last().displayPrefix)) {
            val suffix = message.content.substring(start)
            segmentStream?.update(suffix)
            if (message.contentStream == null) segmentStream?.close()
            result += message.copy(
                content = suffix, timestamp = timestamp,
                contentStream = if (message.contentStream != null) segmentStream?.stream else null,
            )
        }
        return result
    }
}
