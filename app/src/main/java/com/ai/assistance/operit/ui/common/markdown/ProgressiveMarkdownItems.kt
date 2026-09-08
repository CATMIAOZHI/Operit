package com.ai.assistance.operit.ui.common.markdown

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R

internal val LocalProgressiveTranscript = compositionLocalOf { false }
internal val LocalTranscriptUserScrolling = compositionLocalOf { false }
internal val LocalTranscriptFollowing = compositionLocalOf { true }
internal val LocalRevealTranscriptHistory = compositionLocalOf<() -> Unit> { {} }

/** Keep groups intact: splitting inside a tool/result pair changes its display identity. */
@Composable
internal fun ProgressiveMarkdownItems(
    items: List<MarkdownGroupedItem>,
    render: @Composable (List<MarkdownGroupedItem>) -> Unit,
) {
    if (!LocalProgressiveTranscript.current) {
        render(items)
        return
    }
    val following = LocalTranscriptFollowing.current
    val userScrolling = LocalTranscriptUserScrolling.current
    val revealHistory = LocalRevealTranscriptHistory.current
    var frozenStart by remember { mutableStateOf<Int?>(null) }
    var frozenEnd by remember { mutableStateOf<Int?>(null) }
    var revealedStart by remember { mutableStateOf<Int?>(null) }
    val tailStart = (items.size - 32).coerceAtLeast(0)
    val start = (revealedStart ?: frozenStart ?: tailStart)
        .coerceIn(0, items.size)
    val end = (frozenEnd ?: items.size).coerceIn(start, items.size)
    SideEffect {
        if (userScrolling && frozenStart == null) {
            frozenStart = start
            frozenEnd = items.size
        } else if (following && !userScrolling) {
            frozenStart = null
            frozenEnd = null
            revealedStart = null
        }
    }
    if (start > 0) {
        TextButton(onClick = {
            revealHistory()
            revealedStart = (start - 32).coerceAtLeast(0)
            frozenEnd = end
        }) {
            Text(stringResource(R.string.chat_show_earlier_response_blocks, start))
        }
    }
    render(items.subList(start, end))
    if (end < items.size) {
        TextButton(onClick = {
            revealHistory()
            frozenEnd = minOf(items.size, end + 32)
        }) {
            Text(stringResource(R.string.load_newer_history))
        }
    }
}
