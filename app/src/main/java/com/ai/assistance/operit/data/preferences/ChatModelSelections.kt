package com.ai.assistance.operit.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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
import kotlinx.coroutines.flow.getAndUpdate
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

    /**
     * A choice made while no chat is open; the next chat created and opened takes it, and opening
     * an existing chat drops it. Kept in memory only: it belongs to that moment.
     */
    private val _pending = MutableStateFlow<OrbitModelRef?>(null)
    val pending: StateFlow<OrbitModelRef?> = _pending.asStateFlow()

    init {
        scope.launch {
            val saved = runCatching { decode(store.data.first()) }
                .onFailure { AppLogger.w(TAG, "Failed to read chat model choices", it) }
                .getOrDefault(emptyMap())
            // A switch made while reading wins over what was on disk.
            _choices.update { saved + it }
            loaded.complete(Unit)
        }
    }

    private fun key(chatId: String) = stringPreferencesKey(PREFIX + chatId)

    private fun decode(prefs: Preferences): Map<String, OrbitModelRef> =
        prefs.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(PREFIX) || value !is String) return@mapNotNull null
            runCatching { json.decodeFromString<OrbitModelRef>(value) }.getOrNull()
                ?.let { key.name.removePrefix(PREFIX) to it }
        }.toMap()

    fun observe(chatId: String?): Flow<OrbitModelRef?> =
        if (chatId.isNullOrBlank()) pending
        else choices.map { it[chatId] }.distinctUntilChanged()

    /** Memory changes and their writes are queued together, so the disk ends as memory does. */
    private val order = Any()

    fun select(chatId: String, ref: OrbitModelRef) {
        synchronized(order) {
            _choices.update { it + (chatId to ref) }
            scope.launch { store.edit { it[key(chatId)] = json.encodeToString(ref) } }
        }
    }

    fun selectForNextChat(ref: OrbitModelRef) {
        _pending.value = ref
    }

    /** [chatId] was just created and opened: it takes the choice made while no chat was open. */
    fun adoptPending(chatId: String) {
        val ref = _pending.getAndUpdate { null } ?: return
        select(chatId, ref)
    }

    /** An existing chat was opened: a choice made with none open does not carry over to it. */
    fun dropPending() {
        _pending.value = null
    }

    suspend fun forget(chatIds: Collection<String>) {
        if (chatIds.isEmpty()) return
        loaded.await()
        // Queued after any switch still being saved; a failure reaches the caller.
        val write = synchronized(order) {
            _choices.update { it - chatIds.toSet() }
            scope.async { store.edit { prefs -> chatIds.forEach { prefs.remove(key(it)) } } }
        }
        write.await()
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
