package com.ai.assistance.operit.core.agent.collaboration

import android.content.Context
import com.ai.assistance.operit.core.agent.AgentProfileRepository

object CollaborationToolPolicy {
    fun visibility(context: Context, chatId: String?, isSubTask: Boolean): Map<String, Boolean> {
        val v2Child = CollaborationCoordinator.getInstance(context).isAgent(chatId)
        val version = when {
            v2Child -> 2
            isSubTask || chatId.isNullOrBlank() -> 0
            else -> AgentProfileRepository.instance.apply { initialize(context) }.versionForChat(chatId)
        }
        return CollaborationTools.names.associateWith { version == 2 } + ("task" to (version == 1))
    }
}
