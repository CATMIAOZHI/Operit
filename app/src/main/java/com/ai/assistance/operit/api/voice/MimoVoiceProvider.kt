package com.ai.assistance.operit.api.voice

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.SpeechServicesPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Xiaomi MiMo speech over its Chat Completions API: the text to read goes in an assistant
 * message, an optional user message carries the speaking style (or, for voice design, the voice
 * description), and `audio.voice` names a preset voice or carries a cloned voice's sample.
 */
class MimoVoiceProvider(
    private val context: Context,
    private val config: SpeechServicesPreferences.TtsHttpConfig,
    /** The profile's speed and pitch, used when a sentence brings none of its own. */
    private val speechRate: Float = 1f,
    private val pitch: Float = 1f,
) : VoiceService {

    /** How the voice is chosen; each mode is a separate MiMo model. */
    enum class Mode(val model: String) {
        PRESET("mimo-v2.5-tts"),
        DESIGN("mimo-v2.5-tts-voicedesign"),
        CLONE("mimo-v2.5-tts-voiceclone");

        companion object {
            /** Other or newer model names count by their suffix; anything else is a preset-voice model. */
            fun of(model: String): Mode {
                val name = model.trim()
                return values().firstOrNull { it.model == name } ?: when {
                    name.endsWith("-voicedesign") -> DESIGN
                    name.endsWith("-voiceclone") -> CLONE
                    else -> PRESET
                }
            }
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT_URL = "https://api.xiaomimimo.com/v1/chat/completions"
        const val DEFAULT_MODEL_NAME = "mimo-v2.5-tts"
        const val DEFAULT_VOICE_ID = "mimo_default"
        const val API_KEYS_URL = "https://platform.xiaomimimo.com/#/console/api-keys"
        /** MiMo takes at most 10 MB of Base64, which is about 7.5 MB of audio. */
        const val MAX_SAMPLE_BYTES = 7L * 1024L * 1024L
        private const val SAMPLE_DIR = "tts_voice_samples"
        internal const val USER_PARAM = "mimo_user"
        internal const val VOICE_PARAM = "mimo_voice"

        val AVAILABLE_VOICES =
            listOf(
                VoiceService.Voice(
                    id = DEFAULT_VOICE_ID,
                    name = "MiMo Default",
                    locale = "multi",
                    gender = "NEUTRAL"
                ),
                VoiceService.Voice(id = "冰糖", name = "冰糖", locale = "zh-CN", gender = "FEMALE"),
                VoiceService.Voice(id = "茉莉", name = "茉莉", locale = "zh-CN", gender = "FEMALE"),
                VoiceService.Voice(id = "苏打", name = "苏打", locale = "zh-CN", gender = "MALE"),
                VoiceService.Voice(id = "白桦", name = "白桦", locale = "zh-CN", gender = "MALE"),
                VoiceService.Voice(id = "Mia", name = "Mia", locale = "en-US", gender = "FEMALE"),
                VoiceService.Voice(id = "Chloe", name = "Chloe", locale = "en-US", gender = "FEMALE"),
                VoiceService.Voice(id = "Milo", name = "Milo", locale = "en-US", gender = "MALE"),
                VoiceService.Voice(id = "Dean", name = "Dean", locale = "en-US", gender = "MALE")
            )

        private val DEFAULT_RESPONSE_PIPELINE =
            listOf(
                HttpTtsResponsePipelineStep(type = HttpTtsResponsePipelineStep.TYPE_PARSE_JSON),
                HttpTtsResponsePipelineStep(
                    type = HttpTtsResponsePipelineStep.TYPE_PICK,
                    path = "choices[0].message.audio.data"
                ),
                HttpTtsResponsePipelineStep(type = HttpTtsResponsePipelineStep.TYPE_BASE64_DECODE)
            )

        /** Where a picked voice sample is kept; the profile stores only the file name. */
        fun sampleDir(context: Context): File = File(context.filesDir, SAMPLE_DIR)

        /**
         * Whether a clone voice value names a copied recording. Only the file name counts, so an
         * older absolute path still works after a restore into another package or user.
         */
        fun isSampleRef(voice: String): Boolean =
            !voice.startsWith("data:") && sampleName(voice) != null

        /** Whether a voice value carries a recording: an older inline sample or a kept file. */
        fun isRecording(voice: String): Boolean = voice.trim().let { it.startsWith("data:") || isSampleRef(it) }

        /** "wav" or "mp3" from a recording's first bytes; MiMo takes nothing else. */
        fun sampleFormat(head: ByteArray): String? {
            fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
            fun text(from: Int, s: String) = s.indices.all { at(from + it) == s[it].code }
            return when {
                text(0, "RIFF") && text(8, "WAVE") -> "wav"
                text(0, "ID3") -> "mp3"
                // MPEG audio frame sync with layer III (AAC's ADTS has the same sync but layer 0).
                at(0) == 0xFF && at(1) >= 0 && (at(1) and 0xE0) == 0xE0 && (at(1) and 0x06) == 0x02 -> "mp3"
                else -> null
            }
        }

        /**
         * The file name an older inline sample (`data:audio/...;base64,...`) is kept under, named
         * by its content so the same sample always maps to the same file. Null if it is not one.
         */
        fun inlineSampleName(dataUrl: String): String? {
            val v = dataUrl.trim()
            if (!v.startsWith("data:audio/")) return null
            val payload = v.substringAfter(";base64,", "").takeIf { it.isNotEmpty() } ?: return null
            val ext = if (v.substringBefore(";").contains("wav", ignoreCase = true)) "wav" else "mp3"
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            return "inline-" + digest.take(16).joinToString("") { "%02x".format(it) } + "." + ext
        }

        /**
         * Writes an older inline sample out as a kept recording and returns it. The size is checked
         * before decoding, and the file appears whole or not at all, even with two writers at once.
         */
        fun keepInlineSample(context: Context, dataUrl: String): File? {
            // Size first, counting only Base64 letters (line breaks are skipped when decoding),
            // before anything large is copied; the decoded size is checked again exactly below.
            val marker = dataUrl.indexOf(";base64,")
            if (marker < 0) return null
            var letters = 0L
            for (i in marker + 8 until dataUrl.length) {
                val c = dataUrl[i]
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/') letters++
            }
            if (letters * 3 / 4 > MAX_SAMPLE_BYTES) {
                throw TtsException(context.getString(R.string.mimo_tts_error_voice_sample_too_large))
            }
            val name = inlineSampleName(dataUrl) ?: return null
            val dir = sampleDir(context).apply { mkdirs() }
            val out = File(dir, name)
            if (out.isFile) return out
            val payload = dataUrl.trim().substringAfter(";base64,")
            // Only broken Base64 counts as a bad recording; running out of memory is not one.
            val bytes = try {
                java.util.Base64.getMimeDecoder().decode(payload)
            } catch (_: IllegalArgumentException) {
                return null
            }
            // Checked like a newly picked recording: real size, and an mp3 or wav that matches its name.
            if (bytes.size > MAX_SAMPLE_BYTES) {
                throw TtsException(context.getString(R.string.mimo_tts_error_voice_sample_too_large))
            }
            if (sampleFormat(bytes.copyOf(minOf(bytes.size, 12))) != out.extension) return null
            val part = File(dir, "${java.util.UUID.randomUUID()}.part")
            try {
                part.writeBytes(bytes)
                if (!part.renameTo(out) && !out.isFile) throw java.io.IOException("Could not keep the voice sample")
            } finally {
                part.delete()
            }
            return out
        }

        internal fun sampleName(voice: String): String? =
            voice.trim().substringAfterLast('/').takeIf {
                it.isNotEmpty() && !it.startsWith(".") && it.substringAfterLast('.', "").lowercase() in setOf("mp3", "wav")
            }

        /** The recording inside the app's sample folder; nothing outside it is ever read or uploaded. */
        fun sampleFile(context: Context, voice: String): File? {
            if (voice.startsWith("data:")) return null
            val name = sampleName(voice) ?: return null
            return File(sampleDir(context), name).takeIf { it.isFile }
        }

        /** The preset voice to use; a recording kept from the copy-a-voice mode means the default one. */
        fun presetVoice(voice: String): String {
            val v = voice.trim()
            return if (v.isEmpty() || v.startsWith("data:") || isSampleRef(v)) DEFAULT_VOICE_ID else v
        }

        /** Spoken-language hints for the speed and pitch sliders; nothing when both are normal. */
        internal fun pacingHint(rate: Float, pitch: Float): String = buildList {
            if (kotlin.math.abs(rate - 1f) >= 0.05f) add("语速是平常的 ${String.format(Locale.US, "%.1f", rate)} 倍")
            if (pitch >= 1.05f) add("音调偏高") else if (pitch <= 0.95f) add("音调偏低")
        }.joinToString("，")

        /** What MiMo hears before the text: the style, the speed and pitch, else a plain cue. */
        internal fun userMessage(mode: Mode, instruction: String, rate: Float, pitch: Float): String {
            val parts = listOf(instruction.trim(), pacingHint(rate, pitch)).filter { it.isNotEmpty() }
            return parts.joinToString("。").ifEmpty { if (mode == Mode.DESIGN) "" else "自然地朗读。" }
        }

        /**
         * The request template. It stays the same for every sentence of a profile: what differs per
         * sentence (style with speed, preset voice) are placeholders filled from that sentence's own
         * queued parameters, so a sentence waiting in the queue cannot pick up a later one's.
         */
        internal fun requestBody(mode: Mode, cloneVoice: String?, model: String = mode.model): String {
            val messages = JSONArray()
                .put(JSONObject().put("role", "user").put("content", "{$USER_PARAM}"))
                .put(JSONObject().put("role", "assistant").put("content", "{text}"))
            val audio = JSONObject().put("format", "wav")
            when (mode) {
                Mode.PRESET -> audio.put("voice", "{$VOICE_PARAM}")
                Mode.CLONE -> audio.put("voice", cloneVoice)
                Mode.DESIGN -> Unit
            }
            return JSONObject().put("model", model.trim().ifBlank { mode.model }).put("messages", messages).put("audio", audio)
                .put("stream", false).toString()
        }
    }

    // No redirects: the `api-key` header (and a voice sample) must only reach the address set here.
    private val delegate = HttpVoiceProvider(context, followRedirects = false)
    /** Where the recording goes in the body; random, so no model name or text can match it. */
    private val sampleMarker = "{mimo_sample_${java.util.UUID.randomUUID().toString().replace("-", "")}}"
    private var selectedVoiceId: String = presetVoice(config.voiceId)
    private val mode = Mode.of(config.modelName)
    @Volatile private var configured = false

    override val isInitialized: Boolean
        get() = delegate.isInitialized

    override val isSpeaking: Boolean
        get() = delegate.isSpeaking

    override val speakingStateFlow: Flow<Boolean>
        get() = delegate.speakingStateFlow

    private suspend fun ensureConfigured() {
        if (config.apiKey.isBlank()) {
            throw TtsException(context.getString(R.string.mimo_tts_error_api_key_not_set))
        }
        if (mode == Mode.DESIGN && config.instruction.isBlank()) {
            throw TtsException(context.getString(R.string.mimo_tts_error_voice_description_missing))
        }
        if (configured) return
        val sample = if (mode == Mode.CLONE) sampleToStream() else null
        delegate.setConfiguration(buildHttpConfig(sample?.second))
        delegate.streamedFile = sample?.first
        configured = true
    }

    override suspend fun initialize(): Boolean {
        ensureConfigured()
        return delegate.initialize()
    }

    /** The per-sentence values, kept with each queued sentence. */
    private fun sentenceParams(rate: Float, pitch: Float, voice: String?): Map<String, String> =
        mapOf(
            USER_PARAM to userMessage(mode, config.instruction, rate, pitch),
            VOICE_PARAM to presetVoice(voice ?: selectedVoiceId),
            "voice_key" to voiceKey(),
        )

    override suspend fun speak(
        text: String,
        interrupt: Boolean,
        rate: Float?,
        pitch: Float?,
        extraParams: Map<String, String>
    ): Boolean {
        ensureConfigured()
        // The profile's speed and pitch come with this provider, so nothing is read per sentence.
        val r = rate ?: speechRate
        val p = pitch ?: this.pitch
        val params = sentenceParams(r, p, extraParams["voice"]?.takeIf { it.isNotBlank() })
        return delegate.speak(text = text, interrupt = interrupt, rate = r, pitch = p,
            extraParams = extraParams + params)
    }

    override suspend fun stop(): Boolean = delegate.stop()

    override suspend fun synthesizeToFile(text: String, out: File): Boolean {
        return synthesizeToFile(text, out, null, null)
    }

    override suspend fun synthesizeToFile(text: String, out: File, rate: Float?, pitch: Float?): Boolean {
        ensureConfigured()
        val r = rate ?: speechRate
        val p = pitch ?: this.pitch
        return delegate.synthesizeAudio(text, out, sentenceParams(r, p, null), r, p)
    }

    override suspend fun pause(): Boolean = delegate.pause()

    override suspend fun resume(): Boolean = delegate.resume()

    override fun shutdown() {
        delegate.shutdown()
    }

    override suspend fun getAvailableVoices(): List<VoiceService.Voice> =
        if (mode == Mode.PRESET) AVAILABLE_VOICES else emptyList()

    override suspend fun setVoice(voiceId: String): Boolean {
        selectedVoiceId = presetVoice(voiceId)
        return true
    }

    /**
     * Keeps cached audio apart per mode and recording without putting a whole sample into the key.
     * Each picked recording gets a fresh file name, so the name alone tells them apart.
     */
    private fun voiceKey(): String {
        val model = config.modelName.trim().ifBlank { mode.model }
        return if (mode == Mode.CLONE) "$model:${sampleName(config.voiceId) ?: config.voiceId.hashCode()}" else model
    }

    /**
     * The recording to stream and its type, checked once. An older inline sample is written out
     * to a file first, so it is streamed the same way instead of sitting in the request template.
     */
    private suspend fun sampleToStream(): Pair<HttpVoiceProvider.StreamedFile, String> = withContext(Dispatchers.IO) {
        val voice = config.voiceId.trim()
        val file = if (voice.startsWith("data:audio/")) keepInlineSample(context, voice) else sampleFile(context, voice)
        if (file == null) throw TtsException(context.getString(R.string.mimo_tts_error_voice_sample_missing))
        if (file.length() > MAX_SAMPLE_BYTES) throw TtsException(context.getString(R.string.mimo_tts_error_voice_sample_too_large))
        val mime = if (file.extension.equals("wav", ignoreCase = true)) "audio/wav" else "audio/mpeg"
        HttpVoiceProvider.StreamedFile(sampleMarker, file) to mime
    }

    private fun buildHttpConfig(sampleMime: String?): SpeechServicesPreferences.TtsHttpConfig =
        SpeechServicesPreferences.TtsHttpConfig(
            urlTemplate = config.urlTemplate.ifBlank { DEFAULT_ENDPOINT_URL },
            apiKey = "",
            headers = config.headers + mapOf("api-key" to config.apiKey.trim()),
            httpMethod = "POST",
            // A model name the user typed in older settings is kept as it was.
            // A copied voice's recording is streamed into the marker as the request is sent.
            requestBody = requestBody(mode, sampleMime?.let { "data:$it;base64,$sampleMarker" }, config.modelName),
            contentType = "application/json",
            // Only a short name goes here: the HTTP layer logs and caches by it.
            voiceId = mode.name,
            modelName = config.modelName.trim().ifBlank { mode.model },
            responsePipeline = DEFAULT_RESPONSE_PIPELINE
        )
}
