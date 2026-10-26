package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitFiles
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.ui.features.toolbox.screens.logcat.LogcatExportHelper
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports the app log or the tool-error records into Orbit's own files/logs/, as text Orbit can
 * read to look into a problem itself, or as a zip it can send the user to pass on.
 */
internal object OrbitLogTools {
    const val NAME = "orbit_logs"

    val prompt = ToolPrompt(
        name = NAME,
        description = "把应用日志或工具调用错误记录导出到你自己的 files/logs/。导出后你可以自己读来排查问题，也可以发给用户，让用户转给 Operit 开发者或附在 GitHub issue 里。",
        parametersStructured = listOf(
            ToolParameterSchema("kind", description = "app：应用日志；tool_errors：工具调用错误记录", required = true),
            ToolParameterSchema("format", description = "text（默认）：存成 .txt，自己用 orbit_file read 读；zip：完整不截断、体积小，适合发给用户", required = false),
            ToolParameterSchema("hours", "number", "可选：只导出最近几小时的记录，如 1、0.5、24", false),
            ToolParameterSchema("keyword", description = "可选：只保留包含这个词的记录（不分大小写），如 Exception、某个工具名、某个模块名", required = false),
            ToolParameterSchema("tool", description = "可选，只用于 tool_errors：只导出这个工具的错误", required = false),
        ),
        details = """
  - 用户说某个功能出错、卡住、没反应，或者你自己的工具反复失败、想查原因时用。
  - kind=app：应用的运行记录，包括各模块的报错和异常，以及每次 AI 请求的耗时（搜 LlmPerf）。kind=tool_errors：工具调用出问题的记录，每条一行 JSON，写着工具名、参数和错误信息；executionFailed=true 是调用失败，undeclaredParameters 不为空是传了工具没有的参数（这种调用可能仍然成功）。
  - format=text：可以直接用 orbit_file read 读。文件最多大约 60 万字节，超出时只保留最新的记录（最新的在最后），单条特别长的记录会截断。想看得更准，就用 hours 缩小时间范围，或者用 keyword 只留相关记录，然后再导出一次。
  - format=zip：完整、不截断、体积小，orbit_file read 读不了。要交给用户时，用 orbit_file send 发到聊天里，用户可以转给开发者，或者附到 GitHub issue。
  - 日志里可能有聊天内容和请求内容。发到 GitHub 这类公开的地方之前，先提醒用户检查一下。
  - 文件存在 files/logs/，用户在构建页的文件里也能看到。用完可以用 orbit_file delete 删掉。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val runtime = ToolExecutionManager.currentToolRuntimeContext()
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, runtime?.callerChatId)) { "This is not an Orbit chat" }
            val files = OrbitFiles.of(context, orbit.id)
            val args = tool.parameters.associate { it.name to it.value.trim() }
            val kind = args["kind"].orEmpty().lowercase()
            require(kind == "app" || kind == "tool_errors") { "kind must be app or tool_errors; got \"${args["kind"].orEmpty()}\"" }
            val format = args["format"].orEmpty().lowercase().ifEmpty { "text" }
            require(format == "text" || format == "zip") { "format must be text or zip; got \"$format\"" }
            val hours = args["hours"]?.takeIf { it.isNotEmpty() }?.let {
                it.toDoubleOrNull()?.takeIf { h -> h > 0 && h <= 24 * 366 } ?: error("hours must be a number above 0; got \"$it\"")
            }
            val since = hours?.let { System.currentTimeMillis() - (it * 3_600_000).toLong() } ?: 0L
            val keyword = args["keyword"]?.takeIf { it.isNotEmpty() }
            require(keyword == null || keyword.length <= MAX_KEYWORD) { "keyword is too long; use at most $MAX_KEYWORD characters" }
            val toolName = args["tool"]?.takeIf { it.isNotEmpty() }
            require(toolName == null || toolName.length <= MAX_KEYWORD) { "tool is too long; use the tool's name" }
            require(toolName == null || kind == "tool_errors") { "tool only works with kind=tool_errors" }

            val filter = Filter(since.takeIf { hours != null }, keyword)
            // Written out first, so the newest lines are in the file; what is still missing is reported.
            val flush = if (kind == "app") AppLogger.flushFileLogsWithResult() else null
            val missing = flush?.takeUnless { it.complete }?.let {
                "Some of the newest log lines may be missing (pending ${it.pendingRecords}, dropped ${it.droppedRecords}" +
                    (if (it.writeFailed) ", a write failed" else "") + (if (it.interrupted) ", interrupted" else "") + ")." +
                    (if (it.pendingRecords > 0) " The pending ones are still being written; export again in a moment to get them." else "") +
                    (if (it.droppedRecords > 0 || it.writeFailed) " Dropped or unwritten lines are gone and will not come back." else "")
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val baseName = if (kind == "app") "operit_log_$stamp" else "operit_tool_errors_$stamp"
            val temporary = File.createTempFile("orbit-log-", ".tmp", context.cacheDir)
            try {
                val written: Written = temporary.outputStream().buffered().use { out ->
                    when {
                        format == "text" -> writeText(context, kind, filter, toolName, missing, out)
                        kind == "app" && !filter.active -> Written(
                            matched = if (LogcatExportHelper.writeZipTo(context, out, "$baseName.txt")) -1 else 0,
                        )
                        kind == "tool_errors" && filter.keyword == null -> {
                            val repository = ToolErrorRepository.getInstance(context)
                            val count = repository.count(toolName, since).toInt()
                            if (count > 0) repository.export(out, toolName, since)
                            Written(matched = count)
                        }
                        else -> writeZip(context, kind, filter, toolName, baseName, out)
                    }
                }
                if (written.matched == 0) {
                    return@runBlockingIoPreservingToolRuntimeContext ToolResult(tool.name, true, StringResultData(
                        JSONObject().put("saved", false)
                            .put("reason", "No records matched" + filter.describe(toolName) + ". Nothing was saved; try a longer hours or another keyword.")
                            .apply { missing?.let { put("incomplete", it) } }
                            .toString()))
                }
                val path = "files/logs/$baseName.${if (format == "text") "txt" else "zip"}"
                val entry = temporary.inputStream().use { files.saveBytes(path, it) }
                val size = files.fileFor(entry.path).length()
                val result = JSONObject().put("saved", true).put("path", entry.path).put("kind", kind).put("format", format)
                    .put("size_bytes", size)
                missing?.let { result.put("incomplete", it) }
                if (written.matched > 0) result.put("matched_records", written.matched)
                if (format == "text") {
                    result.put("kept_records", written.kept).put("lines", written.lines)
                        .put("cut_to_newest", written.kept < written.matched)
                    written.from?.let { result.put("from", it) }
                    written.to?.let { result.put("to", it) }
                    if (written.longCut > 0) result.put("long_records_cut", written.longCut)
                    result.put("next", "Read it with orbit_file action=read path=${entry.path} (newest records are at the end; " +
                        "start_line=${(written.lines - 300).coerceAtLeast(1)} reads the last 300 lines). " +
                        (if (written.kept < written.matched) "Only the newest records fit; narrow it with hours or keyword to see older ones. " else "") +
                        "To give it to the user, orbit_file action=send path=${entry.path}.")
                } else {
                    result.put("next", "A zip cannot be read with orbit_file; export format=text to read it yourself. " +
                        "To give it to the user, orbit_file action=send path=${entry.path}.")
                }
                ToolResult(tool.name, true, StringResultData(result.toString()))
            } finally {
                temporary.delete()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(tool.name, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    private class Filter(val since: Long?, val keyword: String?) {
        val active get() = since != null || keyword != null
        fun keeps(time: Long?, text: String): Boolean =
            (since == null || (time != null && time >= since)) && (keyword == null || text.contains(keyword, ignoreCase = true))
        fun describe(tool: String?): String = buildList {
            if (since != null) add("since ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(since))}")
            if (keyword != null) add("keyword \"$keyword\"")
            if (tool != null) add("tool $tool")
        }.let { if (it.isEmpty()) "" else " (${it.joinToString(", ")})" }
    }

    /** [matched] is -1 when the count is not known (the whole log as one zip). */
    private class Written(
        val matched: Int,
        val kept: Int = 0,
        val lines: Int = 0,
        val from: String? = null,
        val to: String? = null,
        val longCut: Int = 0,
    )

    private class Record(val time: Long?, val stamp: String?, val text: String)

    /**
     * The app log's records (a line with a time and the lines under it), or each tool error.
     * [maxBuffer] drops a record's extra lines past it; a zip passes no limit, so it stays whole.
     */
    private suspend fun forEachRecord(
        context: Context, kind: String, toolName: String?, since: Long?, maxBuffer: Int, action: (Record) -> Unit,
    ) {
        if (kind == "tool_errors") {
            ToolErrorRepository.getInstance(context).forEachPayload(toolName, since ?: 0L) { payload ->
                val time = runCatching { JSONObject(payload).optLong("occurredAt", 0L).takeIf { it > 0 } }.getOrNull()
                action(Record(time, time?.let { stampFormat().format(Date(it)) }, payload))
            }
            return
        }
        val file = AppLogger.getLogFile()?.takeIf { it.isFile } ?: return
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { isLenient = false }
        var head: String? = null
        var time: Long? = null
        val body = StringBuilder()
        fun emit() {
            if (body.isNotEmpty()) action(Record(time, head, body.toString()))
            body.setLength(0)
        }
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                val lineTime = if (startsRecord(line)) format.parse(line.substring(0, 23), ParsePosition(0))?.time else null
                if (lineTime != null) {
                    emit()
                    head = line.substring(0, 23)
                    time = lineTime
                } else if (body.length > maxBuffer) {
                    return@forEach
                }
                if (body.isNotEmpty()) body.append('\n')
                body.append(line)
            }
        }
        emit()
    }

    /** An AppLogger line head: "yyyy-MM-dd HH:mm:ss.SSS L/Tag: ". A time inside a message does not count. */
    private fun startsRecord(line: String) =
        line.length >= 28 && line[4] == '-' && line[7] == '-' && line[10] == ' ' && line[13] == ':' &&
            line[16] == ':' && line[19] == '.' && line[23] == ' ' && line[24] in "VDIWEA" && line[25] == '/' &&
            line.indexOf(": ", 26) > 26 && line.substring(0, 4).all { it.isDigit() }

    private fun stampFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** The newest matching records that fit a file orbit_file can read, long ones cut. */
    private suspend fun writeText(context: Context, kind: String, filter: Filter, toolName: String?, missing: String?, out: OutputStream): Written {
        val kept = ArrayDeque<String>()
        var keptBytes = 0L
        var matched = 0
        var longCut = 0
        val stamps = ArrayDeque<String?>()
        forEachRecord(context, kind, toolName, filter.since, MAX_RECORD_BUFFER) { record ->
            if (!filter.keeps(record.time, record.text)) return@forEachRecord
            matched++
            val text = if (record.text.length > MAX_TEXT_RECORD) {
                longCut++
                record.text.take(MAX_TEXT_RECORD) + " …[cut: ${record.text.length} characters in all; export format=zip for the whole record]"
            } else record.text
            kept.addLast(text)
            stamps.addLast(record.stamp)
            keptBytes += text.toByteArray(Charsets.UTF_8).size + 1
            while (keptBytes > MAX_TEXT_BYTES && kept.size > 1) {
                keptBytes -= kept.removeFirst().toByteArray(Charsets.UTF_8).size + 1
                stamps.removeFirst()
            }
        }
        if (matched == 0) return Written(0)
        val first = stamps.firstOrNull { it != null }
        val last = stamps.lastOrNull { it != null }
        val header = buildString {
            append(if (kind == "app") "# Operit app log" else "# Operit tool errors (one JSON record per line)")
            append(" · exported ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            append(filter.describe(toolName))
            append("\n# $matched records matched")
            if (kept.size < matched) append(", the newest ${kept.size} kept")
            append("\n")
            missing?.let { append("# ").append(it).append("\n") }
        }
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write(header)
        var lines = header.count { it == '\n' }
        kept.forEach { text ->
            writer.write(text)
            writer.write("\n")
            lines += text.count { it == '\n' } + 1
        }
        writer.flush()
        return Written(matched, kept.size, lines, first, last, longCut)
    }

    /** Every matching record in full, zipped. */
    private suspend fun writeZip(context: Context, kind: String, filter: Filter, toolName: String?, baseName: String, out: OutputStream): Written {
        var matched = 0
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(if (kind == "app") "$baseName.txt" else "errors.jsonl"))
            forEachRecord(context, kind, toolName, filter.since, Int.MAX_VALUE) { record ->
                if (!filter.keeps(record.time, record.text)) return@forEachRecord
                matched++
                zip.write(record.text.toByteArray(Charsets.UTF_8))
                zip.write('\n'.code)
            }
            zip.closeEntry()
        }
        return Written(matched)
    }

    /** The records; the header (filters are capped at [MAX_KEYWORD]) stays within the rest of 600,000 bytes. */
    private const val MAX_TEXT_BYTES = 596_000L
    private const val MAX_KEYWORD = 200
    private const val MAX_TEXT_RECORD = 6_000
    /** A record's extra lines past this are dropped while reading, so one huge stack never fills memory. */
    private const val MAX_RECORD_BUFFER = 2_000_000
}
