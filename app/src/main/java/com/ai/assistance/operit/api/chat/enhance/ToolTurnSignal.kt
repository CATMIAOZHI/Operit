package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.data.model.ToolResult

internal enum class ToolTurnSignal { CONTINUE, COMPLETE, INTERRUPTED }

internal fun resolveToolTurnSignal(results: List<ToolResult>): ToolTurnSignal = when {
    // A denied batch must not become successful just because a sibling requested completion.
    results.any { it.interruptTurn && !it.success } -> ToolTurnSignal.INTERRUPTED
    results.any { it.interruptTurn } -> ToolTurnSignal.COMPLETE
    else -> ToolTurnSignal.CONTINUE
}
