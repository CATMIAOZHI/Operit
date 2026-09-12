package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.repository.SubagentRunRepository
import com.ai.assistance.operit.ui.features.chat.components.part.resolveSubagentDisplayedTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal data class CollaborationDisplayMessage(val sender: String, val kind: String, val body: String)

internal fun collaborationDisplayMessages(content: String, sender: String): List<CollaborationDisplayMessage> {
    val header = Regex(
        "^Message ID: [0-9a-f-]{36}\\nMessage Type: (MESSAGE|NEW_TASK|FINAL_ANSWER|STATUS)\\n" +
            "Task name: [^\\n]+\\nSender: ([^\\n]+)\\nPayload:\\n",
    )
    val match = header.find(content)
    if (match == null) {
        return listOf(CollaborationDisplayMessage(sender, "NEW_TASK", content))
    }
    return listOf(
        CollaborationDisplayMessage(
            sender, match.groupValues[1],
            content.substring(match.range.last + 1).trimEnd(),
        )
    )
}

/**
 * Only a message the agent sent back is its return. A task the parent handed over and a status line
 * are not, so those rows fall through to the child conversation the way a spawn row does.
 */
internal fun collaborationReturnedBody(kind: String, body: String): String? =
    body.takeIf { kind == "MESSAGE" || kind == "FINAL_ANSWER" }

/**
 * The label a collaboration row carries for the kind of message it is. It is the row's own subtitle,
 * so a row whose text arrives on its own reads as what it is rather than as the run's last status.
 */
internal fun collaborationKindLabelRes(kind: String): Int =
    when (kind) {
        "FINAL_ANSWER" -> R.string.subagent_message_result
        "STATUS" -> R.string.subagent_message_status
        "NEW_TASK" -> R.string.subagent_message_task
        else -> R.string.subagent_message_note
    }

/**
 * A mid-way message is not the result the agent finished with. The run status of a live run would say
 * "completed" over it, so a message says what it is instead.
 */
internal fun collaborationMidwayLabelRes(kind: String): Int? =
    R.string.subagent_message_midway.takeIf { kind == "MESSAGE" }

/**
 * Resolves the live run a collaboration row belongs to. Archived runs, v1 tasks and runs owned by
 * another feature (the reading companion shares this table) never shadow the agent's own run.
 */
internal fun findCollaborationRun(
    runs: List<SubagentRunEntity>,
    sender: String?,
): SubagentRunEntity? {
    val owner = sender?.takeIf { it.isNotBlank() } ?: return null
    return runs.firstOrNull {
        it.externalOwnerId == owner &&
            it.externalOwnerType == CollaborationCoordinator.OWNER_TYPE &&
            it.archivedAt == null
    }
}

/**
 * A collaboration row that belongs to a real Subagent run: the row keeps the agent's badge, its
 * live status and the run statistics instead of the anonymous "note" line.
 */
@Composable
private fun SubagentRunEventCard(
    run: SubagentRunEntity,
    event: CollaborationDisplayMessage,
    chatId: String?,
    onOpenConversation: () -> Unit,
) {
    val context = LocalContext.current
    val chatCore = remember(context) {
        ChatRuntimeHolder.getInstance(context.applicationContext).getCore(ChatRuntimeSlot.MAIN)
    }
    val childChatId = run.childChatId
    val childProcessingState = perChatValue(chatCore.inputProcessingStateByChatId, childChatId)
    val childLastToolName = perChatValue(chatCore.lastToolNameByChatId, childChatId)

    val currentTool =
        resolveSubagentDisplayedTool(
            childProcessingState = childProcessingState,
            lastToolName = childLastToolName,
        )
    val cardState =
        subagentCardState(
            runStatus = run.status,
            currentTool = currentTool,
        )
    val agentPath = run.externalOwnerId?.takeIf { it.isNotBlank() } ?: event.sender
    val roleName = rememberMainAgentRole(agentPath)?.name

    SubagentAgentCard(
        agentPath = collaborationRowTitle(agentPath, roleName),
        statusText =
            collaborationMidwayLabelRes(event.kind)?.let { stringResource(it) }
                ?: subagentCardStatusText(cardState),
        identity = remember(agentPath) { subagentAgentIdentity(agentPath) },
        statusColor = subagentCardStatusColor(cardState.status),
        body = collaborationReturnedBody(event.kind, event.body),
        chatId = chatId,
        childChatId = childChatId,
        failureText = subagentFailureText(cardState.status, run.error),
        onOpenConversation = onOpenConversation,
    )
}

/** Only host-marked collaboration rows use this display; normal user prose is never reclassified. */
@Composable
fun CollaborationMessageCard(message: ChatMessage) {
    val events = remember(message.content, message.roleName) {
        collaborationDisplayMessages(message.content, message.roleName)
    }
    val context = LocalContext.current
    val chatCore = remember(context) {
        ChatRuntimeHolder.getInstance(context.applicationContext).getCore(ChatRuntimeSlot.MAIN)
    }
    val repository = remember(context) { SubagentRunRepository.getInstance(context) }
    val currentChatId by chatCore.currentChatId.collectAsState(initial = null)
    val runsFlow = remember(currentChatId) {
        currentChatId?.let { repository.observeByParentChatId(it) }
    }
    val runs by (runsFlow ?: flowOf(emptyList())).collectAsState(initial = emptyList())
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
            events.forEach { event ->
                val run = findCollaborationRun(runs, event.sender)
                if (run != null) {
                    SubagentRunEventCard(
                        run = run,
                        event = event,
                        chatId = currentChatId,
                        onOpenConversation = {
                            chatCore.switchChat(run.childChatId, scrollToBottom = false)
                        },
                    )
                    return@forEach
                }
                CollaborationAgentMessage(
                    sender = event.sender,
                    kindLabel = stringResource(collaborationKindLabelRes(event.kind)),
                    body = event.body,
                    chatId = currentChatId,
                )
            }
    }
}

