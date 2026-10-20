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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
internal fun sendToOrbit(context: android.content.Context, orbit: OrbitProfile, text: String) {
    ChatRuntimeHolder.getInstance(context.applicationContext).getCore(ChatRuntimeSlot.MAIN)
        .sendUserMessage(
            chatIdOverride = orbit.chatId, messageTextOverride = text,
            // Keep whatever reply the user picked in the chat for their own next message.
            turnOptions = com.ai.assistance.operit.data.model.ChatTurnOptions(useComposerReply = false),
        )
}

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
    filesVersion: Int = 0,
    onOpenFile: (String) -> Unit = {},
) {
    var openTaskId by rememberSaveable(orbit.id) { mutableStateOf<String?>(null) }
    // Keeps the task on screen while its page slides away.
    var shownTaskId by remember(orbit.id) { mutableStateOf(openTaskId) }
    LaunchedEffect(openTaskId) { openTaskId?.let { shownTaskId = it } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // A task page over the tab hides the tab from screen readers.
        Box(Modifier.fillMaxSize().then(if (openTaskId != null) Modifier.clearAndSetSemantics { } else Modifier)) {
            when (tab) {
                OrbitTab.CHAT -> Unit
                OrbitTab.FEED -> OrbitFeedPage(orbit, onDraft)
                OrbitTab.IDEAS -> OrbitIdeasPage(orbit, onDraft)
                OrbitTab.GOALS -> OrbitGoalsPage(orbit, onOpenTask = { openTaskId = it }, onDraft = onDraft)
                OrbitTab.BUILDS -> OrbitBuildsPage(orbit, filesVersion, onOpenTask = { openTaskId = it }, onOpenFile = onOpenFile, onDraft = onDraft)
            }
        }
        OrbitCoverPage(visible = openTaskId != null, onDismiss = { openTaskId = null }) {
            (openTaskId ?: shownTaskId)?.let { taskId ->
                key(taskId) { OrbitTasksPage(orbit.id, onBack = { openTaskId = null }, initialTaskId = taskId) }
            }
        }
    }
}

/**
 * Orbit's header (menu, picture and name) for the tab pages. Every tab shows it fixed on top in
 * the same place as in the chat, so the picture never moves when the user switches tabs.
 */
internal val LocalOrbitTabHeader = compositionLocalOf<(@Composable () -> Unit)?> { null }

