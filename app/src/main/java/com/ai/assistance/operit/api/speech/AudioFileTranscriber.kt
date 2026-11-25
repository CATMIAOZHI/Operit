package com.ai.assistance.operit.api.speech

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Turns a recording on the phone into words with the speech recognition the user set up (the
 * one voice input uses). The recording is decoded to 16 kHz mono and recognized in pieces of
 * [PIECE_SECONDS], since every engine takes only so much at once; the words of the pieces are
 * joined. Only on demand: every piece may cost money with a cloud service.
 * When that recognition cannot take a recording (Sherpa-ncnn only listens live) or has no key, a
 * local recognition model already on the phone (SenseVoice, Paraformer) does it instead.
 */
object AudioFileTranscriber {
    sealed interface Outcome {
        data class Words(val text: String) : Outcome
        /** Nothing could be done: [message] says what the user can do about it. */
        data class Problem(val message: String) : Outcome
    }

    private const val RATE = 16000
    private const val PIECE_SECONDS = 50
    /** Longer recordings are refused before anything is sent. */
    private const val MAX_SECONDS = 20 * 60
    private const val TIMEOUT_US = 10_000L

    /** The words of the recording [file]. Reads and decodes it, and may send it to a service. */
    suspend fun transcribe(context: Context, file: File): Outcome = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!file.isFile) return@withContext Outcome.Problem(app.getString(R.string.voice_no_transcript))
        val profile = SpeechServiceProfilesPreferences(app).getCurrentSttProfile()
        // The one setting read decides both what is checked and what is made.
        val local: String? = when (profile.serviceType) {
            SpeechServiceFactory.SpeechServiceType.SHERPA_ONNX -> localModel(app, profile.localModelId)
                ?: return@withContext Outcome.Problem(app.getString(R.string.voice_stt_model_missing))
            // It only listens as you speak; it cannot be given a recording.
            SpeechServiceFactory.SpeechServiceType.SHERPA_NCNN -> localModel(app, null)
                ?: return@withContext Outcome.Problem(app.getString(R.string.voice_stt_live_only))
            else -> if (profile.httpConfig.apiKey.isBlank()) {
                localModel(app, null) ?: return@withContext Outcome.Problem(app.getString(R.string.voice_stt_not_set_up))
            } else null
        }
        if (local == null) {
            val service = SpeechServiceFactory.createSpeechService(app, profile.serviceType, profile.httpConfig, profile.localModelId)
            try {
                transcribeWith(app, file, service)
            } finally {
                runCatching { service.shutdown() }
            }
        } else {
            // A local model takes a few hundred MB: one recording at a time, each model gone from
            // memory before the next one loads.
            localGate.withLock {
                val service = OnnxSpeechProvider(app, local)
                try {
                    transcribeWith(app, file, service)
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) { runCatching { service.release() } }
                }
            }
        }
    }

    private val localGate = kotlinx.coroutines.sync.Mutex()

    private suspend fun transcribeWith(app: Context, file: File, service: SpeechService): Outcome {
        return try {
            if (!service.canStartWithoutPrompt(app)) return Outcome.Problem(app.getString(R.string.voice_stt_model_missing))
            // The whole recording is decoded once first: one too long (or not audio) is refused
            // before anything is sent, even when it does not say how long it is.
            decode(app, file, null)?.let { return Outcome.Problem(it) }
            if (!service.initialize()) return Outcome.Problem(problemOf(app, service))
            val words = StringBuilder()
            val failed = decode(app, file) { piece ->
                recognizeOrStop(service, piece)
                if (service.currentState == SpeechService.RecognitionState.ERROR) return@decode problemOf(app, service)
                service.recognitionResultFlow.value.text.trim().takeIf { it.isNotEmpty() }?.let {
                    // Words in a language with spaces are kept apart; Chinese runs on.
                    if (words.isNotEmpty() && it.first().code < 0x2E80) words.append(' ')
                    words.append(it)
                }
                null
            }
            when {
                failed != null -> Outcome.Problem(failed)
                words.isEmpty() -> Outcome.Problem(app.getString(R.string.voice_stt_nothing))
                else -> Outcome.Words(words.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Problem(app.getString(R.string.voice_stt_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    /** The local recognition model that is downloaded, [preferred] first; null when there is none. */
    private fun localModel(context: Context, preferred: String?): String? =
        LocalVoiceModels.models
            .filter { it.recognition }
            .sortedByDescending { it.id == preferred }
            .firstOrNull { it.downloaded(context) }
            ?.id

    /**
     * Recognizes [piece]; when the caller stops waiting, the request under way is cancelled at once
     * ([SpeechService.cancelRecognition] cancels its call), so nothing more is sent or paid for.
     */
    private suspend fun recognizeOrStop(service: SpeechService, piece: FloatArray) = kotlinx.coroutines.coroutineScope {
        var finished = false
        val stop = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                if (!finished) withContext(kotlinx.coroutines.NonCancellable) { runCatching { service.cancelRecognition() } }
            }
        }
        try {
            service.recognize(piece)
        } finally {
            finished = true
            stop.cancel()
        }
    }

    private fun problemOf(context: Context, service: SpeechService): String =
        context.getString(R.string.voice_stt_failed,
            service.recognitionErrorFlow.value.message.ifBlank { context.getString(R.string.speech_error_recognition_failed) })

    /**
     * Decodes [file] to 16 kHz mono and hands it to [piece] [PIECE_SECONDS] at a time; a piece's
     * answer other than null stops it and is returned (as is a recording too long or not audio).
     * Without [piece] it only measures the recording.
     */
    private suspend fun decode(context: Context, file: File, piece: (suspend (FloatArray) -> String?)?): String? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.fromFile(file), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return context.getString(R.string.voice_no_transcript)
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) > MAX_SECONDS * 1_000_000L) {
                return context.getString(R.string.voice_stt_too_long, MAX_SECONDS / 60)
            }
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var floats = false
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            var resampler = Resampler(rate)
            val chunk = FloatArray(if (piece == null) 0 else RATE * PIECE_SECONDS)
            var filled = 0
            // How long the recording is so far, counted the same way in both passes: whole frames at
            // the rate they came at, so exactly the limit is never pushed over it by rounding.
            var earlier = 0.0
            var frames = 0L
            fun tooLong() = earlier + frames.toDouble() / rate > MAX_SECONDS + 1e-6
            val ready = ArrayDeque<FloatArray>()
            val add = Sink { x ->
                chunk[filled++] = x
                if (filled == chunk.size) {
                    ready.addLast(chunk.copyOf())
                    filled = 0
                }
            }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    floats = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    earlier += frames.toDouble() / rate
                    frames = 0L
                    rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                    resampler = Resampler(rate)
                } else if (o >= 0) {
                    val buffer = decoder.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    frames += info.size / (channels * if (floats) 4 else 2)
                    if (piece == null) {
                        // Only measuring.
                    } else if (floats) {
                        val samples = buffer.asFloatBuffer()
                        while (samples.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += samples.get() }
                            resampler.push(sum / channels, add)
                        }
                    } else {
                        val samples = buffer.asShortBuffer()
                        while (samples.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += samples.get() / 32768f }
                            resampler.push(sum / channels, add)
                        }
                    }
                    decoder.releaseOutputBuffer(o, false)
                    if (tooLong()) return context.getString(R.string.voice_stt_too_long, MAX_SECONDS / 60)
                    while (ready.isNotEmpty()) piece!!(ready.removeFirst())?.let { return it }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            // The last words, unless they are too short to hold any (a tenth of a second).
            if (piece != null && filled > RATE / 10) piece(chunk.copyOf(filled))?.let { return it }
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Takes samples one at a time, as plain floats (a lambda would box every one). */
    internal fun interface Sink {
        fun put(x: Float)
    }

    /** Linear resampling from [from] Hz to [RATE], one sample at a time. */
    internal class Resampler(from: Int) {
        private val step = from.coerceAtLeast(1).toDouble() / RATE
        // Where the next sample out falls, counted in samples in.
        private var next = 0.0
        private var index = -1L
        private var previous = 0f

        fun push(x: Float, out: Sink) {
            index++
            if (index == 0L) {
                previous = x
                while (next <= 0.0) { out.put(x); next += step }
                return
            }
            while (next <= index) {
                val f = (next - (index - 1)).toFloat()
                out.put(previous + (x - previous) * f)
                next += step
            }
            previous = x
        }
    }
}
