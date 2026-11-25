package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.ai.assistance.operit.api.voice.MimoVoiceProvider
import com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences
import com.ai.assistance.operit.data.preferences.SpeechServicesPreferences
import com.ai.assistance.operit.ui.features.settings.screens.FoundSpeechKey
import com.ai.assistance.operit.ui.features.settings.screens.SpeechKeyFamily
import com.ai.assistance.operit.ui.features.settings.screens.chatKeySources
import com.ai.assistance.operit.ui.features.settings.screens.keyEndpointFor
import com.ai.assistance.operit.ui.features.settings.screens.profileKeySources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Voice messages and multi-voice audio for Orbit, made with the phone's own text-to-speech
 * engine so it works offline and needs no extra key. Several speakers are joined into one WAV.
 */
object OrbitVoice {
    data class VoiceInfo(val name: String, val locale: String, val needsNetwork: Boolean)
    /**
     * One speaker's words. [style] says how to say them in words, and [text] may carry performance
     * cues like `(温柔)` or `（叹气）`: both are acted by a Xiaomi MiMo voice; any other voice drops them.
     */
    data class Line(val speaker: String, val text: String, val style: String = "")

    private val lock = Mutex()
    private const val PAUSE_MS = 350
    /** About 20 minutes of speech, which stays under Orbit's 50 MB file limit for common voices. */
    const val MAX_CHARS = 6_000
    private const val CHUNK_CHARS = 1_000
    /** One request to a read-aloud service; most online services take about this much at once. */
    private const val SERVICE_CHUNK_CHARS = 600
    private const val PREFS = "orbit_voice"

