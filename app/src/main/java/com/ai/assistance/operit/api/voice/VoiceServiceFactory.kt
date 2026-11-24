package com.ai.assistance.operit.api.voice

import android.content.Context
import com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences
import kotlinx.coroutines.runBlocking

/** 语音服务工厂，用于创建不同类型的语音服务实例 */
object VoiceServiceFactory {
    /** 语音服务类型枚举 */
    enum class VoiceServiceType {
        /** 基于Android系统TTS的简单语音实现 */
        SIMPLE_TTS,
        /** 基于HTTP请求的TTS实现 */
        HTTP_TTS,
        /** 基于 OpenAI Realtime WebSocket 的 TTS 实现 */
        OPENAI_WS_TTS,
        /** 硅基流动TTS服务 */
        SILICONFLOW_TTS,
        /** MiniMax TTS 服务 */
        MINIMAX_TTS,
        /** MiMo TTS 服务 */
        MIMO_TTS,
        /** 豆包 TTS 服务 */
        DOUBAO_TTS,
        OPENAI_TTS,
        /** 基于 VITS/Piper ONNX Runtime 推理形态的本地 TTS 服务 */
        VITS_TTS,
        SHERPA_ONNX,
    }

    /**
     * 创建语音服务实例 (现在从Preferences中读取配置)
     *
     * @param context 应用上下文
     * @return 对应类型的VoiceService实例
     */
    fun createVoiceService(
        context: Context
    ): VoiceService {
        val profiles = SpeechServiceProfilesPreferences(context)
        return createVoiceService(context, runBlocking { profiles.getCurrentTtsProfile() })
    }

    fun createVoiceService(
        context: Context,
        profile: SpeechServiceProfilesPreferences.TtsProfile,
    ): VoiceService {
        val provider = when (profile.serviceType) {
                VoiceServiceType.SHERPA_ONNX -> OnnxVoiceProvider(context.applicationContext,
                    profile.localModelId, profile.httpConfig.voiceId.toIntOrNull() ?: 0,
                    profile.speechRate)
                VoiceServiceType.SIMPLE_TTS -> {
                    SimpleVoiceProvider(
                        context = context,
                        initialLocaleTag = profile.httpConfig.localeTag,
                        initialVoiceId = profile.httpConfig.voiceId
                    )
                }
                VoiceServiceType.HTTP_TTS -> {
                    HttpVoiceProvider(context).apply {
                        setConfiguration(profile.httpConfig)
                    }
                }
                VoiceServiceType.OPENAI_WS_TTS -> {
                    OpenAIRealtimeVoiceProvider(
                        context = context,
                        endpointUrl = profile.httpConfig.urlTemplate,
                        apiKey = profile.httpConfig.apiKey,
                        model = profile.httpConfig.modelName,
                        initialVoiceId = profile.httpConfig.voiceId
                    )
                }
                VoiceServiceType.SILICONFLOW_TTS -> {
                    SiliconFlowVoiceProvider(
                        context = context,
                        apiKey = profile.httpConfig.apiKey,
                        initialVoiceId = profile.httpConfig.voiceId,
                        initialModelName = profile.httpConfig.modelName
                    )
                }
                VoiceServiceType.MINIMAX_TTS -> {
                    MiniMaxVoiceProvider(
                        context = context,
                        config = profile.httpConfig
                    )
                }
                VoiceServiceType.MIMO_TTS -> {
                    MimoVoiceProvider(
                        context = context,
                        config = profile.httpConfig,
                        speechRate = profile.speechRate,
                        pitch = profile.pitch,
                    )
                }
                VoiceServiceType.DOUBAO_TTS -> {
                    DoubaoVoiceProvider(
                        context = context,
                        config = profile.httpConfig
                    )
                }
                VoiceServiceType.OPENAI_TTS -> {
                    OpenAIVoiceProvider(
                        context = context,
                        endpointUrl = profile.httpConfig.urlTemplate,
                        apiKey = profile.httpConfig.apiKey,
                        model = profile.httpConfig.modelName,
                        initialVoiceId = profile.httpConfig.voiceId
                    )
                }
                VoiceServiceType.VITS_TTS -> {
                    VitsVoiceProvider(
                        context = context,
                        config = profile.vitsConfig
                    )
                }
            }
        return FocusedVoiceService(context.applicationContext, provider)
    }

