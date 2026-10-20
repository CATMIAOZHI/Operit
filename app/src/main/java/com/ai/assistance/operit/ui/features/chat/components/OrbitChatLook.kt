package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

/** The fence an Orbit chat draws as a card from a tool: "```orbit-card" and one line of JSON. */
const val ORBIT_CARD_FENCE = "orbit-card"

/** Emoji reactions Orbit put on the user's messages in this chat, by message time. */
val LocalOrbitReactions = compositionLocalOf<Map<Long, String>> { emptyMap() }

/**
 * True in Orbit's developer view: rows the Orbit chat never shows (tapback notices, replies that
 * only stayed quiet) are drawn with a "hidden in chat" label so they can be audited.
 */
val LocalShowHiddenChatRows = compositionLocalOf { false }

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

/** The user's own reactions on Orbit's messages and how to change them; null outside Orbit. */
class OrbitUserReactions(val byMessage: Map<Long, String>, val toggle: (timestamp: Long, emoji: String) -> Unit)

val LocalOrbitUserReactions = compositionLocalOf<OrbitUserReactions?> { null }

/**
 * The message's own double-tap (like) and long-press (menu), for a card inside it that takes
 * taps itself: without them, a tap on the card never reaches the message around it.
 */
class OrbitMessageGestures(val onDoubleTap: (() -> Unit)?, val onLongPress: (() -> Unit)?)

val LocalOrbitMessageGestures = compositionLocalOf<OrbitMessageGestures?> { null }

/** What Orbit said in a message, without its thinking, tool calls, tool results or status lines. */
object OrbitSpeech {
    private val blank = Regex("\n{3,}")
    private val cardFence = Regex("```(?:a2ui(?:-ref)?|${ORBIT_CARD_FENCE})[^\\n]*\\n[\\s\\S]*?(?:```|$)")
    private val shownCard = Regex("```(?:a2ui(?:[ \\t]+card-\\d{1,2})?|${ORBIT_CARD_FENCE})[ \\t]*\\n")
    // Orbit's own words can never draw an Orbit card; only a tool's result can (see [cards]).
    // The renderer reads the tag after trimming, so "``` orbit-card" counts as the same fence.
    private val writtenOrbitCard = Regex("```[ \\t]*${ORBIT_CARD_FENCE}[^\\n]*(?:\\n[\\s\\S]*?```|[\\s\\S]*$)")
    private val cardToolResult = Regex(
        "^<tool_result[A-Za-z0-9_]*\\s[^>]*\\bname=\"(?:orbit_vault|orbit_file|orbit_request_approval)\"[^>]*\\bstatus=\"success\"",
        RegexOption.IGNORE_CASE,
    )
    private val placeholder = Regex("\uE000(\\d+)\uE001")
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
            pending += cards
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
    val reaction = LocalOrbitUserReactions.current?.byMessage?.get(timestamp)
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
    val reaction = LocalOrbitReactions.current[timestamp]
    if (reaction == null) {
        content()
        return
    }
    Column(Modifier.fillMaxWidth()) {
        content()
        ReactionBadge(reaction, stringResource(R.string.orbit_reaction_label, reaction), Alignment.CenterEnd, end = 8.dp)
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
