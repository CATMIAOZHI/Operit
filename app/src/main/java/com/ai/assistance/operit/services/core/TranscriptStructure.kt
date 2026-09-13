package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import com.ai.assistance.operit.data.model.ChatMessageProcessMetadata

internal data class TranscriptTurn(
    val key: Long,
    val finalTimestamp: Long,
    val memberTimestamps: Set<Long>,
    val durationMs: Long,
)

/** Presentation structure is independent of which bodies happen to be in memory. */
internal class TranscriptStructure(val rows: List<ChatMessageProcessMetadata>) {
    fun retainedTimestamps(topLevelTimestamps: Set<Long>, loadedTimestamps: List<Long>): Set<Long> =
        loadedTimestamps.filterTo(hashSetOf()) { timestamp ->
            timestamp in topLevelTimestamps ||
                turnByTimestamp[timestamp]?.finalTimestamp in topLevelTimestamps
        }

    val turns: List<TranscriptTurn>
    val turnByTimestamp: Map<Long, TranscriptTurn>
    val topLevelRows: List<ChatMessageProcessMetadata>

    init {
        val completed = mutableListOf<TranscriptTurn>()
        val pending = mutableListOf<ChatMessageProcessMetadata>()
        var pendingKey: Long? = null
        for (row in rows) {
            val collaboration = row.displayMode == ChatMessageDisplayMode.COLLABORATION_EVENT.name ||
                row.displayMode == ChatMessageDisplayMode.COLLABORATION_TASK.name
            if (row.sender != "ai") {
                if (collaboration) pending += row
                else if (pendingKey == null) pending.clear()
                continue
            }
            if (pendingKey != null && pendingKey != row.sentAt) pending.clear()
            pendingKey = row.sentAt
            if (row.sentAt > 0 && row.displayMode == ChatMessageDisplayMode.ASSISTANT_INTERMEDIATE.name) {
                pending += row
            } else {
                if (pending.isNotEmpty() && row.sentAt > 0 && row.completedAt > 0 &&
                    row.displayMode == ChatMessageDisplayMode.NORMAL.name
                ) {
                    completed += TranscriptTurn(
                        row.sentAt, row.timestamp,
                        (pending.map { it.timestamp } + row.timestamp).toSet(),
                        row.waitDurationMs + row.outputDurationMs,
                    )
                }
                pending.clear()
                pendingKey = null
            }
        }
        turns = completed
        turnByTimestamp = buildMap {
            completed.forEach { turn -> turn.memberTimestamps.forEach { put(it, turn) } }
        }
        topLevelRows = rows.filter { row ->
            turnByTimestamp[row.timestamp]?.let { it.finalTimestamp == row.timestamp } ?: true
        }
    }
}
