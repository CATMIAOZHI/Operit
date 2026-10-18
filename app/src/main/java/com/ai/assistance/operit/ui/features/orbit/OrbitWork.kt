package com.ai.assistance.operit.ui.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.services.core.TranscriptStructure
import com.ai.assistance.operit.services.core.isAgentInputCard
import com.ai.assistance.operit.ui.features.chat.components.OrbitSpeech
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Where a row of Orbit's activity list leads: the record of one piece of work, read only.
 * A chat turn shows the user's message and every step Orbit took to answer it; a background run
 * shows the hidden chat it ran in.
 */
internal sealed interface OrbitProcess {
    val title: String
    val time: Long

    data class ChatTurn(
        val chatId: String,
        val timestamps: List<Long>,
        override val title: String,
        override val time: Long,
    ) : OrbitProcess

    data class TaskRun(
        val runId: String,
        val generation: Long,
        val taskId: String,
        override val title: String,
        override val time: Long,
        /** Still working when opened; the record then keeps itself up to date. */
        val running: Boolean = false,
    ) : OrbitProcess {
        /** The hidden chat the run worked in; see OrbitTaskRunner. */
        val chatId: String get() =
            "orbit-task-" + UUID.nameUUIDFromBytes("$runId:$generation".toByteArray())
    }
}

/** One chat turn in which Orbit did work (thought or used tools) before it answered. */
internal data class OrbitChatTurn(
    val process: OrbitProcess.ChatTurn,
    /** What Orbit finally said, first line; empty when it said nothing. */
    val reply: String,
)

/** Chat turns with work behind them; [failed] when some chat could not be read. */
internal data class OrbitChatTurns(val turns: List<OrbitChatTurn>, val failed: Boolean)

internal object OrbitWork {
    private const val TURNS_PER_CHAT = 60
    private const val TITLE_LENGTH = 60
    /** The context the app adds to a user message; anything else the user typed stays. */
    private val contextMarkup = listOf(
        com.ai.assistance.operit.util.ChatMarkupRegex.replyToTag,
        com.ai.assistance.operit.util.ChatMarkupRegex.attachmentTag,
        com.ai.assistance.operit.util.ChatMarkupRegex.attachmentSelfClosingTag,
        com.ai.assistance.operit.util.ChatMarkupRegex.workspaceAttachmentTag,
        com.ai.assistance.operit.util.ChatMarkupRegex.memoryTag,
        com.ai.assistance.operit.util.ChatMarkupRegex.proxySenderTag,
    )
    private val attachmentMarkup = listOf(
        com.ai.assistance.operit.util.ChatMarkupRegex.attachmentTag,
        com.ai.assistance.operit.util.ChatMarkupRegex.attachmentSelfClosingTag,
        com.ai.assistance.operit.util.ChatMarkupRegex.workspaceAttachmentTag,
    )
    private val markdownImageOrLink = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
    private val markdownEmphasis = Regex("(\\*\\*|__|~~|`+)")
    private val markdownLineStart = Regex("^\\s*(#{1,6}\\s+|>\\s*|[-*+]\\s+|\\d+[.)]\\s+)")

    /**
     * The newest turns with work behind them in Orbit's chats, newest first: replies in which Orbit
     * called tools, and turns it answered in several steps. A plain answer is already all there is
     * to see in the chat.
     */
    suspend fun chatTurns(context: Context, chatIds: List<String>): OrbitChatTurns {
        val manager = ChatHistoryManager.getInstance(context)
        val attachmentOnly = context.getString(com.ai.assistance.operit.R.string.orbit_user_sent_attachment)
        val reactionTitle = context.getString(com.ai.assistance.operit.R.string.orbit_work_reaction_title)
        // Chats with a reply being written right now; that reply has no complete record yet.
        val streaming = com.ai.assistance.operit.api.chat.ChatRuntimeHolder.getInstance(context).let { holder ->
            com.ai.assistance.operit.api.chat.ChatRuntimeSlot.values()
                .flatMapTo(hashSetOf()) { holder.getCore(it).activeStreamingChatIds.value }
        }
        var failed = false
        val turns = chatIds.distinct().flatMap { chatId ->
            try {
                turnsOf(manager, chatId, attachmentOnly, reactionTitle, writingNow = chatId in streaming)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.ai.assistance.operit.util.AppLogger.w("OrbitWork", "Could not read turns of a chat: ${e.message}")
                failed = true
                emptyList()
            }
        }.sortedByDescending { it.process.time }
        return OrbitChatTurns(turns, failed)
    }

