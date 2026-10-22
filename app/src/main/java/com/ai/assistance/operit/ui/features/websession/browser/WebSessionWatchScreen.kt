package com.ai.assistance.operit.ui.features.websession.browser

import android.graphics.Color as AndroidColor
import android.widget.FrameLayout
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 */
@Composable
internal fun WebSessionWatchScreen(
    watch: BrowserWatchUi,
    webViewHost: WebSessionWebViewHost,
    hasSession: Boolean,
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
            val close = stringResource(R.string.orbit_browser_watch_close)
            Surface(
                onClick = watch.onClose,
                shape = CircleShape,
                color = colors.surfaceVariant,
                modifier = Modifier.size(48.dp).semantics { contentDescription = close },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(26.dp))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
            Spacer(Modifier.size(48.dp))
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { onBrowserArea(it.width, it.height) },
        ) {
            if (hasSession) {
                AndroidView(
                    factory = { context ->
                        FrameLayout(context).apply {
                            setBackgroundColor(AndroidColor.WHITE)
                            webViewHost.attachContainer(this)
                        }
                    },
                    update = { container -> webViewHost.attachContainer(container) },
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
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
    }
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
