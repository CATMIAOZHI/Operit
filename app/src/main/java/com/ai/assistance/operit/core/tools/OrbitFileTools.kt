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
        description = "你文件夹里的 Orbit 专属操作：列出笔记和文件、写每日流水、把文件发进聊天、把应用别处的文件收进 files/、" +
            "把 Markdown 导出成网页、Word 或 PDF、保存 Orbit 部分的系统提示词。读、改、新建、删除文件用应用的文件工具。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "list/daily/send/import/export/prompt_copy", required = true),
            ToolParameterSchema("path", description = "短路径，如 files/报告.md；也可以填你文件夹里的完整路径。send/export 必填；import 时是存到 files/ 下的目标路径（可省略）", required = false),
            ToolParameterSchema("text", description = "daily：要追加的一行", required = false),
            ToolParameterSchema("folder", description = "list 可选：只看这个文件夹下的，如 memory/people、files、files/reading/三体/text", required = false),
            ToolParameterSchema("offset", "integer", "list 可选：从第几个开始列（从 0 开始），用上次返回的 next_offset 继续", false),
            ToolParameterSchema("source", description = "import：应用里已有文件的绝对路径，比如终端或其他工具生成的文件", required = false),
            ToolParameterSchema("format", description = "export：html、docx 或 pdf", required = false),
        ),
        details = """
  - 读、改、新建、删除文件用应用的文件工具（environment=android），path 填完整路径：你的 Orbit 文件夹路径 + 短路径（文件夹路径写在运行信息里）。读用 read_file，按行读用 read_file_part；改一处用 edit_file（old 必须和文件原文一字不差，对不上就不会改，先读再改）；新建或整篇重写用 write_file；删除用 delete_file。删除是永久删除，没有回收站，除非用户明确要删，先确认。搜文字用 grep_code。
  - 人物：memory/people/<名字>.md，一人一份；圈子与关系：memory/groups/<圈子名>.md，一圈一份，记录家人、同事、朋友等圈子的成员、彼此关系、用户在其中的角色及共同经历，不是创建聊天群。只为用户明确提到的人和圈子建档，不靠推断新建；只记录已知事实，不猜测关系。第一行写「# 名字」，下面写关系、近况、要记住的事。INDEX.md 由应用自动维护，不用写。
  - 每日流水：daily 往今天的 memory/YYYY-MM-DD.md 追加一行观察，最多 600 字，换行会变成空格。后台任务的运行结果会自动记进去。流水不是长期记忆，长期要记的事用记忆工具写进 memory.md。
  - user.md 和 memory/memory.md 本来就全文在你的上下文里，要改用 memory_review。SOUL.md、IDENTITY.md 用 orbit_soul。
  - LESSONS.md：做事的约定和教训。踩过坑、用户纠正过做法时，记一条。
  - PROACTIVE_PREFERENCES.md：用户对主动消息的偏好，四节：想听的、别跟我说、什么时候、怎么说。只改相关的那一节；一次拒绝不写成长期禁令。
  - timezones.yaml：用户的长期时区（home）和常联系的人的时区（others）。
  - ALIGNMENT_SYNTHESIS.md：对齐总结（和用户怎么相处），每晚对齐复盘重写。dreams/YYYY-MM-DD.md 是每晚复盘记录，alignment/ 下的 ALIGNMENT_STATE.yaml、PROGRESSION_HISTORY.yaml、REPAIR_THREADS.yaml 是复盘状态，只由复盘维护。
  - DEVICES.md：你能连上的设备，比如 Tailscale 组网里的电脑、NAS。不写密码。
  - files/：给用户做的文件，会出现在「构建」页的文件里。
  - list 一次最多 200 个，有 next_offset 时用 offset 接着列。path、folder 也接受你文件夹里的完整路径，会自动换成短路径；文件夹外的完整路径不行。
  - prompt_copy：用户要你的系统提示词时用，把 Orbit 部分（Orbit 段落和 SOUL.md，不含应用通用规则）存成 files/ 里的一份文件，告诉用户这是 Orbit 部分，在构建页的文件里找。
  - send：把 files/ 里的一个文件直接发到聊天里，像真人发消息一样。图片显示成图片，用户点开全屏看，底部有「保存」按钮；文本文件（txt、md、json、csv 等）点开在应用里直接显示；其他文件显示成附件卡，用户点一下用别的应用打开。只能在和用户的聊天里用。发完不要再贴路径。
  - import：把应用里别处的文件收进 files/（比如终端或其他工具生成的文件），用户就能在构建页的文件里看到。要给用户看，接着用 send 发到聊天里；音频会返回 markdown，可以放进回复里播放。
  - export：把 files/ 里的 Markdown 文件导出成 html（网页）、docx（Word）或 pdf，存在同一个文件夹，返回新文件路径（同名文件已存在时会自动改名）。要交给用户一份正式文档时先写 Markdown 再导出。支持标题、段落、列表、引用、代码、分割线和粗体斜体；表格会变成对齐的等宽文字，图片只留下名字。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext { run(context, tool) }

    /** Runs in the tool call's coroutine, so stopping the chat also stops a long file operation. */
    suspend fun run(context: Context, tool: AITool): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val runtime = ToolExecutionManager.currentToolRuntimeContext()
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, runtime?.callerChatId)) { "This is not an Orbit chat" }
            val files = OrbitFiles.of(context, orbit.id)
            val root = OrbitPreferences.getInstance(context).orbitDir(orbit.id)
            // A full path inside this Orbit's folder (from grep_code, the terminal...) becomes the short one.
            val args = tool.parameters.associate { p ->
                p.name to if (p.name == "path" || p.name == "folder") p.value?.let { shortPath(root, it) } else p.value
            }
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
                            JSONObject().put("path", java.io.File(root, it.path).absolutePath).put("title", it.title)
                                .put("size", if (it.size < 0) "not created yet; create it with write_file" else it.size)
                        }))
                        .toString()
                }
                "daily" -> {
                    val line = arg("text")
                    val cut = line.replace(Regex("\\s+"), " ").trim().length > OrbitFiles.MAX_DAILY_LINE
                    appended(files.appendDaily(line)) +
                        if (cut) " The line was longer than ${OrbitFiles.MAX_DAILY_LINE} characters and was cut; write the rest as another line." else ""
                }
                "send" -> {
                    val settings = OrbitPreferences.getInstance(context).current()
                    check(settings.orbitForChat(runtime?.callerChatId) != null) {
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
                    // The chat keeps its own copy, so later edits to the file do not change what was sent.
                    val copy = files.snapshotForChat(path, com.ai.assistance.operit.features.orbit.OrbitUploads.cleanup(settings))
                    JSONObject().put("sent", true).put("path", path)
                        .put("note", "The file is in the chat now as an attachment. The chat keeps a copy of it as it is now; " +
                            "later changes to $path do not change it. Do not paste its path.")
                        .put(com.ai.assistance.operit.features.orbit.OrbitChatCards.KEY,
                            com.ai.assistance.operit.features.orbit.OrbitChatCards.attachment(
                                orbit.id, copy, file.name, file.length(), bounds.outWidth, bounds.outHeight))
                        .toString()
                }
                "import" -> {
                    val source = importable(context, arg("source"))
                    val target = args["path"]?.takeIf { it.isNotBlank() } ?: "files/${source.name}"
                    val entry = source.inputStream().use { files.saveBytes(target, it) }
                    JSONObject().put("path", entry.path).put("absolute_path", files.fileFor(entry.path).absolutePath)
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
                    "Saved as ${entry.path} (full path ${files.fileFor(entry.path).absolutePath}). The user finds it under Files in the Builds tab."
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
                    "Saved as ${entry.path} (full path ${files.fileFor(entry.path).absolutePath}). The user finds it under Files in the Builds tab."
                }
                else -> error(when (args["action"]) {
                    "read", "write", "append", "delete" -> "orbit_file no longer reads or changes files. Use the app's file tools " +
                        "(environment=android) with the full path ${root.absolutePath}/<short path>: read_file or read_file_part to read, " +
                        "edit_file to change part of a file, write_file to create or rewrite one, delete_file to delete."
                    else -> "Unknown action \"${args["action"].orEmpty()}\"; use list, daily, send, import, export or prompt_copy"
                })
            }
            ToolResult(tool.name, true, StringResultData(result))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(tool.name, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    /**
     * [path] as orbit_file takes it. A full path inside [root] (this Orbit's folder) becomes the short
     * path; a full path anywhere else is refused; a short path, with or without a leading "/", is kept.
     */
    internal fun shortPath(root: java.io.File, path: String): String {
        val raw = path.trim().removePrefix("file://")
        val file = java.io.File(raw)
        if (!file.isAbsolute) return raw
        // "." and ".." would be folded away before a link inside the folder is followed, and could name
        // a different file than the path really reaches; a full path must be written out plainly.
        require(raw.split('/', '\\').none { it == "." || it == ".." }) { "Write the full path without . or .. parts: $raw" }
        val inside = listOf(root.absoluteFile.normalize() to file.absoluteFile.normalize()) +
            listOfNotNull(runCatching { root.canonicalFile to file.canonicalFile }.getOrNull())
        for ((base, target) in inside) {
            val rel = target.relativeToOrNull(base)?.invariantSeparatorsPath ?: continue
            if (rel != ".." && !rel.startsWith("../")) return rel
        }
        // "/LESSONS.md" or "/files/a.md" is a short path with a leading slash: its top folder ("/files")
        // does not exist on the phone. A real place (under /data, /storage...) does, and is refused.
        val top = generateSequence(file.absoluteFile) { it.parentFile }.toList().dropLast(1).lastOrNull()
        require(top == null || !top.exists()) {
            "$raw is outside your Orbit folder (${root.absolutePath}); orbit_file only works inside it. " +
                "Bring a file in with action=import source=<full path>."
        }
        return raw.trimStart('/')
    }

    /** Only files inside the app's own folders and its Download/Operit folder can be taken in. */
    internal fun importable(context: Context, path: String): java.io.File {
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

    /** What an append did: the file, the lines the new text landed on, and the file's size now. */
    private fun appended(w: OrbitFiles.Written): String {
        val total = lineCount(w.after)
        val first = (total - w.appendedLines + 1).coerceAtLeast(1)
        val lines = if (w.appendedLines <= 1) "line $total" else "lines $first-$total"
        val start = if (!w.existed) "Created ${w.path} and added" else "Added to ${w.path} at"
        return "$start $lines (the file now has $total lines, ${w.after.length} characters)."
    }

    private const val MAX_LIST = 200

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
