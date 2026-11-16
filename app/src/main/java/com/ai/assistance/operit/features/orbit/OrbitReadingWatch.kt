package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.reading.ReaderProviderException
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The WATCH source `reading` (or `reading:<n>`): keeps comments ready [n][DEFAULT_AHEAD] chapters
 * past the one the user has open in Legado (commented, skipped or a volume heading count as ready).
 * Writing takes time, so Orbit works in batches: it is woken once fewer than half of them are ready
 * and fills them all up again. The app checks this itself, so a check that finds enough costs no
 * tokens. Legado tells Operit when the user turns a page; then a reading watch that would wake runs
 * now instead of at its next time.
 */
internal object OrbitReadingWatch {
    const val SOURCE = "reading"
    const val DEFAULT_AHEAD = 20
    val AHEAD_RANGE = 2..50
    /** Page turns come every few seconds; one check after the first of a burst sees where the user ends up. */
    private const val PROGRESS_DELAY_MS = 30_000L

    data class Check(val wake: Boolean, val observation: String)

    fun isReading(source: String) = source == SOURCE || source.startsWith("$SOURCE:")

    /** How many chapters past the open one the watch keeps ready. */
    fun ahead(source: String): Int =
        source.substringAfter(':', "").trim().toIntOrNull()?.takeIf { it in AHEAD_RANGE } ?: DEFAULT_AHEAD

    /**
     * Reads Legado and the saved comments; no model. A Legado that is not running is not being read:
     * that is a quiet skip, and its next page turn brings a check (see [onProgress]). Other failures
     * are told to the user in their words.
     */
    suspend fun check(context: Context, orbitId: String, ahead: Int): Check = try {
        check(OrbitReading(context, orbitId), ahead)
    } catch (e: ReaderProviderException) {
        when (e.reason) {
            ReaderProviderException.Reason.NO_RECENT_BOOK, ReaderProviderException.Reason.EMPTY_BOOKSHELF ->
                Check(false, "No book is open in Legado yet.")
            // The system stopped Legado in the background; the user is not reading right now.
            ReaderProviderException.Reason.CONNECTION_FAILED ->
                Check(false, "Legado is not running, so the user is not reading now.")
            ReaderProviderException.Reason.LEGADO_NOT_INSTALLED ->
                throw IllegalStateException(context.getString(R.string.orbit_watch_reading_not_installed), e)
            else -> throw IllegalStateException(context.getString(R.string.orbit_watch_reading_failed, e.message.orEmpty()), e)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Such as a reply from Legado missing a field: still told to the user in their words.
        throw IllegalStateException(context.getString(R.string.orbit_watch_reading_failed, e.message.orEmpty()), e)
    }

    private suspend fun check(reading: OrbitReading, ahead: Int): Check {
        val state = reading.readingState(null)
        val book = state.book
        // A kept list from before the book grew would pull a new chapter back onto an old one.
        val catalog = reading.catalog(book.id).let {
            if (state.chapterIndex >= it.size || book.totalChapterCount > it.size) reading.catalog(book.id, fresh = true) else it
        }
        if (catalog.isEmpty()) return Check(false, "\"${book.name}\" has no chapters in Legado.")
        val index = state.chapterIndex.coerceIn(0, catalog.size - 1)
        val ready = reading.readyIndexes(book.id, catalog)
        // Ready in a row from the open chapter; near the end of the book, what is left is enough.
        var inRow = 0
        while (index + inRow < catalog.size && index + inRow in ready) inRow++
        val left = catalog.size - index
        // Woken when fewer than half (rounded up) of the chapters after the open one are ready.
        val half = (ahead + 1) / 2
        val enough = minOf(left, half + 1)
        if (inRow >= enough) return Check(false,
            "\"${book.name}\": chapters ${index + 1} to ${index + inRow} are ready; " +
                "Orbit is woken once fewer than $half after the open chapter are.")
        val missing = index + inRow
        val last = index + minOf(left, ahead + 1) - 1
        val title = state.chapterTitle?.takeIf { it.isNotBlank() } ?: catalog[index].title
        return Check(true, "The user is reading \"${book.name}\" in Legado (book_id: ${book.id}), " +
            "chapter ${index + 1} \"$title\" of ${catalog.size}. This watch keeps comments ready $ahead chapters past " +
            "the open one (with your comments, skipped, or a volume heading) and wakes you when fewer than $half are. " +
            "Chapter ${missing + 1} \"${catalog[missing].title}\" is the first not ready: go on from there with orbit_reading, " +
            "as docs/reading.md says, until every chapter up to ${last + 1} is ready.")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = AtomicBoolean(false)

    /** Called when Legado says the user turned a page; cheap, and does nothing while no reading watch is on. */
    fun onProgress(context: Context) {
        val app = context.applicationContext
        if (!pending.compareAndSet(false, true)) return
        scope.launch {
            try {
                delay(PROGRESS_DELAY_MS)
            } finally {
                // Page turns from here on start the next check.
                pending.set(false)
            }
            try {
                bringForward(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitReadingWatch", "Reading check failed: ${e.message}")
            }
        }
    }

    /**
     * A reading watch that would wake Orbit for something new runs now. One that is due, running, or
     * would only say what it said last time is left alone.
     */
    private suspend fun bringForward(context: Context) {
        val orbit = OrbitPreferences.getInstance(context).current().active?.takeIf { !it.deleting } ?: return
        val repo = OrbitTaskRepository(context)
        val now = System.currentTimeMillis()
        val tasks = repo.dao.tasks(orbit.id).filter {
            it.kind == OrbitWatchSources.KIND && it.state == "ACTIVE" && isReading(it.source) && it.nextRunAt > now &&
                (it.goalId.isEmpty() || repo.dao.goal(it.goalId)?.state == "ACTIVE") &&
                repo.dao.pendingEvent(it.id, it.version) == null
        }
        // Legado is asked only when some watch could actually be brought forward.
        if (tasks.isEmpty()) return
        // Every reading watch follows the open book, so one look per number of chapters serves them all.
        val checks = HashMap<Int, Check?>()
        var moved = false
        for (task in tasks) {
            val ahead = ahead(task.source)
            // A failed look is kept too (as null), so it is not repeated for the next watch.
            val check = if (ahead in checks) checks[ahead] else {
                val look = try {
                    check(context, orbit.id, ahead)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                checks[ahead] = look
                look
            }
            if (check == null || !check.wake) continue
            val fingerprint = OrbitTaskRunner.sha(check.observation)
            if (fingerprint == task.lastFingerprint) continue
            if (repo.dao.bringForward(task.id, task.version, now) > 0) moved = true
        }
        if (moved) OrbitTaskScheduler.reschedule(context)
    }
}
