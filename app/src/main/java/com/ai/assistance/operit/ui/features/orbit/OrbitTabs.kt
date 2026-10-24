package com.ai.assistance.operit.ui.features.orbit

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.model.OrbitArtifactVersionEntity
import com.ai.assistance.operit.data.model.OrbitGoalEntity
import com.ai.assistance.operit.data.model.OrbitNotificationEntity
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitArtifactRepository
import com.ai.assistance.operit.features.orbit.OrbitBoardRepository
import com.ai.assistance.operit.features.orbit.OrbitBoardRepository.Board
import com.ai.assistance.operit.features.orbit.OrbitExecutionGate
import com.ai.assistance.operit.features.orbit.OrbitFeedGenerator
import com.ai.assistance.operit.features.orbit.OrbitRoutines
import com.ai.assistance.operit.features.orbit.OrbitGoalRepository
import com.ai.assistance.operit.features.orbit.OrbitTaskRepository
import com.ai.assistance.operit.features.orbit.OrbitTaskScheduler
import com.ai.assistance.operit.util.AppLogger
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private const val TABS_TAG = "OrbitTabs"

/** The five tabs at the bottom of the Orbit page, like Muse. */
internal enum class OrbitTab(val icon: String, val label: Int) {
    CHAT("message-circle", R.string.orbit_tab_chat),
    FEED("newspaper", R.string.orbit_tab_feed),
    IDEAS("lightbulb", R.string.orbit_tab_ideas),
    GOALS("square-check-big", R.string.orbit_tab_goals),
    BUILDS("shapes", R.string.orbit_tab_builds),
}

/**
 * The tab bar's height shrinks by however much of it the keyboard covers, read in the layout
 * step each frame of the keyboard's own animation. So the bar leaves and comes back exactly as
 * fast as the keyboard moves, with no wait for the keyboard to finish, and no recomposition.
 */
internal fun Modifier.tuckUnderKeyboard(ime: WindowInsets): Modifier = clipToBounds().layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    val height = (placeable.height - ime.getBottom(this)).coerceAtLeast(0)
    layout(placeable.width, height) { placeable.place(0, 0) }
}

/** Muse's floating pill of five icons; the selected one sits on a soft grey pill. */
@Composable
internal fun OrbitBottomBar(selected: OrbitTab, onSelect: (OrbitTab) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(6.dp)) {
                OrbitTab.entries.forEach { tab ->
                    val isSelected = tab == selected
                    val label = stringResource(tab.label)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(50))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f) else Color.Transparent
                            )
                            .clickable(role = Role.Tab) { onSelect(tab) }
                            .semantics {
                                contentDescription = label
                                this.selected = isSelected
                            },
                    ) {
                        Icon(
                            orbitIcon(tab.icon), contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Sends [text] into the Orbit chat as the user, the same as typing it. */
/**
 * Sends [text] alone to Orbit's main chat. What the user has in the input box (words, files, the
 * message they reply to) stays there for their own next message. False when Orbit could not take
 * it, for example while it is still answering.
 */
internal fun sendToOrbit(context: android.content.Context, orbit: OrbitProfile, text: String): Boolean =
    ChatRuntimeHolder.getInstance(context.applicationContext).getCore(ChatRuntimeSlot.MAIN)
        .trySendUserMessageToExistingChat(
            chatId = orbit.chatId, messageText = text,
            turnOptions = com.ai.assistance.operit.data.model.ChatTurnOptions(useComposerReply = false),
        )

/**
 * Per Orbit chat: what the user had typed before a button added its prompt, and the full text
 * that left in the box. A second tap swaps only the prompt; the user's own words stay.
 */
private val lastOrbitDraft = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

/**
 * Puts [text] in the input box of Orbit's main chat without sending it; the user finishes it
 * and decides whether to send. Something the user typed there stays, with the text after it.
 */
internal fun draftToOrbit(context: android.content.Context, orbit: OrbitProfile, text: String) {
    val core = ChatRuntimeHolder.getInstance(context.applicationContext).getCore(ChatRuntimeSlot.MAIN)
    val current = core.draftForChat(orbit.chatId)
    val last = lastOrbitDraft[orbit.chatId]
    // Untouched since the last tap: keep what the user had typed before it, drop the old prompt.
    val own = if (last != null && current == last.second) last.first else current.trimEnd()
    val draft = if (own.isBlank()) text else own + "\n\n" + text
    lastOrbitDraft[orbit.chatId] = own to draft
    core.setDraftForChat(orbit.chatId, draft)
}

/**
 * One of the Feed, Ideas, Goals and Builds pages. It is opaque because the chat stays underneath,
 * so going back to Chat never reloads it.
 */
@Composable
internal fun OrbitTabPage(
    orbit: OrbitProfile,
    tab: OrbitTab,
    /** Fills Orbit's main chat input with a prompt and shows the chat; nothing is sent. */
    onDraft: (String) -> Unit,
    /** Quotes something above Orbit's main chat input box, like a reply, and shows the chat. */
    onQuote: (com.ai.assistance.operit.data.model.ChatMessage) -> Unit = {},
    filesVersion: Int = 0,
    onOpenFile: (String) -> Unit = {},
    /**
     * False while the chat tab is shown. The page then keeps only Orbit's header alive, unplaced,
     * so the picture (a pet's animation especially) is not loaded again on every tab switch.
     */
    visible: Boolean = true,
) {
    var openTaskId by rememberSaveable(orbit.id) { mutableStateOf<String?>(null) }
    // Keeps the task on screen while its page slides away.
    var shownTaskId by remember(orbit.id) { mutableStateOf(openTaskId) }
    LaunchedEffect(openTaskId) { openTaskId?.let { shownTaskId = it } }
    // Leaving for the chat closes an open task, as leaving the tab always did.
    LaunchedEffect(visible) { if (!visible) openTaskId = null }
    val header = LocalOrbitTabHeader.current
    val density = LocalDensity.current
    // Measured after the first frame; the guess is the header's usual size so nothing jumps.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var headerHeight by remember { mutableStateOf(statusBarTop + 120.dp) }
    Box(Modifier.fillMaxSize().then(if (visible) Modifier else Modifier.orbitKeptUnplaced())) {
        if (visible) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // A task page over the tab hides the tab from screen readers.
                Box(Modifier.fillMaxSize().then(if (openTaskId != null) Modifier.clearAndSetSemantics { } else Modifier)) {
                    CompositionLocalProvider(LocalOrbitTabHeaderHeight provides if (header != null) headerHeight else null) {
                        when (tab) {
                            OrbitTab.CHAT -> Unit
                            OrbitTab.FEED -> OrbitFeedPage(orbit, onDraft, onQuote)
                            OrbitTab.IDEAS -> OrbitIdeasPage(orbit, onDraft)
                            OrbitTab.GOALS -> OrbitGoalsPage(orbit, onOpenTask = { openTaskId = it }, onDraft = onDraft)
                            OrbitTab.BUILDS -> OrbitBuildsPage(orbit, filesVersion, onOpenTask = { openTaskId = it }, onOpenFile = onOpenFile, onDraft = onDraft)
                        }
                    }
                }
            }
        }
        // One header for every tab, over each page's list and the pages it pushes, under a task.
        if (header != null) {
            Box(Modifier.fillMaxWidth().onSizeChanged { headerHeight = with(density) { it.height.toDp() } }) {
                header()
            }
        }
        OrbitCoverPage(visible = visible && openTaskId != null, onDismiss = { openTaskId = null }) {
            (openTaskId ?: shownTaskId)?.let { taskId ->
                key(taskId) {
                    OrbitTasksPage(orbit.id, onBack = { openTaskId = null }, initialTaskId = taskId,
                        onDraft = { text -> openTaskId = null; onDraft(text) })
                }
            }
        }
    }
}

/** Keeps the content composed (its state and loaded pictures) without placing, drawing or touching it. */
private fun Modifier.orbitKeptUnplaced(): Modifier = layout { measurable, constraints ->
    measurable.measure(constraints)
    layout(0, 0) { }
}

/**
 * Orbit's header (menu, picture and name) for the tab pages. [OrbitTabPage] draws one for every
 * tab, fixed on top in the same place as in the chat, so the picture never moves or reloads when
 * the user switches tabs.
 */
internal val LocalOrbitTabHeader = compositionLocalOf<(@Composable () -> Unit)?> { null }

/** The height of the shared tab header, for a page's top padding; null when no header is shown. */
private val LocalOrbitTabHeaderHeight = compositionLocalOf<Dp?> { null }

/**
 * The page frame: Orbit's fixed header, a large title and an optional action on the right. [info]
 * adds a small ⓘ beside the title that says what the page is for. Without a [title] the page
 * starts right under the header with its own first item.
 * [overlay] is a page pushed over the list but under the header, so Orbit's picture stays put
 * while it slides in; it gets the top padding its own list needs.
 */