    /** One turn found in a chat: its key time, final reply and every row it is made of. */
    private class Found(val key: Long, val finalTimestamp: Long, val members: Set<Long>, val byMarkup: Boolean = false)

    private suspend fun turnsOf(
        manager: ChatHistoryManager,
        chatId: String,
        attachmentOnly: String,
        reactionTitle: String,
        writingNow: Boolean,
    ): List<OrbitChatTurn> {
        val rows = manager.loadChatMessageProcessMetadata(chatId)
        if (rows.isEmpty()) return emptyList()
        val indexOf = HashMap<Long, Int>(rows.size).apply { rows.forEachIndexed { i, row -> put(row.timestamp, i) } }
        val structure = TranscriptStructure(rows)
        // Most turns are one reply holding its thinking and tool calls; a turn the user spoke into
        // while it ran is several rows, which the transcript structure already groups.
        val single = manager.loadWorkReplyTimestamps(chatId)
            .distinct()
            .filter { it !in structure.turnByTimestamp }
            .mapNotNull { timestamp ->
                val row = rows.getOrNull(indexOf[timestamp] ?: return@mapNotNull null) ?: return@mapNotNull null
                Found(row.sentAt.takeIf { it > 0 } ?: timestamp, timestamp, setOf(timestamp), byMarkup = true)
            }
        // A reply still being written, or cut off before it finished, has no complete record; it
        // joins the list once it ends. The user may have spoken since, so it need not be last.
        val writing = (rows.lastOrNull { it.sender == "ai" && it.completedAt <= 0L }
            ?: rows.lastOrNull()?.takeIf { writingNow && it.sender == "ai" })?.timestamp
        val turns = (structure.turns.map { Found(it.key, it.finalTimestamp, it.memberTimestamps) } + single)
            .filter { writing == null || writing !in it.members }
            .sortedBy { it.key }
            .takeLast(TURNS_PER_CHAT)
        // The user's message that started each turn: the last one right before its first step.
        // A tapback the user put on Orbit's message starts a turn too, through a hidden notice.
        val hiddenBefore = HashMap<Long, Long>()
        val askedBy = turns.associate { turn ->
            val first = turn.members.mapNotNull { indexOf[it] }.minOrNull() ?: -1
            var asked: Long? = null
            var i = first - 1
            while (i >= 0) {
                val row = rows[i]
                if (row.sender == "ai") break
                // Only a message the user really wrote; placeholders and delivered rows are skipped.
                if (row.sender == "user" && row.displayMode == com.ai.assistance.operit.data.model.ChatMessageDisplayMode.NORMAL.name) {
                    asked = row.timestamp
                    break
                }
                if (row.sender == "user" && turn.key !in hiddenBefore &&
                    row.displayMode == com.ai.assistance.operit.data.model.ChatMessageDisplayMode.HIDDEN_PLACEHOLDER.name) {
                    hiddenBefore[turn.key] = row.timestamp
                }
                i--
            }
            turn.key to asked
        }
        val wanted = turns.map { it.finalTimestamp } + askedBy.values.filterNotNull() + hiddenBefore.values
        val bodies = manager.loadChatMessagesByTimestamps(chatId, wanted).associateBy { it.timestamp }
        // The text search also matches a reply that only quotes markup; keep real work only.
        return turns.filter { turn ->
            !turn.byMarkup || bodies[turn.finalTimestamp]?.let { hasWork(it.content) } == true
        }.filterNot { turn ->
            // Orbit chose to stay quiet after a tapback: nothing happened worth listing.
            bodies[turn.finalTimestamp]?.content?.let { content ->
                content.contains("name=\"${com.ai.assistance.operit.features.orbit.OrbitReactionNotice.NOTHING_TO_DO}\"") &&
                    OrbitSpeech.of(content).isBlank()
            } == true
        }.map { turn ->
            val reaction = hiddenBefore[turn.key]?.let { ts ->
                bodies[ts]?.content?.let { com.ai.assistance.operit.features.orbit.OrbitReactionNotice.emojiOf(it) }?.let { ts to it }
            }
            // The nearest of the two started the turn: a tapback after the user's last message wins.
            val asked = if (reaction != null && (askedBy[turn.key] ?: Long.MIN_VALUE) < reaction.first) reaction.first
                else askedBy[turn.key]
            val title = if (reaction != null && asked == reaction.first) reactionTitle.format(reaction.second)
                else asked?.let { bodies[it] }?.let { userLine(it.content, attachmentOnly) }.orEmpty()
            OrbitChatTurn(
                process = OrbitProcess.ChatTurn(
                    chatId = chatId,
                    // A tapback notice only names the turn; its hidden wording never shows in the details.
                    timestamps = (listOfNotNull(asked?.takeIf { it != reaction?.first }) + turn.members).distinct().sorted(),
                    title = title,
                    time = turn.key,
                ),
                reply = bodies[turn.finalTimestamp]?.let { firstLine(plain(OrbitSpeech.of(it.content))) }.orEmpty(),
            )
        }
    }

