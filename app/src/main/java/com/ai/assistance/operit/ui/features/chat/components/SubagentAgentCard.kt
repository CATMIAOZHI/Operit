package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * A stable badge per agent path, so a parent conversation with several children stays scannable
 * by sight the way each named Codex subagent carries its own mark.
 */
internal data class SubagentAgentIdentity(val icon: ImageVector, val accent: Color)

private val subagentAgentIcons = listOf<ImageVector>(
    Icons.Default.AutoAwesome,
    Icons.Default.Bolt,
    Icons.Default.Science,
    Icons.Default.BugReport,
    Icons.Default.Psychology,
    Icons.Default.RocketLaunch,
    Icons.Default.Hub,
    Icons.Default.TravelExplore,
)

private val subagentAgentAccents = listOf(
    Color(0xFFF59E0B),
    Color(0xFF8B5CF6),
    Color(0xFFEF4444),
    Color(0xFF10B981),
    Color(0xFF3B82F6),
    Color(0xFF06B6D4),
)

internal fun subagentAgentIdentity(seed: String): SubagentAgentIdentity {
    // String.hashCode is fully specified, so the same agent keeps its badge across processes.
    val hash = agentBadgeSeed(seed).hashCode()
    return SubagentAgentIdentity(
        icon = subagentAgentIcons[Math.floorMod(hash, subagentAgentIcons.size)],
        accent =
            subagentAgentAccents[
                Math.floorMod(hash / subagentAgentIcons.size, subagentAgentAccents.size)
            ],
    )
}

/**
 * The part of an agent reference that names the agent: `worker` for both `worker` and
 * `/root/worker`. A run is still being created when its spawn row first appears, so the row only
 * knows the bare task name at that moment and only learns the canonical path afterwards; hashing
 * the name keeps the badge from changing in between.
 */
internal fun agentBadgeSeed(seed: String): String =
    seed.trim().trimEnd('/').substringAfterLast('/').ifEmpty { "subagent" }

/**
 * Follows one chat's slice of a per-chat flow, so unrelated chats' tool activity no longer
 * recomposes every collaboration row in the conversation.
 */
@Composable
internal fun <T> perChatValue(flow: Flow<Map<String, T>>, chatId: String?): T? =
    remember(flow, chatId) { flow.map { state -> chatId?.let(state::get) }.distinctUntilChanged() }
        .collectAsState(initial = null)
        .value

/** Lifecycle states a named subagent can show in the conversation. */
internal enum class SubagentCardStatus { QUEUED, STARTED, CALLING_TOOL, COMPLETED, FAILED, CANCELLED }

internal data class SubagentCardState(
    val status: SubagentCardStatus,
    val toolName: String? = null,
    /** Only known when the queue is visible to the caller; null means "queued, position unknown". */
    val queuePosition: Int? = null,
    val toolCount: Int = 0,
    val roundCount: Int = 0,
)

internal fun subagentCardState(
    runStatus: String?,
    toolCount: Int = 0,
    roundCount: Int = 0,
    currentTool: String? = null,
    queuePosition: Int? = null,
): SubagentCardState {
    val safeToolCount = toolCount.coerceAtLeast(0)
    val safeRoundCount = roundCount.coerceAtLeast(0)
    return when (runStatus?.trim()?.uppercase()) {
        "QUEUED" ->
            SubagentCardState(
                SubagentCardStatus.QUEUED,
                queuePosition = queuePosition?.coerceAtLeast(1),
                toolCount = safeToolCount,
                roundCount = safeRoundCount,
            )
        "RUNNING" ->
            SubagentCardState(
                if (currentTool.isNullOrBlank()) SubagentCardStatus.STARTED else SubagentCardStatus.CALLING_TOOL,
                toolName = currentTool?.takeIf { it.isNotBlank() },
                toolCount = safeToolCount,
                roundCount = safeRoundCount,
            )
        "COMPLETED" ->
            SubagentCardState(
                SubagentCardStatus.COMPLETED,
                toolCount = safeToolCount,
                roundCount = safeRoundCount,
            )
        "FAILED", "INTERRUPTED" ->
            SubagentCardState(
                SubagentCardStatus.FAILED,
                toolCount = safeToolCount,
                roundCount = safeRoundCount,
            )
        "CANCELLED" ->
            SubagentCardState(
                SubagentCardStatus.CANCELLED,
                toolCount = safeToolCount,
                roundCount = safeRoundCount,
            )
        // CREATED and anything unknown read as "the subagent has begun working".
        else ->
            SubagentCardState(
                SubagentCardStatus.STARTED,
                toolCount = safeToolCount,
                roundCount = safeRoundCount,
            )
    }
}

