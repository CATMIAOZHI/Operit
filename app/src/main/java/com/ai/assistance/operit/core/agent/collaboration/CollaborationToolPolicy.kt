package com.ai.assistance.operit.core.agent.collaboration

import android.content.Context

object CollaborationToolPolicy {
    /**
     * Visibility of the tools that depend on which chat calls them: the collaboration tools, plus
     * the Orbit tools that exist only in an Orbit's own chat, and the terminal (always in an Orbit,
     * elsewhere behind its switch).
     */
    suspend fun visibility(context: Context, chatId: String?, isSubTask: Boolean): Map<String, Boolean> {
        // The Orbit owning the chat is looked up once, for its tools and the terminal alike.
        val owner = com.ai.assistance.operit.features.orbit.OrbitMemoryScope.owner(context, chatId)
        val terminalShown = com.ai.assistance.operit.core.tools.TerminalTools.available(context, isOrbitChat = owner != null)
        return collaborationVisibility(context, chatId, isSubTask) +
            com.ai.assistance.operit.core.tools.OrbitTools.visibility(context, chatId, owner) +
            com.ai.assistance.operit.core.tools.TerminalTools.switchedNames.associateWith { terminalShown }
    }

    /** Only the collaboration tools' part of [visibility]. */
    suspend fun collaborationVisibility(context: Context, chatId: String?, isSubTask: Boolean): Map<String, Boolean> =
        visibilityForCaller(chatId, isSubTask, CollaborationCoordinator.getInstance(context).isAgent(chatId))

    internal fun visibilityForCaller(chatId: String?, isSubTask: Boolean, v2Child: Boolean): Map<String, Boolean> {
        // User chats always use v2, including chats with obsolete saved v1 preferences.
        // Internal v1 tasks invoke the coordinator directly and cannot recursively delegate.
        val enabled = v2Child || (!isSubTask && !chatId.isNullOrBlank())
        return CollaborationTools.names.associateWith { enabled } +
            (CollaborationTools.LEGACY_TASK_NAME to false)
    }
}
