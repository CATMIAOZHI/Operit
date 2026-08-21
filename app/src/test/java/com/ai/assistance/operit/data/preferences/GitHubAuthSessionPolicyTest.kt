package com.ai.assistance.operit.data.preferences

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubAuthSessionPolicyTest {
    @Test
    fun nonExpiringCurrentSessionRemainsUsable() {
        assertTrue(
            isGitHubAuthSessionUsable(
                isLoggedIn = true,
                authVersionCurrent = true,
                scopesCurrent = true,
                expiresAtMillis = null,
                nowMillis = 1_000L,
            )
        )
    }

    @Test
    fun expiringSessionStopsBeingUsableAtRecordedBoundary() {
        assertTrue(
            isGitHubAuthSessionUsable(
                isLoggedIn = true,
                authVersionCurrent = true,
                scopesCurrent = true,
                expiresAtMillis = 2_000L,
                nowMillis = 1_999L,
            )
        )
        assertFalse(
            isGitHubAuthSessionUsable(
                isLoggedIn = true,
                authVersionCurrent = true,
                scopesCurrent = true,
                expiresAtMillis = 2_000L,
                nowMillis = 2_000L,
            )
        )
    }

    @Test
    fun staleVersionOrScopesRemainUnusable() {
        assertFalse(
            isGitHubAuthSessionUsable(
                isLoggedIn = true,
                authVersionCurrent = false,
                scopesCurrent = true,
                expiresAtMillis = null,
                nowMillis = 1_000L,
            )
        )
        assertFalse(
            isGitHubAuthSessionUsable(
                isLoggedIn = true,
                authVersionCurrent = true,
                scopesCurrent = false,
                expiresAtMillis = null,
                nowMillis = 1_000L,
            )
        )
    }

    @Test
    fun expirySignalRechecksWallClockAfterForwardJump() = runTest {
        var wallClockMillis = 1_000L
        var signaled = false
        val job = launch {
            githubSessionClockSignalFlow(
                expiresAtMillis = 120_000L,
                nowMillis = { wallClockMillis },
            ).first()
            signaled = true
        }
        runCurrent()
        assertFalse(signaled)

        wallClockMillis = 120_000L
        advanceTimeBy(GITHUB_SESSION_EXPIRY_RECHECK_INTERVAL_MILLIS)
        runCurrent()

        assertTrue(signaled)
        assertTrue(job.isCompleted)
    }

    @Test
    fun clockSignalRechecksSessionThatStartedPastExpiryAfterBackwardJump() = runTest {
        var wallClockMillis = 150_000L
        var signaled = false
        val job = launch {
            githubSessionClockSignalFlow(
                expiresAtMillis = 120_000L,
                nowMillis = { wallClockMillis },
            ).first()
            signaled = true
        }
        runCurrent()
        assertFalse(signaled)

        wallClockMillis = 100_000L
        advanceTimeBy(GITHUB_SESSION_EXPIRY_RECHECK_INTERVAL_MILLIS)
        runCurrent()

        assertTrue(signaled)
        assertTrue(job.isCompleted)
    }

    @Test
    fun cancellingExpirySignalStopsFurtherRechecks() = runTest {
        var signaled = false
        val job = launch {
            githubSessionClockSignalFlow(
                expiresAtMillis = 120_000L,
                nowMillis = { 1_000L },
            ).collect { signaled = true }
        }
        runCurrent()

        job.cancel()
        advanceTimeBy(GITHUB_SESSION_EXPIRY_RECHECK_INTERVAL_MILLIS * 2)
        runCurrent()

        assertFalse(signaled)
        assertTrue(job.isCancelled)
    }
}
