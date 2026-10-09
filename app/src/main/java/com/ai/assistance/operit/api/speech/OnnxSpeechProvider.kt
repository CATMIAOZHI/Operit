package com.ai.assistance.operit.api.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import com.ai.assistance.operit.util.AppLogger
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** On-device utterance recognition. VAD endpoints audio; inference never runs on the UI thread. */
internal class OnnxSpeechProvider(private val context: Context, private val modelId: String) : SpeechService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nativeMutex = Mutex()
    private val lifecycle = Mutex()
    private val epoch = SpeechRequestEpoch()
    private var recognizer: OfflineRecognizer? = null
    private var record: AudioRecord? = null
    private var effects: CaptureAudioEffects? = null
    private var captureJob: Job? = null
    private var samples = FloatArray(0)
    private var length = 0
    @Volatile private var closed = false
    override val isInitialized = MutableStateFlow(false)
    override val recognitionStateFlow = MutableStateFlow(SpeechService.RecognitionState.UNINITIALIZED)
    override val recognitionResultFlow = MutableStateFlow(SpeechService.RecognitionResult(""))
    override val recognitionErrorFlow = MutableStateFlow(SpeechService.RecognitionError(0, ""))
    override val volumeLevelFlow = MutableStateFlow(0f)
    override val speechActivityFlow = MutableStateFlow(false)
    override val currentState get() = recognitionStateFlow.value
    override val isRecognizing get() = currentState == SpeechService.RecognitionState.RECOGNIZING

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            LocalVoiceModels.withModelFiles(context, modelId) { directory ->
            nativeMutex.withLock {
                check(!closed) { "Speech service closed" }
                if (recognizer == null) {
                    val model = directory.resolve("model.int8.onnx").absolutePath
                    val config = OfflineModelConfig(
                        tokens = directory.resolve("tokens.txt").absolutePath, numThreads = 2,
                        provider = "cpu",
                    )
                    if (modelId == "sensevoice-int8") {
                        config.senseVoice = OfflineSenseVoiceModelConfig(model = model, language = "auto",
                            useInverseTextNormalization = true)
                    } else {
                        config.paraformer = OfflineParaformerModelConfig(model = model)
                        config.modelType = "paraformer"
                    }
                    recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = config))
                }
                isInitialized.value = true
            }
            }
            recognitionStateFlow.value = SpeechService.RecognitionState.IDLE
            true
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { failure(e); false }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startRecognition(languageCode: String, continuousMode: Boolean,
                                          partialResults: Boolean, audioSource: Int): Boolean =
        lifecycle.withLock {
            if (closed) return@withLock false
            if (isRecognizing || currentState == SpeechService.RecognitionState.PROCESSING) return@withLock false
            if (!isInitialized.value && !initialize()) return@withLock false
            val session = epoch.begin()
            recognitionResultFlow.value = SpeechService.RecognitionResult("")
            recognitionErrorFlow.value = SpeechService.RecognitionError(0, "")
            recognitionStateFlow.value = SpeechService.RecognitionState.PREPARING
            try {
                withContext(Dispatchers.IO) {
                    val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    check(min > 0) { "Microphone unavailable" }
                    val mic = AudioRecord(audioSource,16000,AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 4096))
                    record = mic
                    check(mic.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
                    effects = CaptureAudioEffects(mic.audioSessionId)
                    samples = FloatArray(16000 * 60)
                    length = 0
                    SpeechPrerollStore.consumePending()?.let { preroll ->
                        for (sample in preroll.take(16000)) samples[length++] = sample / 32768f
                    }
                    mic.startRecording()
                    recognitionStateFlow.value = SpeechService.RecognitionState.RECOGNIZING
                    captureJob = scope.launch {
                        try {
                            OnnxSileroVad(context, speechDurationMs = 100, silenceDurationMs = 700).use { vad ->
                                val frame = ShortArray(512)
                                var heardSpeech = false
                                while (isActive && session == epoch.current()) {
                                    val count = mic.read(frame,0,frame.size)
                                    check(count > 0) { "Microphone read failed: $count" }
                                    var energy = 0f
                                    for (i in 0 until count) {
                                        val value = frame[i] / 32768f
                                        if (length < samples.size) samples[length++] = value
                                        energy += value * value
                                    }
                                    volumeLevelFlow.value = kotlin.math.sqrt(energy / count).coerceIn(0f,1f)
                                    val voice = if (count == frame.size) vad.isSpeech(frame) else heardSpeech
                                    if (voice) heardSpeech = true
                                    speechActivityFlow.value = voice
                                    if ((heardSpeech && !voice) || length == samples.size) {
                                        scope.launch { stopRecognition(session) }
                                        break
                                    }
                                }
                            }
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) {
                            scope.launch {
                                lifecycle.withLock {
                                    if (session == epoch.current() && isRecognizing) {
                                        releaseCapture()
                                        failure(e)
                                    }
                                }
                            }
                        }
                    }
                }
                true
            } catch (e: CancellationException) {
                withContext(NonCancellable) { releaseCapture() }
                epoch.publish(session) {
                    recognitionStateFlow.value = SpeechService.RecognitionState.IDLE
                }
                throw e
            } catch (e: Exception) { releaseCapture(); failure(e); false }
        }

    override suspend fun stopRecognition(): Boolean = stopRecognition(epoch.current())

    private suspend fun stopRecognition(expected: Long): Boolean {
        val captured = lifecycle.withLock {
            if (!isRecognizing || expected != epoch.current()) return false
            val session = epoch.current()
            recognitionStateFlow.value = SpeechService.RecognitionState.PROCESSING
            releaseCapture()
            val pcm = samples.copyOf(length)
            samples = FloatArray(0)
            session to pcm
        }
        return decode(captured.second, captured.first)
    }

    private suspend fun decode(pcm: FloatArray, session: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val text = nativeMutex.withLock {
                if (session != epoch.current() || pcm.isEmpty()) return@withLock ""
                val engine = checkNotNull(recognizer)
                val stream = engine.createStream()
                try {
                    stream.acceptWaveform(pcm,16000)
                    engine.decode(stream)
                    engine.getResult(stream).text
                } finally { stream.release() }
            }
            epoch.publish(session) {
                recognitionStateFlow.value = SpeechService.RecognitionState.IDLE
                recognitionResultFlow.value = SpeechService.RecognitionResult(text, true)
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            epoch.publish(session) { failure(e) }
            false
        }
    }
    override suspend fun cancelRecognition() {
        val session = epoch.begin()
        lifecycle.withLock {
            if (session != epoch.current()) return
            releaseCapture()
            samples = FloatArray(0)
            epoch.publish(session) {
                recognitionResultFlow.value = SpeechService.RecognitionResult("")
                recognitionStateFlow.value = SpeechService.RecognitionState.IDLE
            }
        }
    }
    private suspend fun releaseCapture() {
        runCatching { record?.stop() }
        captureJob?.cancelAndJoin()
        captureJob = null
        record?.release()
        record = null
        effects?.close()
        effects = null
        volumeLevelFlow.value = 0f
        speechActivityFlow.value = false
    }
    private fun failure(e: Exception) {
        AppLogger.e("OnnxSpeech", "Local recognition failed", e)
        recognitionStateFlow.value = SpeechService.RecognitionState.ERROR
        recognitionErrorFlow.value = SpeechService.RecognitionError(-1,e.message.orEmpty())
    }
    override fun shutdown() {
        closed = true
        epoch.begin()
        scope.launch {
            cancelRecognition()
            nativeMutex.withLock { closed = true; recognizer?.release(); recognizer = null }
            isInitialized.value = false
            scope.cancel()
        }
    }
    override suspend fun getSupportedLanguages() =
        if (modelId == "sensevoice-int8") listOf("zh","yue","en","ja","ko") else listOf("zh","en")
    override suspend fun recognize(audioData: FloatArray) {
        if (!isInitialized.value && !initialize()) return
        require(audioData.size <= 16000 * 60) { "Audio exceeds 60 seconds" }
        decode(audioData,epoch.begin())
    }
}
