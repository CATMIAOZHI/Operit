package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.core.config.SystemToolPrompts
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.model.CharacterCardToolAccessConfig
import com.ai.assistance.operit.data.skill.SkillRepository
import kotlinx.coroutines.flow.first

data class ResolvedCharacterCardToolAccess(
    val customEnabled: Boolean,
    val effectiveBuiltinToolVisibility: Map<String, Boolean>,
    val allowedPackageNames: Set<String>,
    val allowedSkillNames: Set<String>,
    val allowedMcpServerNames: Set<String>,
    val canUsePackageSystem: Boolean,
    val hasAnyAllowedExternalSource: Boolean,
    /** Packages switched on for this chat: the main app's list, or an Orbit's own list in its chats. */
    val scopePackageNames: Set<String> = allowedPackageNames,
    /** Every package that exists, so a name outside [scopePackageNames] is known to be a package. */
    val knownPackageNames: Set<String> = emptySet(),
    /** In an Orbit chat, the MCP servers it has switched on; null elsewhere. */
    val mcpScope: Set<String>? = null,
    /** Every MCP server set up in the app, so one outside [mcpScope] is known to be off. */
    val knownMcpNames: Set<String> = emptySet(),
) {
    fun isBuiltinToolAllowed(toolName: String): Boolean {
        if (com.ai.assistance.operit.features.orbit.OrbitTaskExecution.isTaskCapability(toolName)) return true
        if (!customEnabled) {
            return effectiveBuiltinToolVisibility[toolName] ?: true
        }
        return when (toolName) {
            "package_proxy" -> hasAnyAllowedExternalSource
            // Orbit's own tool is not a user-managed builtin; it is gated by the calling chat.
            com.ai.assistance.operit.core.tools.OrbitTools.SET_NAME,
            com.ai.assistance.operit.core.tools.OrbitSoulTools.NAME,
            com.ai.assistance.operit.core.tools.OrbitSkillTools.NAME,
            com.ai.assistance.operit.core.tools.OrbitTaskTools.NAME,
            com.ai.assistance.operit.core.tools.OrbitProductTools.GOAL,
            com.ai.assistance.operit.core.tools.OrbitProductTools.ARTIFACT,
            com.ai.assistance.operit.core.tools.OrbitBoardTools.FEED,
            com.ai.assistance.operit.core.tools.OrbitBoardTools.IDEA,
            com.ai.assistance.operit.core.tools.OrbitBoardTools.TRACKING,
            com.ai.assistance.operit.features.orbit.OrbitTaskRunner.FINISH,
            com.ai.assistance.operit.features.orbit.OrbitReviewReader.TOOL -> true
            in com.ai.assistance.operit.core.tools.OrbitExtraTools.names -> true
            else -> effectiveBuiltinToolVisibility[toolName] == true
        }
    }

    fun isExternalSourceAllowed(sourceName: String): Boolean {
        // A package switched off for this chat stays off even if another chat loaded its tools.
        if (!isPackageInScope(sourceName)) return false
        if (!customEnabled) return true
        if (!canUsePackageSystem) return false
        return allowedPackageNames.contains(sourceName) ||
            allowedSkillNames.contains(sourceName) ||
            allowedMcpServerNames.contains(sourceName)
    }

    /** False only for a known package that is switched off for this chat. */
    fun isPackageInScope(sourceName: String): Boolean {
        val name = sourceName.trim()
        if (mcpScope != null && name in knownMcpNames && name !in mcpScope) return false
        return name !in knownPackageNames || name in scopePackageNames
    }
}

