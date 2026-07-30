package com.ai.assistance.operit.core.agent

/**
 * Stable built-in Agent profiles for the first Subagent release.
 *
 * Profiles are deliberately independent from CharacterCard. User-editable persistence can be
 * added behind this repository without changing task ids or child transcript semantics.
 */
class AgentProfileRepository private constructor() {
    private val profiles: Map<String, AgentProfile> =
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

    fun getById(id: String): AgentProfile? = profiles[id]

    fun requireSubagent(id: String): AgentProfile {
        val profile = requireNotNull(profiles[id]) { "Unknown Subagent profile: $id" }
        require(profile.enabled) { "Subagent profile is disabled: $id" }
        require(profile.mode == AgentMode.SUBAGENT || profile.mode == AgentMode.ALL) {
            "Agent profile cannot run as a Subagent: $id"
        }
        return profile
    }

    fun listAvailableSubagents(includeHidden: Boolean = false): List<AgentProfile> =
        profiles.values
            .asSequence()
            .filter(AgentProfile::enabled)
            .filter { it.mode == AgentMode.SUBAGENT || it.mode == AgentMode.ALL }
            .filter { includeHidden || !it.hidden }
            .sortedBy(AgentProfile::id)
            .toList()

    companion object {
        val instance: AgentProfileRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            AgentProfileRepository()
        }
    }
}
