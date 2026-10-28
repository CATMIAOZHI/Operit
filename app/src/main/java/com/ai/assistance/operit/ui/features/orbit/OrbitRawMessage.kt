package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import java.text.DateFormat
import java.util.Date

/**
 * One stored row for developers, used by a task's process page in developer mode: who wrote it,
 * whether the chat hides it, when, the model and tokens, then its text exactly as stored.
 */
@Composable
internal fun OrbitRawMessage(message: ChatMessage) {
    val colors = MaterialTheme.colorScheme
    val hidden = message.displayMode != ChatMessageDisplayMode.NORMAL
    val time = remember(message.timestamp) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(message.timestamp))
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (hidden) colors.tertiaryContainer.copy(alpha = 0.45f) else colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (message.sender == "user") stringResource(R.string.orbit_dev_raw_user)
                    else stringResource(R.string.orbit_dev_raw_ai),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                )
                if (hidden) {
                    Spacer(Modifier.width(8.dp))
                    OrbitHiddenBadge(message.displayMode)
                }
                Spacer(Modifier.weight(1f))
                Text(time, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            if (message.sender != "user" && (message.modelName.isNotBlank() || message.inputTokens > 0)) {
                Text(
                    stringResource(
                        R.string.orbit_dev_raw_usage,
                        message.modelName.ifBlank { "?" }, message.inputTokens, message.cachedInputTokens, message.outputTokens,
                    ),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            OrbitFoldableText(
                message.content.ifEmpty { "∅" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                selectable = true,
            )
        }
    }
}

/**
 * Text that shows its first [foldedLines] lines with a "show all" button when it is longer, so a
 * long prompt or hidden notice does not fill the page. Only the start is laid out while folded.
 */
@Composable
internal fun OrbitFoldableText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    foldedLines: Int = 10,
) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(text) { androidx.compose.runtime.mutableStateOf(false) }
    var overflows by remember(text) { androidx.compose.runtime.mutableStateOf(false) }
    // Enough characters to fill the folded lines; the rest is only measured once opened.
    val head = remember(text, foldedLines) { if (text.length > foldedLines * 200) text.take(foldedLines * 200) else text }
    val cut = head.length < text.length
    Column(modifier) {
        val body: @Composable () -> Unit = {
            Text(
                if (expanded) text else head,
                style = style,
                maxLines = if (expanded) Int.MAX_VALUE else foldedLines,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            )
        }
        // Copyable folded or open, as the raw text always was.
        if (selectable) SelectionContainer { body() } else body()
        if (expanded || overflows || cut) {
            androidx.compose.material3.TextButton(
                onClick = { expanded = !expanded },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier.height(40.dp),
            ) {
                Text(
                    stringResource(if (expanded) R.string.common_collapse else R.string.orbit_show_all),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** "Hidden in chat · kind" for a developer looking at a row the chat does not show. */
@Composable
internal fun OrbitHiddenBadge(mode: ChatMessageDisplayMode, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(50), color = colors.tertiary, modifier = modifier) {
        Text(
            stringResource(R.string.orbit_dev_raw_hidden, stringResource(when (mode) {
                ChatMessageDisplayMode.HIDDEN_PLACEHOLDER -> R.string.orbit_dev_mode_placeholder
                ChatMessageDisplayMode.ASSISTANT_INTERMEDIATE -> R.string.orbit_dev_mode_intermediate
                ChatMessageDisplayMode.TOOL_DELIVERED -> R.string.orbit_dev_mode_delivered
                ChatMessageDisplayMode.COLLABORATION_EVENT,
                ChatMessageDisplayMode.COLLABORATION_TASK -> R.string.orbit_dev_mode_subagent
                else -> R.string.orbit_dev_mode_other
            })),
            style = MaterialTheme.typography.labelSmall, color = colors.onTertiary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
