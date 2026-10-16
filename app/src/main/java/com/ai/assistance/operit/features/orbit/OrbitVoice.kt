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
    suspend fun synthesize(context: Context, lines: List<Line>, voices: Map<String, String>, out: File) {
        require(lines.isNotEmpty() && lines.all { it.text.isNotBlank() }) { "Nothing to say" }
        require(lines.sumOf { it.text.length } <= MAX_CHARS) { "The script is longer than $MAX_CHARS characters; split it into several parts" }
        withEngine(context) { tts ->
            val installed = tts.voices.orEmpty().filter {
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) && !it.isNetworkConnectionRequired
            }
            val language = if (lines.any { line -> line.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } })
                "zh" else Locale.getDefault().language
            val sameLanguage = installed.filter { it.locale.language == language }.sortedBy { it.name }
            val speakers = lines.map { it.speaker }.distinct()
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
        require(44 + format.size + dataSize <= maxBytes && dataSize <= 0xFFFFFFFFL - 44 - format.size) {
            "The audio would be larger than ${maxBytes / 1024 / 1024} MB; make it shorter or split it into several parts"
        }
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
