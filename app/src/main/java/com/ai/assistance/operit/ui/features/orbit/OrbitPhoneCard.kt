package com.ai.assistance.operit.ui.features.orbit

import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.agent.ShowerController
import com.ai.assistance.operit.core.tools.phone.PhoneBackgroundControl
import com.ai.assistance.operit.ui.features.chat.components.LocalOrbitName
import com.ai.assistance.operit.ui.features.chat.components.OrbitCardSlot
import com.ai.assistance.showerclient.ui.ShowerSurfaceView
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

private const val PHONE_PREVIEW_MS = 2_000L

/**
 * Orbit's background screens in its chat: a live picture of the screen while it is open, and
 * "closed" after. Each card stays after the message it first showed after, like browser cards.
 */
@Composable
internal fun OrbitPhoneCard(chatId: String, bubbleColor: Color, visible: Boolean, slot: OrbitCardSlot) {
    LaunchedEffect(Unit) { PhoneBackgroundControl.loadCards() }
    val cards by PhoneBackgroundControl.cards.collectAsState()
    val mine = remember(cards, chatId, slot) {
        cards.filter { card ->
            if (card.talkChatId != chatId) return@filter false
            val placed = card.anchorAt
            if (placed != null && (placed == Long.MIN_VALUE || placed in slot.shown)) placed == slot.anchor
            else if (placed != null && (placed < slot.loadedFrom || placed > slot.loadedUntil)) false
            else card.createdAt >= slot.from && (slot.until == null || card.createdAt < slot.until)
        }
    }
    if (mine.isEmpty()) return
    LaunchedEffect(mine, slot.anchor) {
        mine.filter { it.anchorAt == null }.forEach { PhoneBackgroundControl.place(it, slot.anchor) }
    }
    val screens by PhoneBackgroundControl.screens.collectAsState()
    Column(if (slot.anchor == Long.MIN_VALUE && slot.until != null) Modifier.padding(bottom = 8.dp) else Modifier) {
        mine.forEach { card ->
            androidx.compose.runtime.key(card.screenId) {
                PhoneScreenCard(card, screens[card.screenId]?.takeIf { !it.stopping }, bubbleColor, visible)
            }
        }
    }
}

