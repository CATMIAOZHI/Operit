package com.ai.assistance.operit.util.stream

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.ai.assistance.operit.util.MemoryCounters
import com.ai.assistance.operit.util.MemoryDiagnosticMetrics
import com.ai.assistance.operit.util.MemoryMetricSource

/** 共享Stream接口，类似于SharedFlow */
interface SharedStream<T> : Stream<T> {
    /** 当前订阅者数量 */
    val subscriptionCount: Int

    /** 重放缓存大小 */
    val replayCache: List<T>
}

/** 可变共享Stream接口，类似于MutableSharedFlow */
interface MutableSharedStream<T> : SharedStream<T> {
    /** 发射一个值到Stream */
    suspend fun emit(value: T)

    /** 尝试发射一个值，如果缓冲区已满返回false */
    fun tryEmit(value: T): Boolean

    /** 重置重放缓存 */
    fun resetReplayCache()
}

/** 状态Stream接口，类似于StateFlow */
interface StateStream<T> : SharedStream<T> {
    /** 当前值 */
    val value: T
}

/** 可变状态Stream接口，类似于MutableStateFlow */
interface MutableStateStream<T> : StateStream<T>, MutableSharedStream<T> {
    /** 设置当前值 */
    override var value: T

    /** 比较并设置值 */
    fun compareAndSet(expect: T, update: T): Boolean
}

/**
 * Helper to access the internal kotlinx.coroutines.flow.StateFlow<Int> for subscription count This
 * is for internal use by the .state() and .share() operators.
 */
internal fun <T> SharedStream<T>.getInternalSubscriptionCountFlow():
        kotlinx.coroutines.flow.StateFlow<Int>? {
    return when (this) {
        is MutableSharedStreamImpl<T> -> this.internalSubscriptionCountFlow
        is MutableStateStreamImpl<T> -> this.internalFlow.subscriptionCount
        else -> null
    }
}

/**
 * Subscribers keep a cursor into one shared log, not a separate unbounded event queue.
 * Unlimited replay intentionally retains the log for late subscribers and rollback consumers.
 * Finite replay bounds unread events and suspends producers when a subscriber falls behind.
 */
