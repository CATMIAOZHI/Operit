package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.OrbitSettings
import com.ai.assistance.operit.data.preferences.OrbitSideChat
import com.ai.assistance.operit.features.orbit.OrbitCompanion
import kotlinx.coroutines.launch

/**
 * Orbit's chats: the main chat and its side chats, like Muse's chat list. A side chat is a
 * separate conversation about one topic; long press one to rename or delete it.
 *
 * [onSelect] only switches the chat; the sheet slides away by itself and then calls [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitChatsSheet(
    orbit: OrbitProfile,
    currentChatId: String,
    unread: Set<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<OrbitSideChat?>(null) }
    var deleting by remember { mutableStateOf<OrbitSideChat?>(null) }
    var busy by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }

    /** Switches to [chatId] right away, then lets the sheet slide down before it goes. */
    fun open(chatId: String) {
        if (closing) return
        closing = true
        onSelect(chatId)
        scope.launch {
            try {
                sheetState.hide()
            } finally {
                onDismiss()
            }
        }
    }

    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.orbit_chats_failed), Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }

    // Taps outside wait for a running change or the closing slide to finish.
    ModalBottomSheet(onDismissRequest = { if (!busy && !closing) onDismiss() }, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.orbit_chats_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            ChatRow(
                icon = "message-circle", title = stringResource(R.string.orbit_chats_main),
                selected = currentChatId == orbit.chatId, unread = orbit.chatId in unread,
                onClick = { open(orbit.chatId) },
            )
            orbit.sideChats.sortedByDescending { it.createdAt }.forEach { side ->
                var menu by remember(side.chatId) { mutableStateOf(false) }
                Box {
                    ChatRow(
                        icon = "message-square", title = side.title, selected = currentChatId == side.chatId,
                        unread = side.chatId in unread,
                        onClick = { open(side.chatId) }, onLongClick = { menu = true },
                        longClickLabel = stringResource(R.string.orbit_library_more_actions),
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = side })
                        DropdownMenuItem(text = { Text(stringResource(R.string.orbit_chats_delete)) }, onClick = { menu = false; deleting = side })
                    }
                }
            }
            if (orbit.sideChats.isNotEmpty()) {
                Text(
                    stringResource(R.string.orbit_chats_long_press), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            val full = orbit.sideChats.size >= OrbitSettings.MAX_SIDE_CHATS
            FilledTonalButton(onClick = { creating = true }, enabled = !full && !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(orbitIcon("plus"), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.orbit_chats_new))
            }
            Text(
                if (full) stringResource(R.string.orbit_chats_limit, OrbitSettings.MAX_SIDE_CHATS)
                else stringResource(R.string.orbit_chats_new_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (creating) {
        TitleDialog(
            title = stringResource(R.string.orbit_chats_new), initial = "", busy = busy,
            onDismiss = { if (!busy) creating = false },
            onSave = { text ->
                act {
                    val id = OrbitCompanion.createSideChat(context, orbit.id, text)
                    creating = false
                    open(id)
                }
            },
        )
    }
    renaming?.let { side ->
        TitleDialog(
            title = stringResource(R.string.rename), initial = side.title, busy = busy,
            onDismiss = { if (!busy) renaming = null },
            onSave = { text -> act { OrbitCompanion.renameSideChat(context, orbit.id, side.chatId, text); renaming = null } },
        )
    }
    deleting?.let { side ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text(stringResource(R.string.orbit_chats_delete)) },
            text = { Text(stringResource(R.string.orbit_chats_delete_confirm, side.title)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    // Show the main chat first, so the screen never sits on a chat being deleted.
                    // The delete itself finishes even if this sheet closes.
                    if (currentChatId == side.chatId) onSelect(orbit.chatId)
                    act {
                        OrbitCompanion.deleteSideChat(context, orbit.id, side.chatId)
                        deleting = null
                    }
                }) {
                    if (busy) BusyMark()
                    Text(stringResource(R.string.delete), color = if (busy) Color.Unspecified else MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun BusyMark() {
    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    Spacer(Modifier.width(8.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    icon: String,
    title: String,
    selected: Boolean,
    unread: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val unreadLabel = stringResource(R.string.orbit_chats_unread)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longClickLabel)
            .semantics { this.selected = selected }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Same round icon as the other Orbit lists.
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(orbitIcon(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (unread && !selected) {
            Box(
                Modifier.padding(start = 8.dp).size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                    .semantics { contentDescription = unreadLabel },
            )
        }
        // The row already says it is selected.
        if (selected) Icon(orbitIcon("check"), null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TitleDialog(title: String, initial: String, busy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { if (it.length <= OrbitSettings.MAX_SIDE_CHAT_TITLE) text = it }, singleLine = true, enabled = !busy,
                label = { Text(stringResource(R.string.orbit_chats_title_label)) },
                placeholder = { Text(stringResource(R.string.orbit_chats_title_hint)) },
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && !busy, onClick = { onSave(text.trim()) }) {
                if (busy) BusyMark()
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
