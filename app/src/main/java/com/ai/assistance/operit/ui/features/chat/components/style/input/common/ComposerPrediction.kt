package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.ai.assistance.operit.R

internal fun visibleComposerPrediction(value: TextFieldValue, prediction: String?): String? =
    prediction?.takeIf { it.isNotBlank() && value.text.isEmpty() && value.composition == null }

/** The editor still owns an empty value: only its native text layout sees the suggestion. */
internal class ComposerPredictionTransformation(
    private val prediction: String?,
    private val color: Color,
    private val fallback: VisualTransformation,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.isNotEmpty() || prediction.isNullOrEmpty()) return fallback.filter(text)
        return TransformedText(
            AnnotatedString(prediction, SpanStyle(color = color)),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = 0
                override fun transformedToOriginal(offset: Int) = 0
            },
        )
    }
}

/** The theme already provides muted text; further fading makes small ghost text unreadable. */
internal fun composerPredictionColor(colorScheme: ColorScheme): Color = colorScheme.onSurfaceVariant

@Composable
internal fun rememberComposerPredictionTransformation(
    value: TextFieldValue,
    prediction: String?,
    fallback: VisualTransformation,
): VisualTransformation {
    val color = composerPredictionColor(MaterialTheme.colorScheme)
    val visible = visibleComposerPrediction(value, prediction)
    return remember(visible, color, fallback) {
        ComposerPredictionTransformation(visible, color, fallback)
    }
}

/** Observe, never consume: focus, the keyboard, long press, selection and scrolling remain native. */
@Composable
internal fun Modifier.composerPredictionSemantics(
    value: TextFieldValue,
    prediction: String?,
    enabled: Boolean = true,
    onAccept: (String) -> Boolean,
): Modifier {
    val visible = visibleComposerPrediction(value, prediction) ?: return this
    val accept = rememberUpdatedState(onAccept)
    val label = stringResource(R.string.composer_prediction_adopt)
    val semanticsModifier = this.semantics {
        // CoreTextField otherwise exposes its transformed text as EditableText to TalkBack.
        editableText = AnnotatedString(value.text)
        stateDescription = visible
        customActions = if (enabled) {
            listOf(CustomAccessibilityAction(label) { accept.value(visible) })
        } else emptyList()
    }
    return semanticsModifier
}

internal class ComposerPredictionLayout {
    var result: TextLayoutResult? by mutableStateOf(null)

    fun containsGlyph(position: Offset, prediction: String): Boolean {
        val layout = result ?: return false
        if (layout.layoutInput.text.text != prediction || prediction.isEmpty()) return false
        if (position.x < 0 || position.y < 0 ||
            position.x >= layout.size.width || position.y >= layout.size.height) return false
        val offset = layout.getOffsetForPosition(position).coerceIn(0, prediction.lastIndex)
        // getOffsetForPosition returns the nearest character even in blank space; require its bounds.
        return listOf(offset, offset - 1).any { index ->
            index in prediction.indices && !prediction[index].isWhitespace() &&
                layout.getBoundingBox(index).contains(position)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private val GhostBringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * The observer is inside the actual text viewport, after its scroll modifier. Its event coordinates
 * therefore track the rendered text, including Material padding and scrolling, without estimates.
 * The normal draft keeps BasicTextField's own scrolling and line limits.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ComposerPredictionViewport(
    value: TextFieldValue,
    prediction: String?,
    layout: ComposerPredictionLayout,
    textStyle: TextStyle,
    maxLines: Int,
    enabled: Boolean = true,
    onAccept: (String) -> Boolean,
    innerTextField: @Composable () -> Unit,
) {
    val visible = visibleComposerPrediction(value, prediction)
    // A new prediction starts at its beginning; hiding the same cached text keeps its position.
    val scroll = key(prediction) { rememberScrollState() }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Match legacy BasicTextField's heightInLines calculation, with the same resolved font/style.
    val maxHeight = if (maxLines == Int.MAX_VALUE) Dp.Unspecified else {
        val oneLine = measurer.measure("HHHHHHHHHH", textStyle).size.height
        val twoLines = measurer.measure("HHHHHHHHHH\nHHHHHHHHHH", textStyle).size.height
        with(density) { (oneLine + (twoLines - oneLine) * (maxLines - 1)).toDp() }
    }
    // Ghost offsets all map to the empty draft's caret at zero. Native focus relocation must not
    // jump a manually scrolled suggestion to its first line between the two adoption taps.
    // Keep this override inside the fixed composer hosts: real draft and outer keyboard/focus
    // relocation stay native. A future scrollable host needs its own relocation regression check.
    val bringIntoViewSpec = if (visible != null) GhostBringIntoViewSpec else LocalBringIntoViewSpec.current
    // Keep a stable inner composition so adopting/typing never recreates the native editor.
    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
        Box(
            if (visible == null) Modifier else Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(scroll)
                .testTag("composer_prediction_text")
                .composerPredictionGesture(visible, layout, enabled, onAccept)
        ) { innerTextField() }
    }
}

@Composable
private fun Modifier.composerPredictionGesture(
    visible: String,
    layout: ComposerPredictionLayout,
    enabled: Boolean,
    onAccept: (String) -> Boolean,
): Modifier {
    if (!enabled) return this
    val accept = rememberUpdatedState(onAccept)
    return this.pointerInput(visible, layout) {
        awaitPointerEventScope {
            var firstTapTime: Long? = null
            var firstTapPosition = androidx.compose.ui.geometry.Offset.Zero
            var downTime: Long? = null
            var downPosition = androidx.compose.ui.geometry.Offset.Zero
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.changes.size != 1) {
                    firstTapTime = null
                    downTime = null
                    continue
                }
                val change = event.changes.single()
                if (change.changedToDownIgnoreConsumed()) {
                    downTime = change.uptimeMillis
                    downPosition = change.position
                    moved = !layout.containsGlyph(change.position, visible)
                }
                if ((change.position - downPosition).getDistance() > viewConfiguration.touchSlop) {
                    moved = true
                }
                if (change.changedToUpIgnoreConsumed()) {
                    val start = downTime
                    val shortTap = start != null && !moved &&
                        layout.containsGlyph(change.position, visible) &&
                        change.uptimeMillis - start < viewConfiguration.longPressTimeoutMillis
                    val previous = firstTapTime
                    val interval = if (previous == null || start == null) -1 else start - previous
                    if (shortTap && previous != null &&
                        interval >= viewConfiguration.doubleTapMinTimeMillis &&
                        interval <= viewConfiguration.doubleTapTimeoutMillis &&
                        (change.position - firstTapPosition).getDistance() <= viewConfiguration.touchSlop
                    ) {
                        firstTapTime = null
                        accept.value(visible)
                    } else {
                        firstTapTime = if (shortTap) change.uptimeMillis else null
                        firstTapPosition = change.position
                    }
                    downTime = null
                }
            }
        }
    }
}
