package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Separate from execution failures: a repair is a routing event, not a failed tool call. */
internal object ToolCallRepairLogger {
    const val FILE_NAME = "tool_call_repairs.jsonl"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    const val PAGE_SIZE = 30

    data class Page(val total: Int, val records: List<String>)

    suspend fun load(context: Context, page: Int): Page = withContext(Dispatchers.IO) {
        mutex.withLock { readPage(File(context.applicationContext.filesDir, FILE_NAME), page) }
    }

    internal fun readPage(file: File, page: Int): Page {
        require(page >= 0)
        if (!file.exists()) return Page(0, emptyList())
        val total = file.useLines { lines -> lines.count { it.isNotBlank() } }
        val end = (total.toLong() - page.toLong() * PAGE_SIZE).coerceAtLeast(0).toInt()
        val start = (end - PAGE_SIZE).coerceAtLeast(0)
        val records = file.useLines { lines ->
            lines.filter { it.isNotBlank() }.drop(start).take(end - start).toList().asReversed()
        }
        return Page(total, records)
    }

    internal fun entry(repair: ToolCallRepairRouter.Repair, occurredAt: Long): String =
        JSONObject()
            .put("occurredAt", occurredAt)
            .put("callId", repair.original.callId ?: JSONObject.NULL)
            .put("invocationIndex", repair.original.invocationIndex)
            .put("originalToolName", repair.originalToolName)
            .put("targetToolName", repair.targetToolName)
            .put("rule", repair.rule)
            .put("rules", JSONArray(repair.rules))
            .put("originalParameterNames", JSONArray(repair.originalParameterNames))
            .put("parameterNames", JSONArray(repair.parameterNames))
            .toString()

    fun record(context: Context, repair: ToolCallRepairRouter.Repair) {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        val line = entry(repair, System.currentTimeMillis())
        scope.launch {
            try {
                mutex.withLock {
                    file.appendText("$line\n", Charsets.UTF_8)
                    revision.value += 1
                }
            } catch (error: Exception) {
                AppLogger.e("ToolCallRepairLogger", "Failed to write tool repair log", error)
            }
        }
    }
}
