package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.ui.common.markdown.TranscriptMarkdownSlice

internal data class TranscriptRow(
    val key: String,
    val messageIndex: Int,
    val section: ResponseMessageSection,
    val group: ResponseProcessGroup? = null,
    val loadMore: Boolean = false,
    val markdownSlice: TranscriptMarkdownSlice? = null,
    val preparingMarkdown: Boolean = false,
)

/** Summary keys survive body paging and completion, unlike the first visible process message. */
internal fun transcriptRows(
    messages: List<ChatMessage>,
    state: ResponseProcessState,
): List<TranscriptRow> = buildList {
    val occurrences = mutableMapOf<Long, Int>()
    messages.forEachIndexed { index, message ->
        val occurrence = occurrences.getOrDefault(message.timestamp, 0)
        occurrences[message.timestamp] = occurrence + 1
        val messageKey = "message:${message.timestamp}" + if (occurrence == 0) "" else ":$occurrence"
        val group = state.groups[index]
        if (group == null) {
            add(TranscriptRow(messageKey, index, ResponseMessageSection.ALL))
        } else {
            if (index == group.firstIndex && state.showSummary) {
                add(TranscriptRow("process:${group.key}", group.headerIndex, ResponseMessageSection.HEADER, group))
            }
            if (state.isExpanded(group.key) || index == group.finalIndex) {
                if (index == group.finalIndex && state.isExpanded(group.key) && state.hasMore(group.key)) {
                    add(TranscriptRow("more:${group.key}", -1, ResponseMessageSection.BODY, group, true))
                }
                add(TranscriptRow(messageKey, index,
                    if (state.showSummary) ResponseMessageSection.BODY else ResponseMessageSection.ALL, group))
            }
        }
    }
}