    /** The messages of one piece of work, oldest first, as they were stored. */
    suspend fun messages(context: Context, process: OrbitProcess): List<ChatMessage> {
        val manager = ChatHistoryManager.getInstance(context)
        return when (process) {
            is OrbitProcess.ChatTurn -> manager.loadChatMessagesByTimestamps(process.chatId, process.timestamps)
                .filterNot { it.displayMode == com.ai.assistance.operit.data.model.ChatMessageDisplayMode.HIDDEN_PLACEHOLDER }
            // The task's own instructions open the hidden chat; the work starts after them.
            is OrbitProcess.TaskRun -> manager.loadChatMessages(process.chatId).filter { it.sender != "user" }
        }
    }

    private val codeSpans = Regex("```[\\s\\S]*?(```|$)|`[^`\\n]*`")

    /** Whether a reply really called a tool or thought before answering; quoted examples don't count. */
    private fun hasWork(content: String): Boolean {
        if (com.ai.assistance.operit.ui.features.chat.viewmodel.messageHasExecutableToolCall("ai", content)) return true
        val outsideCode = content.replace(codeSpans, "").trim()
        return com.ai.assistance.operit.util.ChatUtils.removeThinkingContent(outsideCode) != outsideCode
    }

    /** What the user typed, without the context the app attached to the message. */
    private fun userLine(content: String, attachmentOnly: String): String =
        listLine(userText(content, attachmentOnly))

    /**
     * The user's own words in a stored message, all lines. A message that only carried files reads
     * as [attachmentOnly].
     */
    fun userText(content: String, attachmentOnly: String): String {
        val words = contextMarkup.fold(content) { text, markup -> text.replace(markup, "") }.trim()
        return words.ifEmpty { if (attachmentMarkup.any { it.containsMatchIn(content) }) attachmentOnly else "" }
    }

    /** One short line of plain words for a list row, from text that may be Markdown. */
    fun listLine(text: String): String = firstLine(plain(text))

    /** Markdown reduced to the words a list row can show: link and image labels, no marks. */
    private fun plain(text: String): String = text.lineSequence()
        .filterNot { it.trimStart().startsWith("```") }
        .joinToString("\n") { line ->
            line.replace(markdownImageOrLink, "$1").replace(markdownEmphasis, "").replace(markdownLineStart, "")
        }

    private fun firstLine(text: String): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().let {
            if (it.length > TITLE_LENGTH) it.take(TITLE_LENGTH).trimEnd() + "…" else it
        }
}
