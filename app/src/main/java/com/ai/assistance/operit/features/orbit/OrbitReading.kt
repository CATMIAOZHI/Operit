package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.reading.AnnotationChapterContent
import com.ai.assistance.operit.features.reading.LegadoReaderProvider
import com.ai.assistance.operit.features.reading.ReadableChapterContent
import com.ai.assistance.operit.features.reading.ReaderProvider
import com.ai.assistance.operit.features.reading.ReaderProviderException
import com.ai.assistance.operit.features.reading.ReadingCompanionFileStore
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orbit's reading companion: Orbit reads a book in Legado alongside the user and leaves paragraph
 * comments that Legado shows in its paragraph-comment bubbles under Orbit's name. There is no
 * spoiler fence: Orbit is reading with the user, may read any chapter and decides by itself how far
 * ahead to write. This class only reads Legado and keeps the comments (one small file per chapter,
 * so Legado's lookups stay cheap) in reading/ inside the Orbit's folder, which the file tools cannot
 * reach. Orbit keeps its own reading notes and summaries itself, under files/ (see docs/reading.md).
 *
 * Chapter numbers given to and from the model start at 1; Legado's indexes start at 0.
 */
internal class OrbitReading(
    private val context: Context,
    private val orbitId: String,
    private val reader: ReaderProvider = LegadoReaderProvider(context),
    /** Only tests pass this; otherwise reading/ inside the Orbit's folder. */
    private val rootDir: File? = null,
) {
    private val root: File get() = rootDir ?: File(OrbitPreferences.getInstance(context).orbitDir(orbitId), DIR)

    /** One chapter as Legado lays it out for paragraph comments. */
    class Chapter(val bookId: String, val index: Int, val sourceId: String, val title: String,
        val paragraphs: List<String>, val contractHash: String)

    /** A chapter's saved comments. */
    data class Comment(val id: String, val paragraph: Int, val fingerprint: String, val unique: Boolean,
        val text: String, val createdAt: Long)

    data class ChapterComments(val sourceId: String, val index: Int, val title: String, val contractHash: String,
        val paragraphCount: Int, val updatedAt: Long, val comments: List<Comment>)

    // ---- Legado ----

    suspend fun readingState(bookId: String?) = reader.getReadingState(bookId)

    suspend fun books() = reader.getBooks()

    suspend fun catalog(bookId: String) = reader.getChapters(bookId)

    suspend fun readable(bookId: String, index: Int): ReadableChapterContent? =
        runCatching { reader.getReadableChapterContent(bookId, index) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()

    /**
     * A whole chapter split into Legado's comment paragraphs. Reading one chapter page by page asks
     * Legado once: the layout is kept a few minutes.
     */
    suspend fun chapter(bookId: String, index: Int, fresh: Boolean = false): Chapter {
        val key = "$bookId\u0000$index"
        val now = System.currentTimeMillis()
        if (!fresh) cached(key, now)?.let { return it }
        val content: AnnotationChapterContent = reader.getAnnotationChapterContent(bookId, index)
        val chapter = Chapter(bookId, index, content.sourceId, content.chapterTitle,
            content.content.split('\n'), content.contractHash)
        synchronized(chapterCache) {
            chapterCache[key] = now to chapter
            while (chapterCache.size > CHAPTER_CACHE_SIZE) chapterCache.remove(chapterCache.keys.first())
        }
        return chapter
    }

    private fun cached(key: String, now: Long): Chapter? = synchronized(chapterCache) {
        chapterCache[key]?.takeIf { now - it.first < CHAPTER_CACHE_MS }?.second
    }

    /** The chapter as last read, if it is still kept; never asks Legado. */
    fun peek(bookId: String, index: Int): Chapter? = cached("$bookId\u0000$index", System.currentTimeMillis())

    // ---- Comments ----

    private fun bookDir(bookId: String) = File(root, bookKey(bookId))

    private fun chapterDir(bookId: String) = File(bookDir(bookId), CHAPTERS)

    /**
     * Chapter indexes (from 0) with at least one comment, from the file names alone: a chapter's
     * file is deleted when its last comment goes, and is never written empty.
     */
    fun commentedIndexes(bookId: String): Set<Int> = chapterDir(bookId).list { _, name -> name.endsWith(".json") }
        .orEmpty().mapNotNullTo(HashSet()) { it.substringBefore('_').toIntOrNull() }

    /** The comments of chapter [index]: the file for [sourceId] when given, else the newest one at that index. */
    fun comments(bookId: String, index: Int, sourceId: String? = null): ChapterComments? =
        pick(chapterDir(bookId), index, sourceId = sourceId)

    /**
     * Adds [entries] (paragraph number from 1, text) to [chapter], or replaces its comments with them.
     * Comments written against an older layout of the chapter move to their paragraph by its text,
     * and are dropped when that paragraph is gone. Returns the saved file and how many old ones were dropped.
     */
    suspend fun write(chapter: Chapter, entries: List<Pair<Int, String>>, replace: Boolean,
        now: Long = System.currentTimeMillis()): Pair<ChapterComments, Int> = lock(chapter.bookId).withLock {
        val dir = chapterDir(chapter.bookId)
        check(dir.mkdirs() || dir.isDirectory) { "Could not create the reading folder" }
        val file = File(dir, chapterFileName(chapter.index, chapter.sourceId))
        val old = read(file)
        val fingerprints = chapter.paragraphs.map { ReadingCompanionFileStore.paragraphFingerprint(it) }
        val counts = fingerprints.groupingBy { it }.eachCount()
        var dropped = 0
        val kept = if (replace || old == null) emptyList() else if (old.contractHash == chapter.contractHash) old.comments else {
            old.comments.mapNotNull { comment ->
                val at = fingerprints.indexOf(comment.fingerprint)
                if (comment.unique && at >= 0 && counts[comment.fingerprint] == 1) {
                    comment.copy(paragraph = at + 1, unique = true)
                } else { dropped++; null }
            }
        }
        val added = entries.map { (paragraph, text) ->
            val fingerprint = fingerprints[paragraph - 1]
            Comment(newId(chapter.index), paragraph, fingerprint, counts[fingerprint] == 1, text, now)
        }.filterNot { new -> kept.any { it.paragraph == new.paragraph && it.text == new.text } }
        val all = (kept + added).sortedWith(compareBy({ it.paragraph }, { it.createdAt }))
        require(all.size <= MAX_COMMENTS_PER_CHAPTER) {
            "Chapter ${chapter.index + 1} would have ${all.size} comments; keep it to $MAX_COMMENTS_PER_CHAPTER. " +
                "Delete some with delete_comments or use mode=replace."
        }
        val saved = ChapterComments(chapter.sourceId, chapter.index, chapter.title, chapter.contractHash,
            chapter.paragraphs.size, now, all)
        atomicWrite(file, encode(saved).toString())
        saved to dropped
    }.also { notifyLegado() }

    /** Deletes comments by id; returns the ones deleted. */
    suspend fun delete(bookId: String, ids: Set<String>): List<Pair<ChapterComments, Comment>> = lock(bookId).withLock {
        val gone = mutableListOf<Pair<ChapterComments, Comment>>()
        val indexes = ids.mapNotNullTo(HashSet()) { it.substringBefore('-').toIntOrNull()?.minus(1) }
        chapterFiles(chapterDir(bookId)).filter { it.name.substringBefore('_').toIntOrNull() in indexes }.forEach { file ->
            val chapter = read(file) ?: return@forEach
            val (out, keep) = chapter.comments.partition { it.id in ids }
            if (out.isEmpty()) return@forEach
            out.forEach { gone += chapter to it }
            if (keep.isEmpty()) file.delete() else atomicWrite(file, encode(chapter.copy(comments = keep)).toString())
        }
        gone
    }.also { if (it.isNotEmpty()) notifyLegado() }

    /** Deletes every comment of chapter [index]; returns how many. */
    suspend fun deleteChapter(bookId: String, index: Int): Int = lock(bookId).withLock {
        chapterFiles(chapterDir(bookId)).filter { it.name.substringBefore('_').toIntOrNull() == index }
            .sumOf { file -> (read(file)?.comments?.size ?: 0).also { file.delete() } }
    }.also { if (it > 0) notifyLegado() }

    /**
     * Legado keeps a chapter's comment summary until told otherwise; the same notice the main
     * app's reading companion sends makes the open page show the change at once.
     */
    private fun notifyLegado() {
        runCatching {
            context.contentResolver.notifyChange(
                android.net.Uri.parse("content://${context.packageName}.readingCompanionAnnotations/reviews"), null)
        }
    }

    private fun newId(index: Int): String =
        "${index + 1}-" + java.util.UUID.randomUUID().toString().replace("-", "").take(6)

    companion object {
        const val DIR = "reading"
        private const val CHAPTERS = "chapters"
        const val MAX_COMMENT_CHARS = 300
        const val MAX_COMMENTS_PER_CALL = 30
        const val MAX_COMMENTS_PER_CHAPTER = 60
        private const val CHAPTER_CACHE_SIZE = 6
        private const val CHAPTER_CACHE_MS = 10 * 60_000L

        private val locks = ConcurrentHashMap<String, Mutex>()
        private val chapterCache = LinkedHashMap<String, Pair<Long, Chapter>>()

        private fun lock(bookId: String) = locks.computeIfAbsent(bookId) { Mutex() }

        fun bookKey(bookId: String): String = sha256(bookId).take(16)

        fun chapterFileName(index: Int, sourceId: String) = "${index}_${sha256(sourceId).take(12)}.json"

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun chapterFiles(dir: File): List<File> =
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }.orEmpty()

        /** The file at [index]: the one for [sourceId], else one whose layout is [hash], else the newest. */
        internal fun pick(dir: File, index: Int, hash: String? = null, sourceId: String? = null): ChapterComments? {
            val prefix = "${index}_"
            // Names are checked before anything touches the file, so a lookup costs one listing.
            val found = dir.list { _, name -> name.startsWith(prefix) && name.endsWith(".json") }
                .orEmpty().mapNotNull { read(File(dir, it)) }.filter { it.comments.isNotEmpty() }
            if (sourceId != null) return found.firstOrNull { it.sourceId == sourceId }
            return found.firstOrNull { hash != null && it.contractHash == hash } ?: found.maxByOrNull { it.updatedAt }
        }

        internal fun read(file: File): ChapterComments? = runCatching {
            if (!file.isFile) return null
            val json = JSONObject(file.readText())
            val list = json.optJSONArray("comments") ?: JSONArray()
            ChapterComments(json.getString("source_id"), json.getInt("chapter_index"), json.optString("title"),
                json.getString("contract_hash"), json.optInt("paragraphs"), json.optLong("updated_at"),
                (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { c ->
                    Comment(c.getString("id"), c.getInt("paragraph"), c.optString("fingerprint"), c.optBoolean("unique"),
                        c.getString("text"), c.optLong("created_at"))
                } })
        }.getOrNull()

        private fun encode(chapter: ChapterComments) = JSONObject()
            .put("source_id", chapter.sourceId).put("chapter_index", chapter.index).put("title", chapter.title)
            .put("contract_hash", chapter.contractHash).put("paragraphs", chapter.paragraphCount)
            .put("updated_at", chapter.updatedAt)
            .put("comments", JSONArray(chapter.comments.map { c ->
                JSONObject().put("id", c.id).put("paragraph", c.paragraph).put("fingerprint", c.fingerprint)
                    .put("unique", c.unique).put("text", c.text).put("created_at", c.createdAt)
            }))

        private fun atomicWrite(file: File, text: String) {
            val parent = checkNotNull(file.parentFile)
            check(parent.mkdirs() || parent.isDirectory) { "Could not create ${parent.name}" }
            val temp = File.createTempFile(".reading-", ".tmp", parent)
            try {
                temp.writeText(text)
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temp.delete()
            }
        }

        /**
         * Which paragraphs (from 1) the user's page covers, found by the page's text; null when
         * Legado could not say where the page is. [exact] is false for a position guessed from the
         * saved progress or from counting lines.
         */
        fun locate(paragraphs: List<String>, readable: ReadableChapterContent?): Located? {
            if (readable == null || paragraphs.isEmpty()) return null
            val text = readable.content
            fun at(position: Int): Pair<Int, Boolean> {
                val p = position.coerceIn(0, text.length)
                val start = text.lastIndexOf('\n', (p - 1).coerceAtLeast(0)).let { if (it < 0 || p == 0) 0 else it + 1 }
                val end = text.indexOf('\n', p).let { if (it < 0) text.length else it }
                val line = text.substring(start.coerceAtMost(end), end).trim()
                if (line.length >= 4) {
                    val hits = paragraphs.indices.filter { paragraphs[it].trim() == line }
                    if (hits.size == 1) return hits[0] + 1 to true
                }
                val counted = text.substring(0, start.coerceAtMost(text.length)).split('\n').count { it.isNotBlank() } + 1
                return counted.coerceIn(1, paragraphs.size) to false
            }
            val visibleStart = readable.visibleStart
            val visibleEnd = readable.visibleEnd
            return if (visibleStart != null && visibleEnd != null) {
                val (first, a) = at(visibleStart)
                val (last, b) = at((visibleEnd - 1).coerceAtLeast(visibleStart))
                Located(first, maxOf(first, last), a && b, true)
            } else {
                val p = at(readable.readableUntil).first
                Located(p, p, false, false)
            }
        }

        /** Where the comments of [bookId] live inside a reading folder [root]. */
        internal fun chapterDirIn(root: File, bookId: String) = File(File(root, bookKey(bookId)), CHAPTERS)
    }

    /** Paragraphs [first]..[last] on the user's page; [visible] is false when only the saved progress was known. */
    data class Located(val first: Int, val last: Int, val exact: Boolean, val visible: Boolean)
}

/** Turns a Legado failure into words the model can act on. */
internal fun ReaderProviderException.forModel(): String = when (reason) {
    ReaderProviderException.Reason.LEGADO_NOT_INSTALLED ->
        "Legado (阅读) with Operit support is not installed, so there is no book to read together. " +
            "Tell the user it needs the Legado build that works with Operit."
    ReaderProviderException.Reason.CONNECTION_FAILED ->
        "Could not reach Legado. Ask the user to open Legado once, then try again."
    ReaderProviderException.Reason.EMPTY_BOOKSHELF -> "Legado's bookshelf is empty."
    ReaderProviderException.Reason.NO_RECENT_BOOK -> "The user has not opened a book in Legado yet."
    else -> "Legado: ${message.orEmpty()}"
}