    /**
     * Whether Orbit's voice messages and podcasts use its read-aloud voice (the default, which has to
     * be a cloud or on-phone model voice) instead of the phone's built-in text-to-speech, which only
     * the user turns on. Kept per Orbit; the read-aloud settings themselves are shared with the app.
     */
    fun usesReadAloud(context: Context, orbitId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("read_aloud_$orbitId", true)

    fun setUsesReadAloud(context: Context, orbitId: String, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("read_aloud_$orbitId", value).apply()
    }

    /**
     * The read-aloud profile this Orbit speaks with, chosen for it alone; empty means it uses the
     * app's read-aloud voice like everything else.
     */
    fun ownVoice(context: Context, orbitId: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tts_profile_$orbitId", "").orEmpty()

    /** Sets [orbitId]'s own voice and returns the one it had, in one step. */
    fun setOwnVoice(context: Context, orbitId: String, profileId: String): String = synchronized(ownVoiceLock) {
        val before = ownVoice(context, orbitId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (profileId.isEmpty()) remove("tts_profile_$orbitId") else putString("tts_profile_$orbitId", profileId)
        }.apply()
        before
    }

    /** Sets [orbitId]'s own voice to [profileId] only while it still is [expected]. */
    fun replaceOwnVoice(context: Context, orbitId: String, expected: String, profileId: String): Boolean = synchronized(ownVoiceLock) {
        if (ownVoice(context, orbitId) != expected) return@synchronized false
        setOwnVoice(context, orbitId, profileId)
        true
    }

    private val ownVoiceLock = Any()

    /** [ownVoice] now and after every change, so a page shows what an approved card just set. */
    fun ownVoiceFlow(context: Context, orbitId: String): Flow<String> = callbackFlow {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "tts_profile_$orbitId") trySend(ownVoice(context, orbitId))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(ownVoice(context, orbitId))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** A read-aloud profile as a settings page lists it. */
    data class VoiceChoice(val id: String, val name: String)

    /**
     * What an Orbit's voice row shows: every read-aloud profile, the app's, and the Orbit's own if it
     * still exists. [noAudioVoice]: the voice it reads with cannot make audio (the phone's own, an on-phone
     * model), so unless the user chose the phone's voice it cannot send voice messages or podcasts.
     */
    data class VoiceState(val choices: List<VoiceChoice>, val appVoice: String, val own: VoiceChoice?, val noAudioVoice: Boolean)

    /**
     * [orbitId]'s voice for its settings, read in the background and kept current: only names and
     * ids, so the page never decodes every profile on the main thread.
     */
    fun voiceStateFlow(context: Context, orbitId: String): Flow<VoiceState> {
        val prefs = SpeechServiceProfilesPreferences(context.applicationContext)
        return combine(prefs.ttsProfilesFlow, prefs.currentTtsProfileIdFlow, ownVoiceFlow(context, orbitId)) { all, app, own ->
            val choices = all.map { VoiceChoice(it.id, it.name) }
            val speaking = all.firstOrNull { it.id == own } ?: all.firstOrNull { it.id == app }
            VoiceState(choices, choices.firstOrNull { it.id == app }?.name.orEmpty(), choices.firstOrNull { it.id == own },
                speaking == null || !makesFiles(speaking))
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    /** The phone's built-in text-to-speech, as opposed to a cloud service or an on-phone voice model. */
    fun isPhoneVoice(profile: SpeechServiceProfilesPreferences.TtsProfile): Boolean =
        profile.serviceType == com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType.SIMPLE_TTS

    /** The read-aloud profile [orbitId] speaks with: its own, else the app's. */
    suspend fun readAloudProfile(context: Context, orbitId: String?): SpeechServiceProfilesPreferences.TtsProfile =
        ownTtsProfile(context, orbitId) ?: SpeechServiceProfilesPreferences(context.applicationContext).getCurrentTtsProfile()

    /** [orbitId]'s own read-aloud profile while that profile still exists; null means it uses the app's. */
    suspend fun ownTtsProfile(context: Context, orbitId: String?): SpeechServiceProfilesPreferences.TtsProfile? {
        val own = orbitId?.let { ownVoice(context, it) }.orEmpty()
        if (own.isEmpty()) return null
        return SpeechServiceProfilesPreferences(context.applicationContext).ttsProfilesFlow.first().firstOrNull { it.id == own }
    }

    /** The own voice of the Orbit [chatId] belongs to; null for any other chat, or an Orbit using the app's. */
    suspend fun ownTtsProfileForChat(context: Context, chatId: String?): SpeechServiceProfilesPreferences.TtsProfile? =
        ownTtsProfile(context, chatId?.let {
            com.ai.assistance.operit.data.preferences.OrbitPreferences.getInstance(context).snapshot.orbitForChat(it)?.id
        })

    /** At most this long: a voice description, and the sentence a designed voice says. */
    const val MAX_DESCRIPTION = 500
    const val MAX_SAMPLE_TEXT = 200

    /**
     * A MiMo key already saved in the app, for the address it works at: voice profiles first (the
     * one in use first), then chat model configs. With [owner], only that saved place counts, so an
     * approved card uses the very key it named. Returns where the key is saved and the key.
     */
    internal suspend fun mimoKey(
        context: Context,
        owner: String? = null,
        snapshot: Pair<List<SpeechServiceProfilesPreferences.TtsProfile>, String>? = null,
    ): Pair<String, FoundSpeechKey>? {
        val prefs = SpeechServiceProfilesPreferences(context.applicationContext)
        // [snapshot]: read-aloud profiles and the one in use, already read by the caller.
        val (all, current) = snapshot ?: (prefs.ttsProfilesFlow.first() to prefs.currentTtsProfileIdFlow.first())
        val tts = all.sortedByDescending { it.id == current }
        fun pick(sources: List<com.ai.assistance.operit.ui.features.settings.screens.SpeechKeySource>): Pair<String, FoundSpeechKey>? {
            for (s in sources) {
                if (s.family != SpeechKeyFamily.MIMO || (owner != null && s.owner != owner)) continue
                val endpoint = keyEndpointFor(SpeechKeyFamily.MIMO, s.url, "") ?: continue
                return s.owner to FoundSpeechKey(s.key, endpoint, s.name)
            }
            return null
        }
        // Chat model configs are read only when no voice profile has a key.
        return pick(profileKeySources(tts, prefs.sttProfilesFlow.first()))
            ?: if (owner == null || owner.startsWith("chat:")) pick(chatKeySources(context)) else null
    }

    /** Says [text] into [out] (WAV) in a new voice MiMo makes from [description]. */
    internal suspend fun designVoice(context: Context, key: FoundSpeechKey, description: String, text: String, out: File) {
        val config = SpeechServicesPreferences.TtsHttpConfig(
            urlTemplate = key.endpoint, apiKey = key.key, headers = emptyMap(),
            modelName = MimoVoiceProvider.Mode.DESIGN.model, instruction = description,
        )
        val provider = MimoVoiceProvider(context.applicationContext, config)
        try {
            check(provider.synthesizeToFile(text, out) && out.length() > 0) { "MiMo sent no audio" }
        } finally {
            provider.shutdown()
        }
    }

    /** Read-aloud services that can make an audio file; the others only play aloud. */
    private val FILE_SERVICES = setOf(
        com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType.HTTP_TTS,
        com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType.OPENAI_TTS,
        com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType.SILICONFLOW_TTS,
        com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType.MINIMAX_TTS,
        com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType.MIMO_TTS,
        com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType.DOUBAO_TTS,
    )

    /** Whether [profile] can make voice messages: a cloud service that sends audio files. */
    fun makesFiles(profile: SpeechServiceProfilesPreferences.TtsProfile): Boolean = profile.serviceType in FILE_SERVICES

    /** The file type a service always sends, when known. */
    private fun knownFormat(profile: SpeechServiceProfilesPreferences.TtsProfile): String? = when (profile.serviceType) {
        com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType.MIMO_TTS -> "wav"
        com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType.OPENAI_TTS,
        com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType.MINIMAX_TTS,
        com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType.DOUBAO_TTS,
        com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType.SILICONFLOW_TTS -> "mp3"
        else -> null
    }

    /**
     * Two voices whose audio can be joined into one file: the same service with a fixed file type.
     * Other services can send MP3 at another sample rate, and a custom HTTP one sends whatever its
     * settings say, so those only join with themselves. Each piece is still checked as it arrives.
     */
    private fun joinable(a: SpeechServiceProfilesPreferences.TtsProfile, b: SpeechServiceProfilesPreferences.TtsProfile): Boolean =
        a.id == b.id || (a.serviceType == b.serviceType && knownFormat(a) != null)

    /**
     * Said to Orbit when its read-aloud voice cannot make audio: what to suggest depends on whether
     * that voice was chosen for this Orbit alone (then setting up the app's voice would not help).
     */
    suspend fun notSetUp(context: Context, orbitId: String?): String {
        val profile = readAloudProfile(context, orbitId)
        val own = ownTtsProfile(context, orbitId) != null
        val what = if (isPhoneVoice(profile)) "the phone's built-in voice" else "\"${profile.name}\", which cannot make audio files"
        return buildString {
            append("You have no cloud voice for voice messages or podcasts yet: the voice you read aloud with is $what")
            append(if (own) ", chosen for you alone. " else ", the app's read-aloud voice. ")
            append("Recommend the user set up a cloud voice for you. If profiles lists a cloud tts profile that is not MiMo, you may offer it ")
            append("with request_own_voice. Otherwise the easiest is Xiaomi MiMo, one key for both read aloud and voice input. With MiMo the ")
            append("user always picks the voice first: ask what voice they want, design 2-3 different samples, wait for them to choose, then ")
            append("request_own_voice with the chosen sample (steps in docs/voice.md). If no MiMo key is saved, design fails: first ")
            append("request_create with kind=tts, service=MIMO_TTS and switch=false so the user types the key on the card, then design. ")
            append("If the user would rather use the phone's built-in voice, put a request_settings card so they turn on ")
            append("\"发语音用手机自带的声音\" (Use the phone's own voice) there. Do not switch to the phone's voice yourself.")
        }
    }

    /** What happened when a voice message was made with the read-aloud voice. */
    sealed interface ServiceResult {
        /** Made; [extension] is the audio's file type (mp3, wav, …). [profile] is the service's name. */
        /** [styled]: the voice acts styles and cues (MiMo); with another, they were left out. */
        class Made(val extension: String, val profile: String, val styled: Boolean = false) : ServiceResult
        /** The read-aloud voice cannot make audio (the phone's own, an on-phone model, …): see [notSetUp]. */
        object NotSetUp : ServiceResult
        /** The service failed; [reason] says why in a sentence for Orbit. */
        class Failed(val profile: String, val reason: String) : ServiceResult
    }

    /** The phone voice settings of [orbitId]'s read-aloud profile, when that is the phone's built-in voice. */
    suspend fun phoneStyle(context: Context, orbitId: String?): PhoneStyle? =
        readAloudProfile(context, orbitId).takeIf(::isPhoneVoice)?.let {
            PhoneStyle(it.httpConfig.voiceId.trim(), it.httpConfig.localeTag.trim(), it.speechRate, it.pitch)
        }

    /**
     * Speaks [text] into [out] with [orbitId]'s read-aloud voice (its own, else the app's), in pieces
     * the service accepts, joined into one file. Only MP3 and WAV pieces can be joined.
     */
    suspend fun synthesizeWithReadAloud(context: Context, text: String, out: File, orbitId: String?, style: String = ""): ServiceResult {
        require(text.isNotBlank()) { "Nothing to say" }
        require(text.length <= MAX_CHARS) { "The text has ${text.length} characters; one file allows at most $MAX_CHARS. Split it into several parts" }
        val profile = readAloudProfile(context, orbitId)
        if (!makesFiles(profile)) return ServiceResult.NotSetUp
        val name = profile.name.ifBlank { profile.serviceType.name }
        // A separate instance: making a file must not stop or replace what is being read aloud. It
        // keeps this profile's own speed and pitch, which some engines would otherwise take from the app's.
        val service = com.ai.assistance.operit.api.voice.VoiceServiceFactory.createProfileVoiceService(context.applicationContext, profile)
        val temp = File(context.cacheDir, "orbit-voice-${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            ServiceResult.Made(synthesizePieces(listOf(Piece(service, name, text, style)), out, temp), name, service.actsStyle)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // Too long or too large: Orbit has to shorten or split the text.
            throw e
        } catch (e: ServiceFailure) {
            ServiceResult.Failed(name, e.message.orEmpty())
        } catch (e: Exception) {
            ServiceResult.Failed(name, "the read-aloud service \"$name\" failed: ${e.message ?: e.javaClass.simpleName}".take(300))
        } finally {
            service.shutdown()
            temp.deleteRecursively()
        }
    }

    /** A voice could not make its part; the message says why in a sentence for Orbit. */
    class ServiceFailure(message: String) : IllegalStateException(message)

    /** At most this many requests to voice services for one file, so a script of tiny lines cannot run up a bill. */
    private const val MAX_REQUESTS = 80

    /** One stretch of text, how to say it ([style]), and the voice ([voice] is its name) that says it. */
    private class Piece(val service: com.ai.assistance.operit.api.voice.VoiceService, val voice: String, val text: String,
        val style: String = "")

    /**
     * Says each piece with its voice, in pieces each service accepts, and joins them into [out];
     * returns the file type. Everything has to come back as MP3, or as WAV of one sample format.
     */
    private suspend fun synthesizePieces(pieces: List<Piece>, out: File, temp: File): String {
        val requests = pieces.flatMap { piece ->
            val mimo = piece.service.actsStyle
            // Only MiMo acts the cues; any other voice would read them out.
            val text = if (mimo) piece.text else MimoVoiceProvider.withoutCues(piece.text)
            // A style cue at the start holds for the whole stretch, so every request of it starts with it.
            val lead = if (mimo) MimoVoiceProvider.leadingCue(text) else ""
            // The repeated style counts toward each request's length, and no cue is cut in two.
            chunks(text, SERVICE_CHUNK_CHARS - lead.length, if (mimo) MimoVoiceProvider.bracketed else null)
                .mapIndexed { i, chunk -> piece to (if (i > 0 && lead.isNotEmpty()) lead + chunk else chunk) }
                .filter { it.second.isNotBlank() }
        }
        require(requests.size <= MAX_REQUESTS) {
            "This needs ${requests.size} requests to the voice service; one file allows at most $MAX_REQUESTS. " +
                "Use fewer, longer lines, or split it into several parts"
        }
        val tooLarge = "The audio would be larger than ${OrbitFiles.MAX_FILE_BYTES / 1024 / 1024} MB; make it shorter or split it into several parts"
        val parts = mutableListOf<File>()
        var kind: String? = null
        var wavFormat: ByteArray? = null
        var mp3Format: Int? = null
        var bytes = 0L
        for ((piece, chunk) in requests) {
            val part = File(temp, "${parts.size}.bin")
            val service = piece.service
            val made = if (service.actsStyle && piece.style.isNotBlank()) service.synthesizeToFile(chunk, part, piece.style)
                else service.synthesizeToFile(chunk, part)
            if (!made) throw ServiceFailure("the voice \"${piece.voice}\" cannot make audio files")
            val ext = audioType(part) ?: throw ServiceFailure("the voice \"${piece.voice}\" sent something that is not audio")
            if (kind != null && kind != ext) {
                throw ServiceFailure("the voices sent audio in different formats ($kind and $ext), which cannot be joined; pick another voice for a speaker, or make separate files")
            }
            // WAV pieces are joined by copying, so they need one sample format; checked as each arrives.
            if (ext == "wav") {
                val format = inspectWav(part).format
                if (wavFormat != null && !format.contentEquals(wavFormat)) {
                    throw ServiceFailure("the voices sent WAV audio with different audio settings, which cannot be joined; pick another voice for a speaker, or make separate files")
                }
                wavFormat = format
            }
            // MP3 pieces are joined frame by frame, so they need one MPEG version, sample rate and channel mode.
            if (ext == "mp3") {
                val signature = mp3Signature(part) ?: throw ServiceFailure("the voice \"${piece.voice}\" sent MP3 audio that cannot be read")
                if (mp3Format != null && signature != mp3Format) {
                    throw ServiceFailure("the voices sent MP3 audio with different audio settings, which cannot be joined; pick another voice for a speaker, or make separate files")
                }
                mp3Format = signature
            }
            kind = ext
            // Only MP3 and WAV can be joined: with more pieces to come, stop before paying for them.
            if (requests.size > 1 && ext != "mp3" && ext != "wav") {
                throw ServiceFailure("the voice sends $ext audio, which cannot be joined; keep it to one piece of at most " +
                    "$SERVICE_CHUNK_CHARS characters, or use a voice that makes MP3 or WAV")
            }
            parts += part
            // Stop as soon as the pieces alone are too much, before asking for more.
            bytes += part.length()
            if (bytes > OrbitFiles.MAX_FILE_BYTES) throw AudioTooLarge(tooLarge)
        }
        val ext = checkNotNull(kind)
        withContext(Dispatchers.IO) {
            when {
                parts.size == 1 -> parts[0].copyTo(out, overwrite = true)
                // One sample format (checked above), so the pieces are copied through without converting.
                ext == "wav" -> joinWavs(parts, out, PAUSE_MS, OrbitFiles.MAX_FILE_BYTES)
                ext == "mp3" -> joinMp3s(parts, out)
                else -> throw ServiceFailure("the voice sends $ext audio, which cannot be joined; keep it to one piece of at most " +
                    "$SERVICE_CHUNK_CHARS characters, or use a voice that makes MP3 or WAV")
            }
        }
        return ext
    }

    /** The name of the Orbit's own read-aloud voice among [cloudVoices]. */
    const val ORBIT_VOICE = "orbit"

    /** A voice a podcast speaker can have: the Orbit's read-aloud voice, a saved one, or a MiMo preset. */
    class CloudVoice(val name: String, val locale: String, val gender: String,
        internal val profile: SpeechServiceProfilesPreferences.TtsProfile)

    /**
     * The voices [orbitId] can make a podcast with; none while its own read-aloud voice cannot make
     * audio. Besides that voice ("orbit"): the other saved read-aloud profiles ("profile:<name>") and
     * MiMo's preset voices ("MiMo·<name>") with a MiMo key saved in the app, but only those whose audio
     * joins with the Orbit's, so a podcast never fails after paying for half of it.
     */
    suspend fun cloudVoices(context: Context, orbitId: String?): List<CloudVoice> {
        val prefs = SpeechServiceProfilesPreferences(context.applicationContext)
        // One read of the profiles for everything below.
        val all = prefs.ttsProfilesFlow.first()
        val ownId = orbitId?.let { ownVoice(context, it) }.orEmpty()
        val appId = prefs.currentTtsProfileIdFlow.first()
        val mine = all.firstOrNull { ownId.isNotEmpty() && it.id == ownId } ?: all.firstOrNull { it.id == appId }
            ?: prefs.getCurrentTtsProfile()
        if (!makesFiles(mine)) return emptyList()
        val voices = mutableListOf(CloudVoice(ORBIT_VOICE, "", "", mine))
        // Each profile by the name profiles shows for it (name#1, name#2 … when several share one).
        val labels = OrbitVoiceProfiles.selectors(all, { it.id }, { it.name })
        all.forEach { p ->
            if (p.id != mine.id && makesFiles(p) && joinable(p, mine)) voices += CloudVoice("profile:${labels[p.id] ?: p.name}", "", "", p)
        }
        val mimo = com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType.MIMO_TTS
        if (mine.serviceType == mimo) {
            mimoKey(context, snapshot = all to appId)?.second?.let { key ->
                MimoVoiceProvider.AVAILABLE_VOICES.filter { it.id != MimoVoiceProvider.DEFAULT_VOICE_ID }.forEach { v ->
                    voices += CloudVoice("MiMo·${v.name}", v.locale, v.gender, mine.copy(
                        serviceType = mimo,
                        httpConfig = SpeechServicesPreferences.TtsHttpConfig(
                            urlTemplate = key.endpoint.ifEmpty { MimoVoiceProvider.DEFAULT_ENDPOINT_URL }, apiKey = key.key,
                            headers = emptyMap(), modelName = MimoVoiceProvider.Mode.PRESET.model, voiceId = v.id,
                        ),
                        vitsConfig = SpeechServicesPreferences.DEFAULT_VITS_TTS_PACKAGE_CONFIG,
                        speechRate = SpeechServicesPreferences.DEFAULT_TTS_SPEECH_RATE,
                        pitch = SpeechServicesPreferences.DEFAULT_TTS_PITCH,
                    ))
                }
            }
        }
        return voices
    }

    /** A podcast made with cloud voices: its file type, each speaker's voice, and whether some had to share one. */
    class Podcast(val extension: String, val voices: Map<String, String>, val shared: Boolean, val styled: Boolean = false)

    /**
     * Speaks [lines] into [out] with cloud voices: each speaker gets the voice named in [wanted], or
     * otherwise a different one, the Orbit's own first, then voices for the text's language.
     */
    suspend fun synthesizePodcast(context: Context, lines: List<Line>, wanted: Map<String, String>, out: File,
        orbitId: String?): Podcast {
        require(lines.isNotEmpty() && lines.all { it.text.isNotBlank() }) { "Nothing to say" }
        val length = lines.sumOf { it.text.length }
        require(length <= MAX_CHARS) { "The text has $length characters; one file allows at most $MAX_CHARS. Split it into several parts" }
        val available = cloudVoices(context, orbitId)
        check(available.isNotEmpty()) { notSetUp(context, orbitId) }
        wanted.forEach { (speaker, name) ->
            require(available.any { it.name == name }) {
                "Voice \"$name\" for $speaker is not available. Call action=voices and use a name from it, or leave it out"
            }
        }
        val language = if (lines.any { line -> line.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } })
            "zh" else "en"
        // Within a language, voices alternate between women and men, so two speakers sound apart.
        val rank = available.groupBy { it.gender }.values.flatMap { same -> same.mapIndexed { i, v -> v to i } }.toMap()
        val pool = available.sortedWith(compareBy({
            when {
                it.name == ORBIT_VOICE -> 0
                it.locale.startsWith(language) -> 1
                it.locale.isEmpty() -> 2
                else -> 3
            }
        }, { rank[it] ?: 0 }))
        val speakers = lines.map { it.speaker }.distinct()
        val assigned = LinkedHashMap<String, CloudVoice>()
        speakers.forEach { speaker -> wanted[speaker]?.let { name -> assigned[speaker] = available.first { it.name == name } } }
        var shared = false
        speakers.forEachIndexed { index, speaker ->
            if (speaker !in assigned) {
                assigned[speaker] = pool.firstOrNull { v -> assigned.values.none { it === v } }
                    ?: pool[index % pool.size].also { shared = true }
            }
        }
        val services = assigned.values.distinct().associateWith {
            com.ai.assistance.operit.api.voice.VoiceServiceFactory.createProfileVoiceService(context.applicationContext, it.profile)
        }
        // A speaker's lines in a row are one request. With a voice that acts styles, a new style
        // (or a style cue opening the line) starts a new one; any other voice drops them, so they
        // never cost extra requests.
        fun acts(speaker: String) = services.getValue(assigned.getValue(speaker)).actsStyle
        // Styled when every line that asked for a style is said by a voice that acts it; read from
        // the lines as written, before a speaker's lines are put together.
        val styled = lines.filter { it.style.isNotBlank() || MimoVoiceProvider.hasCues(it.text) }.all { acts(it.speaker) }
        val turns = lines.fold(mutableListOf<Line>()) { acc, line ->
            val last = acc.lastOrNull()
            if (last != null && last.speaker == line.speaker &&
                (!acts(line.speaker) || (last.style == line.style && MimoVoiceProvider.leadingCue(line.text).isEmpty()))
            ) {
                acc[acc.lastIndex] = Line(last.speaker, last.text + "\n" + line.text, last.style)
            }
            else acc += line
            acc
        }
        val temp = File(context.cacheDir, "orbit-voice-${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            val pieces = turns.map { turn -> assigned.getValue(turn.speaker).let { Piece(services.getValue(it), it.name, turn.text, turn.style) } }
            Podcast(synthesizePieces(pieces, out, temp), assigned.mapValues { it.value.name }, shared, styled)
        } finally {
            services.values.forEach { it.shutdown() }
            temp.deleteRecursively()
        }
    }
    /** The audio would be too big for one file; the text has to be shorter or split. */
    class AudioTooLarge(message: String) : IllegalArgumentException(message)

    /**
     * Joins MP3 pieces into one stream of frames. Each piece's tags and its first-frame length
     * header (Xing/Info/VBRI) are left out: kept, the header of the first piece would tell a player
     * the whole file is as long as that piece. A new header with the joined frame and byte counts
     * goes first, so a player shows the right length even when the bitrate varies.
     */
    private fun joinMp3s(parts: List<File>, out: File) {
        val ranges = parts.map(::mp3Frames)
        var frames = 0L
        var counted = true
        var firstHeader: ByteArray? = null
        // Where each frame starts in the joined audio, for the seek table.
        val offsets = java.util.ArrayList<Long>()
        var before = 0L
        parts.forEachIndexed { i, part ->
            val (start, end) = ranges[i]
            java.io.RandomAccessFile(part, "r").use { raf ->
                val header = ByteArray(4)
                var at = start
                while (counted && at + 4 <= end) {
                    raf.seek(at)
                    raf.readFully(header)
                    val length = mp3FrameLength(header)
                    if (length == null || length < 4) {
                        counted = false
                    } else {
                        if (firstHeader == null) firstHeader = header.copyOf()
                        offsets += before + (at - start)
                        frames++
                        at += length
                    }
                }
            }
            before += end - start
        }
        val audioBytes = ranges.sumOf { it.second - it.first }
        val lead = firstHeader?.takeIf { counted && frames > 0 && frames <= Int.MAX_VALUE }
            ?.let { xingFrame(it, offsets, audioBytes) }
        out.parentFile?.mkdirs()
        out.outputStream().buffered().use { stream ->
            if (lead != null) stream.write(lead)
            val chunk = ByteArray(64 * 1024)
            parts.forEachIndexed { i, part ->
                val (start, end) = ranges[i]
                java.io.RandomAccessFile(part, "r").use { raf ->
                    raf.seek(start)
                    var left = end - start
                    while (left > 0) {
                        val n = raf.read(chunk, 0, minOf(chunk.size.toLong(), left).toInt())
                        if (n < 0) break
                        stream.write(chunk, 0, n)
                        left -= n
                    }
                }
            }
        }
    }

    /**
     * A silent frame shaped like [audioHeader]'s that only carries a Xing header: the number of
     * audio frames, the file's total bytes ([audioBytes] plus this frame) and a 100-step seek table
     * built from the frames' [offsets] in the audio, so a player can show the length and seek.
     * Null if it cannot be built.
     */
    private fun xingFrame(audioHeader: ByteArray, offsets: List<Long>, audioBytes: Long): ByteArray? {
        val header = audioHeader.copyOf()
        header[2] = (header[2].toInt() and 0xFD).toByte() // no padding
        header[1] = (header[1].toInt() or 0x01).toByte() // no CRC, so the side info starts right after the header
        val mpeg1 = ((header[1].toInt() shr 3) and 0x3) == 3
        val mono = ((header[3].toInt() and 0xFF) shr 6) == 3
        val at = 4 + if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
        // "Xing", flags, frame count, byte count, then the 100-byte table.
        val need = at + 16 + 100
        // A low bitrate makes too small a frame for the table; this silent frame may use a higher one.
        var length = mp3FrameLength(header) ?: return null
        while (length < need) {
            val index = (header[2].toInt() and 0xFF) shr 4
            if (index >= 14) return null
            header[2] = (((index + 1) shl 4) or (header[2].toInt() and 0x0F)).toByte()
            length = mp3FrameLength(header) ?: return null
        }
        val total = audioBytes + length
        if (total > Int.MAX_VALUE || offsets.isEmpty()) return null
        val frames = offsets.size
        val frame = ByteArray(length)
        System.arraycopy(header, 0, frame, 0, 4)
        val toc = ByteArray(100) { i ->
            // Share of the whole file, in 256ths, at which i percent of the frames have played.
            val position = length + offsets[(i.toLong() * frames / 100).toInt()]
            (position * 256 / total).coerceIn(0, 255).toByte()
        }
        java.nio.ByteBuffer.wrap(frame, at, 16 + 100).put("Xing".toByteArray(Charsets.ISO_8859_1))
            .putInt(0x7).putInt(frames).putInt(total.toInt()).put(toc)
        return frame
    }

    /** Where the audio frames of an MP3 file start and end, without tags or a length header frame. */
    private fun mp3Frames(file: File): Pair<Long, Long> = java.io.RandomAccessFile(file, "r").use { raf ->
        val size = raf.length()
        var start = 0L
        var end = size
        val head = ByteArray(10)
        // ID3v2 tags at the start (there can be more than one).
        while (start + 10 <= size) {
            raf.seek(start)
            raf.readFully(head)
            if (String(head, 0, 3, Charsets.ISO_8859_1) != "ID3") break
            val body = ((head[6].toLong() and 0x7F) shl 21) or ((head[7].toLong() and 0x7F) shl 14) or
                ((head[8].toLong() and 0x7F) shl 7) or (head[9].toLong() and 0x7F)
            val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
            start += 10 + body + footer
        }
        // ID3v1 tag at the end.
        if (end - start >= 128) {
            val tag = ByteArray(3)
            raf.seek(end - 128)
            raf.readFully(tag)
            if (String(tag, Charsets.ISO_8859_1) == "TAG") end -= 128
        }
        // A first frame that only carries the length and seek table.
        if (end - start >= 48) {
            val frame = ByteArray(48)
            raf.seek(start)
            raf.readFully(frame)
            val length = mp3FrameLength(frame)
            if (length != null && start + length <= end) {
                val text = String(frame, Charsets.ISO_8859_1)
                if (listOf("Xing", "Info", "VBRI").any { text.indexOf(it, 4) in 4..40 }) start += length
            }
        }
        start.coerceAtMost(end) to end
    }

    /** The MPEG version, sample rate and channel mode of [file]'s first audio frame, or null without one. */
    private fun mp3Signature(file: File): Int? {
        val start = mp3Frames(file).first
        val header = ByteArray(4)
        java.io.RandomAccessFile(file, "r").use { raf ->
            if (start + 4 > raf.length()) return null
            raf.seek(start)
            raf.readFully(header)
        }
        if (mp3FrameLength(header) == null) return null
        fun at(i: Int) = header[i].toInt() and 0xFF
        return (((at(1) shr 3) and 0x3) shl 4) or (((at(2) shr 2) and 0x3) shl 2) or ((at(3) shr 6) and 0x3)
    }

    /** The byte length of the MPEG audio layer III frame whose header starts [frame], or null. */
    private fun mp3FrameLength(frame: ByteArray): Int? {
        fun at(i: Int) = frame[i].toInt() and 0xFF
        if (at(0) != 0xFF || (at(1) and 0xE0) != 0xE0) return null
        val version = (at(1) shr 3) and 0x3 // 3 = MPEG 1, 2 = MPEG 2, 0 = MPEG 2.5
        val layer = (at(1) shr 1) and 0x3 // 1 = layer III
        if (version == 1 || layer != 1) return null
        val bitrateIndex = (at(2) shr 4) and 0xF
        val rateIndex = (at(2) shr 2) and 0x3
        if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val padding = (at(2) shr 1) and 0x1
        val mpeg1 = version == 3
        val bitrate = (if (mpeg1) MP3_V1_KBPS else MP3_V2_KBPS)[bitrateIndex] * 1000
        val rate = when (version) {
            3 -> intArrayOf(44100, 48000, 32000)
            2 -> intArrayOf(22050, 24000, 16000)
            else -> intArrayOf(11025, 12000, 8000)
        }[rateIndex]
        return (if (mpeg1) 144 else 72) * bitrate / rate + padding
    }

    private val MP3_V1_KBPS = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MP3_V2_KBPS = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /** The audio type of [file] from its first bytes, or null when it is not audio a player opens. */
    private fun audioType(file: File): String? {
        if (file.length() < 4) return null
        val head = ByteArray(12)
        val n = file.inputStream().use { it.read(head) }
        fun at(i: Int) = head[i].toInt() and 0xFF
        fun text(from: Int, len: Int) = if (n >= from + len) String(head, from, len, Charsets.ISO_8859_1) else ""
        return when {
            text(0, 4) == "RIFF" && text(8, 4) == "WAVE" -> "wav"
            text(0, 3) == "ID3" -> "mp3"
            text(0, 4) == "OggS" -> "ogg"
            text(0, 4) == "fLaC" -> "flac"
            text(4, 4) == "ftyp" -> "m4a"
            at(0) == 0xFF && (at(1) and 0xF6) == 0xF0 -> "aac"
            at(0) == 0xFF && (at(1) and 0xE0) == 0xE0 -> "mp3"
            else -> null
        }
    }

    private suspend fun <T> withEngine(context: Context, block: suspend (TextToSpeech) -> T): T = lock.withLock {
        var engine: TextToSpeech? = null
        try {
            // withTimeoutOrNull: a stuck engine is a tool error, not a cancelled turn.
            val ready = withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine<Int> { cont ->
                    engine = TextToSpeech(context.applicationContext) { status -> if (cont.isActive) cont.resume(status) }
                    // If this times out, the engine is still shut down in finally because it was assigned here.
                }
            } ?: error("The phone's text-to-speech engine did not start within 15 seconds. Ask the user to check the text-to-speech engine in system settings, then try again")
            check(ready == TextToSpeech.SUCCESS) { "The phone has no working text-to-speech engine. Tell the user to install or turn on one (for example Google Speech Services) in system settings under Text-to-speech output" }
            block(engine!!)
        } finally {
            engine?.shutdown()
        }
    }

    suspend fun voices(context: Context): List<VoiceInfo> = withEngine(context) { tts ->
        tts.voices.orEmpty()
            .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .map { VoiceInfo(it.name, it.locale.toLanguageTag(), it.isNetworkConnectionRequired) }
            .sortedWith(compareBy({ it.locale }, { it.name }))
    }

    /**
     * Speaks [lines] into [out] as one WAV. Each speaker gets the voice named in [voices], or
     * otherwise a different installed voice for the text's language; with only one voice on the
     * phone, speakers are told apart by pitch.
     */
    /**
     * How a phone-voice read-aloud profile speaks: its voice (used only if the phone still has it),
     * language, speed and pitch. Empty voice or language means the automatic choice.
     */
    data class PhoneStyle(val voice: String, val locale: String, val rate: Float, val pitch: Float)

    /** Same language and country; engines report either two- or three-letter codes. */
    private fun sameRegion(a: Locale, b: Locale): Boolean = runCatching {
        a.isO3Language == b.isO3Language && a.isO3Country == b.isO3Country
    }.getOrElse { a.language == b.language && a.country.equals(b.country, ignoreCase = true) }

    /**
     * Returns the voice each speaker got (empty name: the engine's default for the language).
     * [style] is a read-aloud profile's own way of speaking, for a plain voice message.
     */
    suspend fun synthesize(context: Context, lines: List<Line>, voices: Map<String, String>, out: File,
        style: PhoneStyle? = null): Map<String, String> {
        require(lines.isNotEmpty() && lines.all { it.text.isNotBlank() }) { "Nothing to say" }
        val length = lines.sumOf { it.text.length }
        require(length <= MAX_CHARS) { "The text has $length characters; one file allows at most $MAX_CHARS. Split it into several parts" }
        return withEngine(context) { tts ->
            val installed = tts.voices.orEmpty().filter {
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) && !it.isNetworkConnectionRequired
            }
            val styleLocale = style?.locale?.takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it) }
            val language = styleLocale?.language?.takeIf { it.isNotEmpty() }
                ?: if (lines.any { line -> line.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } })
                    "zh" else Locale.getDefault().language
            val sameLanguage = installed.filter { it.locale.language == language }.sortedBy { it.name }
            // A profile set to a region (zh-TW, pt-BR) only picks that region's voices; with none
            // installed the engine chooses one for that exact locale.
            val pool = if (styleLocale != null && styleLocale.country.isNotEmpty())
                installed.filter { sameRegion(it.locale, styleLocale) }.sortedBy { it.name } else sameLanguage
            val speakers = lines.map { it.speaker }.distinct()
            voices.forEach { (speaker, wanted) ->
                require(tts.voices.orEmpty().any { it.name == wanted }) {
                    "Voice \"$wanted\" for $speaker is not on this phone. Call action=voices and use a name from it, or leave the voice out"
                }
            }
            val assigned = speakers.mapIndexed { index, speaker ->
                val named = voices[speaker]?.let { wanted -> tts.voices.orEmpty().firstOrNull { it.name == wanted } }
                    // The profile's voice, if this phone still has it; otherwise the automatic one.
                    ?: style?.voice?.takeIf { it.isNotEmpty() }?.let { wanted ->
                        tts.voices.orEmpty().firstOrNull { it.name == wanted && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                    }
                speaker to (named ?: pool.getOrNull(index % pool.size.coerceAtLeast(1)))
            }.toMap()
            val pitches = speakers.mapIndexed { index, speaker ->
                // Only needed when two speakers share a voice.
                val shared = speakers.take(index).any { assigned[it] == assigned[speaker] }
                speaker to if (shared) 0.75f + 0.3f * (index % 2) else 1f
            }.toMap()
            val parts = mutableListOf<File>()
            val temp = File(context.cacheDir, "orbit-voice-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                tts.setSpeechRate(style?.rate ?: 1f)
                val total = lines.sumOf { it.text.length }.coerceAtLeast(1)
                var spoken = 0
                var bytes = 0L
                lines.forEach { line ->
                    assigned[line.speaker]?.let { tts.voice = it } ?: tts.setLanguage(styleLocale ?: Locale.forLanguageTag(language))
                    tts.setPitch((pitches[line.speaker] ?: 1f) * (style?.pitch ?: 1f))
                    chunks(line.text, minOf(CHUNK_CHARS, TextToSpeech.getMaxSpeechInputLength() - 50)).forEach { chunk ->
                        val file = File(temp, "${parts.size}.wav")
                        speakTo(tts, chunk, file)
                        parts += file
                        // Stop early when the audio made so far shows the whole thing will not fit.
                        spoken += chunk.length
                        bytes += file.length()
                        check(bytes * total / spoken.coerceAtLeast(1) <= OrbitFiles.MAX_FILE_BYTES * 11 / 10) {
                            "The audio would be larger than ${OrbitFiles.MAX_FILE_BYTES / 1024 / 1024} MB with this voice; make it shorter or split it into several parts"
                        }
                    }
                }
                withContext(Dispatchers.IO) { joinWavs(parts, out, PAUSE_MS, OrbitFiles.MAX_FILE_BYTES) }
            } finally {
                temp.deleteRecursively()
            }
            speakers.associateWith { assigned[it]?.name.orEmpty() }
        }
    }

    private suspend fun speakTo(tts: TextToSpeech, text: String, file: File) {
        val id = UUID.randomUUID().toString()
        // About 6 characters a second of speech; engines render faster than real time.
        val ok = withTimeoutOrNull(60_000L + text.length * 200L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                    override fun onError(utteranceId: String?, errorCode: Int) { onError(utteranceId) }
                })
                if (tts.synthesizeToFile(text, null, file, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
                cont.invokeOnCancellation { tts.stop() }
            }
        } ?: run {
            tts.stop()
            error("The text-to-speech engine took too long on one sentence")
        }
        check(ok && file.length() > 44) { "The text-to-speech engine could not make audio; the chosen voice may not be downloaded. Try another voice from action=voices" }
    }

    /**
     * Splits long text at sentence ends so each piece fits the engine's limit. A match of
     * [keepWhole] (a performance cue) is never cut in two.
     */
    fun chunks(text: String, max: Int, keepWhole: Regex? = null): List<String> {
        val limit = max.coerceAtLeast(200)
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > limit) {
            val window = rest.substring(0, limit)
            var cut = listOf('。', '！', '？', '.', '!', '?', '\n', '，', ',', ' ').maxOf { window.lastIndexOf(it) }
                .let { if (it < limit / 3) limit else it + 1 }
                // Never split an emoji or other surrogate pair.
                .let { if (Character.isHighSurrogate(rest[it - 1])) it - 1 else it }
            // Looked for a little past the window too, so a cue the window ends inside is found.
            keepWhole?.findAll(rest.substring(0, minOf(rest.length, limit + 64)))
                ?.firstOrNull { cut in (it.range.first + 1)..it.range.last }?.let { inside ->
                cut = if (inside.range.first > 0) inside.range.first else inside.range.last + 1
            }
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** Where a WAV file's audio sits, read without loading the audio itself. */
    private class WavInfo(val file: File, val format: ByteArray, val dataStart: Long, val dataLength: Long)

    private fun inspectWav(file: File): WavInfo = java.io.RandomAccessFile(file, "r").use { raf ->
        val head = ByteArray(12)
        require(raf.length() >= 12) { "Not a WAV file" }
        raf.readFully(head)
        require(String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") { "Not a WAV file" }
        var at = 12L
        var format: ByteArray? = null
        val chunk = ByteArray(8)
        while (at + 8 <= raf.length()) {
            raf.seek(at)
            raf.readFully(chunk)
            val id = String(chunk, 0, 4)
            val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val bodyStart = at + 8
            if (id == "data") {
                // Some engines write a data size of 0 or 0xFFFFFFFF while streaming; take the rest of the file.
                val rest = raf.length() - bodyStart
                val length = if (size == 0L || size > rest) rest else size
                return@use WavInfo(file, checkNotNull(format) { "WAV has no format" }, bodyStart, length)
            }
            if (id == "fmt ") {
                require(size in 16..64 && bodyStart + size <= raf.length()) { "WAV format block is broken" }
                format = ByteArray(size.toInt()).also { raf.readFully(it) }
            }
            at = bodyStart + size + (size and 1)
        }
        error("WAV has no audio")
    }

    private fun readData(info: WavInfo): ByteArray = java.io.RandomAccessFile(info.file, "r").use { raf ->
        raf.seek(info.dataStart)
        ByteArray(info.dataLength.toInt()).also { raf.readFully(it) }
    }

    /**
     * Joins WAV files with a short pause between them, streaming to [out]. Voices can speak at
     * different sample rates or channel counts; 16-bit PCM pieces are converted to the first
     * piece's format. Fails before writing if the result would be larger than [maxBytes].
     */
    fun joinWavs(parts: List<File>, out: File, pauseMs: Int, maxBytes: Long = Long.MAX_VALUE) {
        val infos = parts.map(::inspectWav)
        val format = infos.first().format
        val target = pcmFormat(format)
        val buffer = ByteBuffer.wrap(format).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = buffer.getInt(8)
        val blockAlign = buffer.getShort(12).toInt().coerceAtLeast(1)
        val pause = ByteArray((byteRate.toLong() * pauseMs / 1000 / blockAlign * blockAlign).toInt())
        fun lengthOf(info: WavInfo): Long =
            if (info.format.contentEquals(format)) info.dataLength
            else {
                val from = pcmFormat(info.format)
                checkConvertible(from, target)
                val frames = info.dataLength / (from.channels * 2)
                (if (frames == 0L) 0L else (frames * target.rate / from.rate).coerceAtLeast(1)) * target.channels * 2
            }
        val dataSize = infos.sumOf(::lengthOf) + pause.size.toLong() * (infos.size - 1)
        if (44 + format.size + dataSize > maxBytes || dataSize > 0xFFFFFFFFL - 44 - format.size) throw AudioTooLarge(
            "The audio would be larger than ${maxBytes / 1024 / 1024} MB; make it shorter or split it into several parts"
        )
        val header = ByteBuffer.allocate(20 + format.size + 8).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((4 + 8 + format.size + 8 + dataSize).toInt()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(format.size).put(format)
        header.put("data".toByteArray()).putInt(dataSize.toInt())
        out.parentFile?.mkdirs()
        out.outputStream().buffered().use { stream ->
            stream.write(header.array())
            infos.forEachIndexed { index, info ->
                if (index > 0) stream.write(pause)
                if (info.format.contentEquals(format)) {
                    java.io.RandomAccessFile(info.file, "r").use { raf ->
                        raf.seek(info.dataStart)
                        val chunk = ByteArray(64 * 1024)
                        var left = info.dataLength
                        while (left > 0) {
                            val n = raf.read(chunk, 0, minOf(chunk.size.toLong(), left).toInt())
                            check(n >= 0) { "A voice piece ended early" }
                            stream.write(chunk, 0, n)
                            left -= n
                        }
                    }
                } else {
                    stream.write(convertPcm16(readData(info), pcmFormat(info.format), target))
                }
            }
        }
    }
    private class PcmFormat(val tag: Int, val channels: Int, val rate: Int, val bits: Int)

    private fun pcmFormat(format: ByteArray): PcmFormat {
        require(format.size >= 16) { "WAV format block is too short" }
        val b = ByteBuffer.wrap(format).order(ByteOrder.LITTLE_ENDIAN)
        return PcmFormat(b.getShort(0).toInt(), b.getShort(2).toInt(), b.getInt(4), b.getShort(14).toInt())
    }

    private fun checkConvertible(from: PcmFormat, to: PcmFormat) =
        require(from.tag == 1 && to.tag == 1 && from.bits == 16 && to.bits == 16 && from.channels in 1..2 && to.channels in 1..2 && from.rate > 0) {
            "These voices make audio in different formats; use voices from one engine"
        }

    /** Linear resampling and channel mixing for 16-bit PCM. */
    private fun convertPcm16(data: ByteArray, from: PcmFormat, to: PcmFormat): ByteArray {
        checkConvertible(from, to)
        val input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = input.remaining() / from.channels
        // Mono samples at the source rate.
        val mono = FloatArray(frames) { f ->
            if (from.channels == 1) input.get(f).toFloat() else (input.get(f * 2) + input.get(f * 2 + 1)) / 2f
        }
        val outFrames = if (frames == 0) 0 else ((frames.toLong() * to.rate) / from.rate).toInt().coerceAtLeast(1)
        val output = ByteBuffer.allocate(outFrames * to.channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outFrames) {
            val pos = i.toDouble() * from.rate / to.rate
            val left = pos.toInt().coerceAtMost(frames - 1)
            val right = (left + 1).coerceAtMost(frames - 1)
            val t = (pos - left).toFloat()
            val sample = (mono[left] * (1 - t) + mono[right] * t).toInt().coerceIn(-32768, 32767).toShort()
            repeat(to.channels) { output.putShort(sample) }
        }
        return output.array()
    }
}
