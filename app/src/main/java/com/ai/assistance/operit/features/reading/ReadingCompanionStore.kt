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
    RUN_INTERRUPTED,
}

data class AutoCommentGenerationClaim(
    val status: AutoCommentGenerationClaimStatus,
    val commentCount: Int = 0,
)

data class AutoCommentRun(
    val id: Long,
    val bookId: String?,
    val bookName: String?,
    val chapterIndex: Int?,
    val chapterTitle: String?,
    val trigger: String,
    val status: String,
    val stage: String,
    val stageUpdatedAt: Long,
    val roleCardId: String?,
    val roleCardName: String?,
    val modelConfigId: String?,
    val modelConfigName: String?,
    val modelIndex: Int?,
    val modelSource: String?,
    val provider: String?,
    val model: String?,
    val targetCharacterCount: Int?,
    val contextChapterCount: Int?,
    val contextCharacterCount: Int?,
    val contextWindowTokens: Int?,
    val estimatedInputTokens: Int?,
    val commentCount: Int,
    val errorMessage: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    /** Provider-reported usage for this direct model request, when available. */
    val actualUncachedInputTokens: Long? = null,
    val actualCachedInputTokens: Long? = null,
    val actualInputTokens: Long? = null,
    val actualOutputTokens: Long? = null,
    val actualReasoningTokens: Long? = null,
    val actualUsageSource: String? = null,
    val actualUsageComplete: Boolean? = null,
)

data class AutoCommentRunStageEvent(
    val id: Long,
    val runId: Long,
    val stage: String,
    val startedAt: Long,
    val finishedAt: Long?,
) {
    val durationMs: Long
        get() = ((finishedAt ?: System.currentTimeMillis()) - startedAt).coerceAtLeast(0)
}

/**
 * Immutable copy of a generated comment. The latest-per-chapter table is intentionally mutable
 * (a forced run replaces it), so history reads this table instead of re-querying that cache.
 */
data class AutoCommentRunCommentSnapshot(
    val id: Long,
    val runId: Long,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val contentHash: String,
    val paragraphIndex: Int,
    val text: String,
    val kind: String,
    val roleCardId: String?,
    val roleCardName: String?,
    val evidenceJson: String,
    val createdAt: Long,
)

