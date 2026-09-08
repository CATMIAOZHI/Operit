package com.ai.assistance.operit.features.reading

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.asContextElement
import org.json.JSONArray
import org.json.JSONObject

/** Propagates batch ownership through nested IO coroutines; it never resumes an old child chat. */
internal object ReadingTaskContext {
    private val current = ThreadLocal<String?>()
    val taskId: String? get() = current.get()
    fun element(taskId: String) = current.asContextElement(taskId)
}

/** Durable batches and their existing chapter runs share the reading database. */
internal class ReadingTaskRepository(private val store: ReadingCompanionStore) {
    fun hasImportedLegacy(): Boolean = store.readableDatabase.rawQuery(
        "SELECT 1 FROM reading_task_imports WHERE id = 1", null,
    ).use { it.moveToFirst() }

    fun importLegacy(records: JSONArray) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val imported = db.rawQuery("SELECT 1 FROM reading_task_imports WHERE id = 1", null).use { it.moveToFirst() }
            if (!imported) {
                repeat(records.length()) { save(records.getJSONObject(it)) }
                db.execSQL("INSERT INTO reading_task_imports(id) VALUES(1)")
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun recoverInterrupted() {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            listRecords().filter { it.optString("status") in ACTIVE }.forEach {
                it.put("status", "interrupted").put("updatedAt", System.currentTimeMillis())
                save(it)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun save(record: JSONObject) {
        store.writableDatabase.insertWithOnConflict("reading_tasks", null, ContentValues().apply {
            put("task_id", record.getString("task_id"))
            put("book_id", record.getString("bookId"))
            put("status", record.getString("status"))
            val requestId = record.optString("requestId").takeUnless { it.isBlank() || it == "null" }
            if (requestId == null) putNull("request_id") else put("request_id", requestId)
            put("payload", record.toString())
            put("created_at", record.getLong("createdAt"))
            put("updated_at", record.getLong("updatedAt"))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }

    fun findRequest(requestId: String): JSONObject? = store.readableDatabase.rawQuery(
        "SELECT payload FROM reading_tasks WHERE request_id = ?", arrayOf(requestId),
    ).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }

    fun get(id: String): JSONObject? = store.readableDatabase.rawQuery(
        "SELECT payload FROM reading_tasks WHERE task_id = ?", arrayOf(id),
    ).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }

    fun remove(id: String) { store.writableDatabase.delete("reading_tasks", "task_id = ?", arrayOf(id)) }

    fun listRecords(limit: Int? = null): List<JSONObject> = store.readableDatabase.rawQuery(
        "SELECT payload FROM reading_tasks ORDER BY created_at DESC, task_id DESC" +
            (limit?.let { " LIMIT ${it.coerceIn(1, 100)}" } ?: ""), null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) } }

    fun view(record: JSONObject): JSONObject {
        val result = JSONObject(record.toString())
        val db = store.readableDatabase
        db.rawQuery("SELECT name FROM books WHERE book_id = ?", arrayOf(record.getString("bookId"))).use {
            if (it.moveToFirst()) result.put("bookName", it.getString(0))
        }
        val attempts = JSONArray()
        db.rawQuery("""
            SELECT id, chapter_index, chapter_title, status, stage, subagent_run_id, child_chat_id,
                   actual_input_tokens, actual_output_tokens, started_at, finished_at, 0 AS archived
            FROM auto_comment_runs WHERE task_id = ?
            UNION ALL
            SELECT id, chapter_index, chapter_title, status, stage, subagent_run_id, child_chat_id,
                   actual_input_tokens, actual_output_tokens, started_at, finished_at, 1 AS archived
            FROM reading_task_attempts WHERE task_id = ? ORDER BY id
        """.trimIndent(), arrayOf(record.getString("task_id"), record.getString("task_id"))).use { cursor ->
            while (cursor.moveToNext()) {
                val attempt = JSONObject()
                val keys = listOf("runId", "chapterIndex", "chapterTitle", "status", "stage",
                    "subagentRunId", "childChatId", "inputTokens", "outputTokens", "startedAt", "finishedAt")
                keys.forEachIndexed { index, key ->
                    attempt.put(key, if (cursor.isNull(index)) JSONObject.NULL else
                        if (index in setOf(0, 1, 7, 8, 9, 10)) cursor.getLong(index) else cursor.getString(index))
                }
                attempt.put("archived", cursor.getInt(11) != 0)
                attempts.put(attempt)
            }
        }
        result.put("attempts", attempts)
        val completed = (0 until attempts.length()).map(attempts::getJSONObject)
            .filter { it.optString("status") == "generated" }.map { it.optInt("chapterIndex", -1) }.distinct()
        result.put("completedChapterIndices", JSONArray(completed))
        result.put("completedCount", maxOf(completed.size,
            record.optJSONObject("result")?.optInt("completedCount") ?: 0,
            record.optJSONObject("progress")?.optInt("completedCount") ?: 0))
        return result
    }

    companion object {
        val ACTIVE = setOf("queued", "running", "cancelling")
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS reading_tasks (
                task_id TEXT PRIMARY KEY NOT NULL, book_id TEXT NOT NULL, status TEXT NOT NULL,
                request_id TEXT UNIQUE, payload TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS reading_task_attempts (
                id INTEGER PRIMARY KEY, task_id TEXT NOT NULL, chapter_index INTEGER NOT NULL,
                chapter_title TEXT, status TEXT, stage TEXT, subagent_run_id TEXT, child_chat_id TEXT,
                actual_input_tokens INTEGER, actual_output_tokens INTEGER, started_at INTEGER, finished_at INTEGER
            )""")
            db.execSQL("CREATE TABLE IF NOT EXISTS reading_task_imports (id INTEGER PRIMARY KEY)")
            db.execSQL("CREATE INDEX IF NOT EXISTS reading_run_task ON auto_comment_runs(task_id)")
        }
    }
}
