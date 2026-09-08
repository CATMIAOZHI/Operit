package com.ai.assistance.operit.util.stream

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DisplayTextStreamTest {
    @Test
    fun returningRendererReceivesBoundedBatchesAndCompleteHistory() = runBlocking {
        val stream = DisplayTextStream()
        repeat(100_000) { stream.emit("abcdef") }
        stream.close()
        val result = StringBuilder()
        var batches = 0
        stream.collect {
            assertTrue(it.length <= 16 * 1024)
            result.append(it)
            batches++
        }
        assertEquals("abcdef".repeat(100_000), result.toString())
        assertTrue(batches < 100)
    }

    @Test
    fun slowSubscriberAndReturningSubscriberBothReceiveAllText() = runBlocking {
        val stream = DisplayTextStream()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        stream.emit("prefix")
        val slow = async(start = CoroutineStart.UNDISPATCHED) {
            val result = StringBuilder()
            stream.collect {
                result.append(it)
                entered.complete(Unit)
                resume.await()
            }
            result.toString()
        }
        entered.await()
        repeat(20_000) { stream.emit("x") }
        stream.close()
        val replay = StringBuilder()
        stream.collect { replay.append(it) }
        resume.complete(Unit)
        withTimeout(2_000) {
            assertEquals("prefix" + "x".repeat(20_000), slow.await())
            assertEquals(slow.await(), replay.toString())
        }
    }

    @Test
    fun failureIsDeliveredAfterBufferedText() = runBlocking {
        val stream = DisplayTextStream()
        val failure = IllegalStateException("test failure")
        stream.emit("kept")
        stream.close(failure)
        var text = ""
        val caught = runCatching { stream.collect { text += it } }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals("kept", text)
    }
}
