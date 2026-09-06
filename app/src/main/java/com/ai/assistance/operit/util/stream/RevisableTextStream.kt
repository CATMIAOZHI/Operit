package com.ai.assistance.operit.util.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class TextStreamEvent(
    val eventType: TextStreamEventType,
    val id: String,
    val replayCharCount: Int? = null,
)

enum class TextStreamEventType {
    SAVEPOINT,
    ROLLBACK
}

interface TextStreamEventCarrier {
    val eventChannel: SharedStream<TextStreamEvent>
}

interface RevisableTextStream : Stream<String>, TextStreamEventCarrier

interface RevisableSharedTextStream : SharedStream<String>, RevisableTextStream

interface RevisableCharStream : Stream<Char>, TextStreamEventCarrier

private class DelegatingRevisableTextStream(
    private val upstream: Stream<String>,
    override val eventChannel: SharedStream<TextStreamEvent>
) : RevisableTextStream {
    override val isLocked: Boolean
        get() = upstream.isLocked

    override val bufferedCount: Int
        get() = upstream.bufferedCount

    override suspend fun lock() {
        upstream.lock()
    }

    override suspend fun unlock() {
        upstream.unlock()
    }

    override fun clearBuffer() {
        upstream.clearBuffer()
    }

    override suspend fun collect(collector: StreamCollector<String>) {
        upstream.collect(collector)
    }
}

private class DelegatingRevisableSharedTextStream(
    private val upstream: SharedStream<String>,
    override val eventChannel: SharedStream<TextStreamEvent>
) : RevisableSharedTextStream {
    override val isLocked: Boolean
        get() = upstream.isLocked

    override val bufferedCount: Int
        get() = upstream.bufferedCount

    override val subscriptionCount: Int
        get() = upstream.subscriptionCount

    override val replayCache: List<String>
        get() = upstream.replayCache

    override suspend fun lock() {
        upstream.lock()
    }

    override suspend fun unlock() {
        upstream.unlock()
    }

    override fun clearBuffer() {
        upstream.clearBuffer()
    }

    override suspend fun collect(collector: StreamCollector<String>) {
        upstream.collect(collector)
    }
}

private class DelegatingRevisableCharStream(
    private val upstream: Stream<Char>,
    override val eventChannel: SharedStream<TextStreamEvent>
) : RevisableCharStream {
    override val isLocked: Boolean
        get() = upstream.isLocked

    override val bufferedCount: Int
        get() = upstream.bufferedCount

    override suspend fun lock() {
        upstream.lock()
    }

    override suspend fun unlock() {
        upstream.unlock()
    }

    override fun clearBuffer() {
        upstream.clearBuffer()
    }

    override suspend fun collect(collector: StreamCollector<Char>) {
        upstream.collect(collector)
    }
}

fun Stream<String>.withEventChannel(eventChannel: SharedStream<TextStreamEvent>): Stream<String> {
    if (this is RevisableTextStream && this.eventChannel === eventChannel) {
        return this
    }
    return DelegatingRevisableTextStream(this, eventChannel)
}

fun SharedStream<String>.withEventChannel(
    eventChannel: SharedStream<TextStreamEvent>
): SharedStream<String> {
    if (this is RevisableSharedTextStream && this.eventChannel === eventChannel) {
        return this
    }
    return DelegatingRevisableSharedTextStream(this, eventChannel)
}

fun Stream<Char>.withTextEventChannel(eventChannel: SharedStream<TextStreamEvent>): Stream<Char> {
    if (this is RevisableCharStream && this.eventChannel === eventChannel) {
        return this
    }
    return DelegatingRevisableCharStream(this, eventChannel)
}

fun Stream<String>.shareRevisable(
    scope: CoroutineScope,
    replay: Int = 0,
    started: StreamStart = StreamStart.EAGERLY,
    onComplete: suspend () -> Unit = {}
): SharedStream<String> {
    val carrier =
        this as? TextStreamEventCarrier
            ?: return share(
                scope = scope,
                replay = replay,
                started = started,
                onComplete = onComplete,
            )
    if (started != StreamStart.EAGERLY) {
        val sharedTextStream =
            share(scope = scope, replay = replay, started = started, onComplete = onComplete)
        val sharedEventStream =
            carrier.eventChannel.share(scope = scope, replay = Int.MAX_VALUE, started = started)
        return sharedTextStream.withEventChannel(sharedEventStream)
    }

    val sharedTextStream = MutableSharedStreamImpl<String>(replay = replay)
    val sharedEventStream = MutableSharedStreamImpl<TextStreamEvent>(replay = Int.MAX_VALUE)
    scope.launch {
        var processedEventCount = 0
        var emittedTextCharCount = 0

        suspend fun drainEvents() {
            val events = carrier.eventChannel.replayCache
            while (processedEventCount < events.size) {
                sharedEventStream.emit(
                    events[processedEventCount++].copy(
                        replayCharCount = emittedTextCharCount,
                    )
                )
            }
        }

        var failure: Throwable? = null
        try {
            this@shareRevisable.collect { value ->
                // The upstream publishes revision events before the text they govern. Copy both
                // through one coroutine so every downstream text replay observes its preceding
                // savepoint/rollback event first.
                drainEvents()
                sharedTextStream.emit(value)
                emittedTextCharCount += value.length
            }
            drainEvents()
        } catch (error: Throwable) {
            failure = error
            // The closed streams deliver this cause to their consumers, including replay.
            // Throwing it again from this root launch would also invoke the uncaught handler.
            if (error is kotlinx.coroutines.CancellationException) throw error
        } finally {
            sharedEventStream.close(failure)
            sharedTextStream.close(failure)
            onComplete()
        }
    }
    return sharedTextStream.withEventChannel(sharedEventStream)
}
