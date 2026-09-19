package com.ai.assistance.operit.ui.permissions

import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.util.ChatUtils

internal const val MAX_TRANSCRIPT_CANDIDATES = 24
internal const val MAX_TRANSCRIPT_MESSAGE_CHARS = 4_000
internal const val MAX_TRANSCRIPT_CHARS = 16_000

/** No ceiling of the window's own: only the candidate tail and the character budget bound it. */
internal const val NO_MESSAGE_CAP = Int.MAX_VALUE

/**
 * The reviewer's ceiling. It is deliberately [NO_MESSAGE_CAP]: the blocking reviewer has to see the
 * whole budget, and it is pinned here so a count cap cannot come back unnoticed. Only the
 * asynchronous classifier keeps a ceiling of its own.
 */
internal const val REVIEWER_MAX_TRANSCRIPT_MESSAGES = NO_MESSAGE_CAP

internal const val NO_TRANSCRIPT_TEXT = "(no transcript available)"
internal const val OMITTED_ENTRIES_NOTICE =
    "Host notice: older transcript entries are omitted, so this view is partial. Missing context is not a grant."
internal const val USER_ANCHOR_PREFIX = "[user anchor; older messages omitted]"

/**
 * What a delivered turn is called in the window. The reviewer has to read a turn the app delivered
 * as a delivery, and the row's own role name is the owner's, so the label is where that shows.
 */
internal const val DELIVERED_TURN_LABEL = "user turn delivered by the app"

/** One parent message offered to the transcript window. */
internal data class PermissionReviewTranscriptMessage(
    val timestamp: Long,
    val sender: String,
    val roleName: String,
    val content: String,
    val displayMode: ChatMessageDisplayMode = ChatMessageDisplayMode.NORMAL,
) {
    val isUser: Boolean
        get() =
            sender.equals("user", ignoreCase = true) || roleName.equals("user", ignoreCase = true)

    /**
     * Who the entry reads as: the owner, the agent path a collaboration row came from, or the
     * delivery a tool or the host made into this chat.
     */
    val label: String
        get() =
            if (displayMode.isDeliveredTurn) DELIVERED_TURN_LABEL
            else roleName.ifBlank { sender }
}

/** One rendered transcript entry, with the sender class the anchor fallback needs. */
internal data class PermissionReviewTranscriptEntry(val rendered: String, val isUser: Boolean)

/** Renders the sanitized entries, dropping blank content and the entry a live message replaces. */
internal fun permissionReviewTranscriptEntries(
    candidates: List<PermissionReviewTranscriptMessage>,
    maxMessageChars: Int,
    skipTimestamp: Long? = null,
): List<PermissionReviewTranscriptEntry> =
    candidates.mapNotNull { message ->
        if (skipTimestamp != null && message.timestamp == skipTimestamp) {
            return@mapNotNull null
        }
        val content =
            truncateTranscriptMessage(
                permissionReviewTranscriptContent(
                    sender = message.sender,
                    roleName = message.roleName,
                    content = message.content,
                ),
                maxMessageChars,
            )
        if (content.isBlank()) return@mapNotNull null
        PermissionReviewTranscriptEntry(
            rendered = "[${message.label}]\n$content\n",
            isUser = message.isUser,
        )
    }

/**
 * Renders the newest entries that fit the character budget, oldest first.
 *
 * The window takes entries from the end, so growing the turn appends and the text an earlier review
 * already sent stays put while the budget holds. A provider that caches prompt prefixes then charges
 * the shared part at its cache price instead of the full input price, which is what makes a wider
 * window affordable: the entries gained here are the ones an earlier count cap used to cut, and a
 * restriction the owner stated early stays readable.
 *
 * The stability ends once the budget is full: the oldest entries then fall out and what is left
 * moves, so the guarantee is "stable while the budget holds", never "stable for a whole turn". Both
 * that and any ceiling are reported rather than silent: [OMITTED_ENTRIES_NOTICE] tells the reviewer
 * its view is partial, and a window that lost its user turn re-adds the last user entry so an action
 * is never read without the request it answers.
 *
 * [olderEntriesOmitted] is how the caller reports material that was cut before the window saw it,
 * which the window cannot notice on its own; the candidate tail a caller applies is the case that
 * needs it.
 */
internal fun renderPermissionReviewTranscriptWindow(
    entries: List<PermissionReviewTranscriptEntry>,
    maxMessages: Int,
    maxChars: Int,
    olderEntriesOmitted: Boolean = false,
): String {
    val newestFirst = mutableListOf<PermissionReviewTranscriptEntry>()
    var selectedChars = 0
    var omitted = false
    for (entry in entries.asReversed()) {
        if (newestFirst.size >= maxMessages) {
            omitted = true
            break
        }
        if (selectedChars + entry.rendered.length > maxChars) {
            omitted = true
            continue
        }
        newestFirst += entry
        selectedChars += entry.rendered.length
    }
    val selected = newestFirst.asReversed()
    val rendered = mutableListOf<String>()
    if (omitted || olderEntriesOmitted) rendered += "$OMITTED_ENTRIES_NOTICE\n"
    if (selected.none { entry -> entry.isUser }) {
        entries.lastOrNull { entry -> entry.isUser }?.let { anchor ->
            rendered += "$USER_ANCHOR_PREFIX\n${anchor.rendered}"
        }
    }
    rendered += selected.map { entry -> entry.rendered }
    return rendered.joinToString(separator = "").ifBlank { NO_TRANSCRIPT_TEXT }
}

