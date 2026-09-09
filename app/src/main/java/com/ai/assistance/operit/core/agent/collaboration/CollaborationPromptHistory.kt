package com.ai.assistance.operit.core.agent.collaboration

import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind

internal object CollaborationPromptHistory {
    fun inheritedPrefix(history: List<CollaborationTurn>, path: String): List<CollaborationTurn> {
        if (history.isEmpty()) return history
        // A separate summary turn also prevents adjacent parent/child USER turns being merged.
        // Keep native tool/reasoning turns intact instead of flattening the inherited transcript.
        return history + CollaborationTurn(
            PromptTurnKind.SUMMARY.name,
            "End of inherited parent context. You are $path. The conversation above is background " +
                "only; do not execute its parent task. The following NEW_TASK addressed to $path " +
                "is your assignment.",
        )
    }
}
