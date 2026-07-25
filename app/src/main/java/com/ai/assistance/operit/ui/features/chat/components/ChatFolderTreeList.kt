package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitDragOrCancellation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val FolderInsertionSlotHeight = 16.dp
private const val RootDropTargetKey = "root-drop-target"

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

    data class FolderInsertion(
        val parentFolderId: String?,
        val siblingIndex: Int,
        override val depth: Int,
    ) : FolderTreeItem {
        override val key = "folder-insertion:${parentFolderId?.let { "folder:$it" } ?: "root"}:$siblingIndex"
    }
}

private sealed interface PendingTreeMove {
    data class Folder(val id: String, val parentId: String?, val index: Int) : PendingTreeMove
    data class Chat(val id: String, val parentId: String?, val index: Int) : PendingTreeMove
}

private fun folderStateMatches(
    authoritative: List<ChatFolderEntity>,
    draft: List<ChatFolderEntity>,
): Boolean = authoritative.associate { it.id to (it.parentFolderId to it.displayOrder) } ==
    draft.associate { it.id to (it.parentFolderId to it.displayOrder) }

private fun placementStateMatches(
    authoritative: List<ChatPlacementEntity>,
    draft: List<ChatPlacementEntity>,
): Boolean = authoritative.associate { it.chatId to (it.folderId to it.displayOrder) } ==
    draft.associate { it.chatId to (it.folderId to it.displayOrder) }

private fun folderSubtreeIds(
    folders: List<ChatFolderEntity>,
    rootId: String,
): Set<String> {
    val childrenByParent = folders.groupBy { it.parentFolderId }
    val result = mutableSetOf<String>()
    val pending = mutableListOf(rootId)
    while (pending.isNotEmpty()) {
        val id = pending.removeAt(pending.lastIndex)
        if (!result.add(id)) continue
        childrenByParent[id].orEmpty().forEach { pending += it.id }
    }
    return result
}

private fun applyFolderMove(
    folders: List<ChatFolderEntity>,
    move: PendingTreeMove.Folder,
): List<ChatFolderEntity> {
    val moved = folders.firstOrNull { it.id == move.id } ?: return folders
    val siblings = folders
        .filter { it.parentFolderId == move.parentId && it.id != move.id }
        .sortedWith(compareByDescending<ChatFolderEntity> { it.pinned }.thenBy { it.displayOrder })
        .toMutableList()
    siblings.add(
        move.index.coerceIn(0, siblings.size),
        moved.copy(parentFolderId = move.parentId),
    )
    val updates = siblings.mapIndexed { index, folder ->
        folder.copy(parentFolderId = move.parentId, displayOrder = index.toLong())
    }.associateBy { it.id }
    return folders.map { updates[it.id] ?: it }
}

