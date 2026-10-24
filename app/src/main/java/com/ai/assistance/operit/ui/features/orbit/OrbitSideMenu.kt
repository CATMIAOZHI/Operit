package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.OrbitSettings
import com.ai.assistance.operit.data.preferences.OrbitSideChat
import com.ai.assistance.operit.data.preferences.canAddSideChat
import com.ai.assistance.operit.data.preferences.openSideChats
import com.ai.assistance.operit.features.orbit.OrbitCompanion
import com.ai.assistance.operit.ui.features.chat.components.TranscriptJumpHost
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orbit's own side menu, like Muse's: it slides in from the left when the round menu button is
 * tapped (never by a swipe), with the Orbit's name, a button back to Operit, the main chat, the
 * side chats, and settings, search and "new side chat" at the bottom. Long press a side chat to
 * rename, pin, archive or delete it; the archive button by "Side chats" lists the archived ones.
 * The search box opens a full-screen search of everything said in the main chat and side chats.
 * Tapping outside, Back, or a swipe to the left closes it.
 */
@Composable
internal fun OrbitSideMenu(
    visible: Boolean,
    orbit: OrbitProfile,
    currentChatId: String,
    unread: Set<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onLeaveOrbit: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<OrbitSideChat?>(null) }
    var deleting by remember { mutableStateOf<OrbitSideChat?>(null) }
    var busy by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var archiveOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // How far the panel was dragged to the left, in pixels (0 or less).
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var panelPx by remember { mutableFloatStateOf(0f) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(visible) {
        // The chat's text box sits under the menu: drop its focus so typing never goes there.
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        if (visible) dragOffset = 0f
    }

    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) OrbitMotion.PUSH_MS else OrbitMotion.DISMISS_MS, easing = OrbitMotion.Easing),
        label = "orbitSideMenu",
    )
    // Search and the archive list start fresh only once the panel is fully gone, so nothing moves
    // while it slides out.
    val gone = progress == 0f
    LaunchedEffect(gone) {
        if (gone) {
            query = ""
            searching = false
            archiveOpen = false
        }
    }
    // The back swipe slides the panel away under the finger, like a drag; let go early and it returns.
    // The search page and the archive list take back first while they are open.
    PredictiveBackHandler(enabled = visible && !searching && !archiveOpen) { events ->
        try {
            events.collect { event -> dragOffset = -panelPx * event.progress }
            val rest = (OrbitMotion.DISMISS_MS * (1f + dragOffset / panelPx.coerceAtLeast(1f))).toInt().coerceAtLeast(90)
            animate(dragOffset, -panelPx, animationSpec = tween(rest, easing = OrbitMotion.Easing)) { value, _ ->
                dragOffset = value
            }
            onDismiss()
        } catch (e: kotlinx.coroutines.CancellationException) {
            scope.launch {
                animate(dragOffset, 0f, animationSpec = tween(220, easing = OrbitMotion.Easing)) { value, _ ->
                    dragOffset = value
                }
            }
            throw e
        }
    }

    fun open(chatId: String) {
        onSelect(chatId)
        onDismiss()
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
                Toast.makeText(context, resources.getString(R.string.orbit_chats_failed), Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }

    fun newSideChat() {
        when {
            orbit.openSideChats.size >= OrbitSettings.MAX_SIDE_CHATS -> Toast.makeText(
                context, resources.getString(R.string.orbit_chats_limit, OrbitSettings.MAX_SIDE_CHATS), Toast.LENGTH_SHORT,
            ).show()
            !orbit.canAddSideChat -> Toast.makeText(
                context, resources.getString(R.string.orbit_chats_limit_all, OrbitSettings.MAX_ALL_SIDE_CHATS), Toast.LENGTH_SHORT,
            ).show()
            // No title to type: it is named after the first message, like any chat.
            else -> act {
                val id = OrbitCompanion.createSideChat(context, orbit.id, resources.getString(R.string.new_conversation))
                open(id)
            }
        }
    }

    fun setArchived(side: OrbitSideChat, archived: Boolean) {
        if (!archived && orbit.openSideChats.size >= OrbitSettings.MAX_SIDE_CHATS) {
            Toast.makeText(
                context, resources.getString(R.string.orbit_chats_unarchive_limit, OrbitSettings.MAX_SIDE_CHATS), Toast.LENGTH_SHORT,
            ).show()
            return
        }
        // An archived chat leaves the menu, so the screen goes back to the main chat first.
        if (archived && currentChatId == side.chatId) onSelect(orbit.chatId)
        act { OrbitCompanion.setSideChatArchived(context, orbit.id, side.chatId, archived) }
    }

    if (progress > 0f) {
        // Under the full-screen search, neither the menu nor its close scrim is read out or reachable.
        BoxWithConstraints(Modifier.fillMaxSize().then(if (searching) Modifier.clearAndSetSemantics { } else Modifier)) {
            val panelWidth = min(maxWidth * 0.84f, 380.dp)
            val panelWidthPx = with(LocalDensity.current) { panelWidth.toPx() }
            SideEffect { panelPx = panelWidthPx }
            Box(
                Modifier.fillMaxSize()
                    // Read while drawing, so a drag or back swipe fades it without recomposing.
                    .drawBehind {
                        val shown = (1f + dragOffset / panelWidthPx).coerceIn(0f, 1f)
                        drawRect(Color.Black.copy(alpha = 0.28f * progress * shown))
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = stringResource(R.string.close),
                        onClick = onDismiss,
                    )
            )
            val menuTitle = stringResource(R.string.menu)
            Surface(
                // A step apart from the page, so it still reads as a panel in dark mode.
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                shadowElevation = 8.dp,
                modifier = Modifier.width(panelWidth).fillMaxHeight()
                    .semantics { paneTitle = menuTitle; isTraversalGroup = true }
                    .graphicsLayer { translationX = -size.width * (1f - progress) + dragOffset }
                    .draggable(
                        state = rememberDraggableState { delta -> dragOffset = (dragOffset + delta).coerceAtMost(0f) },
                        orientation = Orientation.Horizontal,
                        // The archive list goes back on its own; a drag there must not close the whole menu.
                        enabled = !archiveOpen && !searching,
                        onDragStopped = {
                            if (dragOffset < -panelWidthPx * 0.25f) {
                                onDismiss()
                            } else {
                                animate(dragOffset, 0f, animationSpec = tween(220, easing = OrbitMotion.Easing)) { value, _ ->
                                    dragOffset = value
                                }
                            }
                        },
                    ),
            ) {
                // Under the archive list or the search page, the menu is not read out.
                Box(Modifier.fillMaxSize().then(if (archiveOpen || searching) Modifier.clearAndSetSemantics { } else Modifier)) {
                    MenuContent(
                        orbit = orbit, currentChatId = currentChatId, unread = unread, busy = busy,
                        onOpen = ::open,
                        onRename = { renaming = it },
                        onTogglePin = { side -> act { OrbitCompanion.setSideChatPinned(context, orbit.id, side.chatId, !side.pinned) } },
                        onArchive = { setArchived(it, true) },
                        onDelete = { deleting = it },
                        onNew = ::newSideChat,
                        onSearch = { searching = true },
                        onOpenArchive = { archiveOpen = true },
                        onOpenSettings = {
                            onDismiss()
                            onOpenSettings()
                        },
                        onLeaveOrbit = {
                            onDismiss()
                            onLeaveOrbit()
                        },
                    )
                }
                // The archived side chats slide in over the menu, inside the panel.
                OrbitCoverPage(visible = archiveOpen && visible, onDismiss = { archiveOpen = false }) {
                    ArchivePage(
                        sides = orbit.sideChats.filter { it.archived }.sortedByDescending { it.createdAt },
                        currentChatId = currentChatId, unread = unread,
                        onBack = { archiveOpen = false },
                        onOpen = ::open,
                        onUnarchive = { setArchived(it, false) },
                        onDelete = { deleting = it },
                    )
                }
            }
        }
    }
    // The search covers the whole screen and fades in where the menu is, like Muse's.
    OrbitCoverPage(visible = searching && visible, onDismiss = { searching = false }, fade = true) {
        OrbitSearchPage(
            orbit = orbit, active = searching && visible, query = query, onQueryChange = { query = it },
            onOpenHit = { hit ->
                TranscriptJumpHost.requestMessage(hit.chatId, hit.timestamp)
                open(hit.chatId)
            },
            onOpenSettings = {
                onDismiss()
                onOpenSettings()
            },
            onLeaveOrbit = {
                onDismiss()
                onLeaveOrbit()
            },
            onClose = { searching = false },
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
        OrbitDialog(
            onDismissRequest = { if (!busy) deleting = null },
            dismissEnabled = !busy,
            title = stringResource(R.string.orbit_chats_delete),
            text = { Text(stringResource(R.string.orbit_chats_delete_confirm, side.title)) },
            confirmButton = {
                OrbitDialogAction(enabled = !busy, destructive = true, onClick = {
                    // Show the main chat first, so the screen never sits on a chat being deleted.
                    if (currentChatId == side.chatId) onSelect(orbit.chatId)
                    act {
                        OrbitCompanion.deleteSideChat(context, orbit.id, side.chatId)
                        deleting = null
                    }
                }) {
                    if (busy) BusyMark()
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { OrbitDialogAction(enabled = !busy, secondary = true, onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MenuContent(
    orbit: OrbitProfile,
    currentChatId: String,
    unread: Set<String>,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onRename: (OrbitSideChat) -> Unit,
    onTogglePin: (OrbitSideChat) -> Unit,
    onArchive: (OrbitSideChat) -> Unit,
    onDelete: (OrbitSideChat) -> Unit,
    onNew: () -> Unit,
    onSearch: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenSettings: () -> Unit,
    onLeaveOrbit: () -> Unit,
) {
    // Pinned side chats first, the newest pin on top; then the rest, newest first.
    val sides = remember(orbit.sideChats) {
        orbit.openSideChats.sortedWith(
            compareByDescending<OrbitSideChat> { it.pinnedAt }.thenByDescending { it.createdAt },
        )
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                orbit.name.ifBlank { stringResource(R.string.nav_orbit) },
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            OrbitBackToOperitPill(onLeaveOrbit)
        }
        Spacer(Modifier.height(20.dp))
        ChatRow(
            icon = "house", title = stringResource(R.string.orbit_chats_main),
            selected = currentChatId == orbit.chatId, unread = orbit.chatId in unread,
            onClick = { onOpen(orbit.chatId) },
        )
        // "Side chats", with the archive button on the right, like Muse's.
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.orbit_chats_side_section),
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val archiveLabel = stringResource(R.string.orbit_chats_archived_open)
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = archiveLabel, onClick = onOpenArchive)
                    .semantics { contentDescription = archiveLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(orbitIcon("archive"), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (sides.isNotEmpty()) {
            Text(
                stringResource(R.string.orbit_chats_long_press), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (sides.isEmpty()) {
                EmptySideChats(onNew = onNew, enabled = !busy)
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(sides, key = { it.chatId }) { side ->
                        var menu by remember(side.chatId) { mutableStateOf(false) }
                        Box(Modifier.animateItem(fadeInSpec = tween(220), placementSpec = tween(320, easing = OrbitMotion.Easing), fadeOutSpec = tween(180))) {
                            ChatRow(
                                icon = "message-circle", title = side.title, selected = currentChatId == side.chatId,
                                unread = side.chatId in unread, pinned = side.pinned, highlighted = menu,
                                onClick = { onOpen(side.chatId) }, onLongClick = { menu = true },
                                longClickLabel = stringResource(R.string.orbit_library_more_actions),
                            )
                            SideChatActions(
                                expanded = menu, createdAt = side.createdAt, onDismiss = { menu = false },
                                actions = listOf(
                                    MenuAction("pencil", stringResource(R.string.rename)) { onRename(side) },
                                    if (side.pinned) MenuAction("pin-off", stringResource(R.string.orbit_chats_unpin)) { onTogglePin(side) }
                                    else MenuAction("pin", stringResource(R.string.orbit_chats_pin)) { onTogglePin(side) },
                                    MenuAction("archive", stringResource(R.string.orbit_chats_archive)) { onArchive(side) },
                                    MenuAction("trash-2", stringResource(R.string.delete), danger = true) { onDelete(side) },
                                ),
                            )
                        }
                    }
                }
            }
        }
        // Settings, search and new side chat, like the bottom of Muse's menu.
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OrbitMenuRoundAction("settings", stringResource(R.string.settings), onOpenSettings)
            val searchLabel = stringResource(R.string.orbit_chats_search_label)
            // Looks like the search box; tapping it opens the full-screen search.
            Row(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .clickable(role = Role.Button, onClickLabel = searchLabel, onClick = onSearch)
                    .semantics { contentDescription = searchLabel }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(orbitIcon("search"), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.search), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            OrbitMenuRoundAction("square-pen", stringResource(R.string.orbit_chats_new), onNew, enabled = !busy)
        }
    }
}

/** The archived side chats, newest first. Long press one to bring it back or delete it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchivePage(
    sides: List<OrbitSideChat>,
    currentChatId: String,
    unread: Set<String>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onUnarchive: (OrbitSideChat) -> Unit,
    onDelete: (OrbitSideChat) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OrbitCircleButton(orbitIcon("chevron-left"), stringResource(R.string.back), onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.orbit_chats_archived_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (sides.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(orbitIcon("archive"), null, Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.orbit_chats_archived_empty), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.orbit_chats_archived_empty_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(sides, key = { it.chatId }) { side ->
                    var menu by remember(side.chatId) { mutableStateOf(false) }
                    Box(Modifier.animateItem(fadeInSpec = tween(220), placementSpec = tween(320, easing = OrbitMotion.Easing), fadeOutSpec = tween(180))) {
                        ChatRow(
                            icon = "message-circle", title = side.title, selected = currentChatId == side.chatId,
                            unread = side.chatId in unread, highlighted = menu,
                            onClick = { onOpen(side.chatId) }, onLongClick = { menu = true },
                            longClickLabel = stringResource(R.string.orbit_library_more_actions),
                        )
                        SideChatActions(
                            expanded = menu, createdAt = side.createdAt, onDismiss = { menu = false },
                            actions = listOf(
                                MenuAction("archive-restore", stringResource(R.string.orbit_chats_unarchive)) { onUnarchive(side) },
                                MenuAction("trash-2", stringResource(R.string.delete), danger = true) { onDelete(side) },
                            ),
                        )
                    }
                }
            }
        }
    }
}

private class MenuAction(val icon: String, val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/** The long-press menu of a side chat: when it was started, then the actions. */
@Composable
private fun SideChatActions(expanded: Boolean, createdAt: Long, actions: List<MenuAction>, onDismiss: () -> Unit) {
    OrbitDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(8.dp, 6.dp),
    ) {
        val started = remember(createdAt) {
            val locale = Locale.getDefault()
            SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMdjmm"), locale)
                .format(Date(createdAt))
        }
        Text(
            started, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
        )
        actions.forEach { action ->
            val tint = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            DropdownMenuItem(
                text = { Text(action.label, style = MaterialTheme.typography.titleMedium, color = tint) },
                leadingIcon = { Icon(orbitIcon(action.icon), null, Modifier.size(24.dp), tint = tint) },
                onClick = {
                    onDismiss()
                    action.onClick()
                },
                contentPadding = PaddingValues(horizontal = 24.dp),
                modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 210.dp),
            )
        }
    }
}

@Composable
private fun EmptySideChats(onNew: () -> Unit, enabled: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(orbitIcon("messages-square"), null, Modifier.size(32.dp))
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onNew, enabled = enabled) {
            Text(stringResource(R.string.orbit_chats_empty_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            stringResource(R.string.orbit_chats_empty_hint), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

/** "Back to Operit", where Muse has "Invite"; on the menu and the search page. */
@Composable
internal fun OrbitBackToOperitPill(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = 2.dp,
    ) {
        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(orbitIcon("arrow-left"), null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.orbit_menu_back_to_operit), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A round button at the bottom of the menu and the search page. */
@Composable
internal fun OrbitMenuRoundAction(icon: String, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        modifier = Modifier.size(52.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(orbitIcon(icon), contentDescription = label, modifier = Modifier.size(22.dp))
        }
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
    pinned: Boolean = false,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val unreadLabel = stringResource(R.string.orbit_chats_unread)
    val pinnedLabel = stringResource(R.string.orbit_chats_pinned)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(50))
            .background(if (selected || highlighted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longClickLabel)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(orbitIcon(icon), null, Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (pinned) {
            Icon(
                orbitIcon("pin"), null,
                Modifier.padding(start = 8.dp).size(16.dp).semantics { contentDescription = pinnedLabel },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (unread) {
            Box(
                Modifier.padding(start = 8.dp).size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                    .semantics { contentDescription = unreadLabel },
            )
        }
    }
}

@Composable
private fun TitleDialog(title: String, initial: String, busy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    OrbitDialog(
        onDismissRequest = onDismiss,
        dismissEnabled = !busy,
        title = title,
        text = {
            OutlinedTextField(
                text, { if (it.length <= OrbitSettings.MAX_SIDE_CHAT_TITLE) text = it }, singleLine = true, enabled = !busy,
                label = { Text(stringResource(R.string.orbit_chats_title_label)) },
                placeholder = { Text(stringResource(R.string.orbit_chats_title_hint)) },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            OrbitDialogAction(enabled = text.isNotBlank() && !busy, onClick = { onSave(text.trim()) }) {
                if (busy) BusyMark()
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { OrbitDialogAction(enabled = !busy, secondary = true, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
