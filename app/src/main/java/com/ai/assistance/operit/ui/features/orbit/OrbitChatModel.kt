package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.preferences.FunctionConfigMapping
import com.ai.assistance.operit.data.preferences.OrbitModelPreferences
import com.ai.assistance.operit.data.preferences.OrbitModelRef
import com.ai.assistance.operit.data.preferences.OrbitModelSettings
import com.ai.assistance.operit.data.preferences.OrbitPreferences

internal data class OrbitChatModel(val orbitId: String, val mapping: FunctionConfigMapping)

/** The two shared composers must read and change Orbit's main choice, never the global CHAT map. */
@Composable
internal fun rememberOrbitChatModel(chatId: String?, configs: List<ModelConfigSummary>): OrbitChatModel? {
    val context = LocalContext.current
    val store = remember(context) { OrbitPreferences.getInstance(context) }
    val owners by store.settingsFlow.collectAsState(store.snapshot)
    val orbitId = owners.ownerForChat(chatId)?.id ?: return null
    val models = remember(context) { OrbitModelPreferences(context) }
    val settings by produceState<OrbitModelSettings?>(null, orbitId) {
        value = models.current(orbitId)
        models.observe(orbitId).collect { value = it }
    }
    val ref = settings?.main
    val config = configs.firstOrNull { it.id == ref?.configId }
    val index = if (ref != null && config != null) ref.resolveIndex(config.modelName, config.apiProviderTypeId) else -1
    return OrbitChatModel(orbitId, ref?.mapping(index) ?: FunctionConfigMapping(configId = "", modelIndex = -1))
}

internal suspend fun selectOrbitChatModel(context: android.content.Context, orbitId: String,
    configId: String, modelName: String) {
    OrbitModelPreferences(context).update(orbitId) { it.copy(main = OrbitModelRef(configId, modelName)) }
}
