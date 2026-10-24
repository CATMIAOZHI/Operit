package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.api.chat.llmprovider.ModelConfigConnectionTester
import com.ai.assistance.operit.api.chat.llmprovider.ModelConnectionTestType
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.OrbitModelPreferences
import com.ai.assistance.operit.data.preferences.OrbitModelRef
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The first start of Orbit on a fresh install: the user picks a provider and types one API key,
 * the app checks it, adds the model configuration, starts Orbit with it, and Orbit says hello.
 * Everything else is then set up by talking to Orbit.
 */
internal object OrbitSetup {
    private const val PREFS = "orbit_setup"
    private const val KEY_SKIPPED = "skipped"
    /** First line of the welcome note; it names the turn in the work list. */
    const val MARKER = "[orbit-welcome]"
    private val markerLine = Regex(Regex.escape(MARKER) + "[ \\t]*([^\\n]+)")
    private const val IDLE_WAIT_MS = 5 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun titleOf(content: String): String? =
        markerLine.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    fun skipped(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SKIPPED, false)

    fun skip(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SKIPPED, true).apply()
    }

    /** Providers offered on the setup page, the recommended one first. */
    fun providers(): List<ApiProviderType> =
        listOf(ApiProviderType.DEEPSEEK) + OrbitApprovalActions.providers()
            .mapNotNull { ApiProviderType.fromProviderTypeId(it) }.filter { it != ApiProviderType.DEEPSEEK }

    fun defaultEndpoint(provider: ApiProviderType): String = OrbitApprovalActions.defaultEndpoint(provider)

    fun requiresKey(provider: ApiProviderType, endpoint: String): Boolean = OrbitApprovalActions.requiresKey(provider, endpoint)

    suspend fun fetchModels(context: Context, provider: ApiProviderType, endpoint: String, key: String): List<String> =
        OrbitApprovalActions.fetchModels(context, provider, endpoint, key)

    private val CHEAP = listOf("flash", "mini", "lite", "haiku", "nano", "small", "chat", "turbo")
    private val STRONG = listOf("pro", "reasoner", "opus", "sonnet", "max", "plus", "large")
    private val NOT_CHAT = listOf("embed", "rerank", "tts", "whisper", "audio", "image", "vision-preview", "moderation", "ocr")

    private fun chatModels(models: List<String>) =
        models.filter { m -> NOT_CHAT.none { m.contains(it, ignoreCase = true) } }.ifEmpty { models }

    /** A sensible main model: a strong one the provider offers, else the first chat model. */
    fun suggestMain(models: List<String>): String? {
        val chat = chatModels(models)
        return STRONG.firstNotNullOfOrNull { word -> chat.firstOrNull { it.contains(word, ignoreCase = true) } } ?: chat.firstOrNull()
    }

    /** A sensible assistant model: a cheap, fast one, else the main model. */
    fun suggestAssistant(models: List<String>, main: String?): String? {
        val chat = chatModels(models)
        return CHEAP.firstNotNullOfOrNull { word -> chat.firstOrNull { it.contains(word, ignoreCase = true) && it != main } } ?: main
    }

    /** Why the key or model did not work, for the user, with the key never shown. */
    class SetupException(message: String) : Exception(message)

    private val running = MutableStateFlow(false)

    /** True while [finish] runs; the Orbit page keeps the setup page up until it is done. */
    val inProgress: StateFlow<Boolean> = running.asStateFlow()

    private const val TEST_TIMEOUT_MS = 60_000L

    /**
     * Adds the configuration, checks the main model answers, starts Orbit with it, and has Orbit
     * greet the user. Nothing is left behind when any step fails.
     */
    suspend fun finish(
        context: Context,
        providerName: String,
        provider: ApiProviderType,
        endpoint: String,
        key: String,
        main: String,
        assistant: String,
    ): OrbitProfile = withContext(Dispatchers.IO) {
        check(running.compareAndSet(false, true)) { "Setup is already running" }
        try {
            finishLocked(context.applicationContext, providerName, provider, endpoint, key.trim(), main, assistant)
        } finally {
            running.value = false
        }
    }

    private suspend fun finishLocked(
        app: Context,
        providerName: String,
        provider: ApiProviderType,
        endpoint: String,
        key: String,
        main: String,
        assistant: String,
    ): OrbitProfile {
        fun hide(text: String) = (if (key.length >= 6) text.replace(key, "***") else text).take(200)
        val configs = ModelConfigManager(app)
        configs.initializeIfNeeded()
        val models = listOf(main, assistant).distinct()
        val id = "orbit-setup-" + UUID.randomUUID().toString().take(8)
        configs.createConfig(providerName.take(40), id)
        try {
            val config = configs.updateModelConfig(id, key, endpoint, models.joinToString(","), provider)
            // The request blocks on the network, so a timer cancels it at the limit itself;
            // waiting for the call to return would wait for the much longer network limit.
            val service = java.util.concurrent.atomic.AtomicReference<com.ai.assistance.operit.api.chat.llmprovider.AIService?>()
            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            fun tooSlow() = SetupException(
                if (LocaleUtils.usesEnglishPrompt(app)) "The model did not answer within a minute"
                else "模型一分钟内没有回应")
            val report = try {
                kotlinx.coroutines.coroutineScope {
                    // Runs beside the blocked request: stops it at the limit, and also at once when
                    // the caller is cancelled (the page closed), since the request would not notice.
                    val timer = launch {
                        try {
                            kotlinx.coroutines.delay(TEST_TIMEOUT_MS)
                            timedOut.set(true)
                        } finally {
                            if (!finished.get()) runCatching { service.get()?.cancelStreaming() }
                        }
                    }
                    try {
                        ModelConfigConnectionTester.run(app, configs, config, requestedModelIndex = 0, chatOnly = true,
                            onActiveServiceChanged = { s -> if (s != null) service.set(s) })
                    } finally {
                        finished.set(true)
                        timer.cancel()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stopping the request at the limit surfaces as a cancellation; report it as slow.
                if (timedOut.get()) throw tooSlow()
                throw e
            }
            if (timedOut.get()) throw tooSlow()
            val chat = report.items.firstOrNull { it.type == ModelConnectionTestType.CHAT }
            if (chat == null || !chat.success) throw SetupException(hide(chat?.error.orEmpty()))
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { configs.deleteConfig(id) } }
            throw e
        }
        // Past this point the work must finish even if the page goes away, or Orbit would be left
        // half made. A failure removes the new Orbit and configuration so setup can run again.
        return withContext(NonCancellable) {
            // Only the Orbit this setup made is undone, even if the start fails after saving it.
            var createdId: String? = null
            val started = try {
                val orbit = OrbitCompanion.startFromRoleCard(app, CharacterCardManager.DEFAULT_CHARACTER_CARD_ID,
                    onCreated = { createdId = it })
                OrbitModelPreferences(app).update(orbit.id) {
                    it.copy(main = OrbitModelRef(id, main), assistant = OrbitModelRef(id, assistant))
                }
                orbit
            } catch (e: Throwable) {
                AppLogger.e("OrbitSetup", "Could not start Orbit with the new model", e)
                createdId?.let { runCatching { OrbitCompanion.remove(app, it) } }
                runCatching { configs.deleteConfig(id) }
                throw SetupException(hide(e.message.orEmpty()))
            }
            // The main app gets the key too when it has none yet, so normal mode works right away.
            runCatching {
                val functions = FunctionalConfigManager(app)
                val current = configs.getModelConfig(functions.getConfigIdForFunction(FunctionType.CHAT))
                if (current == null || (current.apiKey.isBlank() && current.apiKeyPool.isEmpty())) {
                    functions.setConfigForFunction(FunctionType.CHAT, id, 0)
                }
            }.onFailure { AppLogger.w("OrbitSetup", "Could not share the new model with the main app: ${it.message}") }
            welcome(app, started)
            started
        }
    }

    private fun welcome(context: Context, orbit: OrbitProfile) {
        scope.launch {
            val english = LocaleUtils.usesEnglishPrompt(context)
            val note = if (english) {
                "$MARKER Welcome\n" +
                    "The user just installed the app, typed an API key, and opened your chat for the first time. " +
                    "This is not a message from the user; they cannot see this note.\n" +
                    "Say hello first, in two or three short sentences: who you are, and that they can just tell you what they want, " +
                    "including setting up the app (models, tool packages, components like the terminal, permissions). " +
                    "Then follow your first-talk guidance. Do not mention hidden notes."
            } else {
                "$MARKER 欢迎\n" +
                    "用户刚装好 App、填好 API 密钥，第一次打开和你的聊天。这不是用户发的消息，用户也看不到这段话。\n" +
                    "先主动打个招呼，两三句短话就好：你是谁；有什么事直接跟你说就行，包括把 App 配置好（模型、工具包、终端这类组件、权限）。" +
                    "然后按你的初次见面指引来。不要提“隐藏消息”这些内部说法。"
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + IDLE_WAIT_MS
            try {
                while (true) {
                    val left = deadline - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    val told = OrbitWake.tell(context, orbit, orbit.chatId, note, left, left, announce = false)
                    if (told != OrbitWake.Told.BUSY) break
                    kotlinx.coroutines.delay(1_500)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitSetup", "Could not send the welcome: ${e.message}")
            }
        }
    }
}
