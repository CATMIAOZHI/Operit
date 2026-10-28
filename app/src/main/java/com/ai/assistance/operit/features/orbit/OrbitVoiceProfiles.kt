package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.speech.LocalVoiceModels
import com.ai.assistance.operit.api.speech.SpeechServiceFactory.SpeechServiceType
import com.ai.assistance.operit.api.voice.DoubaoVoiceProvider
import com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType
import com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences
import com.ai.assistance.operit.data.preferences.SpeechServicesPreferences
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's voice profiles as Orbit handles them from the chat: read aloud (tts) and voice input
 * (stt). Orbit lists them; switching, adding, deleting and changing a key wait for the user's
 * approval on a card, where the user types any key, so a key never reaches the chat.
 */
internal object OrbitVoiceProfiles {
    const val TTS = "tts"
    const val STT = "stt"
    const val OP_CREATE = "create"
    const val OP_SWITCH = "switch"
    const val OP_KEY = "key"
    const val OP_DELETE = "delete"
    const val MAX_NAME = 40
    private const val MAX_FIELD = 300

    private val localTts = setOf(VoiceServiceType.SIMPLE_TTS, VoiceServiceType.SHERPA_ONNX, VoiceServiceType.VITS_TTS)
    private val localStt = setOf(SpeechServiceType.SHERPA_NCNN, SpeechServiceType.SHERPA_ONNX)

    /** VITS needs a model package picked from the phone's files, so it is set up on the settings page. */
    val ttsServices: List<String> = VoiceServiceType.values().filter { it != VoiceServiceType.VITS_TTS }.map { it.name }
    val sttServices: List<String> = SpeechServiceType.values().map { it.name }

    data class Profile(
        val id: String, val name: String, val service: String, val local: Boolean, val hasKey: Boolean,
        val endpoint: String, val model: String, val voice: String, val localModel: String,
    )

    fun isLocal(kind: String, service: String): Boolean =
        if (kind == TTS) localTts.any { it.name == service } else localStt.any { it.name == service }

    /** A remote service needs a key, except a hand-made HTTP voice service, which may not. */
    fun needsKeyByDefault(kind: String, service: String): Boolean =
        !isLocal(kind, service) && !(kind == TTS && service == VoiceServiceType.HTTP_TTS.name)

    fun serviceLabel(context: Context, kind: String, service: String): String = context.getString(
        if (kind == TTS) when (service) {
            VoiceServiceType.SIMPLE_TTS.name -> R.string.speech_services_tts_type_simple
            VoiceServiceType.HTTP_TTS.name -> R.string.speech_services_tts_type_http
            VoiceServiceType.OPENAI_WS_TTS.name -> R.string.speech_services_tts_type_openai_ws
            VoiceServiceType.SILICONFLOW_TTS.name -> R.string.speech_services_tts_type_siliconflow
            VoiceServiceType.MINIMAX_TTS.name -> R.string.speech_services_tts_type_minimax
            VoiceServiceType.MIMO_TTS.name -> R.string.speech_services_tts_type_mimo
            VoiceServiceType.DOUBAO_TTS.name -> R.string.speech_services_tts_type_doubao
            VoiceServiceType.OPENAI_TTS.name -> R.string.speech_services_tts_type_openai
            VoiceServiceType.VITS_TTS.name -> R.string.speech_services_tts_type_vits
            else -> R.string.voice_local_engine
        } else when (service) {
            SpeechServiceType.SHERPA_NCNN.name -> R.string.speech_services_stt_type_sherpa
            SpeechServiceType.OPENAI_STT.name -> R.string.speech_services_stt_type_openai
            SpeechServiceType.DEEPGRAM_STT.name -> R.string.speech_services_stt_type_deepgram
            else -> R.string.voice_local_engine
        }
    )

    private fun prefs(context: Context) = SpeechServiceProfilesPreferences(context.applicationContext)

