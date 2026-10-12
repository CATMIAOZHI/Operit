package com.ai.assistance.operit.ui.common.dynamicui

import androidx.a2ui.compose.runtime.*
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The host embeds UI without a frame; Card is the explicit, optional grouping container. */
internal object FramedCard : A2uiBasicCatalogV1.Card {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        childId: String, accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?, modifier: Modifier,
    ) {
        val child = observeA2uiComponentState(childId)
        val accessible = modifier.fillMaxWidth().semantics {
            listOfNotNull(accessibility?.label, accessibility?.description)
                .takeIf { it.isNotEmpty() }?.let { contentDescription = it.joinToString(". ") }
        }
        Surface(accessible, shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Box(Modifier.padding(16.dp)) {
                when (child) {
                    is A2uiComponentState.Success -> A2uiComponent(child.component)
                    is A2uiComponentState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is A2uiComponentState.Error -> {
                        LaunchedEffect(child.exception) { reportError(child.exception) }
                        Text("⚠", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Fixed semantic colors remain readable with either card palette. No AI-supplied arbitrary colors. */
internal fun cardToneColors(tone: String?, scheme: ColorScheme): Pair<Color, Color> {
    return cardSemanticToneColors(tone, scheme.surface.luminance() < .5f)
        ?: (scheme.onSurfaceVariant to scheme.surfaceContainerHigh)
}

/** Ink and fill for success/info/warning/error; null for neutral or unknown tones. */
internal fun cardSemanticToneColors(tone: String?, dark: Boolean): Pair<Color, Color>? {
    val ink = when (tone) {
        "success" -> if (dark) Color(0xFF9BE0B0) else Color(0xFF256A3C)
        "info" -> if (dark) Color(0xFFABCFFF) else Color(0xFF245A98)
        "warning" -> if (dark) Color(0xFFFFD58A) else Color(0xFF7B5000)
        "error" -> if (dark) Color(0xFFFFB4B4) else Color(0xFF9C3030)
        else -> return null
    }
    val background = when (tone) {
        "success" -> if (dark) Color(0xFF213C2A) else Color(0xFFE0F2E5)
        "info" -> if (dark) Color(0xFF26384F) else Color(0xFFE4EEFC)
        "warning" -> if (dark) Color(0xFF433722) else Color(0xFFFFF1D7)
        else -> if (dark) Color(0xFF4A292B) else Color(0xFFFBE5E5)
    }
    return ink to background
}

@Composable
internal fun CardStatusBadge(text: String, tone: String?, modifier: Modifier = Modifier) {
    val (ink, fill) = cardToneColors(tone, MaterialTheme.colorScheme)
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = fill) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun CardHeader(text: String, subtitle: String, status: String, tone: String?, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Narrow cards and accessibility font sizes keep the title and status on separate lines.
        val stacked = maxWidth < 240.dp || LocalDensity.current.fontScale > 1.3f
        val badgeWidth = maxWidth * .4f
        val title: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title(Modifier.fillMaxWidth())
            if (status.isNotBlank()) CardStatusBadge(status, tone)
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            title(Modifier.weight(1f))
            if (status.isNotBlank()) CardStatusBadge(status, tone, Modifier.widthIn(max = badgeWidth))
        }
    }
}

/**
 * Large statistic value. Shrinks to fit one line so digits are never cut, then wraps at the
 * smallest size: a value inside a chat bubble must never need horizontal dragging.
 */
@Composable
internal fun CardDisplayValue(text: String, atStart: Boolean) {
    val base = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val largest = base.fontSize.value
        val smallest = largest * 0.6f
        var size = largest
        while (measurer.measure(text, style = base.copy(fontSize = size.sp),
                softWrap = false, maxLines = 1).size.width > width && size > smallest) size = (size - 2f).coerceAtLeast(smallest)
        val fitted = size.sp
        val lineHeight = if (base.lineHeight.isSp) base.lineHeight * (fitted.value / largest) else base.lineHeight
        Text(text, Modifier.fillMaxWidth(), style = base.copy(fontSize = fitted, lineHeight = lineHeight),
            textAlign = if (atStart) TextAlign.Start else TextAlign.End)
    }
}

@Composable
internal fun CardInfoRow(label: String, value: String, tone: String?, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val valueColor = if (tone == null || tone == "neutral") scheme.onSurface else cardToneColors(tone, scheme).first
    val labelStyle = MaterialTheme.typography.bodyMedium
    val valueStyle = labelStyle.copy(fontWeight = FontWeight.Medium)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val required = measurer.measure(label, style = labelStyle, softWrap = false).size.width +
            measurer.measure(value, style = valueStyle, softWrap = false).size.width
        val stack = '\n' in label || '\n' in value || required > with(density) { (maxWidth - 16.dp).toPx() }
        if (stack) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = scheme.onSurfaceVariant, style = labelStyle)
            Text(value, color = valueColor, style = valueStyle)
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = scheme.onSurfaceVariant, style = labelStyle)
            Text(value, color = valueColor, style = valueStyle, textAlign = TextAlign.End)
        }
    }
}
