package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ai.assistance.operit.R
import com.ai.assistance.operit.features.orbit.OrbitBrowserWatch
import com.ai.assistance.operit.ui.features.chat.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the card's picture of the page is redrawn while it is on screen. */
private const val PREVIEW_MS = 2_000L

/**
 * "Browser · working" in an Orbit chat while Orbit, or a subagent it sent, uses the browser. It
 * shows a small live picture of the page; the button opens the browser to watch it. Each card stays
 * after the message it first showed after; once its page is gone, or the same task shows a newer
 * card, it stays there marked as expired.
 */
@Composable
internal fun OrbitBrowserCard(chatId: String, bubbleColor: Color, visible: Boolean, slot: OrbitCardSlot) {
    remember { OrbitBrowserWatch.loadCards() }
    val cards by OrbitBrowserWatch.cards.collectAsState()
    val mine = remember(cards, chatId, slot) {
        cards.filter { card ->
            if (card.talkChatId != chatId) return@filter false
            val placed = card.anchorAt
            // A card stays after the message it first showed after, while that message is shown.
            if (placed != null && (placed == Long.MIN_VALUE || placed in slot.shown)) placed == slot.anchor
            // That message is on a page not loaded now: the card shows with it there.
            else if (placed != null && (placed < slot.loadedFrom || placed > slot.loadedUntil)) false
            else card.createdAt >= slot.from && (slot.until == null || card.createdAt < slot.until)
        }
    }
    if (mine.isEmpty()) return
    // The first place a card shows in becomes its place, so a reply that shows up later around it
    // (written after Orbit opened the browser) does not move it.
    LaunchedEffect(mine, slot.anchor) {
        mine.filter { it.anchorAt == null }.forEach { OrbitBrowserWatch.place(it, slot.anchor) }
    }
    // Cards above the first message keep the usual gap before it.
    Column(if (slot.anchor == Long.MIN_VALUE && slot.until != null) Modifier.padding(bottom = 8.dp) else Modifier) {
        mine.forEach { card ->
            androidx.compose.runtime.key(card.executorChatId, card.createdAt) {
                BrowserCardSlot(card, bubbleColor, visible)
            }
        }
    }
}

@Composable
private fun BrowserCardSlot(card: OrbitBrowserWatch.Card, bubbleColor: Color, visible: Boolean) {
    val works by OrbitBrowserWatch.works.collectAsState()
    val work = works[card.executorChatId]?.takeIf { it.createdAt == card.createdAt && it.talkChatId == card.talkChatId }
    if (work != null) BrowserTaskCard(work, bubbleColor, visible) else ExpiredBrowserCard(card, bubbleColor)
}

/** A card whose page is gone or replaced by a newer card: what it showed, no picture, no button. */
@Composable
private fun ExpiredBrowserCard(card: OrbitBrowserWatch.Card, bubbleColor: Color) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val host = runCatching { android.net.Uri.parse(card.url).host }.getOrNull()?.removePrefix("www.").orEmpty()
    val title = card.title.ifBlank { host }.ifBlank { card.orbitName }.take(160)
        .replace(Regex("[\\r\\n\\[\\]<>]"), " ").trim()
    val summary = stringResource(R.string.orbit_browser_task_card_summary, title)
    val piece = OrbitPiece(summary, "browser:${card.createdAt}:${card.executorChatId}", summary, true)
    val reactions = LocalOrbitUserReactions.current
    val reply = LocalOrbitLiveCardReply.current
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    fun react(emoji: String) {
        if (reactions?.byMessage?.get(piece.key) != emoji) OrbitEmojiUsage.record(context, emoji)
        reactions?.toggle?.invoke(piece.key, emoji, summary)
        menu = false
    }
    val status = stringResource(R.string.orbit_browser_card_expired)
    androidx.compose.runtime.CompositionLocalProvider(LocalOrbitPieceActions provides OrbitPieceActions(
        like = { if (reactions?.byMessage?.get(piece.key) != OrbitQuickLike) react(OrbitQuickLike) },
        menu = { menu = true },
        reply = reply?.let { action -> { action(summary, card.createdAt) } },
    )) {
    OrbitPieceFrame(piece) {
    Box {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bubbleColor,
        modifier = Modifier.padding(top = 4.dp).widthIn(max = 320.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Language, null, Modifier.size(26.dp), tint = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title.ifBlank { stringResource(R.string.orbit_browser_card_title) },
                    style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (host.isNotBlank()) "$status · $host" else status,
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        OrbitTapbackRow(reactions?.byMessage?.get(piece.key), ::react, { menu = false; picker = true })
        if (reply != null) androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.reply_message)) },
            onClick = { menu = false; reply(summary, card.createdAt) },
        )
    }
    }
    }
    }
    if (picker) OrbitEmojiPickerSheet(
        selected = reactions?.byMessage?.get(piece.key),
        onPick = { react(it); picker = false },
        onDismiss = { picker = false },
    )
}

