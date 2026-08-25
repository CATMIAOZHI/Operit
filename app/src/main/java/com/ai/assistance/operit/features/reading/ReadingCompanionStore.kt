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
    val source: String = "full_text",
    val entityName: String? = null,
)

data class ChapterKnowledge(
    val summary: String,
    val characters: List<ChapterCharacter>,
    val events: List<String>,
    val locations: List<String>,
    val items: List<String>,
    val relationshipChanges: List<String>,
    val possibleForeshadowing: List<String>,
    val keywords: List<String>,
)

data class ChapterCharacter(
    val name: String,
    val aliases: List<String>,
    val facts: List<String>,
)

data class StoredChapterKnowledge(
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val sourceEndPosition: Int,
    val isComplete: Boolean,
    val summary: String,
    val structuredJson: String,
    val keywords: String,
    val updatedAt: Long,
)

data class ReaderMemory(
    val id: Long,
    val bookId: String,
    val chapterIndex: Int,
    val type: String,
    val content: String,
    val createdAt: Long,
)

data class AutoCommentChapter(
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val contentHash: String,
    val roleCardId: String?,
    val roleCardName: String?,
    val generationRunId: Long?,
    val generationPolicyVersion: Int,
    val status: String,
    val updatedAt: Long,
)

data class AutoCommentRecord(
    val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val text: String,
    val kind: String,
    val roleCardId: String? = null,
    val roleCardName: String? = null,
    val evidenceJson: String,
    val createdAt: Long = 0,
)

data class AutoCommentPersona(
    val bookId: String,
    val roleCardId: String,
    val roleCardName: String,
    val updatedAt: Long,
)

enum class AutoCommentGenerationClaimStatus {
    CLAIMED,
    CACHED,
    ALREADY_GENERATING,
}

data class AutoCommentGenerationClaim(
    val status: AutoCommentGenerationClaimStatus,
    val commentCount: Int = 0,
)

data class AutoCommentRun(
    val id: Long,
    val bookId: String?,
    val chapterIndex: Int?,
    val chapterTitle: String?,
    val trigger: String,
    val status: String,
    val roleCardId: String?,
    val roleCardName: String?,
    val modelConfigId: String?,
    val modelConfigName: String?,
    val modelIndex: Int?,
    val provider: String?,
    val model: String?,
    val commentCount: Int,
    val errorMessage: String?,
    val startedAt: Long,
    val finishedAt: Long?,
)

