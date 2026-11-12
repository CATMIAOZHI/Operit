package com.ai.assistance.operit.features.orbit

import android.content.Context
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
 * The WATCH source `reading:<ahead>` or `reading:<ahead>:<book_key>`: keeps the chapter the user has
 * open in Legado and the next [ahead] chapters ready (commented, skipped or a volume heading). The
 * app checks this itself, so Orbit is only woken when something is missing. Legado tells Operit when
 * the user turns a page; then a reading watch that would wake runs now instead of at its next time.
 */
internal object OrbitReadingWatch {
    private const val TYPE = "reading"
    const val DEFAULT_AHEAD = 3
    const val MAX_AHEAD = 30
    private val SOURCE = Regex("reading(?::(\\d{1,2}))?(?::([0-9a-f]{16}))?")
    /** Page turns come every few seconds; one check after the first of a burst sees where the user ends up. */
    private const val PROGRESS_DELAY_MS = 30_000L
    /** Each run marks only its own reads, apart from the chats' and other tasks'. */
    private const val READ_SCOPE = "reading-watch"

    data class Check(val wake: Boolean, val observation: String)

    fun isReading(source: String) = source == TYPE || source.startsWith("$TYPE:")

    private const val NO_BOOK = "No book is open in Legado yet."
    private const val OTHER_BOOK_END = "open, not the book this watch follows."
    private const val NO_CHAPTERS_END = "has no chapters in Legado."

    /** Why a quiet check did not wake Orbit, for the task page; [observation] is what [check] said. */
    fun skipReason(observation: String): Int = when {
        observation == NO_BOOK -> com.ai.assistance.operit.R.string.orbit_decision_reading_no_book
        observation.endsWith(OTHER_BOOK_END) -> com.ai.assistance.operit.R.string.orbit_decision_reading_other_book
        observation.endsWith(NO_CHAPTERS_END) -> com.ai.assistance.operit.R.string.orbit_decision_reading_no_chapters
        else -> com.ai.assistance.operit.R.string.orbit_decision_reading_ready
    }

    /** How many chapters ahead to keep ready, and the book it follows (null: whichever is open). */
    fun parse(source: String): Pair<Int, String?> {
        val match = requireNotNull(SOURCE.matchEntire(source)) {
            "reading watch: reading, reading:<chapters ahead> or reading:<chapters ahead>:<book_key from orbit_reading where/books>"
        }
        val ahead = match.groupValues[1].takeIf { it.isNotEmpty() }?.toInt() ?: DEFAULT_AHEAD
        require(ahead in 1..MAX_AHEAD) { "reading:<chapters ahead> takes 1 to $MAX_AHEAD" }
        return ahead to match.groupValues[2].takeIf { it.isNotEmpty() }
    }

    /** Reads Legado and the saved comments; no model. */
    suspend fun check(context: Context, orbitId: String, source: String): Check {
        val (ahead, bookKey) = parse(source)
        val reading = OrbitReading(context, orbitId, readScope = READ_SCOPE)
        val state = try {
            reading.readingState(null)
        } catch (e: ReaderProviderException) {
            if (e.reason == ReaderProviderException.Reason.NO_RECENT_BOOK ||
                e.reason == ReaderProviderException.Reason.EMPTY_BOOKSHELF) {
                return Check(false, NO_BOOK)
            }
            throw IllegalStateException(e.forModel(), e)
        }
        val book = state.book
        if (bookKey != null && OrbitReading.bookKey(book.id) != bookKey) {
            return Check(false, "The user has \"${book.name}\" $OTHER_BOOK_END")
        }
        // A kept list from before the book grew would pull a new chapter back onto an old one.
        val catalog = reading.catalog(book.id).let {
            if (state.chapterIndex >= it.size || book.totalChapterCount > it.size) reading.catalog(book.id, fresh = true) else it
        }
        if (catalog.isEmpty()) return Check(false, "\"${book.name}\" $NO_CHAPTERS_END")
        val index = state.chapterIndex.coerceIn(0, catalog.size - 1)
        val last = minOf(catalog.size - 1, index + ahead)
        val ready = reading.readyIndexes(book.id, catalog)
        val missing = (index..last).firstOrNull { it !in ready }
            ?: return Check(false, "\"${book.name}\": chapters ${index + 1} to ${last + 1} are ready.")
        val title = state.chapterTitle?.takeIf { it.isNotBlank() } ?: catalog[index].title
        return Check(true, "The user is reading \"${book.name}\" in Legado (book_id: ${book.id}), " +
            "chapter ${index + 1} \"$title\" of ${catalog.size}. This watch keeps the open chapter and the next $ahead " +
            "ready (with your comments, skipped, or a volume heading); chapter ${missing + 1} " +
            "\"${catalog[missing].title}\" is not ready yet. Go on from there with orbit_reading, as docs/reading.md says.")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = AtomicBoolean(false)

    /** A chapter was made not ready again (comments deleted, skip mark removed): the next check counts as new. */
    suspend fun chapterUnready(context: Context, orbitId: String) {
        runCatching { OrbitTaskRepository(context).dao.forgetReadingFingerprints(orbitId, OrbitWatchSources.KIND) }
            .onFailure { if (it is CancellationException) throw it }
    }

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
        val tasks = repo.dao.tasks(orbit.id).filter {
            it.kind == OrbitWatchSources.KIND && it.state == "ACTIVE" && isReading(it.source)
        }
        if (tasks.isEmpty()) return
        val now = System.currentTimeMillis()
        var moved = false
        for (task in tasks) {
            if (task.nextRunAt <= now) continue
            if (task.goalId.isNotEmpty() && repo.dao.goal(task.goalId)?.state != "ACTIVE") continue
            if (repo.dao.pendingEvent(task.id, task.version) != null) continue
            val check = try {
                check(context, orbit.id, task.source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            if (!check.wake || OrbitTaskRunner.sha(check.observation) == task.lastFingerprint) continue
            if (repo.dao.bringForward(task.id, task.version, now) > 0) moved = true
        }
        if (moved) OrbitTaskScheduler.reschedule(context)
    }
}