@Composable
private fun BrowserTaskCard(work: OrbitBrowserWatch.Work, bubbleColor: Color, visible: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Drawn on the main thread, small, and only while the card is on screen and the app in front.
    // The last picture stays while paused, so the card does not blink when the chat shows again.
    var preview by remember(work.sessionId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(work.sessionId, lifecycle, visible, work.finished) {
        if (!visible) return@LaunchedEffect
        // A stopped page no longer changes: one picture is enough, so it is not redrawn every 2 seconds.
        if (work.finished) {
            if (preview == null) OrbitBrowserWatch.preview(work.sessionId)?.let { preview = it.asImageBitmap() }
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                OrbitBrowserWatch.preview(work.sessionId)?.let { preview = it.asImageBitmap() }
                delay(PREVIEW_MS)
            }
        }
    }
    val status = if (work.stopping) stringResource(R.string.orbit_browser_task_stopping) else when (work.control) {
        OrbitBrowserWatch.Control.USER -> stringResource(R.string.orbit_browser_watch_yours)
        OrbitBrowserWatch.Control.WAITING -> stringResource(R.string.orbit_browser_watch_waiting, work.orbitName.ifBlank { orbitNameFallback() })
        OrbitBrowserWatch.Control.NONE -> stringResource(
            if (work.finished) R.string.orbit_browser_watch_finished else R.string.orbit_browser_watch_working)
    }
    val host = runCatching { android.net.Uri.parse(work.url).host }.getOrNull()?.removePrefix("www.").orEmpty()
    val title = work.title.ifBlank { host }.ifBlank { work.orbitName }.take(160)
        .replace(Regex("[\\r\\n\\[\\]<>]"), " ").trim()
    val summary = stringResource(R.string.orbit_browser_task_card_summary, title)
    val piece = OrbitPiece(summary, "browser:${work.createdAt}:${work.executorChatId}", summary, true)
    val reactions = LocalOrbitUserReactions.current
    val reply = LocalOrbitLiveCardReply.current
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    fun react(emoji: String) {
        if (reactions?.byMessage?.get(piece.key) != emoji) OrbitEmojiUsage.record(context, emoji)
        reactions?.toggle?.invoke(piece.key, emoji, summary)
        menu = false
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalOrbitPieceActions provides OrbitPieceActions(
        like = { if (reactions?.byMessage?.get(piece.key) != OrbitQuickLike) react(OrbitQuickLike) },
        menu = { menu = true },
        reply = reply?.let { action -> { action(summary, work.createdAt) } },
    )) {
    OrbitPieceFrame(piece) {
    Box {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bubbleColor,
        modifier = Modifier.padding(top = 4.dp).widthIn(max = 320.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, modifier = Modifier.size(52.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Language, null, Modifier.size(26.dp), tint = colors.onSurface)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title.ifBlank { stringResource(R.string.orbit_browser_card_title) },
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (host.isNotBlank()) "$status · $host" else status,
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(14.dp))
                    .background(colors.surface.copy(alpha = 0.6f)),
            ) {
                preview?.let {
                    Image(it, null, Modifier.fillMaxSize(), alignment = Alignment.TopCenter, contentScale = ContentScale.Crop)
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(
                onClick = { scope.launch { OrbitBrowserWatch.open(context, work.executorChatId) } },
                enabled = !work.stopping && work.sessionId?.let {
                    com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools.sessions.containsKey(it)
                } == true,
                shape = RoundedCornerShape(50),
                color = colors.surface,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.orbit_browser_card_open), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        OrbitTapbackRow(reactions?.byMessage?.get(piece.key), ::react, { menu = false; picker = true })
        if (reply != null) androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.reply_message)) },
            onClick = { menu = false; reply(summary, work.createdAt) },
        )
    }
    }
    }
    }
    if (picker) OrbitEmojiPickerSheet(
        selected = reactions?.byMessage?.get(piece.key),
        onPick = { react(it); picker = false },
        onDismiss = { picker = false },
    )
}

@Composable
private fun orbitNameFallback(): String = stringResource(R.string.nav_orbit)
