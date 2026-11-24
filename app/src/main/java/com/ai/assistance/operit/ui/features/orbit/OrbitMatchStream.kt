package com.ai.assistance.operit.ui.features.orbit

import android.content.Context
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
import androidx.sqlite.db.SimpleSQLiteQuery
import com.ai.assistance.operit.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The ids of the messages in [chatIds] for which the SQL condition [test] holds, newest first, in
 * the same order one "ORDER BY timestamp DESC, messageId DESC" query over all of them would give.
 *
 * Each chat is read a window of rows at a time, newest first, so the first matches are known
 * without going through a whole long chat: one big query would count every row before handing
 * back the first. Pages read [ids] in order and call [fill] when they need more.
 */
internal class OrbitMatchStream(
    private val context: Context,
    chatIds: List<String>,
    private val test: String,
    private val testArgs: List<Any?> = emptyList(),
) {
    private class Source(val chatId: String) {
        /** Matches already read and not yet handed out, newest first, as (timestamp, id). */
        val waiting = ArrayDeque<LongArray>()
        /** The oldest row read so far; every row older than it is still to be read. */
        var time = Long.MAX_VALUE
        var id = Long.MAX_VALUE
        var done = false
    }

    private val sources = chatIds.distinct().map { Source(it) }
    private val lock = Mutex()
    private val found = ArrayList<Long>()

    /** All matches have been handed out and nothing is left to read. */
    private val finished get() = sources.all { it.done && it.waiting.isEmpty() }

    /** The ids found so far from [from] on, at most [count]. */
    suspend fun ids(from: Int, count: Int): List<Long> = lock.withLock {
        if (from >= found.size) emptyList() else found.subList(from, minOf(from + count, found.size)).toList()
    }

    /** Whether something may still be found from [at] on. */
    suspend fun more(at: Int): Boolean = lock.withLock { at < found.size || !finished }

    /**
     * Reads on until [upTo] ids are found or nothing is left, but stops before reading another
     * window once there is any id from [from] on, or once [deadline] (uptime millis) has passed:
     * what is found is shown first. Stopping the caller stops the query inside SQLite; what was
     * read before is kept, so the next call goes on from there.
     */
    suspend fun fill(from: Int, upTo: Int, deadline: Long = Long.MAX_VALUE) = withContext(Dispatchers.IO) {
        lock.withLock {
            while (found.size < upTo) {
                // The newest match waiting; a chat whose unread rows may be newer is read first.
                val first = sources.filter { it.waiting.isNotEmpty() }
                    .maxWithOrNull(compareBy({ it.waiting.first()[0] }, { it.waiting.first()[1] }))
                val head = first?.waiting?.first()
                val behind = sources.filter { !it.done && it.waiting.isEmpty() }
                    .filter { head == null || it.time > head[0] || (it.time == head[0] && it.id > head[1]) }
                    .maxWithOrNull(compareBy({ it.time }, { it.id }))
                if (behind != null) {
                    if (found.size > from || SystemClock.uptimeMillis() > deadline) return@withLock
                    read(behind)
                    continue
                }
                if (first == null) return@withLock
                found += first.waiting.removeFirst()[1]
            }
        }
    }

    /** Reads the next [WINDOW] rows of [source]; its state changes only once they are all in. */
    private suspend fun read(source: Source) {
        val sql = "SELECT messageId, timestamp, CASE WHEN $test THEN 1 ELSE 0 END FROM messages" +
            " WHERE chatId = ? AND timestamp <= ? AND NOT (timestamp = ? AND messageId >= ?)" +
            " ORDER BY timestamp DESC, messageId DESC LIMIT $WINDOW"
        val args = (testArgs + listOf(source.chatId, source.time, source.time, source.id)).toTypedArray()
        val db = AppDatabase.getDatabase(context).openHelper.readableDatabase
        val signal = CancellationSignal()
        // Only the matches and the last row read are kept.
        var lastTime = 0L
        var lastId = 0L
        var count = 0
        val hits = ArrayList<LongArray>()
        coroutineScope {
            // Room's own queries cannot be stopped midway; this signal can.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { signal.cancel() }
            }
            try {
                db.query(SimpleSQLiteQuery(sql, args), signal).use { cursor ->
                    while (cursor.moveToNext()) {
                        lastTime = cursor.getLong(1)
                        lastId = cursor.getLong(0)
                        if (cursor.getLong(2) != 0L) hits += longArrayOf(lastTime, lastId)
                        count++
                    }
                }
            } catch (e: OperationCanceledException) {
                throw CancellationException("Search replaced")
            } finally {
                watcher.cancel()
            }
        }
        source.waiting.addAll(hits)
        if (count > 0) { source.time = lastTime; source.id = lastId }
        if (count < WINDOW) source.done = true
    }

    companion object {
        /** Rows read per step: small enough to show the first matches soon, big enough to go fast. */
        private const val WINDOW = 1000
        /** How long a page keeps reading for more once it has something to show. */
        const val PAGE_TIME_MS = 150L
    }
}