/** One stats fragment; kept as a resource handle so the layout stays free of formatting logic. */
internal data class SubagentStatLine(val labelResId: Int, val args: List<Any>)

internal fun subagentStatLines(
    durationText: String?,
    toolCount: Int,
    roundCount: Int,
): List<SubagentStatLine> =
    buildList {
        durationText?.takeIf { it.isNotBlank() }?.let {
            add(SubagentStatLine(R.string.subagent_card_elapsed, listOf(it)))
        }
        if (toolCount > 0) {
            add(SubagentStatLine(R.string.subagent_card_tool_count, listOf(toolCount)))
        }
        if (roundCount > 0) {
            add(SubagentStatLine(R.string.subagent_card_round_count, listOf(roundCount)))
        }
    }

@Composable
internal fun subagentCardStatusText(state: SubagentCardState): String =
    when (state.status) {
        SubagentCardStatus.QUEUED ->
            state.queuePosition
                ?.let { stringResource(R.string.subagent_status_queued, it) }
                ?: stringResource(R.string.subagent_status_queued_pending)
        SubagentCardStatus.STARTED -> stringResource(R.string.subagent_status_started)
        SubagentCardStatus.CALLING_TOOL ->
            stringResource(R.string.subagent_status_calling_tool, state.toolName.orEmpty())
        SubagentCardStatus.COMPLETED -> stringResource(R.string.subagent_status_completed)
        SubagentCardStatus.FAILED -> stringResource(R.string.subagent_status_error)
        SubagentCardStatus.CANCELLED -> stringResource(R.string.subagent_status_cancelled)
    }

@Composable
internal fun subagentCardStatusColor(status: SubagentCardStatus): Color =
    when (status) {
        SubagentCardStatus.FAILED -> MaterialTheme.colorScheme.error
        SubagentCardStatus.QUEUED -> MaterialTheme.colorScheme.tertiary
        SubagentCardStatus.STARTED, SubagentCardStatus.CALLING_TOOL ->
            MaterialTheme.colorScheme.primary
        SubagentCardStatus.COMPLETED,
        SubagentCardStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
internal fun subagentCardStatsText(
    durationText: String?,
    toolCount: Int,
    roundCount: Int,
): String? {
    val lines = subagentStatLines(durationText, toolCount, roundCount)
    if (lines.isEmpty()) return null
    val context = LocalContext.current
    return lines.joinToString(" · ") { line ->
        context.getString(line.labelResId, *line.args.toTypedArray())
    }
}

/**
 * The shared subagent card: badge, agent path, live status, run statistics and an optional body
 * (a reply, task or note) revealed on tap.
 */
@Composable
internal fun SubagentAgentCard(
    agentPath: String,
    statusText: String,
    statsText: String?,
    identity: SubagentAgentIdentity,
    statusColor: Color,
    modifier: Modifier = Modifier,
    body: String? = null,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onOpenConversation: (() -> Unit)? = null,
) {
    val expandable = !body.isNullOrBlank() && onToggle != null
    val expansionState =
        stringResource(
            if (expanded) R.string.subagent_event_expanded else R.string.subagent_event_collapsed,
        )
    val openConversationLabel = stringResource(R.string.subagent_open_conversation)
    val rowModifier =
        when {
            expandable -> Modifier.clickable(role = Role.Button, onClick = onToggle!!)
            onOpenConversation != null -> Modifier.clickable(role = Role.Button, onClick = onOpenConversation)
            else -> Modifier
        }
    val rowSemantics =
        when {
            expandable -> Modifier.semantics { stateDescription = expansionState }
            // The whole card opens the child conversation here, so it needs an accessible name.
            onOpenConversation != null -> Modifier.semantics { contentDescription = openConversationLabel }
            else -> Modifier
        }

    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .then(rowSemantics)
            .then(rowModifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(identity.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = identity.icon,
                    contentDescription = null,
                    tint = identity.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = agentPath,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (expandable) {
                Icon(
                    imageVector =
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (onOpenConversation != null) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        statsText?.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                text = text,
                modifier = Modifier.padding(start = 36.dp, top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            body?.takeIf { it.isNotBlank() }?.let { text ->
                Spacer(modifier = Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        text = text,
                        modifier = Modifier.padding(start = 36.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            onOpenConversation?.let { open ->
                TextButton(onClick = open, modifier = Modifier.padding(start = 28.dp)) {
                    Text(
                        text = stringResource(R.string.subagent_open_conversation),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