    /**
     * A service that always speaks at [profile]'s own speed and pitch, even when it is not the app's
     * current profile (several engines otherwise read the app's). Used for a voice chosen for one Orbit.
     */
    fun createProfileVoiceService(
        context: Context,
        profile: SpeechServiceProfilesPreferences.TtsProfile,
    ): VoiceService = ProfileVoiceService(createVoiceService(context, profile), profile.speechRate, profile.pitch)

    private class ProfileVoiceService(
        private val delegate: VoiceService,
        private val rate: Float,
        private val pitch: Float,
    ) : VoiceService by delegate {
        override suspend fun speak(text: String, interrupt: Boolean, rate: Float?, pitch: Float?,
            extraParams: Map<String, String>): Boolean =
            delegate.speak(text, interrupt, rate ?: this.rate, pitch ?: this.pitch, extraParams)

        override suspend fun synthesizeToFile(text: String, out: java.io.File): Boolean =
            delegate.synthesizeToFile(text, out, rate, pitch)

        override suspend fun synthesizeToFile(text: String, out: java.io.File, rate: Float?, pitch: Float?): Boolean =
            delegate.synthesizeToFile(text, out, rate ?: this.rate, pitch ?: this.pitch)
    }

    // 单例实例缓存（App 的朗读配置）
    private var instance: VoiceService? = null
    private var currentProfile: SpeechServiceProfilesPreferences.TtsProfile? = null

    // 某个 Orbit 自己选的声音单独一个实例，App 那边换配置或关悬浮窗都不会把它关掉
    private var ownInstance: VoiceService? = null
    private var ownProfile: SpeechServiceProfilesPreferences.TtsProfile? = null

    /**
     * 获取语音服务单例实例（App 当前的朗读配置）
     *
     * @param context 应用上下文
     * @return VoiceService实例
     */
    @Synchronized
    fun getInstance(context: Context): VoiceService {
        val profiles = SpeechServiceProfilesPreferences(context)
        val profile = runBlocking { profiles.getCurrentTtsProfile() }

        if (instance == null || profile != currentProfile) {
            instance?.shutdown()
            instance = null
            currentProfile = null
            instance = createVoiceService(context, profile)
            currentProfile = profile
        }
        return instance!!
    }

    /**
     * The service for a voice one Orbit uses instead of the app's ([profile]), kept apart from the
     * app's so neither replaces the other. Only one such voice is kept; another replaces it.
     */
    @Synchronized
    fun getOwnInstance(context: Context, profile: SpeechServiceProfilesPreferences.TtsProfile): VoiceService {
        if (ownInstance == null || profile != ownProfile) {
            ownInstance?.shutdown()
            // Cleared first: if making the new one fails, no closed service is left to be handed out.
            ownInstance = null
            ownProfile = null
            ownInstance = createProfileVoiceService(context, profile)
            ownProfile = profile
        }
        return ownInstance!!
    }

    /** Shuts down [service] if it is the Orbit voice kept here, and forgets it so the next use makes a new one. */
    @Synchronized
    fun releaseOwnInstance(service: VoiceService) {
        if (ownInstance !== service) return
        ownInstance?.shutdown()
        ownInstance = null
        ownProfile = null
    }

    /** Stops whatever is being read aloud, the app's voice and an Orbit's own, without starting anything. */
    suspend fun stopAll() {
        val services = synchronized(this) { listOfNotNull(instance, ownInstance) }
        services.forEach { runCatching { it.stop() } }
    }

    /** 重置单例实例 在需要更改语音服务类型或释放资源时调用 */
    @Synchronized
    fun resetInstance() {
        instance?.shutdown()
        instance = null
        currentProfile = null
    }
}
