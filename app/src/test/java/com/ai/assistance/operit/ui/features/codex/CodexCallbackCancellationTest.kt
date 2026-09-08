package com.ai.assistance.operit.ui.features.codex

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CodexCallbackCancellationTest {
    @Test(timeout = 5000)
    fun waitingWithoutBrowserCallbackCanTimeOutAndReleasePort() = runBlocking {
        val server = CodexOAuthLoopbackCallbackServer.open()
        val started = System.nanoTime()
        try {
            withTimeout(300) { server.awaitCallback() }
            fail("Expected callback timeout")
        } catch (_: TimeoutCancellationException) {
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3000)
        } finally {
            server.close()
        }
        CodexOAuthLoopbackCallbackServer.open().close()
    }
}
