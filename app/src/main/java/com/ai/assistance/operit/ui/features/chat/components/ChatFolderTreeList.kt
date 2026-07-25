package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatFolderEntity
import com.ai.assistance.operit.data.model.ChatFolderScope
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ChatPlacementEntity
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private sealed interface FolderTreeItem {
    val key: String
    val depth: Int

    data class Folder(
        val value: ChatFolderEntity,
        override val depth: Int,
    ) : FolderTreeItem {
        override val key = "folder:${value.id}"
    }

    data class Chat(
        val history: ChatHistory,
        val placement: ChatPlacementEntity,
        override val depth: Int,
    ) : FolderTreeItem {
        override val key = "chat:${history.id}"
    }
}

private sealed interface PendingTreeMove {
    data class Folder(
        val id: String,
        val parentId: String?,
        val index: Int,
    ) : PendingTreeMove

    data class Chat(
        val id: String,
        val parentId: String?,
        val index: Int,
    ) : PendingTreeMove
}

private enum class DropFeedbackKind {
    REORDER,
    INTO_FOLDER,
    EXPAND_FIRST,
    INVALID,
    MOVE_TO_PARENT,
    MOVE_TO_ROOT,
}

private data class DropFeedback(
    val kind: DropFeedbackKind,
    val targetKey: String,
    val move: PendingTreeMove? = null,
    val lineYInRoot: Float? = null,
    val targetFolderId: String? = null,
)

private data class DragSession(
    val source: FolderTreeItem,
    val grabOffsetY: Float,
    val sourceBoundsInRoot: Rect,
)

@Composable
private fun TreeDragHandle(
    sourceKey: String,
    contentDescription: String,
    onBoundsChanged: (Rect?) -> Unit,
) {
    val currentOnBoundsChanged by rememberUpdatedState(onBoundsChanged)

    DisposableEffect(sourceKey) {
        onDispose {
            currentOnBoundsChanged(null)
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .onGloballyPositioned { coordinates ->
                currentOnBoundsChanged(coordinates.boundsInRoot())
            }
            .semantics {
                this.contentDescription = contentDescription
            },
    ) {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = null,
        )
    }
}

private fun folderSubtreeIds(
    folders: List<ChatFolderEntity>,
    rootId: String,
): Set<String> {
    val childrenByParent = folders.groupBy { it.parentFolderId }
    return buildSet {
        val pending = mutableListOf(rootId)
        while (pending.isNotEmpty()) {
            val id = pending.removeAt(pending.lastIndex)
            if (!add(id)) continue
            childrenByParent[id].orEmpty().forEach { pending += it.id }
        }
    }
}

