package com.ai.assistance.operit.ui.permissions

import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.util.ChatUtils

internal const val MAX_TRANSCRIPT_CANDIDATES = 24
internal const val MAX_TRANSCRIPT_MESSAGES = 12
internal const val MAX_TRANSCRIPT_MESSAGE_CHARS = 4_000
internal const val MAX_TRANSCRIPT_CHARS = 16_000

/**
 * Renders the recent parent transcript shared by the blocking reviewer and the asynchronous risk
 * classifier. Both need the same window; the classifier additionally receives the retained user
 * instructions, so a restriction that scrolled out of this window is still visible to it.
 */
internal suspend fun buildPermissionReviewTranscript(
    chatCore: ChatServiceCore,
    parentChatId: String,
    timingScopeId: String?,
    liveAssistantContent: String?,
    maxMessages: Int = MAX_TRANSCRIPT_MESSAGES,
    maxMessageChars: Int = MAX_TRANSCRIPT_MESSAGE_CHARS,
    maxChars: Int = MAX_TRANSCRIPT_CHARS,
): String {
    val messages =
        runCatching { chatCore.getChatHistoryDelegate().getChatHistory(parentChatId) }
            .getOrElse { emptyList() }
            .takeLast(MAX_TRANSCRIPT_CANDIDATES)
    val newestFirst = mutableListOf<String>()
    var selectedChars = 0
    val sanitizedLiveAssistant =
        liveAssistantContent
            ?.let { content ->
                permissionReviewTranscriptContent(
                    sender = "ai",
                    roleName = "assistant",
                    content = content,
                )
            }
            ?.takeIf(String::isNotBlank)
    val persistedLiveAssistant =
        sanitizedLiveAssistant?.let {
            messages.lastOrNull { message ->
                isAssistantTranscriptMessage(message.sender, message.roleName) &&
                    message.timestamp.toString() == timingScopeId
            }
        }
    sanitizedLiveAssistant?.let { liveContent ->
        val role =
            persistedLiveAssistant?.let { message -> message.roleName.ifBlank { message.sender } }
                ?: "assistant"
        val entry = "[$role]\n${truncateTranscriptMessage(liveContent, maxMessageChars)}\n"
        newestFirst += entry
        selectedChars += entry.length
    }
    for (message in messages.asReversed()) {
        if (persistedLiveAssistant?.timestamp == message.timestamp) continue
        val role = message.roleName.ifBlank { message.sender }
        val content =
            truncateTranscriptMessage(
                permissionReviewTranscriptContent(
                    sender = message.sender,
                    roleName = message.roleName,
                    content = message.content,
                ),
                maxMessageChars,
            )
        if (content.isBlank()) continue
        val entry = "[$role]\n$content\n"
        if (selectedChars + entry.length > maxChars) continue
        newestFirst += entry
        selectedChars += entry.length
        if (newestFirst.size >= maxMessages) break
    }
    val selected = newestFirst.asReversed().toMutableList()
    val hasUserAnchor = selected.any { entry -> entry.startsWith("[user]", ignoreCase = true) }
    if (!hasUserAnchor) {
        messages
            .lastOrNull { message ->
                message.roleName.equals("user", ignoreCase = true) ||
                    message.sender.equals("user", ignoreCase = true)
            }
            ?.let { user ->
                selected.add(
                    0,
                    "[user anchor; older messages omitted]\n${truncateTranscriptMessage(user.content, maxMessageChars)}\n",
                )
            }
    }
    return selected.joinToString(separator = "").ifBlank { "(no transcript available)" }
}

internal fun permissionReviewTranscriptContent(
    sender: String,
    roleName: String,
    content: String,
): String =
    if (sender.equals("ai", ignoreCase = true) ||
        roleName.equals("assistant", ignoreCase = true)
    ) {
        ChatUtils.removeThinkingContent(content)
    } else {
        content
    }

internal fun truncateTranscriptMessage(value: String, maxMessageChars: Int): String {
    if (value.length <= maxMessageChars) return value
    val omitted = value.length - maxMessageChars
    val marker = "\n<transcript_truncated omitted_chars=\"$omitted\" />\n"
    val available = (maxMessageChars - marker.length).coerceAtLeast(0)
    val prefix = available / 2
    return value.take(prefix) + marker + value.takeLast(available - prefix)
}

private fun isAssistantTranscriptMessage(sender: String, roleName: String): Boolean =
    sender.equals("ai", ignoreCase = true) || roleName.equals("assistant", ignoreCase = true)
