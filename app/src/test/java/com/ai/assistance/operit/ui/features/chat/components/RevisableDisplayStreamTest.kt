package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.MutableSharedStreamImpl
import com.ai.assistance.operit.util.stream.DisplayTextStream
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.TextStreamEvent
import com.ai.assistance.operit.util.stream.TextStreamEventCarrier
import com.ai.assistance.operit.util.stream.TextStreamEventType
import com.ai.assistance.operit.util.stream.withEventChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class RevisableDisplayStreamTest {
    @Test
    fun eofKeepsCompleteAnswerAvailableUntilFinalMessageArrives() = runBlocking {
        val source = MutableSharedStreamImpl<String>(replay = Int.MAX_VALUE)
        val events = MutableSharedStreamImpl<TextStreamEvent>(replay = Int.MAX_VALUE)
        val input = source.withEventChannel(events)
        val display = DisplayTextStream()
        val visible = async(start = CoroutineStart.UNDISPATCHED) { display.readText() }
        val copying = launch(start = CoroutineStart.UNDISPATCHED) {
            collectRevisableDisplayStream(input, input as TextStreamEventCarrier, display) {
                fail("EOF must not replace the displayed stream")
            }
        }

        source.emit("Full answer\n")
        source.emit("including its last line.")
        source.close()
        withTimeout(2_000) {
            copying.join()
            assertEquals("Full answer\nincluding its last line.", visible.await())
            // The service has not yet published the static ChatMessage. A re-subscribing
            // renderer must still get the answer, rather than empty text or an open stream.
            assertEquals("Full answer\nincluding its last line.", display.readText())
        }
    }

    @Test
    fun rollbackClosesOldParserAndReplaysOnlyRevisedAnswer() = runBlocking {
        val source = MutableSharedStreamImpl<String>(replay = Int.MAX_VALUE)
        val events = MutableSharedStreamImpl<TextStreamEvent>(replay = Int.MAX_VALUE)
        events.emit(TextStreamEvent(TextStreamEventType.SAVEPOINT, "retry", 4))
        events.emit(TextStreamEvent(TextStreamEventType.ROLLBACK, "retry", 7))
        source.emit("keep")
        source.emit("bad")
        source.emit("good")
        source.close()
        val input = source.withEventChannel(events)
        val initial = DisplayTextStream()
        val oldParser = async(start = CoroutineStart.UNDISPATCHED) { initial.readText() }
        var replacement: Stream<String>? = null

        collectRevisableDisplayStream(input, input as TextStreamEventCarrier, initial) {
            replacement = it
        }

        withTimeout(2_000) {
            assertEquals("keepbad", oldParser.await())
            assertEquals("keepgood", checkNotNull(replacement).readText())
        }
    }

    @Test
    fun failedTurnRetainsPartialAnswerWithoutThrowingFromUiObserver() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val source = MutableSharedStreamImpl<String>(replay = Int.MAX_VALUE)
            val input = source.withEventChannel(
                MutableSharedStreamImpl<TextStreamEvent>(replay = Int.MAX_VALUE)
            )
            val failure = IllegalStateException("Tool interrupted this turn")
            source.emit("Partial answer")
            source.close(failure)
            val display = DisplayTextStream()
            collectRevisableDisplayStream(input, input as TextStreamEventCarrier, display) {}
            var replay = ""
            val caught = withTimeout(2_000) {
                runCatching { display.collect { replay += it } }.exceptionOrNull()
            }
            assertEquals("Partial answer", replay)
            assertSame(failure, caught)
        }
    }

    @Test
    fun cancellationTerminatesDisplayCollectorAndPreservesAlreadyRenderedText() = runBlocking {
        val source = MutableSharedStreamImpl<String>(replay = Int.MAX_VALUE)
        source.emit("Already visible")
        val input = source.withEventChannel(
            MutableSharedStreamImpl<TextStreamEvent>(replay = Int.MAX_VALUE)
        )
        val display = DisplayTextStream()
        val copying = launch(start = CoroutineStart.UNDISPATCHED) {
            collectRevisableDisplayStream(input, input as TextStreamEventCarrier, display) {}
        }
        copying.cancelAndJoin()
        var replay = ""
        val caught = withTimeout(2_000) {
            runCatching { display.collect { replay += it } }.exceptionOrNull()
        }
        assertTrue(copying.isCancelled)
        assertEquals("Already visible", replay)
        assertTrue(caught is CancellationException)
    }

    private suspend fun Stream<String>.readText(): String {
        val text = StringBuilder()
        collect { text.append(it) }
        return text.toString()
    }
}
