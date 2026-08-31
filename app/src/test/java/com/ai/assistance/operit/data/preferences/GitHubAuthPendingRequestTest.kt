package com.ai.assistance.operit.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GitHubAuthPendingRequestTest {
    @Test
    fun mismatchedStateDoesNotAuthorizeConsumption() {
        assertSame(
            PendingGitHubOAuthRequestConsumption.StateMismatch,
            evaluatePendingOAuthRequestConsumption(
                pendingState = "expected",
                codeVerifier = "verifier",
                returnedState = "forged",
            )
        )
    }

    @Test
    fun matchingStateReturnsVerifierForAtomicConsumption() {
        val result = evaluatePendingOAuthRequestConsumption(
            pendingState = "expected",
            codeVerifier = "verifier",
            returnedState = "expected",
        )

        val consumed = result as PendingGitHubOAuthRequestConsumption.Consumed
        assertEquals("expected", consumed.request.state)
        assertEquals("verifier", consumed.request.codeVerifier)
    }

    @Test
    fun incompletePendingRequestIsMissing() {
        assertSame(
            PendingGitHubOAuthRequestConsumption.Missing,
            evaluatePendingOAuthRequestConsumption(
                pendingState = "expected",
                codeVerifier = null,
                returnedState = "expected",
            )
        )
    }
}
