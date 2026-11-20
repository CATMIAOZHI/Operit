package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.model.multimodalCapabilitiesForModel
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.FunctionConfigMapping
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.OrbitModelPreferences
import com.ai.assistance.operit.data.preferences.OrbitModelRef
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** All Orbit function routes converge here; accounting still uses the existing OrbitUsageTag. */
object OrbitModels {
    const val DEFAULT = "DEFAULT"
    const val MAIN = "MAIN"
    const val ASSISTANT = "ASSISTANT"

    /** Short judgement calls stay light whatever the assistant level is. */
    private val LOW_REASONING = setOf(FunctionType.TITLE_GENERATION, FunctionType.PERMISSION_REVIEWER,
        FunctionType.PERMISSION_RISK_SCORER)

    /**
     * [backgroundReasoningLevel] is set only for a CHAT turn on the assistant model: that turn is
     * background work, so it follows the assistant level instead of the chat's own slider.
     */
    /** [fromChatChoice]: the chat's quick switch picked it, so a later turn looks it up again. */
    class Route(val mapping: FunctionConfigMapping, val backgroundReasoningLevel: Int?,
        val fromChatChoice: Boolean = false)

    fun taskRole(task: OrbitTaskEntity): String = taskRole(task.modelRole, task.source, task.useSubagent)

    private fun taskRole(modelRole: String, source: String, useSubagent: Boolean): String = when (modelRole) {
        MAIN, ASSISTANT -> modelRole
        else -> if (source != OrbitRoutines.ALIGNMENT_SOURCE && !useSubagent) MAIN else ASSISTANT
    }

    fun checkedRole(value: String): String = value.trim().uppercase(java.util.Locale.ROOT).also {
        require(it in setOf(DEFAULT, MAIN, ASSISTANT)) { "model must be default, main or assistant" }
    }

    suspend fun ownerId(context: Context, chatId: String?): String? {
        val settings = OrbitPreferences.getInstance(context).current()
        val callerChatId = com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
            .currentToolRuntimeContext()?.callerChatId
        return resolveOwnerId(settings, currentCoroutineContext()[OrbitUsageTag]?.orbitId, chatId,
            callerChatId, context.getString(R.string.orbit_model_owner_unavailable))
    }

    internal fun resolveOwnerId(settings: com.ai.assistance.operit.data.preferences.OrbitSettings,
        taggedOwnerId: String?, chatId: String?, callerChatId: String?, unavailable: String): String? {
        // chatOwners keeps deleted owners as tombstones. Do not turn a late Orbit call into a
        // main-app call just because the live profile has already gone.
        val orbitId = taggedOwnerId
            ?: settings.ownerForChat(chatId)?.id
            ?: chatId?.let(settings.chatOwners::get)
            ?: settings.ownerForChat(callerChatId)?.id
            ?: callerChatId?.let(settings.chatOwners::get)
            ?: return null
        check(settings.orbits.any { it.id == orbitId && !it.deleting }) { unavailable }
        return orbitId
    }

    /** The model role of the task [chatId] runs, or null when it is not a task's chat. */
    suspend fun chatTaskRole(context: Context, chatId: String): String? {
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        return dao.binding(chatId)?.let { dao.run(it.runId) }?.let { dao.task(it.taskId) }?.let(::taskRole)
    }

    /**
     * [chatTaskRole] kept up to date: a chat's task never changes, but the task's role can be
     * edited. Emits null for a chat that is not a task's.
     */
    fun observeChatTaskRole(context: Context, chatId: String): kotlinx.coroutines.flow.Flow<String?> =
        kotlinx.coroutines.flow.flow {
            val dao = AppDatabase.getDatabase(context).orbitTaskDao()
            val taskId = dao.binding(chatId)?.let { dao.run(it.runId) }?.taskId
            if (taskId == null) emit(null)
            else emitAll(dao.observeTaskModelFields(taskId).map { it?.let { f -> taskRole(f.modelRole, f.source, f.useSubagent) } })
        }.distinctUntilChanged()

    suspend fun mapping(context: Context, function: FunctionType, chatId: String? = null,
        role: String? = null): FunctionConfigMapping? = route(context, function, chatId, role)?.mapping

    suspend fun route(context: Context, function: FunctionType, chatId: String? = null,
        role: String? = null): Route? {
        val orbitId = ownerId(context, chatId) ?: return null
        var chatRole = role
        if (function == FunctionType.CHAT && chatRole == null && chatId != null) {
            chatRole = chatTaskRole(context, chatId)
        }
        // The chat's own quick switch counts for turns the user starts there. A task turn that
        // names its model role keeps the model the Orbit's settings give that role.
        val selections = com.ai.assistance.operit.data.preferences.ChatModelSelections.getInstance(context)
        val chatChoice = if (function == FunctionType.CHAT && role == null) selections.mapping(chatId) else null
        // So does the thinking level the chat set in its composer.
        val chatLevel = if (function == FunctionType.CHAT && role == null) chatId?.let { selections.level(it) } else null
        val mapping = chatChoice ?: mappingForOrbit(context, orbitId, function, chatRole)
        return Route(mapping, mapping.thinkingQualityLevel.takeIf {
            function == FunctionType.CHAT && chatRole == ASSISTANT && chatChoice == null && chatLevel == null
        }, fromChatChoice = chatChoice != null)
    }

    suspend fun mappingForOrbit(context: Context, orbitId: String, function: FunctionType,
        role: String? = null): FunctionConfigMapping {
        val orbit = OrbitPreferences.getInstance(context).requireModelOwner(orbitId)
        val settings = OrbitModelPreferences(context).current(orbitId)
        val name = orbit.name.takeIf { it.isNotBlank() } ?: context.getString(R.string.nav_orbit)
        fun unavailable() = context.getString(R.string.orbit_model_unavailable, name)
        fun imageUnavailable() = context.getString(R.string.orbit_model_image_unavailable, name)
        val selection = if (function == FunctionType.CHAT && role != null) {
            if (role == ASSISTANT) settings.assistant else settings.main
        } else settings.selection(function)
        val configs = ModelConfigManager(context)
        val level = if (function in LOW_REASONING) ApiPreferences.MIN_THINKING_QUALITY_LEVEL
        else settings.assistantThinkingLevel.coerceIn(ApiPreferences.MIN_THINKING_QUALITY_LEVEL,
            ApiPreferences.MAX_THINKING_QUALITY_LEVEL)
        suspend fun resolve(ref: OrbitModelRef?): FunctionConfigMapping {
            checkNotNull(ref) { unavailable() }
            check(ref.configId in configs.configListFlow.first()) { unavailable() }
            val config = checkNotNull(configs.getModelConfig(ref.configId))
            val index = ref.resolveIndex(config.modelName, config.apiProviderTypeId)
            check(index >= 0) { unavailable() }
            return ref.mapping(index).copy(thinkingQualityLevel = level)
        }
        val selected = resolve(selection)
        if (function == FunctionType.IMAGE_RECOGNITION || function == FunctionType.UI_CONTROLLER) {
            val config = checkNotNull(configs.getModelConfig(selected.configId))
            if (!config.multimodalCapabilitiesForModel(selected.modelIndex).image) {
                // An advanced explicit image choice must be fixed, never silently replaced.
                check(!settings.advanced || function.name !in settings.overrides) {
                    imageUnavailable()
                }
                val image = resolve(settings.multimodal)
                check(checkNotNull(configs.getModelConfig(image.configId))
                    .multimodalCapabilitiesForModel(image.modelIndex).image) {
                    imageUnavailable()
                }
                return image
            }
        }
        return selected
    }
}
