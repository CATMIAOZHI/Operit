package com.ai.assistance.operit.util.stream

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HotStreamBufferTest {
    @Test fun closedStreamReplayDoesNotPublishAnActiveSubscription() = runBlocking {
        val stream = MutableSharedStreamImpl<Int>(replay = 1)
        stream.emit(1)
        stream.close()
        val resume = CompletableDeferred<Unit>()
        val reader = launch(start = CoroutineStart.UNDISPATCHED) {
            stream.collect(object : StreamCollector<Int> {
                override suspend fun emit(value: Int) {
                    assertEquals(1, value)
                    resume.await()
                }
            })
        }
        assertEquals(0, stream.subscriptionCount)
        assertEquals(0, stream.internalSubscriptionCountFlow.value)
        resume.complete(Unit)
        reader.join()
    }

    @Test fun slowSubscriberBackpressuresFiniteBufferAndReceivesEveryEvent() = runBlocking {
        withTimeout(5000) {
            val stream = MutableSharedStreamImpl<Int>()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val received = ArrayList<Int>()
            val reader = launch(start = CoroutineStart.UNDISPATCHED) {
                stream.collect(object : StreamCollector<Int> {
                    override suspend fun emit(value: Int) {
                        if (value == 0) { entered.complete(Unit); resume.await() }
                        received.add(value)
                    }
                })
            }
            stream.emit(0)
            entered.await()
            val producer = launch(start = CoroutineStart.UNDISPATCHED) {
                for (i in 1..1000) stream.emit(i)
                stream.close()
            }
            assertFalse(producer.isCompleted)
            assertFalse(stream.tryEmit(-1))
            resume.complete(Unit)
            producer.join()
            reader.join()
            assertEquals((0..1000).toList(), received)
        }
    }

    @Test fun cancellingSlowSubscriberReleasesProducerAndCloseWakesWaiters() = runBlocking {
        withTimeout(5000) {
            val stream = MutableSharedStreamImpl<Int>()
            val reader = launch(start = CoroutineStart.UNDISPATCHED) {
                stream.collect(object : StreamCollector<Int> {
                    override suspend fun emit(value: Int) { awaitCancellation() }
                })
            }
            val producer = launch { repeat(1000) { stream.emit(it) }; stream.close() }
            yield()
            reader.cancelAndJoin()
            producer.join()
            assertEquals(0, stream.subscriptionCount)
        }
    }

    @Test fun unlimitedReplayUsesCursorsAndResetDoesNotDropAnExistingReadersBacklog() = runBlocking {
        withTimeout(5000) {
            val stream = MutableSharedStreamImpl<Int>(replay = Int.MAX_VALUE)
            stream.emit(0)
            val resume = CompletableDeferred<Unit>()
            val seen = ArrayList<Int>()
            val reader = launch(start = CoroutineStart.UNDISPATCHED) {
                stream.collect(object : StreamCollector<Int> {
                    override suspend fun emit(value: Int) {
                        if (value == 0) resume.await()
                        seen.add(value)
                    }
                })
            }
            repeat(10_000) { assertTrue(stream.tryEmit(it + 1)) }
            stream.resetReplayCache()
            assertTrue(stream.replayCache.isEmpty())
            stream.emit(10_001)
            stream.close()
            resume.complete(Unit)
            reader.join()
            assertEquals((0..10_001).toList(), seen)
            val late = ArrayList<Int>()
            stream.collect(object : StreamCollector<Int> {
                override suspend fun emit(value: Int) { late.add(value) }
            })
            assertEquals(listOf(10_001), late)
        }
    }
}
