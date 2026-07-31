package com.ai.assistance.operit.core.agent

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Built-in and user-defined Agent profiles used by the Subagent tool.
 *
 * Profiles are deliberately independent from CharacterCard. Built-in identity and dispatch
 * metadata remain owned by the app, while custom profiles are persisted as complete definitions.
 */
class AgentProfileRepository private constructor() {
    private val defaults: Map<String, AgentProfile> =
        listOf(
                AgentProfile(
                    id = "general",
                    name = "General",
                    description = "Handles general multi-step tasks using available tools.",
                    mode = AgentMode.SUBAGENT,
                    systemPrompt =
                        """
                        You are the built-in General Subagent. Complete the assigned task
                        independently. Use available tools when useful, follow user-controlled
                        permission decisions, and return a concise, conclusive final result.
                        """.trimIndent(),
                ),
                AgentProfile(
                    id = "explore",
                    name = "Explore",
                    description = "Searches code and information and reports evidence.",
                    mode = AgentMode.SUBAGENT,
                    systemPrompt =
                        """
                        You are the built-in Explore Subagent. Investigate the assigned task,
                        using available search and inspection tools when useful. Do not ask the
                        user for input. Return a concise result with concrete evidence.
                        """.trimIndent(),
                ),
            )
            .associateBy(AgentProfile::id)

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val profileListSerializer = ListSerializer(AgentProfile.serializer())
    private val _profiles = MutableStateFlow(defaults.values.sortedBy(AgentProfile::id))
    val profiles: StateFlow<List<AgentProfile>> = _profiles.asStateFlow()

    @Volatile private var profilesById: Map<String, AgentProfile> = defaults
    @Volatile private var preferences: android.content.SharedPreferences? = null

    @Synchronized
    fun initialize(context: Context) {
        if (preferences != null) return

        val loadedPreferences =
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val restored =
            loadedPreferences
                .getString(KEY_PROFILES, null)
                ?.let { encoded ->
                    runCatching { json.decodeFromString(profileListSerializer, encoded) }.getOrNull()
                }
                .orEmpty()
                .associateBy(AgentProfile::id)

        profilesById = mergeAgentProfileSettings(defaults, restored.values.toList())
        preferences = loadedPreferences
        publish()
    }

    fun getById(id: String): AgentProfile? = profilesById[id]

    fun requireSubagent(id: String): AgentProfile {
        val profile = requireNotNull(profilesById[id]) { "Unknown Subagent profile: $id" }
        require(profile.enabled) { "Subagent profile is disabled: $id" }
        require(profile.mode == AgentMode.SUBAGENT || profile.mode == AgentMode.ALL) {
            "Agent profile cannot run as a Subagent: $id"
        }
        return profile
    }

    fun listAvailableSubagents(includeHidden: Boolean = false): List<AgentProfile> =
        profilesById.values
            .asSequence()
            .filter(AgentProfile::enabled)
            .filter { it.mode == AgentMode.SUBAGENT || it.mode == AgentMode.ALL }
            .filter { includeHidden || !it.hidden }
            .sortedBy(AgentProfile::id)
            .toList()

    fun isBuiltIn(id: String): Boolean = id in defaults

    @Synchronized
    fun updateSettings(
        id: String,
        systemPrompt: String,
        modelConfigId: String?,
        modelIndex: Int?,
    ) {
        val current = requireNotNull(profilesById[id]) { "Unknown Agent profile: $id" }
        val updated = current.withEditableSettings(systemPrompt, modelConfigId, modelIndex)
        profilesById = profilesById + (id to updated)
        persist()
        publish()
    }

    @Synchronized
    fun createCustomProfile(
        id: String,
        name: String,
        description: String,
        systemPrompt: String,
        modelConfigId: String?,
        modelIndex: Int?,
    ): AgentProfile {
        val normalizedId = normalizeCustomAgentId(id)
        require(normalizedId !in profilesById) { "Agent profile already exists: $normalizedId" }
        val profile =
            buildCustomAgentProfile(
                id = normalizedId,
                name = name,
                description = description,
                systemPrompt = systemPrompt,
                modelConfigId = modelConfigId,
                modelIndex = modelIndex,
            )
        profilesById = profilesById + (profile.id to profile)
        persist()
        publish()
        return profile
    }