@Composable
private fun PhoneScreenCard(
    card: PhoneBackgroundControl.Card,
    screen: PhoneBackgroundControl.Screen?,
    bubbleColor: Color,
    visible: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val name = LocalOrbitName.current.ifBlank { card.orbitName }.ifBlank { stringResource(R.string.nav_orbit) }
    var preview by remember(card.screenId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(card.screenId, screen != null, visible, lifecycle) {
        if (!visible || screen == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val bytes = PhoneBackgroundControl.preview(card.screenId)
                if (bytes != null) withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 3 })
                }?.let { preview = it.asImageBitmap() }
                delay(PHONE_PREVIEW_MS)
            }
        }
    }
    val label = (screen?.appLabel ?: card.appLabel).replace(Regex("[\\r\\n\\[\\]<>]"), " ").trim()
    val title = label.ifBlank { stringResource(R.string.orbit_phone_card_title) }
    val status = when {
        screen == null -> stringResource(R.string.orbit_phone_card_closed)
        screen.control == PhoneBackgroundControl.Control.USER -> stringResource(R.string.orbit_browser_watch_yours)
        screen.control == PhoneBackgroundControl.Control.WAITING -> stringResource(R.string.orbit_browser_watch_waiting, name)
        else -> stringResource(R.string.orbit_phone_card_working, name)
    }
    val summary = stringResource(R.string.orbit_phone_card_summary, title)
    OrbitCardPiece(summary, "phone:${card.createdAt}:${card.screenId}", card.createdAt) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = bubbleColor,
            modifier = Modifier.padding(top = 4.dp).widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, modifier = Modifier.size(52.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(26.dp),
                                tint = if (screen != null) colors.onSurface else colors.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (screen != null) colors.onSurface else colors.onSurfaceVariant)
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (screen != null) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp))
                            .background(colors.surface.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        preview?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        onClick = { PhoneBackgroundControl.watch(screen.id) },
                        shape = RoundedCornerShape(50),
                        color = colors.surface,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.orbit_phone_card_watch), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The background screen in full: the user watches; after "Take over" their touches go to it,
 * and "Hand back" returns it to Orbit. The system back key only closes this view.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun OrbitPhoneWatchHost() {
    val watching by PhoneBackgroundControl.watching.collectAsState()
    val screens by PhoneBackgroundControl.screens.collectAsState()
    val screen = watching?.let { screens[it] }?.takeIf { !it.stopping } ?: return
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val name = LocalOrbitName.current.ifBlank { screen.orbitName }.ifBlank { stringResource(R.string.nav_orbit) }
    val control by rememberUpdatedState(screen.control)
    val key = screen.key
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val id = screen.id
    // The user's touches go out one by one, in order, and stop the moment they hand the screen back.
    val touches = remember(key) { Channel<MotionEvent>(256) }
    LaunchedEffect(key) {
        withContext(Dispatchers.IO) {
            for (copied in touches) {
                try {
                    val action = copied.actionMasked
                    val size = viewSize
                    if (size.width <= 0 || size.height <= 0) continue
                    fun mapX(x: Float) = ((x / size.width).coerceIn(0f, 1f) * (screen.width - 1)).roundToInt().toFloat()
                    fun mapY(y: Float) = ((y / size.height).coerceIn(0f, 1f) * (screen.height - 1)).roundToInt().toFloat()
                    PhoneBackgroundControl.userInput(id) { target ->
                        if (action == MotionEvent.ACTION_MOVE) {
                            for (i in 0 until copied.historySize) {
                                ShowerController.injectTouchEvent(target, MotionEvent.ACTION_MOVE,
                                    mapX(copied.getHistoricalX(0, i)), mapY(copied.getHistoricalY(0, i)),
                                    copied.downTime, copied.getHistoricalEventTime(i),
                                    copied.getHistoricalPressure(0, i), copied.getHistoricalSize(0, i),
                                    copied.metaState, 1f, 1f, copied.deviceId, copied.edgeFlags)
                            }
                        }
                        ShowerController.injectTouchEvent(target, action, mapX(copied.getX(0)), mapY(copied.getY(0)),
                            copied.downTime, copied.eventTime, copied.getPressure(0), copied.getSize(0),
                            copied.metaState, 1f, 1f, copied.deviceId, copied.edgeFlags)
                    }
                } finally {
                    copied.recycle()
                }
            }
        }
    }
    Dialog(
        onDismissRequest = { PhoneBackgroundControl.watch(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { PhoneBackgroundControl.watch(null) }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.orbit_browser_watch_close))
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(screen.appLabel.ifBlank { stringResource(R.string.orbit_phone_card_title) },
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (screen.control) {
                                PhoneBackgroundControl.Control.USER -> stringResource(R.string.orbit_browser_watch_yours)
                                PhoneBackgroundControl.Control.WAITING -> stringResource(R.string.orbit_browser_watch_waiting, name)
                                PhoneBackgroundControl.Control.AI -> stringResource(R.string.orbit_phone_watch_hint)
                            },
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.width(48.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.aspectRatio(screen.width.toFloat() / screen.height.toFloat())
                            .clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it }.pointerInteropFilter { event ->
                                // Watching only until the user takes over.
                                if (control != PhoneBackgroundControl.Control.USER) return@pointerInteropFilter true
                                if (event.pointerCount != 1) return@pointerInteropFilter true
                                val action = event.actionMasked
                                if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE &&
                                    action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return@pointerInteropFilter true
                                val size = viewSize
                                if (size.width <= 0 || size.height <= 0) return@pointerInteropFilter true
                                val copied = MotionEvent.obtain(event)
                                if (touches.trySend(copied).isFailure) copied.recycle()
                                true
                            },
                            factory = { context ->
                                ShowerSurfaceView(context).also { it.bindController(ShowerController.getInstance(key)) }
                            },
                        )
                    }
                }
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (screen.control) {
                        PhoneBackgroundControl.Control.USER -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            WatchButton(stringResource(R.string.orbit_phone_watch_back), primary = false, modifier = Modifier.weight(1f)) {
                                scope.launch { PhoneBackgroundControl.userInput(id) { target -> ShowerController.key(target, KeyEvent.KEYCODE_BACK) } }
                            }
                            WatchButton(stringResource(R.string.orbit_browser_watch_hand_back, name), primary = true, modifier = Modifier.weight(2f)) {
                                PhoneBackgroundControl.handBack(screen.id)
                            }
                        }
                        PhoneBackgroundControl.Control.WAITING -> Unit
                        PhoneBackgroundControl.Control.AI ->
                            WatchButton(stringResource(R.string.orbit_browser_handoff_take), primary = true, modifier = Modifier.fillMaxWidth()) {
                                PhoneBackgroundControl.takeOver(screen.id)
                            }
                    }
                    WatchButton(stringResource(R.string.orbit_browser_watch_stop), primary = false, modifier = Modifier.fillMaxWidth()) {
                        PhoneBackgroundControl.stopByUser(screen.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchButton(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (primary) colors.primary else colors.surfaceVariant,
        modifier = modifier.height(52.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleSmall, color = if (primary) colors.onPrimary else colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
