package com.ai.assistance.operit.data.model

import java.util.concurrent.atomic.AtomicLong

/**
 * 为聊天消息分配单调递增的时间戳。
 *
 * 项目里大量逻辑仍然把 timestamp 当作消息唯一标识使用，
 * 因此这里需要保证同一进程内新建消息不会拿到重复值。
 */
object ChatMessageTimestampAllocator {
    private val lastIssuedTimestamp = AtomicLong(0L)

    fun next(baseTimestamp: Long = System.currentTimeMillis()): Long {
        val now = System.currentTimeMillis()
        // A base taken from a broken saved row must not carry its time into a new message.
        val base = if (baseTimestamp > now + MAX_AHEAD_MS) now else baseTimestamp
        while (true) {
            val previous = lastIssuedTimestamp.get()
            val candidate = maxOf(base, previous + 1L)
            if (lastIssuedTimestamp.compareAndSet(previous, candidate)) {
                return candidate
            }
        }
    }

    fun observe(timestamp: Long) {
        // A time far in the future is never a real message's: one would push every later message
        // there too, and they would be saved with it.
        if (timestamp > System.currentTimeMillis() + MAX_AHEAD_MS) return
        while (true) {
            val previous = lastIssuedTimestamp.get()
            if (timestamp <= previous) {
                return
            }
            if (lastIssuedTimestamp.compareAndSet(previous, timestamp)) {
                return
            }
        }
    }

    /** A time from the future no real message can have; rows beyond it are broken. */
    const val MAX_AHEAD_MS = 24L * 60 * 60 * 1000

    /**
     * The time of a bubble drawn for a message still being sent: always negative, so it never sorts
     * among or reaches the saved rows. [id] is the bubble's own id (any sign).
     */
    fun placeholder(id: Long): Long = if (id >= 0L) -2L - id else id
}