@Composable
private fun TabList(
    title: String?,
    action: (@Composable () -> Unit)? = null,
    info: (() -> Unit)? = null,
    overlay: (@Composable (top: Dp) -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val openDrawer = com.ai.assistance.operit.ui.main.LocalOpenAppDrawer.current
    val headerHeight = LocalOrbitTabHeaderHeight.current
    val header = headerHeight
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // The header's lower edge fades out, so the title tucks a little under it.
    val listTop = if (headerHeight == null) statusBarTop + 8.dp else (headerHeight - 12.dp).coerceAtLeast(0.dp)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = if (header == null) Modifier.fillMaxSize().statusBarsPadding() else Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp, bottom = 24.dp,
                top = if (headerHeight == null) 8.dp else (headerHeight - 12.dp).coerceAtLeast(0.dp),
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "frame") {
                Column {
                    if (header == null) {
                        OrbitCircleButton(orbitIcon("equal"), stringResource(R.string.menu), openDrawer)
                    }
                    if (title != null) Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = if (header == null) 16.dp else 0.dp, bottom = 4.dp),
                    ) {
                        Text(
                            title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                        )
                        if (info != null) {
                            IconButton(onClick = info, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    orbitIcon("info"), stringResource(R.string.orbit_tab_info, title), Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        action?.invoke()
                    }
                }
            }
            content()
        }
        overlay?.invoke(listTop)
    }
}

/** A short sheet that says what a tab is for, opened from the ⓘ beside its title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabIntroSheet(emoji: String, title: String, lines: List<String>, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    OrbitModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.surface) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        ) {
            Text(emoji, fontSize = 40.sp, lineHeight = 48.sp, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            lines.forEach { line ->
                Spacer(Modifier.height(12.dp))
                Text(line, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            Surface(
                onClick = { scope.launch { runCatching { sheetState.hide() }; onDismiss() } },
                shape = RoundedCornerShape(50), color = colors.onSurface,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.orbit_tab_info_ok), style = MaterialTheme.typography.titleMedium, color = colors.surface)
                }
            }
        }
    }
}

@Composable
private fun PillButton(text: String, icon: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(orbitIcon(icon), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SoftCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp),
    )
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp),
    )
}

private fun shortDate(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

private fun showToast(context: android.content.Context, text: String) =
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()

/** Runs a block off the click; a failure only shows its message. */
@Composable
private fun rememberAction(): (suspend () -> Unit) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(scope) {
        { block ->
            scope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w(TABS_TAG, "Orbit tab action failed: ${e.message}")
                    showToast(context, orbitActionError(context, e))
                }
            }
        }
    }
}

/**
 * What a failed page action tells the user. The app's own reasons are written in English for the
 * model; unless the page itself is in English (values-en), the user gets the localized sentence.
 */
internal fun orbitActionError(context: android.content.Context, e: Exception): String =
    e.message?.takeIf { it.isNotBlank() && context.resources.configuration.locales[0].language == "en" }
        ?: context.getString(com.ai.assistance.operit.R.string.orbit_action_failed)

// ---------------------------------------------------------------- Feed and Ideas: shared parts

/** Lets a row run edge to edge past the page's side padding, for its divider and the swipe colour. */
private fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = (horizontal * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** The emoji beside a post or an idea, drawn large like Muse's little pictures. */
@Composable
private fun BoardEmoji(emoji: String, fallback: String) {
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        Text(emoji.ifBlank { fallback }, fontSize = 36.sp, lineHeight = 42.sp, maxLines = 1)
    }
}

// A Markdown link's address runs to its closing bracket: any letters (Chinese paths too) and
// balanced brackets like Wikipedia's "Formula_One_(video_game)". A bare address in running Chinese
// text has no clear end, so it stops at the first character that is not plain URL ASCII.
private const val BARE_URL = """https?://(?:[A-Za-z0-9\-._~:/?#@!$&'*+,;=%]|\([A-Za-z0-9\-._~:/?#@!$&'*+,;=%]*\))+"""
private val linkPattern = Regex("""\[([^\]\n]+)]\((https?://(?:[^\s()]|\([^\s()]*\))+)\)|($BARE_URL)""")

/** Body text with Markdown links and bare web addresses made tappable. */
private fun linkify(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    var last = 0
    for (match in linkPattern.findAll(text)) {
        append(text.substring(last, match.range.first))
        val label = match.groupValues[1]
        if (label.isNotEmpty()) {
            withLink(LinkAnnotation.Url(match.groupValues[2], style)) { append(label) }
        } else {
            // A bare address keeps the punctuation that ends the sentence as plain text.
            val raw = match.groupValues[3]
            val url = raw.trimEnd('.', ',', ';', ':', '!', '?', '\'')
            withLink(LinkAnnotation.Url(url, style)) { append(url) }
            append(raw.substring(url.length))
        }
        last = match.range.last + 1
    }
    append(text.substring(last))
}

private fun ago(time: Long): String = DateUtils.getRelativeTimeSpanString(
    time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
).toString()

private val LikeGreen = Color(0xFF34C759)
private val DislikeRed = Color(0xFFFF3B30)

/**
 * A row the user can swipe like iOS mail: right uncovers a green thumbs up, left a red thumbs
 * down. Let go past the mark and [onVote] gets 1 or -1; the row always springs back.
 */
@Composable
private fun SwipeVoteRow(onVote: (Int) -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val offset = remember { Animatable(0f) }
    // Where the finger has taken the row; the animation follows it.
    val target = remember { floatArrayOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val threshold = with(density) { maxOf(88.dp.toPx(), width * 0.25f) }
    var armed by remember { mutableIntStateOf(0) }
    val currentOnVote by rememberUpdatedState(onVote)
    val drag by rememberUpdatedState { delta: Float ->
        val now = target[0]
        // Past the mark the row pulls back like a rubber band.
        val next = now + if (abs(now) > threshold && sign(delta) == sign(now)) delta * 0.35f else delta
        target[0] = next
        scope.launch { offset.snapTo(next) }
        val direction = when {
            next >= threshold -> 1
            next <= -threshold -> -1
            else -> 0
        }
        if (direction != armed) {
            if (direction != 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            armed = direction
        }
    }
    // A finished swipe past the mark votes; a touch the system takes away only springs back.
    fun settle(vote: Boolean, velocity: Float) {
        val direction = armed
        armed = 0
        target[0] = 0f
        if (vote && direction != 0) currentOnVote(direction)
        scope.launch {
            offset.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), initialVelocity = velocity)
        }
    }
    val currentSettle by rememberUpdatedState(::settle)
    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
        // What the swipe uncovers. Read while drawing, so a drag never recomposes the row.
        Box(
            Modifier.matchParentSize().drawBehind {
                val x = offset.value
                if (x != 0f) {
                    val progress = (abs(x) / threshold).coerceIn(0f, 1f)
                    drawRect((if (x > 0) LikeGreen else DislikeRed).copy(alpha = 0.16f + 0.22f * progress))
                }
            }
        )
        for (direction in listOf(1, -1)) {
            Icon(
                orbitIcon(if (direction > 0) "thumbs-up" else "thumbs-down"), null,
                Modifier.align(if (direction > 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 36.dp).size(26.dp)
                    .graphicsLayer {
                        val shown = (offset.value * direction / threshold).coerceIn(0f, 1f)
                        alpha = shown
                        val scale = if (armed == direction) 1.18f else 0.8f + 0.2f * shown
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
        Box(
            Modifier
                // Read before the row moves, so the finger's positions are not shifted by the slide.
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            target[0] = offset.value
                        },
                        onDragEnd = { currentSettle(true, tracker.calculateVelocity().x) },
                        onDragCancel = { currentSettle(false, 0f) },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            drag(amount)
                        },
                    )
                }
                .graphicsLayer {
                    translationX = offset.value
                    shape = RoundedCornerShape(28.dp)
                    clip = offset.value != 0f
                },
        ) { content() }
    }
}

