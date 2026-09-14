package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.ui.common.markdown.LocalResponseProcessExpanded
import com.ai.assistance.operit.ui.common.markdown.ResponseActivityHeader

internal data class ResponseProcessGroup(
    val key: Long,
    val firstIndex: Int,
    val finalIndex: Int,
    val durationMs: Long,
)

/** Agent input cards are part of the AI process; human steering stays outside the fold. */
internal fun responseProcessGroups(messages: List<ChatMessage>): Map<Int, ResponseProcessGroup> {
    val result = mutableMapOf<Int, ResponseProcessGroup>()
    val pending = mutableListOf<Int>()
    messages.forEachIndexed { index, message ->
        if (message.sender != "ai") return@forEachIndexed
        if (pending.isNotEmpty() && messages[pending.first()].sentAt != message.sentAt) {
            pending.clear()
        }
        if (message.sentAt > 0L &&
            message.displayMode == ChatMessageDisplayMode.ASSISTANT_INTERMEDIATE
        ) {
            pending += index
        } else {
            if (pending.isNotEmpty() &&
                message.displayMode == ChatMessageDisplayMode.NORMAL &&
                message.completedAt > 0L && message.contentStream == null
            ) {
                val group = ResponseProcessGroup(
                    key = message.sentAt,
                    firstIndex = pending.first(),
                    finalIndex = index,
                    durationMs = message.waitDurationMs + message.outputDurationMs,
                )
                (pending.first()..index).forEach { member ->
                    if (messages[member].sender == "ai" ||
                        messages[member].displayMode.isCollaborationEvent
                    ) {
                        result[member] = group
                    }
                }
            }
            pending.clear()
        }
    }
    return result
}

internal class ResponseProcessState(
    val groups: Map<Int, ResponseProcessGroup>,
    val expandedKeys: List<Long>,
    val toggle: (Long) -> Unit,
    val expand: (Long) -> Unit,
)

@Composable
internal fun rememberResponseProcessState(
    messages: List<ChatMessage>,
    chatId: String?,
    enabled: Boolean = true,
): ResponseProcessState {
    val context = LocalContext.current
    val preferences = remember(context) { DisplayPreferencesManager.getInstance(context) }
    val collapse by preferences.collapseCompletedProcess.collectAsState(initial = true)
    var expandedKeys by rememberSaveable(chatId) { mutableStateOf(emptyList<Long>()) }
    val snapshot = messages.toList()
    val groups = remember(snapshot, collapse, enabled) {
        if (collapse && enabled) responseProcessGroups(snapshot) else emptyMap()
    }
    return ResponseProcessState(
        groups, expandedKeys,
        toggle = { key ->
            expandedKeys = if (key in expandedKeys) expandedKeys - key else expandedKeys + key
        },
        expand = { key -> if (key !in expandedKeys) expandedKeys = expandedKeys + key },
    )
}

@Composable
internal fun ResponseProcessMessage(
    state: ResponseProcessState,
    index: Int,
    textColor: Color,
    content: @Composable () -> Unit,
) {
    val group = state.groups[index]
    if (group == null) {
        Column { content() }
        return
    }
    val expanded = group.key in state.expandedKeys
    Column {
        if (index == group.firstIndex) {
            CompositionLocalProvider(LocalResponseMessageSection provides ResponseMessageSection.HEADER) {
                content()
            }
            ResponseActivityHeader(
                durationMs = group.durationMs,
                expanded = expanded,
                textColor = textColor,
                onClick = { state.toggle(group.key) },
            )
        }
        if (expanded || index == group.finalIndex) {
            CompositionLocalProvider(
                LocalResponseProcessExpanded provides expanded,
                LocalResponseMessageSection provides ResponseMessageSection.BODY,
            ) {
                content()
            }
        }
    }
}
