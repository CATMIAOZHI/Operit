package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitFiles
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.features.orbit.OrbitVoice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Voice messages and multi-voice audio (podcasts, dialogues) saved in Orbit's files/voice/. */
internal object OrbitVoiceTools {
    const val NAME = "orbit_voice"

    val prompt = ToolPrompt(
        name = NAME,
        description = "用手机自带的语音合成把文字做成语音消息，或把多人台词做成一段播客式对话，存进 files/voice/，返回可以直接放进回复的 markdown（聊天里会显示成播放器）。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "voices（列出可用声音）/ speak（一段语音）/ podcast（多人对话）", required = true),
            ToolParameterSchema("text", description = "speak：要读的文字", required = false),
            ToolParameterSchema("voice", description = "speak：声音名（来自 voices，可省略）", required = false),
            ToolParameterSchema("script", description = "podcast：JSON 数组，如 [{\"speaker\":\"A\",\"text\":\"…\"},{\"speaker\":\"B\",\"text\":\"…\"}]", required = false),
            ToolParameterSchema("voices", description = "podcast：JSON 对象，说话人到声音名，如 {\"A\":\"zh-cn-x-…\"}；可省略，自动给每人分不同声音", required = false),
            ToolParameterSchema("title", description = "文件名（不含扩展名），可省略", required = false),
        ),
        details = """
  - 用户想听而不是看时（比如通勤路上的简报、睡前故事、练口语），或者用户要语音消息时用。
  - 声音来自手机系统的语音引擎，效果取决于手机；中文内容会自动选中文声音。
  - 生成后把返回的 markdown 放进回复，用户就能在聊天里直接播放；文件也会出现在构建页的文件里。
  - podcast 的台词要像真人对话：短句、有来有回，每人一段不要太长。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val runtime = ToolExecutionManager.currentToolRuntimeContext()
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, runtime?.callerChatId)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value }
            val result = when (args["action"]) {
                "voices" -> JSONArray(OrbitVoice.voices(context).map {
                    JSONObject().put("name", it.name).put("locale", it.locale).put("needs_network", it.needsNetwork)
                }).toString()
                "speak", "podcast" -> {
                    val lines = if (args["action"] == "speak") {
                        listOf(OrbitVoice.Line("voice", args["text"]?.takeIf { it.isNotBlank() } ?: error("Missing text")))
                    } else {
                        val script = JSONArray(args["script"]?.takeIf { it.isNotBlank() } ?: error("Missing script"))
                        (0 until script.length()).map { i ->
                            val line = script.getJSONObject(i)
                            val text = line.optString("text")
                            require(text.isNotBlank()) { "Line ${i + 1} of the script has no text" }
                            OrbitVoice.Line(line.optString("speaker").ifBlank { "A" }.take(40), text)
                        }
                    }
                    val voices = if (args["action"] == "speak") {
                        args["voice"]?.takeIf { it.isNotBlank() }?.let { mapOf("voice" to it) }.orEmpty()
                    } else {
                        args["voices"]?.takeIf { it.isNotBlank() }?.let { raw ->
                            JSONObject(raw).let { json -> json.keys().asSequence().associateWith { json.getString(it) } }
                        }.orEmpty()
                    }
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    val name = args["title"]?.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "")?.trim('.', ' ')?.take(60)
                        ?.takeIf { it.isNotBlank() } ?: (if (args["action"] == "speak") "voice-$stamp" else "podcast-$stamp")
                    val temp = File(context.cacheDir, "orbit-voice-${UUID.randomUUID()}.wav")
                    try {
                        OrbitVoice.synthesize(context, lines, voices, temp)
                        val files = OrbitFiles.of(context, orbit.id)
                        val entry = temp.inputStream().use { files.saveBytes("files/voice/$name.wav", it) }
                        JSONObject().put("path", entry.path)
                            .put("markdown", OrbitFileTools.mediaMarkdown(files.fileFor(entry.path))).toString()
                    } finally {
                        temp.delete()
                    }
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
}
