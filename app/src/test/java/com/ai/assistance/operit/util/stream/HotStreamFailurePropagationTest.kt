package com.ai.assistance.operit.util.stream

import java.util.concurrent.ConcurrentLinkedQueue
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
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
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

    @Test
    fun toolContinuationFailureReachesActiveAndLateSubscribersWithoutCrashing() = runBlocking {
        val scope = rootScope()
        val tools = rootScope()
        val wasEnabled = StreamLogger.isEnabled
        StreamLogger.setEnabled(false)
        try {
            val failure = IOException("Command Code event too large")
            val release = CompletableDeferred<Unit>()
            val events = MutableSharedStreamImpl<TextStreamEvent>(replay = Int.MAX_VALUE)
            val shared = stream<String> {
                events.emit(TextStreamEvent(TextStreamEventType.SAVEPOINT, "turn"))
                emit("before tools")
                tools.async {
                    release.await()
                    emit("tool continuation")
                    throw failure
                }.await()
            }.withEventChannel(events).shareRevisable(scope, replay = Int.MAX_VALUE)

            val active = scope.async {
                runCatching { shared.collect { } }.exceptionOrNull()
            }
            withTimeout(5_000) { while (shared.subscriptionCount == 0) yield() }
            release.complete(Unit)
            // Coroutine stack-trace recovery may copy IOException and retain it as the cause.
            fun original(error: Throwable?): Throwable? =
                generateSequence(error) { it.cause }.lastOrNull()
            assertSame(failure, original(withTimeout(5_000) { active.await() }))
            assertSame(failure, original(runCatching { shared.collect { } }.exceptionOrNull()))
            assertSame(failure, original(runCatching {
                (shared as TextStreamEventCarrier).eventChannel.collect { }
            }.exceptionOrNull()))
            awaitScopeIdle(scope)
            awaitScopeIdle(tools)
            assertNothingEscaped(scope, "tool continuation")
        } finally {
            scope.cancel()
            tools.cancel()
            StreamLogger.setEnabled(wasEnabled)
        }
    }

    /**
     * Every failure that reached the process-wide uncaught handler of a test scope. A queue rather
     * than one slot, because the tests probe the handler to prove it is reachable and the probe's
     * own failure lands here too.
     */
    private val escaped = ConcurrentLinkedQueue<Throwable>()

    private fun rootScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, error -> escaped.add(error) }
        )

    /** Waits until every coroutine the operator started has finished, so a leak has been reported. */
    private suspend fun awaitScopeIdle(scope: CoroutineScope) {
        val job = scope.coroutineContext[Job]
        withTimeout(5_000) {
            while (job?.children?.any { it.isActive } == true) {
                yield()
            }
        }
    }

    /** LAZILY keeps one long-lived observer; waits until the upstream producer is done too. */
    private suspend fun awaitUpstreamFinished(scope: CoroutineScope) {
        val job = scope.coroutineContext[Job]
        withTimeout(5_000) {
            while ((job?.children?.count { it.isActive } ?: 0) > 1) {
                yield()
            }
        }
    }

    /**
     * Asserts that nothing but the probe reached the uncaught handler.
     *
     * That a failure did not escape cannot be checked by waiting a moment and reading an empty slot:
     * a handler that runs late, or never runs at all, reads exactly the same. The scope's handler is
     * therefore probed with a failure thrown on purpose, and the probe has to arrive. An unreachable
     * handler then fails the probe wait instead of quietly passing the assertion.
     */
    private suspend fun assertNothingEscaped(scope: CoroutineScope, what: String) {
        val probe = IllegalStateException("probe")
        scope.launch { throw probe }
        withTimeout(5_000) { while (escaped.none { it === probe }) yield() }
        // The scope's other coroutines are already finished; this margin only covers a handler that
        // the earlier failure scheduled and that is still on its way here.
        delay(50)
        val leaked = escaped.filter { it !== probe }
        assertTrue("$what reached the uncaught handler: $leaked", leaked.isEmpty())
    }

    @Test
    fun sharedStreamHandsUpstreamFailureToSubscribersWithoutReachingTheUncaughtHandler() =
        runBlocking {
            val scope = rootScope()
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
                assertNothingEscaped(scope, "the upstream failure")
            } finally {
                scope.cancel()
                StreamLogger.setEnabled(wasEnabled)
            }
        }

    @Test
    fun lazilyStartedSharedStreamKeepsTheUpstreamFailureInsideTheStream() = runBlocking {
        val scope = rootScope()
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
            assertNothingEscaped(scope, "the upstream failure")
        } finally {
            scope.cancel()
            StreamLogger.setEnabled(wasEnabled)
        }
    }

    @Test
    fun stateStreamDoesNotLeakUpstreamFailureToTheUncaughtHandler() = runBlocking {
        val scope = rootScope()
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
            assertNothingEscaped(scope, "the upstream failure")
        } finally {
            scope.cancel()
            StreamLogger.setEnabled(wasEnabled)
        }
    }

    @Test
    fun failingCompletionHandlerCannotEscapeTheProducerLaunch() = runBlocking {
        val scope = rootScope()
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
            assertTrue("completion handler must still run", completed.get())
            awaitScopeIdle(scope)
            assertNothingEscaped(scope, "the completion handler failure")
        } finally {
            scope.cancel()
            StreamLogger.setEnabled(wasEnabled)
        }
    }
}
