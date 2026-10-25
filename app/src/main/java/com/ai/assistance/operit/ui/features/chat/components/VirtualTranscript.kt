package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageLocatorPreview
import com.ai.assistance.operit.ui.common.markdown.LocalResponseProcessExpanded
import com.ai.assistance.operit.ui.common.markdown.ResponseActivityHeader
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.stopScroll
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.ui.common.markdown.LocalTranscriptMarkdownSlice
import com.ai.assistance.operit.ui.common.markdown.prepareTranscriptMarkdown
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.ToolCollapseMode
import com.ai.assistance.operit.ui.features.chat.components.part.ThinkToolsXmlNodeGrouper
import com.ai.assistance.operit.ui.features.chat.components.style.bubble.LocalBubbleAiRenderSettings
import com.ai.assistance.operit.ui.features.chat.components.style.bubble.rememberBubbleAiRenderSettings
import androidx.compose.ui.text.rememberTextMeasurer
import com.ai.assistance.operit.ui.common.markdown.LocalTranscriptTextMeasurer

private data class TranscriptBookmark(val key: String, val offset: Int, val timestamp: Long?)
private object TranscriptBookmarks {
    private val entries = LinkedHashMap<String, TranscriptBookmark>()
    fun get(chatId: String) = entries[chatId]
    fun put(chatId: String, value: TranscriptBookmark) {
        entries.remove(chatId)
        entries[chatId] = value
        if (entries.size > 100) entries.remove(entries.keys.first())
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun VirtualTranscript(
    chatId: String,
    messages: List<ChatMessage>,
    process: ResponseProcessState,
    following: Boolean,
    onFollowingChange: ((Boolean) -> Unit)?,
    hasOlder: Boolean,
    hasNewer: Boolean,
    loadingPage: Boolean,
    onOlder: (() -> Unit)?,
    onNewer: (() -> Unit)?,
    onLatest: (() -> Unit)?,
    loadLocator: (suspend (String, String) -> List<ChatMessageLocatorPreview>)?,
    reveal: (suspend (Long) -> Boolean)?,
    onFavorite: ((Long, Boolean) -> Unit)?,
    textColor: Color,
    horizontalPadding: Dp,
    topPadding: Dp,
    bottomPadding: Dp,
    modifier: Modifier,
    renderMessage: @Composable (Int) -> Unit,
    footer: @Composable () -> Unit,
    splitMarkdown: Boolean = true,
    skipMessage: ((ChatMessage) -> Boolean)? = null,
) {
    if (!process.ready) {
        Box(modifier) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        return
    }
    val context = LocalContext.current
    val displayPreferences = remember(context) { DisplayPreferencesManager.getInstance(context) }
    val userPreferences = remember(context) { UserPreferencesManager.getInstance(context) }
    val runRepository = remember(context) {
        com.ai.assistance.operit.data.repository.SubagentRunRepository.getInstance(context)
    }
    val runs by remember(chatId, runRepository) {
        runRepository.observeByParentChatId(chatId)
    }.collectAsState(initial = emptyList())
    val runSnapshot = remember(chatId, runs) { TranscriptRunSnapshot(chatId, runs) }
    val toolModeValue by remember(displayPreferences) {
        displayPreferences.toolCollapseMode.map { it as ToolCollapseMode? }
    }.collectAsState(initial = TranscriptDisplayCache.toolMode)
    val showThinkingValue by remember(userPreferences) {
        userPreferences.showThinkingProcess.map { it as Boolean? }
    }.collectAsState(initial = TranscriptDisplayCache.showThinking)
    val collapseValue by remember(displayPreferences) {
        displayPreferences.collapseCompletedProcess.map { it as Boolean? }
    }.collectAsState(initial = TranscriptDisplayCache.collapse)
    val bubbleSettings = rememberBubbleAiRenderSettings()
    val toolLabelTextMeasurer = rememberTextMeasurer(cacheSize = 256)
    if (toolModeValue == null || showThinkingValue == null || collapseValue == null || bubbleSettings == null) {
        Box(modifier) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        return
    }
    TranscriptDisplayCache.toolMode = toolModeValue
    TranscriptDisplayCache.showThinking = showThinkingValue
    TranscriptDisplayCache.collapse = collapseValue
    val toolMode = toolModeValue!!
    // Orbit's developer view shows all of Orbit's thinking, whatever the display setting.
    val showThinking = showThinkingValue!! || LocalShowHiddenChatRows.current
    val collapse = collapseValue!!
    val grouper = remember(toolMode, showThinking) { ThinkToolsXmlNodeGrouper(showThinking, toolCollapseMode = toolMode) }
    // Prepared replies are kept per way of grouping. Orbit's developer view groups with thinking
    // shown, so going to Orbit's look and back finds its long replies already split into the same
    // rows instead of preparing them again from nothing. A grouping not in use keeps only the
    // replies that were around the screen when it was left.
    val documentsByGrouping = remember(chatId) {
        HashMap<Pair<ToolCollapseMode, Boolean>, androidx.compose.runtime.snapshots.SnapshotStateMap<Long, TranscriptDocumentEntry>>()
    }
    val nearbyByGrouping = remember(chatId) { HashMap<Pair<ToolCollapseMode, Boolean>, Set<Long>>() }
    val grouping = toolMode to showThinking
    val documents = remember(grouping, documentsByGrouping) {
        documentsByGrouping.getOrPut(grouping) { mutableStateMapOf() }
    }
    LaunchedEffect(chatId, grouping) {
        documentsByGrouping.forEach { (other, cached) ->
            if (other == grouping) return@forEach
            val nearby = nearbyByGrouping[other].orEmpty()
            cached.keys.toList().filterNot { it in nearby }.forEach { cached.remove(it) }
        }
    }
    // Skipped rows are dropped before cards are formed, and each remaining row is a card of its own,
    // so a hidden step never squares off or merges the bubbles around it.
    val baseRows = transcriptRows(messages, process).let { all ->
        if (skipMessage == null) all
        // Rows without a message (a page's "more" link) are part of the hidden steps too.
        else all.filter { row -> messages.getOrNull(row.messageIndex)?.let { !skipMessage(it) } == true }
            .map { it.copy(cardKey = it.key) }
    }
    val expandedIds = TranscriptExpansionState.expandedIds(chatId)
    val expandedProcesses = process.groups.values.filter { process.isExpanded(it.key) }.mapTo(hashSetOf()) { it.key }
    val currentFollowingChange by rememberUpdatedState(onFollowingChange)
    val toggleExpansion = remember(chatId) {
        { id: String ->
            currentFollowingChange?.invoke(false)
            TranscriptExpansionState.toggle(chatId, id)
        }
    }
    val rows = remember(baseRows, messages, documents.toMap(), splitMarkdown, collapse, expandedIds, expandedProcesses) {
        transcriptMarkdownRows(baseRows, messages, documents, splitMarkdown, collapse,
            { it in expandedIds },
            toggleExpansion,
            { it in expandedProcesses })
    }
    val bookmark = remember(chatId) { TranscriptBookmarks.get(chatId) }
    var restoring by remember(chatId) { mutableStateOf(bookmark != null) }
    var restoreUnavailable by remember(chatId) { mutableStateOf(false) }
    val restoreTimestamp = bookmark?.timestamp
    val listState = remember(chatId) {
        LazyListState(
            cacheWindow = LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 1f),
            firstVisibleItemIndex = rows.indexOfFirst { it.key == bookmark?.key }.coerceAtLeast(0),
            // The saved markdown slice may not exist until preparation finishes.
            firstVisibleItemScrollOffset = 0,
        )
    }
    val scope = rememberCoroutineScope()
    // Orbit's developer view draws this chat with more rows than Orbit's own look. Each look keeps
    // the row it was on and whether it was following the newest message, so going to the other look
    // and back lands where it was. Read without observing, so scrolling never recomposes this.
    val look = LocalOrbitChatLook.current
    val lookAnchors = remember(chatId) { HashMap<Boolean, Triple<Any?, Int, Boolean>>() }
    val shownLook = remember(chatId) { booleanArrayOf(look) }
    val rowsForLook by rememberUpdatedState(rows)
    val followingForLook by rememberUpdatedState(following)
    if (shownLook[0] != look) {
        androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
            val first = listState.firstVisibleItemIndex
            val row = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == first }
            lookAnchors[shownLook[0]] = Triple(row?.key, listState.firstVisibleItemScrollOffset, followingForLook)
        }
        shownLook[0] = look
    }
    LaunchedEffect(chatId, look) {
        val anchor = lookAnchors[look] ?: return@LaunchedEffect
        val (key, offset, wasFollowing) = anchor
        try {
            // Following again goes to the newest message by itself; otherwise stop following first,
            // so it does not pull the list away from the row being restored.
            onFollowingChange?.invoke(wasFollowing)
            if (wasFollowing || key == null) return@LaunchedEffect
            // The row may still be preparing; wait a little for it, giving up if the reader scrolls.
            // First the old follow has to be gone, or it would pull the list back to the newest message.
            val index = withTimeoutOrNull(1_500) {
                snapshotFlow { followingForLook }.first { !it }
                snapshotFlow { rowsForLook.indexOfFirst { it.key == key } }.first { it >= 0 }
            } ?: return@LaunchedEffect
            if (!listState.isScrollInProgress) listState.scrollToItem(index, offset)
        } finally {
            // A newer anchor saved while this waited is left for its own restore.
            if (lookAnchors[look] === anchor) lookAnchors.remove(look)
        }
    }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var direction by remember(chatId) { mutableStateOf(0) }
    var lastPageRequest by remember(chatId) { mutableStateOf<Pair<Int, Long>?>(null) }
    var pagesWithoutMovement by remember(chatId) { mutableStateOf(0) }
    // Each new drag may ask for a page again, even when the list already rests at the edge and
    // nothing else the page request watches has changed.
    var pageRetry by remember(chatId) { mutableStateOf(0) }
    LaunchedEffect(dragging) {
        if (dragging) {
            lastPageRequest = null
            pagesWithoutMovement = 0
            pageRetry++
        }
    }
    var pendingJump by remember(chatId) { mutableStateOf<Long?>(null) }
    val currentRows by key(chatId) { rememberUpdatedState(rows) }
    val currentBaseRows by rememberUpdatedState(baseRows)
    val currentMessages by key(chatId) { rememberUpdatedState(messages) }
    val currentReveal by rememberUpdatedState(reveal)
    val prepareCandidates by remember(listState) { derivedStateOf {
        val indices = listState.layoutInfo.visibleItemsInfo.mapNotNull {
            currentRows.getOrNull(it.index)?.messageIndex?.takeIf { index -> index >= 0 }
        }
        val positions = currentBaseRows.indices.filter { currentBaseRows[it].messageIndex in indices }
        val first = (positions.minOrNull() ?: 0).minus(3).coerceAtLeast(0)
        val last = (positions.maxOrNull() ?: 0).plus(3).coerceAtMost(currentBaseRows.lastIndex)
        val nearby = if (last < first) emptyList() else currentBaseRows.subList(first, last + 1)
            .filter { it.section != ResponseMessageSection.HEADER }
            .mapNotNull { currentMessages.getOrNull(it.messageIndex) }
        val restoreMessage = if (restoring) currentMessages.firstOrNull { it.timestamp == restoreTimestamp } else null
        (listOfNotNull(restoreMessage) + nearby)
            .filter { canSplitTranscriptMessage(it) }.distinctBy { it.timestamp }
            .map { it.timestamp to it.content }
    } }
    LaunchedEffect(chatId, prepareCandidates, grouper, splitMarkdown) {
        if (!splitMarkdown) return@LaunchedEffect
        // Keep block identities for the loaded window. Evicting a document solely because
        // it left the screen would turn its rows back into one placeholder during a fling.
        val keep = currentMessages.mapTo(hashSetOf()) { it.timestamp }
        documents.keys.toList().filterNot { it in keep }.forEach { documents.remove(it) }
        // Noted before preparing: leaving the grouping halfway through still keeps these.
        nearbyByGrouping[grouping] = prepareCandidates.mapTo(hashSetOf()) { it.first }
        prepareCandidates.forEach { (timestamp, content) ->
            if (documents[timestamp]?.content != content) {
                val document = prepareTranscriptMarkdown(content, grouper)
                documents[timestamp] = TranscriptDocumentEntry(content, document)
            }
        }
    }
    // Loading the missing anchor must survive row changes from asynchronous markdown preparation.
    // A rows-keyed effect can cancel the IO after marking it requested and never retry it.
    LaunchedEffect(chatId, bookmark, restoring) {
        if (restoring && restoreTimestamp != null &&
            currentMessages.none { it.timestamp == restoreTimestamp }) {
            restoreUnavailable = currentReveal?.invoke(restoreTimestamp) != true
        }
    }
    LaunchedEffect(chatId, rows.map { it.key to it.preparingMarkdown }, restoring, restoreUnavailable) {
        if (!restoring || bookmark == null) return@LaunchedEffect
        onFollowingChange?.invoke(false)
        val index = rows.indexOfFirst { it.key == bookmark.key }
        val messageIndex = messages.indexOfFirst { it.timestamp == restoreTimestamp }
        if (messageIndex >= 0 && rows.any { it.messageIndex == messageIndex && it.preparingMarkdown }) {
            return@LaunchedEffect
        }
        if (index >= 0) {
            listState.scrollToItem(index, bookmark.offset)
            restoring = false
        } else if (messageIndex >= 0) {
            val fallback = rows.indexOfFirst { it.messageIndex == messageIndex }
            if (fallback >= 0) {
                listState.scrollToItem(fallback)
                restoring = false
            }
        } else if (restoreTimestamp == null || restoreUnavailable) {
            restoring = false
        }
    }
    // Following stops only once the reader has really left the newest message, not on a nudge at it.
    val followTolerancePx = with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.toPx() }
    fun distanceFromEnd(): Float {
        if (!listState.canScrollForward) return 0f
        val layout = listState.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull() ?: return 0f
        // The footer after the last row is often empty, so it leaves the screen on the first pixels
        // back; measuring from the last row then is close enough.
        if (last.index < layout.totalItemsCount - 2) return Float.MAX_VALUE
        return (last.offset + last.size - (layout.viewportEndOffset - layout.afterContentPadding))
            .coerceAtLeast(0).toFloat()
    }
    val connection = remember(chatId, listState, followTolerancePx) {
        object : NestedScrollConnection {
            private var before = 0 to 0
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                before = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val after = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                // The list really moved (a drag or its fling), so loaded pages were not wasted.
                if (consumed.y != 0f) pagesWithoutMovement = 0
                // A drag or its fling that moved the list back past the tolerance ends following.
                if (consumed.y > 0f && followingForLook && distanceFromEnd() > followTolerancePx) {
                    currentFollowingChange?.invoke(false)
                }
                if (source == NestedScrollSource.UserInput &&
                    (before != after || available.y != 0f)
                ) {
                    restoring = false
                    pagesWithoutMovement = 0
                    direction = if (after.first < before.first ||
                        (after.first == before.first && after.second < before.second) || available.y > 0
                    ) -1 else 1
                    pendingJump = null
                }
                return Offset.Zero
            }
        }
    }
    fun currentBookmark(): TranscriptBookmark? {
        // visibleItemsInfo also contains rows in contentPadding; their key must not be paired
        // with another row's firstVisibleItemScrollOffset.
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.index == listState.firstVisibleItemIndex
        } ?: return null
        val row = currentRows.firstOrNull { it.key == item.key.toString() } ?: return null
        val timestamp = currentMessages.getOrNull(row.messageIndex)?.timestamp
            ?: row.group?.let { currentMessages.getOrNull(it.finalIndex)?.timestamp }
        return TranscriptBookmark(row.key,listState.firstVisibleItemScrollOffset,timestamp)
    }
    LaunchedEffect(chatId, listState) {
        snapshotFlow { if (restoring) null else currentBookmark() }.collect { value ->
            if (value != null) TranscriptBookmarks.put(chatId,value)
        }
    }
    DisposableEffect(chatId,listState) {
        onDispose {
            // Flush the last frame when navigation disposes the collector before it can run.
            if (!restoring) currentBookmark()?.let { TranscriptBookmarks.put(chatId,it) }
        }
    }
    val atEnd by remember(listState) { derivedStateOf { !listState.canScrollForward } }
    val nearStart by remember(listState) { derivedStateOf {
        val layout = listState.layoutInfo
        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
        layout.visibleItemsInfo.firstOrNull()?.let {
            it.index <= 2 && it.offset >= layout.viewportStartOffset - viewport
        } == true
    } }
    val nearEnd by remember(listState) { derivedStateOf {
        val layout = listState.layoutInfo
        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
        layout.visibleItemsInfo.lastOrNull()?.let {
            it.index >= layout.totalItemsCount - 4 &&
                it.offset + it.size <= layout.viewportEndOffset + viewport
        } == true
    } }
    val processPrefetchKey by remember(listState) { derivedStateOf {
        val layout = listState.layoutInfo
        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
        layout.visibleItemsInfo.firstNotNullOfOrNull { item ->
            val row = currentRows.getOrNull(item.index)
            val next = currentRows.getOrNull(item.index + 1)
            when {
                row?.loadMore == true -> row.group?.key
                next?.loadMore == true && item.offset + item.size <= layout.viewportEndOffset + viewport ->
                    next.group?.key
                else -> null
            }
        }
    } }
    LaunchedEffect(processPrefetchKey, messages.size) {
        processPrefetchKey?.let { process.loadMore(it) }
    }
    LaunchedEffect(direction, nearStart, nearEnd, atEnd, loadingPage, hasOlder, hasNewer, pageRetry,
        restoring, messages.firstOrNull()?.timestamp, messages.lastOrNull()?.timestamp) {
        if (loadingPage || restoring) return@LaunchedEffect
        val cursor = if (direction < 0) messages.firstOrNull()?.timestamp else messages.lastOrNull()?.timestamp
        val request = cursor?.let { direction to it }
        fun requestPage(load: (() -> Unit)?) {
            if (request != null && lastPageRequest != request && pagesWithoutMovement < 3) {
                lastPageRequest = request
                pagesWithoutMovement++
                load?.invoke()
            }
        }
        when {
            direction < 0 && nearStart && hasOlder -> requestPage(onOlder)
            direction > 0 && nearEnd && hasNewer -> requestPage(onNewer)
            direction > 0 && atEnd && !hasNewer -> onFollowingChange?.invoke(true)
        }
    }
    LaunchedEffect(following, hasNewer, loadingPage, restoring) {
        if (following && hasNewer && !loadingPage && !restoring) onLatest?.invoke()
    }
    /**
     * The jump-to-latest button. A fling still running would keep following waiting (and could
     * turn it off again), so it is stopped first; then following takes the list to the end.
     */
    fun jumpToLatest() {
        scope.launch {
            listState.stopScroll(androidx.compose.foundation.MutatePriority.PreventUserInput)
            direction = 0
            restoring = false
            pendingJump = null
            onFollowingChange?.invoke(true)
            onLatest?.invoke()
        }
    }
    LaunchedEffect(chatId, following, hasNewer, loadingPage, restoring) {
        if (following && !hasNewer && !loadingPage && !restoring) {
            // Never take the list from the reader: a drag or its fling finishes first. Then the list
            // goes to the end only when something changed (new rows, a row on screen growing, such as
            // a reply or a loaded picture, a resized view): letting go just short of the end leaves it
            // there. Rows that only came into view are measured, not taken as growth.
            var lastShape: List<Int>? = null
            val knownSizes = HashMap<Any, Int>()
            snapshotFlow {
                val layout = listState.layoutInfo
                val count = layout.totalItemsCount
                val shape = listOf(count, layout.viewportSize.width, layout.viewportSize.height,
                    layout.afterContentPadding)
                val sizes = layout.visibleItemsInfo.associate { it.key to it.size }
                Triple(shape, sizes, listState.isScrollInProgress)
            }.distinctUntilChanged().collect { (shape, sizes, scrolling) ->
                if (scrolling || currentRows.isEmpty()) return@collect
                val changed = shape != lastShape || sizes.any { (key, size) ->
                    knownSizes[key].let { it != null && it != size }
                }
                if (shape != lastShape) knownSizes.clear()
                lastShape = shape
                // Only rows on screen are kept, so the record stays as small as the screen.
                knownSizes.keys.retainAll(sizes.keys)
                knownSizes.putAll(sizes)
                if (!changed || !listState.canScrollForward) return@collect
                try {
                    listState.scrollToItem(currentRows.size)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // A scroll that won the list cancels only this jump; keep following afterwards.
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                }
            }
        }
    }
    LaunchedEffect(pendingJump, rows.map { it.key }) {
        val timestamp = pendingJump ?: return@LaunchedEffect
        val shownTimestamp = if (look) com.ai.assistance.operit.features.orbit.OrbitChatFork
            .displayTimestamp(messages, timestamp) else timestamp
        val index = rows.indexOfFirst { row ->
            row.section != ResponseMessageSection.HEADER &&
                messages.getOrNull(row.messageIndex)?.timestamp == shownTimestamp
        }
        if (index >= 0) {
            listState.scrollToItem(index)
            pendingJump = null
        } else {
            val messageIndex = messages.indexOfFirst { it.timestamp == timestamp }
            process.groups[messageIndex]?.let { process.expand(it.key) }
        }
    }
    // A reader can ask for one item of a conversation that was judged in another chat. The request is
    // filed before the switch and read here, because this is where the messages and the scroll state
    // are; the jump that lands on a message already exists for the locator, so the request only names
    // the exchange. It names the review's own message, and the landing spot is the answer to it: the
    // request is what the reader already knows, the verdict is what they came for.
    val jumpRequest = TranscriptJumpHost.pendingFor(chatId)
    var jumpSearched by remember(chatId) { mutableStateOf<Long?>(null) }
    var jumpLocatedAt by remember(chatId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(jumpRequest?.token, rows.map { it.key }) {
        val request = jumpRequest ?: return@LaunchedEffect
        // A search result names its message: load the page holding it if needed, then land there.
        request.timestamp?.let { timestamp ->
            if (messages.none { it.timestamp == timestamp } && reveal?.invoke(timestamp) != true) {
                TranscriptJumpHost.consume(request.token)
                return@LaunchedEffect
            }
            TranscriptJumpHost.consume(request.token)
            restoring = false
            onFollowingChange?.invoke(false)
            pendingJump = timestamp
            return@LaunchedEffect
        }
        val requestIndex =
            messages.indexOfFirst { message -> message.content.contains(request.marker) }
        if (requestIndex >= 0) {
            TranscriptJumpHost.consume(request.token)
            // The reader asked for one exchange of this chat, so where they last left it is not where
            // they belong: the bookmark restore would take the scroll back, so it stops here.
            restoring = false
            onFollowingChange?.invoke(false)
            // A review still being written has only the request so far, and then that is where the
            // reader belongs.
            val target = messages.getOrNull(requestIndex + 1) ?: messages[requestIndex]
            pendingJump = target.timestamp
            return@LaunchedEffect
        }
        // A reviewer conversation holds one exchange per review, so the review the reader asked for can
        // sit in an earlier page than the one this chat opened on. The locator searches the whole
        // conversation rather than the loaded window, and revealing a message loads the page that holds
        // it, which is how the exchange is reached. The search runs once for this chat's composition.
        val searched = jumpSearched == request.token
        val located =
            if (searched) {
                jumpLocatedAt
            } else {
                loadLocator?.invoke(chatId, request.marker)?.firstOrNull()?.timestamp
            }
        // Recorded only once the search returned, and with what it found: an effect cancelled while it
        // was reading would otherwise never look again and the click would do nothing at all, while an
        // effect cancelled after it looked can go straight on to what it found.
        if (!searched) {
            jumpSearched = request.token
            jumpLocatedAt = located
        }
        if (located == null) {
            // Nothing carries the marker: the review is gone, or this is not the chat it ran in.
            // Dropping the request is what keeps it from pulling the list about later.
            TranscriptJumpHost.consume(request.token)
            return@LaunchedEffect
        }
        if (messages.any { it.timestamp == located }) {
            TranscriptJumpHost.consume(request.token)
            restoring = false
            onFollowingChange?.invoke(false)
            pendingJump = located
            return@LaunchedEffect
        }
        // The page this loads carries the answer as well, and the effect runs again once the page is
        // loaded: that is when the marker is found and the landing spot becomes the answer. Until that
        // page is confirmed to exist, the reader's own place in this chat is left where it was.
        if (reveal?.invoke(located) != true) {
            TranscriptJumpHost.consume(request.token)
            return@LaunchedEffect
        }
        restoring = false
        onFollowingChange?.invoke(false)
        pendingJump = located
    }
    var showLocator by remember(chatId) { mutableStateOf(false) }
    var locatorAnchor by remember(chatId) { mutableStateOf(0L) }
    var locatorEntries by remember(chatId) { mutableStateOf<List<ChatMessageLocatorPreview>>(emptyList()) }
    var locatorLoading by remember(chatId) { mutableStateOf(false) }
    var locatorFailed by remember(chatId) { mutableStateOf(false) }
    LaunchedEffect(showLocator, chatId) {
        if (!showLocator) return@LaunchedEffect
        locatorLoading = true
        locatorFailed = false
        try { locatorEntries = loadLocator?.invoke(chatId, "").orEmpty() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { locatorFailed = true }
        finally { locatorLoading = false }
    }
    com.ai.assistance.operit.ui.theme.ProvideAiMarkdownTextLayoutSettings {
    // Orbit's chat reads like a messenger: a time line above a message that comes after a pause.
    val timeRows = if (look) remember(rows, messages) {
        orbitChatTimeRows(rows.map { row -> row.key to messages.getOrNull(row.messageIndex)?.timestamp })
    } else emptyMap()
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(connection),
            contentPadding = PaddingValues(
                start = horizontalPadding, end = horizontalPadding,
                top = topPadding, bottom = bottomPadding + 16.dp,
            ),
        ) {
            items(rows, key = { it.key }, contentType = { it.section }) { row ->
                // Keep each chronological block together for accessibility traversal,
                // instead of geometrically sorting all of its descendants with other rows.
                Column(Modifier.semantics { isTraversalGroup = true }) {
                    timeRows[row.key]?.let { OrbitChatTimeLine(it) }
                    // Keep the paging sentinel key without cutting a transparent hole in the reply.
                    CompositionLocalProvider(
                        com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiOwner provides
                            messages.getOrNull(row.messageIndex)?.takeIf { it.sender == "ai" && !it.isVariantPreview }?.let {
                                com.ai.assistance.operit.ui.common.dynamicui.DynamicUiOwner(
                                    chatId, it.timestamp, it.selectedVariantIndex, it.content,
                                )
                            },
                        LocalResponseMessageSection provides row.section,
                        LocalResponseProcessExpanded provides row.group?.let { process.isExpanded(it.key) },
                        LocalTranscriptMarkdownSlice provides row.markdownSlice,
                        LocalTranscriptRuns provides runSnapshot,
                        LocalTranscriptCardEnds provides
                            TranscriptCardEnds(row.cardFirst, row.cardLast),
                        LocalBubbleAiRenderSettings provides bubbleSettings,
                        LocalTranscriptTextMeasurer provides toolLabelTextMeasurer,
                    ) {
                        if (row.preparingMarkdown) {
                            Box(Modifier.fillMaxWidth().height(480.dp)) {
                                CircularProgressIndicator(Modifier.align(Alignment.Center).size(20.dp))
                            }
                        } else if (row.messageIndex >= 0) renderMessage(row.messageIndex)
                    }
                    if (row.section == ResponseMessageSection.HEADER && row.group != null) {
                        ResponseActivityHeader(row.group.durationMs, process.isExpanded(row.group.key), textColor) {
                            onFollowingChange?.invoke(false)
                            process.toggle(row.group.key)
                        }
                    }
                    // Only the end of a reply leaves space outside its background.
                    if (row.cardLast && row.markdownSlice?.last != false) {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            item("footer") { footer() }
        }
        Column(Modifier.align(Alignment.CenterEnd)) {
            // Orbit's chat reads like messages; jumping by raw records would show the hidden steps.
            if (skipMessage == null) {
            TranscriptPositionIndicator(
                progress = {
                    val layout = listState.layoutInfo
                    if (!listState.canScrollForward) 1f
                    else if (!listState.canScrollBackward) 0f
                    else {
                        val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                        val item = layout.visibleItemsInfo.asSequence().filter {
                            rows.getOrNull(it.index)?.messageIndex?.let { index ->
                                index in messages.indices
                            } == true
                        }.minByOrNull {
                            kotlin.math.abs(it.offset + it.size / 2 - center)
                        }
                        val messageIndex = item?.let { rows.getOrNull(it.index)?.messageIndex } ?: 0
                        messageIndex.coerceAtLeast(0).toFloat() / messages.lastIndex.coerceAtLeast(1)
                    }
                },
                onClick = {
                    val layout = listState.layoutInfo
                    val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                    locatorAnchor = layout.visibleItemsInfo
                        .filter { item ->
                            rows.getOrNull(item.index)?.messageIndex?.let { it in messages.indices } == true
                        }
                        .minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - center) }
                        ?.let { item -> messages.getOrNull(rows[item.index].messageIndex)?.timestamp }
                        ?: messages.firstOrNull()?.timestamp ?: 0L
                    showLocator = true
                },
            )
            }
            if (!look && (!following || hasNewer)) {
                IconButton(onClick = ::jumpToLatest) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.history_scroll_to_bottom))
                }
            }
        }
        // Orbit: a round button just above the message box, in the middle.
        if (look) androidx.compose.animation.AnimatedVisibility(
            visible = !following || hasNewer,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding + 12.dp),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)) +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(150), initialScale = 0.8f),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) +
                androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(120), targetScale = 0.8f),
        ) {
            androidx.compose.material3.Surface(
                onClick = ::jumpToLatest,
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 3.dp,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.ArrowDownward, stringResource(R.string.history_scroll_to_bottom),
                        tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        if (loadingPage) CircularProgressIndicator(Modifier.align(Alignment.TopCenter).size(20.dp))
    }
    }
    if (showLocator) {
        ChatMessageLocatorDialog(
            locatorEntries, locatorAnchor, locatorLoading, locatorFailed,
            chatId, loadLocator, { showLocator = false }, onFavorite,
        ) { timestamp ->
            showLocator = false
            onFollowingChange?.invoke(false)
            pendingJump = timestamp
            if (messages.none { it.timestamp == timestamp }) scope.launch {
                if (reveal?.invoke(timestamp) != true) pendingJump = null
            }
        }
    }
}

/**
 * Display settings last read, so a transcript opened again draws at once with them instead of
 * showing a spinner for the frame or two the live values take; they replace these as they arrive.
 */
private object TranscriptDisplayCache {
    @Volatile var toolMode: ToolCollapseMode? = null
    @Volatile var showThinking: Boolean? = null
    @Volatile var collapse: Boolean? = null
}
