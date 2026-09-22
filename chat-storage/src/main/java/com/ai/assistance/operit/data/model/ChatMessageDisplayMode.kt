package com.ai.assistance.operit.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ChatMessageDisplayMode {
    NORMAL,
    HIDDEN_PLACEHOLDER,
    COLLABORATION_EVENT,
    COLLABORATION_TASK,

    /**
     * A turn the app delivered into the chat: a tool that sends a message, and the agent host that
     * starts a subagent turn. The row is stored like the owner's own turn, and the target chat
     * answers it as one, but the words are model or tool output rather than the owner typing, so
     * the permission reviewer must not read it as the owner's own statement.
     */
    TOOL_DELIVERED,
    ASSISTANT_INTERMEDIATE;

    val isCollaborationEvent: Boolean
        get() = this == COLLABORATION_EVENT || this == COLLABORATION_TASK

    /**
     * True when the row records a turn the app delivered instead of the owner typing it: a tool that
     * sends a message into the chat, and the host that starts a subagent turn.
     *
     * The words of such a turn are tool or model output, so the permission reviewer reads them as
     * evidence rather than as the owner asking for something, and shows them as a delivery rather
     * than under the owner's label.
     */
    val isDeliveredTurn: Boolean
        get() = this == TOOL_DELIVERED || this == HIDDEN_PLACEHOLDER
}
