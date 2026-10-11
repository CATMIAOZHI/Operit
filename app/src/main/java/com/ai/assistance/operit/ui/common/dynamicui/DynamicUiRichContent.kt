package com.ai.assistance.operit.ui.common.dynamicui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** Structured text only: it cannot recurse into tool markup, HTML or another A2UI surface. */
@Composable
internal fun CardRichText(runs: List<*>, modifier: Modifier, onOpenUrl: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val openUrl by rememberUpdatedState(onOpenUrl)
    val annotated = remember(runs, colors, typography) {
        buildAnnotatedString {
            runs.take(64).forEach { item ->
                val run = item as? Map<*, *> ?: return@forEach
                val text = (run["text"] as? String).orEmpty().take(8192)
                val url = (run["url"] as? String)?.takeIf(DynamicUiProtocol::safeUrl)
                val tone = when (run["tone"]) {
                    "primary" -> colors.primary
                    "muted" -> colors.onSurfaceVariant
                    "error" -> colors.error
                    else -> colors.onSurface
                }
                if (url != null) pushLink(LinkAnnotation.Url(url, linkInteractionListener = { openUrl(url) }))
                withStyle(SpanStyle(
                    color = if (url != null) colors.primary else tone,
                    fontSize = when (run["size"]) {
                        "title" -> typography.titleMedium.fontSize
                        "caption" -> typography.bodySmall.fontSize
                        else -> typography.bodyMedium.fontSize
                    },
                    fontWeight = if (run["bold"] == true) FontWeight.SemiBold else FontWeight.Normal,
                    fontStyle = if (run["italic"] == true) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (url != null || run["underline"] == true) TextDecoration.Underline else TextDecoration.None,
                )) { append(text) }
                if (url != null) pop()
            }
        }
    }
    Text(annotated, modifier, style = typography.bodyMedium, color = colors.onSurface)
}