@Composable
private fun VoteButton(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { selected = active }) {
        Icon(
            orbitIcon(icon), label, Modifier.size(22.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------- Feed

@Composable
private fun OrbitFeedPage(
    orbit: OrbitProfile,
    onDraft: (String) -> Unit,
    onQuote: (com.ai.assistance.operit.data.model.ChatMessage) -> Unit,
) {
    val who = orbitName(orbit)
    val context = LocalContext.current
    val action = rememberAction()
    val repository = remember(orbit.id) { OrbitBoardRepository(context, orbit.id, Board.FEED) }
    val posts by remember(repository) { repository.observe().catch { emit(emptyList()) } }.collectAsState(initial = null)
    var briefVersion by remember { mutableIntStateOf(0) }
    // The brief the feed follows: the user's own, or the default one.
    val brief by produceState<String?>(null, orbit.id, briefVersion) {
        value = try {
            OrbitFeedGenerator.effectiveBrief(context, orbit.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OrbitFeedGenerator.defaultBrief(context)
        }
    }
    var editingBrief by remember { mutableStateOf(false) }
    var showIntro by rememberSaveable { mutableStateOf(false) }
    val quoteSender = stringResource(R.string.orbit_feed_quote_sender)

    TabList(
        title = stringResource(R.string.orbit_tab_feed),
        info = { showIntro = true },
        action = {
            // Asks Orbit in the chat; the request waits in the input box for the user to send.
            OrbitCircleButton(orbitIcon("refresh-cw"), stringResource(R.string.orbit_feed_write_now), {
                onDraft(context.getString(R.string.orbit_feed_refresh_message))
            })
        },
    ) {
        val currentBrief = brief
        if (currentBrief != null) {
            item(key = "brief") { BriefPill(currentBrief) { editingBrief = true } }
        }
        val list = posts
        when {
            list == null -> item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            list.isEmpty() -> item(key = "empty") { Hint(stringResource(R.string.orbit_feed_posts_empty, who)) }
            else -> items(list, key = { it.id }) { post ->
                FeedPostRow(
                    post = post,
                    isFirst = post.id == list.first().id,
                    isLast = post.id == list.last().id,
                    // Tapping the chosen answer again takes it back.
                    onVote = { vote -> action { repository.update(post.id, vote = if (post.vote == vote) 0 else vote) } },
                    onDiscuss = {
                        // The post is quoted above the input box, like a reply; the user writes the rest.
                        onQuote(
                            com.ai.assistance.operit.data.model.ChatMessage(
                                sender = "ai", content = post.title + "\n\n" + post.body,
                                timestamp = post.createdAt, roleName = quoteSender,
                            ),
                        )
                    },
                    cardOf = { repository.readCard(post.id) },
                    onCardSubmit = { text ->
                        sendToOrbit(context, orbit, context.getString(R.string.orbit_feed_card_message, post.title, text)).also { sent ->
                            // The card stays open to try again when Orbit is still answering.
                            if (!sent) showToast(context, context.getString(R.string.orbit_feed_card_busy, who))
                        }
                    },
                    cardOwner = "orbit-feed:" + orbit.id,
                    onMove = { up -> action { repository.move(post.id, up) } },
                    onDelete = { action { repository.delete(post.id) } },
                )
            }
        }
    }

    if (showIntro) {
        TabIntroSheet(
            "📰", stringResource(R.string.orbit_feed_intro_title),
            listOf(stringResource(R.string.orbit_feed_intro_1, who), stringResource(R.string.orbit_feed_intro_2)),
        ) { showIntro = false }
    }

    if (editingBrief) {
        BriefDialog(
            who = who,
            initial = brief.orEmpty(),
            onDismiss = { editingBrief = false },
            onSave = { text ->
                editingBrief = false
                action {
                    val started = OrbitFeedGenerator.saveBrief(context, orbit.id, text)
                    briefVersion++
                    showToast(
                        context,
                        context.getString(
                            when {
                                started == false -> R.string.orbit_feed_brief_saved_paused
                                text.isBlank() -> R.string.orbit_feed_brief_cleared
                                started == null -> R.string.orbit_feed_brief_unchanged
                                else -> R.string.orbit_feed_brief_saved
                            },
                        ),
                    )
                }
            },
        )
    }
}

/** The feed's instructions in one line; tapping it opens them for editing. */
@Composable
private fun BriefPill(brief: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = colors.onSurface.copy(alpha = 0.045f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = brief },
    ) {
        Text(
            brief.replace(Regex("\\s+"), " "), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 15.dp),
        )
    }
}

@Composable
private fun BriefDialog(who: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val changed = text.trim() != initial.trim()
    OrbitDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.orbit_feed_brief_dialog_title),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.orbit_feed_brief_hint, who))
                OutlinedTextField(
                    text, { if (it.length <= OrbitBoardRepository.MAX_BRIEF) text = it },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    minLines = 6, maxLines = 12,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            OrbitDialogAction(onClick = { onSave(text) }, enabled = changed) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            OrbitDialogAction(onClick = onDismiss, secondary = true) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedPostRow(
    post: OrbitBoardRepository.Item,
    isFirst: Boolean,
    isLast: Boolean,
    onVote: (Int) -> Unit,
    onDiscuss: () -> Unit,
    /** Reads the post's Dynamic UI card (only called when it has one). */
    cardOf: suspend () -> String?,
    /** A card's "send" goes to Orbit's main chat. */
    onCardSubmit: (String) -> Boolean,
    /** Groups the card's saved state (what the user ticked or typed) under this Orbit's feed. */
    cardOwner: String,
    onMove: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by rememberSaveable(post.id) { mutableStateOf(false) }
    var showWhy by rememberSaveable(post.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val body = remember(post.body, colors.primary) { linkify(post.body, colors.primary) }
    Column(Modifier.bleed(20.dp)) {
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = { expanded = !expanded }, onLongClick = { menu = true })
                    .padding(start = 16.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
            ) {
                BoardEmoji(post.emoji, "📰")
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).animateContentSize()) {
                    Text(post.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        body, style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
                    )
                    if (post.card) FeedPostCard(post, cardOf, onCardSubmit, cardOwner)
                    if (showWhy && post.extra.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(16.dp), color = colors.onSurface.copy(alpha = 0.05f)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(stringResource(R.string.orbit_feed_why), style = MaterialTheme.typography.labelLarge)
                                Text(post.extra, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    // The buttons line up with the text; their touch area reaches past it to the left.
                    Row(Modifier.fillMaxWidth().offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
                        VoteButton("thumbs-up", stringResource(R.string.orbit_feed_like), post.liked) { onVote(1) }
                        VoteButton("thumbs-down", stringResource(R.string.orbit_feed_dislike), post.disliked) { onVote(-1) }
                        // With large text the label shortens before the ⓘ button is squeezed.
                        TextButton(
                            onClick = onDiscuss, colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                            modifier = Modifier.weight(1.5f, fill = false),
                        ) {
                            Icon(orbitIcon("message-circle"), null, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.orbit_feed_discuss), style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // The time takes what room is left, so the ⓘ button always keeps its full size.
                        Text(
                            ago(post.createdAt), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                        if (post.extra.isNotBlank()) {
                            IconButton(onClick = { showWhy = !showWhy }, modifier = Modifier.offset(x = 12.dp)) {
                                Icon(orbitIcon("info"), stringResource(R.string.orbit_feed_why), Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
            OrbitDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!isFirst) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.orbit_move_up)) }, onClick = { menu = false; onMove(true) })
                }
                if (!isLast) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.orbit_move_down)) }, onClick = { menu = false; onMove(false) })
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = colors.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = colors.outlineVariant.copy(alpha = 0.5f))
    }
}

// ---------------------------------------------------------------- Ideas

/**
 * A feed post's Dynamic UI card, under its text. It is read from its own file when the post
 * first scrolls into view and then kept in [feedCardCache], so scrolling back shows it at once
 * instead of growing in again. Ticks and typed values are kept like in the chat; "send" goes to
 * Orbit and only counts as sent when Orbit took it.
 */
@Composable
private fun FeedPostCard(
    post: OrbitBoardRepository.Item,
    cardOf: suspend () -> String?,
    onCardSubmit: (String) -> Boolean,
    cardOwner: String,
) {
    val cacheKey = cardOwner + "/" + post.id
    var reload by remember(cacheKey) { mutableIntStateOf(0) }
    // null while reading; a failure keeps the card's place with a way to try again.
    // The post says it has a card, so a missing or empty card file counts as a failure too.
    val card by produceState<Result<String>?>(feedCardCache.get(cacheKey)?.let { Result.success(it) }, cacheKey, reload) {
        if (value?.getOrNull() != null) return@produceState
        value = try {
            Result.success(checkNotNull(cardOf()) { "The card file is missing" }.also { feedCardCache.put(cacheKey, it) })
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    val result = card
    if (result == null) {
        // Read again after the cache let it go: hold the height it had, so the posts below stay put.
        val height = feedCardHeights.get(cacheKey) ?: return
        Spacer(Modifier.height(with(LocalDensity.current) { height.toDp() }))
        return
    }
    if (result.isFailure) {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.orbit_feed_card_failed), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false),
            )
            TextButton(onClick = { reload++ }) { Text(stringResource(R.string.orbit_memory_retry)) }
        }
        return
    }
    val document = result.getOrNull() ?: return
    val owner = remember(cardOwner, post.createdAt, document) {
        com.ai.assistance.operit.ui.common.dynamicui.DynamicUiOwner(cardOwner, post.createdAt, 0, document)
    }
    val currentSubmit by rememberUpdatedState(onCardSubmit)
    val submit = remember {
        { _: com.ai.assistance.operit.ui.common.dynamicui.DynamicUiOwner, text: String -> currentSubmit(text) }
    }
    Column(Modifier.onSizeChanged { if (it.height > 0) feedCardHeights.put(cacheKey, it.height) }) {
        Spacer(Modifier.height(10.dp))
        CompositionLocalProvider(
            com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiOwner provides owner,
            com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiSubmit provides submit,
        ) {
            com.ai.assistance.operit.ui.common.dynamicui.DynamicUiCard(document, closed = true, streaming = false, blockIndex = 0)
        }
    }
}

