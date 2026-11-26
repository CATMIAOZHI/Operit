package com.ai.assistance.operit.ui.features.websession.browser

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** The site's name as people read it: the host without "www.", or the address itself. */
internal fun siteHost(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url

internal fun isBlankPage(url: String): Boolean = url.isBlank() || url == "about:blank"

/** A round badge with the site's first letter, tinted the same way every time for the same site. */
@Composable
internal fun SiteAvatar(url: String, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val host = remember(url) { siteHost(url) }
    val letter = remember(host) {
        host.firstOrNull { it.isLetterOrDigit() }?.uppercase(Locale.getDefault()) ?: "·"
    }
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (Math.floorMod(host.hashCode(), 3)) {
        0 -> colors.primaryContainer to colors.onPrimaryContainer
        1 -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    Box(
        modifier.size(size).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = content, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp)
    }
}
