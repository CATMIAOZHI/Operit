package com.ai.assistance.operit.features.reading

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

data class ReadingSearchHit(
    val id: Long,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val startPosition: Int,
    val endPosition: Int,
    val text: String,
    val score: Int,
)

internal object ReadingTextIndexSupport {
    private const val CHUNK_SIZE = 1800
    private const val CHUNK_OVERLAP = 220
    private val tokenRegex = Regex("[\\p{L}\\p{N}·]{1,40}")
    private val cjkRegex = Regex("[\\u3400-\\u9FFF]+")

    data class Chunk(
        val start: Int,
        val end: Int,
        val text: String,
    )

    fun chunk(content: String): List<Chunk> {
        if (content.isEmpty()) return emptyList()
        val result = mutableListOf<Chunk>()
        var start = 0
        while (start < content.length) {
            var end = min(start + CHUNK_SIZE, content.length)
            if (end < content.length) {
                val paragraphBreak = content.lastIndexOf('\n', end)
                if (paragraphBreak > start + CHUNK_SIZE / 2) {
                    end = paragraphBreak + 1
                }
            }
            result += Chunk(start = start, end = end, text = content.substring(start, end))
            if (end >= content.length) break
            start = max(start + 1, end - CHUNK_OVERLAP)
        }
        return result
    }

    fun chunkFrom(content: String, startPosition: Int): List<Chunk> {
        val safeStart = startPosition.coerceIn(0, content.length)
        return chunk(content.substring(safeStart)).map { chunk ->
            Chunk(
                start = chunk.start + safeStart,
                end = chunk.end + safeStart,
                text = chunk.text,
            )
        }
    }

    fun extractQueryTerms(query: String): List<String> {
        val terms = linkedSetOf<String>()
        tokenRegex.findAll(query.lowercase()).forEach { match ->
            val token = match.value.trim('·')
            if (token.isBlank()) return@forEach
            terms += token
            cjkRegex.findAll(token).forEach { cjk ->
                if (cjk.value.length >= 2) {
                    cjk.value.windowed(2).forEach(terms::add)
                }
            }
        }
        return terms.take(16)
    }

    fun buildSearchTerms(text: String): String {
        val terms = linkedSetOf<String>()
        tokenRegex.findAll(text.lowercase()).forEach { match ->
            val token = match.value.trim('·')
            if (token.isBlank()) return@forEach
            if (token.length <= 24) terms += token
            cjkRegex.findAll(token).forEach { cjk ->
                cjk.value.windowed(2).forEach(terms::add)
            }
        }
        return terms.joinToString(" ")
    }

    fun buildFtsExpression(terms: List<String>): String {
        return terms
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(16)
            .joinToString(" OR ") { term ->
                val escaped = term.replace("\"", "\"\"")
                "\"$escaped\""
            }
    }

