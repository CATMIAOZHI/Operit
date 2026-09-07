package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.MutableSharedStreamImpl
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.TextStreamEventCarrier
import com.ai.assistance.operit.util.stream.TextStreamEventType
import com.ai.assistance.operit.util.stream.TextStreamRevisionTracker
import kotlinx.coroutines.CancellationException

@Composable
fun rememberRevisableTextStream(sourceStream: Stream<String>?): Stream<String>? {
    val carrier = sourceStream as? TextStreamEventCarrier ?: return sourceStream

    val initialDisplayStream = remember(sourceStream) {
        MutableSharedStreamImpl<String>(replay = Int.MAX_VALUE)
    }
    var displayStream by remember(sourceStream) {
        mutableStateOf<Stream<String>>(initialDisplayStream)
    }

    LaunchedEffect(sourceStream) {
        collectRevisableDisplayStream(sourceStream, carrier, initialDisplayStream) {
            displayStream = it
        }
    }

    // Only the persisted final message (sourceStream == null) switches to static rendering.
    // EOF can reach this observer before that message, while message.content is still stale.
    return displayStream
}

internal suspend fun collectRevisableDisplayStream(
    sourceStream: Stream<String>,
    carrier: TextStreamEventCarrier,
    initialDisplayStream: MutableSharedStreamImpl<String>,
    onReplacement: (Stream<String>) -> Unit,
) {
    val tracker = TextStreamRevisionTracker()
    var currentDisplayStream = initialDisplayStream
    var processedRevisionEventCount = 0
    var processedReplayCharCount = 0

    suspend fun drainDueRevisionEvents() {
        val events = carrier.eventChannel.replayCache
        while (processedRevisionEventCount < events.size) {
            val event = events[processedRevisionEventCount]
            if (event.replayCharCount?.let { it > processedReplayCharCount } == true) {
                break
            }
            processedRevisionEventCount++
            when (event.eventType) {
                TextStreamEventType.SAVEPOINT -> tracker.savepoint(event.id)
                TextStreamEventType.ROLLBACK -> {
                    val snapshot = tracker.rollback(event.id)?.toString() ?: continue
                    val previousDisplayStream = currentDisplayStream
                    val replacementStream =
                        MutableSharedStreamImpl<String>(replay = Int.MAX_VALUE)
                    if (snapshot.isNotEmpty()) {
                        replacementStream.emit(snapshot)
                    }
                    currentDisplayStream = replacementStream
                    onReplacement(replacementStream)
                    previousDisplayStream.close()
                }
            }
        }
    }

    var failure: Throwable? = null
    try {
        sourceStream.collect { chunk ->
            drainDueRevisionEvents()
            tracker.append(chunk)
            processedReplayCharCount += chunk.length
            currentDisplayStream.emit(chunk)
        }
        drainDueRevisionEvents()
    } catch (error: Exception) {
        failure = error
        if (error is CancellationException) throw error
        // The service owns the turn error; this observer must not crash the UI.
        AppLogger.w("RevisableTextStream", "Display stream ended with an error", error)
    } finally {
        currentDisplayStream.close(failure)
    }
}
