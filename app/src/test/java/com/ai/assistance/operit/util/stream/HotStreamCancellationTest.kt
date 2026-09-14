package com.ai.assistance.operit.util.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HotStreamCancellationTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun cancelledSubscriberDoesNotInterruptProducerOrOtherSubscriber() = runBlocking {
        val stream = MutableSharedStreamImpl<String>(replay = 10)
        val subscriber = launch(Dispatchers.Default) { stream.collect { } }
        val received = Channel<String>(Channel.UNLIMITED)
        val survivor = launch(Dispatchers.Default) { stream.collect { received.send(it) } }
        withTimeout(5000) { while (stream.subscriptionCount != 2) yield() }
        val lock = requireNotNull(stream.javaClass.getDeclaredField("stateLock")
            .apply { isAccessible = true }.get(stream))
        val channels = stream.javaClass.getDeclaredField("subscribers").apply { isAccessible = true }
            .get(stream) as Map<*, *>
        try {
            synchronized(lock) {
                subscriber.cancel()
                val deadline = System.nanoTime() + 5_000_000_000L
                // Hold the registry lock to reproduce a cancelled channel still present
                // in the producer's subscriber snapshot, independently of thread scheduling.
                while (channels.values.none { (it as Channel<*>).isClosedForSend } &&
                    System.nanoTime() < deadline) Thread.yield()
                assertTrue(channels.values.any { (it as Channel<*>).isClosedForSend })
                runBlocking { stream.emit("complete tool arguments") }
            }
            assertEquals("complete tool arguments", withTimeout(5000) { received.receive() })
            assertEquals(listOf("complete tool arguments"), stream.replayCache)
        } finally {
            subscriber.cancel()
            survivor.cancel()
            subscriber.join()
            survivor.join()
        }
    }

    @Test
    fun cancelledProducerCannotEmit() = runBlocking {
        val stream = MutableSharedStreamImpl<String>(replay = 10)
        var cancelled = false
        val producer = launch {
            coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            try {
                stream.emit("must not be delivered")
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        producer.join()
        assertTrue(cancelled)
        assertTrue(stream.replayCache.isEmpty())
    }
}
