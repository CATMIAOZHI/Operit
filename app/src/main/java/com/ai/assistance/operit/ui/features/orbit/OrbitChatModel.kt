package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.preferences.ChatModelSelections
import com.ai.assistance.operit.data.preferences.FunctionConfigMapping
import com.ai.assistance.operit.data.preferences.OrbitModelPreferences
import com.ai.assistance.operit.data.preferences.OrbitModelRef
import com.ai.assistance.operit.data.preferences.OrbitModelSettings
import com.ai.assistance.operit.data.preferences.OrbitPreferences

internal data class OrbitChatModel(val orbitId: String, val mapping: FunctionConfigMapping)

/**
 * The two shared composers show this chat's model: its own quick-switch choice, else the Orbit's
 * main model. Switching changes only this chat, never the main model or the global CHAT map.
 */
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
    val chosen = rememberChatModelChoice(chatId, configs)
    if (chosen != null) return OrbitChatModel(orbitId, chosen)
    val ref = settings?.main
    val config = configs.firstOrNull { it.id == ref?.configId }
    val index = if (ref != null && config != null) ref.resolveIndex(config.modelName, config.apiProviderTypeId) else -1
    return OrbitChatModel(orbitId, ref?.mapping(index) ?: FunctionConfigMapping(configId = "", modelIndex = -1))
}

/** This chat's own quick-switch choice, or null when it follows the configured main model. */
@Composable
internal fun rememberChatModelChoice(chatId: String?, configs: List<ModelConfigSummary>): FunctionConfigMapping? {
    val context = LocalContext.current
    val selections = remember(context) { ChatModelSelections.getInstance(context) }
    // Read from the kept choices, so another chat's model is never shown while switching chats.
    val choices by selections.choices.collectAsState()
    val pending by selections.pending.collectAsState()
    val ref = if (chatId.isNullOrBlank()) pending else choices[chatId]
    return remember(ref, configs) { ChatModelSelections.resolve(ref, configs) }
}

/** The quick switch: only [chatId] changes model (with no chat open, the chat made for the next send). */
internal fun selectChatModel(context: android.content.Context, chatId: String?,
    configId: String, modelName: String) {
    val selections = ChatModelSelections.getInstance(context)
    val ref = OrbitModelRef(configId, modelName)
    if (chatId.isNullOrBlank()) selections.selectForNextChat(ref) else selections.select(chatId, ref)
}
