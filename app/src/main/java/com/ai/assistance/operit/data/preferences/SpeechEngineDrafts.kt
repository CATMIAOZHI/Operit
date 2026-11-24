package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.api.voice.HttpTtsResponsePipelineStep
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray

/**
 * Scheme, host and port a request really goes to; ws/wss count as http/https. It is read with
 * the same parser the requests use, after template fields such as `{text}` are given a value.
 */
internal data class SpeechOrigin(val secure: Boolean, val host: String, val port: Int) {
    companion object {
        private val TEMPLATE_FIELD = Regex("\\{[^{}]*\\}")
        private val SCHEME = Regex("^(?i)(https?|wss?)://")

        fun of(url: String): SpeechOrigin? {
            val trimmed = url.trim()
            // Only a plain `scheme://host` start is read. Extra slashes, an empty host or a template
            // field in the scheme or host would let the request go to a server only known at run
            // time; such an address matches nothing. Fields in the path or query are fine.
            val scheme = SCHEME.find(trimmed) ?: return null
            val rest = trimmed.substring(scheme.range.last + 1)
            val authority = rest.substring(0, rest.indexOfAny(charArrayOf('/', '?', '#', '\\')).let { if (it < 0) rest.length else it })
            if (authority.isEmpty() || authority.contains('{') || authority.contains('}')) return null
            val filled = TEMPLATE_FIELD.replace(trimmed, "x")
            val http = when {
                filled.startsWith("wss://", ignoreCase = true) -> "https://" + filled.substring(6)
                filled.startsWith("ws://", ignoreCase = true) -> "http://" + filled.substring(5)
                else -> filled
            }.toHttpUrlOrNull() ?: return null
            return SpeechOrigin(http.isHttps, http.host.lowercase(), http.port)
        }
    }
}

/**
 * The fields each service had in a voice profile, kept on disk so switching to another service and
 * back (even after leaving the page) brings the old key, address and voice back. Keys look like
 * `tts:<profile>:<service>`; MiMo also keeps each voice source as `tts:<profile>:MIMO_TTS#<mode>`.
 */
internal object SpeechEngineDrafts {
    const val TTS_FIELDS = 11
    const val STT_FIELDS = 3

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("speech_engine_drafts", Context.MODE_PRIVATE)

    /** Loads the file once off the main thread, so later reads on it do not wait for the disk. */
    fun warm(context: Context) {
        prefs(context).all
    }

    fun save(context: Context, key: String, fields: List<String>) {
        prefs(context).edit().putString(key, JSONArray(fields).toString()).apply()
    }

    fun load(context: Context, key: String, size: Int): List<String>? =
        prefs(context).getString(key, null)?.let { raw ->
            runCatching {
                val array = JSONArray(raw)
                List(size) { if (it < array.length()) array.optString(it) else "" }
            }.getOrNull()
        }

    /** Every value a draft holds, for telling which kept voice recordings are still named. */
    fun allFields(context: Context): Sequence<String> = prefs(context).all.values.asSequence()
        .filterIsInstance<String>()
        .flatMap { raw -> runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.optString(it) } } }.getOrDefault(emptyList()) }

    fun ttsFields(c: SpeechServicesPreferences.TtsHttpConfig): List<String> = listOf(
        c.urlTemplate, c.apiKey, c.modelName, c.voiceId, c.instruction, c.localeTag,
        Json.encodeToString(c.headers), c.httpMethod, c.requestBody, c.contentType,
        HttpTtsResponsePipelineStep.encodeList(c.responsePipeline))

    /** Keeps a saved profile's current service before something else switches it away. */
    fun keepTts(context: Context, profile: SpeechServiceProfilesPreferences.TtsProfile) {
        save(context, "tts:${profile.id}:${profile.serviceType.name}", ttsFields(profile.httpConfig))
    }

    fun keepStt(context: Context, profile: SpeechServiceProfilesPreferences.SttProfile) {
        val c = profile.httpConfig
        save(context, "stt:${profile.id}:${profile.serviceType.name}", listOf(c.endpointUrl, c.apiKey, c.modelName))
    }

    /** A deleted profile's drafts go with it, keys and recording names included. */
    fun dropProfile(context: Context, kind: String, profileId: String) {
        val prefix = "$kind:$profileId:"
        val p = prefs(context)
        val editor = p.edit()
        p.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * Rewrites the voice kept in MiMo read-aloud drafts with [change]: field 4 of a service draft,
     * field 2 of a voice-source draft. Nothing else is opened or changed.
     */
    fun rewriteMimoVoices(context: Context, change: (String) -> String) {
        val p = prefs(context)
        val editor = p.edit()
        var changed = false
        p.all.forEach { (key, raw) ->
            if (raw !is String || !key.startsWith("tts:")) return@forEach
            val slot = when {
                key.endsWith(":MIMO_TTS") -> 3
                key.contains(":MIMO_TTS#") -> 1
                else -> return@forEach
            }
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return@forEach
            if (slot >= array.length()) return@forEach
            val voice = array.optString(slot)
            val next = change(voice)
            if (next != voice) {
                array.put(slot, next)
                editor.putString(key, array.toString())
                changed = true
            }
        }
        if (changed) editor.apply()
    }
}