class MutableSharedStreamImpl<T>(
        replay: Int = 0,
        extraBufferCapacity: Int = 0,
        onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND
) : MutableSharedStream<T>, MemoryMetricSource {
    private val replayLimit = replay.coerceAtLeast(0)
    private val replayBuffer = ArrayDeque<T>()
    private val subscribers = linkedMapOf<Long, Channel<Unit>>()
    private val cursors = mutableMapOf<Long, Long>()
    private val activeSubscribers = mutableSetOf<Long>()
    private val capacity = (replayLimit.toLong() + extraBufferCapacity.coerceAtLeast(0))
        .coerceAtLeast(64).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private val overflow = onBufferOverflow
    private val spaceChanged = MutableStateFlow(0L)
    private val stateLock = Any()
    private var firstIndex = 0L
    private var nextIndex = 0L
    private var resetIndex = 0L
    private var nextSubscriberId = 0L
    private var closeCause: Throwable? = null
    private var isClosed = false

    internal val internalSubscriptionCountFlow = MutableStateFlow(0)

    init { MemoryDiagnosticMetrics.register(this) }

    override fun memoryCounters(): MemoryCounters = synchronized(stateLock) {
        MemoryCounters(
            streams = 1,
            replayEvents = replayBuffer.size.toLong(),
            maxBacklog = nextIndex - (cursors.values.minOrNull() ?: nextIndex),
            subscribers = activeSubscribers.size.toLong(),
        )
    }

    // 热流不需要锁定机制，所以这里提供默认实现
    override val isLocked: Boolean = false
    override val bufferedCount: Int = 0

    override suspend fun lock() {
        // 热流不支持锁定，此处不执行任何操作
        StreamLogger.d("HotStream", "热流不支持锁定操作")
    }

    override suspend fun unlock() {
        // 热流不支持锁定，此处不执行任何操作
        StreamLogger.d("HotStream", "热流不支持解锁操作")
    }

    override fun clearBuffer() {
        // 热流有自己的缓冲管理，此方法不适用
        StreamLogger.d("HotStream", "热流不支持清空缓冲区操作")
    }

    override val subscriptionCount: Int
        get() = internalSubscriptionCountFlow.value

    override val replayCache: List<T>
        get() = replayFrom(0)

    /** Read only newly appended events without copying the entire replay on each token. */
    internal fun replayFrom(index: Int): List<T> = synchronized(stateLock) {
        val start = replayStartLocked() + index.coerceAtLeast(0)
        (start until nextIndex).map { replayBuffer[(it - firstIndex).toInt()] }
    }

    override suspend fun emit(value: T) {
        currentCoroutineContext().ensureActive()
        while (true) {
            val version = spaceChanged.value
            synchronized(stateLock) {
                if (isClosed || appendLocked(value)) return
            }
            spaceChanged.first { it != version }
            currentCoroutineContext().ensureActive()
        }
    }

    override fun tryEmit(value: T): Boolean {
        return synchronized(stateLock) {
            !isClosed && appendLocked(value)
        }
    }

    override fun resetReplayCache() {
        synchronized(stateLock) {
            // Existing collectors still own their unread events; only future replay is reset.
            resetIndex = nextIndex
            trimLocked()
        }
    }

    fun close(cause: Throwable? = null) {
        synchronized(stateLock) {
            if (isClosed) return
            isClosed = true
            closeCause = cause
            subscribers.values.forEach { it.trySend(Unit) }
            spaceChanged.value++
        }
    }

    override suspend fun collect(collector: StreamCollector<T>) {
        val changed = Channel<Unit>(Channel.CONFLATED)
        val subscriberId = synchronized(stateLock) {
            val id = nextSubscriberId++
            cursors[id] = replayStartLocked()
            subscribers[id] = changed
            // Closed-stream replay still needs a cursor, but must not restart a LAZILY upstream.
            if (!isClosed) activeSubscribers.add(id)
            internalSubscriptionCountFlow.value = activeSubscribers.size
            id
        }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                var available = false
                var value: T? = null
                synchronized(stateLock) {
                    val cursor = cursors.getValue(subscriberId)
                    if (cursor < nextIndex) {
                        value = replayBuffer[(cursor - firstIndex).toInt()]
                        available = true
                        cursors[subscriberId] = cursor + 1
                        trimLocked()
                        spaceChanged.value++
                    } else if (isClosed) {
                        closeCause?.let { throw it }
                        return
                    }
                }
                if (available) {
                    @Suppress("UNCHECKED_CAST")
                    collector.emit(value as T)
                } else {
                    changed.receive()
                }
            }
        } finally {
            changed.cancel()
            synchronized(stateLock) {
                subscribers.remove(subscriberId)
                cursors.remove(subscriberId)
                activeSubscribers.remove(subscriberId)
                trimLocked()
                spaceChanged.value++
                internalSubscriptionCountFlow.value = activeSubscribers.size
            }
        }
    }

    private fun appendLocked(value: T): Boolean {
        val slowest = cursors.values.minOrNull() ?: nextIndex
        if (replayLimit != Int.MAX_VALUE && nextIndex - slowest >= capacity) {
            when (overflow) {
                BufferOverflow.SUSPEND -> return false
                BufferOverflow.DROP_LATEST -> return true
                BufferOverflow.DROP_OLDEST -> cursors.replaceAll { _, cursor ->
                    maxOf(cursor, nextIndex - capacity + 1)
                }
            }
        }
        replayBuffer.addLast(value)
        nextIndex++
        trimLocked()
        subscribers.values.forEach { it.trySend(Unit) }
        return true
    }

    private fun replayStartLocked(): Long = maxOf(resetIndex, nextIndex - replayLimit)

    private fun trimLocked() {
        val keepFrom = minOf(replayStartLocked(), cursors.values.minOrNull() ?: nextIndex)
        while (firstIndex < keepFrom) {
            replayBuffer.removeFirst()
            firstIndex++
        }
    }
}

