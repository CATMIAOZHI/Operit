package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.stream.asFlow

/**
 * True inside an Orbit chat. Orbit chats read like messages with a person: only what the user and
 * Orbit said shows, as bubbles, with no avatars, names, model line, thinking or tool calls. The
 * work behind a reply is listed in Orbit's profile, where each turn opens with its full process.
 */
val LocalOrbitChatLook = compositionLocalOf { false }

/**
 * Cards an Orbit chat shows between its messages, such as the browser Orbit works in. Each sits
 * where it first showed. They get Orbit's bubble color.
 */
val LocalOrbitChatFooter = compositionLocalOf<(@Composable (bubbleColor: androidx.compose.ui.graphics.Color, slot: OrbitCardSlot) -> Unit)?> { null }

/**
 * One place between messages where cards can sit: after the shown message at [anchor] (the time of
 * that message, or [Long.MIN_VALUE] above the first one). A card remembers the place it first showed
 * in; until then, or when that message is no longer shown, it goes by its own time: made between
 * [from] and [until] (null after the last message). [shown] holds the times of all shown messages;
 * a place outside [loadedFrom]..[loadedUntil] is on a page not loaded now, so its cards wait there.
 */
@androidx.compose.runtime.Immutable
data class OrbitCardSlot(
    val anchor: Long,
    val from: Long,
    val until: Long?,
    val shown: Set<Long>,
    val loadedFrom: Long = Long.MIN_VALUE,
    val loadedUntil: Long = Long.MAX_VALUE,
)
/** Reply to one live browser card using the same composer as ordinary messages. */
val LocalOrbitLiveCardReply = compositionLocalOf<((String, Long) -> Unit)?> { null }

/** The name the user gave the Orbit on screen; empty outside an Orbit, where words say "Orbit". */
val LocalOrbitName = compositionLocalOf { "" }

/** [LocalOrbitName], or "Orbit" when none is given. */
@Composable
fun currentOrbitName(): String = LocalOrbitName.current.ifBlank { androidx.compose.ui.res.stringResource(R.string.nav_orbit) }

/** The fence an Orbit chat draws as a card from a tool: "```orbit-card" and one line of JSON. */
const val ORBIT_CARD_FENCE = "orbit-card"

/** The fence reply options are drawn from: "```orbit-options" and the set's id (see OrbitOptions). */
const val ORBIT_OPTIONS_FENCE = "orbit-options"

/** Emoji reactions Orbit put on the user's messages in this chat, by message time. */
val LocalOrbitReactions = compositionLocalOf<Map<String, String>> { emptyMap() }

/**
 * True in Orbit's developer view: rows the Orbit chat never shows (tapback notices, replies that
 * only stayed quiet) are drawn with a "hidden in chat" label so they can be audited.
 */
val LocalShowHiddenChatRows = compositionLocalOf { false }

/** Like a messenger: a time line shows above a message that comes this long after the one before. */
const val ORBIT_CHAT_TIME_GAP_MS = 5 * 60 * 1000L

/**
 * The rows that start a message coming [ORBIT_CHAT_TIME_GAP_MS] or more after the message shown
 * before it (or the first one), with that message's time. Rows are in order; rows of one message
 * sit together, so the first row of each message is where its time would go.
 */
fun orbitChatTimeRows(rowMessages: List<Pair<String, Long?>>): Map<String, Long> {
    val out = HashMap<String, Long>()
    var previous: Long? = null
    var lastMessageTime: Long? = null
    rowMessages.forEach { (key, time) ->
        if (time == null || time == lastMessageTime) return@forEach
        if (previous == null || time - previous!! >= ORBIT_CHAT_TIME_GAP_MS) out[key] = time
        previous = time
        lastMessageTime = time
    }
    return out
}

