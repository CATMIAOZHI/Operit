package com.ai.assistance.operit.api.chat.compaction

import com.ai.assistance.operit.api.chat.llmprovider.HttpStatusCodeException
import com.ai.assistance.operit.api.chat.prediction.ComposerPredictionSnapshot
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ChatMessage
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Rules for compacting a chat's context, after OpenCode's compaction:
 *
 * - the newest part of the conversation stays word for word beside the summary ([summaryPosition]);
 * - a summary can be written ahead of time in the background and swapped in when compaction is due,
 *   as long as the conversation it covers has not changed ([fingerprint]);
 * - a request the provider rejects as too long is compacted and sent once more ([isContextOverflow]),
 *   and a summary request rejected as too long is retried shorter ([SHRINK_STEPS], [shrinkTranscript]).
 */
object ContextCompaction {
    /** Recent conversation kept word for word beside a new summary, in estimated tokens. */
    private const val KEEP_TOKENS = 15_000
    /** The kept part never takes more than this share of the compaction line. */
    private const val KEEP_SHARE = 0.2
    /**
     * Share of the compaction line at which a summary starts in the background. The rest of the
     * way to the line is the room the chat keeps going in while it is written; it is held and
     * swapped in at the line (earlier would throw away the prompt cache), and only a chat that
     * reaches the line before it is written waits for it.
     */
    const val PRECOMPUTE_RATIO = 0.85
    /** Background summaries that may fail before none starts again until the next compaction. */
    const val PRECOMPUTE_MAX_FAILURES = 2
    /**
     * The most conversation a summary written ahead of time may leave beside it: half the
     * compaction line. The chat grows from the precompute share to the line while the summary is
     * written and waits, so its tail is usually far below this; with more, the context would stay
     * near the line, so a new summary is written instead.
     */
    private const val HELD_TAIL_SHARE = 0.5
    /** After each "too long" rejection a summary request aims at this share of its first size. */
    val SHRINK_STEPS = listOf(0.85, 0.7, 0.5, 0.35)
    /** Characters kept, from the start and from the end, of one message in a shortened transcript. */
    private val SHRINK_CLIPS = listOf(2_000, 600)

    fun keepTokens(ceiling: Int): Int = minOf(KEEP_TOKENS, (ceiling * KEEP_SHARE).toInt()).coerceAtLeast(0)

    fun heldTailLimit(ceiling: Int): Int = (ceiling * HELD_TAIL_SHARE).toInt().coerceAtLeast(0)

    /**
     * Where the latest reply of a request starts: inside a turn, a summary covers everything before
     * it and the reply stays word for word, with its tool calls and their results (OpenCode keeps
     * the latest response the same way). Null when there is nothing before it to summarize.
     */
    fun stepAnchor(history: List<PromptTurn>): Int? {
        fun isReply(turn: PromptTurn) = turn.kind == PromptTurnKind.ASSISTANT || turn.kind == PromptTurnKind.TOOL_CALL
        var anchor = history.indexOfLast(::isReply)
        if (anchor < 0) return null
        while (anchor > 0 && isReply(history[anchor - 1])) anchor--
        val hasConversation = history.subList(0, anchor).any {
            it.kind != PromptTurnKind.SYSTEM && it.kind != PromptTurnKind.SUMMARY
        }
        return anchor.takeIf { hasConversation }
    }

    /** A rough token count: Chinese, Japanese and Korean text runs near 0.6 per character, other text near 0.3. */
    fun estimateTokens(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var wide = 0
        var other = 0
        for (ch in text) {
            val code = ch.code
            if (code in 0x2E80..0x9FFF || code in 0xAC00..0xD7AF || code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF) {
                wide++
            } else {
                other++
            }
        }
        return (wide * 0.6 + other * 0.3).toInt() + 4
    }