private fun applyChatMove(
    placements: List<ChatPlacementEntity>,
    move: PendingTreeMove.Chat,
): List<ChatPlacementEntity> {
    val moved = placements.firstOrNull { it.chatId == move.id } ?: return placements
    val siblings = placements
        .filter { it.folderId == move.parentId && it.chatId != move.id }
        .sortedBy { it.displayOrder }
        .toMutableList()
    siblings.add(
        move.index.coerceIn(0, siblings.size),
        moved.copy(folderId = move.parentId),
    )
    val updates = siblings.mapIndexed { index, placement ->
        placement.copy(folderId = move.parentId, displayOrder = index.toLong())
    }.associateBy { it.chatId }
    return placements.map { updates[it.chatId] ?: it }
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
    val draggingEnabled = searchQuery.isBlank()
    val hapticFeedback = LocalHapticFeedback.current
    var draftFolders by remember(scope) { mutableStateOf<List<ChatFolderEntity>>(emptyList()) }
    var draftPlacements by remember(scope) { mutableStateOf<List<ChatPlacementEntity>>(emptyList()) }
    var useDraft by remember(scope) { mutableStateOf(false) }
    var dragActive by remember(scope) { mutableStateOf(false) }
    var commitInProgress by remember(scope) { mutableStateOf(false) }
    var draggingFolderId by remember(scope) { mutableStateOf<String?>(null) }
    var forbiddenFolderDropIds by remember(scope) { mutableStateOf<Set<String>>(emptySet()) }
    var activeInsertionKey by remember(scope) { mutableStateOf<String?>(null) }
    var activeChatTargetId by remember(scope) { mutableStateOf<String?>(null) }
    var candidateChatTargetId by remember(scope) { mutableStateOf<String?>(null) }
    var candidateChatMove by remember(scope) { mutableStateOf<PendingTreeMove.Chat?>(null) }
    var candidateFolderTargetId by remember(scope) { mutableStateOf<String?>(null) }
    var candidateFolderMove by remember(scope) { mutableStateOf<PendingTreeMove?>(null) }
    var activeFolderTargetId by remember(scope) { mutableStateOf<String?>(null) }
    var lastInsertionFeedbackKey by remember(scope) { mutableStateOf<String?>(null) }
    var dragSnapshotItems by remember(scope) { mutableStateOf<List<FolderTreeItem>>(emptyList()) }
    var draggedItemKey by remember(scope) { mutableStateOf<String?>(null) }
    var dragOverlayTopPx by remember(scope) { mutableStateOf(0f) }
    var dragPointerY by remember(scope) { mutableStateOf(0f) }
    var dragStartedWithDraft by remember(scope) { mutableStateOf(false) }
    var invalidDropTargetKey by remember(scope) { mutableStateOf<String?>(null) }
    var activeChatDropAfter by remember(scope) { mutableStateOf<Boolean?>(null) }
    var candidateChatDropAfter by remember(scope) { mutableStateOf<Boolean?>(null) }
    var containerPositionInRoot by remember(scope) { mutableStateOf(Offset.Zero) }
    var rootDropTargetBoundsInRoot by remember(scope) { mutableStateOf<Rect?>(null) }
    val dragHandleBoundsInRoot = remember(scope) { mutableMapOf<String, Rect>() }
    val pendingMove = remember(scope) { mutableStateOf<PendingTreeMove?>(null) }
    val visibleFolders = if (useDraft) draftFolders else folders
    val visiblePlacements = if (useDraft) draftPlacements else placements
    val normalizedQuery = searchQuery.trim()
    val matchingHistoryIds = remember(histories, normalizedQuery, matchedChatIdsByContent) {
        if (normalizedQuery.isBlank()) {
            histories.mapTo(mutableSetOf()) { it.id }
        } else {
            histories.filterTo(mutableListOf()) { history ->
                history.title.contains(normalizedQuery, ignoreCase = true) ||
                    history.id in matchedChatIdsByContent
            }.mapTo(mutableSetOf()) { it.id }
        }
    }
    val treeItems = remember(visibleFolders, visiblePlacements, histories, collapsedFolderIds, matchingHistoryIds) {
        val historiesById = histories.associateBy { it.id }
        val foldersByParent = visibleFolders.groupBy { it.parentFolderId }
        val placementsByFolder = visiblePlacements.groupBy { it.folderId }
        val result = mutableListOf<FolderTreeItem>()
        val visited = mutableSetOf<String>()

        lateinit var appendFolder: (ChatFolderEntity, Int) -> Unit
        fun appendFolderChildren(parentFolderId: String?, depth: Int) {
            val siblings = foldersByParent[parentFolderId].orEmpty()
                .sortedWith(compareByDescending<ChatFolderEntity> { it.pinned }.thenBy { it.displayOrder })
            siblings.forEachIndexed { index, folder ->
                result += FolderTreeItem.FolderInsertion(parentFolderId, index, depth)
                appendFolder(folder, depth)
            }
            result += FolderTreeItem.FolderInsertion(parentFolderId, siblings.size, depth)
        }

        appendFolder = append@ { folder, depth ->
            if (!visited.add(folder.id)) return@append
            result += FolderTreeItem.Folder(folder, depth)
            if (folder.id in collapsedFolderIds) return@append
            appendFolderChildren(folder.id, depth + 1)
            placementsByFolder[folder.id].orEmpty()
                .sortedBy { it.displayOrder }
                .forEach { placement ->
                    historiesById[placement.chatId]
                        ?.takeIf { it.id in matchingHistoryIds }
                        ?.let { result += FolderTreeItem.Chat(it, placement, depth + 1) }
                }
        }

        appendFolderChildren(null, 0)
        placementsByFolder[null].orEmpty()
            .sortedBy { it.displayOrder }
            .forEach { placement ->
                historiesById[placement.chatId]
                    ?.takeIf { it.id in matchingHistoryIds }
                    ?.let { result += FolderTreeItem.Chat(it, placement, 0) }
            }
        result
    }
    val displayItems = if (dragActive && dragSnapshotItems.isNotEmpty()) {
        dragSnapshotItems
    } else {
        treeItems
    }
    val draggedItem = draggedItemKey?.let { key ->
        displayItems.firstOrNull { it.key == key }
    }
    val showRootDropTarget = dragActive && when (draggedItem) {
        is FolderTreeItem.Folder -> draggedItem.value.parentFolderId != null
        is FolderTreeItem.Chat -> draggedItem.placement.folderId != null
        else -> false
    }

    LaunchedEffect(folders, placements, useDraft, dragActive) {
        if (!useDraft) {
            draftFolders = folders
            draftPlacements = placements
        } else if (
            !dragActive &&
                folderStateMatches(folders, draftFolders) &&
                placementStateMatches(placements, draftPlacements)
        ) {
            useDraft = false
            pendingMove.value = null
        }
    }

    LaunchedEffect(candidateFolderTargetId, candidateFolderMove, dragActive) {
        val targetId = candidateFolderTargetId
        val move = candidateFolderMove
        if (!dragActive || targetId == null || move == null) return@LaunchedEffect
        delay(200)
        if (
            dragActive &&
                candidateFolderTargetId == targetId &&
                candidateFolderMove == move
        ) {
            activeFolderTargetId = targetId
            pendingMove.value = move
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    LaunchedEffect(candidateChatTargetId, candidateChatMove, dragActive) {
        val targetId = candidateChatTargetId
        val move = candidateChatMove
        if (!dragActive || targetId == null || move == null) return@LaunchedEffect
        delay(160)
        if (
            dragActive &&
                candidateChatTargetId == targetId &&
                candidateChatMove == move
        ) {
            activeChatTargetId = targetId
            activeChatDropAfter = candidateChatDropAfter
            pendingMove.value = move
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun clearDropTarget(invalidKey: String? = null) {
        activeInsertionKey = null
        activeChatTargetId = null
        activeChatDropAfter = null
        candidateChatTargetId = null
        candidateChatMove = null
        candidateChatDropAfter = null
        candidateFolderTargetId = null
        candidateFolderMove = null
        activeFolderTargetId = null
        pendingMove.value = null
        invalidDropTargetKey = invalidKey
        lastInsertionFeedbackKey = null
    }

    fun armInsertionTarget(key: String, move: PendingTreeMove) {
        activeChatTargetId = null
        activeChatDropAfter = null
        candidateChatTargetId = null
        candidateChatMove = null
        candidateChatDropAfter = null
        candidateFolderTargetId = null
        candidateFolderMove = null
        activeFolderTargetId = null
        invalidDropTargetKey = null
        activeInsertionKey = key
        pendingMove.value = move
        if (lastInsertionFeedbackKey != key) {
            lastInsertionFeedbackKey = key
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun hoverFolderTarget(targetId: String, move: PendingTreeMove) {
        activeInsertionKey = null
        activeChatTargetId = null
        activeChatDropAfter = null
        candidateChatTargetId = null
        candidateChatMove = null
        candidateChatDropAfter = null
        invalidDropTargetKey = null
        lastInsertionFeedbackKey = null
        val alreadyArmed =
            activeFolderTargetId == targetId && pendingMove.value == move
        if (!alreadyArmed) {
            activeFolderTargetId = null
            pendingMove.value = null
        }
        candidateFolderTargetId = targetId
        candidateFolderMove = move
    }

    fun hoverChatTarget(
        targetId: String,
        move: PendingTreeMove.Chat,
        dropAfter: Boolean,
        requiresDwell: Boolean,
    ) {
        activeInsertionKey = null
        candidateFolderTargetId = null
        candidateFolderMove = null
        activeFolderTargetId = null
        invalidDropTargetKey = null
        lastInsertionFeedbackKey = null
        if (!requiresDwell) {
            candidateChatTargetId = null
            candidateChatMove = null
            candidateChatDropAfter = null
            activeChatTargetId = targetId
            activeChatDropAfter = dropAfter
            pendingMove.value = move
            return
        }
        val alreadyArmed =
            activeChatTargetId == targetId &&
                activeChatDropAfter == dropAfter &&
                pendingMove.value == move
        if (!alreadyArmed) {
            activeChatTargetId = null
            activeChatDropAfter = null
            pendingMove.value = null
        }
        candidateChatTargetId = targetId
        candidateChatMove = move
        candidateChatDropAfter = dropAfter
    }

    fun hasFolderNameConflict(moved: ChatFolderEntity, parentId: String?): Boolean =
        visibleFolders.any { folder ->
            folder.id != moved.id &&
                folder.parentFolderId == parentId &&
                folder.name == moved.name
        }

    fun updateDropTarget(pointerY: Float) {
        if (!dragActive) return
        val moved = draggedItemKey?.let { key ->
            dragSnapshotItems.firstOrNull { it.key == key }
        } ?: run {
            clearDropTarget()
            return
        }

        val sourceIsNested = when (moved) {
            is FolderTreeItem.Folder -> moved.value.parentFolderId != null
            is FolderTreeItem.Chat -> moved.placement.folderId != null
            is FolderTreeItem.FolderInsertion -> false
        }
        val pointerYInRoot = containerPositionInRoot.y + pointerY
        val rootBounds = rootDropTargetBoundsInRoot
        if (
            sourceIsNested &&
                rootBounds != null &&
                pointerYInRoot >= rootBounds.top &&
                pointerYInRoot <= rootBounds.bottom
        ) {
            val move = when (moved) {
                is FolderTreeItem.Folder -> {
                    if (hasFolderNameConflict(moved.value, null)) {
                        clearDropTarget(RootDropTargetKey)
                        return
                    }
                    val siblings = visibleFolders
                        .filter { it.parentFolderId == null && it.id != moved.value.id }
                        .sortedWith(
                            compareByDescending<ChatFolderEntity> { it.pinned }
                                .thenBy { it.displayOrder }
                        )
                    val destination = if (moved.value.pinned) {
                        siblings.count { it.pinned }
                    } else {
                        siblings.size
                    }
                    PendingTreeMove.Folder(moved.value.id, null, destination)
                }
                is FolderTreeItem.Chat -> {
                    val destination = visiblePlacements.count {
                        it.folderId == null && it.chatId != moved.history.id
                    }
                    PendingTreeMove.Chat(moved.history.id, null, destination)
                }
                is FolderTreeItem.FolderInsertion -> return
            }
            armInsertionTarget(RootDropTargetKey, move)
            return
        }

        val layoutInfo = lazyListState.layoutInfo
        if (
            pointerY < layoutInfo.viewportStartOffset ||
                pointerY > layoutInfo.viewportEndOffset
        ) {
            clearDropTarget()
            return
        }
        val targetInfo = layoutInfo.visibleItemsInfo.firstOrNull { info ->
            pointerY >= info.offset && pointerY < info.offset + info.size
        } ?: run {
            clearDropTarget()
            return
        }
        val target = dragSnapshotItems.firstOrNull { it.key == targetInfo.key } ?: run {
            clearDropTarget()
            return
        }
        if (target.key == moved.key) {
            clearDropTarget()
            return
        }

        when (moved) {
            is FolderTreeItem.Folder -> when (target) {
                is FolderTreeItem.Chat -> clearDropTarget(target.key)
                is FolderTreeItem.Folder -> {
                    if (
                        target.value.id in forbiddenFolderDropIds ||
                            hasFolderNameConflict(moved.value, target.value.id)
                    ) {
                        clearDropTarget(target.key)
                        return
                    }
                    val targetChildren = visibleFolders
                        .filter {
                            it.parentFolderId == target.value.id &&
                                it.id != moved.value.id
                        }
                        .sortedWith(
                            compareByDescending<ChatFolderEntity> { it.pinned }
                                .thenBy { it.displayOrder }
                        )
                    val destination = if (moved.value.pinned) {
                        targetChildren.count { it.pinned }
                    } else {
                        targetChildren.size
                    }
                    val sourceIndex = visibleFolders
                        .filter { it.parentFolderId == target.value.id }
                        .sortedWith(
                            compareByDescending<ChatFolderEntity> { it.pinned }
                                .thenBy { it.displayOrder }
                        )
                        .indexOfFirst { it.id == moved.value.id }
                    if (
                        moved.value.parentFolderId == target.value.id &&
                            sourceIndex == destination
                    ) {
                        clearDropTarget()
                        return
                    }
                    hoverFolderTarget(
                        target.value.id,
                        PendingTreeMove.Folder(
                            moved.value.id,
                            target.value.id,
                            destination,
                        ),
                    )
                }
                is FolderTreeItem.FolderInsertion -> {
                    val targetParentId = target.parentFolderId
                    if (
                        targetParentId in forbiddenFolderDropIds ||
                            hasFolderNameConflict(moved.value, targetParentId)
                    ) {
                        clearDropTarget(target.key)
                        return
                    }
                    val currentSiblings = visibleFolders
                        .filter { it.parentFolderId == targetParentId }
                        .sortedWith(
                            compareByDescending<ChatFolderEntity> { it.pinned }
                                .thenBy { it.displayOrder }
                        )
                    val sourceIndex = currentSiblings.indexOfFirst {
                        it.id == moved.value.id
                    }
                    val withoutMoved = currentSiblings.filter {
                        it.id != moved.value.id
                    }
                    val adjustedIndex = if (
                        moved.value.parentFolderId == targetParentId &&
                            sourceIndex >= 0 &&
                            sourceIndex < target.siblingIndex
                    ) {
                        target.siblingIndex - 1
                    } else {
                        target.siblingIndex
                    }
                    val destination = adjustedIndex.coerceIn(0, withoutMoved.size)
                    val pinnedCount = withoutMoved.count { it.pinned }
                    val validRange = if (moved.value.pinned) {
                        0..pinnedCount
                    } else {
                        pinnedCount..withoutMoved.size
                    }
                    if (destination !in validRange) {
                        clearDropTarget(target.key)
                        return
                    }
                    if (
                        moved.value.parentFolderId == targetParentId &&
                            sourceIndex == destination
                    ) {
                        clearDropTarget()
                        return
                    }
                    armInsertionTarget(
                        target.key,
                        PendingTreeMove.Folder(
                            moved.value.id,
                            targetParentId,
                            destination,
                        ),
                    )
                }
            }
            is FolderTreeItem.Chat -> when (target) {
                is FolderTreeItem.Folder -> {
                    val targetSiblings = visiblePlacements
                        .filter {
                            it.folderId == target.value.id &&
                                it.chatId != moved.history.id
                        }
                        .sortedBy { it.displayOrder }
                    val sourceIndex = visiblePlacements
                        .filter { it.folderId == target.value.id }
                        .sortedBy { it.displayOrder }
                        .indexOfFirst { it.chatId == moved.history.id }
                    if (
                        moved.placement.folderId == target.value.id &&
                            sourceIndex == targetSiblings.size
                    ) {
                        clearDropTarget()
                        return
                    }
                    hoverFolderTarget(
                        target.value.id,
                        PendingTreeMove.Chat(
                            moved.history.id,
                            target.value.id,
                            targetSiblings.size,
                        ),
                    )
                }
                is FolderTreeItem.Chat -> {
                    val targetFolderId = target.placement.folderId
                    val currentSiblings = visiblePlacements
                        .filter { it.folderId == targetFolderId }
                        .sortedBy { it.displayOrder }
                    val sourceIndex = currentSiblings.indexOfFirst {
                        it.chatId == moved.history.id
                    }
                    val withoutMoved = currentSiblings.filter {
                        it.chatId != moved.history.id
                    }
                    val targetIndex = withoutMoved.indexOfFirst {
                        it.chatId == target.history.id
                    }
                    if (targetIndex < 0) {
                        clearDropTarget()
                        return
                    }
                    val dropAfter =
                        pointerY >= targetInfo.offset + targetInfo.size / 2f
                    val destination = targetIndex + if (dropAfter) 1 else 0
                    if (
                        moved.placement.folderId == targetFolderId &&
                            sourceIndex == destination
                    ) {
                        clearDropTarget()
                        return
                    }
                    hoverChatTarget(
                        target.history.id,
                        PendingTreeMove.Chat(
                            moved.history.id,
                            targetFolderId,
                            destination.coerceIn(0, withoutMoved.size),
                        ),
                        dropAfter,
                        requiresDwell = moved.placement.folderId != targetFolderId,
                    )
                }
                is FolderTreeItem.FolderInsertion -> clearDropTarget(target.key)
            }
            is FolderTreeItem.FolderInsertion -> clearDropTarget()
        }
    }

    fun beginDrag(
        sourceKey: String,
        pointerY: Float,
        displacementY: Float,
    ): Boolean {
        val source = treeItems.firstOrNull { item ->
            item.key == sourceKey &&
                (item is FolderTreeItem.Folder || item is FolderTreeItem.Chat)
        } ?: return false
        val sourceInfo = lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == sourceKey }
            ?: return false
        dragStartedWithDraft = useDraft
        if (!useDraft) {
            draftFolders = folders
            draftPlacements = placements
        }
        dragSnapshotItems = treeItems
        useDraft = true
        dragActive = true
        draggedItemKey = sourceKey
        draggingFolderId = (source as? FolderTreeItem.Folder)?.value?.id
        forbiddenFolderDropIds = (source as? FolderTreeItem.Folder)?.let {
            folderSubtreeIds(visibleFolders, it.value.id)
        }.orEmpty()
        dragOverlayTopPx = sourceInfo.offset + displacementY
        dragPointerY = pointerY
        clearDropTarget()
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        updateDropTarget(pointerY)
        return true
    }

    fun updateDrag(pointerY: Float, deltaY: Float) {
        if (!dragActive) return
        dragPointerY = pointerY
        dragOverlayTopPx += deltaY
        updateDropTarget(pointerY)
    }

    fun finishDrag(commitDrop: Boolean) {
        val keepExistingDraft = dragStartedWithDraft
        val move = pendingMove.value.takeIf { commitDrop }
        dragActive = false
        draggingFolderId = null
        forbiddenFolderDropIds = emptySet()
        draggedItemKey = null
        dragSnapshotItems = emptyList()
        rootDropTargetBoundsInRoot = null
        clearDropTarget()
        if (move == null) {
            useDraft = keepExistingDraft
            return
        }
        when (move) {
            is PendingTreeMove.Folder -> {
                draftFolders = applyFolderMove(visibleFolders, move)
                draftPlacements = visiblePlacements
                move.parentId?.let { collapsedFolderIds = collapsedFolderIds - it }
            }
            is PendingTreeMove.Chat -> {
                draftFolders = visibleFolders
                draftPlacements = applyChatMove(visiblePlacements, move)
                move.parentId?.let { collapsedFolderIds = collapsedFolderIds - it }
            }
        }
        useDraft = true
        commitInProgress = true
        CoroutineScope(Dispatchers.IO).launch {
            val succeeded = runCatching {
                when (move) {
                    is PendingTreeMove.Folder -> manager.moveFolder(move.id, move.parentId, move.index)
                    is PendingTreeMove.Chat -> manager.moveChat(move.id, scope, move.parentId, move.index)
                }
            }.isSuccess
            withContext(Dispatchers.Main.immediate) {
                if (succeeded) {
                    var remainingFlowFrames = 20
                    while (
                        useDraft &&
                            remainingFlowFrames > 0 &&
                            !(
                                folderStateMatches(folders, draftFolders) &&
                                    placementStateMatches(placements, draftPlacements)
                                )
                    ) {
                        delay(16)
                        remainingFlowFrames--
                    }
                }
                // Room invalidation normally reconciles the draft immediately. The bounded
                // fallback prevents unrelated concurrent list updates from pinning stale UI.
                useDraft = false
                commitInProgress = false
            }
        }
    }

    val currentDraggingAllowed = rememberUpdatedState(draggingEnabled && !commitInProgress)
    val currentBeginDrag = rememberUpdatedState(
        newValue = { key: String, pointerY: Float, displacementY: Float ->
            beginDrag(key, pointerY, displacementY)
        }
    )
    val currentUpdateDrag = rememberUpdatedState(
        newValue = { pointerY: Float, deltaY: Float ->
            updateDrag(pointerY, deltaY)
        }
    )
    val currentFinishDrag = rememberUpdatedState(
        newValue = { commitDrop: Boolean ->
            finishDrag(commitDrop)
        }
    )
    val density = LocalDensity.current
    val edgeScrollSizePx = with(density) { 56.dp.toPx() }
    val minimumEdgeScrollStepPx = with(density) { 2.dp.toPx() }
    val maximumEdgeScrollStepPx = with(density) { 20.dp.toPx() }

    LaunchedEffect(dragActive, lazyListState) {
        while (dragActive) {
            delay(16)
            val pointerYInRoot = containerPositionInRoot.y + dragPointerY
            val rootBounds = rootDropTargetBoundsInRoot
            if (
                rootBounds != null &&
                    pointerYInRoot >= rootBounds.top &&
                    pointerYInRoot <= rootBounds.bottom
            ) {
                continue
            }
            val layoutInfo = lazyListState.layoutInfo
            val viewportStart = layoutInfo.viewportStartOffset.toFloat()
            val viewportEnd = layoutInfo.viewportEndOffset.toFloat()
            val strength = when {
                dragPointerY < viewportStart + edgeScrollSizePx ->
                    -((viewportStart + edgeScrollSizePx - dragPointerY) / edgeScrollSizePx)
                        .coerceIn(0f, 1f)
                dragPointerY > viewportEnd - edgeScrollSizePx ->
                    ((dragPointerY - (viewportEnd - edgeScrollSizePx)) / edgeScrollSizePx)
                        .coerceIn(0f, 1f)
                else -> 0f
            }
            if (strength == 0f) continue
            val magnitude = minimumEdgeScrollStepPx +
                (maximumEdgeScrollStepPx - minimumEdgeScrollStepPx) *
                strength * strength
            val consumed = lazyListState.scrollBy(
                if (strength < 0f) -magnitude else magnitude
            )
            if (consumed != 0f) {
                updateDropTarget(dragPointerY)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                containerPositionInRoot = coordinates.positionInRoot()
            }
            .pointerInput(scope, draggingEnabled, commitInProgress) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    if (!currentDraggingAllowed.value) {
                        return@awaitEachGesture
                    }
                    val downInRoot = containerPositionInRoot + down.position
                    val sourceKey = dragHandleBoundsInRoot.entries
                        .lastOrNull { (_, bounds) -> bounds.contains(downInRoot) }
                        ?.key
                        ?: return@awaitEachGesture
                    down.consume()

                    var dragStarted = false
                    var normalRelease = false
                    try {
                        val slopChange = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        } ?: return@awaitEachGesture
                        val displacement = slopChange.position - down.position
                        dragStarted = currentBeginDrag.value(
                            sourceKey,
                            slopChange.position.y,
                            displacement.y,
                        )
                        if (!dragStarted) return@awaitEachGesture
                        var pointerId = slopChange.id
                        while (true) {
                            val change = awaitDragOrCancellation(pointerId) ?: break
                            if (change.changedToUp()) {
                                currentUpdateDrag.value(
                                    change.position.y,
                                    change.positionChange().y,
                                )
                                change.consume()
                                normalRelease = true
                                break
                            }
                            if (!change.pressed) break
                            currentUpdateDrag.value(
                                change.position.y,
                                change.positionChange().y,
                            )
                            change.consume()
                            pointerId = change.id
                        }
                    } finally {
                        if (dragStarted) {
                            currentFinishDrag.value(normalRelease)
                        }
                    }
                }
            }
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 22.dp),
        ) {
        items(displayItems, key = { it.key }) { item ->
            val isDragging = item.key == draggedItemKey
            val isInDraggedFolderSubtree = draggingFolderId != null && when (item) {
                is FolderTreeItem.Folder -> item.value.id in forbiddenFolderDropIds
                is FolderTreeItem.Chat -> item.placement.folderId in forbiddenFolderDropIds
                is FolderTreeItem.FolderInsertion ->
                    item.parentFolderId in forbiddenFolderDropIds
            }
            Box(
                modifier = Modifier
                    .animateItem(
                        placementSpec = if (dragActive) null else tween(durationMillis = 220)
                    )
                    .graphicsLayer {
                        alpha = if (isDragging || isInDraggedFolderSubtree) 0.32f else 1f
                    }
            ) {
                when (item) {
                    is FolderTreeItem.FolderInsertion -> {
                        val isInsertionDrag = dragActive && draggingFolderId != null
                        val isActive = activeInsertionKey == item.key
                        val isInvalid = invalidDropTargetKey == item.key
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FolderInsertionSlotHeight)
                                .padding(start = (item.depth * 14 + 8).dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isInsertionDrag) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(if (isActive || isInvalid) 3.dp else 1.dp)
                                            .background(
                                                when {
                                                    isInvalid -> MaterialTheme.colorScheme.error
                                                    isActive -> MaterialTheme.colorScheme.primary
                                                    else -> MaterialTheme.colorScheme.outlineVariant
                                                }
                                            )
                                    )
                                    if (isActive) {
                                        val parentName = item.parentFolderId?.let { parentId ->
                                            visibleFolders.firstOrNull { it.id == parentId }?.name
                                        }
                                        Text(
                                            text = if (parentName == null) {
                                                stringResource(R.string.move_to_root_level)
                                            } else {
                                                stringResource(R.string.move_to_folder_level, parentName)
                                            },
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is FolderTreeItem.Folder -> {
                        DisposableEffect(item.key) {
                            onDispose { dragHandleBoundsInRoot.remove(item.key) }
                        }
                        val expanded = item.value.id !in collapsedFolderIds
                        val isDropTarget = activeFolderTargetId == item.value.id
                        val isCandidateTarget =
                            candidateFolderTargetId == item.value.id && !isDropTarget
                        val isInvalidTarget = invalidDropTargetKey == item.key
                        Surface(
                            color = if (isInvalidTarget) {
                                MaterialTheme.colorScheme.errorContainer
                            } else if (isDropTarget) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else if (isCandidateTarget) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            border = if (isInvalidTarget) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                            } else if (isDropTarget) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            } else if (isCandidateTarget) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
                            } else {
                                null
                            },
                            shadowElevation = when {
                                isDragging -> 0.dp
                                isDropTarget -> 5.dp
                                isCandidateTarget -> 3.dp
                                else -> 1.dp
                            },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (item.depth * 14).dp, top = 3.dp, bottom = 3.dp)
                                .combinedClickable(
                                    enabled = !dragActive,
                                    onClick = {
                                        collapsedFolderIds = if (item.value.id in collapsedFolderIds) {
                                            collapsedFolderIds - item.value.id
                                        } else {
                                            collapsedFolderIds + item.value.id
                                        }
                                    },
                                    onLongClick = { onFolderLongPress(item.value) },
                                ),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .onGloballyPositioned { coordinates ->
                                            dragHandleBoundsInRoot[item.key] =
                                                coordinates.boundsInRoot()
                                        }
                                        .semantics {
                                            contentDescription = item.value.name
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Default.DragHandle, contentDescription = null)
                                }
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                val folderTargetHint = when {
                                    isDropTarget -> stringResource(R.string.release_to_move_into_folder)
                                    isCandidateTarget -> stringResource(R.string.hold_to_move_into_folder)
                                    else -> null
                                }
                                Text(
                                    text = folderTargetHint?.let { "${item.value.name} · $it" }
                                        ?: item.value.name,
                                    modifier = Modifier.weight(1f),
                                    color = when {
                                        isDropTarget -> MaterialTheme.colorScheme.primary
                                        isCandidateTarget -> MaterialTheme.colorScheme.onSecondaryContainer
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (folderTargetHint != null) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.SemiBold
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (item.value.pinned) {
                                    Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(15.dp))
                                }
                                Icon(
                                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                    is FolderTreeItem.Chat -> {
                        DisposableEffect(item.key) {
                            onDispose { dragHandleBoundsInRoot.remove(item.key) }
                        }
                        val selected = item.history.id == currentId
                        val isChatTarget = activeChatTargetId == item.history.id
                        val isCandidateChatTarget =
                            candidateChatTargetId == item.history.id && !isChatTarget
                        val isInvalidTarget = invalidDropTargetKey == item.key
                        val chatDropLineColor = MaterialTheme.colorScheme.primary
                        Surface(
                            color = if (isInvalidTarget) {
                                MaterialTheme.colorScheme.errorContainer
                            } else if (isChatTarget) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else if (isCandidateChatTarget) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            border = if (isInvalidTarget) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                            } else if (isChatTarget) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            } else if (isCandidateChatTarget) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            } else {
                                null
                            },
                            shadowElevation = 0.dp,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (item.depth * 14).dp, top = 2.dp, bottom = 2.dp)
                                .drawBehind {
                                    if (isChatTarget) {
                                        val strokeWidth = 3.dp.toPx()
                                        val y = if (activeChatDropAfter == true) {
                                            size.height - strokeWidth / 2f
                                        } else {
                                            strokeWidth / 2f
                                        }
                                        drawLine(
                                            color = chatDropLineColor,
                                            start = Offset(0f, y),
                                            end = Offset(size.width, y),
                                            strokeWidth = strokeWidth,
                                        )
                                    }
                                },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerInput(item.history.id, dragActive) {
                                        if (!dragActive) {
                                            detectTapGestures(
                                                onTap = { onSelectChat(item.history.id) },
                                                onLongPress = { onChatLongPress(item.history) },
                                            )
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .onGloballyPositioned { coordinates ->
                                            dragHandleBoundsInRoot[item.key] =
                                                coordinates.boundsInRoot()
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Default.DragHandle, contentDescription = stringResource(R.string.drag_item, item.history.title))
                                }
                                val chatTargetHint = when {
                                    isChatTarget -> stringResource(R.string.release_to_insert_chat_here)
                                    isCandidateChatTarget -> stringResource(R.string.hold_to_insert_chat_here)
                                    else -> null
                                }
                                Text(
                                    text = chatTargetHint?.let { "${item.history.title} · $it" }
                                        ?: item.history.title,
                                    modifier = Modifier.weight(1f),
                                    color = if (isChatTarget) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (chatTargetHint != null) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                )
                                if (scope == ChatFolderScope.FAVORITE) {
                                    Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                if (activeStreamingChatIds.contains(item.history.id)) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                }
                                if (item.history.pinned) {
                                    Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                if (item.history.locked) {
                                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

        if (showRootDropTarget) {
            DisposableEffect(Unit) {
                onDispose { rootDropTargetBoundsInRoot = null }
            }
            val isActive = activeInsertionKey == RootDropTargetKey
            val isInvalid = invalidDropTargetKey == RootDropTargetKey
            Surface(
                color = when {
                    isInvalid -> MaterialTheme.colorScheme.errorContainer
                    isActive -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                border = when {
                    isInvalid -> BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    isActive -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                },
                shadowElevation = if (isActive) 8.dp else 3.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(20f)
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 64.dp, end = 14.dp)
                    .height(44.dp)
                    .onGloballyPositioned { coordinates ->
                        rootDropTargetBoundsInRoot = coordinates.boundsInRoot()
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        tint = if (isInvalid) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    Text(
                        text = stringResource(R.string.move_to_root_level),
                        color = if (isInvalid) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isActive || isInvalid) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Medium
                        },
                    )
                }
            }
        }

        draggedItem?.let { item ->
            val topPadding = if (item is FolderTreeItem.Folder) 3.dp else 2.dp
            val activeInsertion = activeInsertionKey?.let { key ->
                displayItems.firstOrNull { it.key == key } as? FolderTreeItem.FolderInsertion
            }
            val dragHint = when {
                activeInsertionKey == RootDropTargetKey ->
                    stringResource(R.string.move_to_root_level)
                activeInsertion != null -> {
                    val parentName = activeInsertion.parentFolderId?.let { parentId ->
                        visibleFolders.firstOrNull { it.id == parentId }?.name
                    }
                    if (parentName == null) {
                        stringResource(R.string.move_to_root_level)
                    } else {
                        stringResource(R.string.move_to_folder_level, parentName)
                    }
                }
                activeFolderTargetId != null ->
                    stringResource(R.string.release_to_move_into_folder)
                candidateFolderTargetId != null ->
                    stringResource(R.string.hold_to_move_into_folder)
                activeChatTargetId != null ->
                    stringResource(R.string.release_to_insert_chat_here)
                candidateChatTargetId != null ->
                    stringResource(R.string.hold_to_insert_chat_here)
                else -> null
            }
            val isInvalid = invalidDropTargetKey != null
            Surface(
                color = if (isInvalid) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                border = BorderStroke(
                    2.dp,
                    if (isInvalid) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ),
                shadowElevation = 12.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .offset { IntOffset(0, dragOverlayTopPx.roundToInt()) }
                    .zIndex(12f)
                    .fillMaxWidth()
                    .padding(
                        start = 10.dp,
                        end = 70.dp,
                        top = topPadding,
                        bottom = topPadding,
                    )
                    .graphicsLayer { alpha = 0.9f },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 8.dp,
                            vertical = if (item is FolderTreeItem.Folder) 9.dp else 5.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.DragHandle, contentDescription = null)
                    }
                    when (item) {
                        is FolderTreeItem.Folder -> {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = dragHint?.let { "${item.value.name} · $it" }
                                    ?: item.value.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.value.pinned) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                        is FolderTreeItem.Chat -> {
                            Text(
                                text = dragHint?.let { "${item.history.title} · $it" }
                                    ?: item.history.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.history.pinned) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        is FolderTreeItem.FolderInsertion -> Unit
                    }
                }
            }
        }
    }
}
