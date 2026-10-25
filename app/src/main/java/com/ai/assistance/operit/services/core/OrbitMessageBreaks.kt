package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.ChatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orbit sends a few messages in a row by putting `<message_break/>` between them in its final
 * reply. Once the reply is finished, each part is saved as its own message, so the user can reply
 * to or react to one of them. The first part keeps the whole turn before it (thinking, tool calls),
 * and only breaks after the last step count, as the prompt asks.
 */
internal object OrbitMessageBreaks {
    private val messageBreak = Regex("<message_break\\s*/?>(?:\\s*</message_break>)?", RegexOption.IGNORE_CASE)
    private val steps = listOf(
        ChatMarkupRegex.toolOrToolResultBlock,
        ChatMarkupRegex.toolSelfClosingTag,
        ChatMarkupRegex.toolResultSelfClosingTag,
        ChatMarkupRegex.statusTag,
        ChatMarkupRegex.statusSelfClosingTag,
    )

    /**
     * [message] as the messages it was sent as. The parts after the first get the next free
     * timestamps after it; a part already saved there by an earlier call keeps its place, so saving
     * the same reply twice gives the same rows. [savedAt] reads the sender and text of the rows
     * already saved at the given timestamps.
     */
    suspend fun split(
        message: ChatMessage,
        savedAt: suspend (List<Long>) -> Map<Long, Pair<String, String>>,
    ): List<ChatMessage> {
        val parts = withContext(Dispatchers.Default) { parts(message) } ?: return listOf(message)
        val head = message.copy(content = parts.first())
        val rest = parts.drop(1)
        if (rest.isEmpty()) return listOf(head)
        // Rows are read a window at a time; a part that steps past the window reads the next one.
        val taken = HashMap<Long, Pair<String, String>>()
        var readUpTo = message.timestamp
        suspend fun at(t: Long): Pair<String, String>? {
            while (t > readUpTo) {
                // Capped well under SQLite's bound-argument limit.
                val window = (1L..minOf(rest.size + 32L, 400L)).map { readUpTo + it }
                taken.putAll(savedAt(window))
                readUpTo = window.last()
            }
            return taken[t]
        }
        var timestamp = message.timestamp
        val others = rest.map { text ->
            timestamp++
            while (at(timestamp)?.let { it.first != "ai" || it.second.trim() != text } == true) timestamp++
            message.copy(
                content = text,
                timestamp = timestamp,
                inputTokens = 0,
                outputTokens = 0,
                cachedInputTokens = 0,
                outputDurationMs = 0,
                waitDurationMs = 0,
            )
        }
        return listOf(head) + others
    }

    /** The text of each part, the first with the whole turn before it; null when there is no break. */
    private fun parts(message: ChatMessage): List<String>? {
        val raw = message.content
        if (message.sender != "ai" || message.contentStream != null || !raw.contains("message_break", ignoreCase = true)) {
            return null
        }
        val tail = steps.maxOf { pattern -> pattern.findAll(raw).lastOrNull()?.range?.last?.plus(1) ?: 0 }
        val found = messageBreak.findAll(raw, tail).toList()
        if (found.isEmpty()) return null
        // A break inside thinking is not one Orbit sent.
        val outside = ChatUtils.displayOnlyBoundaryVisibility(raw, found.map { it.range.first }.toIntArray(), IntArray(0))
        val cuts = found.filterIndexed { i, _ -> outside.getOrElse(i) { false } }
        if (cuts.isEmpty()) return null
        val first = raw.substring(0, cuts.first().range.first).trimEnd()
        val rest = cuts.mapIndexed { i, cut ->
            raw.substring(cut.range.last + 1, cuts.getOrNull(i + 1)?.range?.first ?: raw.length).trim()
        }.filter { it.isNotEmpty() }
        return listOf(first) + rest
    }

    /**
     * The saved parts of each reply joined back with `<message_break/>`, for the history the model
     * reads. Without this the model sees its own split replies as one message with plain newlines,
     * copies that, and stops sending separate messages. A part is the AI row right after another
     * part of the same reply: same sending and finishing time, and no token counts of its own.
     */
    fun rejoin(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.size < 2) return messages
        val out = ArrayList<ChatMessage>(messages.size)
        var i = 0
        while (i < messages.size) {
            val head = messages[i]
            var end = i + 1
            while (end < messages.size && isPartOf(messages[end], messages[end - 1])) end++
            if (end == i + 1) {
                out += head
            } else {
                val text = StringBuilder(head.content.trimEnd())
                for (k in i + 1 until end) text.append('\n').append(BREAK).append('\n').append(messages[k].content.trim())
                out += head.copy(content = text.toString())
            }
            i = end
        }
        return out
    }

    private const val BREAK = "<message_break/>"

    /** [part] is the next saved part of the reply [before] belongs to. */
    private fun isPartOf(part: ChatMessage, before: ChatMessage): Boolean =
        part.sender == "ai" && before.sender == "ai" &&
            part.displayMode == ChatMessageDisplayMode.NORMAL && before.displayMode == ChatMessageDisplayMode.NORMAL &&
            part.contentStream == null && before.contentStream == null &&
            part.roleName == before.roleName &&
            part.timestamp > before.timestamp &&
            before.sentAt > 0L && part.sentAt == before.sentAt &&
            before.completedAt > 0L && part.completedAt == before.completedAt &&
            part.inputTokens == 0 && part.outputTokens == 0 && part.cachedInputTokens == 0
}
