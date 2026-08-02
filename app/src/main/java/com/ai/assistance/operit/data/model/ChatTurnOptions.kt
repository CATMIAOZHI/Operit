package com.ai.assistance.operit.data.model

data class ChatTurnOptions(
    val persistTurn: Boolean = true,
    val notifyReply: Boolean? = null,
    val hideUserMessage: Boolean = false,
    val disableWarning: Boolean = false,
    /** Stable in-process identity for callers that need the terminal result of this exact turn. */
    val turnId: String? = null,
    val isSubTask: Boolean = false,
    /** Per-turn hard gate. False hides tool schemas and ignores tool-call markup in the response. */
    val toolsEnabled: Boolean = true,
    /** When set, exposes only these tools and bypasses the ordinary global/tool-selector list. */
    val isolatedToolPrompts: List<ToolPrompt>? = null,
    /** Tools that finish the turn immediately after their result is persisted. */
    val terminalToolNames: Set<String> = emptySet(),
    /** False isolates internal turns from every global prompt/history composition hook. */
    val promptHooksEnabled: Boolean = true,
    /** Request-only system role content. It is never persisted as a user transcript message. */
    val systemPromptOverride: String? = null,
    /** Optional transcript-only role label for the persisted prompt sender. */
    val userRoleNameOverride: String? = null,
    /** Optional transcript-only role label for the persisted assistant response. */
    val assistantRoleNameOverride: String? = null,
)
