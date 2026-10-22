package com.ai.assistance.operit.api.chat.enhance

import java.util.concurrent.ConcurrentHashMap

/**
 * Background runs that read a long source through tools (the nightly alignment review, a memory
 * extraction) and must not stop when their context fills up. When the window is nearly full such
 * a run compacts its own history in memory and goes on in the same turn, like a subagent does.
 * Nothing is saved from the compaction: the run's reading position lives in the app, so a run
 * resumed from its stored transcript simply compacts again.
 */
object InPlaceCompaction {
    private val chats = ConcurrentHashMap<String, () -> Unit>()

    /** [onCompacted] runs after each compaction, for budgets that count what the context holds. */
    fun register(chatId: String, onCompacted: () -> Unit = {}) {
        chats[chatId] = onCompacted
    }

    fun unregister(chatId: String) {
        chats.remove(chatId)
    }

    fun contains(chatId: String?): Boolean = chatId != null && chats.containsKey(chatId)

    internal fun compacted(chatId: String) {
        runCatching { chats[chatId]?.invoke() }
    }

    internal const val SUMMARY_RULES =
        "This is a checkpoint for the same background run, which keeps going after it. Preserve the " +
            "assigned task and its rules, every reading position mentioned (message ids, cursors, page " +
            "ranges, what was committed and what was read but not yet recorded), findings not yet written " +
            "down, open threads, and what remains. Your own summarization instructions are not part of the " +
            "task. Never mark unfinished reading or unrecorded findings as done."

    internal const val RESUME_NOTE =
        "The checkpoint above is your own progress in this run, not a new task. Continue the task below " +
            "from where you stopped; do not redo work already committed. The app keeps your reading " +
            "position: when unsure where you are, call the reading tool again without a position."
}