/**
 * A collaboration row with no live run to hang off, which is every row inside the subagent's own
 * conversation. It is the card the conversation's own agents wear: a picture, the name of the agent
 * that spoke, what kind of message it is, and the message itself in the floating card a tap opens.
 */
@Composable
private fun CollaborationAgentMessage(
    sender: String,
    kindLabel: String,
    body: String,
    chatId: String?,
) {
    val role = rememberMainAgentRole(sender)
    SubagentAgentCard(
        agentPath = collaborationRowTitle(sender, role?.name),
        statusText = kindLabel,
        identity = remember(sender) { subagentAgentIdentity(sender) },
        statusColor = MaterialTheme.colorScheme.onSurfaceVariant,
        body = body,
        avatarUri = role?.avatarUri,
        openDetailLabelRes = R.string.subagent_message_open_detail,
        chatId = chatId,
    )
}

/**
 * The main agent is the one-segment task path; every named subagent hangs off it.
 */
internal fun collaborationSenderIsMainAgent(sender: String): Boolean =
    sender.trim().trim('/').let { it.isNotEmpty() && !it.contains('/') }

/**
 * Who the main agent speaks as. A row that speaks for the main agent wears the main role card's name
 * and picture the way the conversation's other role cards do; a row from a named subagent keeps that
 * agent's own path and mark, which is what tells a family of agents apart.
 *
 * The chain is the chat's own: the active card's picture with the system cards' built-in picture
 * filled in, or for a character group the group's picture and then its first member's, and only
 * failing all of them the global AI picture.
 */
@Composable
private fun rememberMainAgentRole(sender: String): MainAgentRole? {
    if (!collaborationSenderIsMainAgent(sender)) return null
    val context = LocalContext.current
    val preferences = remember(context) { UserPreferencesManager.getInstance(context) }
    val characterCardManager = remember(context) { CharacterCardManager.getInstance(context) }
    val groupCardManager = remember(context) { CharacterGroupCardManager.getInstance(context) }
    val activePromptManager = remember(context) { ActivePromptManager.getInstance(context) }
    val activePrompt by
        remember(activePromptManager) { activePromptManager.activePromptFlow }
            .collectAsState(
                initial =
                    ActivePrompt.CharacterCard(CharacterCardManager.DEFAULT_CHARACTER_CARD_ID)
            )
    val cardId = (activePrompt as? ActivePrompt.CharacterCard)?.id
    val groupId = (activePrompt as? ActivePrompt.CharacterGroup)?.id
    val card by
        remember(characterCardManager, cardId) {
                cardId?.let { characterCardManager.getCharacterCardFlow(it) } ?: flowOf(null)
            }
            .collectAsState(initial = null)
    val cardAvatarUri by
        remember(preferences, cardId) { avatarUriFlow(preferences, cardId) }
            .collectAsState(initial = null)
    val groupAvatarUri by
        remember(preferences, groupId) {
                groupId?.let { preferences.getAiAvatarForCharacterGroupFlow(it) } ?: flowOf(null)
            }
            .collectAsState(initial = null)
    val group by
        remember(groupCardManager, groupId) {
                groupId?.let { groupCardManager.getCharacterGroupCardFlow(it) } ?: flowOf(null)
            }
            .collectAsState(initial = null)
    val firstMemberCardId =
        remember(group) {
            group?.members?.sortedBy { it.orderIndex }?.firstOrNull()?.characterCardId
        }
    val firstMemberAvatarUri by
        remember(preferences, firstMemberCardId) {
                avatarUriFlow(preferences, firstMemberCardId)
            }
            .collectAsState(initial = null)
    val globalAvatarUri by preferences.customAiAvatarUri.collectAsState(initial = null)
    return MainAgentRole(
        name = card?.name?.takeIf { it.isNotBlank() } ?: group?.name?.takeIf { it.isNotBlank() },
        avatarUri =
            preferredAvatarUri(
                cardAvatarUri = cardAvatarUri,
                groupAvatarUri = groupAvatarUri,
                firstMemberAvatarUri = firstMemberAvatarUri,
                globalAvatarUri = globalAvatarUri,
            ),
    )
}

/** The role card the main agent speaks as: the name the conversation calls it, and its picture. */
internal data class MainAgentRole(val name: String?, val avatarUri: String?)

/**
 * The label a row carries: the role card that speaks it, then the agent path it speaks from. Only the
 * main agent's row is named this way - a subagent's path is already what tells it from its siblings.
 */
internal fun collaborationRowTitle(sender: String, roleName: String?): String =
    roleName
        ?.trim()
        ?.takeIf { it.isNotEmpty() && collaborationSenderIsMainAgent(sender) }
        ?.let { "$it · $sender" }
        ?: sender

/**
 * Which picture wins when the role card, its group, the group's first member and the global one all
 * have one. A blank value is no picture at all, so it never stands in front of one that is.
 */
internal fun preferredAvatarUri(
    cardAvatarUri: String?,
    groupAvatarUri: String?,
    firstMemberAvatarUri: String?,
    globalAvatarUri: String?,
): String? =
    listOf(cardAvatarUri, groupAvatarUri, firstMemberAvatarUri, globalAvatarUri)
        .firstOrNull { !it.isNullOrBlank() }

private fun avatarUriFlow(
    preferences: UserPreferencesManager,
    characterCardId: String?,
): Flow<String?> =
    characterCardId?.let { preferences.getResolvedAiAvatarForCharacterCardFlow(it) } ?: flowOf(null)
