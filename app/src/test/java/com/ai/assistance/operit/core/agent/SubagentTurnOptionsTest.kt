package com.ai.assistance.operit.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A run whose chat the next review continues has to keep its prompt verbatim, so the option that
 * keeps a chat out of the automatic summary has to survive the trip from the request to the turn.
 */
class SubagentTurnOptionsTest {
    private fun request(disableSummary: Boolean = false) =
        SubagentTaskRequest(
            parentChatId = "parent-chat",
            parentToolCallId = null,
            parentAgentName = null,
            title = "review",
            prompt = "prompt",
            subagentType = "reviewer",
            disableSummary = disableSummary,
        )

    private fun requestPinnedToConversation(providerSessionId: String) =
        SubagentTaskRequest(
            parentChatId = "parent-chat",
            parentToolCallId = null,
            parentAgentName = null,
            title = "review",
            prompt = "prompt",
            subagentType = "reviewer",
            providerSessionId = providerSessionId,
        )

    @Test
    fun aRunCanKeepItsChatOutOfTheSummary() {
        val options = request(disableSummary = true).toChatTurnOptions("system", "assistant")

        assertTrue(options.disableSummary)
    }

    @Test
    fun anOrdinaryRunLeavesTheSummaryAloneBecauseItIsNotContinued() {
        val options = request().toChatTurnOptions("system", "assistant")

        assertFalse(options.disableSummary)
    }

    @Test
    fun theTurnStillCarriesWhatItAlwaysDid() {
        val options = request(disableSummary = true).toChatTurnOptions("a system prompt", "reviewer")

        assertTrue(options.persistTurn)
        assertTrue(options.isSubTask)
        assertEquals("a system prompt", options.systemPromptOverride)
        assertEquals("reviewer", options.assistantRoleNameOverride)
    }

    /**
     * A review that runs in a reviewer chat of its own still belongs to the reviewed conversation, so
     * the identity it asks its provider under has to survive the trip as well: it is what keeps the
     * cached prompt prefix reusable across reviews.
     */
    @Test
    fun aRunCanPinTheConversationItBelongsTo() {
        val options =
            requestPinnedToConversation("reviewer-conversation")
                .toChatTurnOptions("system", "assistant")

        assertEquals("reviewer-conversation", options.providerSessionId)
    }

    @Test
    fun anOrdinaryRunKeepsItsOwnChatAsTheConversation() {
        val options = request().toChatTurnOptions("system", "assistant")

        assertEquals(null, options.providerSessionId)
    }
}
