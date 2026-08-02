package com.ai.assistance.operit.util.stream

import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class RevisableTextStreamTest {
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
