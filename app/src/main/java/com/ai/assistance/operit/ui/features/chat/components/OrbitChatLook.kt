package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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

/**
 * Set in Orbit's WeChat channel: the chat is read here and answered in WeChat, so it takes no
 * input and nothing in it sends. [ready] is false while the link is still being read and a side
 * chat might be the channel; it then shows neither the input box nor the WeChat footer.
 */
class OrbitReadOnlyChannel(val ready: Boolean, val loginExpired: Boolean, val onReconnect: () -> Unit)

val LocalOrbitReadOnlyChannel = compositionLocalOf<OrbitReadOnlyChannel?> { null }

/**
 * Set while opening Orbit's developer view: told whether the transcript's replies are still being
 * prepared, so the view is recorded for its slide-in only once they show.
 */
val LocalTranscriptPreparing = compositionLocalOf<androidx.compose.runtime.MutableState<Boolean>?> { null }

/**
 * How visible the transcript's messages are. Entering an Orbit chat keeps them hidden until they
 * are ready, so they show together instead of one by one. Read only while drawing.
 */
val LocalTranscriptAlpha = compositionLocalOf<androidx.compose.runtime.State<Float>?> { null }

/**
 * In Orbit's developer view: Orbit's own picture, drawn beside replies signed with its [name]
 * (its chats and its background runs), so they never look like a role card's.
 */
@androidx.compose.runtime.Immutable
class OrbitOwnAvatar(val name: String, val content: @Composable () -> Unit)

val LocalOrbitOwnAvatar = compositionLocalOf<OrbitOwnAvatar?> { null }

/** The user's messages in this Orbit chat that never reached the model, and how to send one again. */
@androidx.compose.runtime.Immutable
class OrbitUnsentMessages(val timestamps: Set<Long>, val resend: (ChatMessage) -> Unit)

/** Set in an Orbit chat; a user bubble listed there shows a red sign that sends it again. */
val LocalOrbitUnsentMessages = compositionLocalOf<OrbitUnsentMessages?> { null }

/**
 * How far a note that rose above Orbit's message box (an "added" confirmation) reaches up, so the
 * round "back to the newest" button above the box can step over it instead of being covered.
 */
val LocalOrbitBottomNoteLift = compositionLocalOf { 0.dp }

/**
 * Lifts [content] over such a note. Only this small box reads the lift, and the slide is applied
 * at layout time, so the message list around it is not redrawn on each frame of the slide.
 */
@Composable
fun OrbitNoteLift(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val lift by androidx.compose.animation.core.animateDpAsState(
        LocalOrbitBottomNoteLift.current, label = "orbitNoteLift",
    )
    Box(modifier.offset { androidx.compose.ui.unit.IntOffset(0, -lift.roundToPx()) }) { content() }
}

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
    val today = rememberOrbitChatToday(context)
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = orbitChatTimeText(context, time, now = today),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The day Orbit's time lines are written against. A line is drawn once, so a chat left open past
 * midnight, or brought back the next morning, would still call yesterday "today": the day moves
 * when the system says the date, clock or time zone changed (also after the phone slept through
 * midnight) and is checked again whenever a screen showing the lines starts.
 */
internal object OrbitChatDay {
    private val today = androidx.compose.runtime.mutableLongStateOf(startOfDay(System.currentTimeMillis()))
    private var listening = false

    val value: Long get() = today.longValue

    /** Main thread only. */
    fun refresh() {
        val start = startOfDay(System.currentTimeMillis())
        if (today.longValue != start) today.longValue = start
    }

    /** Main thread only; listens once for the app's whole life. */
    fun listen(context: android.content.Context) {
        if (listening) return
        listening = true
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_DATE_CHANGED)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) = refresh()
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context.applicationContext, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    private fun startOfDay(at: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = at
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}

@Composable
private fun rememberOrbitChatToday(context: android.content.Context): Long {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        OrbitChatDay.listen(context)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) OrbitChatDay.refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return OrbitChatDay.value
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
 * Text the model reads with a message but the chat never shows, such as `<message_break/>` or
 * the send time, as the developer view writes it above the message.
 */
@Composable
fun OrbitDevMark(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
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
    /** Its place in its reply, top to bottom, as [OrbitSpeech.pieceList] gave it (0 for a lone bubble). */
    val index: Int = 0,
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
    /** A long press on a voice bar inside the bubble: the menu for that bubble and that recording. */
    val voiceMenu: ((OrbitPiece, String) -> Unit)? = null,
)

val LocalOrbitPieceActions = compositionLocalOf<OrbitPieceActions?> { null }

/**
 * Picking the bubbles of one Orbit reply one by one while selecting messages. Given by a reply
 * drawn as several bubbles; [picked] and [toggle] take a bubble's place in it, top to bottom.
 */
class OrbitPiecePick(
    val picked: (Int) -> Boolean,
    val toggle: (Int) -> Unit,
    /** "Select to here" while nothing of the reply is picked; shown at [rangeAt]. */
    val rangeHere: (() -> Unit)?,
    val count: Int,
    /** Each bubble says here whether "select to here" fits under its mark. */
    val roomy: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Boolean>,
    /**
     * Where "select to here" shows, worked out once for the reply (see [orbitRangeSpot]): the bubble,
     * and whether the pill takes the place of its mark (no bubble had room for both).
     */
    private val spot: androidx.compose.runtime.State<Pair<Int, Boolean>?>,
) {
    val rangeAt: Int? get() = if (rangeHere == null) null else spot.value?.first
    val rangeAlone: Boolean get() = spot.value?.second == true
}