internal fun ReadingSearchHit.matchesCharacterIdentity(query: String): Boolean =
    entityName.equals(query, ignoreCase = true) ||
        text.substringBefore('：').contains(query, ignoreCase = true)

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

    data class IndexedChapter(
        val title: String,
        val contentHash: String,
        val indexedUntil: Int,
        val isComplete: Boolean,
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
        createCoreTables(db)
        createKnowledgeTables(db)
        createAutoCommentTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Version 1 already exists on development devices. Keep its indexed text and only add
            // the knowledge, memory, and selection tables introduced by the ToolPkg version.
            createKnowledgeTables(db)
        }
        if (oldVersion < 3) {
            createAutoCommentTables(db)
        }
        if (oldVersion < 4) {
            createAutoCommentRunTable(db)
        } else if (oldVersion < 5) {
            migrateAutoCommentRunTableToWorkerAttempts(db)
        }
        if (oldVersion in 3..5) {
            db.execSQL("ALTER TABLE auto_comment_chapters ADD COLUMN role_card_id TEXT")
            db.execSQL("ALTER TABLE auto_comment_chapters ADD COLUMN role_card_name TEXT")
            db.execSQL("ALTER TABLE auto_comments ADD COLUMN role_card_id TEXT")
            db.execSQL("ALTER TABLE auto_comments ADD COLUMN role_card_name TEXT")
        }
        if (oldVersion < 6) {
            createAutoCommentPersonaTable(db)
        }
        if (oldVersion < 7) {
            db.delete(
                "auto_comments",
                "role_card_id IS NULL OR TRIM(role_card_id) = '' OR " +
                    "role_card_name IS NULL OR TRIM(role_card_name) = ''",
                null,
            )
            db.delete(
                "auto_comment_chapters",
                "role_card_id IS NULL OR TRIM(role_card_id) = '' OR " +
                    "role_card_name IS NULL OR TRIM(role_card_name) = ''",
                null,
            )
        }
        if (oldVersion in 3..7) {
            db.execSQL(
                "ALTER TABLE auto_comment_chapters ADD COLUMN generation_run_id INTEGER"
            )
        }
        if (oldVersion in 3..8) {
            db.execSQL(
                "ALTER TABLE auto_comment_chapters " +
                    "ADD COLUMN generation_policy_version INTEGER NOT NULL DEFAULT 1"
            )
        }
    }

    private fun createCoreTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS books (
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
            CREATE TABLE IF NOT EXISTS chapters (
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
            CREATE VIRTUAL TABLE IF NOT EXISTS text_chunks USING fts4(
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

    private fun createKnowledgeTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chapter_knowledge (
                book_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                chapter_title TEXT NOT NULL,
                source_end_pos INTEGER NOT NULL,
                is_complete INTEGER NOT NULL,
                content_hash TEXT NOT NULL,
                summary TEXT NOT NULL,
                structured_json TEXT NOT NULL,
                keywords TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (book_id, chapter_index),
                FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_fts USING fts4(
                book_id,
                chapter_index,
                chapter_title,
                source_end_pos,
                kind,
                entity_name,
                text,
                search_terms,
                tokenize=unicode61
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS reader_memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                memory_type TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS reader_memories_fts USING fts4(
                memory_id,
                book_id,
                chapter_index,
                memory_type,
                content,
                search_terms,
                tokenize=unicode61
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS reading_settings (
                setting_key TEXT PRIMARY KEY NOT NULL,
                setting_value TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createAutoCommentTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comment_chapters (
                book_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                chapter_title TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                role_card_id TEXT,
                role_card_name TEXT,
                generation_run_id INTEGER,
                generation_policy_version INTEGER NOT NULL,
                status TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (book_id, chapter_index),
                FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                paragraph_index INTEGER NOT NULL,
                comment_text TEXT NOT NULL,
                comment_kind TEXT NOT NULL,
                role_card_id TEXT,
                role_card_name TEXT,
                evidence_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (book_id, chapter_index)
                    REFERENCES auto_comment_chapters(book_id, chapter_index)
                    ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS auto_comments_chapter_paragraph
            ON auto_comments(book_id, chapter_index, paragraph_index)
            """.trimIndent()
        )
        createAutoCommentRunTable(db)
        createAutoCommentPersonaTable(db)
    }

    private fun createAutoCommentPersonaTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comment_personas (
                book_id TEXT PRIMARY KEY NOT NULL,
                role_card_id TEXT NOT NULL,
                role_card_name TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    private fun createAutoCommentRunTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comment_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id TEXT,
                chapter_index INTEGER,
                chapter_title TEXT,
                trigger_source TEXT NOT NULL,
                status TEXT NOT NULL,
                role_card_id TEXT,
                role_card_name TEXT,
                model_config_id TEXT,
                model_config_name TEXT,
                model_index INTEGER,
                provider TEXT,
                model TEXT,
                comment_count INTEGER NOT NULL DEFAULT 0,
                error_message TEXT,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS auto_comment_runs_started
            ON auto_comment_runs(started_at DESC)
            """.trimIndent()
        )
    }

    private fun migrateAutoCommentRunTableToWorkerAttempts(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE auto_comment_runs RENAME TO auto_comment_runs_v4")
        db.execSQL("DROP INDEX IF EXISTS auto_comment_runs_started")
        createAutoCommentRunTable(db)
        db.execSQL(
            """
            INSERT INTO auto_comment_runs (
                id, book_id, chapter_index, chapter_title, trigger_source, status,
                role_card_id, role_card_name, model_config_id, model_config_name,
                model_index, provider, model, comment_count, error_message, started_at, finished_at
            )
            SELECT
                id, book_id, chapter_index, chapter_title, trigger_source, status,
                role_card_id, role_card_name, model_config_id, model_config_name,
                model_index, provider, model, comment_count, error_message, started_at, finished_at
            FROM auto_comment_runs_v4
            """.trimIndent()
        )
        db.execSQL("DROP TABLE auto_comment_runs_v4")
    }

    @Synchronized
    fun updateBook(state: ReadingState) {
        val values = ContentValues().apply {
            put("book_id", state.book.id)
            put("name", state.book.name)
            put("author", state.book.author)
            put("last_read_chapter", state.chapterIndex)
            if (state.bodyPosition == null) putNull("last_read_position")
            else put("last_read_position", state.bodyPosition)
            put("updated_at", state.capturedAt)
        }
        val db = writableDatabase
        db.insertWithOnConflict("books", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        db.update("books", values, "book_id = ?", arrayOf(state.book.id))
    }

    @Synchronized
    fun setSelectedBookId(bookId: String?) {
        val db = writableDatabase
        if (bookId.isNullOrBlank()) {
            db.delete("reading_settings", "setting_key = ?", arrayOf(SELECTED_BOOK_KEY))
            return
        }
        db.insertWithOnConflict(
            "reading_settings",
            null,
            ContentValues().apply {
                put("setting_key", SELECTED_BOOK_KEY)
                put("setting_value", bookId)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun getSelectedBookId(): String? {
        readableDatabase.query(
            "reading_settings",
            arrayOf("setting_value"),
            "setting_key = ?",
            arrayOf(SELECTED_BOOK_KEY),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0).takeIf(String::isNotBlank) else null
        }
    }

    /**
     * Removes novel-derived data beyond the latest observed boundary. Reader memories are kept:
     * they are explicitly user-authored and are independently boundary-filtered on retrieval.
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
                "knowledge_fts",
                "book_id = ? AND CAST(chapter_index AS INTEGER) > ?",
                arrayOf(state.book.id, state.chapterIndex.toString()),
            )
            db.delete(
                "chapter_knowledge",
                "book_id = ? AND chapter_index > ?",
                arrayOf(state.book.id, state.chapterIndex.toString()),
            )
            db.delete(
                "chapters",
                "book_id = ? AND chapter_index > ?",
                arrayOf(state.book.id, state.chapterIndex.toString()),
            )
            db.delete(
                "auto_comment_chapters",
                "book_id = ? AND chapter_index > ?",
                arrayOf(state.book.id, (state.chapterIndex + 1).toString()),
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
                    arrayOf(state.book.id, state.chapterIndex.toString(), bodyPosition.toString()),
                )
                val indexedUntil = getIndexedChapter(db, state.book.id, state.chapterIndex)?.indexedUntil
                val unsafeKnowledge = db.delete(
                    "knowledge_fts",
                    """
                    book_id = ? AND CAST(chapter_index AS INTEGER) = ?
                    AND CAST(source_end_pos AS INTEGER) > ?
                    """.trimIndent(),
                    arrayOf(state.book.id, state.chapterIndex.toString(), bodyPosition.toString()),
                )
                if (unsafeKnowledge > 0) {
                    db.delete(
                        "chapter_knowledge",
                        "book_id = ? AND chapter_index = ?",
                        arrayOf(state.book.id, state.chapterIndex.toString()),
                    )
                }
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
    fun getAutoCommentChapter(
        bookId: String,
        chapterIndex: Int,
    ): AutoCommentChapter? =
        getAutoCommentChapter(readableDatabase, bookId, chapterIndex)

    private fun getAutoCommentChapter(
        db: SQLiteDatabase,
        bookId: String,
        chapterIndex: Int,
    ): AutoCommentChapter? {
        db.query(
            "auto_comment_chapters",
            arrayOf(
                "book_id",
                "chapter_index",
                "chapter_title",
                "content_hash",
                "role_card_id",
                "role_card_name",
                "generation_run_id",
                "generation_policy_version",
                "status",
                "updated_at",
            ),
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return AutoCommentChapter(
                bookId = cursor.getString(0),
                chapterIndex = cursor.getInt(1),
                chapterTitle = cursor.getString(2),
                contentHash = cursor.getString(3),
                roleCardId = if (cursor.isNull(4)) null else cursor.getString(4),
                roleCardName = if (cursor.isNull(5)) null else cursor.getString(5),
                generationRunId = if (cursor.isNull(6)) null else cursor.getLong(6),
                generationPolicyVersion = cursor.getInt(7),
                status = cursor.getString(8),
                updatedAt = cursor.getLong(9),
            )
        }
    }

    @Synchronized
    fun tryClaimAutoCommentGeneration(
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String,
        contentHash: String,
        roleCardId: String,
        roleCardName: String,
        generationRunId: Long,
        force: Boolean,
        staleAfterMs: Long,
    ): AutoCommentGenerationClaim {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val existing = getAutoCommentChapter(db, bookId, chapterIndex)
            val sameIdentity =
                existing?.contentHash == contentHash &&
                    existing.roleCardId == roleCardId &&
                    existing.roleCardName == roleCardName &&
                    existing.generationPolicyVersion ==
                        AutoCommentSupport.GENERATION_POLICY_VERSION
            if (
                sameIdentity &&
                existing.status == AUTO_COMMENT_STATUS_GENERATING &&
                now - existing.updatedAt < staleAfterMs
            ) {
                db.setTransactionSuccessful()
                return AutoCommentGenerationClaim(
                    status = AutoCommentGenerationClaimStatus.ALREADY_GENERATING,
                )
            }
            if (!force && sameIdentity && existing.status == AUTO_COMMENT_STATUS_READY) {
                val commentCount = db.rawQuery(
                    """
                    SELECT COUNT(*)
                    FROM auto_comments
                    WHERE book_id = ? AND chapter_index = ?
                    """.trimIndent(),
                    arrayOf(bookId, chapterIndex.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                db.setTransactionSuccessful()
                return AutoCommentGenerationClaim(
                    status = AutoCommentGenerationClaimStatus.CACHED,
                    commentCount = commentCount,
                )
            }
            db.insertWithOnConflict(
                "auto_comment_chapters",
                null,
                ContentValues().apply {
                    put("book_id", bookId)
                    put("chapter_index", chapterIndex)
                    put("chapter_title", chapterTitle)
                    put("content_hash", contentHash)
                    put("role_card_id", roleCardId)
                    put("role_card_name", roleCardName)
                    put("generation_run_id", generationRunId)
                    put(
                        "generation_policy_version",
                        AutoCommentSupport.GENERATION_POLICY_VERSION,
                    )
                    put("status", AUTO_COMMENT_STATUS_GENERATING)
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
            return AutoCommentGenerationClaim(
                status = AutoCommentGenerationClaimStatus.CLAIMED,
            )
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun markAutoCommentGenerationFailedIfOwned(
        bookId: String,
        chapterIndex: Int,
        generationRunId: Long,
    ): Boolean {
        return writableDatabase.update(
            "auto_comment_chapters",
            ContentValues().apply {
                put("status", AUTO_COMMENT_STATUS_FAILED)
                put("updated_at", System.currentTimeMillis())
            },
            "book_id = ? AND chapter_index = ? AND generation_run_id = ? AND status = ?",
            arrayOf(
                bookId,
                chapterIndex.toString(),
                generationRunId.toString(),
                AUTO_COMMENT_STATUS_GENERATING,
            ),
        ) > 0
    }

    @Synchronized
    fun setAutoCommentPersona(
        bookId: String,
        roleCardId: String,
        roleCardName: String,
    ): AutoCommentPersona {
        val normalizedBookId = bookId.trim()
        val normalizedRoleCardId = roleCardId.trim()
        val normalizedRoleCardName = roleCardName.trim()
        require(normalizedBookId.isNotBlank()) { "bookId is required" }
        require(normalizedRoleCardId.isNotBlank()) { "roleCardId is required" }
        require(normalizedRoleCardName.isNotBlank()) { "roleCardName is required" }
        val updatedAt = System.currentTimeMillis()
        writableDatabase.insertWithOnConflict(
            "auto_comment_personas",
            null,
            ContentValues().apply {
                put("book_id", normalizedBookId)
                put("role_card_id", normalizedRoleCardId)
                put("role_card_name", normalizedRoleCardName)
                put("updated_at", updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return AutoCommentPersona(
            bookId = normalizedBookId,
            roleCardId = normalizedRoleCardId,
            roleCardName = normalizedRoleCardName,
            updatedAt = updatedAt,
        )
    }

    @Synchronized
    fun getAutoCommentPersona(bookId: String): AutoCommentPersona? {
        readableDatabase.query(
            "auto_comment_personas",
            arrayOf("book_id", "role_card_id", "role_card_name", "updated_at"),
            "book_id = ?",
            arrayOf(bookId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return AutoCommentPersona(
                bookId = cursor.getString(0),
                roleCardId = cursor.getString(1),
                roleCardName = cursor.getString(2),
                updatedAt = cursor.getLong(3),
            )
        }
    }

    @Synchronized
    fun updateAutoCommentPersonaNameIfRoleMatches(
        bookId: String,
        roleCardId: String,
        roleCardName: String,
    ): Boolean {
        return writableDatabase.update(
            "auto_comment_personas",
            ContentValues().apply {
                put("role_card_name", roleCardName)
                put("updated_at", System.currentTimeMillis())
            },
            "book_id = ? AND role_card_id = ?",
            arrayOf(bookId, roleCardId),
        ) > 0
    }

    @Synchronized
    fun replaceAutoComments(
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String,
        contentHash: String,
        roleCardId: String,
        roleCardName: String,
        generationRunId: Long,
        comments: List<AutoCommentRecord>,
    ): Boolean {
        val db = writableDatabase
        val createdAt = System.currentTimeMillis()
        var replaced = false
        db.beginTransaction()
        try {
            val ownsGeneration = db.query(
                "auto_comment_chapters",
                arrayOf("generation_run_id"),
                "book_id = ? AND chapter_index = ? AND generation_run_id = ? AND status = ?",
                arrayOf(
                    bookId,
                    chapterIndex.toString(),
                    generationRunId.toString(),
                    AUTO_COMMENT_STATUS_GENERATING,
                ),
                null,
                null,
                null,
                "1",
            ).use { cursor -> cursor.moveToFirst() }
            if (!ownsGeneration) {
                db.setTransactionSuccessful()
                return false
            }
            db.delete(
                "auto_comments",
                "book_id = ? AND chapter_index = ?",
                arrayOf(bookId, chapterIndex.toString()),
            )
            val updated = db.update(
                "auto_comment_chapters",
                ContentValues().apply {
                    put("chapter_title", chapterTitle)
                    put("content_hash", contentHash)
                    put("role_card_id", roleCardId)
                    put("role_card_name", roleCardName)
                    put("generation_run_id", generationRunId)
                    put(
                        "generation_policy_version",
                        AutoCommentSupport.GENERATION_POLICY_VERSION,
                    )
                    put("status", AUTO_COMMENT_STATUS_READY)
                    put("updated_at", createdAt)
                },
                "book_id = ? AND chapter_index = ? AND generation_run_id = ?",
                arrayOf(
                    bookId,
                    chapterIndex.toString(),
                    generationRunId.toString(),
                ),
            )
            check(updated == 1) { "段评生成所有权已变化" }
            comments.forEach { comment ->
                db.insertOrThrow(
                    "auto_comments",
                    null,
                    ContentValues().apply {
                        put("book_id", bookId)
                        put("chapter_index", chapterIndex)
                        put("paragraph_index", comment.paragraphIndex)
                        put("comment_text", comment.text)
                        put("comment_kind", comment.kind)
                        if (comment.roleCardId == null) putNull("role_card_id")
                        else put("role_card_id", comment.roleCardId)
                        if (comment.roleCardName == null) putNull("role_card_name")
                        else put("role_card_name", comment.roleCardName)
                        put("evidence_json", comment.evidenceJson)
                        put("created_at", createdAt)
                    },
                )
            }
            replaced = true
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return replaced
    }

    @Synchronized
    fun getAutoComments(
        bookId: String,
        chapterIndex: Int,
        paragraphIndex: Int? = null,
    ): List<AutoCommentRecord> {
        val result = mutableListOf<AutoCommentRecord>()
        val selection = buildString {
            append("book_id = ? AND chapter_index = ?")
            if (paragraphIndex != null) append(" AND paragraph_index = ?")
        }
        val selectionArgs = buildList {
            add(bookId)
            add(chapterIndex.toString())
            paragraphIndex?.let { add(it.toString()) }
        }.toTypedArray()
        readableDatabase.query(
            "auto_comments",
            arrayOf(
                "id",
                "book_id",
                "chapter_index",
                "paragraph_index",
                "comment_text",
                "comment_kind",
                "role_card_id",
                "role_card_name",
                "evidence_json",
                "created_at",
            ),
            selection,
            selectionArgs,
            null,
            null,
            "paragraph_index ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AutoCommentRecord(
                    id = cursor.getLong(0),
                    bookId = cursor.getString(1),
                    chapterIndex = cursor.getInt(2),
                    paragraphIndex = cursor.getInt(3),
                    text = cursor.getString(4),
                    kind = cursor.getString(5),
                    roleCardId = if (cursor.isNull(6)) null else cursor.getString(6),
                    roleCardName = if (cursor.isNull(7)) null else cursor.getString(7),
                    evidenceJson = cursor.getString(8),
                    createdAt = cursor.getLong(9),
                )
            }
        }
        return result
    }

    @Synchronized
    fun getRecentUnlockedAutoComments(
        bookId: String,
        currentChapterIndex: Int,
        currentUnlockedParagraph: Int,
        roleCardId: String?,
        limit: Int,
    ): List<AutoCommentRecord> {
        val normalizedRoleCardId = roleCardId?.trim()?.takeIf(String::isNotBlank)
            ?: return emptyList()
        val selection = buildString {
            append(
                "auto_comments.book_id = ? AND " +
                    "(auto_comments.chapter_index < ? OR " +
                    "(auto_comments.chapter_index = ? AND auto_comments.paragraph_index <= ?))"
            )
            append(" AND auto_comments.role_card_id = ?")
            append(
                " AND EXISTS (" +
                    "SELECT 1 FROM auto_comment_chapters chapter " +
                    "WHERE chapter.book_id = auto_comments.book_id " +
                    "AND chapter.chapter_index = auto_comments.chapter_index " +
                    "AND chapter.status = ? " +
                    "AND chapter.generation_policy_version = ?)"
            )
        }
        val selectionArgs = buildList {
            add(bookId)
            add(currentChapterIndex.toString())
            add(currentChapterIndex.toString())
            add(currentUnlockedParagraph.coerceAtLeast(0).toString())
            add(normalizedRoleCardId)
            add(AUTO_COMMENT_STATUS_READY)
            add(AutoCommentSupport.GENERATION_POLICY_VERSION.toString())
        }.toTypedArray()
        val result = mutableListOf<AutoCommentRecord>()
        readableDatabase.query(
            "auto_comments",
            arrayOf(
                "id",
                "book_id",
                "chapter_index",
                "paragraph_index",
                "comment_text",
                "comment_kind",
                "role_card_id",
                "role_card_name",
                "evidence_json",
                "created_at",
            ),
            selection,
            selectionArgs,
            null,
            null,
            "chapter_index DESC, paragraph_index DESC, id DESC",
            limit.coerceIn(1, 50).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AutoCommentRecord(
                    id = cursor.getLong(0),
                    bookId = cursor.getString(1),
                    chapterIndex = cursor.getInt(2),
                    paragraphIndex = cursor.getInt(3),
                    text = cursor.getString(4),
                    kind = cursor.getString(5),
                    roleCardId = if (cursor.isNull(6)) null else cursor.getString(6),
                    roleCardName = if (cursor.isNull(7)) null else cursor.getString(7),
                    evidenceJson = cursor.getString(8),
                    createdAt = cursor.getLong(9),
                )
            }
        }
        return result
    }

    @Synchronized
    fun startAutoCommentRun(
        trigger: String,
    ): Long {
        return writableDatabase.insertOrThrow(
            "auto_comment_runs",
            null,
            ContentValues().apply {
                put("trigger_source", trigger)
                put("status", AUTO_COMMENT_RUN_STATUS_GENERATING)
                put("comment_count", 0)
                put("started_at", System.currentTimeMillis())
            },
        )
    }

    @Synchronized
    fun updateAutoCommentRunTarget(
        runId: Long,
        bookId: String,
        chapterIndex: Int? = null,
        chapterTitle: String? = null,
    ) {
        writableDatabase.update(
            "auto_comment_runs",
            ContentValues().apply {
                put("book_id", bookId)
                if (chapterIndex == null) putNull("chapter_index")
                else put("chapter_index", chapterIndex)
                if (chapterTitle == null) putNull("chapter_title")
                else put("chapter_title", chapterTitle)
            },
            "id = ?",
            arrayOf(runId.toString()),
        )
    }

    @Synchronized
    fun updateAutoCommentRunExecution(
        runId: Long,
        execution: AutoCommentModelExecution,
    ) {
        writableDatabase.update(
            "auto_comment_runs",
            ContentValues().apply {
                put("role_card_id", execution.roleCardId)
                put("role_card_name", execution.roleCardName)
                put("model_config_id", execution.configId)
                put("model_config_name", execution.configName)
                put("model_index", execution.modelIndex)
                put("provider", execution.provider)
                put("model", execution.model)
            },
            "id = ?",
            arrayOf(runId.toString()),
        )
    }

    @Synchronized
    fun finishAutoCommentRun(
        runId: Long,
        status: String,
        commentCount: Int = 0,
        errorMessage: String? = null,
    ) {
        val db = writableDatabase
        db.update(
            "auto_comment_runs",
            ContentValues().apply {
                put("status", status)
                put("comment_count", commentCount.coerceAtLeast(0))
                if (errorMessage == null) putNull("error_message")
                else put("error_message", errorMessage)
                put("finished_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(runId.toString()),
        )
        pruneAutoCommentRuns(db)
    }

    @Synchronized
    fun getRecentAutoCommentRuns(limit: Int = 10): List<AutoCommentRun> {
        val result = mutableListOf<AutoCommentRun>()
        readableDatabase.query(
            "auto_comment_runs",
            arrayOf(
                "id",
                "book_id",
                "chapter_index",
                "chapter_title",
                "trigger_source",
                "status",
                "role_card_id",
                "role_card_name",
                "model_config_id",
                "model_config_name",
                "model_index",
                "provider",
                "model",
                "comment_count",
                "error_message",
                "started_at",
                "finished_at",
            ),
            null,
            null,
            null,
            null,
            "started_at DESC, id DESC",
            limit.coerceIn(1, 50).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AutoCommentRun(
                    id = cursor.getLong(0),
                    bookId = if (cursor.isNull(1)) null else cursor.getString(1),
                    chapterIndex = if (cursor.isNull(2)) null else cursor.getInt(2),
                    chapterTitle = if (cursor.isNull(3)) null else cursor.getString(3),
                    trigger = cursor.getString(4),
                    status = cursor.getString(5),
                    roleCardId = if (cursor.isNull(6)) null else cursor.getString(6),
                    roleCardName = if (cursor.isNull(7)) null else cursor.getString(7),
                    modelConfigId = if (cursor.isNull(8)) null else cursor.getString(8),
                    modelConfigName = if (cursor.isNull(9)) null else cursor.getString(9),
                    modelIndex = if (cursor.isNull(10)) null else cursor.getInt(10),
                    provider = if (cursor.isNull(11)) null else cursor.getString(11),
                    model = if (cursor.isNull(12)) null else cursor.getString(12),
                    commentCount = cursor.getInt(13),
                    errorMessage = if (cursor.isNull(14)) null else cursor.getString(14),
                    startedAt = cursor.getLong(15),
                    finishedAt = if (cursor.isNull(16)) null else cursor.getLong(16),
                )
            }
        }
        return result
    }

    @Synchronized
    fun interruptStaleAutoCommentRuns(staleBefore: Long): Int {
        val db = writableDatabase
        val updated = db.update(
            "auto_comment_runs",
            ContentValues().apply {
                put("status", AUTO_COMMENT_RUN_STATUS_INTERRUPTED)
                put("error_message", "interrupted")
                put("finished_at", System.currentTimeMillis())
            },
            "status = ? AND started_at < ?",
            arrayOf(
                AUTO_COMMENT_RUN_STATUS_GENERATING,
                staleBefore.toString(),
            ),
        )
        pruneAutoCommentRuns(db)
        return updated
    }

    private fun pruneAutoCommentRuns(db: SQLiteDatabase) {
        db.delete(
            "auto_comment_runs",
            """
            status != ? AND id NOT IN (
                SELECT id
                FROM auto_comment_runs
                ORDER BY started_at DESC, id DESC
                LIMIT ?
            )
            """.trimIndent(),
            arrayOf(
                AUTO_COMMENT_RUN_STATUS_GENERATING,
                AUTO_COMMENT_RUN_RETENTION.toString(),
            ),
        )
    }

    @Synchronized
    fun isCompleteChapterIndexed(bookId: String, chapterIndex: Int): Boolean =
        getIndexedChapter(readableDatabase, bookId, chapterIndex)?.isComplete == true

    @Synchronized
    fun getIndexedChapter(bookId: String, chapterIndex: Int): IndexedChapter? =
        getIndexedChapter(readableDatabase, bookId, chapterIndex)

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
                val knowledgeHash = getKnowledgeContentHash(db, content.bookId, content.chapterIndex)
                if (knowledgeHash != null && knowledgeHash != contentHash) {
                    deleteKnowledge(db, content.bookId, content.chapterIndex)
                }
            }
            val chunks = if (unchanged) emptyList()
            else ReadingTextIndexSupport.chunkFrom(content.content, rebuildFrom)
            chunks.forEach { chunk ->
                db.insertOrThrow(
                    "text_chunks",
                    null,
                    ContentValues().apply {
                        put("book_id", content.bookId)
                        put("chapter_index", content.chapterIndex)
                        put("chapter_title", content.chapterTitle)
                        put("start_pos", chunk.start)
                        put("end_pos", chunk.end)
                        put("text", chunk.text)
                        put("search_terms", ReadingTextIndexSupport.buildSearchTerms(chunk.text))
                    },
                )
            }
            db.insertWithOnConflict(
                "chapters",
                null,
                ContentValues().apply {
                    put("book_id", content.bookId)
                    put("chapter_index", content.chapterIndex)
                    put("chapter_title", content.chapterTitle)
                    put("content_hash", contentHash)
                    put("indexed_until", content.readableUntil)
                    put("is_complete", if (content.isComplete) 1 else 0)
                    put("updated_at", content.capturedAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun storeKnowledge(
        content: ReadableChapterContent,
        knowledge: ChapterKnowledge,
        structuredJson: String,
    ) {
        val db = writableDatabase
        val contentHash = ReadingTextIndexSupport.sha256(content.content)
        val indexed = getIndexedChapter(db, content.bookId, content.chapterIndex)
        require(
            indexed?.contentHash == contentHash &&
                indexed.indexedUntil == content.readableUntil
        ) { "章节正文在生成摘要期间发生变化" }
        db.beginTransaction()
        try {
            deleteKnowledge(db, content.bookId, content.chapterIndex)
            db.insertOrThrow(
                "chapter_knowledge",
                null,
                ContentValues().apply {
                    put("book_id", content.bookId)
                    put("chapter_index", content.chapterIndex)
                    put("chapter_title", content.chapterTitle)
                    put("source_end_pos", content.readableUntil)
                    put("is_complete", if (content.isComplete) 1 else 0)
                    put("content_hash", contentHash)
                    put("summary", knowledge.summary)
                    put("structured_json", structuredJson)
                    put("keywords", knowledge.keywords.joinToString(" "))
                    put("updated_at", System.currentTimeMillis())
                },
            )
            insertKnowledgeFts(
                db = db,
                content = content,
                kind = "summary",
                entityName = "",
                text = buildString {
                    append(knowledge.summary)
                    if (knowledge.events.isNotEmpty()) {
                        append("\n事件：")
                        append(knowledge.events.joinToString("；"))
                    }
                },
                extraTerms = knowledge.keywords,
            )
            knowledge.characters.forEach { character ->
                insertKnowledgeFts(
                    db = db,
                    content = content,
                    kind = "character",
                    entityName = character.name,
                    text = buildString {
                        append(character.name)
                        if (character.aliases.isNotEmpty()) {
                            append("（")
                            append(character.aliases.joinToString("、"))
                            append("）")
                        }
                        if (character.facts.isNotEmpty()) {
                            append("：")
                            append(character.facts.joinToString("；"))
                        }
                    },
                    extraTerms = character.aliases + knowledge.keywords,
                )
            }
            listOf(
                "event" to knowledge.events,
                "location" to knowledge.locations,
                "item" to knowledge.items,
                "relationship" to knowledge.relationshipChanges,
                "foreshadowing" to knowledge.possibleForeshadowing,
            ).forEach { (kind, values) ->
                values.forEach { value ->
                    insertKnowledgeFts(
                        db = db,
                        content = content,
                        kind = kind,
                        entityName = "",
                        text = value,
                        extraTerms = knowledge.keywords,
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun getChapterKnowledge(bookId: String, chapterIndex: Int): StoredChapterKnowledge? {
        readableDatabase.query(
            "chapter_knowledge",
            arrayOf(
                "book_id",
                "chapter_index",
                "chapter_title",
                "source_end_pos",
                "is_complete",
                "summary",
                "structured_json",
                "keywords",
                "updated_at",
            ),
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return StoredChapterKnowledge(
                bookId = cursor.getString(0),
                chapterIndex = cursor.getInt(1),
                chapterTitle = cursor.getString(2),
                sourceEndPosition = cursor.getInt(3),
                isComplete = cursor.getInt(4) == 1,
                summary = cursor.getString(5),
                structuredJson = cursor.getString(6),
                keywords = cursor.getString(7),
                updatedAt = cursor.getLong(8),
            )
        }
    }

    @Synchronized
    fun getRecentKnowledge(
        bookId: String,
        throughChapterIndex: Int,
        limit: Int,
    ): List<StoredChapterKnowledge> {
        val result = mutableListOf<StoredChapterKnowledge>()
        readableDatabase.query(
            "chapter_knowledge",
            arrayOf(
                "book_id",
                "chapter_index",
                "chapter_title",
                "source_end_pos",
                "is_complete",
                "summary",
                "structured_json",
                "keywords",
                "updated_at",
            ),
            "book_id = ? AND chapter_index <= ?",
            arrayOf(bookId, throughChapterIndex.toString()),
            null,
            null,
            "chapter_index DESC",
            limit.coerceIn(1, 20).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += StoredChapterKnowledge(
                    bookId = cursor.getString(0),
                    chapterIndex = cursor.getInt(1),
                    chapterTitle = cursor.getString(2),
                    sourceEndPosition = cursor.getInt(3),
                    isComplete = cursor.getInt(4) == 1,
                    summary = cursor.getString(5),
                    structuredJson = cursor.getString(6),
                    keywords = cursor.getString(7),
                    updatedAt = cursor.getLong(8),
                )
            }
        }
        return result
    }

    @Synchronized
    fun missingKnowledgeChapterIndices(
        bookId: String,
        throughChapterIndexExclusive: Int,
        limit: Int,
    ): List<Int> {
        if (limit <= 0) return emptyList()
        val result = mutableListOf<Int>()
        readableDatabase.rawQuery(
            """
            SELECT c.chapter_index
            FROM chapters c
            LEFT JOIN chapter_knowledge k
              ON k.book_id = c.book_id AND k.chapter_index = c.chapter_index
            WHERE c.book_id = ? AND c.is_complete = 1
              AND c.chapter_index < ? AND k.chapter_index IS NULL
            ORDER BY c.chapter_index DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                bookId,
                throughChapterIndexExclusive.toString(),
                limit.coerceIn(1, 50).toString(),
            ),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.getInt(0)
        }
        return result
    }

    @Synchronized
    fun countMissingKnowledge(bookId: String, throughChapterIndexExclusive: Int): Int {
        readableDatabase.rawQuery(
            """
            SELECT COUNT(*)
            FROM chapters c
            LEFT JOIN chapter_knowledge k
              ON k.book_id = c.book_id AND k.chapter_index = c.chapter_index
            WHERE c.book_id = ? AND c.is_complete = 1
              AND c.chapter_index < ? AND k.chapter_index IS NULL
            """.trimIndent(),
            arrayOf(bookId, throughChapterIndexExclusive.toString()),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    @Synchronized
    fun searchText(
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
                    id = cursor.getLong(0) * 2,
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
        return sortHits(hits)
    }

    @Synchronized
    fun searchKnowledge(
        bookId: String,
        terms: List<String>,
        summaryOnly: Boolean,
        limit: Int = 30,
    ): List<ReadingSearchHit> {
        val expression = ReadingTextIndexSupport.buildFtsExpression(terms)
        if (expression.isBlank()) return emptyList()
        val comparison = if (summaryOnly) "kind = 'summary'" else "kind != 'summary'"
        val hits = mutableListOf<ReadingSearchHit>()
        readableDatabase.rawQuery(
            """
            SELECT rowid, book_id, chapter_index, chapter_title, source_end_pos,
                   kind, entity_name, text
            FROM knowledge_fts
            WHERE knowledge_fts MATCH ? AND book_id = ? AND $comparison
            LIMIT ?
            """.trimIndent(),
            arrayOf(expression, bookId, limit.coerceIn(1, 100).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val text = cursor.getString(7)
                val title = cursor.getString(3)
                val kind = cursor.getString(5)
                hits += ReadingSearchHit(
                    id = cursor.getLong(0) * 2 + 1,
                    bookId = cursor.getString(1),
                    chapterIndex = cursor.getInt(2),
                    chapterTitle = title,
                    startPosition = 0,
                    endPosition = cursor.getInt(4),
                    text = text,
                    score = ReadingTextIndexSupport.score(text, title, terms) +
                        if (kind == "character") 16 else 8,
                    source = if (kind == "summary") "chapter_summary" else "structured_$kind",
                    entityName = cursor.getString(6).takeIf(String::isNotBlank),
                )
            }
        }
        return sortHits(hits)
    }

    @Synchronized
    fun getCharacterEvidence(
        bookId: String,
        name: String,
        limit: Int,
    ): List<ReadingSearchHit> {
        val terms = ReadingTextIndexSupport.extractQueryTerms(name)
        val ftsHits = searchKnowledge(bookId, terms, summaryOnly = false, limit = limit * 3)
            .filter { it.source == "structured_character" }
        val identityMatches = ftsHits.filter { it.matchesCharacterIdentity(name) }
        if (identityMatches.isNotEmpty()) return identityMatches.take(limit)
        val hits = mutableListOf<ReadingSearchHit>()
        readableDatabase.rawQuery(
            """
            SELECT rowid, book_id, chapter_index, chapter_title, source_end_pos,
                   entity_name, text
            FROM knowledge_fts
            WHERE book_id = ? AND kind = 'character' AND entity_name LIKE ?
            ORDER BY CAST(chapter_index AS INTEGER) DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(bookId, "%${name.replace("%", "\\%").replace("_", "\\_")}%", limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                hits += ReadingSearchHit(
                    id = cursor.getLong(0) * 2 + 1,
                    bookId = cursor.getString(1),
                    chapterIndex = cursor.getInt(2),
                    chapterTitle = cursor.getString(3),
                    startPosition = 0,
                    endPosition = cursor.getInt(4),
                    text = cursor.getString(6),
                    score = 1,
                    source = "structured_character",
                    entityName = cursor.getString(5),
                )
            }
        }
        return hits
    }

    @Synchronized
    fun addMemory(
        bookId: String,
        chapterIndex: Int,
        type: String,
        content: String,
    ): ReaderMemory {
        val createdAt = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow(
                "reader_memories",
                null,
                ContentValues().apply {
                    put("book_id", bookId)
                    put("chapter_index", chapterIndex)
                    put("memory_type", type)
                    put("content", content)
                    put("created_at", createdAt)
                },
            )
            db.insertOrThrow(
                "reader_memories_fts",
                null,
                ContentValues().apply {
                    put("memory_id", id)
                    put("book_id", bookId)
                    put("chapter_index", chapterIndex)
                    put("memory_type", type)
                    put("content", content)
                    put("search_terms", ReadingTextIndexSupport.buildSearchTerms("$type $content"))
                },
            )
            db.setTransactionSuccessful()
            return ReaderMemory(id, bookId, chapterIndex, type, content, createdAt)
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun searchMemories(
        bookId: String,
        terms: List<String>,
        throughChapterIndex: Int,
        limit: Int,
    ): List<ReaderMemory> {
        val expression = ReadingTextIndexSupport.buildFtsExpression(terms)
        if (expression.isBlank()) return emptyList()
        val result = mutableListOf<ReaderMemory>()
        readableDatabase.rawQuery(
            """
            SELECT m.id, m.book_id, m.chapter_index, m.memory_type, m.content, m.created_at
            FROM reader_memories_fts f
            JOIN reader_memories m ON m.id = CAST(f.memory_id AS INTEGER)
            WHERE reader_memories_fts MATCH ? AND f.book_id = ?
              AND CAST(f.chapter_index AS INTEGER) <= ?
            ORDER BY m.created_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                expression,
                bookId,
                throughChapterIndex.toString(),
                limit.coerceIn(1, 50).toString(),
            ),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ReaderMemory(
                    id = cursor.getLong(0),
                    bookId = cursor.getString(1),
                    chapterIndex = cursor.getInt(2),
                    type = cursor.getString(3),
                    content = cursor.getString(4),
                    createdAt = cursor.getLong(5),
                )
            }
        }
        return result
    }

    private fun getIndexedChapter(
        db: SQLiteDatabase,
        bookId: String,
        chapterIndex: Int,
    ): IndexedChapter? {
        db.query(
            "chapters",
            arrayOf("chapter_title", "content_hash", "indexed_until", "is_complete"),
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
                isComplete = cursor.getInt(3) == 1,
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
                chunks += StoredChunk(cursor.getInt(0), cursor.getInt(1), cursor.getString(2))
            }
        }
        return chunks
    }

    private fun getKnowledgeContentHash(
        db: SQLiteDatabase,
        bookId: String,
        chapterIndex: Int,
    ): String? {
        db.query(
            "chapter_knowledge",
            arrayOf("content_hash"),
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private fun insertKnowledgeFts(
        db: SQLiteDatabase,
        content: ReadableChapterContent,
        kind: String,
        entityName: String,
        text: String,
        extraTerms: List<String>,
    ) {
        if (text.isBlank()) return
        db.insertOrThrow(
            "knowledge_fts",
            null,
            ContentValues().apply {
                put("book_id", content.bookId)
                put("chapter_index", content.chapterIndex)
                put("chapter_title", content.chapterTitle)
                put("source_end_pos", content.readableUntil)
                put("kind", kind)
                put("entity_name", entityName)
                put("text", text)
                put(
                    "search_terms",
                    ReadingTextIndexSupport.buildSearchTerms(
                        "$entityName $text ${extraTerms.joinToString(" ")}"
                    ),
                )
            },
        )
    }

    private fun deleteKnowledge(db: SQLiteDatabase, bookId: String, chapterIndex: Int) {
        db.delete(
            "knowledge_fts",
            "book_id = ? AND CAST(chapter_index AS INTEGER) = ?",
            arrayOf(bookId, chapterIndex.toString()),
        )
        db.delete(
            "chapter_knowledge",
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
        )
    }

    private fun deleteChapter(db: SQLiteDatabase, bookId: String, chapterIndex: Int) {
        db.delete(
            "text_chunks",
            "book_id = ? AND CAST(chapter_index AS INTEGER) = ?",
            arrayOf(bookId, chapterIndex.toString()),
        )
        deleteKnowledge(db, bookId, chapterIndex)
        db.delete(
            "chapters",
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
        )
    }

    private fun sortHits(hits: List<ReadingSearchHit>): List<ReadingSearchHit> =
        hits.sortedWith(
            compareByDescending<ReadingSearchHit> { it.score }
                .thenByDescending { it.chapterIndex }
                .thenBy { it.startPosition }
        )

    companion object {
        private const val DATABASE_NAME = "reading_companion.db"
        private const val DATABASE_VERSION = 9
        private const val SELECTED_BOOK_KEY = "selected_book_id"
        const val AUTO_COMMENT_STATUS_GENERATING = "generating"
        const val AUTO_COMMENT_STATUS_READY = "ready"
        const val AUTO_COMMENT_STATUS_FAILED = "failed"
        const val AUTO_COMMENT_RUN_STATUS_GENERATING = "generating"
        const val AUTO_COMMENT_RUN_STATUS_GENERATED = "generated"
        const val AUTO_COMMENT_RUN_STATUS_FAILED = "failed"
        const val AUTO_COMMENT_RUN_STATUS_CANCELLED = "cancelled"
        const val AUTO_COMMENT_RUN_STATUS_SUPERSEDED = "superseded"
        const val AUTO_COMMENT_RUN_STATUS_INTERRUPTED = "interrupted"
        private const val AUTO_COMMENT_RUN_RETENTION = 50
    }
}
