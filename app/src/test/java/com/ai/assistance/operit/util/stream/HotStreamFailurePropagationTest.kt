package com.ai.assistance.operit.util.stream

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the device crash seen when an automatic-review denial interrupted a turn.
 *
 * The denial is thrown as a control-flow interrupt, so it fails every stream that carries the
 * assistant response. When that failure escaped the producer coroutine of `share`/`state`, it
 * reached the process-wide uncaught handler and killed the app instead of only failing the turn.
 */
class HotStreamFailurePropagationTest {

    private fun rootScope(
        uncaught: AtomicReference<Throwable?>,
    ): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, error -> uncaught.set(error) }
        )

    /** Waits until every coroutine the operator started has finished, so a leak has been reported. */
    private suspend fun awaitScopeIdle(scope: CoroutineScope) {
        val job = scope.coroutineContext[Job]
        withTimeout(5_000) {
            while (job?.children?.any { it.isActive } == true) {
                yield()
            }
            // A failing child is marked finished just before its exception handler runs; give the
            // handler a bounded moment before the caller asserts that it saw nothing.
            delay(20)
        }
    }

    /** LAZILY keeps one long-lived observer; waits until the upstream producer is done too. */
    private suspend fun awaitUpstreamFinished(scope: CoroutineScope) {
        val job = scope.coroutineContext[Job]
        withTimeout(5_000) {
            while ((job?.children?.count { it.isActive } ?: 0) > 1) {
                yield()
            }
            delay(20)
        }
    }

    @Test
    fun sharedStreamHandsUpstreamFailureToSubscribersWithoutReachingTheUncaughtHandler() =
        runBlocking {
            val uncaught = AtomicReference<Throwable?>(null)
            val scope = rootScope(uncaught)
            // The failure paths log through android.util.Log, which the JVM test runtime does not
            // implement; a throwing logger would replace the failure under test.
            val wasEnabled = StreamLogger.isEnabled
            StreamLogger.setEnabled(false)
            try {
                val denial = IllegalStateException("Automatic permission review denied the action")
                val shared =
                    stream<String> {
                            emit("chunk")
                            throw denial
                        }
                        .share(scope = scope)

                val received = AtomicReference<Throwable?>(null)
                val subscriber =
                    launch(Dispatchers.Default) {
                        try {
                            shared.collect { }
                        } catch (error: Throwable) {
                            received.set(error)
                        }
                    }
                withTimeout(5_000) { subscriber.join() }
                withTimeout(5_000) { while (received.get() == null) yield() }
                assertSame(denial, received.get())

                // The producer is a root coroutine of `scope`; wait for it to finish so an
                // escaping failure would already have been reported.
                awaitScopeIdle(scope)
                assertNull("upstream failure escaped to the uncaught handler", uncaught.get())
            } finally {
                scope.cancel()
                StreamLogger.setEnabled(wasEnabled)
            }
        }

    @Test
    fun lazilyStartedSharedStreamKeepsTheUpstreamFailureInsideTheStream() = runBlocking {
        val uncaught = AtomicReference<Throwable?>(null)
        val scope = rootScope(uncaught)
        val wasEnabled = StreamLogger.isEnabled
        StreamLogger.setEnabled(false)
        try {
            val denial = IllegalStateException("Automatic permission review denied the action")
            val shared =
                stream<String> {
                        emit("chunk")
                        throw denial
                    }
                    .share(scope = scope, started = StreamStart.LAZILY)

            val received = AtomicReference<Throwable?>(null)
            val subscriber =
                launch(Dispatchers.Default) {
                    try {
                        shared.collect { }
                    } catch (error: Throwable) {
                        received.set(error)
                    }
                }
            withTimeout(5_000) { subscriber.join() }
            withTimeout(5_000) { while (received.get() == null) yield() }
            assertSame(denial, received.get())

            awaitUpstreamFinished(scope)
            assertNull("upstream failure escaped to the uncaught handler", uncaught.get())
        } finally {
            scope.cancel()
            StreamLogger.setEnabled(wasEnabled)
        }
    }

    @Test
    fun stateStreamDoesNotLeakUpstreamFailureToTheUncaughtHandler() = runBlocking {
        val uncaught = AtomicReference<Throwable?>(null)
        val scope = rootScope(uncaught)
        val wasEnabled = StreamLogger.isEnabled
        StreamLogger.setEnabled(false)
        try {
            val denial = IllegalStateException("Automatic permission review denied the action")
            val state =
                stream<Int> {
                        emit(1)
                        throw denial
                    }
                    .state(scope = scope, initialValue = 0)

            withTimeout(5_000) { while (state.value != 1) yield() }
            awaitScopeIdle(scope)
            assertNull("upstream failure escaped to the uncaught handler", uncaught.get())
        } finally {
            scope.cancel()
            StreamLogger.setEnabled(wasEnabled)
        }
    }

    @Test
    fun failingCompletionHandlerCannotEscapeTheProducerLaunch() = runBlocking {
        val uncaught = AtomicReference<Throwable?>(null)
        val scope = rootScope(uncaught)
        val wasEnabled = StreamLogger.isEnabled
        StreamLogger.setEnabled(false)
        try {
            val completed = AtomicBoolean(false)
            val shared =
                stream<String> { emit("chunk") }
                    .share(
                        scope = scope,
                        onComplete = {
                            completed.set(true)
                            throw IllegalStateException("cleanup failed")
                        },
                    )

            val subscriber =
                launch(Dispatchers.Default) {
                    shared.collect { }
                }
            withTimeout(5_000) { subscriber.join() }
            awaitScopeIdle(scope)
            assertTrue("completion handler must still run", completed.get())
            assertNull("completion handler failure escaped to the uncaught handler", uncaught.get())
        } finally {
            scope.cancel()
            StreamLogger.setEnabled(wasEnabled)
        }
    }
}