/** The page frame: Orbit's fixed header, a large title and an optional action on the right. */
@Composable
private fun TabList(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val openDrawer = com.ai.assistance.operit.ui.main.LocalOpenAppDrawer.current
    val header = LocalOrbitTabHeader.current
    val density = LocalDensity.current
    // Measured after the first frame; the guess is the header's usual size so nothing jumps.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var headerHeight by remember { mutableStateOf(statusBarTop + 120.dp) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = if (header == null) Modifier.fillMaxSize().statusBarsPadding() else Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp, bottom = 24.dp,
                // The header's lower edge fades out, so the title tucks a little under it.
                top = if (header == null) 8.dp else (headerHeight - 12.dp).coerceAtLeast(0.dp),
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "frame") {
                Column {
                    if (header == null) {
                        OrbitCircleButton(orbitIcon("equal"), stringResource(R.string.menu), openDrawer)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = if (header == null) 16.dp else 0.dp, bottom = 4.dp),
                    ) {
                        Text(
                            title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        action?.invoke()
                    }
                }
            }
            content()
        }
        if (header != null) {
            Box(Modifier.fillMaxWidth().onSizeChanged { headerHeight = with(density) { it.height.toDp() } }) {
                header()
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
                    showToast(context, e.message ?: e.toString())
                }
            }
        }
    }
}

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
private fun OrbitFeedPage(orbit: OrbitProfile, onDraft: (String) -> Unit) {
    val context = LocalContext.current
    val action = rememberAction()
    val repository = remember(orbit.id) { OrbitBoardRepository(context, orbit.id, Board.FEED) }
    val posts by remember(repository) { repository.observe().catch { emit(emptyList()) } }.collectAsState(initial = null)
    var briefVersion by remember { mutableIntStateOf(0) }
    val brief by produceState<String?>(null, orbit.id, briefVersion) {
        value = try {
            OrbitBoardRepository.readBrief(context, orbit.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
    }
    var editingBrief by remember { mutableStateOf(false) }
    val started = stringResource(R.string.orbit_feed_started)

    TabList(
        title = stringResource(R.string.orbit_tab_feed),
        action = {
            if (!brief.isNullOrBlank()) {
                OrbitCircleButton(orbitIcon("sparkles"), stringResource(R.string.orbit_feed_write_now), {
                    action { if (OrbitFeedGenerator.writeOne(context, orbit.id)) showToast(context, started) }
                })
            }
        },
    ) {
        val currentBrief = brief
        if (currentBrief != null) {
            item(key = "brief") {
                if (currentBrief.isBlank()) {
                    SoftCard {
                        Icon(orbitIcon("newspaper"), null, Modifier.size(28.dp))
                        Text(
                            stringResource(R.string.orbit_feed_brief_title), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(stringResource(R.string.orbit_feed_brief_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        PillButton(stringResource(R.string.orbit_feed_brief_write), "pencil", { editingBrief = true })
                    }
                } else {
                    BriefPill(currentBrief) { editingBrief = true }
                }
            }
        }
        val list = posts
        when {
            list == null -> item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            list.isEmpty() -> item(key = "empty") { Hint(stringResource(R.string.orbit_feed_posts_empty)) }
            else -> items(list, key = { it.id }) { post ->
                FeedPostRow(
                    post = post,
                    isFirst = post.id == list.first().id,
                    isLast = post.id == list.last().id,
                    // Tapping the chosen answer again takes it back.
                    onVote = { vote -> action { repository.update(post.id, vote = if (post.vote == vote) 0 else vote) } },
                    onDiscuss = {
                        onDraft(context.getString(R.string.orbit_feed_discuss_message, post.title, post.body.take(1500)))
                    },
                    onMove = { up -> action { repository.move(post.id, up) } },
                    onDelete = { action { repository.delete(post.id) } },
                )
            }
        }
    }

    if (editingBrief) {
        BriefDialog(
            initial = brief.orEmpty(),
            onDismiss = { editingBrief = false },
            onSave = { text ->
                editingBrief = false
                action {
                    OrbitFeedGenerator.saveBrief(context, orbit.id, text)
                    briefVersion++
                    showToast(
                        context,
                        context.getString(if (text.isBlank()) R.string.orbit_feed_brief_cleared else R.string.orbit_feed_brief_saved),
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
private fun BriefDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val colors = MaterialTheme.colorScheme
    val changed = text.trim() != initial.trim()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // With the keyboard up the card gives way and scrolls; the two buttons always keep their room.
            Surface(
                shape = RoundedCornerShape(32.dp), color = colors.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp)) {
                        Text(
                            stringResource(R.string.orbit_feed_brief_dialog_title),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(if (initial.isBlank()) R.string.orbit_feed_brief_hint else R.string.orbit_feed_brief_dialog_hint),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    TextField(
                        text, { if (it.length <= OrbitBoardRepository.MAX_BRIEF) text = it },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        minLines = 6, maxLines = 12,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogPill(stringResource(R.string.cancel), enabled = true, onClick = onDismiss, modifier = Modifier.weight(1f))
                DialogPill(stringResource(R.string.save), enabled = changed, onClick = { onSave(text) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DialogPill(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50), color = colors.surfaceContainerHigh,
        modifier = modifier.height(56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text, style = MaterialTheme.typography.titleMedium,
                color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedPostRow(
    post: OrbitBoardRepository.Item,
    isFirst: Boolean,
    isLast: Boolean,
    onVote: (Int) -> Unit,
    onDiscuss: () -> Unit,
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
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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

@Composable
private fun OrbitIdeasPage(orbit: OrbitProfile, onDraft: (String) -> Unit) {
    val context = LocalContext.current
    val action = rememberAction()
    val repository = remember(orbit.id) { OrbitBoardRepository(context, orbit.id, Board.IDEAS) }
    val ideas by remember(repository) { repository.observe().catch { emit(emptyList()) } }.collectAsState(initial = null)
    var dismissing by remember { mutableStateOf<OrbitBoardRepository.Item?>(null) }
    var openedId by rememberSaveable(orbit.id) { mutableStateOf<String?>(null) }
    val askIdeas = { onDraft(context.getString(R.string.orbit_ideas_ask_message)) }
    // Tapping the chosen answer again takes it back.
    fun vote(idea: OrbitBoardRepository.Item, vote: Int) =
        action { repository.update(idea.id, vote = if (idea.vote == vote) 0 else vote) }

    TabList(title = stringResource(R.string.orbit_tab_ideas)) {
        val list = ideas
        if (list == null) {
            item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            return@TabList
        }
        val open = list.filter { it.state == "OPEN" }
        val done = list.filter { it.state == "BUILT" }.take(10)
        if (open.isEmpty()) {
            item(key = "empty") {
                SoftCard {
                    Icon(orbitIcon("lightbulb"), null, Modifier.size(28.dp))
                    Text(stringResource(R.string.orbit_ideas_empty), style = MaterialTheme.typography.bodyLarge)
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
            items(open, key = { it.id }) { idea ->
                IdeaRow(idea, onOpen = { openedId = idea.id }, onVote = { vote(idea, it) })
            }
            item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    PillButton(stringResource(R.string.orbit_ideas_ask), "sparkles", askIdeas)
                }
            }
        }
        if (done.isNotEmpty()) {
            item(key = "doneTitle") { GroupTitle(stringResource(R.string.orbit_ideas_done)) }
            items(done, key = { "done-" + it.id }) { idea ->
                OrbitListRow(orbitIcon("check"), idea.title, idea.body, shortDate(idea.updatedAt), onClick = {})
            }
        }
    }

    // The sheet follows the saved idea, so a vote shows at once; an idea that is gone closes it.
    val opened = openedId?.let { id -> ideas?.firstOrNull { it.id == id && it.state == "OPEN" } }
    LaunchedEffect(ideas, openedId) { if (openedId != null && ideas != null && opened == null) openedId = null }
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
            onClose = {
                openedId = null
                dismissing = opened
            },
        )
    }

    dismissing?.let { idea ->
        AlertDialog(
            onDismissRequest = { dismissing = null },
            title = { Text(stringResource(R.string.orbit_idea_dismiss)) },
            text = { Text(stringResource(R.string.orbit_idea_dismiss_message)) },
            confirmButton = {
                TextButton(onClick = {
                    dismissing = null
                    action { repository.update(idea.id, state = "DISMISSED") }
                }) { Text(stringResource(R.string.orbit_idea_dismiss_action)) }
            },
            dismissButton = { TextButton(onClick = { dismissing = null }) { Text(stringResource(R.string.cancel)) } },
        )
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
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(idea.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text(body, style = MaterialTheme.typography.bodyLarge)
                    if (idea.expiresAt > 0) {
                        val days = ((idea.expiresAt - System.currentTimeMillis()) / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.orbit_idea_expires, days), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
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
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
                            text = { Text(stringResource(R.string.orbit_idea_dismiss_action), color = colors.error) },
                            leadingIcon = { Icon(orbitIcon("x"), null, tint = colors.error) },
                            onClick = { menu = false; hideThen(onClose) },
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
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

// ---------------------------------------------------------------- Goals and tracking

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrbitGoalsPage(orbit: OrbitProfile, onOpenTask: (String) -> Unit, onDraft: (String) -> Unit) {
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
            item(key = "trackingEmpty") { Hint(stringResource(R.string.orbit_tracking_empty)) }
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
        ModalBottomSheet(onDismissRequest = { goalSheet = null }) {
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
                    }) { Text(stringResource(if (goal.state == "ACTIVE") R.string.orbit_pause else R.string.orbit_resume)) }
                }
                val steps = tasks.orEmpty().filter { it.goalId == goal.id }
                Text(stringResource(R.string.orbit_goal_steps), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (steps.isEmpty()) {
                    Text(
                        stringResource(R.string.orbit_goal_steps_help), style = MaterialTheme.typography.bodySmall,
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
                        stringResource(R.string.orbit_goal_notes_empty), style = MaterialTheme.typography.bodySmall,
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
        AlertDialog(
            onDismissRequest = { deletingNote = null },
            title = { Text(stringResource(R.string.orbit_goal_note_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    deletingNote = null
                    action { notesRepository.delete(id) }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingNote = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    val sheetItem = trackingSheet?.let { id -> tracking?.firstOrNull { it.id == id } }
    if (sheetItem != null) {
        val item = sheetItem
        ModalBottomSheet(onDismissRequest = { trackingSheet = null }) {
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
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.orbit_tracking_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    trackingSheet = null
                    action { trackingRepository.delete(id) }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (goal == null) R.string.orbit_goal_create else R.string.orbit_goal_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    title, { if (it.length <= 80) title = it }, label = { Text(stringResource(R.string.orbit_goal_title)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    plan, { if (it.length <= 8000) plan = it }, minLines = 4, maxLines = 8,
                    label = { Text(stringResource(R.string.orbit_goal_plan)) }, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onSave(title, plan) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---------------------------------------------------------------- Builds

@Composable
private fun OrbitBuildsPage(
    orbit: OrbitProfile,
    filesVersion: Int,
    onOpenTask: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onDraft: (String) -> Unit,
) {
    val context = LocalContext.current
    val dao = remember(context) { OrbitTaskRepository(context).dao }
    val versions by remember(orbit.id) { dao.observeArtifacts(orbit.id).catch { emit(emptyList()) } }.collectAsState(initial = null)
    var openArtifact by rememberSaveable(orbit.id) { mutableStateOf<String?>(null) }
    // files/ in Orbit's folder: things made for the user (reports, voice messages, shared files).
    val orbitFiles = remember(orbit.id) { com.ai.assistance.operit.features.orbit.OrbitFiles.of(context, orbit.id) }
    var filesReload by remember { mutableIntStateOf(0) }
    val userFiles by produceState<Result<List<com.ai.assistance.operit.features.orbit.OrbitFiles.Entry>>?>(null, orbit.id, filesReload, filesVersion) {
        value = try {
            Result.success(orbitFiles.list().filter { it.path.startsWith("files/") }.sortedByDescending { it.modifiedAt })
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    val pins by produceState(emptyList<String>(), orbit.id, filesReload, filesVersion) {
        value = try { orbitFiles.pins() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }
    var kindFilter by rememberSaveable(orbit.id) { mutableStateOf<String?>(null) }
    var fileActions by remember { mutableStateOf<com.ai.assistance.operit.features.orbit.OrbitFiles.Entry?>(null) }
    val action = rememberAction()

    val history = openArtifact?.let { id -> versions.orEmpty().filter { it.artifactId == id }.sortedByDescending { it.createdAt } }
    // Keeps the work on screen while its page slides away.
    var shownHistory by remember(orbit.id) { mutableStateOf(history) }
    SideEffect { if (!history.isNullOrEmpty()) shownHistory = history }
    // The list stays under an open work's page, hidden from screen readers while covered.
    Box(Modifier.fillMaxSize().then(if (!history.isNullOrEmpty()) Modifier.clearAndSetSemantics { } else Modifier)) {
    TabList(title = stringResource(R.string.orbit_tab_builds)) {
        val list = versions
        when {
            list == null -> item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            list.isEmpty() -> item(key = "empty") { Hint(stringResource(R.string.orbit_artifacts_empty)) }
            else -> {
                val groups = list.groupBy { it.artifactId }.values
                    .map { group -> group.maxBy { it.createdAt } to group.size }
                    .sortedByDescending { it.first.createdAt }
                items(groups, key = { it.first.artifactId }) { (latest, count) ->
                    OrbitListRow(
                        icon = orbitIcon(orbitFileIcon(latest.name)),
                        title = latest.name,
                        detail = if (count > 1) stringResource(R.string.orbit_builds_versions, count) else null,
                        meta = shortDate(latest.createdAt),
                        onClick = { openArtifact = latest.artifactId },
                        showChevron = true,
                    )
                }
            }
        }
        val filesResult = userFiles
        val filesList = filesResult?.getOrNull()
        item(key = "files-title") { GroupTitle(stringResource(R.string.orbit_library_title)) }
        if (!filesList.isNullOrEmpty()) item(key = "files-hint") {
            Text(
                stringResource(R.string.orbit_library_long_press_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (!filesList.isNullOrEmpty()) item(key = "files-kinds") {
            LibraryKindChips(filesList, kindFilter) { kindFilter = it }
        }
        when {
            filesResult == null -> item(key = "files-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            filesList == null -> item(key = "files-failed") {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.orbit_file_load_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { filesReload++ }) { Text(stringResource(R.string.orbit_memory_retry)) }
                }
            }
            filesList.isEmpty() -> item(key = "files-empty") { Hint(stringResource(R.string.orbit_builds_files_empty)) }
            else -> {
                val shown = filesList
                    .filter { kindFilter == null || com.ai.assistance.operit.features.orbit.OrbitFiles.kindOf(it.path).name == kindFilter }
                    .sortedBy { pins.indexOf(it.path).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                if (shown.isEmpty()) item(key = "files-kind-empty") { Hint(stringResource(R.string.orbit_library_kind_empty)) }
                items(shown, key = { it.path }) { entry ->
                    LibraryRow(orbitFiles, entry, pinned = entry.path in pins,
                        onClick = {
                            if (com.ai.assistance.operit.features.orbit.OrbitFiles.isText(entry.path)) onOpenFile(entry.path)
                            else fileActions = entry
                        },
                        onLongClick = { fileActions = entry })
                }
            }
        }
    }
    }
    fileActions?.let { entry ->
        val pinned = entry.path in pins
        OrbitMadeFileActions(orbitFiles, entry, onDismiss = { fileActions = null }, onDeleted = { filesReload++ },
            pinned = pinned, onTogglePin = { action { orbitFiles.setPinned(entry.path, !pinned); filesReload++ } })
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
}

/** The Library's filter: all files, or only pictures, audio, documents or the rest. */
@Composable
private fun LibraryKindChips(
    files: List<com.ai.assistance.operit.features.orbit.OrbitFiles.Entry>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val counts = files.groupingBy { com.ai.assistance.operit.features.orbit.OrbitFiles.kindOf(it.path).name }.eachCount()
    val kinds = listOf(
        null to R.string.orbit_library_all,
        "IMAGE" to R.string.orbit_library_images,
        "AUDIO" to R.string.orbit_library_audio,
        "DOCUMENT" to R.string.orbit_library_documents,
        "OTHER" to R.string.orbit_library_other,
    ).filter { (kind, _) -> kind == null || (counts[kind] ?: 0) > 0 || kind == selected }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        kinds.forEach { (kind, label) ->
            FilterChip(
                selected = selected == kind,
                onClick = { onSelect(kind) },
                label = { Text(stringResource(label) + " " + (if (kind == null) files.size else counts[kind] ?: 0)) },
            )
        }
    }
}

/** One Library file; a picture shows a small preview. Long press for pin, share and delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(
    files: com.ai.assistance.operit.features.orbit.OrbitFiles,
    entry: com.ai.assistance.operit.features.orbit.OrbitFiles.Entry,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val name = entry.path.substringAfterLast('/')
    val image = com.ai.assistance.operit.features.orbit.OrbitFiles.kindOf(entry.path) ==
        com.ai.assistance.operit.features.orbit.OrbitFiles.Kind.IMAGE
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.orbit_library_more_actions),
            )
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
        ) {
            // Only pictures need the file; the safety checks touch the disk, so they run off the main thread.
            val file by androidx.compose.runtime.produceState<java.io.File?>(null, entry.path, entry.modifiedAt, image) {
                value = if (!image) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { files.fileFor(entry.path) }.getOrNull()
                }
            }
            if (image) {
                // A picture keeps the plain tile until its file is ready, so no icon flashes first.
                file?.let {
                    coil.compose.AsyncImage(
                        model = it, contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Icon(orbitIcon(orbitFileIcon(name)), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                shortDate(entry.modifiedAt) + " · " + android.text.format.Formatter.formatShortFileSize(context, entry.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        if (pinned) {
            Icon(orbitIcon("pin"), stringResource(R.string.orbit_library_pinned), Modifier.padding(start = 8.dp).size(18.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
    }
}



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
