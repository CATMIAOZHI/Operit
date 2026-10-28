package com.ai.assistance.operit.ui.permissions

import com.ai.assistance.operit.core.agent.collaboration.CollaborationTools
import java.util.concurrent.ConcurrentHashMap

/**
 * Chats whose current turn runs with nobody watching, such as an Orbit check-in.
 *
 * A tool that would ask the user is refused at once in these chats, so the user is not shown an
 * approval window for something they did not start, and the model learns why right away.
 */
object UnattendedChats {
    private val chatIds = ConcurrentHashMap.newKeySet<String>()

    fun begin(chatId: String) {
        chatIds.add(chatId)
    }

    fun end(chatId: String) {
        chatIds.remove(chatId)
    }

    fun contains(chatId: String?): Boolean = chatId != null && chatId in chatIds

    /**
     * Tools that start a turn in another chat. That turn's tools would ask the user there, outside
     * this unattended turn, so they are refused while nobody is watching.
     */
    fun isHandOffTool(toolName: String): Boolean =
        CollaborationTools.isCollaborationTool(toolName) || toolName in HAND_OFF_TOOL_NAMES

    private val HAND_OFF_TOOL_NAMES = setOf("send_message_to_ai", "start_chat_service")
}
