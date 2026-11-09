package com.ai.assistance.operit.core.agent.collaboration

import kotlinx.serialization.Serializable

@Serializable
enum class AgentMessageKind { MESSAGE, NEW_TASK, FINAL_ANSWER, STATUS }

@Serializable
data class AgentMessage(
    val id: String,
    val sender: String,
    val recipient: String,
    val kind: AgentMessageKind,
    val text: String,
    /**
     * The sender's agent ID (its chat). A name can pass to a new agent once its holder is archived;
     * the ID cannot, so a reply sent to it reaches the agent that wrote, or fails if it is gone.
     */
    val senderId: String? = null,
    /** Internal send time (epoch ms); only Orbit envelopes display it. */
    val sentAt: Long? = null,
    /** The phone's time zone when it was sent, so the time reads the same however often it is rendered. */
    val sentZone: String? = null,
) {
    fun render(orbit: Boolean = false): String =
        "Message ID: $id\nMessage Type: $kind\nTask name: $recipient\n" +
            "Sender: $sender\n" + (senderId?.let { "Sender ID: $it\n" } ?: "") +
            (if (orbit) (sentAt?.let { "Sent at: ${sentAtLabel(it)}\n" } ?: "") +
                "Roles: ${roles()}\n" else "") + "Payload:\n$text"

    /** Who sent it and who reads it, so an agent never mistakes itself for the main agent. */
    fun roles(): String {
        val from = when {
            sender == AgentPath.ROOT ->
                if (recipient.substringBeforeLast('/') == sender) "the main agent, your parent" else "the main agent"
            recipient.substringBeforeLast('/') == sender -> "your parent agent"
            sender.substringBeforeLast('/') == recipient -> "your subagent"
            else -> "another subagent"
        }
        val you = if (recipient == AgentPath.ROOT) "the main agent" else "a subagent"
        return "sender $sender is $from; you ($recipient) are $you"
    }

    private fun sentAtLabel(at: Long): String {
        val zone = sentZone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneId.systemDefault()
        return com.ai.assistance.operit.features.orbit.OrbitMessageTime.label(at, zone)
    }
}

private val legacyOrbitEnvelopeInstructions = Regex(
    """(You are the subagent /root(?:/[a-z0-9_]+)+, not the main agent /root\.)\s+""" +
        """Each agent message states when\s+it was sent \(Sent at\) and who sent it \(Roles\); """ +
        """/root is the main agent that talks\s+with the user\.""",
)

/** Saved host prompts may contain both this agent's and inherited parents' old instructions. */
internal fun collaborationSystemPromptForScope(prompt: String, orbit: Boolean): String {
    if (orbit) return prompt
    return legacyOrbitEnvelopeInstructions.replace(prompt) { it.groupValues[1] }
}

@Serializable
enum class CollaborationStatus { IDLE, RUNNING, COMPLETED, INTERRUPTED, FAILED }

@Serializable
data class CollaborationTurn(
    val kind: String, val content: String, val toolName: String? = null,
    val metadata: Map<String, CollaborationMetadata> = emptyMap(),
) {
    fun toPromptTurn() = com.ai.assistance.operit.core.chat.hooks.PromptTurn(
        com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.valueOf(kind), content, toolName,
        metadata.mapValues { it.value.value() },
    )
}

@Serializable
data class CollaborationAgent(
    val rootChatId: String,
    val path: String,
    val chatId: String,
    val runId: String? = null,
    val parentPath: String? = null,
    val profileId: String = "general",
    val systemPrompt: String = "",
    val modelConfigId: String? = null,
    val modelIndex: Int? = null,
    val status: CollaborationStatus = CollaborationStatus.IDLE,
    val messages: List<AgentMessage> = emptyList(),
    val lastError: String? = null,
    val inheritedHistory: List<CollaborationTurn> = emptyList(),
    val reasoningEffort: String? = null,
    val historyCutoff: Long? = null,
    val roleCardId: String? = null,
    val finalAnswer: String? = null,
    /**
     * Archived after it fell out of the most recent agents of its tree: it is no longer listed,
     * addressed or counted for names, so its name can be used again; its chat stays known.
     */
    val archived: Boolean = false,
    /**
     * When the main agent last asked for the work this agent is doing: the time /root spawned or
     * followed it up, or, for work a subagent passed on, the time of that subagent's own request.
     * Lets a running agent count toward the main chat's turn that set it going.
     */
    val workSince: Long? = null,
)

/**
 * Durable input remains queued until the model loop acknowledges its history handoff.
 * Runtime delivery reservations belong to the coordinator, never to this persisted state.
 */
@Serializable
data class CollaborationState(
    val version: Int = 1,
    val agents: List<CollaborationAgent> = emptyList(),
) {
    /** The live agent at [path]; archived ones no longer hold their names. */
    fun find(rootChatId: String, path: String): CollaborationAgent? =
        agents.firstOrNull { it.rootChatId == rootChatId && it.path == path && !it.archived }

    /** Replaces the live agent at the same path and moves it last, so the order runs from least to most recently active. */
    fun update(agent: CollaborationAgent): CollaborationState {
        require(!agent.archived) { "Archived agents cannot change" }
        return copy(agents = agents.filterNot {
            it.rootChatId == agent.rootChatId && it.path == agent.path && !it.archived
        } + agent)
    }

    /**
     * Archives the live agents with these chats. An archived agent keeps only who it was (its tree,
     * path and chat); its prompt, inherited history, mail and answer are dropped, since nothing can
     * run or address it again and its own chat already holds what it did.
     */
    fun archive(chatIds: Set<String>): CollaborationState =
        copy(agents = agents.map {
            if (it.chatId in chatIds && !it.archived) it.copy(
                archived = true, messages = emptyList(), systemPrompt = "", inheritedHistory = emptyList(),
                finalAnswer = null, lastError = null,
            ) else it
        })

    fun enqueue(rootChatId: String, message: AgentMessage): CollaborationState {
        val receiver = requireNotNull(find(rootChatId, message.recipient)) {
            "Unknown agent: ${message.recipient}"
        }
        require(find(rootChatId, message.sender) != null) { "Unknown sender" }
        require(message.text.isNotBlank()) { "Empty message cannot be sent" }
        require(message.kind != AgentMessageKind.NEW_TASK || receiver.path != AgentPath.ROOT) {
            "Follow-up tasks cannot target the root agent"
        }
        require(receiver.messages.none { it.id == message.id }) { "Duplicate message ID" }
        return update(receiver.copy(messages = receiver.messages + message))
    }

    fun acknowledge(rootChatId: String, path: String, ids: Set<String>): CollaborationState {
        val agent = requireNotNull(find(rootChatId, path))
        return update(agent.copy(messages = agent.messages.filterNot { it.id in ids }))
    }
}
