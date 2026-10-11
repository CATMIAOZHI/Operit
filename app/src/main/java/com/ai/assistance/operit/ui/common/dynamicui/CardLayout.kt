package com.ai.assistance.operit.ui.common.dynamicui

import androidx.a2ui.compose.runtime.*
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun cardSpacing(token: String?, default: Int = 8) =
    when (token) { "none" -> 0; "xs" -> 4; "sm" -> 8; "md" -> 16; "lg" -> 24; else -> default }.dp

internal fun cardAutoColumns(width: Float, minimum: Float, fontScale: Float): Int =
    ((width + 8) / (minimum * fontScale.coerceAtLeast(1f) + 8)).toInt().coerceIn(1, 4)

/** Bounded layout tokens shared by all authored cards, independent of screen size. */
internal class CardLayoutComponent(override val name: String) : A2uiComponent {
    private val children = A2uiProperty.childList("children")
    private val child = A2uiProperty.dynamicString("child")
    private val text = A2uiProperty.dynamicString("text")
    private val variant = A2uiProperty.dynamicString("variant")
    private val align = A2uiProperty.dynamicString("align")
    private val textAlign = A2uiProperty.dynamicString("textAlign")
    private val gap = A2uiProperty.dynamicString("gap")
    private val justify = A2uiProperty.dynamicString("justify")
    private val wrap = A2uiProperty.dynamicBoolean("wrap")
    private val padding = A2uiProperty.dynamicString("padding")
    private val radius = A2uiProperty.dynamicString("radius")
    private val background = A2uiProperty.dynamicString("background")
    private val border = A2uiProperty.dynamicBoolean("border")
    private val maxWidth = A2uiProperty.dynamicNumber("maxWidth")
    private val maxHeight = A2uiProperty.dynamicNumber("maxHeight")
    private val lineClamp = A2uiProperty.dynamicNumber("lineClamp")
    override val description = "Bounded responsive $name layout."
    override val properties = listOf(children, child, text, variant, align, textAlign, gap, justify,
        wrap, padding, radius, background, border, maxWidth, maxHeight, lineClamp)

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val refs = properties.bindChildReferences(children).orEmpty()
        val single = properties.bind(child)
        val render: @Composable () -> Unit = {
            if (single != null) CardChild(single)
            else refs.forEach { ref ->
                key(ref.id, ref.baseDataPath) {
                    when (val state = observeA2uiComponentState(ref)) {
                        is A2uiComponentState.Success -> A2uiComponent(state.component)
                        is A2uiComponentState.Error -> {
                            LaunchedEffect(state.exception) { reportError(state.exception) }
                            Text("⚠", color = MaterialTheme.colorScheme.error)
                        }
                        A2uiComponentState.Loading -> LinearProgressIndicator()
                    }
                }
            }
        }
        val spacing = cardSpacing(properties.bind(gap))
        val horizontal = when (properties.bind(align)) {
            "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start
        }
        val vertical = when (properties.bind(align)) {
            "center" -> Alignment.CenterVertically; "end" -> Alignment.Bottom; else -> Alignment.Top
        }
        when (name) {
            "Text" -> Text(properties.bind(text).orEmpty(),
                (if (properties.bind(textAlign) != null) modifier.fillMaxWidth() else modifier)
                    .then(if (properties.bind(variant) in setOf("h1","h2","h3","h4","h5")) Modifier.semantics { heading() } else Modifier),
                style = when (properties.bind(variant)) {
                    "h1" -> MaterialTheme.typography.headlineLarge
                    "h2" -> MaterialTheme.typography.headlineMedium
                    "h3" -> MaterialTheme.typography.headlineSmall
                    "h4" -> MaterialTheme.typography.titleLarge
                    "h5" -> MaterialTheme.typography.titleMedium
                    "caption" -> MaterialTheme.typography.bodySmall
                    else -> MaterialTheme.typography.bodyMedium
                },
                textAlign = when (properties.bind(textAlign)) {
                    "center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start
                },
                maxLines = properties.bind(lineClamp)?.toInt()?.coerceIn(1, 8) ?: Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis)
            "Column" -> Column(modifier, Arrangement.spacedBy(spacing), horizontal) { render() }
            "Row" -> {
                val rowModifier = if (properties.bind(justify) != null) modifier.fillMaxWidth() else modifier
                val arrangement = when (properties.bind(justify)) {
                    "between" -> Arrangement.SpaceBetween
                    "center" -> Arrangement.spacedBy(spacing, Alignment.CenterHorizontally)
                    "end" -> Arrangement.spacedBy(spacing, Alignment.End)
                    else -> Arrangement.spacedBy(spacing)
                }
                if (properties.bind(wrap) == true) FlowRow(rowModifier,
                    horizontalArrangement = arrangement, verticalArrangement = Arrangement.spacedBy(spacing),
                    itemVerticalAlignment = vertical) { render() }
                else Row(rowModifier, arrangement, vertical) { render() }
            }
            "Panel" -> {
                val colors = MaterialTheme.colorScheme
                val fill = when (properties.bind(background)) {
                    "surface" -> colors.surface
                    "inset" -> colors.surfaceContainerHigh
                    "accentMuted" -> colors.primaryContainer
                    else -> Color.Transparent
                }
                val ink = if (properties.bind(background) == "accentMuted") colors.onPrimaryContainer else colors.onSurface
                Surface(modifier.widthIn(max = (properties.bind(maxWidth)?.toInt() ?: 960).dp).fillMaxWidth(),
                    color = fill, contentColor = ink,
                    shape = RoundedCornerShape(cardSpacing(properties.bind(radius), 16)),
                    border = if (properties.bind(border) == true) BorderStroke(1.dp, colors.outlineVariant) else null) {
                    Box(Modifier.padding(cardSpacing(properties.bind(padding), 16))) { render() }
                }
            }
            // The height constraint MUST precede scrolling inside a vertically unbounded chat.
            "Scroll" -> Box(modifier.fillMaxWidth()
                .heightIn(max = (properties.bind(maxHeight)?.toInt() ?: 240).coerceIn(80, 480).dp)
                .verticalScroll(rememberScrollState())) { render() }
        }
    }
}

@Composable
internal fun A2uiComponentScope.CardChild(id: String) {
    when (val state = observeA2uiComponentState(id)) {
        is A2uiComponentState.Success -> A2uiComponent(state.component)
        is A2uiComponentState.Error -> {
            LaunchedEffect(state.exception) { reportError(state.exception) }
            Text("⚠", color = MaterialTheme.colorScheme.error)
        }
        A2uiComponentState.Loading -> LinearProgressIndicator()
    }
}