@Composable
internal fun ChatFolderTreeList(
    manager: ChatHistoryManager,
    scope: ChatFolderScope,
    histories: List<ChatHistory>,
    searchQuery: String,
    matchedChatIdsByContent: Set<String>,
    currentId: String?,
    activeStreamingChatIds: Set<String>,
    lazyListState: LazyListState,
    onSelectChat: (String) -> Unit,
    onChatLongPress: (ChatHistory) -> Unit,
    onFolderLongPress: (ChatFolderEntity) -> Unit,
) {
    val folders by remember(manager, scope) { manager.observeFolders(scope) }
        .collectAsState(initial = emptyList())
    val placements by remember(manager, scope) { manager.observePlacements(scope) }
        .collectAsState(initial = emptyList())
    var collapsedFolderIds by remember(scope) { mutableStateOf(emptySet<String>()) }
    var dragSession by remember(scope) { mutableStateOf<DragSession?>(null) }
    var dragPointerInRoot by remember(scope) { mutableStateOf(Offset.Zero) }
    var dropFeedback by remember(scope) { mutableStateOf<DropFeedback?>(null) }
    var pendingCommitKeys by remember(scope) { mutableStateOf(emptySet<String>()) }
    var commitInProgress by remember(scope) { mutableStateOf(false) }
    var containerPositionInRoot by remember(scope) { mutableStateOf(Offset.Zero) }
    var containerHeightPx by remember(scope) { mutableFloatStateOf(0f) }
    var parentDropBoundsInRoot by remember(scope) { mutableStateOf<Rect?>(null) }
    var rootDropBoundsInRoot by remember(scope) { mutableStateOf<Rect?>(null) }
    val rowBoundsInRoot = remember(scope) { mutableMapOf<String, Rect>() }
    val handleBoundsInRoot = remember(scope) { mutableMapOf<String, Rect>() }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val moveFailedMessage = stringResource(R.string.history_move_failed)
    val dragEnabled = searchQuery.isBlank() && !commitInProgress
    val normalizedQuery = searchQuery.trim()
    val matchingHistoryIds = remember(histories, normalizedQuery, matchedChatIdsByContent) {
        if (normalizedQuery.isBlank()) {
            histories.mapTo(mutableSetOf()) { it.id }
        } else {
            histories
                .filter { history ->
                    history.title.contains(normalizedQuery, ignoreCase = true) ||
                        history.id in matchedChatIdsByContent
                }
                .mapTo(mutableSetOf()) { it.id }
        }
    }
    val foldersById = remember(folders) { folders.associateBy { it.id } }
    val treeItems = remember(
        folders,
        placements,
        histories,
        collapsedFolderIds,
        matchingHistoryIds,
    ) {
        val historiesById = histories.associateBy { it.id }
        val foldersByParent = folders.groupBy { it.parentFolderId }
        val placementsByFolder = placements.groupBy { it.folderId }
        val result = mutableListOf<FolderTreeItem>()
        val visitedFolderIds = mutableSetOf<String>()

        fun appendFolder(folder: ChatFolderEntity, depth: Int) {
            if (!visitedFolderIds.add(folder.id)) return

            result += FolderTreeItem.Folder(folder, depth)
            if (folder.id in collapsedFolderIds) return

            foldersByParent[folder.id]
                .orEmpty()
                .sortedWith(
                    compareByDescending<ChatFolderEntity> { it.pinned }
                        .thenBy { it.displayOrder },
                )
                .forEach { child -> appendFolder(child, depth + 1) }

            placementsByFolder[folder.id]
                .orEmpty()
                .sortedBy { it.displayOrder }
                .forEach { placement ->
                    historiesById[placement.chatId]
                        ?.takeIf { history -> history.id in matchingHistoryIds }
                        ?.let { history ->
                            result += FolderTreeItem.Chat(history, placement, depth + 1)
                        }
                }
        }

        foldersByParent[null]
            .orEmpty()
            .sortedWith(
                compareByDescending<ChatFolderEntity> { it.pinned }
                    .thenBy { it.displayOrder },
            )
            .forEach { folder -> appendFolder(folder, 0) }

        placementsByFolder[null]
            .orEmpty()
            .sortedBy { it.displayOrder }
            .forEach { placement ->
                historiesById[placement.chatId]
                    ?.takeIf { history -> history.id in matchingHistoryIds }
                    ?.let { history ->
                        result += FolderTreeItem.Chat(history, placement, 0)
                    }
            }

        result
    }
    val treeItemsByKey = remember(treeItems) { treeItems.associateBy { it.key } }

    val draggedFolderSubtreeIds = remember(dragSession?.source, folders) {
        val sourceFolder = (dragSession?.source as? FolderTreeItem.Folder)?.value
        sourceFolder?.let { folderSubtreeIds(folders, it.id) }.orEmpty()
    }

    fun sortedFolderSiblings(parentId: String?, excludingId: String? = null) =
        folders
            .filter { it.parentFolderId == parentId && it.id != excludingId }
            .sortedWith(
                compareByDescending<ChatFolderEntity> { it.pinned }
                    .thenBy { it.displayOrder },
            )

    fun sortedChatSiblings(parentId: String?, excludingId: String? = null) =
        placements
            .filter { it.folderId == parentId && it.chatId != excludingId }
            .sortedBy { it.displayOrder }

    fun folderAppendIndex(
        parentId: String?,
        source: ChatFolderEntity,
    ): Int {
        val siblings = sortedFolderSiblings(parentId, source.id)
        return if (source.pinned) {
            siblings.count { it.pinned }
        } else {
            siblings.size
        }
    }

    fun hasFolderNameConflict(source: ChatFolderEntity, parentId: String?): Boolean =
        folders.any { folder ->
            folder.id != source.id &&
                folder.parentFolderId == parentId &&
                folder.name == source.name
        }

    fun folderSubtreeBottomInRoot(target: FolderTreeItem.Folder): Float {
        val targetIndex = treeItems.indexOfFirst { it.key == target.key }
        var bottom = rowBoundsInRoot[target.key]?.bottom ?: return 0f
        if (targetIndex < 0) return bottom
        for (index in (targetIndex + 1) until treeItems.size) {
            val candidate = treeItems[index]
            if (candidate.depth <= target.depth) break
            rowBoundsInRoot[candidate.key]?.let { bounds ->
                bottom = maxOf(bottom, bounds.bottom)
            }
        }
        return bottom
    }

    fun moveToContainer(
        source: FolderTreeItem,
        targetParentId: String?,
        feedbackKind: DropFeedbackKind,
        targetKey: String,
    ): DropFeedback? =
        when (source) {
            is FolderTreeItem.Chat -> {
                val index = sortedChatSiblings(targetParentId, source.history.id).size
                DropFeedback(
                    kind = feedbackKind,
                    targetKey = targetKey,
                    move = PendingTreeMove.Chat(source.history.id, targetParentId, index),
                )
            }

            is FolderTreeItem.Folder -> {
                if (hasFolderNameConflict(source.value, targetParentId)) {
                    DropFeedback(DropFeedbackKind.INVALID, targetKey)
                } else {
                    DropFeedback(
                        kind = feedbackKind,
                        targetKey = targetKey,
                        move = PendingTreeMove.Folder(
                            source.value.id,
                            targetParentId,
                            folderAppendIndex(targetParentId, source.value),
                        ),
                    )
                }
            }
        }

    fun setDropFeedback(nextFeedback: DropFeedback?) {
        val oldSignature = dropFeedback?.let { Triple(it.kind, it.targetKey, it.move) }
        val newSignature = nextFeedback?.let { Triple(it.kind, it.targetKey, it.move) }
        if (newSignature != null && newSignature != oldSignature) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        dropFeedback = nextFeedback
    }

    fun updateDropTarget(pointerInRoot: Offset) {
        val session = dragSession ?: return
        val source = session.source

        val parentBounds = parentDropBoundsInRoot
        if (parentBounds != null && parentBounds.contains(pointerInRoot)) {
            val sourceContainerId = when (source) {
                is FolderTreeItem.Chat -> source.placement.folderId
                is FolderTreeItem.Folder -> source.value.parentFolderId
            }
            val oneLevelUpParentId = sourceContainerId?.let { foldersById[it]?.parentFolderId }
            setDropFeedback(
                moveToContainer(
                    source = source,
                    targetParentId = oneLevelUpParentId,
                    feedbackKind = DropFeedbackKind.MOVE_TO_PARENT,
                    targetKey = "move-to-parent",
                )
            )
            return
        }

        val rootBounds = rootDropBoundsInRoot
        if (rootBounds != null && rootBounds.contains(pointerInRoot)) {
            setDropFeedback(
                moveToContainer(
                    source = source,
                    targetParentId = null,
                    feedbackKind = DropFeedbackKind.MOVE_TO_ROOT,
                    targetKey = "move-to-root",
                )
            )
            return
        }

        val targetKey =
            rowBoundsInRoot.entries
                .firstOrNull { (_, bounds) ->
                    pointerInRoot.y in bounds.top..bounds.bottom
                }
                ?.key
        val target = targetKey?.let(treeItemsByKey::get)
        val nextFeedback = when (target) {
            null -> null
            is FolderTreeItem.Chat -> {
                if (source !is FolderTreeItem.Chat || source.key == target.key) {
                    null
                } else {
                    val bounds = rowBoundsInRoot[target.key] ?: return
                    val siblings =
                        sortedChatSiblings(target.placement.folderId, source.history.id)
                    val targetIndex = siblings.indexOfFirst { it.chatId == target.history.id }
                    if (targetIndex < 0) {
                        null
                    } else {
                        val dropAfter = pointerInRoot.y >= bounds.center.y
                        val destination = targetIndex + if (dropAfter) 1 else 0
                        val currentIndex =
                            placements
                                .filter { it.folderId == source.placement.folderId }
                                .sortedBy { it.displayOrder }
                                .indexOfFirst { it.chatId == source.history.id }
                        if (
                            target.placement.folderId == source.placement.folderId &&
                                destination == currentIndex
                        ) {
                            null
                        } else {
                            DropFeedback(
                                kind = DropFeedbackKind.REORDER,
                                targetKey = target.key,
                                move = PendingTreeMove.Chat(
                                    source.history.id,
                                    target.placement.folderId,
                                    destination,
                                ),
                                lineYInRoot = if (dropAfter) bounds.bottom else bounds.top,
                            )
                        }
                    }
                }
            }

            is FolderTreeItem.Folder -> {
                val bounds = rowBoundsInRoot[target.key] ?: return
                val fraction =
                    ((pointerInRoot.y - bounds.top) / bounds.height).coerceIn(0f, 1f)
                val centerZone = fraction in 0.25f..0.75f
                when (source) {
                    is FolderTreeItem.Chat -> {
                        if (!centerZone) {
                            null
                        } else if (target.value.id in collapsedFolderIds) {
                            DropFeedback(DropFeedbackKind.EXPAND_FIRST, target.key)
                        } else {
                            DropFeedback(
                                kind = DropFeedbackKind.INTO_FOLDER,
                                targetKey = target.key,
                                move = PendingTreeMove.Chat(
                                    source.history.id,
                                    target.value.id,
                                    sortedChatSiblings(
                                        target.value.id,
                                        source.history.id,
                                    ).size,
                                ),
                                targetFolderId = target.value.id,
                            )
                        }
                    }

                    is FolderTreeItem.Folder -> {
                        if (centerZone) {
                            when {
                                target.value.id in draggedFolderSubtreeIds ->
                                    DropFeedback(DropFeedbackKind.INVALID, target.key)

                                target.value.id in collapsedFolderIds ->
                                    DropFeedback(DropFeedbackKind.EXPAND_FIRST, target.key)

                                hasFolderNameConflict(source.value, target.value.id) ->
                                    DropFeedback(DropFeedbackKind.INVALID, target.key)

                                else ->
                                    DropFeedback(
                                        kind = DropFeedbackKind.INTO_FOLDER,
                                        targetKey = target.key,
                                        move = PendingTreeMove.Folder(
                                            source.value.id,
                                            target.value.id,
                                            folderAppendIndex(target.value.id, source.value),
                                        ),
                                        targetFolderId = target.value.id,
                                    )
                            }
                        } else if (
                            target.value.id in draggedFolderSubtreeIds ||
                                target.value.pinned != source.value.pinned
                        ) {
                            DropFeedback(DropFeedbackKind.INVALID, target.key)
                        } else {
                            val siblings =
                                sortedFolderSiblings(target.value.parentFolderId, source.value.id)
                            val targetIndex =
                                siblings.indexOfFirst { it.id == target.value.id }
                            if (targetIndex < 0) {
                                null
                            } else {
                                val dropAfter = fraction > 0.75f
                                val destination = targetIndex + if (dropAfter) 1 else 0
                                val currentIndex =
                                    sortedFolderSiblings(source.value.parentFolderId)
                                        .indexOfFirst { it.id == source.value.id }
                                if (
                                    target.value.parentFolderId ==
                                        source.value.parentFolderId &&
                                        destination == currentIndex
                                ) {
                                    null
                                } else if (
                                    hasFolderNameConflict(
                                        source.value,
                                        target.value.parentFolderId,
                                    )
                                ) {
                                    DropFeedback(DropFeedbackKind.INVALID, target.key)
                                } else {
                                    DropFeedback(
                                        kind = DropFeedbackKind.REORDER,
                                        targetKey = target.key,
                                        move = PendingTreeMove.Folder(
                                            source.value.id,
                                            target.value.parentFolderId,
                                            destination,
                                        ),
                                        lineYInRoot =
                                            if (dropAfter) {
                                                folderSubtreeBottomInRoot(target)
                                            } else {
                                                bounds.top
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        setDropFeedback(nextFeedback)
    }

    fun beginDrag(sourceKey: String, pointerInRoot: Offset): Boolean {
        if (!dragEnabled) return false
        val source = treeItemsByKey[sourceKey] ?: return false
        val sourceBounds = rowBoundsInRoot[sourceKey] ?: return false
        dragSession = DragSession(
            source = source,
            grabOffsetY = (pointerInRoot.y - sourceBounds.top)
                .coerceIn(0f, sourceBounds.height),
            sourceBoundsInRoot = sourceBounds,
        )
        dragPointerInRoot = pointerInRoot
        dropFeedback = null
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        return true
    }

    fun updateDrag(pointerInRoot: Offset) {
        if (dragSession == null) return
        dragPointerInRoot = pointerInRoot
        updateDropTarget(pointerInRoot)
    }

    fun finishDrag(commit: Boolean) {
        val completedSession = dragSession
        val completedFolderSubtreeIds =
            (completedSession?.source as? FolderTreeItem.Folder)
                ?.let { source -> folderSubtreeIds(folders, source.value.id) }
                .orEmpty()
        val move = if (commit) dropFeedback?.move else null
        dragSession = null
        dropFeedback = null
        parentDropBoundsInRoot = null
        rootDropBoundsInRoot = null
        if (move == null) return

        pendingCommitKeys =
            when (val source = completedSession?.source) {
                is FolderTreeItem.Folder ->
                    treeItems
                        .filter { item ->
                            when (item) {
                                is FolderTreeItem.Folder ->
                                    item.value.id in completedFolderSubtreeIds

                                is FolderTreeItem.Chat ->
                                    item.placement.folderId in completedFolderSubtreeIds
                            }
                        }
                        .mapTo(mutableSetOf()) { it.key }

                is FolderTreeItem.Chat -> setOf(source.key)
                null -> emptySet()
            }
        commitInProgress = true
        coroutineScope.launch {
            val result = runCatching {
                when (move) {
                    is PendingTreeMove.Chat ->
                        manager.moveChat(move.id, scope, move.parentId, move.index)

                    is PendingTreeMove.Folder ->
                        manager.moveFolder(move.id, move.parentId, move.index)
                }
            }
            if (result.isFailure) {
                pendingCommitKeys = emptySet()
                commitInProgress = false
                snackbarHostState.showSnackbar(
                    message = moveFailedMessage,
                )
                return@launch
            }
            withTimeoutOrNull(600) {
                when (move) {
                    is PendingTreeMove.Chat ->
                        manager.observePlacements(scope).first { latest ->
                            val moved =
                                latest.firstOrNull { it.chatId == move.id }
                                    ?: return@first false
                            val index =
                                latest
                                    .filter { it.folderId == move.parentId }
                                    .sortedBy { it.displayOrder }
                                    .indexOfFirst { it.chatId == move.id }
                            moved.folderId == move.parentId && index == move.index
                        }

                    is PendingTreeMove.Folder ->
                        manager.observeFolders(scope).first { latest ->
                            val moved =
                                latest.firstOrNull { it.id == move.id }
                                    ?: return@first false
                            val index =
                                latest
                                    .filter { it.parentFolderId == move.parentId }
                                    .sortedWith(
                                        compareByDescending<ChatFolderEntity> { it.pinned }
                                            .thenBy { it.displayOrder },
                                    )
                                    .indexOfFirst { it.id == move.id }
                            moved.parentFolderId == move.parentId && index == move.index
                        }
                }
            }
            pendingCommitKeys = emptySet()
            commitInProgress = false
        }
    }

    val currentBeginDrag by rememberUpdatedState<(String, Offset) -> Boolean>(::beginDrag)
    val currentUpdateDrag by rememberUpdatedState<(Offset) -> Unit>(::updateDrag)
    val currentFinishDrag by rememberUpdatedState<(Boolean) -> Unit>(::finishDrag)
    val currentUpdateDropTarget by rememberUpdatedState<(Offset) -> Unit>(::updateDropTarget)
    val currentDragEnabled by rememberUpdatedState(dragEnabled)

    LaunchedEffect(dragSession != null, lazyListState) {
        if (dragSession == null) return@LaunchedEffect
        val edgeSizePx = with(density) { 72.dp.toPx() }
        val maximumStepPx = with(density) { 18.dp.toPx() }
        while (dragSession != null) {
            val currentPointer = dragPointerInRoot
            val pointerY = currentPointer.y - containerPositionInRoot.y
            val isUsingExitTarget =
                dropFeedback?.kind in
                    setOf(
                        DropFeedbackKind.MOVE_TO_PARENT,
                        DropFeedbackKind.MOVE_TO_ROOT,
                    )
            val scrollFactor = when {
                isUsingExitTarget -> 0f
                pointerY < edgeSizePx ->
                    -((edgeSizePx - pointerY) / edgeSizePx).coerceIn(0f, 1f)

                pointerY > containerHeightPx - edgeSizePx ->
                    ((pointerY - (containerHeightPx - edgeSizePx)) / edgeSizePx)
                        .coerceIn(0f, 1f)

                else -> 0f
            }
            if (scrollFactor != 0f) {
                val consumed = lazyListState.scrollBy(scrollFactor * maximumStepPx)
                if (consumed != 0f) {
                    currentUpdateDropTarget(currentPointer)
                }
            }
            delay(16)
        }
    }

    LaunchedEffect(treeItems, dragSession?.source?.key) {
        val sourceKey = dragSession?.source?.key ?: return@LaunchedEffect
        if (treeItems.none { it.key == sourceKey }) {
            finishDrag(commit = false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                containerPositionInRoot = coordinates.positionInRoot()
                containerHeightPx = coordinates.size.height.toFloat()
            }
            .pointerInput(scope) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    if (!currentDragEnabled) return@awaitEachGesture

                    val downInRoot = containerPositionInRoot + down.position
                    val sourceKey =
                        handleBoundsInRoot.entries
                            .firstOrNull { (_, bounds) -> bounds.contains(downInRoot) }
                            ?.key
                            ?: return@awaitEachGesture
                    val pointerId = down.id
                    var accumulatedMovement = Offset.Zero
                    var previousPosition = down.position
                    var dragStarted = false
                    var releasedNormally = false

                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change =
                                event.changes.firstOrNull { it.id == pointerId }
                                    ?: break
                            val pointerInRoot =
                                containerPositionInRoot + change.position

                            if (change.changedToUpIgnoreConsumed()) {
                                if (dragStarted) {
                                    currentUpdateDrag(pointerInRoot)
                                    change.consume()
                                    releasedNormally = true
                                }
                                break
                            }
                            if (!change.pressed) break

                            accumulatedMovement += change.position - previousPosition
                            previousPosition = change.position
                            if (
                                !dragStarted &&
                                    accumulatedMovement.getDistance() >=
                                    viewConfiguration.touchSlop
                            ) {
                                dragStarted =
                                    currentBeginDrag(sourceKey, downInRoot)
                            }
                            if (dragStarted) {
                                currentUpdateDrag(pointerInRoot)
                                change.consume()
                            }
                        }
                    } finally {
                        if (dragStarted) {
                            currentFinishDrag(releasedNormally)
                        }
                    }
                }
            },
    ) {
        LazyColumn(
            state = lazyListState,
            userScrollEnabled = dragSession == null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 22.dp),
        ) {
            items(treeItems, key = { item -> item.key }) { item ->
                DisposableEffect(item.key) {
                    onDispose {
                        rowBoundsInRoot.remove(item.key)
                        handleBoundsInRoot.remove(item.key)
                    }
                }

                val isDraggedSource = dragSession?.source?.key == item.key
                val isPendingCommit = item.key in pendingCommitKeys
                val isDraggedDescendant =
                    when (item) {
                        is FolderTreeItem.Folder ->
                            item.value.id in draggedFolderSubtreeIds &&
                                !isDraggedSource

                        is FolderTreeItem.Chat ->
                            item.placement.folderId in draggedFolderSubtreeIds
                    }
                val isFolderDropTarget =
                    item is FolderTreeItem.Folder &&
                        dropFeedback?.targetFolderId == item.value.id
                val isInvalidTarget =
                    dropFeedback?.targetKey == item.key &&
                        dropFeedback?.kind in
                            setOf(
                                DropFeedbackKind.INVALID,
                                DropFeedbackKind.EXPAND_FIRST,
                            )

                when (item) {
                    is FolderTreeItem.Folder -> {
                        val expanded = item.value.id !in collapsedFolderIds
                        Surface(
                            color = when {
                                isInvalidTarget -> MaterialTheme.colorScheme.errorContainer
                                isFolderDropTarget -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainer
                            },
                            border = when {
                                isInvalidTarget ->
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.error)

                                isFolderDropTarget ->
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)

                                else -> null
                            },
                            shadowElevation = if (isFolderDropTarget) 6.dp else 1.dp,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = (item.depth * 14).dp,
                                    top = 3.dp,
                                    bottom = 3.dp,
                                )
                                .onGloballyPositioned { coordinates ->
                                    rowBoundsInRoot[item.key] = coordinates.boundsInRoot()
                                }
                                .graphicsLayer {
                                    alpha = when {
                                        isPendingCommit -> 0f
                                        isDraggedSource || isDraggedDescendant -> 0.3f
                                        else -> 1f
                                    }
                                }
                                .combinedClickable(
                                    enabled = dragSession == null,
                                    onClick = {
                                        collapsedFolderIds = if (expanded) {
                                            collapsedFolderIds + item.value.id
                                        } else {
                                            collapsedFolderIds - item.value.id
                                        }
                                    },
                                    onLongClick = { onFolderLongPress(item.value) },
                                ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TreeDragHandle(
                                    sourceKey = item.key,
                                    contentDescription = stringResource(
                                        R.string.drag_item,
                                        item.value.name,
                                    ),
                                    onBoundsChanged = { bounds ->
                                        if (bounds == null) {
                                            handleBoundsInRoot.remove(item.key)
                                        } else {
                                            handleBoundsInRoot[item.key] = bounds
                                        }
                                    },
                                )
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(10.dp))
                                val targetHint = when {
                                    isFolderDropTarget ->
                                        stringResource(
                                            R.string.move_into_folder_named,
                                            item.value.name,
                                        )

                                    isInvalidTarget &&
                                        dropFeedback?.kind ==
                                        DropFeedbackKind.EXPAND_FIRST ->
                                        stringResource(R.string.expand_folder_before_drop)

                                    isInvalidTarget ->
                                        stringResource(R.string.cannot_move_here)

                                    else -> null
                                }
                                Text(
                                    text = targetHint ?: item.value.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight =
                                        if (targetHint == null) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Bold
                                        },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (item.value.pinned) {
                                    Icon(
                                        Icons.Default.PushPin,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Icon(
                                    imageVector = if (expanded) {
                                        Icons.Default.KeyboardArrowDown
                                    } else {
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                                    },
                                    contentDescription = null,
                                )
                            }
                        }
                    }

                    is FolderTreeItem.Chat -> {
                        val selected = item.history.id == currentId
                        Surface(
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            shadowElevation = 0.dp,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = (item.depth * 14).dp,
                                    top = 2.dp,
                                    bottom = 2.dp,
                                )
                                .onGloballyPositioned { coordinates ->
                                    rowBoundsInRoot[item.key] = coordinates.boundsInRoot()
                                }
                                .graphicsLayer {
                                    alpha = when {
                                        isPendingCommit -> 0f
                                        isDraggedSource || isDraggedDescendant -> 0.3f
                                        else -> 1f
                                    }
                                }
                                .combinedClickable(
                                    enabled = dragSession == null,
                                    onClick = { onSelectChat(item.history.id) },
                                    onLongClick = { onChatLongPress(item.history) },
                                ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TreeDragHandle(
                                    sourceKey = item.key,
                                    contentDescription = stringResource(
                                        R.string.drag_item,
                                        item.history.title,
                                    ),
                                    onBoundsChanged = { bounds ->
                                        if (bounds == null) {
                                            handleBoundsInRoot.remove(item.key)
                                        } else {
                                            handleBoundsInRoot[item.key] = bounds
                                        }
                                    },
                                )
                                Text(
                                    text = item.history.title,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (scope == ChatFolderScope.FAVORITE) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                if (activeStreamingChatIds.contains(item.history.id)) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                    )
                                }
                                if (item.history.pinned) {
                                    Icon(
                                        Icons.Default.PushPin,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                if (item.history.locked) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        dropFeedback?.lineYInRoot?.let { lineYInRoot ->
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (lineYInRoot - containerPositionInRoot.y).roundToInt(),
                        )
                    }
                    .zIndex(20f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(4.dp),
            ) {}
        }

        val activeDrag = dragSession
        if (activeDrag != null) {
            val sourceContainerId = when (val source = activeDrag.source) {
                is FolderTreeItem.Chat -> source.placement.folderId
                is FolderTreeItem.Folder -> source.value.parentFolderId
            }
            if (sourceContainerId != null) {
                val oneLevelUpParentId = foldersById[sourceContainerId]?.parentFolderId
                val showSeparateParentTarget = oneLevelUpParentId != null
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(30f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (showSeparateParentTarget) {
                        val active =
                            dropFeedback?.kind == DropFeedbackKind.MOVE_TO_PARENT
                        val invalid =
                            dropFeedback?.targetKey == "move-to-parent" &&
                                dropFeedback?.kind == DropFeedbackKind.INVALID
                        Surface(
                            color = when {
                                invalid -> MaterialTheme.colorScheme.errorContainer
                                active -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            border = BorderStroke(
                                if (active || invalid) 2.dp else 1.dp,
                                when {
                                    invalid -> MaterialTheme.colorScheme.error
                                    active -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                            shadowElevation = 6.dp,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .onGloballyPositioned { coordinates ->
                                    parentDropBoundsInRoot = coordinates.boundsInRoot()
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = null,
                                )
                                Text(
                                    if (invalid) {
                                        stringResource(R.string.cannot_move_here)
                                    } else {
                                        stringResource(R.string.move_to_parent_level)
                                    },
                                    fontWeight =
                                        if (active || invalid) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Medium
                                        },
                                )
                            }
                        }
                    }

                    val rootActive =
                        dropFeedback?.kind == DropFeedbackKind.MOVE_TO_ROOT
                    val rootInvalid =
                        dropFeedback?.targetKey == "move-to-root" &&
                            dropFeedback?.kind == DropFeedbackKind.INVALID
                    Surface(
                        color = when {
                            rootInvalid -> MaterialTheme.colorScheme.errorContainer
                            rootActive -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = BorderStroke(
                            if (rootActive || rootInvalid) 2.dp else 1.dp,
                            when {
                                rootInvalid -> MaterialTheme.colorScheme.error
                                rootActive -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                        shadowElevation = 6.dp,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .onGloballyPositioned { coordinates ->
                                rootDropBoundsInRoot = coordinates.boundsInRoot()
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = null,
                            )
                            Text(
                                if (rootInvalid) {
                                    stringResource(R.string.cannot_move_here)
                                } else {
                                    stringResource(R.string.move_to_root_level)
                                },
                                fontWeight =
                                    if (rootActive || rootInvalid) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Medium
                                    },
                            )
                        }
                    }
                }
            }

            val overlayTop =
                dragPointerInRoot.y -
                    containerPositionInRoot.y -
                    activeDrag.grabOffsetY
            val overlayLeft =
                activeDrag.sourceBoundsInRoot.left - containerPositionInRoot.x
            val hint = when (dropFeedback?.kind) {
                DropFeedbackKind.REORDER ->
                    stringResource(R.string.release_to_reorder_here)

                DropFeedbackKind.INTO_FOLDER -> {
                    val folderName =
                        dropFeedback?.targetFolderId?.let { foldersById[it]?.name }.orEmpty()
                    stringResource(R.string.move_into_folder_named, folderName)
                }

                DropFeedbackKind.EXPAND_FIRST ->
                    stringResource(R.string.expand_folder_before_drop)

                DropFeedbackKind.INVALID ->
                    stringResource(R.string.cannot_move_here)

                DropFeedbackKind.MOVE_TO_PARENT ->
                    stringResource(R.string.move_to_parent_level)

                DropFeedbackKind.MOVE_TO_ROOT ->
                    stringResource(R.string.move_to_root_level)

                null -> null
            }
            val invalid =
                dropFeedback?.kind in
                    setOf(DropFeedbackKind.EXPAND_FIRST, DropFeedbackKind.INVALID)
            Surface(
                color = if (invalid) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                border = BorderStroke(
                    2.dp,
                    if (invalid) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ),
                shadowElevation = 12.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            overlayLeft.roundToInt(),
                            overlayTop.roundToInt(),
                        )
                    }
                    .zIndex(25f)
                    .width(with(density) { activeDrag.sourceBoundsInRoot.width.toDp() })
                    .height(with(density) { activeDrag.sourceBoundsInRoot.height.toDp() }),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                    when (val source = activeDrag.source) {
                        is FolderTreeItem.Folder -> {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = hint?.let { "${source.value.name} · $it" }
                                    ?: source.value.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        is FolderTreeItem.Chat -> {
                            Text(
                                text = hint?.let { "${source.history.title} · $it" }
                                    ?: source.history.title,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).zIndex(40f),
        )
    }
}
