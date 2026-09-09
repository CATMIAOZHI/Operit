package com.ai.assistance.operit.data.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CodexRefreshCancellationTest {
    @Test
    fun cancellationDuringRotationStillPersistsNewToken() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val responseReady = CompletableDeferred<Unit>()
        var savedToken = "old"
        var continuedRequest = false
        val job = launch {
            persistCodexRefreshBeforeCancellation {
                requestStarted.complete(Unit)
                responseReady.await()
                savedToken = "rotated"
            }
            continuedRequest = true
        }
        requestStarted.await()
        job.cancel()
        responseReady.complete(Unit)
        job.join()
        assertEquals("rotated", savedToken)
        assertFalse(continuedRequest)
    }
}
