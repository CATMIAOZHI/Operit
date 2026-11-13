package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.data.model.ToolResult

internal enum class ToolTurnSignal { CONTINUE, COMPLETE, INTERRUPTED }

internal fun resolveToolTurnSignal(results: List<ToolResult>): ToolTurnSignal = when {
    // A denied batch must not become successful just because a sibling requested completion.
    results.any { it.interruptTurn && !it.success } -> ToolTurnSignal.INTERRUPTED
    results.any { it.interruptTurn } -> ToolTurnSignal.COMPLETE
    else -> ToolTurnSignal.CONTINUE
}

/** How many failed calls of a turn's finishing tool go back to the model before the turn ends anyway. */
internal const val TERMINAL_TOOL_RETRY_LIMIT = 2

/**
 * A finishing tool that failed saved nothing, so ending the turn on it loses the work behind it.
 * The model reads the error and may call the tool again; [failuresBefore] counts the failed calls
 * already answered that way in this turn.
 */
internal fun terminalToolMayRetry(results: List<ToolResult>, failuresBefore: Int): Boolean =
    failuresBefore < TERMINAL_TOOL_RETRY_LIMIT && results.isNotEmpty() &&
        results.none { it.success || it.interruptTurn }
