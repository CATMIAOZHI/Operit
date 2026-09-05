package com.ai.assistance.operit.features.reading

import android.content.Context
import android.util.AtomicFile
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Owns manual generation beyond the lifetime of the requesting tool call or UI page. */
class ReadingCompanionTasks private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val records = linkedMapOf<String, JSONObject>()
    private val jobs = mutableMapOf<String, Job>()
    private val file = AtomicFile(File(appContext.filesDir, "reading_companion_tasks.json"))

    init {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val saved = file.openRead().use { JSONArray(it.readBytes().toString(Charsets.UTF_8)) }
            for (index in 0 until saved.length()) {
                val record = saved.getJSONObject(index)
                if (record.optString("status") in ACTIVE) {
                    record.put("status", "interrupted")
                    record.put("updatedAt", System.currentTimeMillis())
                }
                records[record.getString("task_id")] = record
            }
            persist()
        }
    }

    @Synchronized
    fun start(
        kind: String,
        bookId: String,
        count: Int,
        startChapterIndex: Int?,
        endChapterIndex: Int?,
        mode: String = "fill_missing",
        runtime: ToolExecutionManager.ToolRuntimeContext? = null,
        requestId: String? = null,
    ): JSONObject {
        require(kind == "summary" || kind == "commentary") { "kind must be summary or commentary" }
        require(bookId.isNotBlank()) { "book_id is required" }
        val maxCount = if (kind == "summary") ReadingCompanionService.MAX_MANUAL_BATCH_BUDGET
            else AutoCommentSupport.MAX_PREFETCH_AHEAD_CHAPTERS
        require(count in 1..maxCount) { "count must be between 1 and $maxCount" }
        require(startChapterIndex == null || startChapterIndex >= 0)
        require(endChapterIndex == null || endChapterIndex >= 0)
        require(startChapterIndex == null || endChapterIndex == null || endChapterIndex >= startChapterIndex)
        require(mode == "fill_missing" || (kind == "commentary" && mode == "regenerate"))
        if (mode == "regenerate") {
            require(startChapterIndex != null && endChapterIndex != null)
            require(endChapterIndex - startChapterIndex + 1 <= maxCount)
        }
        val request = JSONObject()
            .put("kind", kind).put("bookId", bookId).put("count", count).put("mode", mode)
            .put("startChapterIndex", startChapterIndex ?: JSONObject.NULL)
            .put("endChapterIndex", endChapterIndex ?: JSONObject.NULL)
        val normalizedRequestId = requestId?.trim()?.takeIf(String::isNotEmpty)
        if (normalizedRequestId != null) {
            records.values.firstOrNull { it.optString("requestId") == normalizedRequestId }?.let {
                val old = it.getJSONObject("request")
                require(request.keys().asSequence().all { key -> request.get(key) == old.get(key) }) {
                    "request_id already belongs to a different request"
                }
                return copy(it)
            }
        }
        val lease = ManualBatchGate.acquire(if (kind == "summary") "summaries" else "comments", bookId)
            ?: throw IllegalStateException("已有其他批次正在生成，请等待完成后再试")
        val id = UUID.randomUUID().toString()
        val record = JSONObject()
            .put("task_id", id).put("taskId", id).put("kind", kind).put("bookId", bookId)
            .put("status", "queued").put("request", request)
            .put("requestId", normalizedRequestId ?: JSONObject.NULL)
            .put("createdAt", System.currentTimeMillis()).put("updatedAt", System.currentTimeMillis())
        try {
            records[id] = record
            persist()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    ensureActive()
                    update(id) { it.put("status", "running") }
                    val result = withContext(ToolExecutionManager.toolRuntimeContextElement(runtime)) {
                        val progress: (JSONObject) -> Unit = { value ->
                            update(id) { it.put("progress", value) }
                        }
                        if (kind == "summary") {
                            ReadingCompanionService.getInstance(appContext).manualBatchSummaries(
                                batchId = id, count = count, startChapterIndex = startChapterIndex,
                                endChapterIndex = endChapterIndex, runtime = runtime, bookId = bookId,
                                onProgress = progress,
                            )
                        } else {
                            ReadingCompanionAutoCommentary.getInstance(appContext).generateManualBatch(
                                count = count, startChapterIndex = startChapterIndex,
                                endChapterIndex = endChapterIndex,
                                scope = if (mode == "regenerate") MANUAL_COMMENTARY_SCOPE_READ
                                    else MANUAL_COMMENTARY_SCOPE_AHEAD,
                                batchId = id, expectedBookId = bookId, runtime = runtime,
                                onProgress = progress,
                            )
                        }
                    }
                    update(id) {
                        val status = when {
                            it.optBoolean("cancelRequested") || result.optString("status") == "stopped" -> "cancelled"
                            result.optInt("failedCount") > 0 -> "completed_with_failures"
                            else -> "completed"
                        }
                        it.put("status", status).put("result", compactResult(result))
                    }
                } catch (cancelled: CancellationException) {
                    update(id) {
                        it.put("status", "cancelled").put(
                            "result",
                            compactResult(it.optJSONObject("progress") ?: JSONObject()).put("status", "stopped"),
                        )
                    }
                    throw cancelled
                } catch (error: Exception) {
                    update(id) { it.put("status", "failed").put("error", safeReadingCompanionError(error)) }
                }
            }
            jobs[id] = job
            // Completion also runs if a lazy job is cancelled before its body is entered.
            job.invokeOnCompletion {
                synchronized(this) {
                    try {
                        if (records[id]?.optString("status") in ACTIVE) {
                            update(id) { it.put("status", "cancelled") }
                        }
                    } finally {
                        jobs.remove(id)
                        ManualBatchGate.release(lease)
                    }
                }
            }
            job.start()
            return copy(record)
        } catch (error: Exception) {
            records.remove(id)
            ManualBatchGate.release(lease)
            throw error
        }
    }

    @Synchronized
    fun get(taskId: String): JSONObject =
        copy(requireNotNull(records[taskId]) { "Unknown task_id" })

    @Synchronized
    fun list(limit: Int = 20): JSONObject = JSONObject().put(
        "tasks", JSONArray(records.values.toList().asReversed().take(limit.coerceIn(1, 100)).map(::copy)),
    )

    @Synchronized
    fun cancel(taskId: String): JSONObject {
        val record = requireNotNull(records[taskId]) { "Unknown task_id" }
        if (record.optString("status") in ACTIVE) {
            update(taskId) { it.put("status", "cancelling").put("cancelRequested", true) }
            if (record.getString("kind") == "summary") {
                ReadingCompanionService.getInstance(appContext).requestManualSummaryBatchStop(taskId)
            } else {
                ReadingCompanionAutoCommentary.getInstance(appContext).requestManualCommentaryBatchStop(taskId)
            }
            jobs[taskId]?.cancel()
        }
        return copy(record)
    }

    @Synchronized
    fun cancelAll() {
        records.keys.toList().forEach(::cancel)
    }

    @Synchronized
    private fun update(id: String, change: (JSONObject) -> Unit) {
        val record = records.getValue(id)
        change(record)
        record.put("updatedAt", System.currentTimeMillis())
        persist()
    }

    private fun persist() {
        val stream = file.startWrite()
        try {
            stream.write(JSONArray(records.values.toList()).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun copy(record: JSONObject) = JSONObject(record.toString())

    companion object {
        // Chapter bodies and generated prose stay in their existing files and audit records.
        internal fun compactResult(result: JSONObject): JSONObject = JSONObject().apply {
            for (key in listOf(
                "status", "targetChapterIndices", "modelTaskCount", "completedCount",
                "processedCount", "failedCount", "failures", "remainingMissing",
                "unavailableCount", "scanComplete", "supersededChapterIndex",
            )) {
                if (result.has(key)) put(key, result.get(key))
            }
        }

        private val ACTIVE = setOf("queued", "running", "cancelling")
        @Volatile private var instance: ReadingCompanionTasks? = null

        fun getInstance(context: Context): ReadingCompanionTasks =
            instance ?: synchronized(this) {
                instance ?: ReadingCompanionTasks(context).also { instance = it }
            }
    }
}