    fun estimateTokens(messages: List<ChatMessage>): Int =
        messages.fold(0L) { total, message -> total + estimateTokens(message.content) }
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /**
     * Where a new summary goes in [messages] (the runtime history, from the latest summary on), so
     * that every message from that index on stays word for word. The kept part starts at a user
     * message, fits [keepTokens], leaves at least one user or AI message before it to summarize, and
     * has room for the summary's timestamp between it and the message before. Without such a point
     * the summary goes after the last AI reply, as it always did.
     */
    fun summaryPosition(messages: List<ChatMessage>, keepTokens: Int): Int {
        // A reply still empty and unfinished (one rejected as too long is deleted when its turn
        // ends) is no anchor for the summary.
        val lastReply = messages.indexOfLast {
            it.sender == "ai" && !(it.completedAt <= 0L && it.content.isBlank())
        }
        val lastSummary = messages.indexOfLast { it.sender == "summary" }
        // With no reply since the last summary, the new one goes at the end: right after the old
        // summary it would have no place of its own.
        val afterLastReply = if (lastReply > lastSummary) lastReply + 1 else messages.size
        var kept = 0L
        var earliest = -1
        for (index in messages.indices.reversed()) {
            if (index <= lastSummary) break
            kept += estimateTokens(messages[index].content)
            if (kept > keepTokens) break
            if (messages[index].sender == "user" && index > 0 &&
                messages[index].timestamp - messages[index - 1].timestamp > 1L &&
                hasConversation(messages, lastSummary + 1, index)
            ) earliest = index
        }
        return if (earliest in 1..afterLastReply) earliest else afterLastReply
    }

    private fun hasConversation(messages: List<ChatMessage>, from: Int, until: Int): Boolean =
        (from until until).any { messages[it].sender == "user" || messages[it].sender == "ai" }

    /** Changes when any message is added, removed, reordered or edited. */
    fun fingerprint(messages: List<ChatMessage>): Long {
        var hash = 1_125_899_906_842_597L
        for (message in messages) {
            hash = 31 * hash + message.timestamp
            hash = 31 * hash + message.sender.hashCode()
            hash = 31 * hash + message.content.length
            hash = 31 * hash + message.content.hashCode()
        }
        return 31 * hash + messages.size
    }

    private val overflowPattern = Regex(
        "context[_ ]length|maximum context|context window|context limit|too many tokens|" +
            "prompt is too long|input is too long|too long for|request too large|exceeds? the (?:maximum|max|context)|" +
            "reduce the length|input token count|上下文.{0,8}(?:超|过长|上限)|超.{0,6}上下文",
        RegexOption.IGNORE_CASE,
    )

    /** The provider answered that the request does not fit the model's window (a rate limit is not that). */
    fun isContextOverflow(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            if (current is ContextOverflowException) return true
            if (current is HttpStatusCodeException && current.statusCode in 400..499 && current.statusCode != 429 &&
                overflowPattern.containsMatchIn(current.message.orEmpty())
            ) return true
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * Whether an error a provider reported inside a successful response (a JSON error object or a
     * failed stream event) says the request is longer than the model takes. Rate limits are not.
     */
    fun isOverflowError(code: String?, message: String?): Boolean {
        val text = "${code.orEmpty()} ${message.orEmpty()}"
        if (rateLimitPattern.containsMatchIn(text)) return false
        return code.equals("context_length_exceeded", ignoreCase = true) || overflowPattern.containsMatchIn(message.orEmpty())
    }

    private val rateLimitPattern = Regex("rate[_ ]limit|per min|tokens per minute|\\bTPM\\b|quota", RegexOption.IGNORE_CASE)

    /**
     * A summary transcript cut down toward [targetTokens]: long messages are clipped to their start
     * and end, more tightly when still too long, and then the oldest messages are left out. A lone
     * message (a group chat's whole transcript is one) keeps its newest part instead.
     */
    fun shrinkTranscript(transcript: List<Pair<String, String>>, targetTokens: Int): List<Pair<String, String>> {
        fun size(pairs: List<Pair<String, String>>) = pairs.fold(0L) { total, pair -> total + estimateTokens(pair.second) }
        if (transcript.size == 1) {
            val (role, text) = transcript.single()
            val total = size(transcript)
            if (total <= targetTokens) return transcript
            val keepChars = (text.length * (targetTokens.toDouble() / total)).toInt().coerceIn(0, text.length)
            return listOf(role to "[... earlier part left out ...]\n" + text.takeLast(keepChars))
        }
        var current = transcript
        for (clip in SHRINK_CLIPS) {
            if (size(current) <= targetTokens) return current
            current = current.map { (role, text) -> role to clipMiddle(text, clip) }
        }
        var total = size(current)
        var start = 0
        while (total > targetTokens && start < current.size - 1) {
            total -= estimateTokens(current[start].second)
            start++
        }
        return current.subList(start, current.size)
    }

    private fun clipMiddle(text: String, keep: Int): String =
        if (text.length <= keep * 2 + 20) text
        else text.take(keep) + "\n[... ${text.length - keep * 2} characters left out ...]\n" + text.takeLast(keep)

    /**
     * Appended to the request the latest turn sent, so the summary request reads that whole prefix
     * from the provider's cache. It asks for the same summary the separate summary request asks for.
     */
    fun checkpointInstruction(summaryRules: String, useEnglish: Boolean): String =
        if (useEnglish) {
            """
            This message comes from the app, not from the user. Stop the conversation here: do not reply to the user, do not continue the task, and do not call tools or write tool markup.
            Write a checkpoint of the whole conversation above, so it can go on from the checkpoint alone. If the conversation starts from an earlier checkpoint, carry forward what is still relevant in it.
            Follow these instructions for the checkpoint:
            <instructions>
            $summaryRules
            </instructions>
            """.trimIndent()
        } else {
            """
            这条消息来自 App，不是用户发的。对话先停在这里：不要回复用户，不要继续任务，不要调用工具，也不要写工具调用格式。
            为上面的整段对话写一份检查点，让对话只看检查点也能接着进行。如果对话开头已经有一份更早的检查点，把其中仍然有用的内容带过来。
            检查点按下面的要求写：
            <instructions>
            $summaryRules
            </instructions>
            """.trimIndent()
        }
}

/** Present in a summary's coroutine context when it runs in the background and must not show progress. */
class QuietSummary : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<QuietSummary>
}