/** A message time the way a messenger writes it: "21:03", "昨天 21:03", "星期三 21:03", "10月20日 21:03". */
fun orbitChatTimeText(context: android.content.Context, time: Long, now: Long = System.currentTimeMillis()): String {
    val locale = java.util.Locale.getDefault()
    val clock = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(time))
    fun day(at: Long) = java.util.Calendar.getInstance().apply {
        timeInMillis = at
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    val then = day(time)
    val today = day(now)
    val days = Math.round((today.timeInMillis - then.timeInMillis) / 86_400_000.0)
    fun pattern(skeleton: String) = java.text.SimpleDateFormat(
        android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale,
    ).format(java.util.Date(time))
    return when {
        days <= 0L -> clock
        days == 1L -> context.getString(R.string.orbit_chat_time_yesterday, clock)
        days < 7L -> "${pattern("EEEE")} $clock"
        then.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) -> "${pattern("MMMd")} $clock"
        else -> "${pattern("yMMMd")} $clock"
    }
}

/** The small grey time line between messages in Orbit's chat. */
@Composable
fun OrbitChatTimeLine(time: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = orbitChatTimeText(context, time),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The small "hidden in chat" label used in the developer view. */
@Composable
fun OrbitHiddenInChatBadge(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.orbit_dev_hidden_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * A user row the chat hides (such as a tapback notice), as the developer view draws it: the label
 * and the first lines of what was sent. A tap shows all of it; a long press opens the menu.
 */
@Composable
fun OrbitHiddenUserRow(content: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            OrbitHiddenInChatBadge()
            Text(
                content.trim().ifEmpty { "∅" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 8,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * The user's own reactions on Orbit's bubbles and how to change them; null outside Orbit. A key
 * is a message time for its words, or a card's [OrbitPiece.key]; [toggle] gets the card's
 * [OrbitPiece.summary] as what Orbit hears, or null to read the message's words.
 */
class OrbitUserReactions(val byMessage: Map<String, String>, val toggle: (key: String, emoji: String, said: String?) -> Unit)

val LocalOrbitUserReactions = compositionLocalOf<OrbitUserReactions?> { null }

/**
 * One bubble of an Orbit reply. Its words share the message's key ([key] is its time); each card
 * and Dynamic UI card is a bubble of its own that can be liked, reacted to and replied to alone.
 */
data class OrbitPiece(
    val content: String,
    val key: String,
    /** For a card: what it is, for a reply quote or a reaction notice. Null for words. */
    val summary: String?,
    /** Words: the last words bubble shows the message's reaction. */
    val showsBadge: Boolean,
    /** For a Dynamic UI card: which one of its message it is, so each keeps its own state. */
    val uiIndex: Int? = null,
    /** A reply with no words: its first card also shows a reaction given to the whole message. */
    val fallbackBadgeKey: String? = null,
) {
    /**
     * The key this bubble's reaction is kept under: the whole message's older reaction while that
     * is all there is, so it is shown, changed and removed in one place; otherwise its own [key].
     */
    fun reactionKey(byMessage: Map<String, String>?): String =
        fallbackBadgeKey?.takeIf { byMessage?.get(key) == null && byMessage?.get(it) != null } ?: key
}

/**
 * What the long-press, double tap and swipe on one bubble do; given by the message row, so each
 * bubble of a reply acts on itself. Null outside Orbit or while selecting messages.
 */
class OrbitPieceActions(
    val like: ((OrbitPiece) -> Unit)?,
    val menu: ((OrbitPiece) -> Unit)?,
    val reply: ((OrbitPiece) -> Unit)?,
)

val LocalOrbitPieceActions = compositionLocalOf<OrbitPieceActions?> { null }

/** One bubble with its own gestures and its own reaction under it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OrbitPieceFrame(piece: OrbitPiece, content: @Composable () -> Unit) {
    // Every Dynamic UI card knows which one of its message it is, gestures or not (also while
    // Orbit is still writing), so its state never moves to another card.
    androidx.compose.runtime.CompositionLocalProvider(
        com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiBlockIndex provides piece.uiIndex,
    ) { OrbitPieceGestures(piece, content) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun OrbitPieceGestures(piece: OrbitPiece, content: @Composable () -> Unit) {
    val actions = LocalOrbitPieceActions.current
    if (actions == null) {
        content()
        return
    }
    val like = actions.like?.let { { it(piece) } }
    val menu = actions.menu?.let { { it(piece) } }
    val source = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    OrbitSwipeToReply(enabled = actions.reply != null, onReply = { actions.reply?.invoke(piece) }) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().combinedClickable(
                    interactionSource = source, indication = null,
                    onClick = {}, onLongClick = menu, onDoubleClick = like,
                ),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalOrbitMessageGestures provides OrbitMessageGestures(like, menu),
                ) {
                    content()
                }
            }
            val byMessage = LocalOrbitUserReactions.current?.byMessage
            val reaction = if (piece.showsBadge || piece.fallbackBadgeKey != null) byMessage?.get(piece.reactionKey(byMessage)) else null
            if (reaction != null) {
                ReactionBadge(reaction, stringResource(R.string.orbit_user_reaction_label, reaction), Alignment.CenterStart, start = 16.dp)
            }
        }
    }
}

/**
 * The message's own double-tap (like) and long-press (menu), for a card inside it that takes
 * taps itself: without them, a tap on the card never reaches the message around it.
 */
class OrbitMessageGestures(val onDoubleTap: (() -> Unit)?, val onLongPress: (() -> Unit)?)

val LocalOrbitMessageGestures = compositionLocalOf<OrbitMessageGestures?> { null }

/** What Orbit said in a message, without its thinking, tool calls, tool results or status lines. */
object OrbitSpeech {
    private val blank = Regex("\n{3,}")
    private val cardFence = Regex("```(?:a2ui(?:-ref)?|${ORBIT_CARD_FENCE}|${ORBIT_OPTIONS_FENCE})[^\\n]*\\n[\\s\\S]*?(?:```|$)")
    private val shownCard = Regex("```(?:a2ui(?:[ \\t]+card-\\d{1,2})?|${ORBIT_CARD_FENCE})[ \\t]*\\n")
    // Orbit's own words can never draw an Orbit card; only a tool's result can (see [cards]).
    // The renderer reads the tag after trimming, so "``` orbit-card" counts as the same fence.
    private val writtenOrbitCard = Regex("```[ \\t]*${ORBIT_CARD_FENCE}[^\\n]*(?:\\n[\\s\\S]*?```|[\\s\\S]*$)")
    private val cardToolResult = Regex(
        "^<tool_result[A-Za-z0-9_]*\\s[^>]*\\bname=\"(?:orbit_vault|orbit_file|orbit_request_approval|orbit_permission_card)\"[^>]*\\bstatus=\"success\"",
        RegexOption.IGNORE_CASE,
    )
    private val placeholder = Regex("\uE000(\\d+)\uE001")
    // The start of an options token at the very end of a reply still streaming in.
    private val partialOptionsToken = Regex("\\[\\[orbit-options(?::[a-z0-9]{0,10}]?)?$")
    // The chat is re-shown as a reply streams in; each stored text is cleaned only once.
    private val cleaned = java.util.Collections.synchronizedMap(java.util.WeakHashMap<String, String>())
    // Stored instead of a text that needed no cleaning: a value pointing at its own key would keep
    // the entry alive forever.
    private val unchanged = String(charArrayOf('\u0000'))
    // A real step opens like `<tool_ab12 name="…">`; words that only mention a tag stay.
    private val unclosedStepAtEnd = Regex(
        "<(?:tool|tool_result|status)(?:_[A-Za-z0-9]+)?\\s+(?:name|type)=\"[\\s\\S]*$",
        RegexOption.IGNORE_CASE,
    )

    fun of(content: String): String {
        cleaned[content]?.let { return if (it === unchanged) content else it }
        val result = clean(content)
        cleaned[content] = if (result === content) unchanged else result
        return result
    }

    /**
     * What Orbit said in words, for a notification or a list row that cannot draw a card. A reply
     * that is only a card gives [cardOnly]; a reply with nothing to say, or only a card that could
     * not be shown, gives an empty string.
     */
    fun words(content: String, cardOnly: String): String {
        val said = of(content)
        if (said.isEmpty()) return ""
        return said.replace(cardFence, "").replace(messageBreak, "\n").replace(blank, "\n\n").trim()
            .ifEmpty { if (shownCard.containsMatchIn(said)) cardOnly else "" }
    }

    /** Orbit puts this between messages it sends one after another (Muse's "separate bubbles"). */
    internal val messageBreak = Regex("<message_break\\s*/?>(?:\\s*</message_break>)?", RegexOption.IGNORE_CASE)

    /** One Orbit reply as the bubbles it sends: its words split at each message break, then its cards. */
    fun bubbles(shown: String): Pair<List<String>, List<String>> {
        val (speech, cards) = splitCards(shown)
        val parts = if (speech.contains("message_break", ignoreCase = true)) {
            speech.split(messageBreak).map { it.trim() }.filter { it.isNotEmpty() }
        } else listOf(speech).filter { it.isNotBlank() }
        return parts to cards
    }

    /**
     * One shown Orbit reply as the bubbles it is drawn as, top to bottom: its words split at each
     * message break, then each card on its own. A reply with neither is one bubble, as it is.
     */
    fun pieces(shown: String): List<String> {
        if (!shown.contains("message_break", ignoreCase = true) && !shown.contains("```$ORBIT_CARD_FENCE\n")) return listOf(shown)
        val (parts, cards) = bubbles(shown)
        return parts + cards.map { "```$ORBIT_CARD_FENCE\n$it\n```" }
    }

    // A whole Dynamic UI card, which becomes a bubble of its own.
    private val uiFence = Regex("(?m)^```a2ui(?:-ref)?[^\\n]*\\n[\\s\\S]*?\\n```[ \\t]*$")

    /**
     * Which Dynamic UI card of an Orbit message [source] is, counted as [pieceList] counts them, for
     * a card drawn from the whole message (the developer view, or while Orbit is still writing) so
     * it keeps the state it has in its own bubble. [earlier] is how many cards with the same content
     * come before it. Null if it is not there.
     */
    fun uiIndexOf(content: String, source: String, earlier: Int = 0): Int? {
        val wanted = source.trim()
        var left = earlier
        uiFence.findAll(of(content)).forEachIndexed { i, m ->
            val body = m.value.lines().drop(1).let { if (it.lastOrNull()?.trim() == "```") it.dropLast(1) else it }
            if (body.joinToString("\n").trim() == wanted && left-- == 0) return i
        }
        return null
    }

    /**
     * [pieces] with what each bubble is: the words (split at message breaks and around Dynamic UI
     * cards), each Dynamic UI card, then each tool card. [timestamp] is the message's.
     */
    fun pieceList(shown: String, timestamp: Long, english: Boolean): List<OrbitPiece> {
        val whole = timestamp.toString()
        if (!shown.contains("message_break", ignoreCase = true) && !shown.contains("```$ORBIT_CARD_FENCE\n") &&
            !shown.contains("```a2ui")) return listOf(OrbitPiece(shown, whole, null, true))
        val (parts, cards) = bubbles(shown)
        val out = mutableListOf<OrbitPiece>()
        var ui = 0
        parts.forEach { part ->
            var at = 0
            fun words(text: String) { text.trim().takeIf { it.isNotEmpty() }?.let { out += OrbitPiece(it, whole, null, false) } }
            uiFence.findAll(part).forEach { m ->
                words(part.substring(at, m.range.first))
                val n = ui++
                out += OrbitPiece(m.value, com.ai.assistance.operit.features.orbit.OrbitReactions.pieceKey("ui", timestamp, n),
                    if (english) "[Interactive card]" else "[交互卡片]", true, uiIndex = n)
                at = m.range.last + 1
            }
            words(part.substring(at))
        }
        // The message's own reaction sits under its last words.
        out.indexOfLast { it.summary == null }.takeIf { it >= 0 }?.let { out[it] = out[it].copy(showsBadge = true) }
        cards.forEachIndexed { i, card ->
            val key = runCatching { org.json.JSONObject(card).optString(com.ai.assistance.operit.features.orbit.OrbitChatCards.PIECE_KEY) }
                .getOrNull()?.takeIf { it.isNotEmpty() }
                ?: com.ai.assistance.operit.features.orbit.OrbitReactions.pieceKey("card", timestamp, i)
            out += OrbitPiece("```$ORBIT_CARD_FENCE\n$card\n```", key,
                com.ai.assistance.operit.features.orbit.OrbitChatCards.summary(card, english), true)
        }
        // A reaction given to the whole message before its cards were bubbles of their own.
        if (out.none { it.summary == null } && out.isNotEmpty()) out[0] = out[0].copy(fallbackBadgeKey = whole)
        return out
    }

    private fun clean(content: String): String {
        var text = ChatUtils.removeThinkingContent(content)
        text = ChatMarkupRegex.removeGeminiThoughtSignatureMeta(text)
        text = ChatMarkupRegex.removeOpenAiResponsesReasoningMeta(text)
        // A card a tool put in the chat (a secure entry card, a picture or file Orbit sent) stays
        // where the tool ran; every other step and result goes.
        val cards = mutableListOf<String>()
        // The marks below stand in for cards; the same characters in the text itself are dropped.
        if (text.indexOf('\uE000') >= 0 || text.indexOf('\uE001') >= 0) text = text.replace("\uE000", "").replace("\uE001", "")
        text = text.replace(ChatMarkupRegex.toolOrToolResultBlock) { match ->
            cardOf(match.value)?.let { card ->
                cards += card
                "\uE000${cards.size - 1}\uE001"
            }.orEmpty()
        }
        // "Viewed N images": the pictures Orbit looked at while working, which the user never sees.
        text = com.ai.assistance.operit.api.chat.llmprovider.MediaLinkParser.removeImageLinks(text)
        listOf(
            ChatMarkupRegex.toolSelfClosingTag,
            ChatMarkupRegex.toolResultSelfClosingTag,
            ChatMarkupRegex.statusTag,
            ChatMarkupRegex.statusSelfClosingTag,
            ChatMarkupRegex.emotionTag,
        ).forEach { text = text.replace(it, "") }
        // A card Orbit wrote itself never shows; checked last, so removing a tag cannot put one together.
        if (text.contains(ORBIT_CARD_FENCE)) text = text.replace(writtenOrbitCard, "")
        // Reply options: the token Orbit wrote becomes buttons (see OrbitOptions); a fence it typed
        // itself does nothing, since the buttons only come from a set its tool made. A token still
        // being written does not show as text.
        if (text.contains("[[orbit-options", ignoreCase = true)) {
            text = text.replace(com.ai.assistance.operit.features.orbit.OrbitOptions.token) { m ->
                "\n\n```$ORBIT_OPTIONS_FENCE\n${m.groupValues[1]}\n```\n\n"
            }.replace(partialOptionsToken, "")
        }
        if (cards.isNotEmpty()) {
            text = text.replace(placeholder) { m ->
                val card = m.groupValues[1].toIntOrNull()?.let(cards::getOrNull) ?: return@replace ""
                "\n\n```$ORBIT_CARD_FENCE\n$card\n```\n\n"
            }
        }
        // A reply cut off mid-step (cancelled, interrupted) can end in a step that never closed.
        return text.replace(unclosedStepAtEnd, "").replace(blank, "\n\n").trim()
    }

    /** The card in one of Orbit's tool results, as one line of JSON, or null. */
    private fun cardOf(block: String): String? {
        if (!block.contains(com.ai.assistance.operit.features.orbit.OrbitChatCards.KEY) || !cardToolResult.containsMatchIn(block)) return null
        val body = block.substringAfter("<content>", "").substringBeforeLast("</content>", "")
        return runCatching {
            org.json.JSONObject(body.trim()).optJSONObject(com.ai.assistance.operit.features.orbit.OrbitChatCards.KEY)?.toString()
        }.getOrNull()
    }

    /**
     * The chat as an Orbit chat shows it. The list keeps its length and order, because message
     * actions point at messages by position; rows with nothing to say are skipped when drawn
     * (see [hidden]). A reply still being written shows what Orbit has said so far ([live], read
     * from its stream by [rememberOrbitLiveSpeech]); its thinking and tool calls never show.
     *
     * Cards Orbit's tools made during a turn are sent after its reply, like Muse: they move to the
     * last row of that turn, and while Orbit is still working ([busy]) the newest turn holds them back.
     */
    fun history(messages: List<ChatMessage>, busy: Boolean = false): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(messages.size)
        val pending = mutableListOf<String>()
        var lastAi = -1
        fun flush() {
            if (lastAi >= 0 && pending.isNotEmpty()) {
                val row = out[lastAi]
                out[lastAi] = row.copy(content = (row.content + pending.joinToString("") { "\n\n```$ORBIT_CARD_FENCE\n$it\n```" }).trim())
            }
            pending.clear()
        }
        for (message in messages) {
            if (message.sender == "user") {
                flush()
                lastAi = -1
            }
            if (message.sender != "ai" || message.displayMode.isCollaborationEvent) {
                out += message
                continue
            }
            val (speech, cards) = splitCards(of(message.content))
            // Each card remembers the message it came from, so it keeps its reactions once moved.
            pending += cards.mapIndexed { i, card ->
                com.ai.assistance.operit.features.orbit.OrbitChatCards.withPieceKey(card,
                    com.ai.assistance.operit.features.orbit.OrbitReactions.pieceKey("card", message.timestamp, i))
            }
            lastAi = out.size
            out += message.copy(content = speech, contentStream = null)
        }
        if (!busy) flush()
        return out
    }

    private val cardBlock = Regex("\\n*```$ORBIT_CARD_FENCE\\n([^\\n]*)\\n```\\n*")

    /** What Orbit said in [shown] (cleaned text), and the cards in it, in order. */
    fun splitCards(shown: String): Pair<String, List<String>> {
        if (!shown.contains("```$ORBIT_CARD_FENCE\n")) return shown to emptyList()
        val cards = cardBlock.findAll(shown).map { it.groupValues[1] }.toList()
        return shown.replace(cardBlock, "\n\n").replace(blank, "\n\n").trim() to cards
    }

    /**
     * [shown] (from [history] of [messages]) with the reply being written replaced by what Orbit
     * has said so far; only that one row changes, so a long chat is not rebuilt as it streams.
     */
    fun withLive(messages: List<ChatMessage>, shown: List<ChatMessage>, live: Pair<Long, String>?): List<ChatMessage> {
        if (live == null) return shown
        val index = messages.indexOfLast { it.timestamp == live.first && it.contentStream != null }
        if (index < 0 || index >= shown.size) return shown
        return shown.toMutableList().also { it[index] = messages[index].copy(content = live.second, contentStream = null) }
    }

    private val openTagAtEnd = Regex(
        "<(?:think|search|tool|status|emotion)[\\s\\S]*$|<[A-Za-z_/]*$",
        RegexOption.IGNORE_CASE,
    )

    /** What Orbit has said so far in a reply still being written: finished words, no open steps. */
    fun live(partial: String): String = splitCards(clean(partial)).first.replace(openTagAtEnd, "").trim()

    /** Rows an Orbit chat does not draw: subagent cards, summaries, placeholders and empty replies. */
    fun hidden(message: ChatMessage): Boolean =
        message.displayMode.isCollaborationEvent ||
            message.displayMode == ChatMessageDisplayMode.HIDDEN_PLACEHOLDER ||
            (message.sender != "user" && message.sender != "ai") ||
            (message.sender == "ai" && message.content.isBlank())
}

/**
 * What Orbit has said so far in [message] while it is being written, read from its replayable
 * stream and refreshed a few times a second. Null when there is no reply being written.
 */
@Composable
fun rememberOrbitLiveSpeech(message: ChatMessage?): androidx.compose.runtime.State<Pair<Long, String>?> =
    androidx.compose.runtime.produceState<Pair<Long, String>?>(null, message?.timestamp, message?.contentStream) {
        val stream = message?.contentStream
        if (stream == null) {
            value = null
            return@produceState
        }
        val timestamp = message.timestamp
        // A provider may retry part of an answer: it rolls the text back to a savepoint and
        // streams the new attempt, so the bubble follows the same revisions the chat does.
        val carrier = stream as? com.ai.assistance.operit.util.stream.TextStreamEventCarrier
        val tracker = com.ai.assistance.operit.util.stream.TextStreamRevisionTracker()
        var processedEvents = 0
        var processedChars = 0
        fun drainRevisions() {
            val channel = carrier?.eventChannel ?: return
            // Only the events not seen yet, without copying the whole history on every chunk.
            val events = (channel as? com.ai.assistance.operit.util.stream.MutableSharedStreamImpl<com.ai.assistance.operit.util.stream.TextStreamEvent>)
                ?.replayFrom(processedEvents)
                ?: channel.replayCache.drop(processedEvents)
            for (event in events) {
                if (event.replayCharCount?.let { it > processedChars } == true) break
                processedEvents++
                when (event.eventType) {
                    com.ai.assistance.operit.util.stream.TextStreamEventType.SAVEPOINT -> tracker.savepoint(event.id)
                    com.ai.assistance.operit.util.stream.TextStreamEventType.ROLLBACK -> tracker.rollback(event.id)
                }
            }
        }
        var shownAt = 0L
        suspend fun show() {
            val snapshot = tracker.currentContent().toString()
            value = timestamp to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { OrbitSpeech.live(snapshot) }
        }
        try {
            stream.asFlow().collect { chunk ->
                drainRevisions()
                tracker.append(chunk)
                processedChars += chunk.length
                val now = android.os.SystemClock.uptimeMillis()
                if (now - shownAt >= 200) {
                    shownAt = now
                    show()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // The sending service reports stream errors; this only feeds the display.
        }
        drainRevisions()
        show()
    }

/** One of Orbit's messages with the user's tapback, if any, tucked under the bubble's corner. */
@Composable
fun OrbitReactedAiMessage(timestamp: Long, content: @Composable () -> Unit) {
    val reaction = LocalOrbitUserReactions.current?.byMessage?.get(timestamp.toString())
    if (reaction == null) {
        content()
        return
    }
    Column(Modifier.fillMaxWidth()) {
        content()
        ReactionBadge(reaction, stringResource(R.string.orbit_user_reaction_label, reaction), Alignment.CenterStart, start = 16.dp)
    }
}

@Composable
private fun ReactionBadge(reaction: String, label: String, alignment: Alignment, start: androidx.compose.ui.unit.Dp = 0.dp, end: androidx.compose.ui.unit.Dp = 0.dp) {
    // Lifted 10dp to overlap the bubble's lower corner, without leaving that gap below.
    Box(
        Modifier.fillMaxWidth().padding(start = start, end = end).layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val lift = 10.dp.roundToPx()
            layout(placeable.width, (placeable.height - lift).coerceAtLeast(0)) { placeable.place(0, -lift) }
        },
        contentAlignment = alignment,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 1.dp,
            modifier = Modifier.clearAndSetSemantics { contentDescription = label },
        ) {
            Text(reaction, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
        }
    }
}

/** A user's message with Orbit's reaction, if any, tucked under the bubble's corner like a chat app. */
@Composable
fun OrbitReactedUserMessage(timestamp: Long, content: @Composable () -> Unit) {
    val reaction = LocalOrbitReactions.current[timestamp.toString()]
    if (reaction == null) {
        content()
        return
    }
    Column(Modifier.fillMaxWidth()) {
        content()
        ReactionBadge(reaction, stringResource(R.string.orbit_reaction_label, currentOrbitName(), reaction), Alignment.CenterEnd, end = 8.dp)
    }
}

/**
 * The long-press menu of a card Orbit sent, drawn like the one on its messages: the tapbacks on
 * top, a line, then "Reply" with its icon.
 */
@Composable
fun OrbitPieceMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    selected: String?,
    onPick: (String) -> Unit,
    onMore: () -> Unit,
    onReply: (() -> Unit)?,
) {
    com.ai.assistance.operit.ui.features.orbit.OrbitDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 200.dp),
    ) {
        OrbitTapbackRow(selected, onPick, onMore)
        if (onReply != null) {
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { androidx.compose.material3.Text(stringResource(R.string.reply_message), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium) },
                onClick = onReply,
                leadingIcon = {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                modifier = Modifier.heightIn(min = 44.dp),
            )
        }
    }
}

/**
 * The row of tapbacks at the top of the long-press menu on one of Orbit's messages: the user's
 * most used emoji, then a "+" that opens every emoji ([onMore]).
 */
@Composable
fun OrbitTapbackRow(selected: String?, onPick: (String) -> Unit, onMore: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ranked by androidx.compose.runtime.remember { OrbitEmojiUsage.observe(context) }.collectAsState()
    // The reaction already on the message stays in the row, so it can be taken off from here.
    val quick = androidx.compose.runtime.remember(ranked, selected) {
        val top = OrbitEmojiUsage.quick(ranked, 5)
        if (selected == null || selected in top) top else top.take(4) + selected
    }
    // 5 x 44dp + a 48dp "+" + padding stays inside a menu's 280dp.
    androidx.compose.foundation.layout.Row(
        Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        quick.forEach { emoji ->
            val isSelected = emoji == selected
            val label = stringResource(R.string.orbit_react_with, emoji)
            val state = if (isSelected) stringResource(R.string.orbit_reaction_selected) else ""
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onPick(emoji) }
                    .semantics {
                        contentDescription = label
                        if (isSelected) stateDescription = state
                        this.selected = isSelected
                    },
            ) {
                Text(emoji, fontSize = 24.sp)
            }
        }
        OrbitMoreTapbacksButton(onMore)
    }
}

