package com.ai.assistance.operit.util.stream

import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class RevisableTextStreamTest {
    @Test
    fun failedToolTurnReplaysPartialOutputAndCauseWithoutUncaughtProducerFailure() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            StreamLogger.setEnabled(false)
            val uncaught = mutableListOf<Throwable>()
            val producerScope = CoroutineScope(
                coroutineContext.minusKey(Job) + SupervisorJob() +
                    CoroutineExceptionHandler { _, error -> uncaught += error }
            )
            try {
                val failure = IllegalStateException("Automatic approval stopped this turn")
                val events = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
                val ready = CompletableDeferred<Unit>()
                val upstream = stream<String> {
                    emit("partial answer")
                    events.emit(TextStreamEvent(TextStreamEventType.SAVEPOINT, "tool"))
                    emit("<tool_result status=\"error\">denied</tool_result>")
                    throw failure
                }.withEventChannel(events)
                val shared = upstream.shareRevisable(producerScope, replay = Int.MAX_VALUE,
                    onComplete = { ready.complete(Unit) })
                ready.await()
                repeat(2) {
                    val text = mutableListOf<String>()
                    val caught = runCatching { shared.collect { text += it } }.exceptionOrNull()
                    assertSame(failure, caught)
                    assertEquals(listOf("partial answer", "<tool_result status=\"error\">denied</tool_result>"), text)
                }
                val eventStream = (shared as TextStreamEventCarrier).eventChannel
                assertSame(failure, runCatching { eventStream.collect {} }.exceptionOrNull())
                assertEquals(listOf("tool"), eventStream.replayCache.map { it.id })
                yield()
                assertTrue(uncaught.isEmpty())
            } finally {
                producerScope.cancel()
                StreamLogger.setEnabled(true)
            }
        }
    }

    @Test
    fun sharedTextNeverOvertakesItsRevisionEvents() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            StreamLogger.setEnabled(false)
            try {
                val events = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
                val upstream =
                    stream<String> {
                            emit("before")
                            events.emit(TextStreamEvent(TextStreamEventType.SAVEPOINT, "retry"))
                            emit("discarded")
                            events.emit(TextStreamEvent(TextStreamEventType.ROLLBACK, "retry"))
                            emit("after")
                        }
                        .withEventChannel(events)
                val shared = upstream.shareRevisable(this, replay = Int.MAX_VALUE)
                val carrier = shared as TextStreamEventCarrier
                val replayedText = mutableListOf<String>()

                shared.collect { text ->
                    replayedText += text
                }

                assertEquals(listOf("before", "discarded", "after"), replayedText)
                assertEquals(
                    listOf(6, 15),
                    carrier.eventChannel.replayCache.map { event -> event.replayCharCount },
                )

                val tracker = TextStreamRevisionTracker()
                val replayEvents = carrier.eventChannel.replayCache
                var processedChars = 0
                var eventIndex = 0
                fun drainDueEvents() {
                    while (eventIndex < replayEvents.size &&
                        replayEvents[eventIndex].replayCharCount!! <= processedChars
                    ) {
                        val event = replayEvents[eventIndex++]
                        when (event.eventType) {
                            TextStreamEventType.SAVEPOINT -> tracker.savepoint(event.id)
                            TextStreamEventType.ROLLBACK -> tracker.rollback(event.id)
                        }
                    }
                }
                replayedText.forEach { text ->
                    drainDueEvents()
                    tracker.append(text)
                    processedChars += text.length
                }
                drainDueEvents()

                assertEquals("beforeafter", tracker.currentContent().toString())
            } finally {
                StreamLogger.setEnabled(true)
            }
        }
    }
}
