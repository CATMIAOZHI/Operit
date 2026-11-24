package com.ai.assistance.operit.api.voice

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.SpeechServicesPreferences
import com.ai.assistance.operit.util.AppLogger
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 基于Android系统TextToSpeech API的语音服务实现
 *
 * 此实现利用Android的TextToSpeech API进行文本转语音
 */
class SimpleVoiceProvider(
    private val context: Context,
    initialLocaleTag: String = "",
    initialVoiceId: String = ""
) : VoiceService {
    companion object {
        private const val TAG = "SimpleVoiceProvider"
        private const val SPEECH_PREVIEW_MAX = 48
        /** Reported to a waiting start when its engine was released before it came up. */
        private const val RELEASED = Int.MIN_VALUE
    }

    private fun speechPreview(text: String): String {
        return text.replace("\n", "\\n").take(SPEECH_PREVIEW_MAX)
    }

    private data class PendingUtterance(
        val utteranceId: String,
        val text: String,
        val completion: CompletableDeferred<Boolean> = CompletableDeferred()
    )

    // TextToSpeech引擎实例
    /** Set and cleared under [startLock]. */
    @Volatile private var tts: TextToSpeech? = null
    /**
     * Starting the engine is one at a time: the chat, the floating window (whose speak starts it
     * lazily) and settings may all ask at once, and each start would build its own TextToSpeech.
     */
    private val initMutex = kotlinx.coroutines.sync.Mutex()
    /** Set by [shutdown]: this provider is retired and never starts an engine again. */
    @Volatile private var closed = false
    /**
     * The start still waiting for its engine's callback. Releasing the engine ends that wait at once,
     * since the callback may never come after the engine is shut down. Cleared when the start ends.
     */
    private var pendingStart: CompletableDeferred<Int>? = null
    private val startLock = Any()

    // 初始化状态
    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: Boolean
        get() = _isInitialized.value

    // 播放状态
    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: Boolean
        get() = _isSpeaking.value

    // 播放状态Flow
    override val speakingStateFlow: Flow<Boolean> = _isSpeaking.asStateFlow()

    // 当前语音参数
    private var currentRate: Float = 1.0f
    private var currentPitch: Float = 1.0f
    private var currentVoiceId: String? = initialVoiceId.takeIf { it.isNotBlank() }
    private var currentLocaleTag: String = initialLocaleTag.trim()

    private val queueLock = Any()
    private val queuedUtterances = ArrayDeque<PendingUtterance>()
    private var currentUtteranceId: String? = null
    private var currentUtteranceText: String = ""
    private var currentUtteranceRangeStart: Int = 0
    private var pausedSegments: List<PendingUtterance> = emptyList()
    private var isPausedInternally: Boolean = false

    private fun logQueueState(event: String, extra: String = "") {
        val suffix = if (extra.isNotBlank()) " $extra" else ""
        AppLogger.d(
            TAG,
            "queue[$event] initialized=${_isInitialized.value} speaking=${_isSpeaking.value} size=${queuedUtterances.size} current=$currentUtteranceId rangeStart=$currentUtteranceRangeStart paused=$isPausedInternally pausedSegments=${pausedSegments.size}$suffix"
        )
    }

    private fun updateSpeakingStateLocked(event: String) {
        _isSpeaking.value = queuedUtterances.isNotEmpty()
        logQueueState(event)
    }

    private fun removeQueuedUtteranceLocked(utteranceId: String): PendingUtterance? {
        val iterator = queuedUtterances.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.utteranceId == utteranceId) {
                iterator.remove()
                return entry
            }
        }
        return null
    }

    private fun buildPausedSegmentsLocked(): List<PendingUtterance> {
        if (queuedUtterances.isEmpty()) return emptyList()

        val currentId = currentUtteranceId
        if (currentId == null) {
            return queuedUtterances.filter { it.text.isNotBlank() }
        }

        val result = mutableListOf<PendingUtterance>()
        var foundCurrent = false
        queuedUtterances.forEach { entry ->
            if (!foundCurrent && entry.utteranceId == currentId) {
                foundCurrent = true
                val safeStart = currentUtteranceRangeStart.coerceIn(0, entry.text.length)
                val remaining = entry.text.substring(safeStart).trimStart()
                if (remaining.isNotBlank()) {
                    result += entry.copy(text = remaining)
                } else entry.completion.complete(true)
            } else if (foundCurrent && entry.text.isNotBlank()) {
                result += entry
            }
        }

        if (!foundCurrent) {
            return queuedUtterances.filter { it.text.isNotBlank() }
        }
        return result
    }

    private fun submitUtterance(
        textToSpeech: TextToSpeech,
        entry: PendingUtterance,
        queueMode: Int
    ): Boolean {
        val params = HashMap<String, String>()
        params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = entry.utteranceId

        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech.speak(entry.text, queueMode, null, entry.utteranceId)
            } else {
                @Suppress("DEPRECATION")
                textToSpeech.speak(entry.text, queueMode, params)
            }
        return result == TextToSpeech.SUCCESS
    }

    /** 初始化TTS引擎 */
    override suspend fun initialize(): Boolean {
        if (closed) return false
        return initMutex.withLock {
            if (closed) return@withLock false
            try {
                startEngine() && !closed
            } finally {
                // Retired while it was starting (however the start ended): nobody owns this engine any more.
                if (closed) releaseEngine()
            }
        }
    }

    /**
     * Builds one engine. Its callback only reports how the start went (it may come before the
     * constructor returns, or never once the engine is shut down); this start then sets the engine
     * up, if it still owns it.
     */
    private suspend fun startEngine(): Boolean =
        withContext(Dispatchers.IO) {
            if (_isInitialized.value && tts != null) return@withContext true
            val result = CompletableDeferred<Int>()
            val engine = TextToSpeech(context) { status -> result.complete(status) }
            val owned = synchronized(startLock) {
                if (closed) false else { pendingStart = result; tts = engine; true }
            }
            if (!owned) {
                engine.shutdown()
                return@withContext false
            }
            val status = try {
                result.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A cancelled start only drops its own engine; the provider can start again.
                if (synchronized(startLock) { tts === engine }) releaseEngine()
                throw e
            } finally {
                synchronized(startLock) { if (pendingStart === result) pendingStart = null }
            }
            if (status == RELEASED) return@withContext false
            if (status != TextToSpeech.SUCCESS) {
                AppLogger.e(TAG, "TTS初始化失败: $status")
                if (synchronized(startLock) { tts === engine }) releaseEngine()
                throw TtsException(context.getString(R.string.accessibility_tts_init_failed, status))
            }
            engine.setSpeechRate(currentRate)
            engine.setPitch(currentPitch)
            AppLogger.d(
                TAG,
                "initialize success locale=$currentLocaleTag voice=$currentVoiceId rate=$currentRate pitch=$currentPitch"
            )
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        synchronized(queueLock) {
                            val entry =
                                queuedUtterances.firstOrNull {
                                    it.utteranceId == utteranceId
                                }
                            if (entry == null) {
                                AppLogger.w(
                                    TAG,
                                    "listener onStart ignored stale utteranceId=$utteranceId"
                                )
                                return
                            }
                            currentUtteranceId = utteranceId
                            currentUtteranceText = entry.text
                            currentUtteranceRangeStart = 0
                            _isSpeaking.value = true
                            logQueueState(
                                event = "listener.onStart",
                                extra = "utteranceId=$utteranceId len=${entry.text.length}"
                            )
                        }
                    }

                    override fun onDone(utteranceId: String) {
                        synchronized(queueLock) {
                            val removed =
                                removeQueuedUtteranceLocked(utteranceId)?.also { it.completion.complete(true) }
                            if (currentUtteranceId == utteranceId) {
                                currentUtteranceId = null
                                currentUtteranceText = ""
                                currentUtteranceRangeStart = 0
                            }
                            updateSpeakingStateLocked("listener.onDone")
                            AppLogger.d(
                                TAG,
                                "listener onDone utteranceId=$utteranceId removed=${removed != null}"
                            )
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String) {
                        synchronized(queueLock) {
                            removeQueuedUtteranceLocked(utteranceId)?.completion?.complete(false)
                            if (currentUtteranceId == utteranceId) {
                                currentUtteranceId = null
                                currentUtteranceText = ""
                                currentUtteranceRangeStart = 0
                            }
                            updateSpeakingStateLocked("listener.onError")
                        }
                        AppLogger.e(
                            TAG,
                            "listener onError utteranceId=$utteranceId"
                        )
                    }

                    override fun onError(
                        utteranceId: String,
                        errorCode: Int
                    ) {
                        super.onError(utteranceId, errorCode)
                        synchronized(queueLock) {
                            removeQueuedUtteranceLocked(utteranceId)?.completion?.complete(false)
                            if (currentUtteranceId == utteranceId) {
                                currentUtteranceId = null
                                currentUtteranceText = ""
                                currentUtteranceRangeStart = 0
                            }
                            updateSpeakingStateLocked("listener.onErrorCode")
                        }
                        AppLogger.e(
                            TAG,
                            "TTS错误: utteranceId=$utteranceId, errorCode=$errorCode"
                        )
                    }

                    override fun onRangeStart(
                        utteranceId: String,
                        start: Int,
                        end: Int,
                        frame: Int
                    ) {
                        synchronized(queueLock) {
                            if (utteranceId == currentUtteranceId) {
                                currentUtteranceRangeStart =
                                    start.coerceIn(
                                        0,
                                        currentUtteranceText.length
                                    )
                            }
                            logQueueState(
                                event = "listener.onRangeStart",
                                extra = "utteranceId=$utteranceId start=$start end=$end frame=$frame"
                            )
                        }
                    }
                }
            )

            // Released while it was being set up: report no engine.
            synchronized(startLock) {
                if (tts !== engine || closed) return@withContext false
                _isInitialized.value = true
            }
            true
        }

    /** 将文本转换为语音并播放 */
    override suspend fun speak(
        text: String,
        interrupt: Boolean,
        rate: Float?,
        pitch: Float?,
        extraParams: Map<String, String>
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (!isInitialized) {
                val initResult = initialize()
                if (!initResult) {
                    return@withContext false
                }
            }

            val profile = com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences(context.applicationContext).getCurrentTtsProfile()
            val effectiveRate = rate ?: profile.speechRate
            val effectivePitch = pitch ?: profile.pitch
            val queueSizeBefore = synchronized(queueLock) { queuedUtterances.size }
            AppLogger.d(
                TAG,
                "speak request interrupt=$interrupt len=${text.length} preview=\"${speechPreview(text)}\" rate=$effectiveRate pitch=$effectivePitch voice=$currentVoiceId locale=$currentLocaleTag initialized=$isInitialized speaking=$isSpeaking queueSize=$queueSizeBefore paused=$isPausedInternally"
            )

            val engine = tts ?: return@withContext false
            ensureVoiceAndLocaleReady(engine)
            val entry = PendingUtterance(UUID.randomUUID().toString(), text)
            synchronized(queueLock) {
                if (interrupt) {
                    clearWaitingLocked()
                    isPausedInternally = false
                    currentUtteranceId = null
                }
                engine.setSpeechRate(effectiveRate)
                engine.setPitch(effectivePitch)
                currentRate = effectiveRate
                currentPitch = effectivePitch
                if (isPausedInternally) pausedSegments = pausedSegments + entry
                else {
                    queuedUtterances.addLast(entry)
                    if (!submitUtterance(engine, entry,
                            if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD)) {
                        removeQueuedUtteranceLocked(entry.utteranceId)
                        entry.completion.complete(false)
                    }
                    updateSpeakingStateLocked("speak.submitted")
                }
            }
            entry.completion.await()
        }

    private fun clearWaitingLocked() {
        queuedUtterances.forEach { it.completion.complete(false) }
        pausedSegments.forEach { it.completion.complete(false) }
        queuedUtterances.clear()
        pausedSegments = emptyList()
    }

    /** 停止当前正在播放的语音 */
    override suspend fun stop(): Boolean =
        withContext(Dispatchers.IO) {
            if (!isInitialized) return@withContext false

            AppLogger.d(TAG, "stop request speaking=$isSpeaking")
            tts?.let { textToSpeech ->
                synchronized(queueLock) {
                    clearWaitingLocked()
                    isPausedInternally = false
                    currentUtteranceId = null
                    currentUtteranceText = ""
                    currentUtteranceRangeStart = 0
                    _isSpeaking.value = false
                    logQueueState("stop.prepare")
                }
                val result = textToSpeech.stop() == TextToSpeech.SUCCESS
                synchronized(queueLock) {
                    _isSpeaking.value = false
                    logQueueState("stop.done", "result=$result")
                }
                return@withContext result
            }
            return@withContext false
        }

    /** 暂停当前正在播放的语音 */
    override suspend fun pause(): Boolean =
        withContext(Dispatchers.IO) {
            if (!isInitialized) return@withContext false

            AppLogger.d(TAG, "pause request speaking=$isSpeaking")
            tts?.let { textToSpeech ->
                val segmentsToResume =
                    synchronized(queueLock) {
                        val captured =
                            if (isPausedInternally && pausedSegments.isNotEmpty()) {
                                pausedSegments
                            } else {
                                buildPausedSegmentsLocked()
                            }
                        if (captured.isEmpty()) {
                            logQueueState("pause.skip", "reason=noPendingSegments")
                            return@synchronized emptyList()
                        }
                        pausedSegments = captured
                        isPausedInternally = true
                        queuedUtterances.clear()
                        currentUtteranceId = null
                        currentUtteranceText = ""
                        currentUtteranceRangeStart = 0
                        _isSpeaking.value = false
                        logQueueState(
                            "pause.capture",
                            "lengths=${captured.joinToString(prefix = "[", postfix = "]") { it.text.length.toString() }}"
                        )
                        captured
                    }
                if (segmentsToResume.isEmpty()) {
                    return@withContext false
                }

                val result = textToSpeech.stop() == TextToSpeech.SUCCESS
                synchronized(queueLock) {
                    if (!result) {
                        isPausedInternally = false
                        clearWaitingLocked()
                    }
                    _isSpeaking.value = false
                    logQueueState(
                        "pause.done",
                        "result=$result resumeSegments=${segmentsToResume.size}"
                    )
                }
                return@withContext result
            }
            return@withContext false
        }

    /** 继续播放暂停的语音 */
    override suspend fun resume(): Boolean =
        withContext(Dispatchers.IO) {
            if (!isInitialized) return@withContext false

            val segmentsToResume =
                synchronized(queueLock) {
                    if (pausedSegments.isEmpty()) {
                        logQueueState("resume.skip", "reason=noPausedSegments")
                        return@synchronized emptyList()
                    }
                    val captured = pausedSegments
                    pausedSegments = emptyList()
                    isPausedInternally = false
                    captured
                }
            if (segmentsToResume.isEmpty()) {
                AppLogger.d(
                    TAG,
                    "resume request initialized=$isInitialized speaking=$isSpeaking result=false reason=noPausedSegments"
                )
                return@withContext false
            }

            AppLogger.d(
                TAG,
                "resume request initialized=$isInitialized speaking=$isSpeaking segments=${segmentsToResume.size} lengths=${segmentsToResume.joinToString(prefix = "[", postfix = "]") { it.text.length.toString() }}"
            )

            val textToSpeech = tts ?: run {
                segmentsToResume.forEach { it.completion.complete(false) }
                return@withContext false
            }
            ensureVoiceAndLocaleReady(textToSpeech)

            val success =
                synchronized(queueLock) {
                    var failed = false
                    segmentsToResume.forEachIndexed { index, segment ->
                        val entry =
                            segment.copy(utteranceId = UUID.randomUUID().toString())
                        queuedUtterances.addLast(entry)
                        val submitSuccess =
                            submitUtterance(
                                textToSpeech = textToSpeech,
                                entry = entry,
                                queueMode =
                                    if (index == 0) {
                                        TextToSpeech.QUEUE_FLUSH
                                    } else {
                                        TextToSpeech.QUEUE_ADD
                                    }
                            )
                        if (!submitSuccess) {
                            failed = true
                        }
                    }

                    if (failed) {
                        clearWaitingLocked()
                        currentUtteranceId = null
                        currentUtteranceText = ""
                        currentUtteranceRangeStart = 0
                        _isSpeaking.value = false
                        logQueueState("resume.submitFailed")
                        false
                    } else {
                        _isSpeaking.value = queuedUtterances.isNotEmpty()
                        logQueueState("resume.submitted")
                        true
                    }
                }

            if (!success) {
                textToSpeech.stop()
            }
            return@withContext success
        }

    /** 释放TTS引擎资源 */
    override fun shutdown() {
        closed = true
        releaseEngine()
    }

    private fun releaseEngine() {
        AppLogger.d(TAG, "shutdown request initialized=$isInitialized speaking=$isSpeaking")
        // A start still waiting ends now, reporting no engine; the engine is taken in the same step.
        val engine = synchronized(startLock) {
            pendingStart?.complete(RELEASED)
            pendingStart = null
            _isInitialized.value = false
            tts.also { tts = null }
        }
        engine?.let {
            try {
                it.stop()
                it.shutdown()
            } catch (e: Exception) {
                AppLogger.e(TAG, "关闭TTS引擎失败", e)
            } finally {
                synchronized(queueLock) {
                    clearWaitingLocked()
                    isPausedInternally = false
                    currentUtteranceId = null
                    currentUtteranceText = ""
                    currentUtteranceRangeStart = 0
                }
                _isSpeaking.value = false
            }
        }
    }

    /** 获取可用的语音列表 */
    override suspend fun getAvailableVoices(): List<VoiceService.Voice> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<VoiceService.Voice>()

            if (!isInitialized) {
                val initResult = initialize()
                if (!initResult) {
                    return@withContext emptyList<VoiceService.Voice>()
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.let { textToSpeech ->
                    val voices = textToSpeech.voices
                    if (voices != null) {
                        for (voice in voices) {
                            val gender =
                                when {
                                    voice.name.contains("female", ignoreCase = true) -> "FEMALE"
                                    voice.name.contains("male", ignoreCase = true) -> "MALE"
                                    else -> "NEUTRAL"
                                }

                            result.add(
                                VoiceService.Voice(
                                    id = voice.name,
                                    name = voice.name,
                                    locale = voice.locale.toLanguageTag(),
                                    gender = gender
                                )
                            )
                        }
                    }
                }
            } else {
                AppLogger.w(TAG, "当前Android版本不支持获取TTS语音列表")
            }

            return@withContext result
        }

    /** 设置当前使用的语音 */
    override suspend fun setVoice(voiceId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isInitialized) {
                val initResult = initialize()
                if (!initResult) {
                    return@withContext false
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.let { textToSpeech ->
                    val voices = textToSpeech.voices
                    if (voices != null) {
                        val voice = voices.find { it.name == voiceId }
                        if (voice != null) {
                            val result = textToSpeech.setVoice(voice) == TextToSpeech.SUCCESS
                            if (result) {
                                currentVoiceId = voiceId
                                currentLocaleTag = voice.locale.toLanguageTag()
                            }
                            return@withContext result
                        } else {
                            AppLogger.e(TAG, "未找到ID为'$voiceId'的语音")
                            return@withContext false
                        }
                    }
                }
            } else {
                AppLogger.w(TAG, "当前Android版本不支持设置TTS语音")
            }

            return@withContext false
        }

    private fun ensureVoiceAndLocaleReady(textToSpeech: TextToSpeech) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !currentVoiceId.isNullOrBlank()) {
            val voices = textToSpeech.voices
            val selectedVoice = voices?.firstOrNull { it.name == currentVoiceId }
            if (selectedVoice != null) {
                val result = textToSpeech.setVoice(selectedVoice)
                if (result == TextToSpeech.SUCCESS) {
                    currentLocaleTag = selectedVoice.locale.toLanguageTag()
                    return
                }
            }
        }

        val requestedTag = currentLocaleTag.ifBlank { Locale.getDefault().toLanguageTag() }
        val targetLocale = resolveBestLocale(textToSpeech, requestedTag)
        if (targetLocale == null) {
            throw TtsException(
                context.getString(R.string.accessibility_tts_locale_not_supported, requestedTag)
            )
        }

        val result = textToSpeech.setLanguage(targetLocale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw TtsException(
                context.getString(R.string.accessibility_tts_locale_not_supported, requestedTag)
            )
        }

        currentLocaleTag = targetLocale.toLanguageTag()
    }

    private fun resolveBestLocale(textToSpeech: TextToSpeech, requestedTag: String): Locale? {
        val normalizedTag = requestedTag.trim().replace('_', '-')
        if (normalizedTag.isBlank()) {
            return Locale.getDefault()
        }

        val requestedLocale = Locale.forLanguageTag(normalizedTag)
        val availableLocales = linkedSetOf<Locale>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech.voices
                ?.mapNotNull { it.locale }
                ?.forEach { availableLocales.add(it) }
        }

        textToSpeech.availableLanguages
            ?.forEach { availableLocales.add(it) }

        availableLocales.firstOrNull {
            it.toLanguageTag().equals(normalizedTag, ignoreCase = true)
        }?.let {
            return it
        }

        val requestedLanguage = requestedLocale.language
        if (requestedLanguage.isNotBlank()) {
            availableLocales.firstOrNull {
                it.language.equals(requestedLanguage, ignoreCase = true)
            }?.let {
                return it
            }
        }

        return if (textToSpeech.isLanguageAvailable(requestedLocale) >= TextToSpeech.LANG_AVAILABLE) {
            requestedLocale
        } else {
            null
        }
    }
}