/** Loads the parent history exactly as the window and the continuation cursor both read it. */
internal suspend fun loadPermissionReviewTranscriptMessages(
    chatCore: ChatServiceCore,
    parentChatId: String,
): List<PermissionReviewTranscriptMessage> =
    runCatching { chatCore.getChatHistoryDelegate().getChatHistory(parentChatId) }
        .getOrElse { emptyList() }
        .map { message ->
            PermissionReviewTranscriptMessage(
                timestamp = message.timestamp,
                sender = message.sender,
                roleName = message.roleName,
                content = message.content,
                displayMode = message.displayMode,
            )
        }

/**
 * Renders the recent parent transcript shared by the blocking reviewer and the asynchronous risk
 * classifier, bounded by [maxChars] and by the ceiling the caller passes. The reviewer passes
 * [REVIEWER_MAX_TRANSCRIPT_MESSAGES] and so has no count ceiling of its own, which makes its window a
 * superset of the window a count cap used to produce; the classifier passes its own smaller ceiling.
 * The classifier additionally receives the retained user instructions, so a restriction that scrolled
 * out of this window is still visible to it.
 *
 * The candidate tail bounds the history the window is even offered, so a history beyond it is
 * reported as an omission: the reviewer prompt reads a missing [OMITTED_ENTRIES_NOTICE] as "nothing
 * was left out", and a conversation whose tail fits the budget would otherwise look complete. The
 * check is made on the offered messages, so it reports an omission whenever the tail was cut rather
 * than only when an entry was really dropped, which errs towards saying the view is partial.
 */
internal suspend fun buildPermissionReviewTranscript(
    chatCore: ChatServiceCore,
    parentChatId: String,
    timingScopeId: String?,
    liveAssistantContent: String?,
    maxMessages: Int,
    maxMessageChars: Int = MAX_TRANSCRIPT_MESSAGE_CHARS,
    maxChars: Int = MAX_TRANSCRIPT_CHARS,
): String =
    buildPermissionReviewTranscript(
        history = loadPermissionReviewTranscriptMessages(chatCore, parentChatId),
        timingScopeId = timingScopeId,
        liveAssistantContent = liveAssistantContent,
        maxMessages = maxMessages,
        maxMessageChars = maxMessageChars,
        maxChars = maxChars,
    )

/**
 * The same window over a history the caller already loaded, so one review reads the history once and
 * uses the same list for its window and for its continuation cursor.
 */
internal fun buildPermissionReviewTranscript(
    history: List<PermissionReviewTranscriptMessage>,
    timingScopeId: String?,
    liveAssistantContent: String?,
    maxMessages: Int,
    maxMessageChars: Int = MAX_TRANSCRIPT_MESSAGE_CHARS,
    maxChars: Int = MAX_TRANSCRIPT_CHARS,
): String {
    val sanitizedLiveAssistant = sanitizedLiveAssistantContent(liveAssistantContent)
    val persistedLiveAssistant =
        sanitizedLiveAssistant?.let { persistedLiveAssistantMessage(history, timingScopeId) }

    val candidates = history.takeLast(MAX_TRANSCRIPT_CANDIDATES)
    val entries =
        permissionReviewTranscriptEntries(
                candidates = candidates,
                maxMessageChars = maxMessageChars,
                skipTimestamp = persistedLiveAssistant?.timestamp,
            )
            .toMutableList()
    sanitizedLiveAssistant?.let { liveContent ->
        val role =
            persistedLiveAssistant?.let { message -> message.roleName.ifBlank { message.sender } }
                ?: "assistant"
        val rendered = "[$role]\n${truncateTranscriptMessage(liveContent, maxMessageChars)}\n"
        entries += PermissionReviewTranscriptEntry(rendered = rendered, isUser = false)
    }

    return renderPermissionReviewTranscriptWindow(
        entries = entries,
        maxMessages = maxMessages,
        maxChars = maxChars,
        olderEntriesOmitted = candidates.size < history.size,
    )
}

/** The live assistant text a review may show, or null when there is nothing worth showing. */
internal fun sanitizedLiveAssistantContent(liveAssistantContent: String?): String? =
    liveAssistantContent
        ?.let { content ->
            permissionReviewTranscriptContent(
                sender = "ai",
                roleName = "assistant",
                content = content,
            )
        }
        ?.takeIf(String::isNotBlank)

/** The persisted message a live assistant entry stands in for, matched by the turn's scope id. */
internal fun persistedLiveAssistantMessage(
    history: List<PermissionReviewTranscriptMessage>,
    timingScopeId: String?,
): PermissionReviewTranscriptMessage? =
    history.lastOrNull { message ->
        isAssistantTranscriptMessage(message.sender, message.roleName) &&
            message.timestamp.toString() == timingScopeId
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

internal fun isAssistantTranscriptMessage(sender: String, roleName: String): Boolean =
    sender.equals("ai", ignoreCase = true) || roleName.equals("assistant", ignoreCase = true)