    /** The profiles of [kind] in the app's order, and the id of the one in use. */
    suspend fun profiles(context: Context, kind: String): Pair<List<Profile>, String> {
        val p = prefs(context)
        return if (kind == TTS) {
            p.ttsProfilesFlow.first().map {
                Profile(it.id, it.name, it.serviceType.name, it.serviceType in localTts, it.httpConfig.apiKey.isNotBlank(),
                    it.httpConfig.urlTemplate, it.httpConfig.modelName, it.httpConfig.voiceId,
                    if (it.serviceType == VoiceServiceType.SHERPA_ONNX) it.localModelId else "")
            } to p.currentTtsProfileIdFlow.first()
        } else {
            p.sttProfilesFlow.first().map {
                Profile(it.id, it.name, it.serviceType.name, it.serviceType in localStt, it.httpConfig.apiKey.isNotBlank(),
                    it.httpConfig.endpointUrl, it.httpConfig.modelName, "",
                    if (it.serviceType == SpeechServiceType.SHERPA_ONNX) it.localModelId else "")
            } to p.currentSttProfileIdFlow.first()
        }
    }

    /** How Orbit names each profile: its name, or name#1, name#2 … when several share one. */
    fun selectors(all: List<Profile>): Map<String, String> {
        val counts = all.groupingBy { it.name }.eachCount()
        val picks = LinkedHashMap<String, String>()
        all.filter { counts[it.name] == 1 }.forEach { picks[it.id] = it.name }
        val used = picks.values.toMutableSet()
        all.filter { counts[it.name] != 1 }.groupBy { it.name }.forEach { (name, same) ->
            var n = 1
            same.forEach { profile ->
                while ("$name#$n" in used) n++
                picks[profile.id] = "$name#$n"
                used += "$name#$n"
            }
        }
        return picks
    }

    suspend fun find(context: Context, kind: String, select: String): Profile {
        require(select.isNotEmpty()) { "Give profile: a profile's select from action=profiles" }
        val (all, _) = profiles(context, kind)
        val picks = selectors(all)
        all.firstOrNull { picks[it.id] == select }?.let { return it }
        require(all.none { it.name == select }) {
            "Several $kind profiles are named \"$select\"; pass profile exactly as profiles' select shows it, e.g. \"$select#1\""
        }
        error("No $kind profile \"$select\"; use a select exactly as action=profiles shows it")
    }

    /** Every profile, without keys, for action=profiles. */
    suspend fun listJson(context: Context): JSONObject {
        fun part(kind: String, all: List<Profile>, current: String): JSONObject {
            val picks = selectors(all)
            return JSONObject().put("in_use", picks[current].orEmpty()).put("profiles", JSONArray(all.map {
                JSONObject().put("select", picks[it.id]).put("service", it.service).put("local", it.local)
                    .put("has_key", it.hasKey).put("endpoint", it.endpoint).put("model", it.model)
                    .apply { if (kind == TTS) put("voice", it.voice) }
                    .apply { if (it.localModel.isNotEmpty()) put("local_model", it.localModel) }
                    .put("in_use", it.id == current)
            }))
        }
        val (tts, ttsNow) = profiles(context, TTS)
        val (stt, sttNow) = profiles(context, STT)
        return JSONObject()
            .put("tts", part(TTS, tts, ttsNow))
            .put("stt", part(STT, stt, sttNow))
            .put("tts_services", JSONArray(ttsServices))
            .put("stt_services", JSONArray(sttServices))
            .put("local_models", JSONArray(LocalVoiceModels.models.map {
                JSONObject().put("id", it.id).put("name", it.name).put("for", if (it.recognition) STT else TTS)
                    .put("downloaded", it.downloaded(context))
            }))
    }

    fun field(value: String, name: String): String {
        val v = value.trim()
        require(v.length <= MAX_FIELD && !v.contains('\n')) { "$name must be one line of at most $MAX_FIELD characters" }
        return v
    }

    /** A local model for [kind], checked against the catalog; empty means keep the default. */
    fun localModel(kind: String, id: String): String {
        if (id.isEmpty()) return ""
        val model = LocalVoiceModels.find(id) ?: error("Unknown local_model \"$id\"; pick one from profiles' local_models")
        require(model.recognition == (kind == STT)) { "local_model \"$id\" is for ${if (model.recognition) STT else TTS}, not $kind" }
        return id
    }