/** MutableStateFlow的包装器，实现MutableStateStream */
class MutableStateStreamImpl<T>(initialValue: T) : MutableStateStream<T> {
    internal val internalFlow = MutableStateFlow(initialValue)

    // 热流不需要锁定机制，所以这里提供默认实现
    override val isLocked: Boolean = false
    override val bufferedCount: Int = 0

    override suspend fun lock() {
        // 热流不支持锁定，此处不执行任何操作
        StreamLogger.d("HotStream", "状态流不支持锁定操作")
    }

    override suspend fun unlock() {
        // 热流不支持锁定，此处不执行任何操作
        StreamLogger.d("HotStream", "状态流不支持解锁操作")
    }

    override fun clearBuffer() {
        // 热流有自己的缓冲管理，此方法不适用
        StreamLogger.d("HotStream", "状态流不支持清空缓冲区操作")
    }

    override var value: T
        get() = internalFlow.value
        set(value) {
            internalFlow.value = value
        }

    override val subscriptionCount: Int
        get() = internalFlow.subscriptionCount.value

    override val replayCache: List<T>
        get() = internalFlow.replayCache

    override suspend fun emit(value: T) {
        internalFlow.emit(value)
    }

    override fun tryEmit(value: T): Boolean {
        return internalFlow.tryEmit(value)
    }

    override fun resetReplayCache() {
        // StateFlow does not support resetting replay cache as it always holds the current state.
    }

    override fun compareAndSet(expect: T, update: T): Boolean {
        return internalFlow.compareAndSet(expect, update)
    }

    override suspend fun collect(collector: StreamCollector<T>) {
        internalFlow.collect { value -> collector.emit(value) }
    }
}

/** 创建一个MutableSharedStream */
fun <T> MutableSharedStream(
        replay: Int = 0,
        extraBufferCapacity: Int = 0,
        onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
        context: CoroutineContext = EmptyCoroutineContext
): MutableSharedStream<T> {
    return MutableSharedStreamImpl<T>(replay, extraBufferCapacity, onBufferOverflow)
}

/** 创建一个MutableStateStream */
fun <T> MutableStateStream(initialValue: T): MutableStateStream<T> {
    return MutableStateStreamImpl(initialValue)
}

/**
 * 在 root launch 里执行完成回调。
 *
 * 回调与上游收集共用一个 root 协程：回调抛出的异常既会掩盖真正的失败原因，又会从 root launch
 * 逃逸到进程级未捕获处理，把一次失败的对话变成崩溃上报。
 */
internal suspend fun runCompletionHandler(onComplete: suspend () -> Unit) {
    try {
        onComplete()
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        StreamLogger.e("share", "onComplete 失败: ${error.message}", error)
    }
}