class CharacterCardToolAccessResolver private constructor(private val context: Context) {
    companion object {
        @Volatile
        private var INSTANCE: CharacterCardToolAccessResolver? = null

        fun getInstance(context: Context): CharacterCardToolAccessResolver {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CharacterCardToolAccessResolver(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val apiPreferences by lazy { ApiPreferences.getInstance(context) }
    private val characterCardManager by lazy { CharacterCardManager.getInstance(context) }
    private val skillRepository by lazy { SkillRepository.getInstance(context) }

    suspend fun resolve(
        roleCardId: String?,
        packageManager: PackageManager,
        globalToolVisibility: Map<String, Boolean>? = null,
        chatId: String? = com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
    ): ResolvedCharacterCardToolAccess {
        val effectiveGlobalToolVisibility = globalToolVisibility
            ?: runCatching { apiPreferences.toolPromptVisibilityFlow.first() }.getOrElse { emptyMap() }

        // In an Orbit chat the package list is the Orbit's own, apart from the main app's.
        val orbit = orbitOf(chatId)
        val orbitScope = orbit?.let {
            com.ai.assistance.operit.features.orbit.OrbitPackages.scopeFor(context, packageManager, it)
        }
        val globalPackageNames = orbitScope?.let { LinkedHashSet(it) }
            ?: buildGlobalPackageNames(packageManager)
        val knownPackageNames = packageManager.getAvailablePackages().keys
            .filterTo(LinkedHashSet()) { !packageManager.isToolPkgContainer(it) }
        val globalSkillNames = LinkedHashSet(SkillRepository.forChat(context, chatId).getAiVisibleSkillPackages().keys)
        // An Orbit uses only the MCP servers it has switched on itself.
        val orbitMcp = orbit?.let { com.ai.assistance.operit.features.orbit.OrbitMcp.scopeFor(context, it) }
        val globalMcpServerNames = packageManager.getAvailableServerPackages().keys
            .filterTo(LinkedHashSet()) { orbitMcp == null || it in orbitMcp.first }

        // A main-app role card's tool limits do not apply to an Orbit.
        val roleCardConfig = roleCardId
            ?.takeIf { it.isNotBlank() && orbitScope == null }
            ?.let { cardId ->
                runCatching { characterCardManager.getCharacterCard(cardId).toolAccessConfig.normalized() }
                    .getOrDefault(CharacterCardToolAccessConfig())
            }
            ?: CharacterCardToolAccessConfig()

        if (!roleCardConfig.enabled) {
            val hasAnyGlobalExternalSource = globalPackageNames.isNotEmpty() ||
                globalSkillNames.isNotEmpty() ||
                globalMcpServerNames.isNotEmpty()
            return ResolvedCharacterCardToolAccess(
                customEnabled = false,
                effectiveBuiltinToolVisibility = effectiveGlobalToolVisibility,
                allowedPackageNames = globalPackageNames,
                allowedSkillNames = globalSkillNames,
                allowedMcpServerNames = globalMcpServerNames,
                canUsePackageSystem = true,
                hasAnyAllowedExternalSource = hasAnyGlobalExternalSource,
                scopePackageNames = globalPackageNames,
                knownPackageNames = knownPackageNames,
                mcpScope = orbitMcp?.first,
                knownMcpNames = orbitMcp?.second.orEmpty(),
            )
        }

        val allowedBuiltinTools = LinkedHashSet(roleCardConfig.allowedBuiltinTools)
        val manageableBuiltinNames = SystemToolPrompts
            .getManageableToolPrompts(useEnglish = false)
            .mapTo(LinkedHashSet()) { it.name }
        val effectiveBuiltinToolVisibility = manageableBuiltinNames.associateWith { toolName ->
            (effectiveGlobalToolVisibility[toolName] ?: true) && allowedBuiltinTools.contains(toolName)
        }

        val canUsePackageSystem = effectiveBuiltinToolVisibility["use_package"] == true
        val allowedPackageNames = if (canUsePackageSystem) {
            globalPackageNames.filterTo(LinkedHashSet()) { packageName ->
                roleCardConfig.allowedPackages.contains(packageName)
            }
        } else {
            linkedSetOf()
        }
        val allowedSkillNames = if (canUsePackageSystem) {
            globalSkillNames.filterTo(LinkedHashSet()) { skillName ->
                roleCardConfig.allowedSkills.contains(skillName)
            }
        } else {
            linkedSetOf()
        }
        val allowedMcpServerNames = if (canUsePackageSystem) {
            globalMcpServerNames.filterTo(LinkedHashSet()) { serverName ->
                roleCardConfig.allowedMcpServers.contains(serverName)
            }
        } else {
            linkedSetOf()
        }
        val hasAnyAllowedExternalSource = allowedPackageNames.isNotEmpty() ||
            allowedSkillNames.isNotEmpty() ||
            allowedMcpServerNames.isNotEmpty()

        return ResolvedCharacterCardToolAccess(
            customEnabled = true,
            effectiveBuiltinToolVisibility = effectiveBuiltinToolVisibility,
            allowedPackageNames = allowedPackageNames,
            allowedSkillNames = allowedSkillNames,
            allowedMcpServerNames = allowedMcpServerNames,
            canUsePackageSystem = canUsePackageSystem,
            hasAnyAllowedExternalSource = hasAnyAllowedExternalSource,
            scopePackageNames = globalPackageNames,
            knownPackageNames = knownPackageNames,
            mcpScope = orbitMcp?.first,
            knownMcpNames = orbitMcp?.second.orEmpty(),
        )
    }

    /** The Orbit a chat belongs to, looked up once per check; null for any other chat. */
    private suspend fun orbitOf(chatId: String?): OrbitProfile? {
        if (chatId.isNullOrBlank()) return null
        return runCatching { com.ai.assistance.operit.features.orbit.OrbitMemoryScope.owner(context, chatId) }.getOrNull()
    }

    private fun buildGlobalPackageNames(packageManager: PackageManager): LinkedHashSet<String> {
        return packageManager.getEnabledPackageNames()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { packageName ->
                packageManager.getPackageTools(packageName) != null &&
                    !packageManager.isToolPkgContainer(packageName)
            }
            .toCollection(LinkedHashSet())
    }

    /**
     * Only which packages this chat may use, for a chat with no role card: the main app's list, or
     * an Orbit's own list in its chats. Skips the skill scan [resolve] does.
     */
    suspend fun resolvePackageScope(packageManager: PackageManager, chatId: String?): ResolvedCharacterCardToolAccess {
        val orbit = orbitOf(chatId)
        val scope = orbit?.let {
            LinkedHashSet(com.ai.assistance.operit.features.orbit.OrbitPackages.scopeFor(context, packageManager, it))
        }
            ?: buildGlobalPackageNames(packageManager)
        val orbitMcp = orbit?.let { com.ai.assistance.operit.features.orbit.OrbitMcp.scopeFor(context, it) }
        return ResolvedCharacterCardToolAccess(
            customEnabled = false,
            effectiveBuiltinToolVisibility = emptyMap(),
            allowedPackageNames = scope,
            allowedSkillNames = emptySet(),
            allowedMcpServerNames = emptySet(),
            canUsePackageSystem = true,
            hasAnyAllowedExternalSource = scope.isNotEmpty(),
            scopePackageNames = scope,
            knownPackageNames = packageManager.getAvailablePackages().keys
                .filterTo(LinkedHashSet()) { !packageManager.isToolPkgContainer(it) },
            mcpScope = orbitMcp?.first,
            knownMcpNames = orbitMcp?.second.orEmpty(),
        )
    }
}
