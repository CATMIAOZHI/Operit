package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import com.ai.assistance.operit.data.model.ChatMessageProcessMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** A slice must group its rows exactly as the whole chat does, wherever it is read from. */
class TranscriptMetadataSliceTest {
    private val card = ChatMessageDisplayMode.entries.first { it.isCollaborationEvent }.name

    private fun randomChat(random: Random, size: Int): List<ChatMessageProcessMetadata> {
        var key = 1L
        var t = 0L
        return (1..size).map { i ->
            // Now and then rows share a time, as some old chats have.
            if (random.nextInt(6) != 0 || i == 1) t += 10
            when (random.nextInt(10)) {
                0, 1 -> ChatMessageProcessMetadata(t, "user", "NORMAL", 0, 0, 0, 0)
                2 -> ChatMessageProcessMetadata(t, "summary", "NORMAL", 0, 0, 0, 0)
                3 -> ChatMessageProcessMetadata(t, "user", card, 0, 0, 0, 0)
                else -> {
                    if (random.nextInt(4) == 0) key++
                    val mode = if (random.nextBoolean()) ChatMessageDisplayMode.ASSISTANT_INTERMEDIATE.name else "NORMAL"
                    val sentAt = if (random.nextInt(8) == 0) 0L else key
                    ChatMessageProcessMetadata(t, "ai", mode, sentAt, if (random.nextBoolean()) t else 0L, 1, 1)
                }
            }
        }
    }

    @Test
    fun sliceGroupsRowsAsTheWholeChat() = runBlocking {
        val random = Random(7)
        repeat(300) {
            val all = randomChat(random, random.nextInt(0, 1500))
            val full = TranscriptStructure(all)
            val before: suspend (Long, Int) -> List<ChatMessageProcessMetadata> = { time, limit ->
                all.filter { it.timestamp < time }.takeLast(limit)
            }
            val after: suspend (Long, Int) -> List<ChatMessageProcessMetadata> = { time, limit ->
                all.filter { it.timestamp > time }.take(limit)
            }
            val at: suspend (Long) -> List<ChatMessageProcessMetadata> = { time -> all.filter { it.timestamp == time } }
            val a = random.nextLong(0, all.size * 10L + 20)
            val b = random.nextLong(a, all.size * 10L + 30)
            val high = if (random.nextBoolean()) b else null
            val slice = readMetadataSlice(before, after, at, a, high)
            val start = all.indexOfFirst { it === slice.rows.firstOrNull() }
            if (slice.rows.isNotEmpty()) {
                assertEquals(all.subList(start, start + slice.rows.size), slice.rows)
                assertEquals(start == 0, slice.atStart)
                assertEquals(start + slice.rows.size == all.size, slice.atEnd)
            }
            assertTrue(slice.covers(a, high ?: Long.MAX_VALUE) || all.isEmpty())
            val part = TranscriptStructure(slice.rows)
            val topLevel = full.topLevelRows.mapTo(hashSetOf()) { it.timestamp }
            slice.rows.forEach { row ->
                assertEquals(full.turnByTimestamp[row.timestamp], part.turnByTimestamp[row.timestamp])
                assertEquals(row.timestamp in topLevel, part.topLevelRows.any { it.timestamp == row.timestamp })
            }
        }
    }
}
