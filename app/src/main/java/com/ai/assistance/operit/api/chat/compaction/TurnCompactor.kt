package com.ai.assistance.operit.api.chat.compaction

import com.ai.assistance.operit.core.chat.hooks.PromptTurn

/**
 * Compacts a chat while one of its turns keeps running, after OpenCode's compaction. The turn
 * checks it before each request it sends: from [ContextCompaction.PRECOMPUTE_RATIO] of the
 * compaction line a summary is written in the background and held; it is swapped in only once a
 * request reaches the line (swapping earlier would throw away the provider's prompt cache), and
 * the turn waits there only when it is not written yet.
 *
 * The first summary of a turn covers the chat before the turn and is saved right before it
 * ([prepare], [takeReady], [await]). After that, a summary covers the request up to the turn's
 * latest reply, finished steps of the turn included, and that reply stays word for word
 * ([prepareSteps], [awaitSteps]); so a long turn can be compacted as often as it needs.
 */
interface TurnCompactor {
    /** The summary of the chat before the turn may still come; false once it was handed out, failed or is not needed. */
    val beforeTurnPending: Boolean

    /** Starts the background summary of the chat before the turn when none is being written. Never waits. */
    fun prepare()

    /** That summary's text once it is saved before the turn; null while it is not ready. */
    suspend fun takeReady(): String?

    /** Waits for that summary, starting it when needed, saves it and returns its text; null when it failed. */
    suspend fun await(): String?

    /**
     * Starts a background summary of [history] before [anchor] (the start of the latest reply),
     * unless the one being held still fits: it still covers the start of [history] and leaves at
     * most [tailLimit] tokens after it. Never waits.
     */
    fun prepareSteps(history: List<PromptTurn>, anchor: Int, tailLimit: Int)

    /** The held summary of the start of [history] when it is written and still fits; null otherwise. */
    fun takeReadySteps(history: List<PromptTurn>, tailLimit: Int): StepSummary?

    /**
     * A summary of the start of [history]: the held one while it still fits (see [prepareSteps]),
     * waited for when still being written; otherwise one of [history] before [anchor], written now.
     * Null when it failed.
     */
    suspend fun awaitSteps(history: List<PromptTurn>, anchor: Int, tailLimit: Int): StepSummary?

    /** The turn is over: a summary of its steps no one will use stops. */
    fun endTurn()

    /** A summary covering the first [covered] entries of the request it was asked for. */
    class StepSummary(val covered: Int, val text: String)
}