    fun title(context: Context, action: JSONObject): String {
        val name = action.optString("profile_name").ifEmpty { action.optString("name") }
        return context.getString(when (action.optString("op")) {
            OP_CREATE -> R.string.orbit_approval_voice_title_create
            OP_SWITCH -> R.string.orbit_approval_voice_title_switch
            OP_KEY -> R.string.orbit_approval_voice_title_key
            else -> R.string.orbit_approval_voice_title_delete
        }, name)
    }

    fun detail(context: Context, action: JSONObject, current: String, reason: String): String = buildString {
        val kind = action.optString("kind")
        append(context.getString(if (kind == TTS) R.string.orbit_approval_voice_kind_tts else R.string.orbit_approval_voice_kind_stt))
        val service = action.optString("service")
        if (service.isNotEmpty()) append("\n").append(context.getString(R.string.orbit_approval_voice_service, serviceLabel(context, kind, service)))
        fun line(key: String, res: Int) = action.optString(key).takeIf { it.isNotEmpty() }?.let { append("\n").append(context.getString(res, it)) }
        line("endpoint", R.string.orbit_approval_voice_endpoint)
        line("model", R.string.orbit_approval_voice_model)
        line("voice", R.string.orbit_approval_voice_voice)
        action.optString("local_model").takeIf { it.isNotEmpty() }?.let { id ->
            append("\n").append(context.getString(R.string.orbit_approval_voice_local, LocalVoiceModels.find(id)?.name ?: id))
        }
        if (current.isNotEmpty() && action.optString("op") in setOf(OP_CREATE, OP_SWITCH)) {
            append("\n").append(context.getString(R.string.orbit_approval_voice_current, current))
        }
        when (action.optString("op")) {
            OP_CREATE -> append("\n").append(context.getString(R.string.orbit_approval_voice_create_note))
            OP_DELETE -> append("\n").append(context.getString(R.string.orbit_approval_voice_delete_note))
        }
        if (action.optBoolean("needs_key")) append("\n").append(context.getString(R.string.orbit_approval_voice_key_needed))
        if (reason.isNotBlank()) append("\n\n").append(context.getString(R.string.orbit_approval_reason, reason.trim()))
    }

    /** Carries out an approved voice request and says plainly what changed. */
    suspend fun apply(context: Context, action: JSONObject, secret: String?, english: Boolean): String {
        val kind = action.getString("kind")
        require(kind == TTS || kind == STT) { "Unknown voice kind" }
        val p = prefs(context)
        val what = if (kind == TTS) (if (english) "read-aloud" else "朗读") else (if (english) "voice input" else "语音输入")
        val key = secret?.trim().orEmpty()
        require(key.length <= 4000) { "API key too long" }
        return when (action.getString("op")) {
            OP_SWITCH -> {
                val id = action.getString("profile_id")
                if (kind == TTS) p.selectTtsProfile(id) else p.selectSttProfile(id)
                val name = action.optString("profile_name")
                if (english) "The $what profile in use is now \"$name\"." else "${what}现在用的是语音配置「$name」。"
            }
            OP_DELETE -> {
                val id = action.getString("profile_id")
                val (_, current) = profiles(context, kind)
                require(id != current) {
                    if (english) "The profile in use cannot be deleted; switch to another one first" else "正在用的配置不能删，先换用别的"
                }
                if (kind == TTS) p.deleteTtsProfile(id) else p.deleteSttProfile(id)
                val name = action.optString("profile_name")
                if (english) "Deleted the $what profile \"$name\"." else "已删除${what}配置「$name」。"
            }
            OP_KEY -> {
                require(key.isNotEmpty()) { "API key missing" }
                val id = action.getString("profile_id")
                if (kind == TTS) {
                    val profile = p.ttsProfilesFlow.first().firstOrNull { it.id == id } ?: error("That voice profile was deleted")
                    p.updateTtsProfile(profile.copy(httpConfig = profile.httpConfig.copy(apiKey = key)))
                } else {
                    val profile = p.sttProfilesFlow.first().firstOrNull { it.id == id } ?: error("That voice profile was deleted")
                    p.updateSttProfile(profile.copy(httpConfig = profile.httpConfig.copy(apiKey = key)))
                }
                val name = action.optString("profile_name")
                if (english) "Saved the new API key the user typed for the $what profile \"$name\". It is used from the next time it speaks or listens."
                else "已为${what}配置「$name」存好用户亲手填的新 API 密钥，下次朗读或听写时生效。"
            }
            OP_CREATE -> create(context, p, kind, action, key, english, what)
            else -> error("Unknown voice request")
        }
    }