/**
 * Swipe a message to the right to reply to it, like a chat app: the message follows the finger, a
 * reply arrow fades in behind it, and letting go past the mark (felt as a tick) sets it as the reply.
 */
@Composable
fun OrbitSwipeToReply(
    enabled: Boolean,
    onReply: () -> Unit,
    /** The user's own bubbles sit at the right edge; they barely move, so they never slip off it. */
    rightAligned: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Drawn offset: written synchronously while dragging so the bubble tracks the finger;
    // only the release spring animates it back.
    val offset = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val settle = androidx.compose.runtime.remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    // Kept short so a right-aligned bubble only peeks past the edge while it is pulled.
    val trigger = with(density) { 52.dp.toPx() }
    val limit = with(density) { 64.dp.toPx() }
    // How far the bubble itself follows the finger: all the way, or a little for one at the edge.
    val bubbleShare = if (rightAligned) 0.25f else 1f
    val currentShare = androidx.compose.runtime.rememberUpdatedState(bubbleShare)
    val currentReply = androidx.compose.runtime.rememberUpdatedState(onReply)
    fun springBack() {
        settle[0]?.cancel()
        settle[0] = scope.launch {
            androidx.compose.animation.core.animate(
                initialValue = offset.floatValue,
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f),
            ) { value, _ -> offset.floatValue = value }
        }
    }
    Box(
        Modifier.fillMaxWidth().pointerInput(Unit) {
            var passed = false
            var dragged = 0f
            detectHorizontalDragGestures(
                onDragStart = {
                    settle[0]?.cancel()
                    passed = false
                    dragged = offset.floatValue
                },
                onDragEnd = {
                    if (dragged >= trigger) currentReply.value()
                    dragged = 0f
                    springBack()
                },
                onDragCancel = {
                    dragged = 0f
                    springBack()
                },
            ) { change, dragAmount ->
                val next = (dragged + dragAmount).coerceIn(0f, limit)
                if (next == dragged) return@detectHorizontalDragGestures
                change.consume()
                dragged = next
                if (!passed && next >= trigger) {
                    passed = true
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                } else if (passed && next < trigger) {
                    passed = false
                }
                offset.floatValue = next
            }
        },
    ) {
        val showIcon = offset.floatValue > 0f
        if (showIcon) {
            androidx.compose.material3.Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(22.dp)
                    .graphicsLayer {
                        val progress = (offset.floatValue / trigger).coerceIn(0f, 1f)
                        alpha = progress
                        scaleX = 0.6f + 0.4f * progress
                        scaleY = 0.6f + 0.4f * progress
                    },
            )
        }
        Box(Modifier.offset { IntOffset((offset.floatValue * currentShare.value).roundToInt(), 0) }) { content() }
    }
}
