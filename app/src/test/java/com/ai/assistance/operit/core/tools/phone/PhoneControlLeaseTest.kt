package com.ai.assistance.operit.core.tools.phone

import org.junit.Assert.*
import org.junit.Test

class PhoneControlLeaseTest {
    private val owner = PhoneControlLease.Owner("chat", "turn")
    @Test fun startupFailureCanRetryButCannotUndoUserStop() {
        val lease = PhoneControlLease()
        lease.register("turn")
        lease.begin(owner, "failed")
        assertNotNull(lease.abortStart("failed"))
        lease.begin(owner, "retry")
        lease.stop("retry")
        assertNull(lease.abortStart("retry"))
        fails { lease.begin(owner, "after-stop") }
    }
    private fun fails(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalStateException) { }
    }

    @Test fun stoppedTurnCannotRestartButNextTurnCan() {
        val lease = PhoneControlLease()
        lease.register("turn")
        lease.begin(owner, "session")
        lease.stop()
        fails { lease.begin(owner, "late-model-response") }
        lease.finish("turn")
        fails { lease.begin(owner, "after-completion") }
        lease.register("next")
        lease.begin(owner.copy(turn = "next"), "next-session")
    }

    @Test fun observationsAreSingleUseAndBoundToOwner() {
        val lease = PhoneControlLease()
        lease.register("turn")
        lease.begin(owner, "session")
        lease.observed(owner, "session", "screen")
        fails { lease.consume(owner.copy(chat = "other"), "session", "screen") }
        lease.consume(owner, "session", "screen")
        fails { lease.consume(owner, "session", "screen") }
        lease.observed(owner, "session", "screen2")
        fails { lease.consume(owner, "session", "screen") }
        lease.consume(owner, "session", "screen2")
    }

    @Test fun unrelatedTurnCompletionCannotReleaseControl() {
        val lease = PhoneControlLease()
        lease.register("turn")
        lease.register("other")
        lease.begin(owner, "session")
        assertNull(lease.finish("other"))
        assertEquals("session", lease.requireSession(owner, "session").id)
        assertEquals("session", lease.finish("turn")?.id)
        assertNull(lease.active())
        fails { lease.requireSession(owner, "session") }
    }

    @Test fun twoChatsCannotAcquireThePhoneConcurrently() {
        val lease = PhoneControlLease()
        lease.register("turn")
        lease.register("other")
        lease.begin(owner, "session")
        fails { lease.begin(PhoneControlLease.Owner("other", "other"), "second") }
        assertNull(lease.stop("second"))
        assertEquals("session", lease.active()?.id)
    }
}
