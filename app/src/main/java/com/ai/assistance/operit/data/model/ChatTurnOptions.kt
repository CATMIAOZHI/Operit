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
    /**
     * True when this turn is delivered by the app rather than typed by the owner: a tool that sends
     * a message into a chat, or the host that starts a subagent turn. The turn still runs normally
     * in the target chat; only the permission reviewer treats it as evidence instead of the user's
     * own request.
     */
    val deliveredByTool: Boolean = false,
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

/**
 * The display mode of the user turn this turn stores.
 *
 * The mode is the only record of where a stored user turn came from, so a caller that delivers a
 * turn rather than answering the owner has to mark it here: the permission reviewer reads the
 * modes of [TOOL_DELIVERED][ChatMessageDisplayMode.TOOL_DELIVERED] and the collaboration ones as
 * evidence instead of the owner's own request, and reads everything else as the owner speaking.
 *
 * A hidden turn wins over the other two: the chat does not show it at all, which is a stronger
 * statement than who delivered it.
 *
 * @param hidden whether the chat hides this turn, which is `hideUserMessage` once the caller has
 *   decided that the turn is persisted at all.
 */
internal fun ChatTurnOptions.userTurnDisplayMode(hidden: Boolean): ChatMessageDisplayMode =
    when {
        hidden -> ChatMessageDisplayMode.HIDDEN_PLACEHOLDER
        isCollaborationAgent -> ChatMessageDisplayMode.COLLABORATION_TASK
        deliveredByTool -> ChatMessageDisplayMode.TOOL_DELIVERED
        else -> ChatMessageDisplayMode.NORMAL
    }
