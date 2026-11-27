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
    /** How long a style may be: a director's note of a role, a scene and guidance fits. */
    private const val MAX_STYLE = 800

    val prompt = ToolPrompt(
        name = NAME,
        description = "把文字做成语音消息，或把多人台词做成一段播客式对话，都用云端声音（用户在设置里选了手机自带声音时才用手机语音），存进 files/voice/，返回可以直接放进回复的 markdown（聊天里显示成语音条）。" +
            "也能查看 App 的语音配置（朗读 tts / 语音输入 stt），并申请换用、新建、删除配置或换密钥（都要用户在卡片上批准，密钥由用户在卡片里填）。" +
            "还能按文字描述用小米 MiMo 设计一个新声音给用户试听，用户满意后申请把它定为你自己的声音。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "voices（列出可用声音）/ speak（一段语音）/ podcast（多人对话）/ profiles（语音配置）/ " +
                "request_switch / request_create / request_key / request_delete（申请改语音配置）/ request_settings（放一张语音设置卡片）/ " +
                "design（按描述设计声音、做一段试听）/ request_own_voice（申请定你自己的声音）", required = true),
            ToolParameterSchema("text", description = "speak：要读的文字，最多6000字（约20分钟），可带 (风格) 和（叹气）这类表演标签；design：试听说的话，最多${OrbitVoice.MAX_SAMPLE_TEXT}字", required = false),
            ToolParameterSchema("style", description = "speak：用一段话说怎么读（语气、情绪、语速、导演模式的角色/场景/指导），最多${MAX_STYLE}字，可省略；写法见 docs/voice-style.md", required = false),
            ToolParameterSchema("description", description = "design：声音描述，如“二十多岁的女生，声音温柔，语速偏慢”，最多${OrbitVoice.MAX_DESCRIPTION}字", required = false),
            ToolParameterSchema("sample", description = "request_own_voice：照着复刻的录音，files/ 里的 mp3 或 wav 路径（design 返回的 path，或用户发来的录音）", required = false),
            ToolParameterSchema("voice", description = "speak：手机声音名（来自 voices），只在用户选了手机自带声音时能写，可省略", required = false),
            ToolParameterSchema("script", description = "podcast：JSON 数组，如 [{\"speaker\":\"A\",\"text\":\"…\"},{\"speaker\":\"B\",\"text\":\"…\",\"style\":\"压低声音，带点坏笑\"}]，style 可省略", required = false),
            ToolParameterSchema("voices", description = "podcast：JSON 对象，说话人到声音名（来自 voices），如 {\"A\":\"orbit\",\"B\":\"MiMo·苏打\"}；可省略，自动给每人分不同声音", required = false),
            ToolParameterSchema("title", description = "speak / podcast / design：文件名（不含扩展名），可省略", required = false),
            ToolParameterSchema("kind", description = "request_*：tts（朗读）或 stt（语音输入）", required = false),
            ToolParameterSchema("profile", description = "request_switch / request_key / request_delete：profiles 里的 select；" +
                "request_own_voice：用已有的朗读配置当你的声音（tts 的 select）", required = false),
            ToolParameterSchema("follow_app", description = "request_own_voice：true 表示改回和 App 一样的声音", required = false),
            ToolParameterSchema("name", description = "request_create / request_own_voice（有 sample 时）：新配置的名字，最多${OrbitVoiceProfiles.MAX_NAME}字", required = false),
            ToolParameterSchema("service", description = "request_create：profiles 的 tts_services / stt_services 里的一个", required = false),
            ToolParameterSchema("endpoint", description = "request_create：服务地址（按服务商文档填；HTTP_TTS 填请求地址模板），可省略", required = false),
            ToolParameterSchema("model", description = "request_create：服务商的模型名，可省略", required = false),
            ToolParameterSchema("voice_id", description = "request_create（tts）：服务商的音色 ID，可省略", required = false),
            ToolParameterSchema("local_model", description = "request_create，service=SHERPA_ONNX：profiles 的 local_models 里的 id", required = false),
            ToolParameterSchema("switch", description = "request_create（tts）：false 时只保存、不换用，正在用的声音不变（第一次给 MiMo 填密钥做试听时用）；默认 true", required = false),
            ToolParameterSchema("needs_key", description = "request_create：true / false；默认远程服务要填密钥，与正在用的同一服务且已有密钥时可写 false 沿用", required = false),
            ToolParameterSchema("reason", description = "request_*：一句话告诉用户为什么", required = false),
        ),
        details = """
  - 用户想听而不是看时（比如通勤路上的简报、睡前故事、练口语），或者用户要语音消息时用。
  - speak 用你的朗读声音（profiles 的 orbit_voice，null 表示和 App 的朗读一样）。podcast 里第一个说话人默认用你的朗读声音（名字 orbit），其他人自动分到能和它拼成一个文件的云端声音：同一服务的其他朗读配置，你的声音是 MiMo 时还有 MiMo 预置声音。想指定就先用 voices 看有哪些。
  - 你还没有云端声音时（朗读声音是手机自带的），speak 和 podcast 会报错。这时推荐用户配一个云端声音（最省事的是小米 MiMo），具体怎么配照报错里写的步骤来：你单独选了声音时，在 App 里新建配置不会改到你。用户就想用手机自带声音的话，放 request_settings 卡片，让用户自己打开「发语音用手机自带的声音」。别自己改用手机声音。
  - 云端声音出错时会直接报错，不会改用手机声音；用一句话告诉用户原因。
  - 用户选了手机自带声音时，speak 和 podcast 都用手机的语音引擎，效果取决于手机；voice / voices 写手机声音名（来自 voices）。
  - 一个文件最多6000字（podcast 是所有台词加起来）。更长就拆成几段分别生成。
  - 生成后把返回的 markdown 单独放一行，聊天里显示成语音条，用户点一下就播放；文件也会出现在构建页的文件里。像发语音一样：前后最多一句短话，不要把语音的内容再用文字写一遍（用户长按语音条、点「转文字」就能看到原文），用户要文字版时再给。title 写几个字的话题，用作文件名。
  - 让语音像真人说话：你的声音是小米 MiMo 时，可以用 style 写怎么读，在 text 里加 (风格) 和（叹气）（轻笑）这类表演标签。第一次用或想做得更好时，先读 docs/voice-style.md。MiMo 会把括号里的短内容当表演提示、不读出来，所以要读的话别放括号里。表演标签不会出现在「转文字」里；别的声音会去掉它们和 style，结果里 styled=false。
  - podcast 的台词要像真人对话：短句、有来有回，每人一段不要太长。
  - App 的语音配置分两类：tts 是朗读（文字转语音），stt 是语音输入（听写）。每类可以有多个配置，同一时间各用一个。先用 profiles 看有哪些、正在用哪个；里面没有密钥，只有 has_key。
  - 换用另一个配置用 request_switch；新建用 request_create（新建后马上换用它；从正在用的配置复制设置，换了服务就从空白开始）；换密钥用 request_key；删除用 request_delete（正在用的不能删）。这些都会在聊天里放一张批准卡片，要填密钥时用户在卡片里填，你看不到。放完用一句话结束这一轮，用户批准后你会收到一条隐藏消息说明结果。
  - 服务地址、模型名、音色 ID 按服务商官方文档填，别靠猜；不确定就省略，用户可以之后在设置页里补。VITS 本地模型要从手机文件里选模型包，没法在聊天里配。
  - 语速、音调、请求头这类细项，或者用户想自己慢慢调，用 request_settings 放一张语音设置卡片，用户点它打开设置页。放完用一句话结束这一轮。
  - 你可以有自己的声音：用于你聊天里的朗读、speak 的语音消息和 podcast 里的 orbit。App 别处不变。
  - 第一次用小米 MiMo 给你配声音时，一定让用户先挑：先问用户想要什么样的声音（没想法就按你的人设提一个），再用 design 做 2～3 段描述不同的试听，title 分别写清是哪一版，markdown 各放一行，结束这一轮等用户听完挑。不要直接拿 MiMo 预置声音当你的声音。步骤见 docs/voice.md。
  - 用户要你换声音或设计声音时（包括用户安排的任务里）：用 design 按描述做试听（用 App 里已存的小米 MiMo 密钥，每段都花用户一点钱），把 markdown 放进回复。在聊天里做完就结束这一轮等用户听完；用户说要改再改描述重做。用户没要求时别自己做。
  - 用户挑定后用 request_own_voice：sample 写那段试听的 path，name 起个名字；批准后照这段录音复刻，之后用它朗读。用户发来的真人录音只在用户明确要求时当 sample。
  - request_own_voice 也可以写 profile 换成已有的朗读配置，或写 follow_app=true 改回和 App 一样。放完卡片用一句话结束这一轮。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext { run(context, tool) }

    /** Runs in the tool call's coroutine, so stopping the chat also stops a voice being made. */
    suspend fun run(context: Context, tool: AITool): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
                "profiles" -> OrbitVoiceProfiles.listJson(context, orbit.id).toString()
                "design" -> {
                    // A sample only becomes the Orbit's voice after the user approves request_own_voice, so tasks may design too.
                    val description = arg("description")
                    require(description.isNotEmpty() && description.length <= OrbitVoice.MAX_DESCRIPTION) {
                        "design needs description: what the voice sounds like, at most ${OrbitVoice.MAX_DESCRIPTION} characters"
                    }
                    val text = arg("text")
                    require(text.isNotEmpty() && text.length <= OrbitVoice.MAX_SAMPLE_TEXT) {
                        "design needs text: what the voice says in the sample, at most ${OrbitVoice.MAX_SAMPLE_TEXT} characters"
                    }
                    val key = OrbitVoice.mimoKey(context)?.second ?: error(
                        "No Xiaomi MiMo API key is saved in the app. Tell the user that designing a voice needs one: use request_create " +
                            "with kind=tts, service=MIMO_TTS and switch=false so they type the key on the card without changing the voice " +
                            "in use, then design again once it is approved"
                    )
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val name = fileTitle(arg("title")) ?: "voice-design-$stamp"
                    val files = OrbitFiles.of(context, orbit.id)
                    val temp = File(context.cacheDir, "orbit-voice-${UUID.randomUUID()}.wav")
                    try {
                        OrbitVoice.designVoice(context, key, description, text, temp)
                        val entry = temp.inputStream().use {
                            files.saveBytes("files/voice/$name.wav", it, voiceText = com.ai.assistance.operit.api.voice.MimoVoiceProvider.withoutCues(text))
                        }
                        JSONObject().put("path", entry.path).put("bytes", entry.size)
                            .put("markdown", OrbitFileTools.mediaMarkdown(files.fileFor(entry.path)))
                            .put("note", "If this turn has ${com.ai.assistance.operit.features.orbit.OrbitTaskRunner.FINISH} (a separate task run), " +
                                "finish as usual and put this markdown unchanged in its userMessage; Orbit then decides how to pass it on. " +
                                "Otherwise (in the chat, also when a schedule woke you there) put the markdown in your reply so the user " +
                                "can listen; if the user asked just now and you are making several samples to choose from (2 or 3), make the rest " +
                                "first, put a line with each one's number and name (e.g. \"1. Gentle\") above its markdown, then end this " +
                                "turn and wait for them. " +
                                "Only when they say they like it, use request_own_voice with sample=${entry.path}; " +
                                "if they want changes, design once more with a new description.")
                            .toString()
                    } finally {
                        temp.delete()
                    }
                }
                "request_own_voice" -> {
                    val sample = arg("sample")
                    val select = arg("profile")
                    val followApp = arg("follow_app").equals("true", ignoreCase = true)
                    require(listOf(sample.isNotEmpty(), select.isNotEmpty(), followApp).count { it } == 1) {
                        "Give exactly one of sample (a recording to copy), profile (a tts profile's select) or follow_app=true"
                    }
                    val ownId = OrbitVoice.ownVoice(context, orbit.id)
                    val (all, _) = OrbitVoiceProfiles.profiles(context, OrbitVoiceProfiles.TTS)
                    val own = all.firstOrNull { it.id == ownId }
                    val current = own?.name ?: context.getString(com.ai.assistance.operit.R.string.orbit_voice_own_app)
                    val action = JSONObject().put("type", OrbitApprovalActions.VOICE_PROFILE).put("op", OrbitVoiceProfiles.OP_OWN)
                        .put("kind", OrbitVoiceProfiles.TTS).put("orbit_id", orbit.id).put("orbit_name", orbit.name)
                    when {
                        sample.isNotEmpty() -> {
                            val name = arg("name").replace(Regex("\\s+"), " ")
                            require(name.isNotEmpty() && name.length <= OrbitVoiceProfiles.MAX_NAME) {
                                "name must be 1-${OrbitVoiceProfiles.MAX_NAME} characters"
                            }
                            val file = OrbitFiles.of(context, orbit.id).chatFile(sample)
                                ?: error("No recording at $sample; give a path in files/ such as the one design returned")
                            require(file.length() <= com.ai.assistance.operit.api.voice.MimoVoiceProvider.MAX_SAMPLE_BYTES) {
                                "The recording is larger than 7 MB; use a shorter one"
                            }
                            require(com.ai.assistance.operit.api.voice.MimoVoiceProvider.sampleFormat(head(file)) != null) {
                                "The recording must be an mp3 or wav file"
                            }
                            val key = OrbitVoice.mimoKey(context)
                            action.put("name", name).put("sample", sample).put("sample_sha256", OrbitVoiceProfiles.sha256(file))
                                .put("needs_key", key == null)
                            // The card shows where the key is saved, and approving uses exactly that one.
                            key?.let { (owner, found) ->
                                action.put("key_from", found.source).put("key_owner", owner).put("key_endpoint", found.endpoint)
                            }
                        }
                        followApp -> require(own != null) { "You already speak with the app's voice" }
                        else -> {
                            val profile = OrbitVoiceProfiles.find(context, OrbitVoiceProfiles.TTS, select)
                            require(profile.id != own?.id) { "\"${profile.name}\" is already your voice" }
                            action.put("profile_id", profile.id).put("profile_name", profile.name)
                        }
                    }
                    approve(action, current)
                }
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
                        .apply { if (kind == OrbitVoiceProfiles.TTS && arg("switch").lowercase() == "false") put("switch", false) }
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
                "voices" -> if (OrbitVoice.usesReadAloud(context, orbit.id)) {
                    val cloud = OrbitVoice.cloudVoices(context, orbit.id)
                    check(cloud.isNotEmpty()) { OrbitVoice.notSetUp(context, orbit.id) }
                    JSONObject().put("uses", "cloud").put("voices", JSONArray(cloud.map { v ->
                        JSONObject().put("name", v.name)
                            .apply { if (v.locale.isNotEmpty()) put("locale", v.locale) }
                            .apply { if (v.gender.isNotEmpty()) put("gender", v.gender.lowercase()) }
                    })).toString()
                } else {
                    JSONObject().put("uses", "phone").put("voices", JSONArray(OrbitVoice.voices(context).map {
                        JSONObject().put("name", it.name).put("locale", it.locale).put("needs_network", it.needsNetwork)
                    })).toString()
                }
                "speak", "podcast" -> {
                    val style = arg("style")
                    require(style.length <= MAX_STYLE) { "style has ${style.length} characters; at most $MAX_STYLE" }
                    require(style.isEmpty() || args["action"] == "speak") { "In a podcast, give each line its own style in the script" }
                    val lines = if (args["action"] == "speak") {
                        listOf(OrbitVoice.Line("voice", args["text"]?.takeIf { it.isNotBlank() } ?: error("speak needs text"), style))
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
                            val lineStyle = line.optString("style").trim()
                            require(lineStyle.length <= MAX_STYLE) { "The style of line ${i + 1} has ${lineStyle.length} characters; at most $MAX_STYLE" }
                            OrbitVoice.Line(line.optString("speaker").ifBlank { "A" }.take(40), text, lineStyle)
                        }
                    }
                    // One file holds at most this much of what was written, for any voice.
                    val written = lines.sumOf { it.text.length }
                    require(written <= OrbitVoice.MAX_CHARS) {
                        "The text has $written characters; one file allows at most ${OrbitVoice.MAX_CHARS}. Split it into several parts"
                    }
                    // The words shown under "to text": what is said, without the performance cues.
                    val words = com.ai.assistance.operit.features.orbit.OrbitVoiceText.of(
                        lines.map { it.speaker to com.ai.assistance.operit.api.voice.MimoVoiceProvider.withoutCues(it.text) })
                    val styledAsked = lines.any { it.style.isNotEmpty() || com.ai.assistance.operit.api.voice.MimoVoiceProvider.hasCues(it.text) }
                    val voices = if (args["action"] == "speak") {
                        args["voice"]?.takeIf { it.isNotBlank() }?.let { mapOf("voice" to it) }.orEmpty()
                    } else {
                        args["voices"]?.takeIf { it.isNotBlank() }?.let { raw ->
                            runCatching { JSONObject(raw) }.getOrElse { error("voices must be a JSON object like {\"A\":\"voice name\"}") }
                                .let { json -> json.keys().asSequence().associateWith { json.getString(it) } }
                        }.orEmpty()
                    }
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    val name = fileTitle(args["title"].orEmpty()) ?: (if (args["action"] == "speak") "voice-$stamp" else "podcast-$stamp")
                    val files = OrbitFiles.of(context, orbit.id)
                    // Audio is made with cloud voices; the phone's built-in voice only when the user chose it.
                    if (OrbitVoice.usesReadAloud(context, orbit.id)) {
                        require(args["action"] == "podcast" || voices.isEmpty()) {
                            "voice names a phone voice, which is only used when the user chose the phone's own voice; leave it out"
                        }
                        val made = File(context.cacheDir, "orbit-voice-${UUID.randomUUID()}.audio")
                        try {
                            val (extension, details) = if (args["action"] == "speak") {
                                when (val r = OrbitVoice.synthesizeWithReadAloud(context, lines[0].text, made, orbit.id, style)) {
                                    is OrbitVoice.ServiceResult.Made -> r.extension to JSONObject().put("service", r.profile)
                                        .apply { if (styledAsked) put("styled", r.styled) }
                                    OrbitVoice.ServiceResult.NotSetUp -> error(OrbitVoice.notSetUp(context, orbit.id))
                                    is OrbitVoice.ServiceResult.Failed -> error("No audio was made: ${r.reason}. " +
                                        "Tell the user in one line; do not make it with the phone's voice instead")
                                }
                            } else {
                                val podcast = OrbitVoice.synthesizePodcast(context, lines, voices, made, orbit.id)
                                podcast.extension to JSONObject().put("lines", lines.size).put("voices", JSONObject(podcast.voices))
                                    .apply { if (podcast.shared) put("note", "There were not enough different voices, so some speakers share one. Tell the user in one line if it matters.") }
                                    .apply { if (styledAsked) put("styled", podcast.styled) }
                            }
                            val entry = made.inputStream().use { files.saveBytes("files/voice/$name.$extension", it, voiceText = words) }
                            return@withContext ToolResult(tool.name, true, StringResultData(
                                details.put("path", entry.path).put("chars", lines.sumOf { it.text.length })
                                    .put("bytes", entry.size).put("engine", "cloud")
                                    .put("markdown", OrbitFileTools.mediaMarkdown(files.fileFor(entry.path))).toString()))
                        } finally {
                            made.delete()
                        }
                    }
                    // The user chose the phone's voice: a plain message keeps their phone voice settings.
                    val phoneStyle = if (args["action"] == "speak" && voices.isEmpty()) OrbitVoice.phoneStyle(context, orbit.id) else null
                    val temp = File(context.cacheDir, "orbit-voice-${UUID.randomUUID()}.wav")
                    try {
                        // The phone's voice would read the cues out.
                        val plain = lines.map { it.copy(text = com.ai.assistance.operit.api.voice.MimoVoiceProvider.withoutCues(it.text), style = "") }
                        require(plain.all { it.text.isNotBlank() }) { "Without its performance cues a line has no words left" }
                        val used = OrbitVoice.synthesize(context, plain, voices, temp, phoneStyle)
                        val entry = temp.inputStream().use { files.saveBytes("files/voice/$name.wav", it, voiceText = words) }
                        JSONObject().put("path", entry.path).put("chars", lines.sumOf { it.text.length })
                            .put("lines", lines.size).put("bytes", entry.size)
                            .put("engine", "phone")
                            .apply { if (styledAsked) put("styled", false) }
                            .put("voices", JSONObject(used.mapValues { it.value.ifEmpty { "default" } }))
                            .put("markdown", OrbitFileTools.mediaMarkdown(files.fileFor(entry.path))).toString()
                    } finally {
                        temp.delete()
                    }
                }                else -> error("Unknown action \"${args["action"].orEmpty()}\". Use voices, speak, podcast, profiles, request_switch, " +
                    "request_create, request_key, request_delete, request_settings, design or request_own_voice.")
            }
            ToolResult(tool.name, true, StringResultData(result))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(tool.name, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    /** A file name from the given title, without characters a file name cannot have; null if nothing is left. */
    private fun fileTitle(title: String): String? =
        title.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "").trim('.', ' ').take(60).takeIf { it.isNotBlank() }

    /** A file's first bytes, enough to tell mp3 from wav. */
    private fun head(file: File): ByteArray {
        val bytes = ByteArray(12)
        var read = 0
        file.inputStream().use { input ->
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n < 0) break
                read += n
            }
        }
        return bytes.copyOf(read)
    }
}
