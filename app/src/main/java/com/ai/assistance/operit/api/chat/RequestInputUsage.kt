package com.ai.assistance.operit.api.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot

/** Only an unchanged request prefix can reuse the server's input count. */
internal class RequestInputUsage(
    private val history: List<PromptTurn>,
    private val tools: List<ToolPrompt>?,
) {
    private var attempt = 0
    private var input: Long? = null

    @Synchronized
    fun actualInput(): Long? = input

    @Synchronized
    fun report(usage: ProviderUsageSnapshot, attemptNumber: Int) {
        if (attemptNumber < attempt) return
        if (attemptNumber != attempt || usage.completeSnapshot) input = null
        attempt = attemptNumber
        usage.totalInputTokens?.takeIf { it >= 0 }?.let { input = it }
    }

    @Synchronized
    fun baseline(nextHistory: List<PromptTurn>, nextTools: List<ToolPrompt>?): Pair<Long, Int>? {
        val actual = input ?: return null
        if (tools != nextTools || nextHistory.size < history.size ||
            nextHistory.take(history.size) != history) return null
        return actual to history.size
    }
}