/**
 * Where "select to here" goes in a reply of [pieces]: under the mark of the lowest bubble with room
 * for both, else, once every bubble has been laid out, alone beside the lowest bubble of words.
 */
fun orbitRangeSpot(
    roomy: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Boolean>,
    pieces: List<OrbitPiece>,
): Pair<Int, Boolean>? {
    var lowest: Int? = null
    for ((i, fits) in roomy) if (fits && (lowest == null || i > lowest)) lowest = i
    if (lowest != null) return lowest to false
    if (roomy.size < pieces.size) return null
    return pieces.indexOfLast { it.summary == null }.takeIf { it >= 0 }?.let { it to true }
}

val LocalOrbitPiecePick = compositionLocalOf<OrbitPiecePick?> { null }

/** The round mark of a message or bubble being picked. */
@Composable
fun OrbitPickMark(picked: Boolean, label: String, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(22.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            // The row or bubble already reads as one selectable item to a screen reader.
            .clearAndSetSemantics {}
            .clickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            )
            .then(
                if (picked) Modifier.background(tint)
                // Filled, so it still shows where it lies over a wide card.
                else Modifier.background(MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (picked) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Default.Check, null, Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** QQ's "select to here": picks every message between the last one picked and this one. */
@Composable
fun OrbitSelectRangePill(onClick: () -> Unit) {
    // Only its own pill takes the tap; a bigger touch area would reach the mark above.
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                stringResource(R.string.orbit_selection_range_here),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 64.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** The widest the empty side next to a picked message or bubble is used for its mark. */
val ORBIT_PICK_SIDE = 76.dp

/**
 * The bubble a jump from the chat search is looking for ([target], a piece key) and where it was
 * last laid out ([top], in root pixels), so the list can bring that very picture or card to the top
 * instead of the start of the message holding it.
 */
class OrbitPieceSpot {
    var target by androidx.compose.runtime.mutableStateOf<String?>(null)
    var top by androidx.compose.runtime.mutableStateOf<Float?>(null)
    /** The first bubble laid out for [target]; only it reports, when a picture shows twice. */
    var owner: Any? = null

    /** Records where [who] (a bubble showing [target]) was laid out, if it is the one followed. */
    fun report(who: Any, y: Float) {
        if (owner == null) owner = who
        if (owner == who) top = y
    }
}

val LocalOrbitPieceSpot = compositionLocalOf<OrbitPieceSpot?> { null }

/**
 * One bubble with its own gestures and its own reaction under it. [index] is its place in its
 * reply, so it can be picked alone while selecting messages (see [OrbitPiecePick]).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OrbitPieceFrame(piece: OrbitPiece, index: Int = -1, content: @Composable () -> Unit) {
    val spot = LocalOrbitPieceSpot.current
    val wanted = spot != null && spot.target == piece.key
    val pick = LocalOrbitPiecePick.current?.takeIf { index in 0 until it.count }
    val picked = pick?.picked(index) == true
    // Every Dynamic UI card knows which one of its message it is, gestures or not (also while
    // Orbit is still writing), so its state never moves to another card.
    androidx.compose.runtime.CompositionLocalProvider(
        com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiBlockIndex provides piece.uiIndex,
    ) {
        // Always the same box: only its modifier changes, so the bubble inside is never rebuilt.
        Box(
            (if (wanted) Modifier.onGloballyPositioned { spot?.report(piece.key, it.positionInRoot().y) }
            else Modifier).then(
                if (picked) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                else Modifier
            )
        ) {
            // While picking, the bubble is one choice: its cards and links take no screen-reader action.
            Box(if (pick != null) Modifier.clearAndSetSemantics {} else Modifier) { OrbitPieceGestures(piece, content) }
            if (pick != null) OrbitPiecePickLayer(piece, index, pick, picked)
        }
    }
}

/** A tap anywhere on the bubble picks it; its round mark sits on the side the bubble leaves empty. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.OrbitPiecePickLayer(piece: OrbitPiece, index: Int, pick: OrbitPiecePick, picked: Boolean) {
    val label = stringResource(R.string.multi_select)
    val voice = stringResource(R.string.ai_message_quoted_voice)
    val toggle = { pick.toggle(index) }
    val words = androidx.compose.runtime.remember(piece, voice) {
        piece.summary ?: OrbitSpeech.voiceCaption(piece)?.let { if (it.isEmpty()) voice else "$voice $it" } ?: piece.content.take(200)
    }
    Box(
        Modifier.matchParentSize()
            .clickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClickLabel = label,
                onClick = toggle,
            )
            .semantics {
                selected = picked
                contentDescription = words
            },
    )
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
        val side = minOf(maxWidth * 0.2f, ORBIT_PICK_SIDE)
        // Words or a voice bar leave the side empty; "select to here" fits under the mark when tall enough.
        val roomy = piece.summary == null && side >= 64.dp && maxHeight >= 52.dp
        androidx.compose.runtime.SideEffect { if (pick.roomy[index] != roomy) pick.roomy[index] = roomy }
        val range = pick.rangeHere?.takeIf { pick.rangeAt == index }
        when {
            // A card may use the whole row: its mark goes in the top corner, over as little as it can.
            piece.summary != null -> Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { OrbitPickMark(picked, label, toggle) }
            // No bubble of the reply has room for both: the pill alone, the bubble still picked by a tap.
            range != null && pick.rangeAlone -> Box(Modifier.align(Alignment.CenterEnd).requiredWidth(side), contentAlignment = Alignment.Center) {
                OrbitSelectRangePill(range)
            }
            range != null && side >= 64.dp && maxHeight >= 52.dp -> Column(
                Modifier.align(Alignment.CenterEnd).requiredWidth(side),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                OrbitPickMark(picked, label, toggle)
                Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) { OrbitSelectRangePill(range) }
            }
            else -> Box(Modifier.align(Alignment.CenterEnd).requiredWidth(side), contentAlignment = Alignment.Center) {
                OrbitPickMark(picked, label, toggle)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun OrbitPieceGestures(piece: OrbitPiece, content: @Composable () -> Unit) {
    val actions = LocalOrbitPieceActions.current
    val byMessage = LocalOrbitUserReactions.current?.byMessage
    val reaction = if (piece.showsBadge || piece.fallbackBadgeKey != null) byMessage?.get(piece.reactionKey(byMessage)) else null
    // One fixed layout whether or not gestures are on (multi-select, still being written): only the
    // handlers change, so a card inside is never rebuilt and a tapback never blinks off.
    val like = actions?.like?.let { { it(piece) } }
    val menu = actions?.menu?.let { { it(piece) } }
    val gestures = if (actions != null) OrbitMessageGestures(like, menu) else LocalOrbitMessageGestures.current
    // A voice bar here opens the menu for this very bubble, so "reply" quotes it.
    val voiceHost = com.ai.assistance.operit.ui.common.markdown.LocalVoiceMenu.current
    // The actions are new on every redraw of the row: the host stays the same, so the bars keep theirs.
    val voiceMenu = androidx.compose.runtime.rememberUpdatedState(actions?.voiceMenu)
    val hasVoiceMenu = actions?.voiceMenu != null
    val bubbleVoiceHost = androidx.compose.runtime.remember(voiceHost, hasVoiceMenu, piece) {
        if (voiceHost?.open == null || !hasVoiceMenu) voiceHost
        else com.ai.assistance.operit.ui.common.markdown.VoiceMenuHost(
            open = { url -> voiceMenu.value?.invoke(piece, url) }, words = voiceHost.words, pending = voiceHost.pending,
        )
    }
    val source = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    OrbitSwipeToReply(enabled = actions?.reply != null, onReply = { actions?.reply?.invoke(piece) }) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().then(
                    if (actions != null) Modifier.combinedClickable(
                        interactionSource = source, indication = null,
                        onClick = {}, onLongClick = menu, onDoubleClick = like,
                    ) else Modifier,
                ),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalOrbitMessageGestures provides gestures,
                    com.ai.assistance.operit.ui.common.markdown.LocalVoiceMenu provides bubbleVoiceHost,
                ) {
                    content()
                }
            }
            if (reaction != null) {
                ReactionBadge(reaction, stringResource(R.string.orbit_user_reaction_label, reaction), Alignment.CenterStart, start = 16.dp)
            }
        }
    }
}

/**
 * The newest message time when the current reply began, in [chatId]: rows after it belong to that
 * reply. A reply starts loading before any row of its own exists (a hidden turn's prompt is still
 * being prepared), and until then the last reply on screen is an old, finished one. The time is the
 * newest one shown while idle, never read from the first loading frame (that frame may already hold
 * the new reply), and only from a [ready] history of [chatId]. Null when not loading, or when the
 * chat was opened mid-reply and that start is unknown.
 */
@Composable
fun rememberOrbitTurnFloor(chatId: String?, isLoading: Boolean, messages: List<ChatMessage>, ready: Boolean): Long? {
    val idleNewest = androidx.compose.runtime.remember(chatId) { arrayOfNulls<Long>(1) }
    val newest = androidx.compose.runtime.remember(messages, isLoading, ready) {
        if (isLoading || !ready) null else messages.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE
    }
    if (newest != null) idleNewest[0] = newest
    return androidx.compose.runtime.remember(chatId, isLoading) { if (isLoading) idleNewest[0] else null }
}

/** Where the Orbit reply now loading starts (see [rememberOrbitTurnFloor]), for the rows drawn below. */
val LocalOrbitTurnFloor = compositionLocalOf<Long?> { null }

/** The time of the Orbit reply being written now, if any; its bubbles are not picked one by one. */
val LocalOrbitWritingTimestamp = compositionLocalOf<Long?> { null }

/** Whether [message] can be part of the reply now loading, given [floor] from [rememberOrbitTurnFloor]. */
fun orbitInCurrentTurn(message: ChatMessage?, floor: Long?): Boolean =
    floor == null || (message != null && message.timestamp > floor)

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
    private val optionsFence = Regex("```${ORBIT_OPTIONS_FENCE}\\n([a-z0-9]+)\\n```")

    /** The reply option sets [shown] (cleaned text) draws, by id; each still has to exist to show. */
    fun optionIds(shown: String): List<String> =
        if (!shown.contains(ORBIT_OPTIONS_FENCE)) emptyList() else optionsFence.findAll(shown).map { it.groupValues[1] }.toList()
    // Orbit's own words can never draw an Orbit card; only a tool's result can (see [cards]).
    // The renderer reads the tag after trimming, so "``` orbit-card" counts as the same fence.
    private val writtenOrbitCard = Regex("```[ \\t]*${ORBIT_CARD_FENCE}[^\\n]*(?:\\n[\\s\\S]*?```|[\\s\\S]*$)")
    /** Tools whose results may carry an Orbit card; a tool that sends a card must be listed here. */
    val CARD_TOOLS = listOf(
        "orbit_vault", "orbit_file", "orbit_request_approval", "orbit_permission_card", "orbit_packages",
        "orbit_models", "orbit_components", "orbit_settings", "orbit_skills", "orbit_voice",
        "orbit_channel", "orbit_soul", "orbit_mcp", "orbit_task",
    )
    // A tool result's opening tag is read once, up to its first `>`: a card tool's name, then a
    // success; or, for a sending tool stopped for the user's OK, an error carrying the approval card.
    private val toolResultOpening = Regex("^<tool_result[A-Za-z0-9_]*\\s", RegexOption.IGNORE_CASE)
    private val cardToolName = Regex("\\bname=\"(?:${CARD_TOOLS.joinToString("|")})\"", RegexOption.IGNORE_CASE)
    private val successStatus = Regex("\\bstatus=\"success\"", RegexOption.IGNORE_CASE)
    private val errorStatus = Regex("\\bstatus=\"error\"", RegexOption.IGNORE_CASE)
    private val placeholder = Regex("\uE000(\\d+)\uE001")
    // The start of an options token at the very end of a reply still streaming in.
    private val partialOptionsToken = Regex("\\[\\[orbit-options(?::[a-z0-9]{0,10}]?)?$")
    // A token Orbit wrote without making a set (empty or made up) draws nothing, so it is not shown.
    private val strayOptionsToken = Regex("\\[\\[orbit-options(?::[^\\]\\n]{0,40})?]]", RegexOption.IGNORE_CASE)
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

    /** [splitSteps] off: words after a tool step join the words before, as a channel sends them. */
    fun of(content: String, splitSteps: Boolean = true): String {
        if (!splitSteps) return clean(content, splitSteps = false)
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
    internal val messageBreak = Regex("<message_break(?:\\s+auto)?\\s*/?>(?:\\s*</message_break>)?", RegexOption.IGNORE_CASE)

    /**
     * The break the chat itself puts where Orbit goes on talking after a tool step (see [clean]).
     * It draws like Orbit's own. A channel that sends each reply as one message cleans without it
     * (`splitSteps = false`), so what it sends stays exactly as before.
     */
    const val AUTO_BREAK = "\n<message_break auto/>\n"

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

    /** The content of each Dynamic UI card of an Orbit message, in the order [pieceList] counts them. */
    fun uiSources(content: String): List<String> = uiFence.findAll(of(content)).map { m ->
        m.value.lines().drop(1).let { if (it.lastOrNull()?.trim() == "```") it.dropLast(1) else it }.joinToString("\n").trim()
    }.toList()

    /**
     * [pieces] with what each bubble is: the words (split at message breaks and around Dynamic UI
     * cards), each Dynamic UI card, then each tool card. [timestamp] is the message's. [uiOffset]
     * is how many Dynamic UI cards come before [shown] when it is only a part of its message.
     */
    /**
     * [splitVoice]: for the chat on screen, a voice message alone on its line is a bubble of its own.
     * Off elsewhere, so pieces keep their numbers (a channel keys what it sent by them).
     */
    fun pieceList(shown: String, timestamp: Long, english: Boolean, uiOffset: Int = 0, splitVoice: Boolean = false): List<OrbitPiece> {
        val key = PieceKey(shown, timestamp, english, uiOffset, splitVoice)
        synchronized(pieceCache) { pieceCache[key] }?.let { return it }
        val made = splitPieces(shown, timestamp, english, uiOffset, splitVoice).mapIndexed { i, piece -> piece.copy(index = i) }
        // A reply Orbit is still writing keeps only its latest text; all kept replies together hold
        // at most [MAX_CACHED_CHARS] characters, the oldest let go first.
        if (shown.length <= MAX_CACHED_CHARS) synchronized(pieceCache) {
            val old = pieceCache.keys.iterator()
            while (old.hasNext()) {
                val it = old.next()
                if (it.timestamp == timestamp && it.uiOffset == uiOffset && it.english == english && it.splitVoice == splitVoice) {
                    cachedChars -= it.shown.length
                    old.remove()
                }
            }
            pieceCache[key] = made
            cachedChars += shown.length
            val eldest = pieceCache.keys.iterator()
            while (cachedChars > MAX_CACHED_CHARS && eldest.hasNext()) {
                val it = eldest.next()
                if (it == key) break
                cachedChars -= it.shown.length
                eldest.remove()
            }
        }
        return made
    }

    /** [pieceList] when it is already worked out, else null; never splits. */
    fun cachedPieceList(shown: String, timestamp: Long, english: Boolean, uiOffset: Int = 0, splitVoice: Boolean = false): List<OrbitPiece>? =
        synchronized(pieceCache) { pieceCache[PieceKey(shown, timestamp, english, uiOffset, splitVoice)] }

    private const val MAX_CACHED_CHARS = 4_000_000
    private var cachedChars = 0L

    private data class PieceKey(val shown: String, val timestamp: Long, val english: Boolean, val uiOffset: Int, val splitVoice: Boolean)
    // The same reply is split for drawing, for picking bubbles and for the selection bar; the latest
    // few are kept so each is split once. A key compares its text by reference first, so it is cheap.
    private val pieceCache = object : java.util.LinkedHashMap<PieceKey, List<OrbitPiece>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PieceKey, List<OrbitPiece>>?): Boolean {
            val drop = size > 128
            if (drop && eldest != null) cachedChars -= eldest.key.shown.length
            return drop
        }
    }

    private fun splitPieces(shown: String, timestamp: Long, english: Boolean, uiOffset: Int, splitVoice: Boolean): List<OrbitPiece> {
        val whole = timestamp.toString()
        if (!shown.contains("message_break", ignoreCase = true) && !shown.contains("```$ORBIT_CARD_FENCE\n") &&
            !shown.contains("```a2ui") && !(splitVoice && hasVoiceLine(shown))) return listOf(OrbitPiece(shown, whole, null, true))
        val (parts, cards) = bubbles(shown)
        val out = mutableListOf<OrbitPiece>()
        var ui = uiOffset
        parts.forEach { part ->
            var at = 0
            // A voice message on a line of its own is a bubble of its own, as in a messenger.
            fun words(text: String) {
                var from = 0
                fun add(t: String) { t.trim().takeIf { it.isNotEmpty() }?.let { out += OrbitPiece(it, whole, null, false) } }
                if (!splitVoice || !text.contains("](")) return add(text)
                voiceLines(text).forEach { m ->
                    add(text.substring(from, m.range.first))
                    add(m.value)
                    from = m.range.last + 1
                }
                add(text.substring(from))
            }
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

    // A Markdown audio link alone on its line (Orbit's voice messages are written so). At most three
    // spaces before it: four make it code.
    private val voiceLine = Regex("(?m)^ {0,3}!\\[[^\\]\\n]*\\]\\(([^)\\s]+)\\)[ \\t]*$")
    /** The voice lines of [text] outside code blocks. */
    private fun voiceLines(text: String): List<MatchResult> {
        val code = if (text.contains("```") || text.contains("~~~")) com.ai.assistance.operit.ui.common.markdown.markdownFenceRanges(text) else emptyList()
        return voiceLine.findAll(text).filter { m ->
            com.ai.assistance.operit.ui.common.markdown.isLikelyAudioUrl(m.groupValues[1]) && code.none { m.range.first in it }
        }.toList()
    }

    private fun hasVoiceLine(text: String) = text.contains("](") && voiceLines(text).isNotEmpty()

    /**
     * [text] with each voice message written as words: "[[voice]] caption" (only [voice] without
     * a caption), so a copy or a kept message shows no private file address.
     */
    fun voiceAsWords(text: String, voice: String): String {
        if (!text.contains("](")) return text
        val lines = voiceLines(text)
        if (lines.isEmpty()) return text
        val out = StringBuilder()
        var from = 0
        lines.forEach { m ->
            out.append(text, from, m.range.first)
            val caption = m.value.substringAfter("![").substringBefore("](").trim()
            out.append("[").append(voice).append("]")
            if (caption.isNotEmpty()) out.append(" ").append(caption)
            from = m.range.last + 1
        }
        return out.append(text, from, text.length).toString()
    }

    /**
     * When [piece] is a voice message bubble (a voice line alone), its caption ("" when it has
     * none); null for any other bubble. A reply to it quotes this instead of the link.
     */
    fun voiceCaption(piece: OrbitPiece): String? {
        if (piece.summary != null || !piece.content.contains("](")) return null
        val m = voiceLine.matchEntire(piece.content.trim()) ?: return null
        if (!com.ai.assistance.operit.ui.common.markdown.isLikelyAudioUrl(m.groupValues[1])) return null
        return m.value.substringAfter("![").substringBefore("](").trim()
    }

    private fun clean(content: String, splitSteps: Boolean = true): String {
        // A card a tool put in the chat (a secure entry card, a picture or file Orbit sent) stays
        // where the tool ran; every other step and result goes.
        val cards = mutableListOf<String>()
        var text = withoutThinkingOrSteps(content, cards, splitSteps)
        text = ChatMarkupRegex.removeGeminiThoughtSignatureMeta(text)
        text = ChatMarkupRegex.removeOpenAiResponsesReasoningMeta(text)
        // "Viewed N images": the pictures Orbit looked at while working, which the user never sees.
        text = com.ai.assistance.operit.api.chat.llmprovider.MediaLinkParser.removeImageLinks(text)
        // The shared patterns read on from each opening to the end of the text when it never ends,
        // so a long run of them would cost a pass each; these remove the same tags in one pass.
        text = withoutSelfClosing(text, stepOpening)
        text = withoutSelfClosing(text, resultOpening)
        text = withoutBlocks(text, "status")
        text = withoutSelfClosing(text, statusOpening)
        text = withoutBlocks(text, "emotion")
        // A card Orbit wrote itself never shows; checked last, so removing a tag cannot put one together.
        if (text.contains(ORBIT_CARD_FENCE)) text = text.replace(writtenOrbitCard, "")
        // Reply options: the token Orbit wrote becomes buttons (see OrbitOptions); a fence it typed
        // itself does nothing, since the buttons only come from a set its tool made. A token still
        // being written does not show as text.
        if (text.contains("[[orbit-options", ignoreCase = true)) {
            text = text.replace(com.ai.assistance.operit.features.orbit.OrbitOptions.token) { m ->
                "\n\n```$ORBIT_OPTIONS_FENCE\n${m.groupValues[1]}\n```\n\n"
            }.replace(strayOptionsToken, "").replace(partialOptionsToken, "")
        }
        if (cards.isNotEmpty()) {
            text = text.replace(placeholder) { m ->
                val card = m.groupValues[1].toIntOrNull()?.let(cards::getOrNull) ?: return@replace ""
                "\n\n```$ORBIT_CARD_FENCE\n$card\n```\n\n"
            }
        }
        // A reply cut off mid-step (cancelled, interrupted) can end in a step that never closed.
        text = text.replace(unclosedStepAtEnd, "")
        if (text.indexOf(STEP_MARK) >= 0) text = withStepBreaks(text)
        return tidyBreaks(text.replace(blank, "\n\n").trim())
    }

    // Where words follow a tool step, until [clean] turns it into a break. The card mark's opening
    // character, already kept out of the text; once the card marks are replaced, a lone one can
    // only be this.
    private const val STEP_MARK = '\uE000'

    /**
     * [text] with each [STEP_MARK] made an [AUTO_BREAK], except one inside code (a block or an
     * inline span), where a step was only written as an example. Code is found in the text without
     * the marks, so a mark right before a fence line cannot hide the fence.
     */
    private fun withStepBreaks(text: String): String {
        val plain = StringBuilder(text.length)
        val marks = mutableListOf<Int>() // positions in [plain]
        for (c in text) if (c == STEP_MARK) marks += plain.length else plain.append(c)
        val words = plain.toString()
        val code = codeRanges(words)
        val out = StringBuilder(words.length + marks.size * AUTO_BREAK.length)
        var copied = 0
        var block = 0
        for (at in marks) {
            while (block < code.size && code[block].last < at) block++
            // Between two characters of the same code span or block.
            val inCode = block < code.size && code[block].first < at
            if (inCode) continue
            out.append(words, copied, at).append(AUTO_BREAK)
            copied = at
        }
        return out.append(words, copied, words.length).toString()
    }

    /**
     * The code blocks and inline code spans of [text], in order, found in linear time: each run of
     * backticks outside a block closes at the next run of the same length on its line (as the chat's
     * Markdown renderer ends inline code at a line break), or is plain text.
     */
    private fun codeRanges(text: String): List<IntRange> {
        if (text.indexOf('`') < 0 && !text.contains("~~~")) return emptyList()
        val blocks = com.ai.assistance.operit.ui.common.markdown.markdownFenceRanges(text).sortedBy { it.first }
        val runs = mutableListOf<IntRange>()
        // Which line outside the code blocks each run is on: a run never pairs across a line or block.
        val gaps = mutableListOf<Int>()
        var line = 0
        var i = 0
        var b = 0
        while (i < text.length) {
            while (b < blocks.size && blocks[b].last < i) b++
            if (b < blocks.size && blocks[b].first <= i) { i = blocks[b].last + 1; line++; continue }
            if (text[i] != '`') { if (text[i] == '\n') line++; i++; continue }
            var j = i
            while (j < text.length && text[j] == '`') j++
            runs += i until j
            gaps += line
            i = j
        }
        // For each run, the next run of the same length on the same line.
        val next = IntArray(runs.size) { -1 }
        var lastOfLength = HashMap<Int, Int>()
        for (k in runs.indices.reversed()) {
            // A new map, as clearing a large one keeps its size.
            if (k + 1 < runs.size && gaps[k + 1] != gaps[k]) lastOfLength = HashMap()
            val n = runs[k].last - runs[k].first + 1
            lastOfLength[n]?.let { next[k] = it }
            lastOfLength[n] = k
        }
        val out = blocks.toMutableList()
        var k = 0
        while (k < runs.size) {
            val close = next[k]
            if (close < 0) { k++; continue }
            out += runs[k].first..runs[close].last
            k = close + 1
        }
        return out.sortedBy { it.first }
    }

    /**
     * [text] without message breaks that end up with nothing on one side, such as one put after a
     * step before words that turned out to be only markup, before a step still being written, or
     * between two cards moved out of the words. Without this a row could look visible while
     * drawing no bubble, and a streaming reply would change text without changing what shows.
     * Every other part, and each break kept, stays exactly as written.
     */
    internal fun tidyBreaks(text: String): String {
        if (!text.contains("message_break", ignoreCase = true)) return text
        val out = StringBuilder(text.length)
        var from = 0
        var pending: String? = null
        fun part(end: Int): Boolean {
            val words = text.substring(from, end)
            if (words.isBlank()) return false
            if (out.isNotEmpty()) pending?.let(out::append)
            out.append(words)
            return true
        }
        for (m in messageBreak.findAll(text)) {
            val kept = part(m.range.first)
            // Two breaks with nothing between are one; Orbit's own wins over the chat's.
            val previous = pending
            pending = if (!kept && previous != null && !previous.contains("auto", ignoreCase = true)) previous else m.value
            from = m.range.last + 1
        }
        part(text.length)
        return out.toString().trim()
    }

    // Where a tool, tool result or status tag starts: its name, whole.
    private val stepOpening = Regex("<${ChatMarkupRegex.TOOL_TAG_NAME_REGEX_SOURCE}\\b", RegexOption.IGNORE_CASE)
    private val resultOpening = Regex("<${ChatMarkupRegex.TOOL_RESULT_TAG_NAME_REGEX_SOURCE}\\b", RegexOption.IGNORE_CASE)
    private val statusOpening = Regex("<status\\b", RegexOption.IGNORE_CASE)

    /**
     * [text] without the self-closing tags [opening] starts, read as the shared patterns read them
     * (the tag ends at the first `>` after its name, a `/` right before it), in one pass: the
     * first `>` after a name is the same for every name before it, so it is looked for once.
     */
    private fun withoutSelfClosing(text: String, opening: Regex): String {
        var match = opening.find(text) ?: return text
        var out: StringBuilder? = null
        var copied = 0
        var close = -2
        while (true) {
            val nameEnd = match.range.last + 1
            if (close != -1 && close < nameEnd) close = text.indexOf('>', nameEnd)
            // No `>` after this name means none after a later one either.
            if (close < 0) break
            val next = if (text[close - 1] == '/') {
                if (out == null) out = StringBuilder(text.length)
                out.append(text, copied, match.range.first)
                copied = close + 1
                opening.find(text, copied)
            } else {
                match.next()
            }
            match = next ?: break
        }
        return out?.append(text, copied, text.length)?.toString() ?: text
    }

    /**
     * [text] without its `<name …>…</name>` blocks, each ending at the first closing after its
     * opening, in one pass: an opening with no closing after it has none after a later one either.
     */
    private fun withoutBlocks(text: String, name: String): String {
        val open = "<$name"
        val close = "</$name>"
        var at = openingAt(text, open, 0)
        var out: StringBuilder? = null
        var copied = 0
        while (at >= 0) {
            val end = text.indexOf(close, at + open.length, ignoreCase = true)
            if (end < 0) break
            if (out == null) out = StringBuilder(text.length)
            out.append(text, copied, at)
            copied = end + close.length
            at = openingAt(text, open, copied)
        }
        return out?.append(text, copied, text.length)?.toString() ?: text
    }

    // Where the next [open] starts at or after [from] as a whole name (`<status`, not `<statusbar`), or -1.
    private fun openingAt(text: String, open: String, from: Int): Int {
        var at = text.indexOf(open, from, ignoreCase = true)
        while (at >= 0) {
            val next = text.getOrNull(at + open.length)
            if (next == null || !(next.isLetterOrDigit() || next == '_')) return at
            at = text.indexOf(open, at + 1, ignoreCase = true)
        }
        return -1
    }

    /**
     * [content] without its thinking and without its tool calls and results, read in one pass
     * (see [ChatUtils.visitOutsideThinking]), so a `<think>` in a tool's input or output (a log
     * Orbit read, an old message) cannot hide the rest of the reply. A tool result carrying a card
     * leaves a mark for it in [cards]; the marks' own characters in the text itself are dropped.
     */
    private fun withoutThinkingOrSteps(content: String, cards: MutableList<String>, splitSteps: Boolean): String {
        val marks = content.indexOf('\uE000') >= 0 || content.indexOf('\uE001') >= 0
        val out = StringBuilder()
        var whole = false
        // Where the card key comes next; a step without it holds no card and is not copied.
        var nextKey = -2
        // Words after a tool step start a new bubble, as if Orbit had sent them as a new message.
        var spoke = false
        var stepped = false
        ChatUtils.visitOutsideThinking(content, object : ChatUtils.OutsideThinkingVisitor {
            override fun text(start: Int, end: Int) {
                // A part that turns out to hold only markup cleaned away later is an empty bubble,
                // which [bubbles] drops, so blank is the only check needed here.
                val words = (start until end).any { !content[it].isWhitespace() }
                if (stepped && words) {
                    out.append(STEP_MARK)
                    stepped = false
                }
                if (words) spoke = true
                when {
                    marks -> for (i in start until end) {
                        val c = content[i]
                        if (c != '\uE000' && c != '\uE001') out.append(c)
                    }
                    // Nothing to leave out at all: the text is the content itself.
                    start == 0 && end == content.length -> whole = true
                    else -> out.append(content, start, end)
                }
            }

            override fun toolBlock(start: Int, end: Int) {
                if (spoke && splitSteps) stepped = true
                if (nextKey != -1 && nextKey < start) {
                    nextKey = content.indexOf(com.ai.assistance.operit.features.orbit.OrbitChatCards.KEY, start)
                }
                if (nextKey < 0 || nextKey >= end) return
                cardOf(content.substring(start, end))?.let { card ->
                    cards += card
                    out.append('\uE000').append(cards.size - 1).append('\uE001')
                }
            }
        })
        return if (whole) content.trim() else out.trim().toString()
    }

    /** The card in one of Orbit's tool results, as one line of JSON, or null. */
    private fun cardOf(block: String): String? = cardJsonOf(block)?.let(::inert)

    /**
     * [json] with the characters that mean something in a reply written as `\u` escapes, so a card
     * can never be read as a tag (a `<think>` in a file name), a code fence or a card mark. They
     * only occur inside JSON strings, where the escape reads back the same.
     */
    private fun inert(json: String): String {
        if (json.none { it == '<' || it == '>' || it == '`' || it == '\uE000' || it == '\uE001' }) return json
        val out = StringBuilder(json.length + 16)
        for (c in json) {
            when (c) {
                '<', '>', '`', '\uE000', '\uE001' -> out.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun cardJsonOf(block: String): String? {
        if (!block.contains(com.ai.assistance.operit.features.orbit.OrbitChatCards.KEY)) return null
        val opening = toolResultOpening.find(block) ?: return null
        // From the space after the name, so a `\b` before an attribute reads as it does in the tag.
        val attributes = block.substring(opening.range.last, block.indexOf('>').let { if (it < 0) block.length else it })
        val name = cardToolName.find(attributes)
        if (name == null || successStatus.find(attributes, name.range.last + 1) == null) {
            if (!errorStatus.containsMatchIn(attributes) ||
                !block.contains(com.ai.assistance.operit.features.orbit.OrbitConnectorSend.PREFIX)) return null
            val error = block.substringAfter("<error>", "").substringBeforeLast("</error>", "")
            return com.ai.assistance.operit.features.orbit.OrbitConnectorSend.cardIn(error)?.toString()
        }
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
    fun history(messages: List<ChatMessage>, busy: Boolean = false, splitSteps: Boolean = true): List<ChatMessage> {
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
            val (speech, cards) = splitCards(of(message.content, splitSteps))
            // Each card remembers the message it came from, so it keeps its reactions once moved.
            // Writing the key writes the JSON again, so it is made inert again too.
            pending += cards.mapIndexed { i, card ->
                inert(com.ai.assistance.operit.features.orbit.OrbitChatCards.withPieceKey(card,
                    com.ai.assistance.operit.features.orbit.OrbitReactions.pieceKey("card", message.timestamp, i)))
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
        // Two cards a break stood between leave it with nothing on one side.
        return tidyBreaks(shown.replace(cardBlock, "\n\n").replace(blank, "\n\n").trim()) to cards
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
    fun live(partial: String): String = tidyBreaks(splitCards(clean(partial)).first.replace(openTagAtEnd, "").trim())

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
        var dirty = false
        suspend fun show() {
            dirty = false
            val snapshot = tracker.currentContent().toString()
            value = timestamp to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { OrbitSpeech.live(snapshot) }
            shownAt = android.os.SystemClock.uptimeMillis()
        }
        // One refresher shows the latest text at most every 200 ms, and also when no next chunk
        // comes: the stream stays open while a tool runs. Only one clean-up runs at a time.
        val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        val refresher = launch {
            for (ignored in wake) {
                val wait = 200 - (android.os.SystemClock.uptimeMillis() - shownAt)
                if (wait > 0) kotlinx.coroutines.delay(wait)
                if (dirty) {
                    drainRevisions()
                    show()
                }
            }
        }
        try {
            var yieldedAt = android.os.SystemClock.uptimeMillis()
            stream.asFlow().collect { chunk ->
                drainRevisions()
                tracker.append(chunk)
                processedChars += chunk.length
                dirty = true
                wake.trySend(Unit)
                // A long reply replays all at once when the chat is reopened; let the UI breathe.
                val now = android.os.SystemClock.uptimeMillis()
                if (now - yieldedAt >= 8) {
                    yieldedAt = now
                    kotlinx.coroutines.yield()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // The sending service reports stream errors; this only feeds the display.
        }
        // The final text must not be followed by a late, older one from the refresher.
        refresher.cancel()
        refresher.join()
        wake.close()
        drainRevisions()
        show()
    }

/** One of Orbit's messages with the user's tapback, if any, tucked under the bubble's corner. */
@Composable
fun OrbitReactedAiMessage(timestamp: Long, content: @Composable () -> Unit) {
    val reaction = LocalOrbitUserReactions.current?.byMessage?.get(timestamp.toString())
    if (reaction == null || !closesMessage()) {
        content()
        return
    }
    Column(Modifier.fillMaxWidth()) {
        content()
        ReactionBadge(reaction, stringResource(R.string.orbit_user_reaction_label, reaction), Alignment.CenterStart, start = 16.dp)
    }
}

/**
 * Whether this row draws the end of its message. A long message (every one in the developer view)
 * is drawn as several rows, the header (time taken, who) on its own; a tapback goes under the last
 * one only, not between them.
 */
@Composable
private fun closesMessage(): Boolean =
    LocalResponseMessageSection.current != ResponseMessageSection.HEADER &&
        com.ai.assistance.operit.ui.common.markdown.LocalTranscriptMarkdownSlice.current?.last != false

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
    if (reaction == null || !closesMessage()) {
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
    // Outside an Orbit chat a disabled swipe adds nothing. Inside one the layout stays the same
    // either way, so switching the swipe on or off never rebuilds the bubble's cards.
    if (!enabled && !LocalOrbitChatLook.current) {
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
    androidx.compose.runtime.LaunchedEffect(enabled) {
        if (!enabled) {
            settle[0]?.cancel()
            offset.floatValue = 0f
        }
    }
    // Set when something inside the bubble (a card slider, a wide code block) already took the
    // finger; the swipe then stays put. Read before the swipe below sees the same move.
    val innerOwns = androidx.compose.runtime.remember { booleanArrayOf(false) }
    Box(
        Modifier.fillMaxWidth().then(if (!enabled) Modifier else Modifier.pointerInput(Unit) {
            var passed = false
            var dragged = 0f
            detectHorizontalDragGestures(
                onDragStart = {
                    settle[0]?.cancel()
                    passed = false
                    dragged = offset.floatValue
                },
                onDragEnd = {
                    if (dragged >= trigger && !innerOwns[0]) currentReply.value()
                    dragged = 0f
                    springBack()
                },
                onDragCancel = {
                    dragged = 0f
                    springBack()
                },
            ) { change, dragAmount ->
                if (innerOwns[0]) return@detectHorizontalDragGestures
                val next = (dragged + dragAmount).coerceIn(0f, limit)
                if (next == dragged) return@detectHorizontalDragGestures
                change.consume()
                dragged = next
                if (!passed && next >= trigger) {
                    passed = true
                    com.ai.assistance.operit.ui.features.orbit.OrbitHaptics.threshold(haptic)
                } else if (passed && next < trigger) {
                    passed = false
                }
                offset.floatValue = next
            }
        }.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                innerOwns[0] = false
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.isConsumed }) innerOwns[0] = true
                } while (event.changes.any { it.pressed })
            }
        }),
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
