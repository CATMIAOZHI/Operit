package com.ai.assistance.operit.api.chat.compaction

/**
 * Compacts a chat while one of its turns keeps running. The summary covers everything before the
 * turn and is saved right before it, so the turn itself stays word for word after the summary.
 * A turn checks it before each request it sends: from [ContextCompaction.PRECOMPUTE_RATIO] of the
 * compaction line a summary is written in the background, a finished one is swapped in at once,
 * and only a turn that reaches the line before its summary is done waits for it.
 */
interface TurnCompactor {
    /** Starts the background summary when none is being written for this turn. Never waits. */
    fun prepare()

    /** The finished summary's text once it is saved before the turn; null while it is not ready. */
    suspend fun takeReady(): String?

    /** Waits for the summary, starting it when needed, saves it and returns its text; null when it failed. */
    suspend fun await(): String?
}