/** A request the provider rejected, inside an otherwise successful response, as too long. */
class ContextOverflowException(message: String) : java.io.IOException(message)

/**
 * The request the latest completed turn of a chat actually sent. A summary can reuse that request's
 * cache only while the chat still ends with that turn's reply and nothing in it has changed: the
 * first check records the chat's fingerprint, and later checks compare against it.
 */
object CompactionSnapshots {
    private class Entry(val snapshot: ComposerPredictionSnapshot, val replyTimestamp: Long, var fingerprint: Long?)

    /** A snapshot carries the whole request; only the most recently used chats keep one. */
    private const val MAX_CHATS = 3
    private val entries = LinkedHashMap<String, Entry>(8, 0.75f, true)
    /** Bumped whenever a chat's kept request stops being valid; a turn may only keep its own. */
    private val generations = HashMap<String, Long>()

    /** Called as a turn's request starts: drops the previous turn's request and returns this turn's token. */
    @Synchronized
    fun begin(chatId: String): Long {
        clear(chatId)
        return generations.getValue(chatId)
    }

    /** Keeps the request of the turn that got [generation], unless something newer started or changed the chat since. */
    @Synchronized
    fun put(chatId: String, snapshot: ComposerPredictionSnapshot, replyTimestamp: Long, generation: Long) {
        if (generations[chatId] != generation) return
        entries[chatId] = Entry(snapshot, replyTimestamp, null)
        while (entries.size > MAX_CHATS) {
            val eldest = entries.keys.iterator().next()
            entries.remove(eldest)
        }
    }

    /** The snapshot while [messages] (the chat's runtime history) is still what that turn left. */
    @Synchronized
    fun forConversation(chatId: String, messages: List<ChatMessage>): ComposerPredictionSnapshot? {
        val entry = entries[chatId] ?: return null
        val fingerprint = ContextCompaction.fingerprint(messages)
        entry.fingerprint?.let { return if (it == fingerprint) entry.snapshot else null }
        val last = messages.lastOrNull()
        if (last == null || last.sender != "ai" || last.timestamp < entry.replyTimestamp ||
            last.completedAt <= 0L || last.contentStream != null
        ) {
            entries.remove(chatId)
            return null
        }
        entry.fingerprint = fingerprint
        return entry.snapshot
    }

    @Synchronized
    fun clear(chatId: String) {
        entries.remove(chatId)
        generations[chatId] = (generations[chatId] ?: 0L) + 1L
    }
}

/**
 * A request rejected as too long is compacted and sent once more. The mark lasts until the user
 * sends a new message, so the automatic retry after the compaction cannot start a second one.
 */
object ContextOverflowRecovery {
    private val recovering = ConcurrentHashMap.newKeySet<String>()

    /** True when this is the first rejection since the user's latest message. */
    fun begin(chatId: String): Boolean = recovering.add(chatId)

    fun reset(chatId: String) {
        recovering.remove(chatId)
    }
}
