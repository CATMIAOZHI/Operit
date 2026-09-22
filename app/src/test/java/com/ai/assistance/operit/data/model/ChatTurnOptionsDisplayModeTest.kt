package com.ai.assistance.operit.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every stored user turn says where it came from in its display mode, and that mode is what the
 * permission reviewer reads to decide whether the turn is the owner's own request. A caller that
 * answers the owner, one that delivers a turn through a tool or a subagent host, and one that hides
 * the turn all have to land on the mode that says so.
 */
class ChatTurnOptionsDisplayModeTest {
    @Test
    fun anOrdinaryTurnIsTheOwners() {
        assertEquals(
            ChatMessageDisplayMode.NORMAL,
            ChatTurnOptions().userTurnDisplayMode(hidden = false),
        )
    }

    @Test
    fun aDeliveredTurnIsMarkedEvenWhenTheOwnerCouldReceiveIt() {
        assertEquals(
            ChatMessageDisplayMode.TOOL_DELIVERED,
            ChatTurnOptions(deliveredByTool = true).userTurnDisplayMode(hidden = false),
        )
    }

    @Test
    fun aCollaborationTurnKeepsItsOwnMode() {
        assertEquals(
            ChatMessageDisplayMode.COLLABORATION_TASK,
            ChatTurnOptions(isCollaborationAgent = true, deliveredByTool = true)
                .userTurnDisplayMode(hidden = false),
        )
    }

    @Test
    fun aHiddenTurnWinsOverTheDeliverer() {
        assertEquals(
            ChatMessageDisplayMode.HIDDEN_PLACEHOLDER,
            ChatTurnOptions(deliveredByTool = true).userTurnDisplayMode(hidden = true),
        )
        assertEquals(
            ChatMessageDisplayMode.HIDDEN_PLACEHOLDER,
            ChatTurnOptions().userTurnDisplayMode(hidden = true),
        )
    }
}
