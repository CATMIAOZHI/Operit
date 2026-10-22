package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.orbit.OrbitFiles
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.util.LocaleUtils
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Orbit's own files: people and groups, the daily log, the lessons book, settings files and files/. */
internal object OrbitFileTools {
    const val NAME = "orbit_file"
    val names = setOf(NAME)

    val prompt = ToolPrompt(
        name = NAME,
        description = "读写你自己文件夹里的文件：人物和群组笔记、每日流水、经验本、主动消息偏好、时区、对齐总结、设备，以及 files/ 里给用户做的文件。" +
            "删除的文件进回收站，30 天内可以恢复。还能把应用里别处的文件（比如画好的图）收进 files/，把 Markdown 导出成网页、Word 或 PDF。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "list/read/write/append/delete/trash/restore/daily/prompt_copy/import/export/send", required = true),
            ToolParameterSchema("path", description = "相对路径，如 memory/people/张三.md、LESSONS.md、files/报告.md；read/write/append/delete/export/send 必填；import 时是存到 files/ 下的目标路径（可省略）", required = false),
            ToolParameterSchema("text", description = "write：完整新内容；append/daily：要追加的一段或一行", required = false),
            ToolParameterSchema("folder", description = "list 可选：只列这个文件夹下的，如 memory/people、files、dreams、alignment", required = false),
            ToolParameterSchema("offset", "integer", "list 可选：从第几个开始列（从 0 开始），用上次返回的 next_offset 继续", false),
            ToolParameterSchema("start_line", "integer", "read 可选：从第几行开始读（从 1 开始），默认 1", false),
            ToolParameterSchema("line_count", "integer", "read 可选：最多读几行，默认读到 20000 字为止", false),
            ToolParameterSchema("start_char", "integer", "read 可选：只在接着读一行特别长的行时用，表示从 start_line 这一行的第几个字开始（从 1 开始）", false),
            ToolParameterSchema("trash_id", description = "restore：trash 返回的编号", required = false),
            ToolParameterSchema("source", description = "import：应用里已有文件的绝对路径，比如终端或其他工具生成的文件", required = false),
            ToolParameterSchema("format", description = "export：html、docx 或 pdf", required = false),
        ),
        details = """
  - 人物：memory/people/<名字>.md，一人一份；群组：memory/groups/<名字>.md。第一行写「# 名字」，下面写关系、近况、要记住的事。INDEX.md 由应用自动维护，不用写。
  - 每日流水：daily 往今天的 memory/YYYY-MM-DD.md 追加一行观察，最多 600 字，换行会变成空格。后台任务的运行结果会自动记进去。流水不是长期记忆，长期要记的事用记忆工具写进 memory.md（memory.md 不归这个工具管）。
  - LESSONS.md：做事的约定和教训。踩过坑、用户纠正过做法时，用 append 记一条。
  - PROACTIVE_PREFERENCES.md：用户对主动消息的偏好，四节：想听的、别跟我说、什么时候、怎么说。只改相关的那一节；一次拒绝不写成长期禁令。
  - timezones.yaml：用户的长期时区（home）和常联系的人的时区（others）。
  - ALIGNMENT_SYNTHESIS.md：对齐总结（和用户怎么相处），每晚对齐复盘重写。dreams/YYYY-MM-DD.md 是每晚复盘记录，alignment/ 下的 ALIGNMENT_STATE.yaml、PROGRESSION_HISTORY.yaml、REPAIR_THREADS.yaml 是复盘状态，只由复盘维护。
  - DEVICES.md：你能连上的设备，比如 Tailscale 组网里的电脑、NAS。不写密码。
  - files/：给用户做的文件，会出现在「构建」页的文件里。
  - read 一次最多返回 20000 字；没读完时末尾会写明读到第几行、下次怎么接着读（照着提示填 start_line，长行还要填 start_char）。list 一次最多 200 个，有 next_offset 时用 offset 接着列。
  - write 会覆盖整个文件，先 read 再改；返回会说明新建还是覆盖、现在几行，以及改了哪些行。delete 后告诉用户可以在回收站恢复。
  - prompt_copy：用户要你的系统提示词时用，把 Orbit 部分（Orbit 段落和 SOUL.md，不含应用通用规则）存成 files/ 里的一份文件，告诉用户这是 Orbit 部分，在构建页的文件里找。
  - send：把 files/ 里的一个文件直接发到聊天里，像真人发消息一样。图片显示成图片，用户点开能放大看，下面有「保存到相册」按钮；其他文件显示成附件卡，用户点一下用别的应用打开。只能在和用户的聊天里用。发完不要再贴路径。
  - import：把应用里别处的文件收进 files/（比如终端或其他工具生成的文件），用户就能在构建页的文件里看到。要给用户看，接着用 send 发到聊天里；音频会返回 markdown，可以放进回复里播放。
  - export：把 files/ 里的 Markdown 文件导出成 html（网页）、docx（Word）或 pdf，存在同一个文件夹，返回新文件路径（同名文件已存在时会自动改名）。要交给用户一份正式文档时先写 Markdown 再导出。支持标题、段落、列表、引用、代码、分割线和粗体斜体；表格会变成对齐的等宽文字，图片只留下名字。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val runtime = ToolExecutionManager.currentToolRuntimeContext()
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, runtime?.callerChatId)) { "This is not an Orbit chat" }
            val files = OrbitFiles.of(context, orbit.id)
            val args = tool.parameters.associate { it.name to it.value }
            fun arg(name: String) = args[name]?.takeIf { it.isNotBlank() } ?: error("Missing $name")
            val result: String = when (args["action"]) {
                "list" -> {
                    val folder = args["folder"]?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
                    val all = files.list(includeReview = true)
                        .filter { folder == null || it.path.startsWith("$folder/") }
                    val offset = args["offset"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: error("offset must be a whole number from 0; got \"$it\"")
                    } ?: 0
                    require(offset == 0 || offset < all.size) { "offset $offset is past the end; there are ${all.size} files" }
                    val page = all.drop(offset).take(MAX_LIST)
                    val next = (offset + page.size).takeIf { it < all.size }
                    JSONObject().put("total", all.size).put("offset", offset).put("returned", page.size)
                        .put("next_offset", next ?: JSONObject.NULL)
                        .put("files", JSONArray(page.map {
                            JSONObject().put("path", it.path).put("title", it.title)
                                .put("size", if (it.size < 0) "empty template" else it.size)
                        }))
                        .toString()
                }
                "read" -> paged(arg("path"), files.read(arg("path")), args["start_line"], args["line_count"], args["start_char"])
                "write" -> wrote(context, files.write(arg("path"), args["text"] ?: error("write needs text: the whole new content of the file")))
                "append" -> appended(files.append(arg("path"), arg("text")))
                "daily" -> {
                    val line = arg("text")
                    val cut = line.replace(Regex("\\s+"), " ").trim().length > OrbitFiles.MAX_DAILY_LINE
                    appended(files.appendDaily(line)) +
                        if (cut) " The line was longer than ${OrbitFiles.MAX_DAILY_LINE} characters and was cut; write the rest as another line." else ""
                }
                "delete" -> {
                    val item = files.delete(arg("path"))
                    "Moved to the trash (id ${item.id}); it can be restored for ${OrbitFiles.TRASH_DAYS} days"
                }
                "trash" -> JSONArray(files.trash().map {
                    JSONObject().put("trash_id", it.id).put("path", it.path).put("deleted_at", it.deletedAt)
                }).toString()
                "restore" -> "Restored to " + files.restore(arg("trash_id"))
                "send" -> {
                    check(OrbitPreferences.getInstance(context).current().orbitForChat(runtime?.callerChatId) != null) {
                        "send only works in a chat with the user"
                    }
                    val path = arg("path").trim().removePrefix("/")
                    require(path.startsWith("files/")) { "Only files under files/ can be sent" }
                    val file = files.fileFor(path)
                    require(file.isFile) { "No such file: $path" }
                    // A picture's shape, read without decoding it, so the chat sizes it before it loads.
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    if (file.extension.lowercase() in com.ai.assistance.operit.features.orbit.OrbitChatCards.imageTypes) {
                        runCatching { android.graphics.BitmapFactory.decodeFile(file.path, bounds) }
                    }
                    JSONObject().put("sent", true).put("path", path)
                        .put("note", "The file is in the chat now as an attachment. Do not paste its path.")
                        .put(com.ai.assistance.operit.features.orbit.OrbitChatCards.KEY,
                            com.ai.assistance.operit.features.orbit.OrbitChatCards.attachment(
                                orbit.id, path, file.name, file.length(), bounds.outWidth, bounds.outHeight))
                        .toString()
                }
                "import" -> {
                    val source = importable(context, arg("source"))
                    val target = args["path"]?.takeIf { it.isNotBlank() } ?: "files/${source.name}"
                    val entry = source.inputStream().use { files.saveBytes(target, it) }
                    JSONObject().put("path", entry.path)
                        .apply { mediaMarkdown(files.fileFor(entry.path))?.let { put("markdown", it) } }
                        .toString()
                }
                "export" -> {
                    val path = arg("path")
                    val format = arg("format").lowercase().removePrefix(".")
                    require(format in com.ai.assistance.operit.features.orbit.OrbitExport.FORMATS) { "format must be html, docx or pdf" }
                    require(path.startsWith("files/") && path.substringAfterLast('.').lowercase() in setOf("md", "txt")) {
                        "Export works on a .md or .txt file under files/"
                    }
                    val text = files.read(path)
                    val title = OrbitFiles.titleOf(text, path.substringAfterLast('/').substringBeforeLast('.'))
                    val bytes = java.io.ByteArrayOutputStream().also { out ->
                        when (format) {
                            "html" -> out.write(com.ai.assistance.operit.features.orbit.OrbitExport.toHtml(title, text).toByteArray())
                            "docx" -> com.ai.assistance.operit.features.orbit.OrbitExport.toDocx(text, out)
                            else -> com.ai.assistance.operit.features.orbit.OrbitExport.toPdf(text, out)
                        }
                    }.toByteArray()
                    val entry = files.saveBytes(path.substringBeforeLast('.') + ".$format", bytes.inputStream())
                    "Saved as ${entry.path}. The user finds it under Files in the Builds tab."
                }
                "prompt_copy" -> {
                    val english = LocaleUtils.usesEnglishPrompt(context)
                    val block = OrbitTools.promptParts(context, runtime?.callerChatId, english)?.all.orEmpty()
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    val path = "files/orbit-prompt-$stamp.md"
                    val text = if (english) {
                        "# Orbit's part of the system prompt\n\nPROMPT.md, the Orbit section, IDENTITY.md and SOUL.md. The app's shared rules and tool list are not included.\n\n$block\n"
                    } else {
                        "# Orbit 部分的系统提示词\n\n包含 PROMPT.md、Orbit 段落、IDENTITY.md 和 SOUL.md，不含应用通用规则和工具列表。\n\n$block\n"
                    }
                    val entry = files.saveBytes(path, text.byteInputStream())
                    "Saved as ${entry.path}. The user finds it under Files in the Builds tab."
                }
                else -> error("Unknown action \"${args["action"].orEmpty()}\"; use list, read, write, append, daily, delete, trash, restore, send, import, export or prompt_copy")
            }
            ToolResult(tool.name, true, StringResultData(result))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(tool.name, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    /** Only files inside the app's own folders and its Download/Operit folder can be taken in. */
    private fun importable(context: Context, path: String): java.io.File {
        val file = java.io.File(path.removePrefix("file://"))
        require(file.isFile) { "No such file: $path" }
        val real = file.toPath().toRealPath()
        val roots = listOfNotNull(context.filesDir, context.cacheDir, context.getExternalFilesDir(null),
            runCatching { com.ai.assistance.operit.util.OperitPaths.operitRootDir() }.getOrNull())
        require(roots.any { root -> runCatching { real.startsWith(root.toPath().toRealPath()) }.getOrDefault(false) }) {
            "Only files the app made (its own folders or Download/Operit) can be imported"
        }
        require(file.length() <= OrbitFiles.MAX_FILE_BYTES) { "File is larger than 50 MB" }
        return file
    }

    private fun lineCount(text: String): Int {
        val unix = text.replace("\r\n", "\n")
        return if (unix.isEmpty()) 0 else unix.removeSuffix("\n").lines().size
    }

    /**
     * What a write did, like the app's own file tools: the path, new or overwritten, its size now,
     * and the changed lines as a diff, so Orbit knows exactly what it put in the file.
     */
    private fun wrote(context: Context, w: OrbitFiles.Written): String {
        val size = "${lineCount(w.after)} lines, ${w.after.length} characters"
        val before = w.before
        return when {
            !w.existed -> "Created ${w.path} ($size)."
            before == null -> "Overwrote ${w.path} (now $size)."
            before == w.after -> "No change: ${w.path} already had this content ($size)."
            else -> {
                val old = before.replace("\r\n", "\n")
                val new = w.after.replace("\r\n", "\n")
                // The diff search grows with the square of the lines; a big rewrite only gets counts.
                if (old.length + new.length > MAX_DIFF_INPUT || lineCount(old) + lineCount(new) > MAX_DIFF_LINES) {
                    return "Overwrote ${w.path} (now $size; was ${lineCount(before)} lines). " +
                        "Too large to show the changes; read the file to check it."
                }
                val diff = com.ai.assistance.operit.api.chat.enhance.FileBindingService(context).generateUnifiedDiff(old, new)
                val shown = if (diff.length <= MAX_DIFF) diff
                else diff.take(MAX_DIFF).substringBeforeLast('\n') + "\n… (diff cut; read the file to see the rest)"
                "Overwrote ${w.path} (now $size; was ${lineCount(before)} lines).\n$shown"
            }
        }
    }

    /** What an append did: the file, the lines the new text landed on, and the file's size now. */
    private fun appended(w: OrbitFiles.Written): String {
        val total = lineCount(w.after)
        val first = (total - w.appendedLines + 1).coerceAtLeast(1)
        val lines = if (w.appendedLines <= 1) "line $total" else "lines $first-$total"
        val start = if (!w.existed) "Created ${w.path} and added" else "Added to ${w.path} at"
        return "$start $lines (the file now has $total lines, ${w.after.length} characters)."
    }

    private const val MAX_DIFF = 2000
    private const val MAX_LIST = 200
    private const val MAX_READ = 20_000

    /**
     * The lines asked for, up to [MAX_READ] characters. A whole file comes back as it is; a part
     * ends with where it stopped and where to go on.
     */
    private fun paged(path: String, text: String, start: String?, count: String?, startChar: String?): String {
        val lines = text.replace("\r\n", "\n").removeSuffix("\n").let { if (it.isEmpty()) emptyList() else it.lines() }
        val from = start?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: error("start_line must be a whole number from 1; got \"$it\"")
        } ?: 1
        val wanted = count?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: error("line_count must be a whole number from 1; got \"$it\"")
        } ?: Int.MAX_VALUE
        val charFrom = startChar?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: error("start_char must be a whole number from 1; got \"$it\"")
        } ?: 1
        if (lines.isEmpty()) return ""
        require(from <= lines.size) { "start_line $from is past the end; $path has ${lines.size} lines" }
        val first = lines[from - 1]
        require(charFrom == 1 || charFrom <= first.length) { "start_char $charFrom is past the end of line $from, which has ${first.length} characters" }
        // Inside one long line: the next slice of that line, then a pointer to the rest.
        if (charFrom > 1 || first.length > MAX_READ) {
            val end = minOf(first.length, charFrom - 1 + MAX_READ)
            val more = if (end < first.length) "Read on with start_line=$from start_char=${end + 1}."
                else if (from < lines.size) "Read on with start_line=${from + 1}." else "This is the end of the file."
            return first.substring(charFrom - 1, end) +
                "\n\n[Line $from of ${lines.size} in $path, characters ${charFrom}-$end of ${first.length}. $more]"
        }
        val out = StringBuilder()
        var last = from - 1
        while (last < lines.size && last - from + 1 < wanted) {
            val line = lines[last]
            val started = last > from - 1
            // A line that does not fit is left whole for the next read, which pages through it if it is long.
            if (started && (out.length + line.length + 1 > MAX_READ || line.length > MAX_READ)) break
            if (started) out.append('\n')
            out.append(line)
            last++
        }
        if (from == 1 && last == lines.size && text.length <= MAX_READ + 1) return text
        val more = if (last < lines.size) " Read on with start_line=${last + 1}." else ""
        return "$out\n\n[Lines $from-$last of ${lines.size} in $path.$more]"
    }
    private const val MAX_DIFF_INPUT = 40_000
    private const val MAX_DIFF_LINES = 1_500

    /**
     * Pictures and audio can be shown in the chat; other files are only listed in the Builds tab.
     * The chat's renderer reads the first brackets and parentheses, so both are kept out of the
     * label and escaped in the address.
     */
    fun mediaMarkdown(file: java.io.File): String? {
        if (file.extension.lowercase() !in setOf("png", "jpg", "jpeg", "webp", "gif", "mp3", "wav", "m4a", "ogg", "oga", "aac", "flac", "opus", "weba")) return null
        val uri = android.net.Uri.fromFile(file).toString().replace("(", "%28").replace(")", "%29")
        val label = file.nameWithoutExtension.replace(Regex("[\\[\\]()]"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "file" }
        return "![$label]($uri)"
    }
}
