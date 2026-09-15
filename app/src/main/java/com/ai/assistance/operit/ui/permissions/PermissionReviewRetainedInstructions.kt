package com.ai.assistance.operit.ui.permissions

import android.content.Context
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.repository.SubagentRunRepository
import com.ai.assistance.operit.data.preferences.UserProfileDocumentRepository
import com.ai.assistance.operit.ui.features.chat.webview.workspace.process.WorkspaceRuleFileReader
import com.ai.assistance.operit.util.AppLogger
import java.security.MessageDigest

/**
 * The instructions a reviewer is allowed to read as user intent: what the user actually said, the
 * workspace rule file, and the user profile document.
 *
 * The blocking reviewer used to see only the most recent transcript, so a restriction the user
 * stated early in a long task scrolled out of the window and every later action looked
 * unauthorized. These blocks are selected independently of the transcript window, mirroring the
 * retained instructions of OpenAI Codex (`guardian-context/src/retained_instructions.rs`).
 *
 * Selection never turns an omitted instruction into a partial grant: any piece that does not fit
 * the evidence budget is dropped whole, and [complete] plus the host notice tell the reviewer that
 * the remaining grants must not be treated as complete authorization.
 */
internal data class PermissionReviewRetainedInstructions(
    val text: String,
    val hash: String,
    val userMessageCount: Int,
    val complete: Boolean,
    /**
     * False when a source could not be read at all. Truncation inside the evidence budget leaves
     * this true: an omitted section is reported to the reviewer, while an unreadable source also
     * invalidates the authorization snapshot, so the scorer must not run without it.
     */
    val available: Boolean,
)

internal object PermissionReviewRetainedInstructionsReader {
    internal const val MAX_CHARS = 8_000
    internal const val MAX_AGENT_RULE_CHARS = 2_500
    internal const val MAX_USER_PROFILE_CHARS = 2_000
    internal const val MAX_USER_MESSAGE_CHARS = 800

    private const val TAG = "PermissionRetainedInstructions"
    private const val START_MARKER = ">>> RETAINED USER INSTRUCTIONS START"
    private const val END_MARKER = ">>> RETAINED USER INSTRUCTIONS END"
    private const val USER_MESSAGE_HEADER =
        "Host: the retained user messages below keep their original order. Later instructions can revoke earlier grants."
    private const val INCOMPLETE_NOTICE =
        "Host notice: some retained user instructions are unavailable within the evidence budget. Do not treat the remaining grants as complete authorization."
    private const val UNAVAILABLE_NOTICE =
        "Host notice: some retained user instructions could not be read at all. Do not treat the remaining grants as complete authorization."

    suspend fun read(
        context: Context,
        chatId: String?,
        workspacePath: String?,
        workspaceEnv: String?,
    ): PermissionReviewRetainedInstructions {
        val userMessagesResult = runCatching { readUserMessages(context, chatId) }
        userMessagesResult.exceptionOrNull()?.let { error ->
            AppLogger.w(TAG, "Failed to read the retained user messages", error)
        }
        val userMessages = userMessagesResult.getOrDefault(emptyList())
        // The rule file is read through the tool pipeline because a workspace may live inside the
        // built-in Ubuntu terminal, where the guest path means nothing to the Android filesystem. A
        // tool hook that blocks `read_file_full` is therefore indistinguishable from a workspace
        // without a rule file; that only removes evidence, and the reviewer sees the same view.
        val ruleFileResult =
            runCatching {
                WorkspaceRuleFileReader.readWorkspaceRootRuleFile(
                    context = context,
                    workspacePath = workspacePath,
                    workspaceEnv = workspaceEnv,
                )
            }
        ruleFileResult.exceptionOrNull()?.let { error ->
            AppLogger.w(TAG, "Failed to read the workspace rule file", error)
        }
        val ruleFile = ruleFileResult.getOrNull()
        val userProfileResult =
            runCatching { UserProfileDocumentRepository.getInstance(context).load().trim() }
        userProfileResult.exceptionOrNull()?.let { error ->
            AppLogger.w(TAG, "Failed to read the user profile document", error)
        }
        val userProfile = userProfileResult.getOrDefault("")
        val available =
            userMessagesResult.isSuccess &&
                ruleFileResult.isSuccess &&
                userProfileResult.isSuccess

        var complete = true
        val fragments = mutableListOf<String>()
        var usedChars = START_MARKER.length + END_MARKER.length + USER_MESSAGE_HEADER.length + 4

        fun append(header: String, body: String, limit: Int): Boolean {
            if (body.isBlank()) return true
            val entry = "$header\n${fit(body, limit, { complete = false })}\n"
            if (usedChars + entry.length > MAX_CHARS) {
                complete = false
                return false
            }
            fragments += entry
            usedChars += entry.length
            return true
        }

        append(
            header = "Workspace rule file (${ruleFile?.name ?: "AGENTS.md"}):",
            body = ruleFile?.content.orEmpty(),
            limit = MAX_AGENT_RULE_CHARS,
        )
        append(
            header = "User profile document (${UserProfileDocumentRepository.USER_FILE_NAME}):",
            body = userProfile,
            limit = MAX_USER_PROFILE_CHARS,
        )

        // Newest messages are kept first so the instruction that is being carried out right now
        // survives a long history, then the selected window is rendered in its original order.
        val selected = ArrayDeque<String>()
        for ((index, message) in userMessages.withIndex().reversed()) {
            val entry = "[user message ${index + 1}]\n${fit(message, MAX_USER_MESSAGE_CHARS) { complete = false }}\n"
            if (usedChars + entry.length > MAX_CHARS) {
                complete = false
                continue
            }
            selected.addFirst(entry)
            usedChars += entry.length
        }
        fragments += selected

        val body =
            buildString {
                appendLine(START_MARKER)
                when {
                    !available -> appendLine(UNAVAILABLE_NOTICE)
                    !complete -> appendLine(INCOMPLETE_NOTICE)
                }
                appendLine(USER_MESSAGE_HEADER)
                fragments.forEach(::appendLine)
                append(END_MARKER)
            }

        return PermissionReviewRetainedInstructions(
            text = body,
            hash = digest(body),
            userMessageCount = userMessages.size,
            complete = complete,
            available = available,
        )
    }