/** A safe, structured operation event captured for a single generation run. */
data class AutoCommentRunTraceEvent(
    val id: Long,
    val runId: Long,
    val operation: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val metadataJson: String?,
) {
    val durationMs: Long
        get() = ((finishedAt ?: System.currentTimeMillis()) - startedAt).coerceAtLeast(0)
}

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
        if (oldVersion in 5..9) {
            db.execSQL(
                "ALTER TABLE auto_comment_runs " +
                    "ADD COLUMN stage TEXT NOT NULL DEFAULT 'starting'"
            )
            db.execSQL(
                "ALTER TABLE auto_comment_runs " +
                    "ADD COLUMN stage_updated_at INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE auto_comment_runs ADD COLUMN target_character_count INTEGER"
            )
            db.execSQL(
                "ALTER TABLE auto_comment_runs ADD COLUMN context_chapter_count INTEGER"
            )
            db.execSQL(
                "ALTER TABLE auto_comment_runs ADD COLUMN context_character_count INTEGER"
            )
            db.execSQL(
                "ALTER TABLE auto_comment_runs ADD COLUMN context_window_tokens INTEGER"
            )
            db.execSQL(
                "ALTER TABLE auto_comment_runs ADD COLUMN estimated_input_tokens INTEGER"
            )
            db.execSQL(
                "UPDATE auto_comment_runs SET stage_updated_at = " +
                    "COALESCE(finished_at, started_at) WHERE stage_updated_at = 0"
            )
        }
        if (oldVersion in 5..10) {
            db.execSQL("ALTER TABLE auto_comment_runs ADD COLUMN model_source TEXT")
        }
        if (oldVersion < 11) {
            createAutoCommentRunStageEventTable(db)
            db.execSQL(
                """
                INSERT INTO auto_comment_run_stage_events (
                    run_id, stage, started_at, finished_at
                )
                SELECT
                    id,
                    COALESCE(NULLIF(stage, ''), '${AutoCommentRunStages.STARTING}'),
                    CASE
                        WHEN stage_updated_at > 0 THEN stage_updated_at
                        ELSE started_at
                    END,
                    finished_at
                FROM auto_comment_runs
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM auto_comment_run_stage_events existing
                    WHERE existing.run_id = auto_comment_runs.id
                )
                """.trimIndent()
            )
        }
        if (oldVersion < 12) {
            ensureAutoCommentRunUsageColumns(db)
            createAutoCommentRunHistoryTables(db)
            // Preserve the latest comments already present on devices upgraded from v11.
            // Future runs write immutable snapshots transactionally in replaceAutoComments().
            db.execSQL(
                """
                INSERT INTO auto_comment_run_comments (
                    run_id, book_id, chapter_index, chapter_title, content_hash,
                    paragraph_index, comment_text, comment_kind, role_card_id,
                    role_card_name, evidence_json, created_at
                )
                SELECT
                    chapter.generation_run_id,
                    comment.book_id,
                    comment.chapter_index,
                    chapter.chapter_title,
                    chapter.content_hash,
                    comment.paragraph_index,
                    comment.comment_text,
                    comment.comment_kind,
                    comment.role_card_id,
                    comment.role_card_name,
                    comment.evidence_json,
                    comment.created_at
                FROM auto_comments comment
                JOIN auto_comment_chapters chapter
                  ON chapter.book_id = comment.book_id
                 AND chapter.chapter_index = comment.chapter_index
                JOIN auto_comment_runs run
                  ON run.id = chapter.generation_run_id
                WHERE chapter.generation_run_id IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1
                    FROM auto_comment_run_comments existing
                    WHERE existing.run_id = chapter.generation_run_id
                  )
                """.trimIndent()
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
                stage TEXT NOT NULL DEFAULT 'starting',
                stage_updated_at INTEGER NOT NULL DEFAULT 0,
                role_card_id TEXT,
                role_card_name TEXT,
                model_config_id TEXT,
                model_config_name TEXT,
                model_index INTEGER,
                model_source TEXT,
                provider TEXT,
                model TEXT,
                target_character_count INTEGER,
                context_chapter_count INTEGER,
                context_character_count INTEGER,
                context_window_tokens INTEGER,
                estimated_input_tokens INTEGER,
                actual_uncached_input_tokens INTEGER,
                actual_cached_input_tokens INTEGER,
                actual_input_tokens INTEGER,
                actual_output_tokens INTEGER,
                actual_reasoning_tokens INTEGER,
                actual_usage_source TEXT,
                actual_usage_complete INTEGER,
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
        createAutoCommentRunStageEventTable(db)
        createAutoCommentRunHistoryTables(db)
    }

    private fun createAutoCommentRunStageEventTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comment_run_stage_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id INTEGER NOT NULL,
                stage TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                FOREIGN KEY (run_id) REFERENCES auto_comment_runs(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS auto_comment_run_stage_events_run
            ON auto_comment_run_stage_events(run_id, started_at, id)
            """.trimIndent()
        )
    }

    private fun ensureAutoCommentRunUsageColumns(db: SQLiteDatabase) {
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = "actual_uncached_input_tokens",
            definition = "INTEGER",
        )
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = "actual_cached_input_tokens",
            definition = "INTEGER",
        )
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = "actual_input_tokens",
            definition = "INTEGER",
        )
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = "actual_output_tokens",
            definition = "INTEGER",
        )
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = "actual_reasoning_tokens",
            definition = "INTEGER",
        )
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = "actual_usage_source",
            definition = "TEXT",
        )
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = "actual_usage_complete",
            definition = "INTEGER",
        )
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        definition: String,
    ) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    private fun createAutoCommentRunHistoryTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comment_generation_claims (
                book_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                run_id INTEGER NOT NULL UNIQUE,
                content_hash TEXT NOT NULL,
                role_card_id TEXT NOT NULL,
                role_card_name TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (book_id, chapter_index),
                FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE,
                FOREIGN KEY (run_id) REFERENCES auto_comment_runs(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comment_run_comments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id INTEGER NOT NULL,
                book_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                chapter_title TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                paragraph_index INTEGER NOT NULL,
                comment_text TEXT NOT NULL,
                comment_kind TEXT NOT NULL,
                role_card_id TEXT,
                role_card_name TEXT,
                evidence_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (run_id) REFERENCES auto_comment_runs(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS auto_comment_run_comments_run
            ON auto_comment_run_comments(run_id, paragraph_index, id)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comment_run_trace (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id INTEGER NOT NULL,
                operation TEXT NOT NULL,
                status TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                metadata_json TEXT,
                FOREIGN KEY (run_id) REFERENCES auto_comment_runs(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS auto_comment_run_trace_run
            ON auto_comment_run_trace(run_id, started_at, id)
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
            // 已中断的 run 不再参与任何 claim/缓存决策：先验证 run 仍为 generating，
            // 防止被 stale 清理标记 interrupted 的协程重新取得所有权或返回与 DB 不一致
            // 的 cached/already_generating 结果。
            val runStillGenerating = db.query(
                "auto_comment_runs",
                arrayOf("id"),
                "id = ? AND status = ?",
                arrayOf(
                    generationRunId.toString(),
                    AUTO_COMMENT_RUN_STATUS_GENERATING,
                ),
                null,
                null,
                null,
                "1",
            ).use { cursor -> cursor.moveToFirst() }
            if (!runStillGenerating) {
                db.setTransactionSuccessful()
                return AutoCommentGenerationClaim(
                    status = AutoCommentGenerationClaimStatus.RUN_INTERRUPTED,
                )
            }
            val existing = getAutoCommentChapter(db, bookId, chapterIndex)
            val sameIdentity =
                existing?.contentHash == contentHash &&
                    existing.roleCardId == roleCardId &&
                    existing.roleCardName == roleCardName &&
                    existing.generationPolicyVersion ==
                        AutoCommentSupport.GENERATION_POLICY_VERSION
            val activeClaim = db.query(
                "auto_comment_generation_claims",
                arrayOf("run_id", "updated_at"),
                "book_id = ? AND chapter_index = ?",
                arrayOf(bookId, chapterIndex.toString()),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) null
                else cursor.getLong(0) to cursor.getLong(1)
            }
            if (
                activeClaim != null &&
                activeClaim.first != generationRunId &&
                now - activeClaim.second < staleAfterMs
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
            if (activeClaim != null) {
                db.delete(
                    "auto_comment_generation_claims",
                    "book_id = ? AND chapter_index = ?",
                    arrayOf(bookId, chapterIndex.toString()),
                )
            }
            db.insertOrThrow(
                "auto_comment_generation_claims",
                null,
                ContentValues().apply {
                    put("book_id", bookId)
                    put("chapter_index", chapterIndex)
                    put("run_id", generationRunId)
                    put("content_hash", contentHash)
                    put("role_card_id", roleCardId)
                    put("role_card_name", roleCardName)
                    put("updated_at", now)
                },
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
        return writableDatabase.delete(
            "auto_comment_generation_claims",
            "book_id = ? AND chapter_index = ? AND run_id = ?",
            arrayOf(
                bookId,
                chapterIndex.toString(),
                generationRunId.toString(),
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
        AutoCommentSupport.requireReplacementComments(comments)
        val db = writableDatabase
        val createdAt = System.currentTimeMillis()
        var replaced = false
        db.beginTransaction()
        try {
            val ownsGeneration = db.query(
                "auto_comment_generation_claims",
                arrayOf("run_id"),
                "book_id = ? AND chapter_index = ? AND run_id = ?",
                arrayOf(
                    bookId,
                    chapterIndex.toString(),
                    generationRunId.toString(),
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
            val chapterValues = ContentValues().apply {
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
            }
            val updated = db.update(
                "auto_comment_chapters",
                chapterValues,
                "book_id = ? AND chapter_index = ?",
                arrayOf(bookId, chapterIndex.toString()),
            )
            if (updated == 0) {
                db.insertOrThrow(
                    "auto_comment_chapters",
                    null,
                    ContentValues(chapterValues).apply {
                        put("book_id", bookId)
                        put("chapter_index", chapterIndex)
                    },
                )
            }
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
                db.insertOrThrow(
                    "auto_comment_run_comments",
                    null,
                    ContentValues().apply {
                        put("run_id", generationRunId)
                        put("book_id", bookId)
                        put("chapter_index", chapterIndex)
                        put("chapter_title", chapterTitle)
                        put("content_hash", contentHash)
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
            db.delete(
                "auto_comment_generation_claims",
                "book_id = ? AND chapter_index = ? AND run_id = ?",
                arrayOf(bookId, chapterIndex.toString(), generationRunId.toString()),
            )
            finishAutoCommentRunInTransaction(
                db = db,
                runId = generationRunId,
                status = AUTO_COMMENT_RUN_STATUS_GENERATED,
                commentCount = comments.size,
                errorMessage = null,
                finishedAt = createdAt,
                finalStage = AutoCommentRunStages.COMPLETED,
            )
            pruneAutoCommentRuns(db)
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
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val runId = db.insertOrThrow(
                "auto_comment_runs",
                null,
                ContentValues().apply {
                    put("trigger_source", trigger)
                    put("status", AUTO_COMMENT_RUN_STATUS_GENERATING)
                    put("stage", AutoCommentRunStages.STARTING)
                    put("stage_updated_at", now)
                    put("comment_count", 0)
                    put("started_at", now)
                },
            )
            db.insertOrThrow(
                "auto_comment_run_stage_events",
                null,
                ContentValues().apply {
                    put("run_id", runId)
                    put("stage", AutoCommentRunStages.STARTING)
                    put("started_at", now)
                },
            )
            db.setTransactionSuccessful()
            runId
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun updateAutoCommentRunStage(
        runId: Long,
        stage: String,
    ) {
        val normalizedStage = stage.trim().ifBlank { AutoCommentRunStages.STARTING }
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val currentStage = db.rawQuery(
                """
                SELECT stage
                FROM auto_comment_run_stage_events
                WHERE run_id = ? AND finished_at IS NULL
                ORDER BY id DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(runId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            if (currentStage != normalizedStage) {
                db.update(
                    "auto_comment_run_stage_events",
                    ContentValues().apply { put("finished_at", now) },
                    "run_id = ? AND finished_at IS NULL",
                    arrayOf(runId.toString()),
                )
                db.insertOrThrow(
                    "auto_comment_run_stage_events",
                    null,
                    ContentValues().apply {
                        put("run_id", runId)
                        put("stage", normalizedStage)
                        put("started_at", now)
                    },
                )
            }
            db.update(
                "auto_comment_runs",
                ContentValues().apply {
                    put("stage", normalizedStage)
                    put("stage_updated_at", now)
                },
                "id = ?",
                arrayOf(runId.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
                put("model_source", execution.modelSource)
                put("provider", execution.provider)
                put("model", execution.model)
            },
            "id = ?",
            arrayOf(runId.toString()),
        )
    }

    @Synchronized
    fun updateAutoCommentRunPromptMetrics(
        runId: Long,
        targetCharacterCount: Int,
        metrics: AutoCommentPromptMetrics,
    ) {
        writableDatabase.update(
            "auto_comment_runs",
            ContentValues().apply {
                put("target_character_count", targetCharacterCount.coerceAtLeast(0))
                put("context_chapter_count", metrics.previousContextChapterCount.coerceAtLeast(0))
                put(
                    "context_character_count",
                    metrics.previousContextCharacterCount.coerceAtLeast(0),
                )
                put("context_window_tokens", metrics.contextWindowTokens.coerceAtLeast(0))
                put("estimated_input_tokens", metrics.estimatedInputTokens.coerceAtLeast(0))
            },
            "id = ?",
            arrayOf(runId.toString()),
        )
    }

    @Synchronized
    fun updateAutoCommentRunUsage(
        runId: Long,
        usage: com.ai.assistance.operit.data.stats.ProviderUsageSnapshot,
    ) {
        writableDatabase.update(
            "auto_comment_runs",
            ContentValues().apply {
                if (usage.uncachedInputTokens == null) putNull("actual_uncached_input_tokens")
                else put("actual_uncached_input_tokens", usage.uncachedInputTokens)
                if (usage.cachedInputTokens == null) putNull("actual_cached_input_tokens")
                else put("actual_cached_input_tokens", usage.cachedInputTokens)
                if (usage.totalInputTokens == null) putNull("actual_input_tokens")
                else put("actual_input_tokens", usage.totalInputTokens)
                if (usage.outputTokens == null) putNull("actual_output_tokens")
                else put("actual_output_tokens", usage.outputTokens)
                if (usage.reasoningTokens == null) putNull("actual_reasoning_tokens")
                else put("actual_reasoning_tokens", usage.reasoningTokens)
                put("actual_usage_source", usage.source)
                put("actual_usage_complete", if (usage.completeSnapshot) 1 else 0)
            },
            "id = ?",
            arrayOf(runId.toString()),
        )
    }

    @Synchronized
    fun recordAutoCommentRunTrace(
        runId: Long,
        operation: String,
        status: String,
        startedAt: Long,
        finishedAt: Long? = null,
        metadataJson: String? = null,
    ): Long {
        val normalizedOperation = operation.trim().take(80).ifBlank { "unknown" }
        val normalizedStatus = status.trim().take(40).ifBlank { "completed" }
        return writableDatabase.insertOrThrow(
            "auto_comment_run_trace",
            null,
            ContentValues().apply {
                put("run_id", runId)
                put("operation", normalizedOperation)
                put("status", normalizedStatus)
                put("started_at", startedAt)
                if (finishedAt == null) putNull("finished_at") else put("finished_at", finishedAt)
                if (metadataJson == null) putNull("metadata_json")
                else put("metadata_json", metadataJson.take(4000))
            },
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
        val finishedAt = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.delete(
                "auto_comment_generation_claims",
                "run_id = ?",
                arrayOf(runId.toString()),
            )
            finishAutoCommentRunInTransaction(
                db = db,
                runId = runId,
                status = status,
                commentCount = commentCount,
                errorMessage = errorMessage,
                finishedAt = finishedAt,
            )
            pruneAutoCommentRuns(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun finishAutoCommentRunInTransaction(
        db: SQLiteDatabase,
        runId: Long,
        status: String,
        commentCount: Int,
        errorMessage: String?,
        finishedAt: Long,
        finalStage: String? = null,
    ) {
        val isGenerating = db.query(
            "auto_comment_runs",
            arrayOf("id"),
            "id = ? AND status = ?",
            arrayOf(runId.toString(), AUTO_COMMENT_RUN_STATUS_GENERATING),
            null,
            null,
            null,
            "1",
        ).use { cursor -> cursor.moveToFirst() }
        if (!isGenerating) return
        db.update(
            "auto_comment_run_stage_events",
            ContentValues().apply { put("finished_at", finishedAt) },
            "run_id = ? AND finished_at IS NULL",
            arrayOf(runId.toString()),
        )
        if (finalStage != null) {
            db.insertOrThrow(
                "auto_comment_run_stage_events",
                null,
                ContentValues().apply {
                    put("run_id", runId)
                    put("stage", finalStage)
                    put("started_at", finishedAt)
                    put("finished_at", finishedAt)
                },
            )
        }
        db.update(
            "auto_comment_runs",
            ContentValues().apply {
                put("status", status)
                put("comment_count", commentCount.coerceAtLeast(0))
                if (errorMessage == null) putNull("error_message")
                else put("error_message", errorMessage)
                if (finalStage != null) {
                    put("stage", finalStage)
                    put("stage_updated_at", finishedAt)
                }
                put("finished_at", finishedAt)
            },
            "id = ?",
            arrayOf(runId.toString()),
        )
    }

    @Synchronized
    fun getRecentAutoCommentRuns(limit: Int = 10): List<AutoCommentRun> {
        val result = mutableListOf<AutoCommentRun>()
        readableDatabase.query(
            "auto_comment_runs LEFT JOIN books ON books.book_id = auto_comment_runs.book_id",
            arrayOf(
                "auto_comment_runs.id",
                "auto_comment_runs.book_id",
                "books.name",
                "auto_comment_runs.chapter_index",
                "auto_comment_runs.chapter_title",
                "auto_comment_runs.trigger_source",
                "auto_comment_runs.status",
                "auto_comment_runs.stage",
                "auto_comment_runs.stage_updated_at",
                "auto_comment_runs.role_card_id",
                "auto_comment_runs.role_card_name",
                "auto_comment_runs.model_config_id",
                "auto_comment_runs.model_config_name",
                "auto_comment_runs.model_index",
                "auto_comment_runs.model_source",
                "auto_comment_runs.provider",
                "auto_comment_runs.model",
                "auto_comment_runs.target_character_count",
                "auto_comment_runs.context_chapter_count",
                "auto_comment_runs.context_character_count",
                "auto_comment_runs.context_window_tokens",
                "auto_comment_runs.estimated_input_tokens",
                "auto_comment_runs.actual_uncached_input_tokens",
                "auto_comment_runs.actual_cached_input_tokens",
                "auto_comment_runs.actual_input_tokens",
                "auto_comment_runs.actual_output_tokens",
                "auto_comment_runs.actual_reasoning_tokens",
                "auto_comment_runs.actual_usage_source",
                "auto_comment_runs.actual_usage_complete",
                "auto_comment_runs.comment_count",
                "auto_comment_runs.error_message",
                "auto_comment_runs.started_at",
                "auto_comment_runs.finished_at",
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
                    bookName = if (cursor.isNull(2)) null else cursor.getString(2),
                    chapterIndex = if (cursor.isNull(3)) null else cursor.getInt(3),
                    chapterTitle = if (cursor.isNull(4)) null else cursor.getString(4),
                    trigger = cursor.getString(5),
                    status = cursor.getString(6),
                    stage = cursor.getString(7),
                    stageUpdatedAt = cursor.getLong(8),
                    roleCardId = if (cursor.isNull(9)) null else cursor.getString(9),
                    roleCardName = if (cursor.isNull(10)) null else cursor.getString(10),
                    modelConfigId = if (cursor.isNull(11)) null else cursor.getString(11),
                    modelConfigName = if (cursor.isNull(12)) null else cursor.getString(12),
                    modelIndex = if (cursor.isNull(13)) null else cursor.getInt(13),
                    modelSource = if (cursor.isNull(14)) null else cursor.getString(14),
                    provider = if (cursor.isNull(15)) null else cursor.getString(15),
                    model = if (cursor.isNull(16)) null else cursor.getString(16),
                    targetCharacterCount =
                        if (cursor.isNull(17)) null else cursor.getInt(17),
                    contextChapterCount =
                        if (cursor.isNull(18)) null else cursor.getInt(18),
                    contextCharacterCount =
                        if (cursor.isNull(19)) null else cursor.getInt(19),
                    contextWindowTokens =
                        if (cursor.isNull(20)) null else cursor.getInt(20),
                    estimatedInputTokens =
                        if (cursor.isNull(21)) null else cursor.getInt(21),
                    actualUncachedInputTokens =
                        if (cursor.isNull(22)) null else cursor.getLong(22),
                    actualCachedInputTokens =
                        if (cursor.isNull(23)) null else cursor.getLong(23),
                    actualInputTokens =
                        if (cursor.isNull(24)) null else cursor.getLong(24),
                    actualOutputTokens =
                        if (cursor.isNull(25)) null else cursor.getLong(25),
                    actualReasoningTokens =
                        if (cursor.isNull(26)) null else cursor.getLong(26),
                    actualUsageSource =
                        if (cursor.isNull(27)) null else cursor.getString(27),
                    actualUsageComplete =
                        if (cursor.isNull(28)) null else cursor.getInt(28) != 0,
                    commentCount = cursor.getInt(29),
                    errorMessage = if (cursor.isNull(30)) null else cursor.getString(30),
                    startedAt = cursor.getLong(31),
                    finishedAt = if (cursor.isNull(32)) null else cursor.getLong(32),
                )
            }
        }
        return result
    }

    @Synchronized
    fun getAutoCommentRun(runId: Long): AutoCommentRun? =
        getRecentAutoCommentRuns(AUTO_COMMENT_RUN_RETENTION)
            .firstOrNull { run -> run.id == runId }

    @Synchronized
    fun getAutoCommentRunStageEvents(runId: Long): List<AutoCommentRunStageEvent> {
        val result = mutableListOf<AutoCommentRunStageEvent>()
        readableDatabase.query(
            "auto_comment_run_stage_events",
            arrayOf("id", "run_id", "stage", "started_at", "finished_at"),
            "run_id = ?",
            arrayOf(runId.toString()),
            null,
            null,
            "started_at ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AutoCommentRunStageEvent(
                    id = cursor.getLong(0),
                    runId = cursor.getLong(1),
                    stage = cursor.getString(2),
                    startedAt = cursor.getLong(3),
                    finishedAt = if (cursor.isNull(4)) null else cursor.getLong(4),
                )
            }
        }
        return result
    }

    @Synchronized
    fun getAutoCommentRunComments(runId: Long): List<AutoCommentRunCommentSnapshot> {
        val result = mutableListOf<AutoCommentRunCommentSnapshot>()
        readableDatabase.query(
            "auto_comment_run_comments",
            arrayOf(
                "id",
                "run_id",
                "book_id",
                "chapter_index",
                "chapter_title",
                "content_hash",
                "paragraph_index",
                "comment_text",
                "comment_kind",
                "role_card_id",
                "role_card_name",
                "evidence_json",
                "created_at",
            ),
            "run_id = ?",
            arrayOf(runId.toString()),
            null,
            null,
            "chapter_index ASC, paragraph_index ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AutoCommentRunCommentSnapshot(
                    id = cursor.getLong(0),
                    runId = cursor.getLong(1),
                    bookId = cursor.getString(2),
                    chapterIndex = cursor.getInt(3),
                    chapterTitle = cursor.getString(4),
                    contentHash = cursor.getString(5),
                    paragraphIndex = cursor.getInt(6),
                    text = cursor.getString(7),
                    kind = cursor.getString(8),
                    roleCardId = if (cursor.isNull(9)) null else cursor.getString(9),
                    roleCardName = if (cursor.isNull(10)) null else cursor.getString(10),
                    evidenceJson = cursor.getString(11),
                    createdAt = cursor.getLong(12),
                )
            }
        }
        return result
    }

    @Synchronized
    fun getAutoCommentRunTraceEvents(runId: Long): List<AutoCommentRunTraceEvent> {
        val result = mutableListOf<AutoCommentRunTraceEvent>()
        readableDatabase.query(
            "auto_comment_run_trace",
            arrayOf(
                "id",
                "run_id",
                "operation",
                "status",
                "started_at",
                "finished_at",
                "metadata_json",
            ),
            "run_id = ?",
            arrayOf(runId.toString()),
            null,
            null,
            "started_at ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AutoCommentRunTraceEvent(
                    id = cursor.getLong(0),
                    runId = cursor.getLong(1),
                    operation = cursor.getString(2),
                    status = cursor.getString(3),
                    startedAt = cursor.getLong(4),
                    finishedAt = if (cursor.isNull(5)) null else cursor.getLong(5),
                    metadataJson = if (cursor.isNull(6)) null else cursor.getString(6),
                )
            }
        }
        return result
    }

    /**
     * 是否存在仍在进行的自动段评生成。
     *
     * claim 表是并发权威信号：真正在生成的 run 一定持有 claim，且 finish/失败时会删除。
     * 这里同时 JOIN auto_comment_runs 确认 run 状态仍为 generating，避免过期 claim 残留
     * 导致误报；并兜底覆盖 startAutoCommentRun 创建 run 到 tryClaimAutoCommentGeneration
     * 写入 claim 之间的极小窗口（run 已生成但 claim 尚未写入）。
     */
    @Synchronized
    fun hasActiveAutoCommentGeneration(staleAfterMs: Long): Boolean {
        val staleBefore = System.currentTimeMillis() - staleAfterMs
        val claimActive = readableDatabase.rawQuery(
            """
            SELECT 1
            FROM auto_comment_generation_claims
            JOIN auto_comment_runs ON auto_comment_runs.id = auto_comment_generation_claims.run_id
            WHERE auto_comment_generation_claims.updated_at > ?
              AND auto_comment_runs.status = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(staleBefore.toString(), AUTO_COMMENT_RUN_STATUS_GENERATING),
        ).use { cursor ->
            cursor.moveToFirst()
        }
        if (claimActive) return true
        // 兜底：run 已创建但 claim 尚未写入（或已释放但 run 尚未收尾）的极小窗口。
        return readableDatabase.query(
            "auto_comment_runs",
            arrayOf("id"),
            "status = ? AND started_at >= ?",
            arrayOf(AUTO_COMMENT_RUN_STATUS_GENERATING, staleBefore.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            cursor.moveToFirst()
        }
    }

    @Synchronized
    fun interruptStaleAutoCommentRuns(staleBefore: Long): Int {
        val db = writableDatabase
        val finishedAt = System.currentTimeMillis()
        db.beginTransaction()
        return try {
            val staleRunIds = mutableListOf<Long>()
            db.query(
                "auto_comment_runs",
                arrayOf("id"),
                """
                status = ? AND started_at < ? AND id NOT IN (
                    SELECT run_id
                    FROM auto_comment_generation_claims
                    WHERE updated_at > ?
                )
                """.trimIndent(),
                arrayOf(
                    AUTO_COMMENT_RUN_STATUS_GENERATING,
                    staleBefore.toString(),
                    staleBefore.toString(),
                ),
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) staleRunIds += cursor.getLong(0)
            }
            if (staleRunIds.isNotEmpty()) {
                val placeholders = staleRunIds.joinToString(",") { "?" }
                db.delete(
                    "auto_comment_generation_claims",
                    "run_id IN ($placeholders)",
                    staleRunIds.map(Long::toString).toTypedArray(),
                )
                db.update(
                    "auto_comment_run_stage_events",
                    ContentValues().apply { put("finished_at", finishedAt) },
                    "run_id IN ($placeholders) AND finished_at IS NULL",
                    staleRunIds.map(Long::toString).toTypedArray(),
                )
            }
            val updated = db.update(
                "auto_comment_runs",
                ContentValues().apply {
                    put("status", AUTO_COMMENT_RUN_STATUS_INTERRUPTED)
                    put("error_message", "interrupted")
                    put("finished_at", finishedAt)
                },
                """
                status = ? AND started_at < ? AND id NOT IN (
                    SELECT run_id
                    FROM auto_comment_generation_claims
                    WHERE updated_at > ?
                )
                """.trimIndent(),
                arrayOf(
                    AUTO_COMMENT_RUN_STATUS_GENERATING,
                    staleBefore.toString(),
                    staleBefore.toString(),
                ),
            )
            pruneAutoCommentRuns(db)
            db.setTransactionSuccessful()
            updated
        } finally {
            db.endTransaction()
        }
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

    /**
     * Returns the reader's newest memories for a book without requiring a search expression.
     *
     * Reader memories are authored notes/reactions/predictions, not novel-derived facts. The
     * chapter bound keeps this read surface aligned with the ordinary companion spoiler boundary.
     */
    @Synchronized
    fun getRecentMemories(
        bookId: String,
        throughChapterIndex: Int,
        limit: Int,
    ): List<ReaderMemory> {
        if (limit <= 0) return emptyList()
        val result = mutableListOf<ReaderMemory>()
        readableDatabase.query(
            "reader_memories",
            arrayOf("id", "book_id", "chapter_index", "memory_type", "content", "created_at"),
            "book_id = ? AND chapter_index <= ?",
            arrayOf(bookId, throughChapterIndex.toString()),
            null,
            null,
            "created_at DESC, id DESC",
            limit.coerceIn(1, 50).toString(),
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
        private const val DATABASE_VERSION = 12
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
        const val AUTO_COMMENT_RUN_STATUS_NO_VALID_COMMENTS = "no_valid_comments"
        private const val AUTO_COMMENT_RUN_RETENTION = 50
    }
}
