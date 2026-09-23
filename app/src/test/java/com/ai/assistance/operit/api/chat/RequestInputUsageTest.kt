package com.ai.assistance.operit.api.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import org.junit.Assert.*
import org.junit.Test

class RequestInputUsageTest {
    private val history = listOf(PromptTurn(PromptTurnKind.USER, "question"))
    private fun usage(input: Long? = null, complete: Boolean = false) =
        ProviderUsageSnapshot(totalInputTokens = input, completeSnapshot = complete, source = "test")

    @Test fun serverTotalIncludesCachedInputWithoutAddingItAgain() {
        val tracker = RequestInputUsage(history, null)
        assertNull(tracker.baseline(history, null))
        tracker.report(usage(460000).copy(cachedInputTokens = 450000), 1)
        assertEquals(460000L to 1, tracker.baseline(history + PromptTurn(PromptTurnKind.ASSISTANT, "new reply"), null))
        assertNull(tracker.baseline(listOf(PromptTurn(PromptTurnKind.USER, "compressed")), null))
    }

    @Test fun partialUpdatesKeepInputButNewAttemptsAndFullUnknownUsageDoNot() {
        val tracker = RequestInputUsage(history, null)
        tracker.report(usage(123), 1)
        tracker.report(usage().copy(outputTokens = 20), 1)
        assertEquals(123L to 1, tracker.baseline(history, null))
        tracker.report(usage(), 2)
        assertNull(tracker.baseline(history, null))
        tracker.report(usage(999), 1)
        assertNull(tracker.baseline(history, null))
        tracker.report(usage(456), 2)
        tracker.report(usage(complete = true), 2)
        assertNull(tracker.baseline(history, null))
    }
}
