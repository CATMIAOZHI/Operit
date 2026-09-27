package com.ai.assistance.operit.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandResourcesPolicyTest {
    @Test
    fun neverDeclinedStillPrompts() {
        // A missing decline must not look like a recent one, whatever the clock reads.
        assertFalse(shouldSuppressPrompt(null, 1_000L))
        assertFalse(shouldSuppressPrompt(null, 5_000_000L))
    }

    @Test
    fun declineSilencesItsOwnRetryLoopOnly() {
        val declinedAt = 1_000_000L
        assertTrue(shouldSuppressPrompt(declinedAt, declinedAt))
        assertTrue(shouldSuppressPrompt(declinedAt, declinedAt + DECLINE_SUPPRESSION_MS - 1))
        assertFalse(shouldSuppressPrompt(declinedAt, declinedAt + DECLINE_SUPPRESSION_MS))
        assertFalse(shouldSuppressPrompt(declinedAt, declinedAt + DECLINE_SUPPRESSION_MS + 60_000L))
    }
}
