package com.ai.assistance.operit.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentModelFallbackPolicyTest {
    @Test
    fun acceptsExplicitBillingQuotaAccountAndModelFailures() {
        assertTrue(
            SubagentModelFallbackPolicy.isExplicitModelAvailabilityFailure(
                "HTTP 429: insufficient_quota"
            )
        )
        assertTrue(
            SubagentModelFallbackPolicy.isExplicitModelAvailabilityFailure(
                "The account is disabled"
            )
        )
        assertTrue(
            SubagentModelFallbackPolicy.isExplicitModelAvailabilityFailure(
                "模型不可用"
            )
        )
    }

    @Test
    fun rejectsGenericNetworkAndRateLimitFailures() {
        assertFalse(
            SubagentModelFallbackPolicy.isExplicitModelAvailabilityFailure(
                "Socket timeout while connecting"
            )
        )
        assertFalse(
            SubagentModelFallbackPolicy.isExplicitModelAvailabilityFailure(
                "HTTP 429: rate limit exceeded"
            )
        )
        assertFalse(
            SubagentModelFallbackPolicy.isExplicitModelAvailabilityFailure(
                "HTTP 503: service unavailable"
            )
        )
    }
}
