package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.speech.LocalVoiceModels
import com.ai.assistance.operit.api.speech.SpeechServiceFactory.SpeechServiceType
import com.ai.assistance.operit.api.voice.DoubaoVoiceProvider
import com.ai.assistance.operit.api.voice.MimoVoiceProvider
import com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType
import com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences
import com.ai.assistance.operit.data.preferences.SpeechServicesPreferences
import com.ai.assistance.operit.data.preferences.SpeechOrigin
import com.ai.assistance.operit.ui.features.settings.screens.SpeechKeyFamily
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
    /** Sets the voice one Orbit reads aloud with: a new copied voice, a saved profile, or the app's. */
    const val OP_OWN = "own"
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
            SpeechServiceType.MIMO_STT.name -> R.string.speech_services_stt_type_mimo
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
                    it.httpConfig.urlTemplate, it.httpConfig.modelName, listedVoice(it.httpConfig.voiceId),
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

    /** A cloned voice is a recording (a path, or inline audio from older versions), never shown in full. */
    private fun listedVoice(voice: String): String =
        if (voice.trim().startsWith("/") || MimoVoiceProvider.isRecording(voice)) "(voice sample)"
        else voice.take(MAX_FIELD)

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

    /** Every profile, without keys, for action=profiles; [orbitId]'s own voice is shown as orbit_voice. */
    suspend fun listJson(context: Context, orbitId: String): JSONObject {
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
        val own = OrbitVoice.ownVoice(context, orbitId)
        return JSONObject()
            .put("tts", part(TTS, tts, ttsNow))
            .put("stt", part(STT, stt, sttNow))
            .put("orbit_voice", tts.firstOrNull { it.id == own }?.let { selectors(tts)[it.id] } ?: JSONObject.NULL)
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
        if (action.optString("op") == OP_OWN) {
            val orbit = action.optString("orbit_name")
            return when {
                action.optString("sample").isNotEmpty() -> context.getString(R.string.orbit_approval_voice_title_own_new, orbit, name)
                action.optString("profile_id").isEmpty() -> context.getString(R.string.orbit_approval_voice_title_own_app, orbit)
                else -> context.getString(R.string.orbit_approval_voice_title_own, orbit, name)
            }
        }
        return context.getString(when (action.optString("op")) {
            OP_CREATE -> R.string.orbit_approval_voice_title_create
            OP_SWITCH -> R.string.orbit_approval_voice_title_switch
            OP_KEY -> R.string.orbit_approval_voice_title_key
            else -> R.string.orbit_approval_voice_title_delete
        }, name)
    }

    fun detail(context: Context, action: JSONObject, current: String, reason: String): String = buildString {
        if (action.optString("op") == OP_OWN) {
            append(context.getString(R.string.orbit_approval_voice_own_note, action.optString("orbit_name")))
            action.optString("sample").takeIf { it.isNotEmpty() }?.let {
                append("\n").append(context.getString(R.string.orbit_approval_voice_own_sample, it))
                val from = action.optString("key_from")
                if (from.isNotEmpty()) append("\n").append(context.getString(R.string.orbit_approval_voice_own_key_from, from))
            }
            append("\n").append(context.getString(R.string.orbit_approval_voice_current, current))
            if (action.optBoolean("needs_key")) append("\n").append(context.getString(R.string.orbit_approval_voice_key_needed))
            if (reason.isNotBlank()) append("\n\n").append(context.getString(R.string.orbit_approval_reason, reason.trim()))
            return@buildString
        }
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

    /** Carries out [orbitId]'s approved voice request and says plainly what changed. */
    suspend fun apply(context: Context, orbitId: String, action: JSONObject, secret: String?, english: Boolean): String {
        val kind = action.getString("kind")
        require(kind == TTS || kind == STT) { "Unknown voice kind" }
        val p = prefs(context)
        val what = if (kind == TTS) (if (english) "read-aloud" else "朗读") else (if (english) "voice input" else "语音输入")
        val key = secret?.trim().orEmpty()
        require(key.length <= 4000) { "API key too long" }
        return when (action.getString("op")) {
            OP_OWN -> own(context, p, orbitId, action, key, english)
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

    /** SHA-256 of a file, so an approved recording is exactly the one the card was made for. */
    fun sha256(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Gives one Orbit its own read-aloud voice. A recording becomes a new MiMo copied-voice profile
     * (with a MiMo key already saved in the app, or the one typed on the card); a saved profile is
     * used as it is; no profile means the app's voice again. The app's own voice never changes.
     */
    private suspend fun own(context: Context, p: SpeechServiceProfilesPreferences, orbitId: String, action: JSONObject,
        key: String, english: Boolean): String {
        require(action.optString("orbit_id") == orbitId) { "This voice request is for another Orbit" }
        // What the user is told when the card can no longer be carried out as it was shown.
        fun problem(res: Int): Nothing = throw OrbitApprovalActions.Problem(context.getString(res))
        val sample = action.optString("sample")
        if (sample.isEmpty()) {
            val id = action.optString("profile_id")
            if (id.isEmpty()) {
                OrbitVoice.setOwnVoice(context, orbitId, "")
                return if (english) "You read aloud with the app's voice again." else "你现在又和 App 用同一个朗读声音了。"
            }
            if (p.ttsProfilesFlow.first().none { it.id == id }) problem(R.string.orbit_voice_own_err_profile_gone)
            val before = OrbitVoice.setOwnVoice(context, orbitId, id)
            // Deleted in the meantime: put back the earlier voice (unless the user picked another since) and say so.
            if (p.ttsProfilesFlow.first().none { it.id == id }) {
                OrbitVoice.replaceOwnVoice(context, orbitId, expected = id, profileId = before)
                problem(R.string.orbit_voice_own_err_profile_gone)
            }
            val name = action.optString("profile_name")
            return if (english) "You now read aloud with \"$name\"; the rest of the app keeps its voice."
            else "你现在用「$name」朗读，App 其他地方的声音不变。"
        }
        val file = OrbitFiles.of(context, orbitId).chatFile(sample) ?: problem(R.string.orbit_voice_own_err_sample_gone)
        if (file.length() > MimoVoiceProvider.MAX_SAMPLE_BYTES) problem(R.string.orbit_voice_own_err_sample_changed)
        // The key the card named: the one typed on it, or the very saved key it showed, still at the same address.
        val http = if (key.isNotEmpty()) {
            key to MimoVoiceProvider.DEFAULT_ENDPOINT_URL
        } else {
            val found = OrbitVoice.mimoKey(context, action.optString("key_owner").ifEmpty { problem(R.string.orbit_voice_own_err_key_gone) })
                ?.second?.takeIf { it.endpoint == action.optString("key_endpoint") }
                ?: problem(R.string.orbit_voice_own_err_key_gone)
            found.key to found.endpoint.ifEmpty { MimoVoiceProvider.DEFAULT_ENDPOINT_URL }
        }
        // Copied and hashed in one read, so what is kept is exactly the recording that was approved.
        val kept = try {
            com.ai.assistance.operit.ui.features.settings.screens.keepVoiceSample(
                context, android.net.Uri.fromFile(file), action.optString("sample_sha256"))
        } catch (_: com.ai.assistance.operit.ui.features.settings.screens.SampleChangedException) {
            problem(R.string.orbit_voice_own_err_sample_changed)
        }
        val template = p.getCurrentTtsProfile()
        val name = action.getString("name")
        // Its own reading rules stay the user's; speed and pitch start at normal for the new voice.
        val profile = p.createTtsProfile(name, template.copy(
            serviceType = VoiceServiceType.MIMO_TTS,
            httpConfig = SpeechServicesPreferences.TtsHttpConfig(
                urlTemplate = http.second, apiKey = http.first, headers = emptyMap(),
                modelName = MimoVoiceProvider.Mode.CLONE.model, voiceId = kept.name,
            ),
            vitsConfig = SpeechServicesPreferences.DEFAULT_VITS_TTS_PACKAGE_CONFIG,
            speechRate = SpeechServicesPreferences.DEFAULT_TTS_SPEECH_RATE,
            pitch = SpeechServicesPreferences.DEFAULT_TTS_PITCH,
        ), select = false)
        OrbitVoice.setOwnVoice(context, orbitId, profile.id)
        return if (english) "Your new voice is saved as \"$name\" and you read aloud with it now; the rest of the app keeps its voice."
        else "新声音已存成「$name」，你现在就用它朗读，App 其他地方的声音不变。"
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
        // Copying a profile to another server leaves its key, headers and voice recording behind.
        var leftKey = false
        fun sameServer(old: String, family: SpeechKeyFamily?): Boolean {
            if (endpoint.isEmpty()) return true
            val fallback = family?.officialUrl.orEmpty()
            val a = SpeechOrigin.of(old.ifBlank { fallback })
            return a != null && a == SpeechOrigin.of(endpoint)
        }
        if (kind == TTS) {
            val type = VoiceServiceType.valueOf(service)
            val template = p.getCurrentTtsProfile()
            val same = template.serviceType == type
            // Another service starts clean, so nothing meant for the old one (a key, headers) goes along.
            var http = if (same) template.httpConfig else SpeechServicesPreferences.TtsHttpConfig("", "", emptyMap()).let {
                if (type == VoiceServiceType.DOUBAO_TTS) it.copy(urlTemplate = DoubaoVoiceProvider.DEFAULT_ENDPOINT_URL,
                    voiceId = DoubaoVoiceProvider.DEFAULT_VOICE_ID) else it
            }
            if (same && !sameServer(http.urlTemplate, SpeechKeyFamily.of(type))) {
                val sample = MimoVoiceProvider.isRecording(http.voiceId)
                leftKey = http.apiKey.isNotEmpty()
                http = http.copy(apiKey = "", headers = emptyMap(), voiceId = if (sample) "" else http.voiceId)
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
            if (same && !sameServer(http.endpointUrl, SpeechKeyFamily.of(type))) {
                leftKey = http.apiKey.isNotEmpty()
                http = http.copy(apiKey = "")
            }
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
        val keyNote = if (leftKey && key.isEmpty()) {
            if (english) " The new address is another server, so the old API key was not carried over; ask the user for its own key."
            else "新地址和原来的不是同一个服务器，所以没有沿用原来的 API 密钥，需要请用户填这个服务器的密钥。"
        } else ""
        return if (english) {
            "Added the $what profile \"$name\" ($label)" + (if (key.isNotEmpty()) " with the API key the user typed" else "") +
                ". It is now the profile in use.$download$keyNote"
        } else {
            "已新建${what}配置「$name」（$label）" + (if (key.isNotEmpty()) "，API 密钥是用户亲手填的" else "") +
                "，现在正在用它。$download$keyNote"
        }
    }
}
