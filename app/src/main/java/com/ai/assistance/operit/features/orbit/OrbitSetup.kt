package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.api.chat.llmprovider.ModelConfigConnectionTester
import com.ai.assistance.operit.api.chat.llmprovider.ModelConnectionTestType
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.multimodalCapabilitiesForModel
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.OrbitModelPreferences
import com.ai.assistance.operit.data.preferences.OrbitModelRef
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.resolveModelIndex
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
    /** Setup that started making a configuration and Orbit but has not bound them yet. */
    private const val KEY_PENDING_CONFIG = "pending_config"
    private const val KEY_PENDING_ORBIT = "pending_orbit"
    /** The Orbit whose welcome has not been delivered yet. */
    private const val KEY_WELCOME = "welcome_pending"
    /** When the welcome last went to Orbit; its saved reply after this time settles it. */
    private const val KEY_WELCOME_SENT = "welcome_sent_at"
    /** How long Orbit's reply to the welcome is waited for before it may be sent again. */
    private const val REPLY_WAIT_MS = 10 * 60 * 1000L
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

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Providers offered on the setup page, the recommended one first. */
    fun providers(): List<ApiProviderType> =
        listOf(ApiProviderType.DEEPSEEK) + OrbitApprovalActions.providers()
            .mapNotNull { ApiProviderType.fromProviderTypeId(it) }.filter { it != ApiProviderType.DEEPSEEK }

    fun defaultEndpoint(provider: ApiProviderType): String = OrbitApprovalActions.defaultEndpoint(provider)

    fun requiresKey(provider: ApiProviderType, endpoint: String): Boolean = OrbitApprovalActions.requiresKey(provider, endpoint)

    suspend fun fetchModels(context: Context, provider: ApiProviderType, endpoint: String, key: String): List<String> =
        OrbitApprovalActions.fetchModels(context, provider, endpoint, key)

    /** The recommended model for both roles: a fast, capable "flash" model, DeepSeek's first. */
    private val RECOMMENDED = listOf("v4.1-flash", "deepseek-flash", "flash")
    private val CHEAP = listOf("mini", "lite", "haiku", "nano", "small", "chat", "turbo")
    private val STRONG = listOf("pro", "reasoner", "opus", "sonnet", "max", "plus", "large")
    private val NOT_CHAT = listOf("embed", "rerank", "tts", "whisper", "audio", "image", "vision-preview", "moderation", "ocr")

    private fun chatModels(models: List<String>) =
        models.filter { m -> NOT_CHAT.none { m.contains(it, ignoreCase = true) } }.ifEmpty { models }

    private fun recommended(chat: List<String>): String? =
        RECOMMENDED.firstNotNullOfOrNull { word -> chat.firstOrNull { it.contains(word, ignoreCase = true) } }

    /** A sensible main model: the recommended flash model, else a strong one, else the first chat model. */
    fun suggestMain(models: List<String>): String? {
        val chat = chatModels(models)
        return recommended(chat)
            ?: STRONG.firstNotNullOfOrNull { word -> chat.firstOrNull { it.contains(word, ignoreCase = true) } }
            ?: chat.firstOrNull()
    }

    /** A sensible assistant model: the recommended flash model (it may be the main one too), else a cheap one, else the main model. */
    fun suggestAssistant(models: List<String>, main: String?): String? {
        val chat = chatModels(models)
        return recommended(chat)
            ?: CHEAP.firstNotNullOfOrNull { word -> chat.firstOrNull { it.contains(word, ignoreCase = true) && it != main } }
            ?: main
    }

    /** What went wrong, in words the setup page can explain with a next step. */
    enum class Problem { KEY, MONEY, MODEL, RATE, NETWORK, SLOW, OTHER }

    /**
     * Why the key or model did not work, with the key never shown. [detail] is the provider's own
     * text, shown only on request; [model] names the helper model when that one failed.
     */
    class SetupException(val problem: Problem, val detail: String, val model: String? = null) : Exception(detail)

    fun problemOf(raw: String): Problem {
        val text = raw.lowercase()
        fun has(vararg words: String) = words.any { text.contains(it) }
        return when {
            has("401", "403", "unauthorized", "forbidden", "invalid api key", "invalid_api_key", "incorrect api key",
                "authentication", "api key", "apikey", "permission") -> Problem.KEY
            has("402", "insufficient", "balance", "quota", "billing", "credit", "余额") -> Problem.MONEY
            has("404", "model_not_found", "model not found", "does not exist", "not exist", "unknown model",
                "no such model", "invalid model", "not supported") -> Problem.MODEL
            has("429", "rate limit", "rate_limit", "too many requests") -> Problem.RATE
            has("timeout", "timed out", "unknownhost", "unable to resolve", "failed to connect", "connection",
                "network", "ssl", "eof", "unreachable") -> Problem.NETWORK
            else -> Problem.OTHER
        }
    }

    /** The plain-language reason and next step for [problem]. */
    fun describe(context: Context, problem: Problem): String = context.getString(when (problem) {
        Problem.KEY -> com.ai.assistance.operit.R.string.orbit_setup_problem_key
        Problem.MONEY -> com.ai.assistance.operit.R.string.orbit_setup_problem_money
        Problem.MODEL -> com.ai.assistance.operit.R.string.orbit_setup_problem_model
        Problem.RATE -> com.ai.assistance.operit.R.string.orbit_setup_problem_rate
        Problem.NETWORK -> com.ai.assistance.operit.R.string.orbit_setup_problem_network
        Problem.SLOW -> com.ai.assistance.operit.R.string.orbit_setup_problem_slow
        Problem.OTHER -> com.ai.assistance.operit.R.string.orbit_setup_problem_other
    })

    /** The main app's chat and helper models when they are ready to use, shown as "configuration / model". */
    data class Existing(val main: String, val assistant: String)

    private fun ready(config: com.ai.assistance.operit.data.model.ModelConfigData): Boolean {
        val provider = ApiProviderType.fromProviderTypeId(config.apiProviderTypeId) ?: config.apiProviderType
        return hasKey(config) || provider in OrbitApprovalActions.ACCOUNT_PROVIDERS ||
            !com.ai.assistance.operit.data.collects.ApiProviderConfigs.requiresApiKey(config.apiProviderTypeId, config.apiEndpoint)
    }

    /** Whether a request would get a key, the way the key providers pick one. */
    private fun hasKey(config: com.ai.assistance.operit.data.model.ModelConfigData): Boolean {
        if (!config.useMultipleApiKeys) return config.apiKey.isNotBlank()
        val enabled = config.apiKeyPool.filter { it.isEnabled }
        val marked = enabled.any { it.availabilityStatus != com.ai.assistance.operit.data.model.ApiKeyAvailabilityStatus.UNTESTED }
        if (marked) return enabled.any { it.availabilityStatus == com.ai.assistance.operit.data.model.ApiKeyAvailabilityStatus.AVAILABLE }
        return enabled.isNotEmpty() || config.apiKey.isNotBlank()
    }

    /** The model a main-app function uses, as "configuration / model", when it can answer now. */
    private suspend fun usable(app: Context, configs: ModelConfigManager, functions: FunctionalConfigManager,
        function: FunctionType): String? {
        val mapping = functions.getEffectiveConfigMappingForFunction(function)
        val config = configs.getModelConfig(mapping.configId)?.takeIf(::ready) ?: return null
        val index = mapping.resolveModelIndex(config.modelName).modelIndex
        val model = com.ai.assistance.operit.data.model.getModelList(config.modelName).getOrNull(index)
        val summary = configs.getAllConfigSummaries().firstOrNull { it.id == config.id } ?: return null
        OrbitModelRef.fromSelection(summary, index) ?: return null
        return listOfNotNull(config.name.takeIf { it.isNotBlank() }, model?.takeIf { it.isNotBlank() })
            .joinToString(" / ").ifBlank { null }
    }

    /** The main app's models Orbit would start with, or null when its chat model is not set up yet. */
    suspend fun existing(context: Context): Existing? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        runCatching {
            val configs = ModelConfigManager(app)
            configs.initializeIfNeeded()
            val functions = FunctionalConfigManager(app)
            val main = usable(app, configs, functions, FunctionType.CHAT) ?: return@runCatching null
            Existing(main, usable(app, configs, functions, FunctionType.MEMORY) ?: main)
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
    }

    /** Starts Orbit with the models the main app already uses; Orbit then greets the user. */
    suspend fun startWithExisting(context: Context): OrbitProfile = withContext(Dispatchers.IO) {
        check(running.compareAndSet(false, true)) { "Setup is already running" }
        val app = context.applicationContext
        try {
            withContext(NonCancellable) {
                prefs(app).edit().remove(KEY_PENDING_CONFIG).putString(KEY_PENDING_ORBIT, "").commit()
                var createdId: String? = null
                try {
                    val orbit = OrbitCompanion.start(app, onCreated = {
                        createdId = it
                        prefs(app).edit().putString(KEY_PENDING_ORBIT, it).commit()
                    })
                    // The first read copies the main app's chat, helper and picture models. A helper
                    // model that cannot answer is replaced by the main one, as the setup page showed.
                    val models = OrbitModelPreferences(app)
                    models.current(orbit.id)
                    val configs = ModelConfigManager(app)
                    if (usable(app, configs, FunctionalConfigManager(app), FunctionType.MEMORY) == null) {
                        models.update(orbit.id) { it.copy(assistant = it.main) }
                    }
                    prefs(app).edit().remove(KEY_PENDING_ORBIT).remove(KEY_WELCOME_SENT).putString(KEY_WELCOME, orbit.id).commit()
                    welcome(app, orbit)
                    orbit
                } catch (e: Throwable) {
                    AppLogger.e("OrbitSetup", "Could not start Orbit with the existing models", e)
                    createdId?.let { runCatching { OrbitCompanion.remove(app, it) } }
                    prefs(app).edit().remove(KEY_PENDING_ORBIT).commit()
                    throw SetupException(Problem.OTHER, e.message.orEmpty().take(200))
                }
            }
        } finally {
            running.value = false
        }
    }

    /** True when an earlier setup stopped halfway (the app was closed) and needs undoing first. */
    fun interrupted(context: Context): Boolean {
        val p = prefs(context)
        return !running.value && (p.contains(KEY_PENDING_CONFIG) || p.contains(KEY_PENDING_ORBIT))
    }

    /**
     * Undoes a setup that stopped halfway: the Orbit and configuration it made are removed, so the
     * setup page can run again cleanly. An Orbit whose models were already bound is never touched.
     */
    suspend fun recoverInterrupted(context: Context) = withContext(Dispatchers.IO + NonCancellable) {
        if (!running.compareAndSet(false, true)) return@withContext
        val app = context.applicationContext
        try {
            val p = prefs(app)
            val orbitId = p.getString(KEY_PENDING_ORBIT, null)?.takeIf { it.isNotBlank() }
            val configId = p.getString(KEY_PENDING_CONFIG, null)?.takeIf { it.isNotBlank() }
            if (orbitId != null && OrbitPreferences.getInstance(app).current().orbits.any { it.id == orbitId }) {
                runCatching { OrbitCompanion.remove(app, orbitId) }
                    .onFailure { AppLogger.w("OrbitSetup", "Could not remove the half-made Orbit: ${it.message}") }
            }
            if (configId != null) runCatching { ModelConfigManager(app).deleteConfig(configId) }
            p.edit().remove(KEY_PENDING_CONFIG).remove(KEY_PENDING_ORBIT).commit()
        } finally {
            running.value = false
        }
    }

    /** Sends the welcome again when an earlier one never reached Orbit (the app closed, or the chat stayed busy). */
    fun resumeWelcome(context: Context) {
        val app = context.applicationContext
        val orbitId = prefs(app).getString(KEY_WELCOME, null) ?: return
        if (running.value) return
        scope.launch {
            val orbit = OrbitPreferences.getInstance(app).current().orbits.firstOrNull { it.id == orbitId && !it.deleting }
            if (orbit == null) {
                prefs(app).edit().remove(KEY_WELCOME).remove(KEY_WELCOME_SENT).apply()
                return@launch
            }
            welcome(app, orbit)
        }
    }

    private val running = MutableStateFlow(false)

    private val finishing = MutableStateFlow(false)

    /**
     * True while [finish] runs; the Orbit page keeps the setup page up until it is done. Starting
     * with the existing models does not hold it, so starting from the Orbit list stays on the list.
     */
    val inProgress: StateFlow<Boolean> = finishing.asStateFlow()

    private const val TEST_TIMEOUT_MS = 60_000L
    /** "high" in low, medium, high, xhigh, max. */
    private val THINKING_HIGH =
        com.ai.assistance.operit.data.preferences.ApiPreferences.THINKING_QUALITY_EFFORTS.indexOf("high") +
            com.ai.assistance.operit.data.preferences.ApiPreferences.MIN_THINKING_QUALITY_LEVEL

    /**
     * Adds the configuration, checks both models answer, starts Orbit with them, and has Orbit
     * greet the user. Nothing is left behind when any step fails; when the app is closed halfway,
     * [recoverInterrupted] undoes it on the next start.
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
        finishing.value = true
        try {
            finishLocked(context.applicationContext, providerName, provider, endpoint, key.trim(), main, assistant)
        } finally {
            finishing.value = false
            running.value = false
        }
    }

    /**
     * Checks that model [index] of [config] answers a short chat, within a minute. Throws a
     * [SetupException] saying why not; [hide] keeps the key out of the provider's text.
     */
    internal suspend fun checkModel(
        app: Context,
        configs: ModelConfigManager,
        config: com.ai.assistance.operit.data.model.ModelConfigData,
        index: Int,
        /** Named in the failure when it is the helper model; null for the main one. */
        helper: String?,
        hide: (String) -> String,
    ) {
        // The request blocks on the network, so a timer cancels it at the limit itself;
        // waiting for the call to return would wait for the much longer network limit.
        val service = java.util.concurrent.atomic.AtomicReference<com.ai.assistance.operit.api.chat.llmprovider.AIService?>()
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun tooSlow() = SetupException(Problem.SLOW, "", helper)
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
                    ModelConfigConnectionTester.run(app, configs, config, requestedModelIndex = index, chatOnly = true,
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
        if (chat == null || !chat.success) {
            val detail = hide(chat?.error.orEmpty())
            throw SetupException(problemOf(detail), detail, helper)
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
        val functions = FunctionalConfigManager(app)
        // Decided before anything is added: a main app that already answers keeps its own settings.
        val mainAppReady = runCatching { usable(app, configs, functions, FunctionType.CHAT) != null }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrDefault(true)
        val models = listOf(main, assistant).distinct()
        val id = "orbit-setup-" + UUID.randomUUID().toString().take(8)
        prefs(app).edit().putString(KEY_PENDING_CONFIG, id).remove(KEY_PENDING_ORBIT).commit()
        configs.createConfig(providerName.take(40), id)
        try {
            configs.updateModelConfig(id, key, endpoint, models.joinToString(","), provider)
            // The catalog sets the request protocol (how thinking strength is sent) and picture support.
            com.ai.assistance.operit.data.model.ModelAutoConfig.fill(app, id)
            val config = checkNotNull(configs.getModelConfig(id)) { "The model configuration could not be read" }
            checkModel(app, configs, config, 0, null, ::hide)
            // The helper model does the background work, so it is checked too when it differs.
            if (models.size > 1) checkModel(app, configs, config, 1, models[1], ::hide)
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                runCatching { configs.deleteConfig(id) }
                prefs(app).edit().remove(KEY_PENDING_CONFIG).commit()
            }
            throw e
        }
        // Past this point the work must finish even if the page goes away, or Orbit would be left
        // half made. A failure removes the new Orbit and configuration so setup can run again.
        return withContext(NonCancellable) {
            // Only the Orbit this setup made is undone, even if the start fails after saving it.
            var createdId: String? = null
            val started = try {
                val orbit = OrbitCompanion.start(app, onCreated = {
                    createdId = it
                    prefs(app).edit().putString(KEY_PENDING_ORBIT, it).commit()
                })
                // Pictures go to whichever of the two models can see them; none means Orbit says so when asked.
                val saved = configs.getModelConfig(id)
                val image = listOf(main, assistant).distinct().firstOrNull { name ->
                    val index = com.ai.assistance.operit.data.model.getModelList(saved?.modelName.orEmpty()).indexOf(name)
                    index >= 0 && saved?.multimodalCapabilitiesForModel(index)?.image == true
                }
                OrbitModelPreferences(app).update(orbit.id) {
                    it.copy(main = OrbitModelRef(id, main), assistant = OrbitModelRef(id, assistant),
                        multimodal = image?.let { name -> OrbitModelRef(id, name) })
                }
                prefs(app).edit().remove(KEY_PENDING_CONFIG).remove(KEY_PENDING_ORBIT)
                    .remove(KEY_WELCOME_SENT).putString(KEY_WELCOME, orbit.id).commit()
                orbit
            } catch (e: Throwable) {
                AppLogger.e("OrbitSetup", "Could not start Orbit with the new model", e)
                createdId?.let { runCatching { OrbitCompanion.remove(app, it) } }
                runCatching { configs.deleteConfig(id) }
                prefs(app).edit().remove(KEY_PENDING_CONFIG).remove(KEY_PENDING_ORBIT).commit()
                throw SetupException(Problem.OTHER, hide(e.message.orEmpty()))
            }
            // Only a main app that could not chat yet is set up too: it gets the new model, and
            // thinking on at high strength (the chat's slider can change it). A main app the user
            // already set up keeps its own model and thinking settings.
            if (!mainAppReady) {
                runCatching {
                    com.ai.assistance.operit.data.preferences.ApiPreferences.getInstance(app)
                        .updateThinkingSettings(enableThinkingMode = true, thinkingQualityLevel = THINKING_HIGH)
                }.onFailure { AppLogger.w("OrbitSetup", "Could not turn thinking on: ${it.message}") }
                runCatching { functions.setConfigForFunction(FunctionType.CHAT, id, 0) }
                    .onFailure { AppLogger.w("OrbitSetup", "Could not share the new model with the main app: ${it.message}") }
            }
            welcome(app, started)
            started
        }
    }

    private val welcoming = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Has Orbit greet the user once. Until the greeting is delivered it stays due, so a closed
     * app or a chat that stayed busy tries again on the next open; a user who already spoke drops it.
     */
    private fun welcome(context: Context, orbit: OrbitProfile) {
        if (!welcoming.compareAndSet(false, true)) return
        scope.launch {
            try {
                welcomeOnce(context, orbit)
            } finally {
                welcoming.set(false)
            }
        }
    }

    private suspend fun welcomeOnce(context: Context, orbit: OrbitProfile) {
        val p = prefs(context)
        fun done() = p.edit().remove(KEY_WELCOME).remove(KEY_WELCOME_SENT).commit()
        if (p.getString(KEY_WELCOME, null) != orbit.id) return
        val dao = com.ai.assistance.operit.data.db.AppDatabase.getDatabase(context).orbitTaskDao()
        suspend fun typed() = runCatching { dao.userMessageCount(orbit.chatId) }.getOrDefault(0) > 0
        if (typed()) { done(); return }
        // An earlier welcome went out: Orbit's reply saved after it means the welcome is done; a
        // reply that may still be on its way is left alone for a while, then the welcome goes again.
        val sentAt = p.getLong(KEY_WELCOME_SENT, 0L)
        if (sentAt > 0) {
            // An empty or thinking-only row is saved before the reply streams; only words the user sees count.
            val said = runCatching { dao.aiSaidAfter(orbit.chatId, sentAt) }.getOrDefault(emptyList()).joinToString("\n\n")
            if (com.ai.assistance.operit.ui.features.chat.components.OrbitSpeech.words(said, context.getString(com.ai.assistance.operit.R.string.orbit_sent_card)).isNotBlank()) { done(); return }
            if (System.currentTimeMillis() - sentAt < REPLY_WAIT_MS) return
        }
        val english = LocaleUtils.usesEnglishPrompt(context)
        val note = if (english) {
            "$MARKER Welcome\n" +
                "The user just set you up and opened your chat for the first time. " +
                "This is not a message from the user; they cannot see this note.\n" +
                "Say hello first, in two or three short sentences: who you are, and that they can just tell you what they want, " +
                "including setting up the app (models, tool packages, components like the terminal, permissions). " +
                "Then follow your first-talk guidance. Do not mention hidden notes."
        } else {
            "$MARKER 欢迎\n" +
                "用户刚把你设置好，第一次打开和你的聊天。这不是用户发的消息，用户也看不到这段话。\n" +
                "先主动打个招呼，两三句短话就好：你是谁；有什么事直接跟你说就行，包括把 App 配置好（模型、工具包、终端这类组件、权限）。" +
                "然后按你的初次见面指引来。不要提“隐藏消息”这些内部说法。"
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + IDLE_WAIT_MS
        try {
            while (true) {
                val left = deadline - android.os.SystemClock.elapsedRealtime()
                if (left <= 0) break
                val turn = OrbitChatDecision.Turn()
                val told = OrbitWake.tell(context, orbit, orbit.chatId, note, left, left + REPLY_WAIT_MS,
                    // Checked again right before sending: a user who started talking meanwhile drops it.
                    ready = {
                        if (typed()) { done(); false }
                        else { p.edit().putLong(KEY_WELCOME_SENT, System.currentTimeMillis() - 1).commit(); true }
                    },
                    decision = turn)
                when (told) {
                    OrbitWake.Told.BUSY -> { kotlinx.coroutines.delay(1_500); continue }
                    OrbitWake.Told.DROPPED -> {}
                    // The model failed: still due, the next open of Orbit tries again.
                    OrbitWake.Told.FAILED -> p.edit().remove(KEY_WELCOME_SENT).commit()
                    // Answered, or the wait ran out: settled by Orbit's saved reply (checked above next time).
                    else -> if (turn.confirmed || told == OrbitWake.Told.SPOKE) done()
                }
                break
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("OrbitSetup", "Could not send the welcome: ${e.message}")
        }
    }
}
