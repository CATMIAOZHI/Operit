package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitProfile
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** One chat the developer view can show. */
@Immutable
private class DevChat(val id: String, val title: String, val icon: String, val time: Long)

/** The chats in the sheet, by group: the main chat, pinned and other side chats, then archived ones. */
private class DevChatGroups(val main: DevChat, val side: List<DevChat>, val archived: List<DevChat>)

private fun groups(orbit: OrbitProfile, mainTitle: String): DevChatGroups {
    val open = orbit.sideChats.filterNot { it.archived }
        .sortedWith(compareByDescending<com.ai.assistance.operit.data.preferences.OrbitSideChat> { it.pinnedAt }
            .thenByDescending { it.createdAt })
        .map { DevChat(it.chatId, it.title, if (it.pinned) "pin" else "message-circle", it.createdAt) }
    val archived = orbit.sideChats.filter { it.archived }.sortedByDescending { it.createdAt }
        .map { DevChat(it.chatId, it.title, "archive", it.createdAt) }
    return DevChatGroups(DevChat(orbit.chatId, mainTitle, "house", 0L), open, archived)
}

/**
 * Picks which of Orbit's chats the developer view shows: a sheet with a search box, the main chat
 * first, then side chats (pinned on top) and archived ones, so a long list stays easy to scan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitDevChatSheet(
    orbit: OrbitProfile,
    currentChatId: String,
    /** Subagents working under Orbit's chats, newest first. */
    subagents: List<com.ai.assistance.operit.data.model.SubagentRunEntity>,
    onSelect: (String) -> Unit,
    onSelectSubagent: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val mainTitle = stringResource(R.string.orbit_chats_main)
    val all = remember(orbit, mainTitle) { groups(orbit, mainTitle) }
    val agents = remember(subagents) {
        subagents.map { DevChat(it.childChatId, OrbitSubagents.nameOf(it), "bot", it.createdAt) }
    }
    var query by remember { mutableStateOf("") }
    val needle = query.trim().lowercase()
    fun List<DevChat>.match() = if (needle.isEmpty()) this else filter { it.title.lowercase().contains(needle) }
    val side = all.side.match()
    val archived = all.archived.match()
    val agentsShown = agents.match()
    val showMain = needle.isEmpty() || all.main.title.lowercase().contains(needle)
    fun pick(id: String, subagent: Boolean = false) {
        // The sheet slides down first, then the chat changes under it.
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            if (id != currentChatId) if (subagent) onSelectSubagent(id) else onSelect(id)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Text(
                stringResource(R.string.orbit_dev_view_switch_chat),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            )
            // A search box only helps once there are enough chats to look for one.
            if (orbit.sideChats.size + agents.size > 5) {
                Box(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
                    SearchPill(query, { query = it }, placeholder = stringResource(R.string.orbit_dev_chats_search))
                }
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            ) {
                if (showMain) item(key = "main") { DevChatRow(all.main, all.main.id == currentChatId) { pick(all.main.id) } }
                if (side.isNotEmpty()) {
                    item(key = "side-title") { DevChatSection(stringResource(R.string.orbit_chats_side_section)) }
                    items(side, key = { it.id }) { chat -> DevChatRow(chat, chat.id == currentChatId) { pick(chat.id) } }
                }
                if (archived.isNotEmpty()) {
                    item(key = "archived-title") { DevChatSection(stringResource(R.string.orbit_chats_archived_title)) }
                    items(archived, key = { it.id }) { chat -> DevChatRow(chat, chat.id == currentChatId) { pick(chat.id) } }
                }
                if (agentsShown.isNotEmpty()) {
                    item(key = "agents-title") { DevChatSection(stringResource(R.string.orbit_dev_chats_subagents)) }
                    items(agentsShown, key = { "agent:" + it.id }) { chat ->
                        DevChatRow(chat, chat.id == currentChatId) { pick(chat.id, subagent = true) }
                    }
                }
                if (!showMain && side.isEmpty() && archived.isEmpty() && agentsShown.isEmpty()) {
                    item(key = "none") {
                        Text(
                            stringResource(R.string.orbit_dev_chats_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DevChatSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
    )
}

private val dateFormat: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

@Composable
private fun DevChatRow(chat: DevChat, current: Boolean, onClick: () -> Unit) {
    val date = remember(chat.time) { if (chat.time > 0) dateFormat.format(Date(chat.time)) else null }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .then(if (current) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = current }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) { Icon(orbitIcon(chat.icon), contentDescription = null, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(chat.title, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (date != null) {
                Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (current) Icon(orbitIcon("check"), contentDescription = null, modifier = Modifier.size(18.dp))
    }
}
