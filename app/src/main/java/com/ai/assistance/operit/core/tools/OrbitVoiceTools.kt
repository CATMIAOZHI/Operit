package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitApprovalActions
import com.ai.assistance.operit.features.orbit.OrbitApprovals
import com.ai.assistance.operit.features.orbit.OrbitFiles
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.features.orbit.OrbitVoice
import com.ai.assistance.operit.features.orbit.OrbitVoiceProfiles
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
        description = "用手机自带的语音合成把文字做成语音消息，或把多人台词做成一段播客式对话，存进 files/voice/，返回可以直接放进回复的 markdown（聊天里会显示成播放器）。" +
            "也能查看 App 的语音配置（朗读 tts / 语音输入 stt），并申请换用、新建、删除配置或换密钥（都要用户在卡片上批准，密钥由用户在卡片里填）。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "voices（列出可用声音）/ speak（一段语音）/ podcast（多人对话）/ profiles（语音配置）/ " +
                "request_switch / request_create / request_key / request_delete（申请改语音配置）/ request_settings（放一张语音设置卡片）", required = true),
            ToolParameterSchema("text", description = "speak：要读的文字，最多6000字（约20分钟）", required = false),
            ToolParameterSchema("voice", description = "speak：声音名（来自 voices，可省略；写了不存在的名字会报错）", required = false),
            ToolParameterSchema("script", description = "podcast：JSON 数组，如 [{\"speaker\":\"A\",\"text\":\"…\"},{\"speaker\":\"B\",\"text\":\"…\"}]", required = false),
            ToolParameterSchema("voices", description = "podcast：JSON 对象，说话人到声音名，如 {\"A\":\"zh-cn-x-…\"}；可省略，自动给每人分不同声音", required = false),
            ToolParameterSchema("title", description = "文件名（不含扩展名），可省略", required = false),
            ToolParameterSchema("kind", description = "request_*：tts（朗读）或 stt（语音输入）", required = false),
            ToolParameterSchema("profile", description = "request_switch / request_key / request_delete：profiles 里的 select", required = false),
            ToolParameterSchema("name", description = "request_create：新配置的名字，最多${OrbitVoiceProfiles.MAX_NAME}字", required = false),
            ToolParameterSchema("service", description = "request_create：profiles 的 tts_services / stt_services 里的一个", required = false),
            ToolParameterSchema("endpoint", description = "request_create：服务地址（按服务商文档填；HTTP_TTS 填请求地址模板），可省略", required = false),
            ToolParameterSchema("model", description = "request_create：服务商的模型名，可省略", required = false),
            ToolParameterSchema("voice_id", description = "request_create（tts）：服务商的音色 ID，可省略", required = false),
            ToolParameterSchema("local_model", description = "request_create，service=SHERPA_ONNX：profiles 的 local_models 里的 id", required = false),
            ToolParameterSchema("needs_key", description = "request_create：true / false；默认远程服务要填密钥，与正在用的同一服务且已有密钥时可写 false 沿用", required = false),
            ToolParameterSchema("reason", description = "request_*：一句话告诉用户为什么", required = false),
        ),
        details = """
  - 用户想听而不是看时（比如通勤路上的简报、睡前故事、练口语），或者用户要语音消息时用。
  - 声音来自手机系统的语音引擎，效果取决于手机；中文内容会自动选中文声音。
  - 一个文件最多6000字（podcast 是所有台词加起来）。更长就拆成几段分别生成。
  - 生成后把返回的 markdown 放进回复，用户就能在聊天里直接播放；文件也会出现在构建页的文件里。
  - podcast 的台词要像真人对话：短句、有来有回，每人一段不要太长。
  - App 的语音配置分两类：tts 是朗读（文字转语音），stt 是语音输入（听写）。每类可以有多个配置，同一时间各用一个。先用 profiles 看有哪些、正在用哪个；里面没有密钥，只有 has_key。
  - 换用另一个配置用 request_switch；新建用 request_create（新建后马上换用它；从正在用的配置复制设置，换了服务就从空白开始）；换密钥用 request_key；删除用 request_delete（正在用的不能删）。这些都会在聊天里放一张批准卡片，要填密钥时用户在卡片里填，你看不到。放完用一句话结束这一轮，用户批准后你会收到一条隐藏消息说明结果。
  - 服务地址、模型名、音色 ID 按服务商官方文档填，别靠猜；不确定就省略，用户可以之后在设置页里补。VITS 本地模型要从手机文件里选模型包，没法在聊天里配。
  - 语速、音调、请求头这类细项，或者用户想自己慢慢调，用 request_settings 放一张语音设置卡片，用户点它打开设置页。放完用一句话结束这一轮。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val runtime = ToolExecutionManager.currentToolRuntimeContext()
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, runtime?.callerChatId)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value }
            fun arg(name: String) = args[name]?.trim().orEmpty()
            suspend fun inChat() = check(com.ai.assistance.operit.data.preferences.OrbitPreferences.getInstance(context).current()
                .orbitForChat(runtime?.callerChatId)?.id == orbit.id) { "Approval cards only work in a chat with the user" }
            fun kind(): String = arg("kind").lowercase().also {
                require(it == OrbitVoiceProfiles.TTS || it == OrbitVoiceProfiles.STT) { "kind must be tts (read aloud) or stt (voice input)" }
            }
            suspend fun approve(action: JSONObject, current: String): String {
                inChat()
                val title = OrbitVoiceProfiles.title(context, action).take(OrbitApprovals.MAX_TITLE)
                val detail = OrbitVoiceProfiles.detail(context, action, current, arg("reason").take(300))
                val approval = OrbitApprovals.request(context, orbit.id, runtime?.callerChatId.orEmpty(), title, detail, action.toString())
                return JSONObject().put("approval_id", approval.id).put("state", approval.state)
                    .put("note", "An approval card is now in the chat" +
                        (if (action.optBoolean("needs_key")) "; the user types the API key on it" else "") +
                        ". End this turn now with one short line. You get a hidden note with the result.")
                    .put(com.ai.assistance.operit.features.orbit.OrbitChatCards.KEY,
                        com.ai.assistance.operit.features.orbit.OrbitChatCards.approval(orbit.id, approval)).toString()
            }
            suspend fun currentName(kind: String): String {
                val (all, current) = OrbitVoiceProfiles.profiles(context, kind)
                return all.firstOrNull { it.id == current }?.name.orEmpty()
            }
            val result = when (args["action"]) {
                "profiles" -> OrbitVoiceProfiles.listJson(context).toString()
                "request_switch", "request_key", "request_delete" -> {
                    val kind = kind()
                    val profile = OrbitVoiceProfiles.find(context, kind, arg("profile"))
                    val (_, current) = OrbitVoiceProfiles.profiles(context, kind)
                    val op = when (args["action"]) {
                        "request_switch" -> {
                            require(profile.id != current) { "\"${profile.name}\" is already the $kind profile in use" }
                            OrbitVoiceProfiles.OP_SWITCH
                        }
                        "request_delete" -> {
                            require(profile.id != current) { "\"${profile.name}\" is in use; switch to another $kind profile first" }
                            OrbitVoiceProfiles.OP_DELETE
                        }
                        else -> {
                            require(!profile.local) { "\"${profile.name}\" runs on the phone and uses no key" }
                            OrbitVoiceProfiles.OP_KEY
                        }
                    }
                    approve(JSONObject().put("type", OrbitApprovalActions.VOICE_PROFILE).put("op", op).put("kind", kind)
                        .put("profile_id", profile.id).put("profile_name", profile.name)
                        .put("service", profile.service).put("needs_key", op == OrbitVoiceProfiles.OP_KEY), currentName(kind))
                }
                "request_create" -> {
                    val kind = kind()
                    val name = arg("name").replace(Regex("\\s+"), " ")
                    require(name.isNotEmpty() && name.length <= OrbitVoiceProfiles.MAX_NAME) {
                        "name must be 1-${OrbitVoiceProfiles.MAX_NAME} characters"
                    }
                    val service = arg("service").uppercase()
                    val services = if (kind == OrbitVoiceProfiles.TTS) OrbitVoiceProfiles.ttsServices else OrbitVoiceProfiles.sttServices
                    require(service in services) {
                        if (kind == OrbitVoiceProfiles.TTS && service == "VITS_TTS") "VITS needs a model package from the phone's files; use request_settings"
                        else "service must be one of ${services.joinToString()}"
                    }
                    val endpoint = OrbitVoiceProfiles.field(arg("endpoint"), "endpoint")
                    require(endpoint.isEmpty() || endpoint.matches(Regex("(https?|wss?)://\\S{3,}"))) { "endpoint must be a full URL" }
                    val model = OrbitVoiceProfiles.field(arg("model"), "model")
                    val voice = OrbitVoiceProfiles.field(arg("voice_id"), "voice_id")
                    require(voice.isEmpty() || kind == OrbitVoiceProfiles.TTS) { "voice_id only applies to tts" }
                    val local = OrbitVoiceProfiles.localModel(kind, arg("local_model"))
                    require(local.isEmpty() || service == "SHERPA_ONNX") { "local_model only applies with service=SHERPA_ONNX" }
                    val isLocal = OrbitVoiceProfiles.isLocal(kind, service)
                    val needsKey = !isLocal && when (arg("needs_key").lowercase()) {
                        "true" -> true
                        "false" -> false
                        else -> OrbitVoiceProfiles.needsKeyByDefault(kind, service)
                    }
                    approve(JSONObject().put("type", OrbitApprovalActions.VOICE_PROFILE).put("op", OrbitVoiceProfiles.OP_CREATE)
                        .put("kind", kind).put("name", name).put("service", service)
                        .apply { if (!isLocal) put("endpoint", endpoint).put("model", model) }
                        .apply { if (voice.isNotEmpty()) put("voice", voice) }
                        .apply { if (local.isNotEmpty()) put("local_model", local) }
                        .put("needs_key", needsKey), currentName(kind))
                }
                "request_settings" -> {
                    check(com.ai.assistance.operit.data.preferences.OrbitPreferences.getInstance(context).current()
                        .orbitForChat(runtime?.callerChatId)?.id == orbit.id) {
                        "The settings card only works in a chat with the user"
                    }
                    JSONObject().put("card_sent", true)
                        .put("note", "A voice settings card is now in the chat; the user opens it and changes the settings there. " +
                            "You are not told what they change. End this turn now with one short line.")
                        .put(com.ai.assistance.operit.features.orbit.OrbitChatCards.KEY,
                            com.ai.assistance.operit.features.orbit.OrbitChatCards.speechSettings(orbit.id)).toString()
                }
                "voices" -> JSONArray(OrbitVoice.voices(context).map {
                    JSONObject().put("name", it.name).put("locale", it.locale).put("needs_network", it.needsNetwork)
                }).toString()
                "speak", "podcast" -> {
                    val lines = if (args["action"] == "speak") {
                        listOf(OrbitVoice.Line("voice", args["text"]?.takeIf { it.isNotBlank() } ?: error("speak needs text")))
                    } else {
                        val raw = args["script"]?.takeIf { it.isNotBlank() } ?: error("podcast needs script")
                        val script = runCatching { JSONArray(raw) }.getOrElse {
                            error("script must be a JSON array like [{\"speaker\":\"A\",\"text\":\"…\"}]")
                        }
                        require(script.length() > 0) { "script has no lines" }
                        (0 until script.length()).map { i ->
                            val line = script.optJSONObject(i) ?: error("Line ${i + 1} of the script is not an object with speaker and text")
                            val text = line.optString("text")
                            require(text.isNotBlank()) { "Line ${i + 1} of the script has no text" }
                            OrbitVoice.Line(line.optString("speaker").ifBlank { "A" }.take(40), text)
                        }
                    }
                    val voices = if (args["action"] == "speak") {
                        args["voice"]?.takeIf { it.isNotBlank() }?.let { mapOf("voice" to it) }.orEmpty()
                    } else {
                        args["voices"]?.takeIf { it.isNotBlank() }?.let { raw ->
                            runCatching { JSONObject(raw) }.getOrElse { error("voices must be a JSON object like {\"A\":\"voice name\"}") }
                                .let { json -> json.keys().asSequence().associateWith { json.getString(it) } }
                        }.orEmpty()
                    }
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    val name = args["title"]?.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "")?.trim('.', ' ')?.take(60)
                        ?.takeIf { it.isNotBlank() } ?: (if (args["action"] == "speak") "voice-$stamp" else "podcast-$stamp")
                    val temp = File(context.cacheDir, "orbit-voice-${UUID.randomUUID()}.wav")
                    try {
                        val used = OrbitVoice.synthesize(context, lines, voices, temp)
                        val files = OrbitFiles.of(context, orbit.id)
                        val entry = temp.inputStream().use { files.saveBytes("files/voice/$name.wav", it) }
                        JSONObject().put("path", entry.path).put("chars", lines.sumOf { it.text.length })
                            .put("lines", lines.size).put("bytes", entry.size)
                            .put("voices", JSONObject(used.mapValues { it.value.ifEmpty { "default" } }))
                            .put("markdown", OrbitFileTools.mediaMarkdown(files.fileFor(entry.path))).toString()
                    } finally {
                        temp.delete()
                    }
                }
                else -> error("Unknown action \"${args["action"].orEmpty()}\". Use voices, speak, podcast, profiles, request_switch, " +
                    "request_create, request_key, request_delete or request_settings.")
            }
            ToolResult(tool.name, true, StringResultData(result))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(tool.name, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
