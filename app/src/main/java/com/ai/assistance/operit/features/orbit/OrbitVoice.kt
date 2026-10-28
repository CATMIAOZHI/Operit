package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
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
    data class Line(val speaker: String, val text: String)

    private val lock = Mutex()
    private const val PAUSE_MS = 350
    /** About 20 minutes of speech, which stays under Orbit's 50 MB file limit for common voices. */
    const val MAX_CHARS = 6_000
    private const val CHUNK_CHARS = 1_000
    /** One request to a read-aloud service; most online services take about this much at once. */
    private const val SERVICE_CHUNK_CHARS = 600
    private const val PREFS = "orbit_voice"

    /**
     * Whether Orbit's voice messages use the app's read-aloud service (the user's chosen TTS, the
     * default) instead of the phone's own voice. Kept per Orbit; the read-aloud settings themselves
     * are shared with the rest of the app.
     */
    fun usesReadAloud(context: Context, orbitId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("read_aloud_$orbitId", true)

    fun setUsesReadAloud(context: Context, orbitId: String, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("read_aloud_$orbitId", value).apply()
    }

    /** What happened when a voice message was made with the read-aloud service. */
    sealed interface ServiceResult {
        /** Made; [extension] is the audio's file type (mp3, wav, …). [profile] is the service's name. */
        class Made(val extension: String, val profile: String) : ServiceResult
        /** The read-aloud service is the phone's own voice, so the phone's voice path is used as it is. */
        object PhoneVoice : ServiceResult
        /** The service cannot make files or failed; [reason] says why in a sentence for Orbit. */
        class Failed(val profile: String, val reason: String) : ServiceResult
    }

    /**
     * Speaks [text] into [out] with the app's current read-aloud service, in pieces the service
     * accepts, joined into one file. Only MP3 and WAV pieces can be joined; a longer text in another
     * format fails and the caller falls back to the phone's voice.
     */
    suspend fun synthesizeWithReadAloud(context: Context, text: String, out: File): ServiceResult {
        require(text.isNotBlank()) { "Nothing to say" }
        require(text.length <= MAX_CHARS) { "The text has ${text.length} characters; one file allows at most $MAX_CHARS. Split it into several parts" }
        val profile = com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences(context.applicationContext)
            .getCurrentTtsProfile()
        if (profile.serviceType == com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType.SIMPLE_TTS) {
            return ServiceResult.PhoneVoice
        }
        val name = profile.name.ifBlank { profile.serviceType.name }
        // A separate instance: making a file must not stop or replace what is being read aloud.
        val service = com.ai.assistance.operit.api.voice.VoiceServiceFactory.createVoiceService(context.applicationContext, profile)
        val temp = File(context.cacheDir, "orbit-voice-${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            val parts = mutableListOf<File>()
            var kind: String? = null
            for (chunk in chunks(text, SERVICE_CHUNK_CHARS)) {
                val part = File(temp, "${parts.size}.bin")
                if (!service.synthesizeToFile(chunk, part)) {
                    return ServiceResult.Failed(name, "the read-aloud service \"$name\" cannot make audio files")
                }
                val ext = audioType(part)
                    ?: return ServiceResult.Failed(name, "the read-aloud service \"$name\" sent something that is not audio")
                if (kind != null && kind != ext) return ServiceResult.Failed(name, "the read-aloud service \"$name\" sent pieces in different formats")
                kind = ext
                parts += part
            }
            val ext = checkNotNull(kind)
            val joined = withContext(Dispatchers.IO) {
                when {
                    parts.size == 1 -> { parts[0].copyTo(out, overwrite = true); true }
                    ext == "wav" -> { joinWavs(parts, out, PAUSE_MS, OrbitFiles.MAX_FILE_BYTES); true }
                    ext == "mp3" -> {
                        if (parts.sumOf { it.length() } > OrbitFiles.MAX_FILE_BYTES) throw AudioTooLarge(
                            "The audio would be larger than ${OrbitFiles.MAX_FILE_BYTES / 1024 / 1024} MB; make it shorter or split it into several parts"
                        )
                        joinMp3s(parts, out)
                        true
                    }
                    else -> false
                }
            }
            if (!joined) {
                return ServiceResult.Failed(name, "the text is too long for one $ext file from \"$name\"; keep it under $SERVICE_CHUNK_CHARS characters")
            }
            ServiceResult.Made(ext, name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: AudioTooLarge) {
            // Orbit has to shorten or split the text; the phone's voice would be just as large.
            throw e
        } catch (e: Exception) {
            ServiceResult.Failed(name, "the read-aloud service \"$name\" failed: ${e.message ?: e.javaClass.simpleName}".take(300))
        } finally {
            service.shutdown()
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
    /** Returns the voice each speaker got (empty name: the engine's default for the language). */
    suspend fun synthesize(context: Context, lines: List<Line>, voices: Map<String, String>, out: File): Map<String, String> {
        require(lines.isNotEmpty() && lines.all { it.text.isNotBlank() }) { "Nothing to say" }
        val length = lines.sumOf { it.text.length }
        require(length <= MAX_CHARS) { "The text has $length characters; one file allows at most $MAX_CHARS. Split it into several parts" }
        return withEngine(context) { tts ->
            val installed = tts.voices.orEmpty().filter {
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) && !it.isNetworkConnectionRequired
            }
            val language = if (lines.any { line -> line.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } })
                "zh" else Locale.getDefault().language
            val sameLanguage = installed.filter { it.locale.language == language }.sortedBy { it.name }
            val speakers = lines.map { it.speaker }.distinct()
            voices.forEach { (speaker, wanted) ->
                require(tts.voices.orEmpty().any { it.name == wanted }) {
                    "Voice \"$wanted\" for $speaker is not on this phone. Call action=voices and use a name from it, or leave the voice out"
                }
            }
            val assigned = speakers.mapIndexed { index, speaker ->
                val named = voices[speaker]?.let { wanted -> tts.voices.orEmpty().firstOrNull { it.name == wanted } }
                speaker to (named ?: sameLanguage.getOrNull(index % sameLanguage.size.coerceAtLeast(1)))
            }.toMap()
            val pitches = speakers.mapIndexed { index, speaker ->
                // Only needed when two speakers share a voice.
                val shared = speakers.take(index).any { assigned[it] == assigned[speaker] }
                speaker to if (shared) 0.75f + 0.3f * (index % 2) else 1f
            }.toMap()
            val parts = mutableListOf<File>()
            val temp = File(context.cacheDir, "orbit-voice-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val total = lines.sumOf { it.text.length }.coerceAtLeast(1)
                var spoken = 0
                var bytes = 0L
                lines.forEach { line ->
                    assigned[line.speaker]?.let { tts.voice = it } ?: tts.setLanguage(Locale.forLanguageTag(language))
                    tts.setPitch(pitches[line.speaker] ?: 1f)
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

    /** Splits long text at sentence ends so each piece fits the engine's limit. */
    fun chunks(text: String, max: Int): List<String> {
        val limit = max.coerceAtLeast(200)
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > limit) {
            val window = rest.substring(0, limit)
            val cut = listOf('。', '！', '？', '.', '!', '?', '\n', '，', ',', ' ').maxOf { window.lastIndexOf(it) }
                .let { if (it < limit / 3) limit else it + 1 }
                // Never split an emoji or other surrogate pair.
                .let { if (Character.isHighSurrogate(rest[it - 1])) it - 1 else it }
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
