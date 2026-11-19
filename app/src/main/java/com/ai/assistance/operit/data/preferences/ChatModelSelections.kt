package com.ai.assistance.operit.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.chatModelSelectionStore by preferencesDataStore(name = "chat_model_selections")

/**
 * The model one chat switched to with the composer's quick switch. Each chat keeps its own, so a
 * side chat's switch never reaches the main chat or the configured main model; a chat without one
 * (every new chat) follows the configured main model.
 *
 * The choices live in memory and are written to disk behind it, so a switch counts for the very
 * next send and every composer shows it at once.
 *
 * A chat's thinking level is kept the same way: set from one chat's composer it stays with that
 * chat, and a chat that never set one follows the saved composer level.
 */
class ChatModelSelections private constructor(context: Context) {
    private val app = context.applicationContext
    private val store = app.chatModelSelectionStore
    private val json = Json { ignoreUnknownKeys = true }
    /** One at a time, in order: the last switch is the one left on disk. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val writer = Dispatchers.IO.limitedParallelism(1)
    /** A failed write is logged, never thrown into the app: the choice still holds in memory. */
    private val scope = CoroutineScope(SupervisorJob() + writer +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e -> AppLogger.w(TAG, "Failed to save a chat model choice", e) })
    private val loaded = CompletableDeferred<Unit>()
    private val _choices = MutableStateFlow<Map<String, OrbitModelRef>>(emptyMap())

    /** Every chat's own choice, by chat ID. */
    val choices: StateFlow<Map<String, OrbitModelRef>> = _choices.asStateFlow()

    /** Memory changes and their writes are queued together, so the disk ends as memory does. */
    private val order = Any()

    /**
     * A choice made while no chat is open; the next chat created and opened takes it, and opening
     * an existing chat drops it. Kept in memory only: it belongs to that moment.
     */
    private val _pending = MutableStateFlow<OrbitModelRef?>(null)
    val pending: StateFlow<OrbitModelRef?> = _pending.asStateFlow()

    private val _levels = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Every chat's own thinking level, by chat ID. */
    val levels: StateFlow<Map<String, Int>> = _levels.asStateFlow()

    /** A thinking level set while no chat is open; it goes with the model choice in [pending]. */
    private val _pendingLevel = MutableStateFlow<Int?>(null)
    val pendingLevel: StateFlow<Int?> = _pendingLevel.asStateFlow()

    init {
        scope.launch {
            val prefs = runCatching { store.data.first() }
                .onFailure { AppLogger.w(TAG, "Failed to read chat model choices", it) }
                .getOrNull()
            val saved = prefs?.let(::decode).orEmpty()
            val savedLevels = prefs?.let(::decodeLevels).orEmpty()
            // A switch made while reading wins over what was on disk.
            _choices.update { saved + it }
            _levels.update { savedLevels + it }
            loaded.complete(Unit)
        }
    }

    private fun key(chatId: String) = stringPreferencesKey(PREFIX + chatId)
    private fun levelKey(chatId: String) = intPreferencesKey(LEVEL_PREFIX + chatId)

    private fun decode(prefs: Preferences): Map<String, OrbitModelRef> =
        prefs.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(PREFIX) || value !is String) return@mapNotNull null
            runCatching { json.decodeFromString<OrbitModelRef>(value) }.getOrNull()
                ?.let { key.name.removePrefix(PREFIX) to it }
        }.toMap()

    private fun decodeLevels(prefs: Preferences): Map<String, Int> =
        prefs.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(LEVEL_PREFIX) || value !is Int) null
            else key.name.removePrefix(LEVEL_PREFIX) to value
        }.toMap()

    fun observe(chatId: String?): Flow<OrbitModelRef?> =
        if (chatId.isNullOrBlank()) pending
        else choices.map { it[chatId] }.distinctUntilChanged()

    fun select(chatId: String, ref: OrbitModelRef) {
        synchronized(order) {
            _choices.update { it + (chatId to ref) }
            scope.launch { store.edit { it[key(chatId)] = json.encodeToString(ref) } }
        }
    }

    /**
     * The chat goes back to its default model when [drop] says its own pick should go; its own
     * thinking level stays. Waits for the saved picks first, so one read late cannot come back,
     * and leaves a pick changed meanwhile alone.
     */
    suspend fun clearModelIf(chatId: String, drop: suspend (OrbitModelRef) -> Boolean) {
        loaded.await()
        val ref = _choices.value[chatId] ?: return
        if (!drop(ref)) return
        synchronized(order) {
            if (_choices.value[chatId] != ref) return
            _choices.update { it - chatId }
            scope.launch { store.edit { it.remove(key(chatId)) } }
        }
    }

    /**
     * Each pick made with no chat open counts up its own generation, so the model and the level
     * picked go to one chat each, and a pick made later is never taken or cleared for an earlier one.
     */
    private var modelGeneration = 0L
    private var levelGeneration = 0L
    private var claimedModelGeneration = -1L
    private var claimedLevelGeneration = -1L

    fun selectForNextChat(ref: OrbitModelRef) {
        synchronized(order) {
            _pending.value = ref
            modelGeneration++
        }
    }

    fun selectLevel(chatId: String, level: Int) {
        val clamped = level.coerceIn(ApiPreferences.MIN_THINKING_QUALITY_LEVEL, ApiPreferences.MAX_THINKING_QUALITY_LEVEL)
        synchronized(order) {
            _levels.update { it + (chatId to clamped) }
            scope.launch { store.edit { it[levelKey(chatId)] = clamped } }
        }
    }

    fun selectLevelForNextChat(level: Int) {
        synchronized(order) {
            _pendingLevel.value =
                level.coerceIn(ApiPreferences.MIN_THINKING_QUALITY_LEVEL, ApiPreferences.MAX_THINKING_QUALITY_LEVEL)
            levelGeneration++
        }
    }

    /** What one chat took from the no-chat picks: each one's generation, or null if not taken. */
    class PendingClaim internal constructor(
        internal val modelGeneration: Long?,
        internal val levelGeneration: Long?,
    )

    /** The no-chat picks standing at one moment, so only those are dropped later. */
    class PendingMark internal constructor(
        internal val modelGeneration: Long,
        internal val levelGeneration: Long,
    )

    /**
     * [chatId] was just created and is being opened: it takes the picks made while no chat was open
     * that no other chat took. They stay shown for the no-chat composer until [releasePending] with
     * the returned claim, called once [chatId] is on screen, so the composer never shows the
     * defaults in between. Null when there was nothing to take.
     */
    fun adoptPending(chatId: String): PendingClaim? = synchronized(order) {
        val model = _pending.value?.takeIf { claimedModelGeneration != modelGeneration }
        val level = _pendingLevel.value?.takeIf { claimedLevelGeneration != levelGeneration }
        if (model == null && level == null) return null
        model?.let {
            claimedModelGeneration = modelGeneration
            select(chatId, it)
        }
        level?.let {
            claimedLevelGeneration = levelGeneration
            selectLevel(chatId, it)
        }
        PendingClaim(
            modelGeneration = modelGeneration.takeIf { model != null },
            levelGeneration = levelGeneration.takeIf { level != null },
        )
    }

    /** Lets go of the picks [adoptPending] took with [claim]; any made since stay. */
    fun releasePending(claim: PendingClaim) {
        synchronized(order) {
            if (claim.modelGeneration == modelGeneration) _pending.value = null
            if (claim.levelGeneration == levelGeneration) _pendingLevel.value = null
        }
    }

    fun pendingMark(): PendingMark = synchronized(order) { PendingMark(modelGeneration, levelGeneration) }

    /**
     * A chat was opened: the picks made with none open before [mark], taken when the opening began,
     * do not carry over to it (a chat just made has taken them already). Picks made since stay.
     */
    fun dropPending(mark: PendingMark) {
        synchronized(order) {
            if (mark.modelGeneration == modelGeneration) {
                _pending.value = null
                modelGeneration++
            }
            if (mark.levelGeneration == levelGeneration) {
                _pendingLevel.value = null
                levelGeneration++
            }
        }
    }

    suspend fun forget(chatIds: Collection<String>) {
        if (chatIds.isEmpty()) return
        loaded.await()
        // Queued after any switch still being saved; a failure reaches the caller.
        val write = synchronized(order) {
            _choices.update { it - chatIds.toSet() }
            _levels.update { it - chatIds.toSet() }
            scope.async {
                store.edit { prefs ->
                    chatIds.forEach {
                        prefs.remove(key(it))
                        prefs.remove(levelKey(it))
                    }
                }
            }
        }
        write.await()
    }

    /** The chat's own thinking level, or null when it follows the saved composer level. */
    suspend fun level(chatId: String?): Int? =
        if (chatId.isNullOrBlank()) _pendingLevel.value
        else {
            loaded.await()
            _levels.value[chatId]
        }

    /** The chat's own choice as it stands now, or null when it has none or the model is gone. */
    suspend fun mapping(chatId: String?): FunctionConfigMapping? {
        val ref = if (chatId.isNullOrBlank()) {
            _pending.value
        } else {
            loaded.await()
            _choices.value[chatId]
        } ?: return null
        // Only the chosen config is read, not every config's summary.
        val configs = ModelConfigManager(app)
        if (ref.configId !in configs.configListFlow.first()) return null
        val config = configs.getModelConfig(ref.configId) ?: return null
        val index = ref.resolveIndex(config.modelName, config.apiProviderTypeId)
        return if (index >= 0) ref.mapping(index) else null
    }

    companion object {
        private const val TAG = "ChatModelSelections"
        private const val PREFIX = "chat_"
        private const val LEVEL_PREFIX = "level_"

        @Volatile private var instance: ChatModelSelections? = null

        fun getInstance(context: Context): ChatModelSelections =
            instance ?: synchronized(this) {
                instance ?: ChatModelSelections(context).also { instance = it }
            }

        /** [ref] against the loaded summaries, or null when its model is no longer there. */
        fun resolve(ref: OrbitModelRef?, configs: List<ModelConfigSummary>): FunctionConfigMapping? {
            ref ?: return null
            val config = configs.firstOrNull { it.id == ref.configId } ?: return null
            val index = ref.resolveIndex(config.modelName, config.apiProviderTypeId)
            return if (index >= 0) ref.mapping(index) else null
        }
    }
}
