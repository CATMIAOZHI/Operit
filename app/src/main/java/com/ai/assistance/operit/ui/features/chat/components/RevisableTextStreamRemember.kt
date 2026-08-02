package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.assistance.operit.util.stream.MutableSharedStream
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.TextStreamEventCarrier
import com.ai.assistance.operit.util.stream.TextStreamEventType
import com.ai.assistance.operit.util.stream.TextStreamRevisionTracker

@Composable
fun rememberRevisableTextStream(sourceStream: Stream<String>?): Stream<String>? {
    val carrier = sourceStream as? TextStreamEventCarrier ?: return sourceStream

    var displayStream by remember(sourceStream) {
        mutableStateOf<Stream<String>?>(MutableSharedStream(replay = Int.MAX_VALUE))
    }

    LaunchedEffect(sourceStream) {
        val tracker = TextStreamRevisionTracker()
        var currentDisplayStream = MutableSharedStream<String>(replay = Int.MAX_VALUE)
        var processedRevisionEventCount = 0
        var processedReplayCharCount = 0
        displayStream = currentDisplayStream

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
                            MutableSharedStream<String>(replay = Int.MAX_VALUE)
                        if (snapshot.isNotEmpty()) {
                            replacementStream.emit(snapshot)
                        }
                        currentDisplayStream = replacementStream
                        displayStream = replacementStream
                        previousDisplayStream.resetReplayCache()
                    }
                }
            }
        }

        try {
            sourceStream.collect { chunk ->
                drainDueRevisionEvents()
                tracker.append(chunk)
                processedReplayCharCount += chunk.length
                currentDisplayStream.emit(chunk)
            }
            drainDueRevisionEvents()
        } finally {
            currentDisplayStream.resetReplayCache()
            displayStream = null
        }
    }

    return displayStream
}
