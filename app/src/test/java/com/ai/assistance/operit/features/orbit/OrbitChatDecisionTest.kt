package com.ai.assistance.operit.features.orbit

import org.junit.Assert.*
import org.junit.Test

class OrbitChatDecisionTest {
    @Test fun reasonsBelongToTheTrackedChatAndAreClearedAfterTheTurn() {
        val turn = OrbitChatDecision.Turn()
        OrbitChatDecision.begin("chat", turn)
        try {
            assertFalse(OrbitChatDecision.record("other", "wrong turn"))
            assertFalse(OrbitChatDecision.record(null, "no chat"))
            assertTrue(OrbitChatDecision.record("chat", "not useful now", silent = true))
            assertTrue(turn.silenceRequested)
            assertEquals("not useful now", turn.reason)
            assertFalse(turn.confirmed)
            turn.accepting = false
            assertFalse(OrbitChatDecision.record("chat", "a later user's reason"))
            assertEquals("not useful now", turn.reason)
        } finally { OrbitChatDecision.end("chat", turn) }
        assertFalse(OrbitChatDecision.record("chat", "late"))
    }

    @Test fun replacingATurnDoesNotLetTheOldTurnRemoveItsSuccessor() {
        val old = OrbitChatDecision.Turn()
        val new = OrbitChatDecision.Turn()
        OrbitChatDecision.begin("chat", old)
        OrbitChatDecision.begin("chat", new)
        OrbitChatDecision.end("chat", old)
        try {
            assertTrue(OrbitChatDecision.record("chat", "new reason"))
            assertEquals("", old.reason)
            assertEquals("new reason", new.reason)
        } finally { OrbitChatDecision.end("chat", new) }
    }

    @Test fun nextUserTurnClosesReasonsBeforeThePreviousTerminalSignalArrives() {
        val turn = OrbitChatDecision.Turn("scheduled-turn")
        OrbitChatDecision.begin("chat", turn)
        try {
            OrbitChatDecision.onTurnStarted("chat", "scheduled-turn")
            assertTrue(OrbitChatDecision.record("chat", "task reason"))
            OrbitChatDecision.onTurnStarted("other", null)
            assertTrue(turn.accepting)
            OrbitChatDecision.onTurnStarted("chat", null)
            assertFalse(OrbitChatDecision.record("chat", "later user turn"))
            assertEquals("task reason", turn.reason)
        } finally { OrbitChatDecision.end("chat", turn) }
    }
}
