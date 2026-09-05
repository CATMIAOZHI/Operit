package com.ai.assistance.operit.core.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ai.assistance.operit.util.AppLogger
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ToolErrorCount(val toolName: String, val count: Long, val failures: Long, val parameterIssues: Long)

data class ToolErrorPage(
    val counts: List<ToolErrorCount> = emptyList(),
    val records: List<ToolErrorRecord> = emptyList(),
    val total: Long = 0,
)

/** A separate diagnostic database: no chat history schema or message content is changed. */
class ToolErrorRepository internal constructor(context: Context) {
    companion object {
        const val PAGE_SIZE = 30
        @Volatile private var instance: ToolErrorRepository? = null

        fun getInstance(context: Context): ToolErrorRepository =
            instance ?: synchronized(this) {
                instance ?: ToolErrorRepository(context.applicationContext).also { instance = it }
            }
    }

    private val helper = object : SQLiteOpenHelper(context, "tool_errors.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE tool_errors (id TEXT PRIMARY KEY, occurred_at INTEGER NOT NULL, tool_name TEXT NOT NULL, execution_failed INTEGER NOT NULL, parameter_issue INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX tool_errors_time ON tool_errors(occurred_at DESC)")
            db.execSQL("CREATE INDEX tool_errors_tool_time ON tool_errors(tool_name, occurred_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private val writeError = MutableStateFlow(false)
    val recordingFailed = writeError.asStateFlow()

    /** Finish queued writes before closing an explicitly owned diagnostic store. */
    internal suspend fun close() = withContext(Dispatchers.IO) {
        scope.coroutineContext.job.children.toList().joinAll()
        scope.cancel()
        mutex.withLock { helper.close() }
    }

    fun reportRecordingFailure(error: Exception) {
        writeError.value = true
        AppLogger.e("ToolErrorRepository", "Failed to record tool diagnostics", error)
    }

    fun record(record: ToolErrorRecord) {
        scope.launch {
            try {
                mutex.withLock {
                    val values = ContentValues().apply {
                        put("id", record.id)
                        put("occurred_at", record.occurredAt)
                        put("tool_name", record.toolName)
                        put("execution_failed", if (record.executionFailed) 1 else 0)
                        put("parameter_issue", if (record.undeclaredParameters.isNotEmpty()) 1 else 0)
                        put("payload", record.toJson())
                    }
                    val inserted = helper.writableDatabase.insertWithOnConflict(
                        "tool_errors", null, values, SQLiteDatabase.CONFLICT_IGNORE,
                    )
                    if (inserted != -1L) revision.value += 1
                }
            } catch (e: Exception) {
                reportRecordingFailure(e)
            }
        }
    }

    suspend fun load(toolName: String?, since: Long, page: Int): ToolErrorPage =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = helper.readableDatabase
                val counts = counts(db, since)
                val records = mutableListOf<ToolErrorRecord>()
                val toolFilter = if (toolName == null) "" else " AND tool_name = ?"
                val arguments = if (toolName == null) arrayOf(since.toString()) else arrayOf(since.toString(), toolName)
                db.rawQuery(
                    "SELECT payload FROM tool_errors WHERE occurred_at >= ?$toolFilter ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?",
                    arguments + arrayOf(PAGE_SIZE.toString(), (page * PAGE_SIZE).toString()),
                ).use { cursor ->
                    while (cursor.moveToNext()) records += Json.decodeFromString<ToolErrorRecord>(cursor.getString(0))
                }
                ToolErrorPage(
                    counts, records,
                    counts.filter { toolName == null || it.toolName == toolName }.sumOf { it.count },
                )
            }
        }

    private fun counts(db: SQLiteDatabase, since: Long): List<ToolErrorCount> =
        buildList {
            db.rawQuery(
                "SELECT tool_name, COUNT(*), SUM(execution_failed), SUM(parameter_issue) FROM tool_errors WHERE occurred_at >= ? GROUP BY tool_name ORDER BY COUNT(*) DESC, tool_name",
                arrayOf(since.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) add(ToolErrorCount(cursor.getString(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3)))
            }
        }

    /** Stream all matching records, not just the visible page, from one consistent snapshot. */
    suspend fun export(output: OutputStream, toolName: String?, since: Long) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = helper.readableDatabase
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("summary.json"))
                    zip.write(Json.encodeToString(counts(db, since).filter {
                        toolName == null || it.toolName == toolName
                    }).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("errors.jsonl"))
                    val toolFilter = if (toolName == null) "" else " AND tool_name = ?"
                    val arguments = if (toolName == null) arrayOf(since.toString()) else arrayOf(since.toString(), toolName)
                    db.rawQuery(
                        "SELECT payload FROM tool_errors WHERE occurred_at >= ?$toolFilter ORDER BY occurred_at, id",
                        arguments,
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            zip.write(cursor.getString(0).toByteArray(Charsets.UTF_8))
                            zip.write('\n'.code)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
}
