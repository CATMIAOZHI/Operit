package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import com.ai.assistance.operit.data.model.ChatMessageProcessMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A stretch of a chat's metadata rows, oldest first, that begins and ends where no reply process
 * is cut, so [TranscriptStructure] over it groups its rows exactly as over the whole chat.
 */
internal class MetadataSlice(
    val rows: List<ChatMessageProcessMetadata>,
    /** Nothing was said before [rows]. */
    val atStart: Boolean,
    /** Nothing was said after [rows]. */
    val atEnd: Boolean,
) {
    /** Whether every time in [low]..[high] is inside this slice. */
    fun covers(low: Long, high: Long): Boolean =
        (atStart || rows.firstOrNull()?.let { it.timestamp <= low } == true) &&
            (atEnd || rows.lastOrNull()?.let { it.timestamp >= high } == true)
}

/**
 * True when [row] starts afresh after [before]: a finished reply row, then the owner's own row
 * (not a summary, not an agent card), at different times. No reply process holds rows on both
 * sides of such a cut — the reply row closes any process, and the owner's row keeps the next one
 * from continuing it.
 */
internal fun isCleanTranscriptCut(before: ChatMessageProcessMetadata, row: ChatMessageProcessMetadata): Boolean =
    before.timestamp != row.timestamp && before.sender == "ai" &&
        !(before.sentAt > 0 && before.displayMode == ChatMessageDisplayMode.ASSISTANT_INTERMEDIATE.name) &&
        row.sender != "ai" && row.sender != TRANSCRIPT_SUMMARY_SENDER && !isAgentInputCard(row.displayMode)

/**
 * Reads the part of a chat's metadata that holds [low]..[high] (null [high]: up to the newest row;
 * null [low]: as low as [high]), widened both ways to clean cuts, then reads further back while
 * [wantOlder] and further on while [wantNewer] say more is needed. A long chat costs about as much
 * as the part shown; with no clean cut at all it reads the whole chat, as before. The work runs
 * off the main thread.
 *
 * [before] gives the newest rows said before a time and [after] the oldest after one, oldest
 * first; [at] gives every row said at one time, so rows sharing a time are never split.
 */
internal suspend fun readMetadataSlice(
    before: suspend (Long, Int) -> List<ChatMessageProcessMetadata>,
    after: suspend (Long, Int) -> List<ChatMessageProcessMetadata>,
    at: suspend (Long) -> List<ChatMessageProcessMetadata>,
    low: Long?,
    high: Long?,
    wantOlder: (MetadataSlice) -> Boolean = { false },
    wantNewer: (MetadataSlice) -> Boolean = { false },
): MetadataSlice = withContext(Dispatchers.Default) {
    SliceReader(before, after, at, high).read(low ?: high, wantOlder, wantNewer)
}

private class SliceReader(
    private val before: suspend (Long, Int) -> List<ChatMessageProcessMetadata>,
    private val after: suspend (Long, Int) -> List<ChatMessageProcessMetadata>,
    private val at: suspend (Long) -> List<ChatMessageProcessMetadata>,
    private val high: Long?,
) {
    private var olderChunk = FIRST_CHUNK
    private var newerChunk = FIRST_CHUNK
    private val rows = ArrayDeque<ChatMessageProcessMetadata>()
    private var atStart = false
    private var atEnd = high == null
    // Where the lowest and highest clean cuts read are; only newly read rows are looked through.
    private var lowCut: Int? = null
    private var highCut: Int? = null
    private val top = high?.let { if (it == Long.MAX_VALUE) it else it + 1 } ?: Long.MAX_VALUE

    private fun cut(i: Int) = isCleanTranscriptCut(rows[i - 1], rows[i])

    private suspend fun older() {
        val first = rows.firstOrNull()?.timestamp ?: top
        var read = before(first, olderChunk)
        if (read.size < olderChunk) atStart = true
        // A full read may have left out rows sharing its oldest time; they are all taken.
        else read.first().timestamp.let { edge -> read = at(edge) + read.dropWhile { it.timestamp == edge } }
        olderChunk = (olderChunk * 2).coerceAtMost(MAX_CHUNK)
        val k = read.size
        if (k == 0) return
        rows.addAll(0, read)
        val newPairs = k.coerceAtMost(rows.size - 1)
        highCut = highCut?.plus(k) ?: (newPairs downTo 1).firstOrNull(::cut)
        lowCut = (1..newPairs).firstOrNull(::cut) ?: lowCut?.plus(k)
    }

    private suspend fun newer() {
        val last = rows.lastOrNull()?.timestamp ?: (top - 1)
        var read = after(last, newerChunk)
        if (read.size < newerChunk) atEnd = true
        // The same for rows sharing its newest time.
        else read.last().timestamp.let { edge -> read = read.dropLastWhile { it.timestamp == edge } + at(edge) }
        newerChunk = (newerChunk * 2).coerceAtMost(MAX_CHUNK)
        if (read.isEmpty()) return
        val old = rows.size
        rows.addAll(read)
        lowCut = lowCut ?: (maxOf(old, 1) until rows.size).firstOrNull(::cut)
        highCut = (rows.size - 1 downTo maxOf(old, 1)).firstOrNull(::cut) ?: highCut
    }

    suspend fun read(
        needLow: Long?,
        wantOlder: (MetadataSlice) -> Boolean,
        wantNewer: (MetadataSlice) -> Boolean,
    ): MetadataSlice {
        older()
        while (true) {
            // The lowest clean cut read, so all that was read is used; it must not be above [needLow].
            val from = if (atStart) 0 else lowCut?.takeIf { needLow == null || rows[it].timestamp <= needLow }
            if (from == null) { older(); continue }
            // The highest clean cut read, which the slice ends just before; it must not be below [high].
            val until = if (atEnd) rows.size
                else highCut?.takeIf { it > from && (high == null || rows[it - 1].timestamp >= high) }
            if (until == null) { newer(); continue }
            val slice = MetadataSlice(rows.subList(from, until).toList(),
                atStart = from == 0 && atStart, atEnd = until == rows.size && atEnd)
            if (!slice.atStart && wantOlder(slice)) { older(); continue }
            if (!slice.atEnd && wantNewer(slice)) { newer(); continue }
            return slice
        }
    }
}

private const val FIRST_CHUNK = 256
private const val MAX_CHUNK = 65536
