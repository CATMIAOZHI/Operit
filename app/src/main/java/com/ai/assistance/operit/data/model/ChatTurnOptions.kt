package com.ai.assistance.operit.data.model

data class ChatTurnOptions(
    val persistTurn: Boolean = true,
    val notifyReply: Boolean? = null,
    val hideUserMessage: Boolean = false,
    /** Floating composers can share a runtime without consuming the main screen's reply selection. */
    val useComposerReply: Boolean = true,
    val disableWarning: Boolean = false,
    /** Stable in-process identity for callers that need the terminal result of this exact turn. */
    val turnId: String? = null,
    val isSubTask: Boolean = false,
    /** Host-owned v2 collaboration turns accept mailbox steering without enabling it for v1. */
    val isCollaborationAgent: Boolean = false,
    val collaborationHistory: List<com.ai.assistance.operit.core.chat.hooks.PromptTurn> = emptyList(),
    val collaborationHistoryCutoff: Long? = null,
    /** Runtime model-function route. Internal turns can use a functional model without becoming CHAT. */
    val functionType: FunctionType = FunctionType.CHAT,
    /**
     * The provider conversation this turn belongs to, when that is not the chat the turn runs in.
     * An internal turn whose conversation outlives any single chat pins it here so a provider that
     * caches prompt prefixes keeps reusing the prefix a sibling turn already warmed.
     */
    val providerSessionId: String? = null,
    /**
     * Keeps this turn's chat out of the automatic history summary. A turn whose chat is continued by
     * a later turn needs the earlier prompt to still be in the conversation verbatim, because that
     * prompt is the part a provider caches and the part the next turn does not resend.
     */
    val disableSummary: Boolean = false,
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