    private suspend fun create(context: Context, p: SpeechServiceProfilesPreferences, kind: String, action: JSONObject,
        key: String, english: Boolean, what: String): String {
        val name = action.getString("name")
        val service = action.getString("service")
        val endpoint = action.optString("endpoint")
        val model = action.optString("model")
        val voice = action.optString("voice")
        val local = localModel(kind, action.optString("local_model"))
        require(!action.optBoolean("needs_key") || key.isNotEmpty()) { "API key missing" }
        val localModelId: String
        if (kind == TTS) {
            val type = VoiceServiceType.valueOf(service)
            val template = p.getCurrentTtsProfile()
            val same = template.serviceType == type
            // Another service starts clean, so nothing meant for the old one (a key, headers) goes along.
            var http = if (same) template.httpConfig else SpeechServicesPreferences.TtsHttpConfig("", "", emptyMap()).let {
                if (type == VoiceServiceType.DOUBAO_TTS) it.copy(urlTemplate = DoubaoVoiceProvider.DEFAULT_ENDPOINT_URL,
                    voiceId = DoubaoVoiceProvider.DEFAULT_VOICE_ID) else it
            }
            if (endpoint.isNotEmpty()) http = http.copy(urlTemplate = endpoint)
            if (model.isNotEmpty()) http = http.copy(modelName = model)
            if (voice.isNotEmpty()) http = http.copy(voiceId = voice)
            if (type == VoiceServiceType.SHERPA_ONNX && voice.isEmpty() && !same) http = http.copy(voiceId = "0")
            if (key.isNotEmpty()) http = http.copy(apiKey = key)
            localModelId = local.ifEmpty { template.localModelId }
            // Another service also starts from default reading rules, speed and pitch.
            val base = if (same) template else template.copy(
                vitsConfig = SpeechServicesPreferences.DEFAULT_VITS_TTS_PACKAGE_CONFIG,
                cleanerRegexs = SpeechServicesPreferences.DEFAULT_TTS_CLEANER_REGEXS,
                speechRate = SpeechServicesPreferences.DEFAULT_TTS_SPEECH_RATE,
                pitch = SpeechServicesPreferences.DEFAULT_TTS_PITCH,
            )
            p.createTtsProfile(name, base.copy(serviceType = type, httpConfig = http, localModelId = localModelId))
        } else {
            val type = SpeechServiceType.valueOf(service)
            val template = p.getCurrentSttProfile()
            val same = template.serviceType == type
            var http = if (same) template.httpConfig else SpeechServicesPreferences.SttHttpConfig("", "", "")
            if (endpoint.isNotEmpty()) http = http.copy(endpointUrl = endpoint)
            if (model.isNotEmpty()) http = http.copy(modelName = model)
            if (key.isNotEmpty()) http = http.copy(apiKey = key)
            localModelId = local.ifEmpty { template.localModelId }
            p.createSttProfile(name, template.copy(serviceType = type, httpConfig = http, localModelId = localModelId))
        }
        val label = serviceLabel(context, kind, service)
        val download = if (service == "SHERPA_ONNX" && LocalVoiceModels.find(localModelId)?.downloaded(context) == false) {
            if (english) " Its local model is not downloaded yet; the app asks the user to download it the first time it is used."
            else "它的本机模型还没下载，第一次用时 App 会请用户下载。"
        } else ""
        return if (english) {
            "Added the $what profile \"$name\" ($label)" + (if (key.isNotEmpty()) " with the API key the user typed" else "") +
                ". It is now the profile in use.$download"
        } else {
            "已新建${what}配置「$name」（$label）" + (if (key.isNotEmpty()) "，API 密钥是用户亲手填的" else "") +
                "，现在正在用它。$download"
        }
    }
}