/** Feed cards already read, by "<owner>/<post id>": a post's card never changes. About 1 MB of text at most. */
private val feedCardCache = object : android.util.LruCache<String, String>(512 * 1024) {
    override fun sizeOf(key: String, value: String) = value.length
}

/** Each feed card's last height in pixels, kept longer than the cards themselves. */
private val feedCardHeights = android.util.LruCache<String, Int>(1024)

/** The picture and name of each idea category, in the order they are shown. */
private val ideaCategoryEmoji = mapOf(
    "productivity" to "⚡", "money" to "💰", "relationships" to "🤝",
    "health" to "💪", "shopping" to "🛍️", "other" to "✨",
)

@Composable
private fun ideaCategoryName(category: String): String = stringResource(
    when (category) {
        "productivity" -> R.string.orbit_idea_category_productivity
        "money" -> R.string.orbit_idea_category_money
        "relationships" -> R.string.orbit_idea_category_relationships
        "health" -> R.string.orbit_idea_category_health
        "shopping" -> R.string.orbit_idea_category_shopping
        else -> R.string.orbit_idea_category_other
    }
)

/** How many of the newest ideas sit on top; the rest are found in their category. */
private const val RECENT_IDEAS = 5

/**
 * Muse's Ideas tab: the newest ideas on top, then the categories, each opening its own page, then
 * the ideas Orbit already did. Any idea opens its details. New ideas come from the Ideas routine
 * every morning; the button asks it for some now.
 */
