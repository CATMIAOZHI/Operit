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
            ToolParameterSchema("action", description = "list/read/write/append/delete/trash/restore/daily/prompt_copy/import/export", required = true),
            ToolParameterSchema("path", description = "相对路径，如 memory/people/张三.md、LESSONS.md、files/报告.md；read/write/append/delete/export 必填；import 时是存到 files/ 下的目标路径（可省略）", required = false),
            ToolParameterSchema("text", description = "write：完整新内容；append/daily：要追加的一段或一行", required = false),
            ToolParameterSchema("trash_id", description = "restore：trash 返回的编号", required = false),
            ToolParameterSchema("source", description = "import：应用里已有文件的绝对路径，比如绘图工具返回的 file_path", required = false),
            ToolParameterSchema("format", description = "export：html、docx 或 pdf", required = false),
        ),
        details = """
  - 人物：memory/people/<名字>.md，一人一份；群组：memory/groups/<名字>.md。第一行写「# 名字」，下面写关系、近况、要记住的事。INDEX.md 由应用自动维护，不用写。
  - 每日流水：daily 往今天的 memory/YYYY-MM-DD.md 追加一行观察。后台任务的运行结果会自动记进去。流水不是长期记忆，长期要记的事仍写进 memory.md。
  - LESSONS.md：做事的约定和教训。踩过坑、用户纠正过做法时，用 append 记一条。
  - PROACTIVE_PREFERENCES.md：用户对主动消息的偏好（要什么、不要什么、什么时间）。后台任务会读它。
  - timezones.yaml：用户的长期时区（home）和常联系的人的时区（others）。
  - ALIGNMENT_SYNTHESIS.md：你对用户最重要的理解，后台自我整理时更新。
  - DEVICES.md：你能连上的设备，比如 Tailscale 组网里的电脑、NAS。不写密码。
  - files/：给用户做的文件，会出现在「构建」页的文件里。
  - write 会覆盖整个文件，先 read 再改。delete 后告诉用户可以在回收站恢复。
  - prompt_copy：用户要你的系统提示词时用，把 Orbit 部分（Orbit 段落和 SOUL.md，不含应用通用规则）存成 files/ 里的一份文件，告诉用户这是 Orbit 部分，在构建页的文件里找。
  - import：把应用里别处的文件收进 files/（比如绘图工具画好的图），用户就能在构建页的文件里看到。图片和音频会返回 markdown，可以直接放进回复里展示。
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
                "list" -> JSONArray(files.list().map {
                    JSONObject().put("path", it.path).put("title", it.title)
                        .put("size", if (it.size < 0) "empty template" else it.size)
                }).toString()
                "read" -> files.read(arg("path"))
                "write" -> { files.write(arg("path"), args["text"] ?: error("Missing text")); "Saved" }
                "append" -> { files.append(arg("path"), arg("text")); "Appended" }
                "daily" -> { files.appendDaily(arg("text")); "Added to today's log" }
                "delete" -> {
                    val item = files.delete(arg("path"))
                    "Moved to the trash (id ${item.id}); it can be restored for ${OrbitFiles.TRASH_DAYS} days"
                }
                "trash" -> JSONArray(files.trash().map {
                    JSONObject().put("trash_id", it.id).put("path", it.path).put("deleted_at", it.deletedAt)
                }).toString()
                "restore" -> "Restored to " + files.restore(arg("trash_id"))
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
                    val block = OrbitTools.systemPromptBlock(context, runtime?.callerChatId, english)
                    val soul = OrbitPreferences.getInstance(context).readSoul(orbit.id)
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    val path = "files/orbit-prompt-$stamp.md"
                    val text = if (english) {
                        "# Orbit's part of the system prompt\n\nThe Orbit section and SOUL.md. The app's shared rules and tool list are not included.\n\n$block\n\n## SOUL.md\n\n$soul\n"
                    } else {
                        "# Orbit 部分的系统提示词\n\n包含 Orbit 段落和 SOUL.md，不含应用通用规则和工具列表。\n\n$block\n\n## SOUL.md\n\n$soul\n"
                    }
                    val entry = files.saveBytes(path, text.byteInputStream())
                    "Saved as ${entry.path}. The user finds it under Files in the Builds tab."
                }
                else -> error("Unknown action")
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