    @Synchronized
    fun updateCustomProfile(
        id: String,
        name: String,
        description: String,
        systemPrompt: String,
        modelConfigId: String?,
        modelIndex: Int?,
    ): AgentProfile {
        require(id !in defaults) { "Built-in Agent profile metadata cannot be changed: $id" }
        requireNotNull(profilesById[id]) { "Unknown Agent profile: $id" }
        val updated =
            buildCustomAgentProfile(
                id = id,
                name = name,
                description = description,
                systemPrompt = systemPrompt,
                modelConfigId = modelConfigId,
                modelIndex = modelIndex,
            )
        profilesById = profilesById + (id to updated)
        persist()
        publish()
        return updated
    }

    @Synchronized
    fun deleteCustomProfile(id: String) {
        require(id !in defaults) { "Built-in Agent profile cannot be deleted: $id" }
        requireNotNull(profilesById[id]) { "Unknown Agent profile: $id" }
        profilesById = profilesById - id
        persist()
        publish()
    }

    @Synchronized
    fun resetSettings(id: String) {
        val default = requireNotNull(defaults[id]) { "Unknown Agent profile: $id" }
        profilesById = profilesById + (id to default)
        persist()
        publish()
    }

    private fun persist() {
        val encoded =
            json.encodeToString(
                profileListSerializer,
                profilesById.values.sortedBy(AgentProfile::id),
            )
        preferences?.edit { putString(KEY_PROFILES, encoded) }
    }

    private fun publish() {
        _profiles.value = profilesById.values.sortedBy(AgentProfile::id)
    }

    companion object {
        private const val PREFERENCES_NAME = "agent_profiles"
        private const val KEY_PROFILES = "profiles_v1"

        val instance: AgentProfileRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            AgentProfileRepository()
        }
    }
}

internal fun mergeAgentProfileSettings(
    defaults: Map<String, AgentProfile>,
    restored: List<AgentProfile>,
): Map<String, AgentProfile> {
    val restoredById = restored.associateBy(AgentProfile::id)
    val mergedDefaults = defaults.mapValues { (id, default) ->
        val saved = restoredById[id] ?: return@mapValues default
        val restoredPrompt = saved.systemPrompt.trim().takeIf(String::isNotEmpty)
        default.withEditableSettings(
            systemPrompt = restoredPrompt ?: default.systemPrompt,
            modelConfigId = saved.modelConfigId,
            modelIndex = saved.modelIndex,
        )
    }
    val restoredCustom =
        restored
            .asSequence()
            .filter { it.id !in defaults }
            .mapNotNull { saved ->
                runCatching {
                        buildCustomAgentProfile(
                            id = saved.id,
                            name = saved.name,
                            description = saved.description,
                            systemPrompt = saved.systemPrompt,
                            modelConfigId = saved.modelConfigId,
                            modelIndex = saved.modelIndex,
                        )
                    }
                    .getOrNull()
            }
            .associateBy(AgentProfile::id)
    return mergedDefaults + restoredCustom
}

internal fun AgentProfile.withEditableSettings(
    systemPrompt: String,
    modelConfigId: String?,
    modelIndex: Int?,
): AgentProfile {
    val normalizedPrompt = systemPrompt.trim()
    require(normalizedPrompt.isNotEmpty()) { "Agent profile system prompt cannot be empty" }
    val normalizedConfigId = modelConfigId?.trim()?.takeIf(String::isNotEmpty)
    return copy(
        systemPrompt = normalizedPrompt,
        modelConfigId = normalizedConfigId,
        modelIndex = if (normalizedConfigId == null) null else (modelIndex ?: 0).coerceAtLeast(0),
    )
}

internal fun normalizeCustomAgentId(id: String): String {
    val normalized = id.trim().lowercase()
    require(CUSTOM_AGENT_ID_PATTERN.matches(normalized)) {
        "Agent profile ID must start with a letter and contain only lowercase letters, numbers, _ or -"
    }
    return normalized
}

internal fun buildCustomAgentProfile(
    id: String,
    name: String,
    description: String,
    systemPrompt: String,
    modelConfigId: String?,
    modelIndex: Int?,
): AgentProfile {
    val normalizedId = normalizeCustomAgentId(id)
    val normalizedName = name.trim()
    val normalizedDescription = description.trim()
    require(normalizedName.isNotEmpty()) { "Agent profile name cannot be empty" }
    require(normalizedDescription.isNotEmpty()) { "Agent profile description cannot be empty" }
    return AgentProfile(
            id = normalizedId,
            name = normalizedName,
            description = normalizedDescription,
            mode = AgentMode.SUBAGENT,
            systemPrompt = systemPrompt,
        )
        .withEditableSettings(systemPrompt, modelConfigId, modelIndex)
}

private val CUSTOM_AGENT_ID_PATTERN = Regex("[a-z][a-z0-9_-]{0,63}")