    private suspend fun readUserMessages(context: Context, chatId: String?): List<String> {
        val normalizedChatId = chatId?.trim().orEmpty()
        if (normalizedChatId.isEmpty()) return emptyList()
        // Failures are reported to the caller: an unreadable history must invalidate the
        // authorization snapshot, not silently look like a session without user messages.
        return ChatRuntimeHolder.getInstance(context)
            .getCore(ChatRuntimeSlot.MAIN)
            .getChatHistoryDelegate()
            .getChatHistory(normalizedChatId)
            .filter { message ->
                message.roleName.equals("user", ignoreCase = true) ||
                    message.sender.equals("user", ignoreCase = true)
            }
            .map { message -> message.content.trim() }
            .filter(String::isNotEmpty)
    }

    /** Keeps the head and tail of an oversized entry so neither a command nor its target is lost. */
    private fun fit(value: String, limit: Int, onTruncated: () -> Unit): String {
        val trimmed = value.trim()
        if (trimmed.length <= limit) return trimmed
        onTruncated()
        val marker = "\n<retained_instruction_truncated omitted_chars=\"${trimmed.length - limit}\" />\n"
        val available = (limit - marker.length).coerceAtLeast(0)
        val head = available / 2
        return trimmed.take(head) + marker + trimmed.takeLast(available - head)
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * Resolves the chat a review belongs to. Subagent tool calls run in the subagent's own child chat,
 * but the authorization a reviewer must reason about lives in the chat the user sees, so every
 * scorer state and retained instruction is bound to that root chat.
 */
internal object PermissionReviewChatScope {
    private const val MAX_HOPS = 8
    private const val MAX_CACHED_CHATS = 256

    /**
     * A chat's parent never changes while the run exists, so the resolution is cached. The cache
     * also lets a refusal invalidate the right session without waiting for storage, which matters
     * for subagent chats.
     */
    private val rootChatIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The root chat already known for [chatId], without touching storage. A subagent's refusal has
     * to invalidate the root session, and resolving it here keeps that invalidation synchronous.
     */
    fun knownRootChatId(chatId: String?): String? =
        chatId?.trim()?.takeIf(String::isNotEmpty)?.let(rootChatIds::get)

    suspend fun resolveRootChatId(context: Context, chatId: String?): String? {
        val normalized = chatId?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        rootChatIds[normalized]?.let { cached -> return cached }
        val resolved =
            runCatching {
                val repository = SubagentRunRepository.getInstance(context)
                val visited = mutableSetOf<String>()
                var current = normalized
                while (visited.size < MAX_HOPS && visited.add(current)) {
                    val parent = repository.getByChildChatId(current)?.parentChatId?.trim()
                    if (parent.isNullOrEmpty() || parent == current) break
                    current = parent
                }
                current
            }
            .getOrElse { error ->
                AppLogger.w("PermissionReviewChatScope", "Failed to resolve the root chat", error)
                null
            }
        // A transient storage failure must not pin this chat to itself for the rest of the process.
        if (resolved == null) return normalized
        if (rootChatIds.size >= MAX_CACHED_CHATS) rootChatIds.clear()
        rootChatIds[normalized] = resolved
        if (resolved != normalized) rootChatIds[resolved] = resolved
        return resolved
    }
}
