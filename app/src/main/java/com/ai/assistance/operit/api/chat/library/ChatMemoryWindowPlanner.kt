package com.ai.assistance.operit.api.chat.library

import com.ai.assistance.operit.data.model.ChatMessage

internal object ChatMemoryWindowPlanner {

    const val DEFAULT_WINDOW_MESSAGE_COUNT = 32
    const val MIN_WINDOW_MESSAGE_COUNT = 8
    const val MAX_WINDOW_MESSAGE_COUNT = 48

    data class Window(
        val messages: List<ChatMessage>,
        val sourceMessageCount: Int
    )

    fun plan(
        messages: List<ChatMessage>,
        windowMessageCount: Int,
        timeScope: ChatMemoryRebuildTimeScope
    ): List<Window> {
        val boundedWindowSize =
            windowMessageCount.coerceIn(MIN_WINDOW_MESSAGE_COUNT, MAX_WINDOW_MESSAGE_COUNT)
        val scopedMessages =
            when (timeScope) {
                ChatMemoryRebuildTimeScope.EntireChat -> messages
                is ChatMemoryRebuildTimeScope.InclusiveLocalRange ->
                    messages.filter { timeScope.contains(it.timestamp) }
            }
        val windows = mutableListOf<Window>()
        val pendingSourceMessages = mutableListOf<ChatMessage>()
        val pendingContextMessages = mutableListOf<ChatMessage>()
        // A time-ranged rebuild can start in the middle of a turn, so the replies at the very start of
        // the range have no user message of their own inside it. The user turn that prompted them sits
        // just before the range, and carrying it in as context is what lets those replies be learned
        // instead of dropped: every window needs a user message for the extractor to work from.
        val carriedUserTurn =
            (timeScope as? ChatMemoryRebuildTimeScope.InclusiveLocalRange)?.let { range ->
                messages
                    .filter { it.sender == "user" && it.content.isNotBlank() && it.timestamp < range.startInclusiveMs }
                    .maxByOrNull { it.timestamp }
            }
        var currentTurnUser: ChatMessage? = null

        fun emitWindow() {
            // A window opened by carried context has source replies but no user turn of its own, so
            // the reply count is the only reliable emptiness signal here.
            if (pendingSourceMessages.isEmpty()) return
            windows += Window(
                messages = pendingContextMessages.toList() + pendingSourceMessages.toList(),
                sourceMessageCount = pendingSourceMessages.size
            )
            pendingSourceMessages.clear()
            pendingContextMessages.clear()
        }

        fun beginWindow(contextUser: ChatMessage? = null) {
            contextUser?.let(pendingContextMessages::add)
        }

        scopedMessages.sortedBy { it.timestamp }.forEach { message ->
            when (message.sender) {
                "user" -> {
                    if (message.content.isBlank()) return@forEach
                    if (
                        pendingSourceMessages.isNotEmpty() &&
                            pendingSourceMessages.size >= boundedWindowSize - 1
                    ) {
                        emitWindow()
                    }
                    if (pendingSourceMessages.isEmpty()) {
                        beginWindow()
                    }
                    currentTurnUser = message
                    pendingSourceMessages += message
                }

                "ai", "assistant" -> {
                    if (message.content.isBlank()) return@forEach
                    val turnUser = currentTurnUser ?: run {
                        // Without a carried user turn there is no query to attach, and a window the
                        // extractor would skip anyway is worse than an honest gap.
                        val carried = carriedUserTurn ?: return@forEach
                        if (pendingSourceMessages.isEmpty() && pendingContextMessages.isEmpty()) {
                            beginWindow(contextUser = carried)
                        }
                        carried
                    }
                    if (pendingSourceMessages.size >= boundedWindowSize) {
                        // A group chat can have more assistant replies than the selected window
                        // size. Repeat only the prompting user turn as context for the next
                        // slice so every request remains bounded and every reply is retained.
                        emitWindow()
                        beginWindow(contextUser = turnUser)
                    }
                    pendingSourceMessages += message
                }
            }
        }
        emitWindow()
        return windows
    }
}
