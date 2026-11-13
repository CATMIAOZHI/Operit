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
 * The WATCH source `reading`: keeps the chapter the user has open in Legado and the next [AHEAD]
 * chapters ready (commented, skipped or a volume heading). The app checks this itself, so Orbit is
 * only woken when something is missing. Legado tells Operit when the user turns a page; then a
 * reading watch that would wake runs now instead of at its next time.
 */
internal object OrbitReadingWatch {
    const val SOURCE = "reading"
    const val AHEAD = 3
    /** Page turns come every few seconds; one check after the first of a burst sees where the user ends up. */
    private const val PROGRESS_DELAY_MS = 30_000L

    data class Check(val wake: Boolean, val observation: String)

    fun isReading(source: String) = source == SOURCE

    /** Reads Legado and the saved comments; no model. A Legado that cannot be read fails with words for the user. */
    suspend fun check(context: Context, orbitId: String): Check = try {
        check(OrbitReading(context, orbitId))
    } catch (e: ReaderProviderException) {
        when (e.reason) {
            ReaderProviderException.Reason.NO_RECENT_BOOK, ReaderProviderException.Reason.EMPTY_BOOKSHELF ->
                Check(false, "No book is open in Legado yet.")
            ReaderProviderException.Reason.LEGADO_NOT_INSTALLED ->
                throw IllegalStateException(context.getString(R.string.orbit_watch_reading_not_installed), e)
            ReaderProviderException.Reason.CONNECTION_FAILED ->
                throw IllegalStateException(context.getString(R.string.orbit_watch_reading_unreachable), e)
            else -> throw IllegalStateException(context.getString(R.string.orbit_watch_reading_failed, e.message.orEmpty()), e)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Such as a reply from Legado missing a field: still told to the user in their words.
        throw IllegalStateException(context.getString(R.string.orbit_watch_reading_failed, e.message.orEmpty()), e)
    }

    private suspend fun check(reading: OrbitReading): Check {
        val state = reading.readingState(null)
        val book = state.book
        // A kept list from before the book grew would pull a new chapter back onto an old one.
        val catalog = reading.catalog(book.id).let {
            if (state.chapterIndex >= it.size || book.totalChapterCount > it.size) reading.catalog(book.id, fresh = true) else it
        }
        if (catalog.isEmpty()) return Check(false, "\"${book.name}\" has no chapters in Legado.")
        val index = state.chapterIndex.coerceIn(0, catalog.size - 1)
        val last = minOf(catalog.size - 1, index + AHEAD)
        val ready = reading.readyIndexes(book.id, catalog)
        val missing = (index..last).firstOrNull { it !in ready }
            ?: return Check(false, "\"${book.name}\": chapters ${index + 1} to ${last + 1} are ready.")
        val title = state.chapterTitle?.takeIf { it.isNotBlank() } ?: catalog[index].title
        return Check(true, "The user is reading \"${book.name}\" in Legado (book_id: ${book.id}), " +
            "chapter ${index + 1} \"$title\" of ${catalog.size}. This watch keeps the open chapter and the next $AHEAD " +
            "ready (with your comments, skipped, or a volume heading); chapter ${missing + 1} " +
            "\"${catalog[missing].title}\" is not ready yet. Go on from there with orbit_reading, as docs/reading.md says.")
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
        // Every reading watch follows the open book, so one look serves them all.
        val check = try {
            check(context, orbit.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (!check.wake) return
        val fingerprint = OrbitTaskRunner.sha(check.observation)
        var moved = false
        for (task in tasks) {
            if (fingerprint == task.lastFingerprint) continue
            if (repo.dao.bringForward(task.id, task.version, now) > 0) moved = true
        }
        if (moved) OrbitTaskScheduler.reschedule(context)
    }
}
