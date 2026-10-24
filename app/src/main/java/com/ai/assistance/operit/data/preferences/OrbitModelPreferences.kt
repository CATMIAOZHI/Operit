package com.ai.assistance.operit.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.getModelList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.orbitModelsStore by preferencesDataStore(name = "orbit_models")

/** An empty modelName explicitly selects a plugin's default, not a missing named model. */
@Serializable
data class OrbitModelRef(val configId: String, val modelName: String) {
    fun resolveIndex(models: String, pluginProvider: Boolean): Int =
        if (modelName.isEmpty() && models.isBlank() && pluginProvider) 0
        else getModelList(models).indexOf(modelName)

    fun resolveIndex(models: String, providerId: String): Int =
        resolveIndex(models, models.isBlank() &&
            com.ai.assistance.operit.plugins.toolpkg.ToolPkgAiProviderRegistry.get(providerId) != null)

    fun mapping(index: Int) = FunctionConfigMapping(
        configId = configId, modelIndex = index, modelName = modelName.takeIf { it.isNotEmpty() })

    companion object {
        fun fromSelection(config: com.ai.assistance.operit.data.model.ModelConfigSummary,
            index: Int): OrbitModelRef? {
            val name = getModelList(config.modelName).getOrNull(index)
            if (name != null) return OrbitModelRef(config.id, name)
            val default = OrbitModelRef(config.id, "")
            return default.takeIf { index == 0 && it.resolveIndex(config.modelName, config.apiProviderTypeId) == 0 }
        }
    }
}

@Serializable
data class OrbitModelSettings(
    val advanced: Boolean = false,
    val main: OrbitModelRef? = null,
    val assistant: OrbitModelRef? = null,
    val multimodal: OrbitModelRef? = null,
    val overrides: Map<String, OrbitModelRef> = emptyMap(),
) {
    fun selection(function: FunctionType): OrbitModelRef? =
        if (function == FunctionType.CHAT) main
        else (if (advanced) overrides[function.name] else null) ?: assistant
}

class OrbitModelPreferences(context: Context) {
    private val app = context.applicationContext
    private val store = app.orbitModelsStore
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(orbitId: String) = stringPreferencesKey("orbit_$orbitId")

    fun observe(orbitId: String): Flow<OrbitModelSettings?> = store.data.map {
        it[key(orbitId)]?.let { raw -> json.decodeFromString<OrbitModelSettings>(raw) }
    }

    /** One-time copy only. Later deletion or invalidation never falls back to the main app. */
    suspend fun current(orbitId: String): OrbitModelSettings = initialization.withLock { currentLocked(orbitId) }

    private suspend fun currentLocked(orbitId: String): OrbitModelSettings {
        val owners = OrbitPreferences.getInstance(app)
        owners.requireModelOwner(orbitId)
        observe(orbitId).first()?.let { return it }
        val configs = ModelConfigManager(app)
        configs.initializeIfNeeded()
        val summaries = configs.getAllConfigSummaries()
        val functions = FunctionalConfigManager(app)
        suspend fun initial(function: FunctionType): OrbitModelRef? {
            val mapping = functions.getEffectiveConfigMappingForFunction(function)
            val summary = summaries.firstOrNull { it.id == mapping.configId } ?: return null
            val resolved = mapping.resolveModelIndex(summary.modelName)
            return OrbitModelRef.fromSelection(summary, resolved.modelIndex)
        }
        val main = initial(FunctionType.CHAT)
        val settings = OrbitModelSettings(main = main, assistant = initial(FunctionType.MEMORY) ?: main,
            multimodal = initial(FunctionType.IMAGE_RECOGNITION))
        owners.requireModelOwner(orbitId)
        store.edit { prefs ->
            if (prefs[key(orbitId)] == null) prefs[key(orbitId)] = json.encodeToString(settings)
        }
        return observe(orbitId).first() ?: settings
    }

    suspend fun update(orbitId: String, transform: (OrbitModelSettings) -> OrbitModelSettings) {
        initialization.withLock {
            currentLocked(orbitId)
            OrbitPreferences.getInstance(app).requireModelOwner(orbitId)
            store.edit { prefs ->
                val settings = json.decodeFromString<OrbitModelSettings>(checkNotNull(prefs[key(orbitId)]))
                prefs[key(orbitId)] = json.encodeToString(transform(settings))
            }
        }
        com.ai.assistance.operit.api.chat.EnhancedAIService.refreshOrbitServices(orbitId)
    }

    suspend fun remove(orbitId: String) {
        initialization.withLock { store.edit { it.remove(key(orbitId)) } }
        com.ai.assistance.operit.api.chat.EnhancedAIService.refreshOrbitServices(orbitId)
    }

    companion object { private val initialization = Mutex() }
}
