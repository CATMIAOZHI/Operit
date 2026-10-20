package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.OrbitSettings
import com.ai.assistance.operit.data.preferences.OrbitSideChat
import com.ai.assistance.operit.features.orbit.OrbitCompanion
import kotlinx.coroutines.launch

/**
 * Orbit's own side menu, like Muse's: it slides in from the left when the round menu button is
 * tapped (never by a swipe), with the Orbit's name, a button back to Operit, the main chat, the
 * side chats, and settings, search and "new side chat" at the bottom. Long press a side chat to
 * rename or delete it. Tapping outside, Back, or a swipe to the left closes it.
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
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<OrbitSideChat?>(null) }
    var deleting by remember { mutableStateOf<OrbitSideChat?>(null) }
    var busy by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // How far the panel was dragged to the left, in pixels (0 or less).
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var panelPx by remember { mutableFloatStateOf(0f) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(visible) {
        // The chat's text box sits under the menu: drop its focus so typing never goes there,
        // and drop the menu's own search focus once it closes.
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        if (visible) dragOffset = 0f
    }

    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) OrbitMotion.PUSH_MS else OrbitMotion.DISMISS_MS, easing = OrbitMotion.Easing),
        label = "orbitSideMenu",
    )
    // The search is cleared only once the panel is fully gone, so nothing moves while it slides out.
    val gone = progress == 0f
    LaunchedEffect(gone) { if (gone) query = "" }
    // The back swipe slides the panel away under the finger, like a drag; let go early and it returns.
    PredictiveBackHandler(enabled = visible) { events ->
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
                Toast.makeText(context, context.getString(R.string.orbit_chats_failed), Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }

    if (progress > 0f) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
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
                MenuContent(
                    closing = !visible,
                    orbit = orbit, currentChatId = currentChatId, unread = unread, query = query,
                    onQueryChange = { query = it }, busy = busy,
                    onOpen = ::open,
                    onRename = { renaming = it }, onDelete = { deleting = it },
                    onNew = {
                        if (orbit.sideChats.size >= OrbitSettings.MAX_SIDE_CHATS) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.orbit_chats_limit, OrbitSettings.MAX_SIDE_CHATS),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            // No title to type: it is named after the first message, like any chat.
                            act {
                                val id = OrbitCompanion.createSideChat(
                                    context, orbit.id, context.getString(R.string.new_conversation),
                                )
                                open(id)
                            }
                        }
                    },
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
        }
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun MenuContent(
    closing: Boolean,
    orbit: OrbitProfile,
    currentChatId: String,
    unread: Set<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onRename: (OrbitSideChat) -> Unit,
    onDelete: (OrbitSideChat) -> Unit,
    onNew: () -> Unit,
    onOpenSettings: () -> Unit,
    onLeaveOrbit: () -> Unit,
) {
    // With the keyboard up (search), everything above the list folds away so the results and the
    // search box keep room, even in landscape or with large text. It folds smoothly, without a jump.
    // Only the menu's own search counts: the chat's keyboard may still be closing as the menu opens.
    var searchFocused by remember { mutableStateOf(false) }
    val typingNow = searchFocused && WindowInsets.isImeVisible
    // While the panel slides out it keeps its last layout instead of unfolding on the way.
    var typing by remember { mutableStateOf(false) }
    if (!closing) typing = typingNow
    val fold = tween<androidx.compose.ui.unit.IntSize>(260, easing = OrbitMotion.Easing)
    val fade = tween<Float>(200, easing = OrbitMotion.Easing)
    val gap by androidx.compose.animation.core.animateDpAsState(
        if (typing) 6.dp else 12.dp, tween(260, easing = OrbitMotion.Easing), label = "orbitMenuGap",
    )
    val sides = orbit.sideChats.sortedByDescending { it.createdAt }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
      AnimatedVisibility(
        visible = !typing,
        enter = expandVertically(fold) + fadeIn(fade),
        exit = shrinkVertically(fold) + fadeOut(fade),
      ) {
       Column {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                orbit.name.ifBlank { stringResource(R.string.nav_orbit) },
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            // Where Muse has "Invite": back to Operit's main screen.
            Surface(
                onClick = onLeaveOrbit,
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
        Spacer(Modifier.height(20.dp))
        ChatRow(
            icon = "house", title = stringResource(R.string.orbit_chats_main),
            selected = currentChatId == orbit.chatId, unread = orbit.chatId in unread,
            onClick = { onOpen(orbit.chatId) },
        )
        Text(
            stringResource(R.string.orbit_chats_side_section),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = if (sides.isEmpty()) 6.dp else 0.dp),
        )
       }
      }
        // Said up here so it is seen even when the list is long.
        AnimatedVisibility(
            visible = sides.isNotEmpty() && query.isBlank() && !typing,
            enter = expandVertically(fold) + fadeIn(fade),
            exit = shrinkVertically(fold) + fadeOut(fade),
        ) {
            Text(
                stringResource(R.string.orbit_chats_long_press), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 6.dp),
            )
        }
        // A small breath above the results while searching; it grows and shrinks with the fold.
        Spacer(Modifier.height((12.dp - gap) * 2))
        val shown = if (query.isBlank()) sides else sides.filter { it.title.contains(query.trim(), ignoreCase = true) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                sides.isEmpty() -> EmptySideChats(onNew = onNew, enabled = !busy)
                shown.isEmpty() -> Text(
                    stringResource(R.string.orbit_chats_search_empty),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shown, key = { it.chatId }) { side ->
                        var menu by remember(side.chatId) { mutableStateOf(false) }
                        Box(Modifier.animateItem(fadeInSpec = tween(220), placementSpec = tween(320, easing = OrbitMotion.Easing), fadeOutSpec = tween(180))) {
                            ChatRow(
                                icon = "message-circle", title = side.title, selected = currentChatId == side.chatId,
                                unread = side.chatId in unread,
                                onClick = { onOpen(side.chatId) }, onLongClick = { menu = true },
                                longClickLabel = stringResource(R.string.orbit_library_more_actions),
                            )
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; onRename(side) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.orbit_chats_delete)) }, onClick = { menu = false; onDelete(side) })
                            }
                        }
                    }
                }
            }
        }
        // Settings, search and new side chat, like the bottom of Muse's menu.
        Row(
            // Tighter while searching, so short landscape screens still show results above the keyboard.
            Modifier.fillMaxWidth().padding(vertical = gap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundAction("settings", stringResource(R.string.settings), onOpenSettings)
            val searchLabel = stringResource(R.string.orbit_chats_search_label)
            // The whole pill is the text field, so a tap anywhere on it starts typing.
            BasicTextField(
                value = query, onValueChange = onQueryChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).height(52.dp)
                    .onFocusChanged { searchFocused = it.isFocused }
                    .semantics { contentDescription = searchLabel },
                decorationBox = { field ->
                    Row(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(orbitIcon("search"), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(stringResource(R.string.search), style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            field()
                        }
                    }
                },
            )
            RoundAction("square-pen", stringResource(R.string.orbit_chats_new), onNew, enabled = !busy)
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

@Composable
private fun RoundAction(icon: String, label: String, onClick: () -> Unit, enabled: Boolean = true) {
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
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val unreadLabel = stringResource(R.string.orbit_chats_unread)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longClickLabel)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(orbitIcon(icon), null, Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
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
