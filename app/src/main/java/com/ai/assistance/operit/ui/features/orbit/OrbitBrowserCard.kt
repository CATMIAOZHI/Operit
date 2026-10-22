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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the card's picture of the page is redrawn while it is on screen. */
private const val PREVIEW_MS = 2_000L

/**
 * "Browser · working" at the end of an Orbit chat while Orbit, or a subagent it sent, uses the
 * browser. It shows a small live picture of the page; the button opens the browser to watch it.
 */
@Composable
internal fun OrbitBrowserCard(chatId: String, bubbleColor: Color, visible: Boolean = true) {
    val works by OrbitBrowserWatch.works.collectAsState()
    val work = works[chatId] ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Drawn on the main thread, small, and only while the card is on screen and the app in front.
    // The last picture stays while paused, so the card does not blink when the chat shows again.
    var preview by remember(work.sessionId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(work.sessionId, lifecycle, visible) {
        if (!visible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                OrbitBrowserWatch.preview(work.sessionId)?.let { preview = it.asImageBitmap() }
                delay(PREVIEW_MS)
            }
        }
    }
    val status = when (work.control) {
        OrbitBrowserWatch.Control.USER -> stringResource(R.string.orbit_browser_watch_yours)
        OrbitBrowserWatch.Control.WAITING -> stringResource(R.string.orbit_browser_watch_waiting, work.orbitName.ifBlank { orbitNameFallback() })
        OrbitBrowserWatch.Control.NONE -> stringResource(R.string.orbit_browser_watch_working)
    }
    val host = runCatching { android.net.Uri.parse(work.url).host }.getOrNull()?.removePrefix("www.").orEmpty()
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
                        stringResource(R.string.orbit_browser_card_title),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    )
                    Text(
                        if (host.isNotBlank()) "$status · $host" else status,
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                onClick = { scope.launch { OrbitBrowserWatch.open(context, chatId) } },
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
}

@Composable
private fun orbitNameFallback(): String = stringResource(R.string.nav_orbit)
