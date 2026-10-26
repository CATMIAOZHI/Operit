package com.ai.assistance.operit.ui.features.websession.browser

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserWatchUi
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionWebViewHost

private val WatchBlue = Color(0xFF3478F6)

/**
 * Orbit's browser while it works, like watching over someone's shoulder: the page shows, but
 * touches stop at a layer over it until the user takes control. The AI drives the page directly,
 * so the layer never gets in its way.
 *
 * Two fingers zoom and move the whole page picture (never the page's own size, which sites read
 * to tell phones from computers); one finger is the page's, once the user has control.
 */
@Composable
internal fun WebSessionWatchScreen(
    watch: BrowserWatchUi,
    webViewHost: WebSessionWebViewHost,
    hasSession: Boolean,
    url: String,
    isLoading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefreshOrStop: () -> Unit,
    onTotalHeight: (Int) -> Unit,
    onBrowserArea: (width: Int, height: Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(colors.background).onSizeChanged { onTotalHeight(it.height) },
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WatchCloseButton(watch)
            WatchTitle(watch, Modifier.weight(1f).padding(horizontal = 8.dp))
            Spacer(Modifier.size(48.dp))
        }
        // Once the user drives the page, they get a browser's own controls.
        if (watch.controlling) {
            WatchControls(url, isLoading, canGoBack, canGoForward, onNavigate, onBack, onForward, onRefreshOrStop)
        }
        WatchBrowserArea(watch, webViewHost, hasSession, onBrowserArea, Modifier.weight(1f).fillMaxWidth())
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WatchActions(watch)
        }
    }
}

/**
 * The page picture. Margins around it take the screen's own background. Two fingers zoom and move
 * the picture; until the user takes control, every other touch stops at a layer over the page.
 */
@Composable
private fun WatchBrowserArea(
    watch: BrowserWatchUi,
    webViewHost: WebSessionWebViewHost,
    hasSession: Boolean,
    onBrowserArea: (width: Int, height: Int) -> Unit,
    modifier: Modifier,
) {
    val margin = MaterialTheme.colorScheme.background.toArgb()
    Box(
        modifier
            // A zoomed picture is bigger than its area; it must not draw over the bars above and below.
            .clipToBounds()
            .onSizeChanged { onBrowserArea(it.width, it.height) }
            .pointerInput(webViewHost) {
                // Seen before the page: once a second finger lands the gesture is the picture's,
                // and the page gets a cancel for the first finger, until every finger lifts.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var zooming = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!zooming && event.changes.count { it.pressed } >= 2) zooming = true
                        if (zooming) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (centroid.isSpecified() && (zoom != 1f || pan.x != 0f || pan.y != 0f)) {
                                webViewHost.transformUserView(centroid.x, centroid.y, zoom, pan.x, pan.y)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        if (hasSession) {
            AndroidView(
                factory = { context ->
                    FrameLayout(context).apply {
                        setBackgroundColor(margin)
                        clipChildren = true
                        webViewHost.attachContainer(this)
                    }
                },
                update = { container ->
                    container.setBackgroundColor(margin)
                    webViewHost.attachContainer(container)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!watch.controlling) {
            // Every touch, scroll and long press stops here; the page under it only shows.
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                },
            )
        }
    }
}

private fun androidx.compose.ui.geometry.Offset.isSpecified(): Boolean =
    this != androidx.compose.ui.geometry.Offset.Unspecified && x.isFinite() && y.isFinite()

@Composable
private fun WatchCloseButton(watch: BrowserWatchUi) {
    WatchRoundButton(Icons.Filled.Close, stringResource(R.string.orbit_browser_watch_close), 26, watch.onClose)
}

@Composable
private fun WatchRoundButton(icon: ImageVector, label: String, iconDp: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.surfaceVariant,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(iconDp.dp))
        }
    }
}

@Composable
private fun WatchTitle(watch: BrowserWatchUi, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(when {
                watch.controlling -> R.string.orbit_browser_watch_yours
                watch.finished -> R.string.orbit_browser_watch_finished
                else -> R.string.orbit_browser_watch_working
            }),
            color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (watch.host.isNotBlank()) {
            Text(
                watch.host, color = colors.onSurfaceVariant, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WatchActions(watch: BrowserWatchUi) {
    val colors = MaterialTheme.colorScheme
    WatchButton(
        text = when {
            watch.controlling -> stringResource(R.string.orbit_browser_watch_hand_back, watch.orbitName)
            watch.waiting -> stringResource(R.string.orbit_browser_watch_waiting, watch.orbitName)
            else -> stringResource(R.string.orbit_browser_watch_take_over)
        },
        color = WatchBlue,
        textColor = Color.White,
        busy = watch.waiting,
        onClick = { if (watch.controlling) watch.onHandBack() else if (!watch.waiting) watch.onTakeOver() },
    )
    WatchButton(
        stringResource(if (watch.finished) R.string.orbit_browser_watch_close_page else R.string.orbit_browser_watch_stop),
        colors.surfaceVariant, textColor = colors.onSurfaceVariant, busy = false, onClick = watch.onStop,
    )
}

@Composable
private fun WatchButton(text: String, color: Color, textColor: Color, busy: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = !busy,
        shape = RoundedCornerShape(50),
        color = color,
        modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), color = textColor, strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
            }
            Text(text, color = textColor, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Back, forward, reload and an address bar for the user driving the page. */
@Composable
private fun WatchControls(
    url: String,
    isLoading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefreshOrStop: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var editing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var draft by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun submit() {
        val target = normalizeNavigationUrl(draft)
        editing = false
        keyboard?.hide()
        if (target != "about:blank") onNavigate(target)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!editing) {
            WatchIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.web_session_back), canGoBack, onBack)
            WatchIcon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.web_session_forward), canGoForward, onForward)
            WatchIcon(
                if (isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                stringResource(if (isLoading) R.string.web_session_stop else R.string.web_session_refresh),
                true, onRefreshOrStop,
            )
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.surfaceVariant,
            modifier = Modifier.weight(1f).height(40.dp).padding(start = if (editing) 4.dp else 6.dp),
        ) {
            if (editing) {
                Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val inputLabel = stringResource(R.string.web_session_address_input)
                    // Leaving the field (tapping the page) ends editing, once it had focus.
                    var hadFocus by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    androidx.compose.foundation.text.BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { submit() }),
                        modifier = Modifier.weight(1f).focusRequester(focus)
                            .onFocusChanged {
                                if (it.isFocused) hadFocus = true
                                else if (hadFocus) editing = false
                            }
                            .semantics { contentDescription = inputLabel },
                    )
                    WatchIcon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.web_session_open_address), true) { submit() }
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            } else {
                val barLabel = stringResource(R.string.web_session_address_bar)
                Box(
                    Modifier.fillMaxSize().clickable {
                        draft = url
                        editing = true
                    }.padding(horizontal = 14.dp).semantics { contentDescription = barLabel },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        url.removePrefix("https://").removePrefix("http://").ifBlank { "about:blank" },
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(icon, label, modifier = Modifier.size(22.dp))
    }
}