/** 将Stream转变为热流，类似于Flow的shareIn */
fun <T> Stream<T>.share(
        scope: CoroutineScope,
        replay: Int = 0,
        started: StreamStart = StreamStart.EAGERLY,
        onComplete: suspend () -> Unit = {}
): SharedStream<T> {
    val sharedStream = MutableSharedStreamImpl<T>(replay = replay)
    var upstreamJob: Job? = null

    when (started) {
        StreamStart.EAGERLY -> {
            // 这个Job现在是scope的直接子Job
            upstreamJob =
                    scope.launch {
                        var failure: Throwable? = null
                        try {
                            this@share.collect { value -> sharedStream.emit(value) }
                        } catch (error: Throwable) {
                            // 关闭的共享流会把该原因交给订阅者。这里再抛一次只会触发进程级未捕获
                            // 处理，把一次失败的对话变成崩溃上报。
                            failure = error
                            if (error is CancellationException) throw error
                        } finally {
                            // 当上游流完成或被取消时，我们不再需要这个共享流。
                            // 但由于SharedFlow本身不会"关闭"，依赖协程的结构化并发来清理是最好的方式。
                            // 此处的finally确保了协程在任何情况下（完成、取消、异常）都能结束。
                            sharedStream.close(failure) // 关闭流以允许收集器完成
                            runCompletionHandler(onComplete)
                        }
                    }
        }
        StreamStart.LAZILY -> {
            scope.launch {
                val subscriptionCountFlow = sharedStream.getInternalSubscriptionCountFlow()
                if (subscriptionCountFlow != null) {
                    subscriptionCountFlow.collect { count ->
                        if (count > 0 && upstreamJob?.isActive != true) {
                            upstreamJob =
                                    scope.launch {
                                        var failure: Throwable? = null
                                        try {
                                            this@share.collect { emittedValue ->
                                                sharedStream.emit(emittedValue)
                                            }
                                        } catch (error: Throwable) {
                                            failure = error
                                            if (error is CancellationException) throw error
                                        } finally {
                                            sharedStream.close(failure) // 关闭流以允许收集器完成
                                            runCompletionHandler(onComplete)
                                        }
                                    }
                        } else if (count == 0) {
                            // 当没有订阅者时，取消上游流的收集
                            upstreamJob?.cancel()
                            upstreamJob = null
                        }
                    }
                } else {
                    println(
                            "Warning: Stream.share LAZILY mode could not observe subscriptions, may behave like EAGERLY."
                    )
                    // Fallback to EAGERLY behavior
                    scope.launch {
                        var failure: Throwable? = null
                        try {
                            this@share.collect { value -> sharedStream.emit(value) }
                        } catch (error: Throwable) {
                            failure = error
                            if (error is CancellationException) throw error
                        } finally {
                            sharedStream.close(failure) // 关闭流以允许收集器完成
                            runCompletionHandler(onComplete)
                        }
                    }
                }
            }
        }
    }

    return sharedStream
}

/** 将Stream转变为StateStream，类似于Flow的stateIn */
fun <T> Stream<T>.state(
        scope: CoroutineScope,
        initialValue: T,
        started: StreamStart = StreamStart.EAGERLY
): StateStream<T> {
    val stateStream = MutableStateStreamImpl(initialValue)
    var upstreamJob: Job? = null

    when (started) {
        StreamStart.EAGERLY -> {
            scope.launch {
                try {
                    this@state.collect { value -> stateStream.value = value }
                } catch (error: Throwable) {
                    // 状态流没有把失败交给订阅者的通道；在这里再抛只会触发进程级未捕获处理。
                    if (error is CancellationException) throw error
                    StreamLogger.e("state", "状态流上游收集出错: ${error.message}", error)
                }
            }
        }
        StreamStart.LAZILY -> {
            scope.launch {
                val subscriptionCountFlow = stateStream.getInternalSubscriptionCountFlow()
                if (subscriptionCountFlow != null) {
                    subscriptionCountFlow.collect { count ->
                        if (count > 0 && upstreamJob == null) {
                            upstreamJob =
                                    scope.launch {
                                        try {
                                            this@state.collect { emittedValue ->
                                                stateStream.value = emittedValue
                                            }
                                        } catch (error: Throwable) {
                                            if (error is CancellationException) throw error
                                            StreamLogger.e(
                                                    "state",
                                                    "状态流上游收集出错: ${error.message}",
                                                    error
                                            )
                                        }
                                    }
                        }
                    }
                } else {
                    println(
                            "Warning: Stream.state LAZILY mode could not observe subscriptions, may behave like EAGERLY."
                    )
                    upstreamJob =
                            scope.launch {
                                try {
                                    this@state.collect { value -> stateStream.value = value }
                                } catch (error: Throwable) {
                                    if (error is CancellationException) throw error
                                    StreamLogger.e(
                                            "state",
                                            "状态流上游收集出错: ${error.message}",
                                            error
                                    )
                                }
                            }
                }
            }
        }
    }

    return stateStream
}

/** 流启动模式 */
enum class StreamStart {
    /** 立即启动 */
    EAGERLY,

    /** 有订阅者时启动 */
    LAZILY
}