    fun score(text: String, title: String, terms: List<String>): Int {
        val normalizedText = text.lowercase()
        val normalizedTitle = title.lowercase()
        return terms.sumOf { term ->
            val normalized = term.lowercase()
            var score = if (normalizedTitle.contains(normalized)) 12 else 0
            var index = normalizedText.indexOf(normalized)
            while (index >= 0) {
                score += 4 + normalized.length.coerceAtMost(8)
                index = normalizedText.indexOf(normalized, index + normalized.length.coerceAtLeast(1))
            }
            score
        }
    }

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

class ReadingCompanionStore(context: Context) :
    SQLiteOpenHelper(
        context.applicationContext,
        DATABASE_NAME,
        null,
        DATABASE_VERSION,
    ) {

    private data class IndexedChapter(
        val title: String,
        val contentHash: String,
        val indexedUntil: Int,
    )

    private data class StoredChunk(
        val start: Int,
        val end: Int,
        val text: String,
    )

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE books (
                book_id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                author TEXT NOT NULL,
                last_read_chapter INTEGER NOT NULL,
                last_read_position INTEGER,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE chapters (
                book_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                chapter_title TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                indexed_until INTEGER NOT NULL,
                is_complete INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (book_id, chapter_index),
                FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE text_chunks USING fts4(
                book_id,
                chapter_index,
                chapter_title,
                start_pos,
                end_pos,
                text,
                search_terms,
                tokenize=unicode61
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != newVersion) {
            db.execSQL("DROP TABLE IF EXISTS text_chunks")
            db.execSQL("DROP TABLE IF EXISTS chapters")
            db.execSQL("DROP TABLE IF EXISTS books")
            onCreate(db)
        }
    }

    @Synchronized
    fun updateBook(state: ReadingState) {
        val values = ContentValues().apply {
            put("book_id", state.book.id)
            put("name", state.book.name)
            put("author", state.book.author)
            put("last_read_chapter", state.chapterIndex)
            if (state.bodyPosition == null) {
                putNull("last_read_position")
            } else {
                put("last_read_position", state.bodyPosition)
            }
            put("updated_at", state.capturedAt)
        }
        val db = writableDatabase
        db.insertWithOnConflict("books", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        db.update(
            "books",
            values,
            "book_id = ?",
            arrayOf(state.book.id),
        )
    }

    /**
     * Removes future data and trims a regressed current chapter before any new content is loaded.
     * A forward-only refresh preserves existing current chunks so replaceChapter can extend only
     * the tail instead of rebuilding the whole safe prefix.
     */
    @Synchronized
    fun prepareForState(state: ReadingState) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                "text_chunks",
                "book_id = ? AND CAST(chapter_index AS INTEGER) > ?",
                arrayOf(state.book.id, state.chapterIndex.toString()),
            )
            db.delete(
                "chapters",
                "book_id = ? AND chapter_index > ?",
                arrayOf(state.book.id, state.chapterIndex.toString()),
            )
            val bodyPosition = state.bodyPosition
            if (bodyPosition == null) {
                deleteChapter(db, state.book.id, state.chapterIndex)
            } else {
                val removedChunks = db.delete(
                    "text_chunks",
                    """
                    book_id = ? AND CAST(chapter_index AS INTEGER) = ?
                    AND CAST(end_pos AS INTEGER) > ?
                    """.trimIndent(),
                    arrayOf(
                        state.book.id,
                        state.chapterIndex.toString(),
                        bodyPosition.toString(),
                    ),
                )
                val indexedUntil = getIndexedChapter(
                    db,
                    state.book.id,
                    state.chapterIndex,
                )?.indexedUntil
                if (removedChunks > 0 || (indexedUntil != null && indexedUntil > bodyPosition)) {
                    deleteChapter(db, state.book.id, state.chapterIndex)
                } else {
                    db.execSQL(
                        """
                        UPDATE chapters SET is_complete = 0
                        WHERE book_id = ? AND chapter_index = ?
                        """.trimIndent(),
                        arrayOf<Any>(state.book.id, state.chapterIndex),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun isCompleteChapterIndexed(bookId: String, chapterIndex: Int): Boolean {
        readableDatabase.query(
            "chapters",
            arrayOf("is_complete"),
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) == 1
        }
    }

    @Synchronized
    fun replaceChapter(content: ReadableChapterContent) {
        val db = writableDatabase
        val contentHash = ReadingTextIndexSupport.sha256(content.content)
        db.beginTransaction()
        try {
            val indexedChapter = getIndexedChapter(db, content.bookId, content.chapterIndex)
            val storedChunks = getStoredChunks(db, content.bookId, content.chapterIndex)
            val unchanged = indexedChapter?.contentHash == contentHash &&
                indexedChapter.indexedUntil == content.readableUntil &&
                indexedChapter.title == content.chapterTitle
            val canExtend = !unchanged &&
                indexedChapter != null &&
                indexedChapter.title == content.chapterTitle &&
                indexedChapter.indexedUntil <= content.readableUntil &&
                storedChunks.all { chunk ->
                    chunk.start >= 0 &&
                        chunk.end in chunk.start..content.content.length &&
                        content.content.substring(chunk.start, chunk.end) == chunk.text
                }
            val rebuildFrom = when {
                unchanged -> content.readableUntil
                canExtend -> storedChunks.lastOrNull()?.start ?: 0
                else -> 0
            }
            if (!unchanged) {
                db.delete(
                    "text_chunks",
                    """
                    book_id = ? AND CAST(chapter_index AS INTEGER) = ?
                    AND CAST(start_pos AS INTEGER) >= ?
                    """.trimIndent(),
                    arrayOf(
                        content.bookId,
                        content.chapterIndex.toString(),
                        rebuildFrom.toString(),
                    ),
                )
            }
            val chunks = if (unchanged) {
                emptyList()
            } else {
                ReadingTextIndexSupport.chunkFrom(content.content, rebuildFrom)
            }
            chunks.forEach { chunk ->
                val values = ContentValues().apply {
                    put("book_id", content.bookId)
                    put("chapter_index", content.chapterIndex)
                    put("chapter_title", content.chapterTitle)
                    put("start_pos", chunk.start)
                    put("end_pos", chunk.end)
                    put("text", chunk.text)
                    put("search_terms", ReadingTextIndexSupport.buildSearchTerms(chunk.text))
                }
                db.insertOrThrow("text_chunks", null, values)
            }
            val chapterValues = ContentValues().apply {
                put("book_id", content.bookId)
                put("chapter_index", content.chapterIndex)
                put("chapter_title", content.chapterTitle)
                put("content_hash", contentHash)
                put("indexed_until", content.readableUntil)
                put("is_complete", if (content.isComplete) 1 else 0)
                put("updated_at", content.capturedAt)
            }
            db.insertWithOnConflict(
                "chapters",
                null,
                chapterValues,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun getIndexedChapter(
        db: SQLiteDatabase,
        bookId: String,
        chapterIndex: Int,
    ): IndexedChapter? {
        db.query(
            "chapters",
            arrayOf("chapter_title", "content_hash", "indexed_until"),
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return IndexedChapter(
                title = cursor.getString(0),
                contentHash = cursor.getString(1),
                indexedUntil = cursor.getInt(2),
            )
        }
    }

    private fun getStoredChunks(
        db: SQLiteDatabase,
        bookId: String,
        chapterIndex: Int,
    ): List<StoredChunk> {
        val chunks = mutableListOf<StoredChunk>()
        db.query(
            "text_chunks",
            arrayOf("start_pos", "end_pos", "text"),
            "book_id = ? AND CAST(chapter_index AS INTEGER) = ?",
            arrayOf(bookId, chapterIndex.toString()),
            null,
            null,
            "CAST(start_pos AS INTEGER) ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                chunks += StoredChunk(
                    start = cursor.getInt(0),
                    end = cursor.getInt(1),
                    text = cursor.getString(2),
                )
            }
        }
        return chunks
    }

    private fun deleteChapter(
        db: SQLiteDatabase,
        bookId: String,
        chapterIndex: Int,
    ) {
        db.delete(
            "text_chunks",
            "book_id = ? AND CAST(chapter_index AS INTEGER) = ?",
            arrayOf(bookId, chapterIndex.toString()),
        )
        db.delete(
            "chapters",
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
        )
    }

    @Synchronized
    fun search(
        bookId: String,
        terms: List<String>,
        limit: Int = 40,
    ): List<ReadingSearchHit> {
        val expression = ReadingTextIndexSupport.buildFtsExpression(terms)
        if (expression.isBlank()) return emptyList()
        val hits = mutableListOf<ReadingSearchHit>()
        readableDatabase.rawQuery(
            """
            SELECT rowid, book_id, chapter_index, chapter_title, start_pos, end_pos, text
            FROM text_chunks
            WHERE text_chunks MATCH ? AND book_id = ?
            LIMIT ?
            """.trimIndent(),
            arrayOf(expression, bookId, limit.coerceIn(1, 100).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val text = cursor.getString(6)
                val title = cursor.getString(3)
                hits += ReadingSearchHit(
                    id = cursor.getLong(0),
                    bookId = cursor.getString(1),
                    chapterIndex = cursor.getInt(2),
                    chapterTitle = title,
                    startPosition = cursor.getInt(4),
                    endPosition = cursor.getInt(5),
                    text = text,
                    score = ReadingTextIndexSupport.score(text, title, terms),
                )
            }
        }
        return hits.sortedWith(
            compareByDescending<ReadingSearchHit> { it.score }
                .thenBy { it.chapterIndex }
                .thenBy { it.startPosition }
        )
    }

    companion object {
        private const val DATABASE_NAME = "reading_companion.db"
        private const val DATABASE_VERSION = 1
    }
}