@Composable
private fun OrbitIdeasPage(orbit: OrbitProfile, onDraft: (String) -> Unit) {
    val who = orbitName(orbit)
    val context = LocalContext.current
    val action = rememberAction()
    val repository = remember(orbit.id) { OrbitBoardRepository(context, orbit.id, Board.IDEAS) }
    val ideas by remember(repository) { repository.observe().catch { emit(emptyList()) } }.collectAsState(initial = null)
    var dismissing by remember { mutableStateOf<OrbitBoardRepository.Item?>(null) }
    var openedId by rememberSaveable(orbit.id) { mutableStateOf<String?>(null) }
    var category by rememberSaveable(orbit.id) { mutableStateOf<String?>(null) }
    // Keeps the category on screen while its page slides away.
    var shownCategory by remember(orbit.id) { mutableStateOf(category) }
    LaunchedEffect(category) { category?.let { shownCategory = it } }
    // Asks Orbit in the main chat; the request waits in the input box for the user to send.
    val askIdeas: () -> Unit = {
        onDraft(context.getString(R.string.orbit_task_run_now_draft, context.getString(R.string.orbit_routine_ideas)))
    }
    // Tapping the chosen answer again takes it back.
    fun vote(idea: OrbitBoardRepository.Item, vote: Int) =
        action { repository.update(idea.id, vote = if (idea.vote == vote) 0 else vote) }

    val list = ideas
    val open = remember(list) { list.orEmpty().filter { it.state == "OPEN" } }
    val recent = remember(open) { open.sortedByDescending { it.createdAt }.take(RECENT_IDEAS) }
    val byCategory = remember(open) { open.groupBy { OrbitBoardRepository.categoryOf(it) } }
    val done = remember(list) { list.orEmpty().filter { it.state == "BUILT" }.sortedByDescending { it.builtAt }.take(10) }
    var showIntro by rememberSaveable { mutableStateOf(false) }
    if (showIntro) {
        TabIntroSheet(
            "💡", stringResource(R.string.orbit_ideas_intro_title),
            listOf(stringResource(R.string.orbit_ideas_intro_1, who), stringResource(R.string.orbit_ideas_intro_2)),
        ) { showIntro = false }
    }

    TabList(
        title = stringResource(R.string.orbit_tab_ideas),
        info = { showIntro = true },
        overlay = { top ->
            OrbitCoverPage(visible = category != null, onDismiss = { category = null }) {
                (category ?: shownCategory)?.let { shown ->
                    IdeaCategoryPage(
                        who = who,
                        category = shown,
                        ideas = byCategory[shown].orEmpty(),
                        top = top,
                        onBack = { category = null },
                        onOpen = { openedId = it.id },
                        onVote = ::vote,
                    )
                }
            }
        },
    ) {
        if (list == null) {
            item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            return@TabList
        }
        if (open.isEmpty()) {
            item(key = "empty") {
                SoftCard {
                    Icon(orbitIcon("lightbulb"), null, Modifier.size(28.dp))
                    Text(stringResource(R.string.orbit_ideas_empty, who), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    PillButton(stringResource(R.string.orbit_ideas_ask), "sparkles", askIdeas)
                }
            }
        } else {
            item(key = "hint") {
                Text(
                    stringResource(R.string.orbit_idea_swipe_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(recent, key = { it.id }) { idea ->
                IdeaRow(idea, onOpen = { openedId = idea.id }, onVote = { vote(idea, it) })
            }
        }
        item(key = "categoriesTitle") { GroupTitle(stringResource(R.string.orbit_ideas_categories)) }
        items(OrbitBoardRepository.IDEA_CATEGORIES, key = { "category-$it" }) { name ->
            IdeaCategoryRow(name, byCategory[name]?.size ?: 0) { category = name }
        }
        if (open.isNotEmpty()) {
            item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    PillButton(stringResource(R.string.orbit_ideas_ask), "sparkles", askIdeas)
                }
            }
        }
        if (done.isNotEmpty()) {
            item(key = "doneTitle") { GroupTitle(stringResource(R.string.orbit_ideas_done)) }
            items(done, key = { "done-" + it.id }) { idea -> DoneIdeaRow(idea) { openedId = idea.id } }
        }
    }

    // The sheet follows the saved idea, so a vote shows at once; an idea that is gone closes it.
    val opened = openedId?.let { id -> list?.firstOrNull { it.id == id && it.state in setOf("OPEN", "BUILT") } }
    LaunchedEffect(list, openedId) { if (openedId != null && list != null && opened == null) openedId = null }
    if (opened != null) {
        IdeaSheet(
            idea = opened,
            onDismiss = { openedId = null },
            // Only fills the chat input. The idea stays open until Orbit has really done it.
            onStart = {
                openedId = null
                onDraft(context.getString(R.string.orbit_idea_build_message, opened.title, opened.extra.ifBlank { opened.body }))
            },
            onVote = { vote(opened, it) },
            // Orbit changes the icon: the request waits in the chat input for the user to finish.
            onChangeIcon = {
                openedId = null
                onDraft(context.getString(R.string.orbit_idea_change_icon_message, opened.title))
            },
            onClose = {
                openedId = null
                dismissing = opened
            },
        )
    }

    dismissing?.let { idea ->
        OrbitDialog(
            onDismissRequest = { dismissing = null },
            title = stringResource(R.string.orbit_idea_dismiss),
            text = { Text(stringResource(R.string.orbit_idea_dismiss_message)) },
            confirmButton = {
                OrbitDialogAction(onClick = {
                    dismissing = null
                    action { repository.update(idea.id, state = "DISMISSED") }
                }) { Text(stringResource(R.string.orbit_idea_dismiss_action)) }
            },
            dismissButton = { OrbitDialogAction(onClick = { dismissing = null }, secondary = true) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** One category on the Ideas tab: its picture, its name and how many ideas it holds. */
@Composable
private fun IdeaCategoryRow(category: String, count: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.bleed(20.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(colors.onSurface.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(ideaCategoryEmoji[category] ?: "✨", fontSize = 22.sp, lineHeight = 26.sp, maxLines = 1)
            }
            Spacer(Modifier.width(14.dp))
            Text(
                ideaCategoryName(category), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(
                if (count > 0) stringResource(R.string.orbit_idea_category_count, count)
                else stringResource(R.string.orbit_idea_category_none),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1,
            )
            Icon(
                orbitIcon("chevron-right"), null, Modifier.padding(start = 4.dp).size(20.dp),
                tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

/** An idea Orbit already did: tap for its details. */
@Composable
private fun DoneIdeaRow(idea: OrbitBoardRepository.Item, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.bleed(20.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Text(idea.emoji.ifBlank { "💡" }, fontSize = 26.sp, lineHeight = 30.sp, maxLines = 1)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(idea.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.orbit_idea_done_at, shortDate(idea.builtAt)),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
            Icon(orbitIcon("chevron-right"), null, Modifier.size(20.dp), tint = colors.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

/** One category's ideas, pushed over the Ideas tab under Orbit's header. */
@Composable
private fun IdeaCategoryPage(
    who: String,
    category: String,
    ideas: List<OrbitBoardRepository.Item>,
    top: Dp,
    onBack: () -> Unit,
    onOpen: (OrbitBoardRepository.Item) -> Unit,
    onVote: (OrbitBoardRepository.Item, Int) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "frame") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    OrbitCircleButton(orbitIcon("chevron-left"), stringResource(R.string.back), onBack)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        ideaCategoryName(category), style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (ideas.isEmpty()) {
                item(key = "empty") { Hint(stringResource(R.string.orbit_idea_category_empty, who)) }
            } else {
                items(ideas, key = { it.id }) { idea ->
                    IdeaRow(idea, onOpen = { onOpen(idea) }, onVote = { onVote(idea, it) })
                }
            }
        }
    }
}
@Composable
private fun IdeaRow(idea: OrbitBoardRepository.Item, onOpen: () -> Unit, onVote: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val likeLabel = stringResource(R.string.orbit_feed_like)
    val dislikeLabel = stringResource(R.string.orbit_feed_dislike)
    Column(Modifier.bleed(20.dp)) {
        SwipeVoteRow(onVote = onVote) {
            Row(
                Modifier.fillMaxWidth().background(colors.background).clickable(onClick = onOpen)
                    // Screen readers get the two swipes as actions on the row they focus.
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(likeLabel) { onVote(1); true },
                            CustomAccessibilityAction(dislikeLabel) { onVote(-1); true },
                        )
                    }
                    .padding(start = 16.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)
                    // An idea the user does not want fades back, so the rest stand out.
                    .graphicsLayer { alpha = if (idea.disliked) 0.5f else 1f },
            ) {
                BoardEmoji(idea.emoji, "💡")
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            idea.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        if (idea.vote != 0) {
                            Icon(
                                orbitIcon(if (idea.liked) "thumbs-up" else "thumbs-down"),
                                if (idea.liked) likeLabel else dislikeLabel,
                                Modifier.padding(start = 8.dp, top = 4.dp).size(18.dp),
                                tint = if (idea.liked) LikeGreen else colors.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        idea.body, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant,
                        maxLines = 5, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdeaSheet(
    idea: OrbitBoardRepository.Item,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onVote: (Int) -> Unit,
    onChangeIcon: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // An idea Orbit already did shows the same details; there is nothing left to start or dismiss.
    val done = idea.state == "BUILT"
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var menu by remember { mutableStateOf(false) }
    val body = remember(idea.body, colors.primary) { linkify(idea.body, colors.primary) }
    // The sheet slides down before the next thing happens.
    fun hideThen(block: () -> Unit) {
        scope.launch {
            runCatching { sheetState.hide() }
            block()
        }
    }
    OrbitModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(idea.emoji.ifBlank { "💡" }, fontSize = 40.sp, lineHeight = 48.sp, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    Text(idea.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text(body, style = MaterialTheme.typography.bodyLarge)
                    val note = when {
                        done -> stringResource(R.string.orbit_idea_done_at, shortDate(idea.builtAt))
                        // A liked idea stays until it is done or dismissed.
                        idea.liked -> stringResource(R.string.orbit_idea_kept)
                        idea.expiresAt > 0 -> {
                        val days = ((idea.expiresAt - System.currentTimeMillis()) / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
                            stringResource(R.string.orbit_idea_expires, days)
                        }
                        else -> null
                    }
                    if (note != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                if (idea.parts.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(top = 20.dp), color = colors.outlineVariant.copy(alpha = 0.5f))
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.orbit_idea_includes), style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(orbitIcon("clipboard-list"), null, Modifier.size(26.dp), tint = colors.onSurfaceVariant)
                        }
                        idea.parts.forEach { part ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(part.title, style = MaterialTheme.typography.titleMedium)
                                    if (part.detail.isNotBlank()) {
                                        Text(part.detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Box(
                                    Modifier.size(28.dp).background(colors.primary, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(orbitIcon("check"), null, Modifier.size(16.dp), tint = colors.onPrimary)
                                }
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(16.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Surface(
                        onClick = { menu = true }, shape = CircleShape, color = colors.onSurface.copy(alpha = 0.06f),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(orbitIcon("ellipsis"), stringResource(R.string.more)) }
                    }
                    OrbitDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.orbit_feed_like)) },
                            leadingIcon = { Icon(orbitIcon("thumbs-up"), null, tint = if (idea.liked) LikeGreen else colors.onSurface) },
                            onClick = { menu = false; onVote(1) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.orbit_feed_dislike)) },
                            leadingIcon = { Icon(orbitIcon("thumbs-down"), null, tint = if (idea.disliked) colors.primary else colors.onSurface) },
                            onClick = { menu = false; onVote(-1) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.orbit_idea_change_icon)) },
                            leadingIcon = { Icon(orbitIcon("smile"), null) },
                            onClick = { menu = false; hideThen(onChangeIcon) },
                        )
                        if (!done) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.orbit_idea_dismiss_action), color = colors.error) },
                                leadingIcon = { Icon(orbitIcon("x"), null, tint = colors.error) },
                                onClick = { menu = false; hideThen(onClose) },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                if (done) {
                    // Shown where the start button would be, so the sheet keeps its shape.
                    Surface(
                        shape = RoundedCornerShape(50), color = colors.onSurface.copy(alpha = 0.06f),
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(orbitIcon("check"), null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.orbit_idea_done_badge), style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = { hideThen(onStart) }, shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Icon(orbitIcon("wand-sparkles"), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.orbit_idea_start), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Goals and tracking

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrbitGoalsPage(orbit: OrbitProfile, onOpenTask: (String) -> Unit, onDraft: (String) -> Unit) {
    val who = orbitName(orbit)
    val context = LocalContext.current
    val action = rememberAction()
    val dao = remember(context) { OrbitTaskRepository(context).dao }
    val goals by remember(orbit.id) { dao.observeGoals(orbit.id).catch { emit(emptyList()) } }.collectAsState(initial = null)
    val tasks by remember(orbit.id) { dao.observeTasks(orbit.id).catch { emit(emptyList()) } }.collectAsState(initial = null)
    val trackingRepository = remember(orbit.id) { OrbitBoardRepository(context, orbit.id, Board.TRACKING) }
    val tracking by remember(trackingRepository) { trackingRepository.observe().catch { emit(emptyList()) } }
        .collectAsState(initial = null)
    val notesRepository = remember(orbit.id) { OrbitBoardRepository(context, orbit.id, Board.GOAL_NOTES) }
    val goalNotes by remember(notesRepository) { notesRepository.observe().catch { emit(emptyList()) } }
        .collectAsState(initial = null)
    var deletingNote by remember { mutableStateOf<String?>(null) }
    var goalSheet by remember { mutableStateOf<String?>(null) }
    var trackingSheet by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<OrbitGoalEntity?>(null) }
    // A new goal starts as a message the user finishes in the chat; Orbit then sets it up.
    val newGoal = { onDraft(context.getString(R.string.orbit_goal_new_message)) }

    TabList(
        title = stringResource(R.string.orbit_tab_goals),
        action = { OrbitCircleButton(orbitIcon("plus"), stringResource(R.string.orbit_goal_create), newGoal) },
    ) {
        val goalList = goals?.filter { it.state != "CANCELLED" }
        when {
            goalList == null -> item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            goalList.isEmpty() -> item(key = "goalsEmpty") { Hint(stringResource(R.string.orbit_goals_empty)) }
            else -> items(goalList, key = { it.id }) { goal ->
                val steps = tasks.orEmpty().filter { it.goalId == goal.id }
                OrbitListRow(
                    icon = orbitIcon(if (goal.state == "ACTIVE") "target" else "circle-pause"),
                    title = goal.title,
                    detail = goal.plan.lineSequence().firstOrNull { it.isNotBlank() },
                    meta = goalStateText(goal.state) + " · " +
                        stringResource(R.string.orbit_goal_progress, steps.count { it.state == "DONE" }, steps.size),
                    onClick = { goalSheet = goal.id },
                    showChevron = true,
                )
            }
        }
        item(key = "trackingTitle") { GroupTitle(stringResource(R.string.orbit_tracking_section)) }
        val trackList = tracking
        val active = trackList.orEmpty().filter { it.state == "ACTIVE" }
        val done = trackList.orEmpty().filter { it.state == "DONE" }.take(20)
        if (trackList != null && active.isEmpty()) {
            item(key = "trackingEmpty") { Hint(stringResource(R.string.orbit_tracking_empty, who)) }
        }
        items(active, key = { "t-" + it.id }) { item ->
            OrbitListRow(
                orbitIcon("radar"), item.title, item.notes.lastOrNull()?.text ?: item.body,
                shortDate(item.updatedAt), onClick = { trackingSheet = item.id }, showChevron = true,
            )
        }
        if (done.isNotEmpty()) {
            item(key = "trackingDoneTitle") { GroupTitle(stringResource(R.string.orbit_tracking_done_section)) }
            items(done, key = { "td-" + it.id }) { item ->
                OrbitListRow(
                    orbitIcon("circle-check"), item.title, item.notes.lastOrNull()?.text ?: item.body,
                    shortDate(item.updatedAt), onClick = { trackingSheet = item.id },
                )
            }
        }
    }

    val sheetGoal = goalSheet?.let { id -> goals?.firstOrNull { it.id == id } }
    if (sheetGoal != null) {
        val goal = sheetGoal
        OrbitModalBottomSheet(onDismissRequest = { goalSheet = null }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = 20.dp).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(goal.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(goalStateText(goal.state), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (goal.plan.isNotBlank()) SelectionContainer { Text(goal.plan) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { goalSheet = null; editor = goal }) { Text(stringResource(R.string.orbit_goal_edit)) }
                    OutlinedButton(onClick = {
                        action {
                            OrbitGoalRepository(context).update(goal, goal.title, goal.plan, if (goal.state == "ACTIVE") "PAUSED" else "ACTIVE")
                        }
                    }) { Text(stringResource(if (goal.state == "ACTIVE") R.string.pause else R.string.orbit_resume)) }
                }
                val steps = tasks.orEmpty().filter { it.goalId == goal.id }
                Text(stringResource(R.string.orbit_goal_steps), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (steps.isEmpty()) {
                    Text(
                        stringResource(R.string.orbit_goal_steps_help, who), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                steps.forEach { task ->
                    OrbitListRow(
                        orbitIcon(if (task.state == "DONE") "circle-check" else "circle-dot"), task.title,
                        task.lastObservation.takeIf { it.isNotBlank() }, null,
                        onClick = { goalSheet = null; onOpenTask(task.id) }, showChevron = true,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.orbit_goal_notes), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    if (goal.state != "CANCELLED") {
                        // Progress is logged by telling Orbit; the prompt waits in the chat box.
                        TextButton(onClick = {
                            goalSheet = null
                            onDraft(context.getString(R.string.orbit_goal_note_draft, goal.title))
                        }) { Text(stringResource(R.string.orbit_goal_note_add)) }
                    }
                }
                val notes = goalNotes?.filter { it.extra == goal.id }?.sortedByDescending { it.createdAt }
                if (notes == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (notes.isEmpty()) {
                    Text(
                        stringResource(R.string.orbit_goal_notes_empty, who), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                notes.orEmpty().take(20).forEach { note -> GoalNoteCard(note, onDelete = { deletingNote = note.id }) }
                if (notes.orEmpty().size > 20) {
                    Text(
                        stringResource(R.string.orbit_goal_notes_recent), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    deletingNote?.let { id ->
        OrbitDialog(
            onDismissRequest = { deletingNote = null },
            title = stringResource(R.string.orbit_goal_note_delete_confirm),
            confirmButton = {
                OrbitDialogAction(destructive = true, onClick = {
                    deletingNote = null
                    action { notesRepository.delete(id) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { OrbitDialogAction(onClick = { deletingNote = null }, secondary = true) { Text(stringResource(R.string.cancel)) } },
        )
    }

    val sheetItem = trackingSheet?.let { id -> tracking?.firstOrNull { it.id == id } }
    if (sheetItem != null) {
        val item = sheetItem
        OrbitModalBottomSheet(onDismissRequest = { trackingSheet = null }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = 20.dp).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (item.body.isNotBlank()) SelectionContainer { Text(item.body) }
                if (item.notes.isNotEmpty()) {
                    Text(stringResource(R.string.orbit_tracking_notes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    item.notes.asReversed().forEach { note ->
                        Column {
                            Text(shortDate(note.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(note.text)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        action { trackingRepository.update(item.id, state = if (item.state == "ACTIVE") "DONE" else "ACTIVE") }
                    }) {
                        Text(stringResource(if (item.state == "ACTIVE") R.string.orbit_tracking_complete else R.string.orbit_tracking_reopen))
                    }
                    TextButton(onClick = { confirmDelete = item.id }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    confirmDelete?.let { id ->
        OrbitDialog(
            onDismissRequest = { confirmDelete = null },
            title = stringResource(R.string.orbit_tracking_delete_confirm),
            confirmButton = {
                OrbitDialogAction(destructive = true, onClick = {
                    confirmDelete = null
                    trackingSheet = null
                    action { trackingRepository.delete(id) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { OrbitDialogAction(onClick = { confirmDelete = null }, secondary = true) { Text(stringResource(R.string.cancel)) } },
        )
    }

    editor?.let { goal ->
        GoalEditor(
            goal = goal,
            onDismiss = { editor = null },
            onSave = { title, plan ->
                editor = null
                action { OrbitGoalRepository(context).update(goal, title, plan, goal.state) }
            },
        )
    }
}

@Composable
private fun goalStateText(state: String) = stringResource(
    when (state) {
        "ACTIVE" -> R.string.orbit_task_active
        "PAUSED" -> R.string.orbit_task_paused
        else -> R.string.orbit_task_cancelled
    }
)

/** A briefing Orbit wrote in the background, or a progress entry; tap to read it all. */
@Composable
private fun GoalNoteCard(note: OrbitBoardRepository.Item, onDelete: () -> Unit) {
    var expanded by rememberSaveable(note.id) { mutableStateOf(false) }
    val briefing = note.state == "BRIEF"
    SoftCard(Modifier.clickable { expanded = !expanded }.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(orbitIcon(if (briefing) "mail-open" else "pencil-line"), null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (briefing) R.string.orbit_goal_note_briefing else R.string.orbit_goal_note_progress) +
                    " · " + shortDate(note.createdAt),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
                Icon(orbitIcon("trash-2"), stringResource(R.string.delete), Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (briefing && note.title.isNotBlank()) {
            Text(note.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        val text = note.body
        if (expanded) SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
        else Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GoalEditor(goal: OrbitGoalEntity?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(goal?.title.orEmpty()) }
    var plan by rememberSaveable { mutableStateOf(goal?.plan.orEmpty()) }
    OrbitDialog(
        onDismissRequest = onDismiss,
        title = stringResource(if (goal == null) R.string.orbit_goal_create else R.string.orbit_goal_edit),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    title, { if (it.length <= 80) title = it }, label = { Text(stringResource(R.string.orbit_goal_title)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                )
                OutlinedTextField(
                    plan, { if (it.length <= 8000) plan = it }, minLines = 4, maxLines = 8,
                    label = { Text(stringResource(R.string.orbit_goal_plan)) }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                )
            }
        },
        confirmButton = {
            OrbitDialogAction(enabled = title.isNotBlank(), onClick = { onSave(title, plan) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { OrbitDialogAction(onClick = onDismiss, secondary = true) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---------------------------------------------------------------- Builds

/** One row of the Builds tab's first part: a work Orbit saved, or a file it made. */
private sealed interface BuildEntry {
    val key: String
    val time: Long

    data class Work(val latest: OrbitArtifactVersionEntity, val count: Int) : BuildEntry {
        override val key get() = "work:" + latest.artifactId
        override val time get() = latest.createdAt
    }

    data class Made(val file: com.ai.assistance.operit.features.orbit.OrbitFiles.Entry) : BuildEntry {
        override val key get() = "file:" + file.path
        override val time get() = file.modifiedAt
    }
}

/**
 * The Builds tab, like Muse: on top a switch between what Orbit made (its works and files) and the
 * Library, the files the user sent Orbit in chat. Every row has a ⋮ for its actions.
 */
@Composable
private fun OrbitBuildsPage(
    orbit: OrbitProfile,
    filesVersion: Int,
    onOpenTask: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onDraft: (String) -> Unit,
) {
    val who = orbitName(orbit)
    val context = LocalContext.current
    val dao = remember(context) { OrbitTaskRepository(context).dao }
    val versions by remember(orbit.id) { dao.observeArtifacts(orbit.id).catch { emit(emptyList()) } }.collectAsState(initial = null)
    var openArtifact by rememberSaveable(orbit.id) { mutableStateOf<String?>(null) }
    // 0: what Orbit made; 1: the Library of what the user sent.
    var section by rememberSaveable(orbit.id) { mutableIntStateOf(0) }
    // files/ in Orbit's folder: things made for the user (reports, voice messages, shared files).
    val orbitFiles = remember(orbit.id) { com.ai.assistance.operit.features.orbit.OrbitFiles.of(context, orbit.id) }
    var filesReload by remember { mutableIntStateOf(0) }
    val madeFiles by produceState<Result<List<com.ai.assistance.operit.features.orbit.OrbitFiles.Entry>>?>(null, orbit.id, filesReload, filesVersion) {
        value = try {
            Result.success(orbitFiles.list().filter { it.path.startsWith("files/") })
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    val pins by produceState(emptyList<String>(), orbit.id, filesReload, filesVersion) {
        value = try { orbitFiles.pins() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }
    var uploadsReload by remember { mutableIntStateOf(0) }
    // Read when the Library is shown, and again each time it is opened, so new uploads appear.
    val uploads by produceState<Result<List<com.ai.assistance.operit.features.orbit.OrbitUploads.Upload>>?>(null, orbit.id, uploadsReload, section) {
        if (section != 1) return@produceState
        value = try {
            Result.success(com.ai.assistance.operit.features.orbit.OrbitUploads.list(context, orbit.id))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    var deletingUpload by remember { mutableStateOf<com.ai.assistance.operit.features.orbit.OrbitUploads.Upload?>(null) }
    // Pictures, videos, sounds and plain text open in Orbit's own viewer; anything else goes to another app.
    var previewFile by remember { mutableStateOf<java.io.File?>(null) }
    fun viewFile(fileOf: () -> java.io.File) {
        val file = runCatching(fileOf).getOrNull()
        if (file != null && file.isFile && OrbitMediaPreview.canPreview(file.name)) previewFile = file
        else openOrbitFile(context, fileOf)
    }
    val action = rememberAction()
    // Pinned files first, in pin order; then works and files together, newest first.
    val entries = remember(versions, madeFiles, pins) {
        val works = versions.orEmpty()
        val fileList = madeFiles?.getOrNull().orEmpty()
        val workRows = works.groupBy { it.artifactId }.values
            .map { group -> BuildEntry.Work(group.maxBy { it.createdAt }, group.size) }
        val (pinned, rest) = fileList.map { BuildEntry.Made(it) }.partition { it.file.path in pins }
        pinned.sortedBy { pins.indexOf(it.file.path) } + (workRows + rest).sortedByDescending { it.time }
    }

    val history = openArtifact?.let { id -> versions.orEmpty().filter { it.artifactId == id }.sortedByDescending { it.createdAt } }
    // Keeps the work on screen while its page slides away.
    var shownHistory by remember(orbit.id) { mutableStateOf(history) }
    SideEffect { if (!history.isNullOrEmpty()) shownHistory = history }
    // The list stays under an open work's page, hidden from screen readers while covered.
    Box(Modifier.fillMaxSize().then(if (!history.isNullOrEmpty()) Modifier.clearAndSetSemantics { } else Modifier)) {
    TabList(title = null) {
        item(key = "sections") { BuildSections(section) { section = it } }
        if (section == 0) {
            val works = versions
            val files = madeFiles
            if (works == null || files == null) {
                item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            } else {
                if (files.isFailure) item(key = "files-failed") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.orbit_file_load_failed), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { filesReload++ }) { Text(stringResource(R.string.orbit_memory_retry)) }
                    }
                }
                if (entries.isEmpty() && files.isSuccess) item(key = "empty") { Hint(stringResource(R.string.orbit_builds_made_empty, who)) }
                items(entries, key = { it.key }) { entry ->
                    when (entry) {
                        is BuildEntry.Work -> BuildRow(
                            tile = { FileTile(entry.latest.name, work = true, seed = entry.latest.artifactId) },
                            title = entry.latest.name,
                            subtitle = stringResource(R.string.orbit_builds_work) +
                                (if (entry.count > 1) " · " + stringResource(R.string.orbit_builds_versions, entry.count) else "") +
                                " · " + shortDate(entry.latest.createdAt),
                            onClick = { openArtifact = entry.latest.artifactId },
                        ) { close ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.orbit_file_open)) },
                                onClick = { close(); openArtifact = entry.latest.artifactId })
                            DropdownMenuItem(text = { Text(stringResource(R.string.orbit_artifact_source)) },
                                onClick = { close(); onOpenTask(entry.latest.taskId) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.orbit_artifact_revise)) }, onClick = {
                                close(); onDraft(context.getString(R.string.orbit_build_continue_draft, entry.latest.name))
                            })
                        }
                        is BuildEntry.Made -> {
                            val file = entry.file
                            val name = file.path.substringAfterLast('/')
                            val pinned = file.path in pins
                            val isImage = com.ai.assistance.operit.features.orbit.OrbitFiles.kindOf(file.path) ==
                                com.ai.assistance.operit.features.orbit.OrbitFiles.Kind.IMAGE
                            // Only pictures need the file; the safety checks touch the disk, so they run off the main thread.
                            val image by produceState<java.io.File?>(null, file.path, file.modifiedAt, isImage) {
                                value = if (!isImage) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching { orbitFiles.fileFor(file.path) }.getOrNull()
                                }
                            }
                            BuildRow(
                                tile = { FileTile(name, image = image, imageExpected = isImage) },
                                title = name,
                                subtitle = typeLabel(name) + " · " + shortDate(file.modifiedAt),
                                pinned = pinned,
                                onClick = {
                                    if (com.ai.assistance.operit.features.orbit.OrbitFiles.isText(file.path)) onOpenFile(file.path)
                                    else viewFile { orbitFiles.fileFor(file.path) }
                                },
                            ) { close ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (pinned) R.string.orbit_library_unpin else R.string.orbit_library_pin)) },
                                    onClick = { close(); action { orbitFiles.setPinned(file.path, !pinned); filesReload++ } },
                                )
                                DropdownMenuItem(text = { Text(stringResource(R.string.orbit_file_share)) },
                                    onClick = { close(); shareOrbitFile(context) { orbitFiles.fileFor(file.path) } })
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.orbit_file_delete), color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        close()
                                        action {
                                            try {
                                                orbitFiles.delete(file.path)
                                                showToast(context, context.getString(R.string.orbit_builds_moved_to_trash))
                                            } finally {
                                                filesReload++
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            val result = uploads
            val list = result?.getOrNull()
            when {
                result == null -> item(key = "uploads-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                list == null -> item(key = "uploads-failed") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.orbit_file_load_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { uploadsReload++ }) { Text(stringResource(R.string.orbit_memory_retry)) }
                    }
                }
                else -> {
                    item(key = "uploads-usage") { UploadsUsage(who, list.sumOf { it.size }) }
                    if (list.isEmpty()) item(key = "uploads-empty") { Hint(stringResource(R.string.orbit_uploads_empty, who)) }
                    items(list, key = { "upload:" + it.file.absolutePath }) { upload ->
                        val isImage = com.ai.assistance.operit.features.orbit.OrbitFiles.kindOf(upload.name) ==
                            com.ai.assistance.operit.features.orbit.OrbitFiles.Kind.IMAGE
                        BuildRow(
                            tile = { FileTile(upload.name, image = upload.file.takeIf { isImage }, imageExpected = isImage) },
                            title = upload.name,
                            subtitle = typeLabel(upload.name) + " · " +
                                android.text.format.Formatter.formatShortFileSize(context, upload.size) +
                                " · " + shortDate(upload.addedAt),
                            onClick = { viewFile { upload.file } },
                        ) { close ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.orbit_file_open)) },
                                onClick = { close(); viewFile { upload.file } })
                            DropdownMenuItem(text = { Text(stringResource(R.string.orbit_file_share)) },
                                onClick = { close(); shareOrbitFile(context) { upload.file } })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.orbit_file_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { close(); deletingUpload = upload },
                            )
                        }
                    }
                }
            }
        }
    }
    }
    deletingUpload?.let { upload ->
        OrbitDialog(
            onDismissRequest = { deletingUpload = null },
            title = stringResource(R.string.orbit_uploads_delete_title),
            text = { Text(stringResource(R.string.orbit_uploads_delete_text, upload.name)) },
            confirmButton = {
                OrbitDialogAction(destructive = true, onClick = {
                    deletingUpload = null
                    action {
                        try {
                            com.ai.assistance.operit.features.orbit.OrbitUploads.delete(context, orbit.id, upload)
                        } finally {
                            uploadsReload++
                        }
                    }
                }) { Text(stringResource(R.string.orbit_file_delete)) }
            },
            dismissButton = { OrbitDialogAction(onClick = { deletingUpload = null }, secondary = true) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // A work's page slides in over the list; the back swipe slides it away under the finger.
    OrbitCoverPage(visible = !history.isNullOrEmpty(), onDismiss = { openArtifact = null }) {
        (history?.takeIf { it.isNotEmpty() } ?: shownHistory)?.takeIf { it.isNotEmpty() }?.let { shown ->
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                key(shown.first().artifactId) {
                    BuildDetail(orbit, shown, onBack = { openArtifact = null }, onOpenTask = onOpenTask, onDraft = onDraft)
                }
            }
        }
    }
    previewFile?.let { file -> OrbitMediaPreview.Screen(file) { previewFile = null } }
}

/** The switch at the top of the Builds tab: what Orbit made, or the Library of what the user sent. */
@Composable
private fun BuildSections(selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50), color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Row(Modifier.padding(6.dp)) {
            listOf(R.string.orbit_builds_made, R.string.orbit_builds_uploads).forEachIndexed { index, label ->
                val chosen = index == selected
                Surface(
                    onClick = { onSelect(index) },
                    shape = RoundedCornerShape(50),
                    color = if (chosen) colors.onSurface.copy(alpha = 0.07f) else Color.Transparent,
                    modifier = Modifier.weight(1f).height(48.dp).semantics { this.selected = chosen },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(label), style = MaterialTheme.typography.titleMedium,
                            color = if (chosen) colors.onSurface else colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** How much of the Library's room is used, and what happens when it is full. */
@Composable
private fun UploadsUsage(who: String, used: Long) {
    val context = LocalContext.current
    val limit = com.ai.assistance.operit.features.orbit.OrbitUploads.LIMIT_BYTES
    SoftCard {
        Text(
            stringResource(
                R.string.orbit_uploads_used,
                android.text.format.Formatter.formatShortFileSize(context, used),
                android.text.format.Formatter.formatShortFileSize(context, limit),
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        LinearProgressIndicator(progress = { (used.toDouble() / limit).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.orbit_uploads_hint, who), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One row of the Builds tab: a tile, the name and one line below it, and a ⋮ with its actions. */
@Composable
private fun BuildRow(
    tile: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    pinned: Boolean = false,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tile()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (pinned) {
                    Icon(orbitIcon("pin"), stringResource(R.string.orbit_library_pinned),
                        Modifier.padding(start = 6.dp).size(16.dp), tint = colors.onSurfaceVariant)
                }
            }
            Text(
                subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { open = true }) {
                Icon(orbitIcon("ellipsis-vertical"), stringResource(R.string.orbit_library_more_actions), Modifier.size(20.dp),
                    tint = colors.onSurfaceVariant)
            }
            OrbitDropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
        }
    }
}

/** A file's type as shown under its name: the extension in capitals, or a word for a file without one. */
@Composable
private fun typeLabel(name: String): String =
    name.substringAfterLast('.', "").uppercase().take(6).ifEmpty { stringResource(R.string.orbit_library_other) }

/**
 * The picture in front of a file: a picture's own preview; for a document, a small page with a
 * folded corner and its type on it, like a red PDF or a gray MD; for a work Orbit made, a colored
 * square, its color picked from [seed] so each work keeps its own.
 */
@Composable
private fun FileTile(
    name: String,
    image: java.io.File? = null,
    imageExpected: Boolean = false,
    work: Boolean = false,
    seed: String = name,
) {
    val colors = MaterialTheme.colorScheme
    val ext = name.substringAfterLast('.', "").lowercase()
    val kind = com.ai.assistance.operit.features.orbit.OrbitFiles.kindOf(name)
    val video = ext in setOf("mp4", "webm", "mov", "mkv")
    val audio = kind == com.ai.assistance.operit.features.orbit.OrbitFiles.Kind.AUDIO
    // Pictures, works, sound and video get a rounded square; documents get a page.
    if (image != null || imageExpected || work || audio || video) {
        val (background, content) = when {
            work -> workTileColors[Math.floorMod(seed.hashCode(), workTileColors.size)] to Color.White
            audio -> Color(0xFF8E59D9) to Color.White
            video -> Color(0xFF374151) to Color.White
            else -> colors.onSurface.copy(alpha = 0.06f) to colors.onSurfaceVariant
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(background),
        ) {
            when {
                image != null -> coil.compose.AsyncImage(
                    model = image, contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                // A picture keeps the plain tile until its file is ready, so no icon flashes first.
                imageExpected -> Unit
                work -> Icon(orbitIcon("component"), null, Modifier.size(24.dp), tint = content)
                audio -> Icon(orbitIcon("audio-lines"), null, Modifier.size(24.dp), tint = content)
                else -> Icon(orbitIcon("film"), null, Modifier.size(24.dp), tint = content)
            }
        }
        return
    }
    val (page, label) = when (ext) {
        "pdf" -> Color(0xFFE5484D) to Color.White
        "doc", "docx", "rtf", "odt" -> Color(0xFF2F6FEB) to Color.White
        "xls", "xlsx", "csv" -> Color(0xFF1F9D55) to Color.White
        "ppt", "pptx" -> Color(0xFFE8743B) to Color.White
        "zip", "rar", "7z", "tar", "gz" -> Color(0xFFB7791F) to Color.White
        else -> colors.onSurface.copy(alpha = 0.07f) to colors.onSurfaceVariant
    }
    val fold = Color.Black.copy(alpha = 0.12f)
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(width = 38.dp, height = 46.dp).drawWithCache {
                val corner = size.width * 0.3f
                val radius = 5.dp.toPx()
                // The page, its top right corner cut off and folded down.
                val outline = androidx.compose.ui.graphics.Path().apply {
                    moveTo(radius, 0f)
                    lineTo(size.width - corner, 0f)
                    lineTo(size.width, corner)
                    lineTo(size.width, size.height - radius)
                    quadraticTo(size.width, size.height, size.width - radius, size.height)
                    lineTo(radius, size.height)
                    quadraticTo(0f, size.height, 0f, size.height - radius)
                    lineTo(0f, radius)
                    quadraticTo(0f, 0f, radius, 0f)
                    close()
                }
                val flap = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width - corner, 0f)
                    lineTo(size.width - corner, corner - radius / 2)
                    quadraticTo(size.width - corner, corner, size.width - corner + radius / 2, corner)
                    lineTo(size.width, corner)
                    close()
                }
                onDrawBehind {
                    drawPath(outline, page)
                    drawPath(flap, fold)
                }
            },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Text(
                ext.uppercase().take(4).ifEmpty { "•" }, color = label, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

/** Soft colors for works' tiles, white icon on each. */
private val workTileColors = listOf(
    Color(0xFFC08A6B), Color(0xFF6B9AC0), Color(0xFF7FAE84), Color(0xFFB77FB0),
    Color(0xFFD1A04F), Color(0xFF6FA8A3), Color(0xFFC07474), Color(0xFF8C86C9),
)


@Composable
private fun BuildDetail(
    orbit: OrbitProfile,
    history: List<OrbitArtifactVersionEntity>,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
    onDraft: (String) -> Unit,
) {
    val context = LocalContext.current
    var versionId by rememberSaveable(history.first().artifactId) { mutableStateOf(history.first().id) }
    val version = history.firstOrNull { it.id == versionId } ?: history.first()
    var offset by rememberSaveable(version.id) { mutableIntStateOf(0) }
    val text by produceState<Result<String>?>(null, version.id) {
        value = try {
            Result.success(OrbitArtifactRepository(context).read(orbit.id, version.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrbitCircleButton(orbitIcon("chevron-left"), stringResource(R.string.back), onBack)
                Spacer(Modifier.width(12.dp))
                Text(
                    version.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.orbit_artifact_source), "list", { onOpenTask(version.taskId) })
                // Revisions are asked of Orbit in chat; the prompt waits in the chat box.
                PillButton(stringResource(R.string.orbit_artifact_revise), "pencil", {
                    onDraft(context.getString(R.string.orbit_build_continue_draft, version.name))
                })
            }
        }
        if (history.size > 1) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    history.take(6).forEachIndexed { index, item ->
                        FilterChip(selected = item.id == version.id, onClick = { versionId = item.id }, label = { Text("v${history.size - index}") })
                    }
                }
            }
        }
        item {
            SoftCard {
                Text(shortDate(version.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val result = text
                when {
                    result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    result.isFailure -> Text(result.exceptionOrNull()?.message.orEmpty(), color = MaterialTheme.colorScheme.error)
                    else -> {
                        val body = result.getOrThrow()
                        SelectionContainer { Text(body.drop(offset).take(20_000)) }
                        Row {
                            if (offset > 0) {
                                TextButton(onClick = { offset = (offset - 20_000).coerceAtLeast(0) }) { Text(stringResource(R.string.orbit_previous_page)) }
                            }
                            if (offset + 20_000 < body.length) {
                                TextButton(onClick = { offset += 20_000 }) { Text(stringResource(R.string.orbit_next_page)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Notifications

/** Quiet hours and how often task results are delivered; opened from the Orbit settings. */
@Composable
internal fun OrbitNotificationsPage(orbitId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val action = rememberAction()
    val dao = remember(context) { OrbitTaskRepository(context).dao }
    var policy by remember(orbitId) { mutableStateOf<OrbitNotificationEntity?>(null) }
    LaunchedEffect(orbitId) {
        policy = try {
            dao.notificationPolicy(orbitId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: OrbitNotificationEntity(orbitId)
    }
    fun save(change: (OrbitNotificationEntity) -> OrbitNotificationEntity) = action {
        OrbitExecutionGate.serialized {
            val current = dao.notificationPolicy(orbitId) ?: OrbitNotificationEntity(orbitId)
            dao.saveNotificationPolicy(change(current))
        }
        policy = dao.notificationPolicy(orbitId)
        OrbitTaskScheduler.reschedule(context)
    }
    OrbitPageBack(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrbitCircleButton(orbitIcon("chevron-left"), stringResource(R.string.back), onBack)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.orbit_notifications_row), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            val settings = policy
            if (settings == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                SoftCard {
                    Text(stringResource(R.string.orbit_notifications_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.orbit_quiet_hours), Modifier.weight(1f))
                        Switch(checked = settings.quietEnabled, onCheckedChange = { enabled -> save { it.copy(quietEnabled = enabled) } })
                    }
                    Text(stringResource(R.string.orbit_delivery_interval), style = MaterialTheme.typography.titleSmall)
                    listOf(0, 15, 60).forEach { minutes ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { save { it.copy(intervalMinutes = minutes) } },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.intervalMinutes == minutes, onClick = { save { it.copy(intervalMinutes = minutes) } })
                            Text(
                                if (minutes == 0) stringResource(R.string.orbit_delivery_immediate)
                                else stringResource(R.string.orbit_delivery_minutes, minutes)
                            )
                        }
                    }
                }
            }
        }
    }
}
