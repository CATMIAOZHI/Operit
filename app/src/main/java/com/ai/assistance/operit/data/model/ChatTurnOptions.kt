package com.ai.assistance.operit.data.model

data class ChatTurnOptions(
    val persistTurn: Boolean = true,
    val notifyReply: Boolean? = null,
    val hideUserMessage: Boolean = false,
    val disableWarning: Boolean = false,
    /** Stable in-process identity for callers that need the terminal result of this exact turn. */
    val turnId: String? = null,
    val isSubTask: Boolean = false,
    /** Request-only system role content. It is never persisted as a user transcript message. */
    val systemPromptOverride: String? = null,
    /** Optional transcript-only role label for the persisted prompt sender. */
    val userRoleNameOverride: String? = null,
    /** Optional transcript-only role label for the persisted assistant response. */
    val assistantRoleNameOverride: String? = null,
)
