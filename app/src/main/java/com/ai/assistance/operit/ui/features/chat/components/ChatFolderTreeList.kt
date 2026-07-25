package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatFolderEntity
import com.ai.assistance.operit.data.model.ChatFolderScope
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ChatPlacementEntity
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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
        override val key = "folder-insertion:${parentFolderId ?: "root"}:$siblingIndex"
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
    val coroutineScope = rememberCoroutineScope()
    var draftFolders by remember(scope) { mutableStateOf<List<ChatFolderEntity>>(emptyList()) }
    var draftPlacements by remember(scope) { mutableStateOf<List<ChatPlacementEntity>>(emptyList()) }
    var useDraft by remember(scope) { mutableStateOf(false) }
    var dragActive by remember(scope) { mutableStateOf(false) }
    var commitInProgress by remember(scope) { mutableStateOf(false) }
    var draggingFolderId by remember(scope) { mutableStateOf<String?>(null) }
    var activeInsertionKey by remember(scope) { mutableStateOf<String?>(null) }
    var activeFolderTargetId by remember(scope) { mutableStateOf<String?>(null) }
    val pendingMove = remember(scope) { mutableStateOf<PendingTreeMove?>(null) }
    val visibleFolders = if (useDraft) draftFolders else folders
    val visiblePlacements = if (useDraft) draftPlacements else placements
    val forbiddenFolderDropIds = remember(visibleFolders, draggingFolderId) {
        val draggedId = draggingFolderId ?: return@remember emptySet()
        visibleFolders.mapNotNullTo(mutableSetOf()) { folder ->
            val isDescendant = generateSequence(folder.parentFolderId) { parentId ->
                visibleFolders.firstOrNull { it.id == parentId }?.parentFolderId
            }.any { it == draggedId }
            folder.id.takeIf { isDescendant }
        } + draggedId
    }
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
            commitInProgress = false
            pendingMove.value = null
        }
    }

    fun beginDrag(folderId: String? = null) {
        if (!useDraft) {
            draftFolders = folders
            draftPlacements = placements
        }
        useDraft = true
        dragActive = true
        draggingFolderId = folderId
        activeInsertionKey = null
        activeFolderTargetId = null
        pendingMove.value = null
    }

    fun finishDrag() {
        dragActive = false
        draggingFolderId = null
        val move = pendingMove.value
        if (move == null) {
            useDraft = false
            activeInsertionKey = null
            activeFolderTargetId = null
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
        pendingMove.value = null
        coroutineScope.launch {
            runCatching {
                when (move) {
                    is PendingTreeMove.Folder -> manager.moveFolder(move.id, move.parentId, move.index)
                    is PendingTreeMove.Chat -> manager.moveChat(move.id, scope, move.parentId, move.index)
                }
            }.onFailure {
                useDraft = false
            }.also {
                commitInProgress = false
                activeInsertionKey = null
                activeFolderTargetId = null
            }
        }
    }

    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val moved = treeItems.getOrNull(from.index) ?: return@rememberReorderableLazyListState
        val target = treeItems.getOrNull(to.index) ?: return@rememberReorderableLazyListState
        when (moved) {
            is FolderTreeItem.Folder -> {
                val targetParentId = when (target) {
                    is FolderTreeItem.Folder -> target.value.id
                    is FolderTreeItem.Chat -> target.placement.folderId
                    is FolderTreeItem.FolderInsertion -> target.parentFolderId
                }
                val targetIsDescendant = generateSequence(targetParentId) { id ->
                    draftFolders.firstOrNull { it.id == id }?.parentFolderId
                }.any { it == moved.value.id }
                if (targetIsDescendant) return@rememberReorderableLazyListState

                val currentSiblings = visibleFolders
                    .filter { it.parentFolderId == targetParentId }
                    .sortedWith(compareByDescending<ChatFolderEntity> { it.pinned }.thenBy { it.displayOrder })
                val withoutMoved = currentSiblings.filter { it.id != moved.value.id }.toMutableList()
                val destination = when (target) {
                    is FolderTreeItem.FolderInsertion -> {
                        val sourceIndex = currentSiblings.indexOfFirst { it.id == moved.value.id }
                        val adjustedIndex = if (
                            moved.value.parentFolderId == targetParentId &&
                                sourceIndex >= 0 && sourceIndex < target.siblingIndex
                        ) {
                            target.siblingIndex - 1
                        } else {
                            target.siblingIndex
                        }
                        adjustedIndex.coerceIn(0, withoutMoved.size)
                    }
                    else -> withoutMoved.size
                }
                pendingMove.value = PendingTreeMove.Folder(moved.value.id, targetParentId, destination)
                activeInsertionKey = (target as? FolderTreeItem.FolderInsertion)?.key
                activeFolderTargetId = (target as? FolderTreeItem.Folder)?.value?.id
            }
            is FolderTreeItem.Chat -> {
                val targetFolderId = when (target) {
                    is FolderTreeItem.Folder -> target.value.id
                    is FolderTreeItem.Chat -> target.placement.folderId
                    is FolderTreeItem.FolderInsertion -> target.parentFolderId
                }
                val currentSiblings = visiblePlacements
                    .filter { it.folderId == targetFolderId }
                    .sortedBy { it.displayOrder }
                val withoutMoved = currentSiblings.filter { it.chatId != moved.history.id }
                val destination = if (target is FolderTreeItem.Chat) {
                    val sourceIndex = currentSiblings.indexOfFirst { it.chatId == moved.history.id }
                    val targetIndex = currentSiblings.indexOfFirst { it.chatId == target.history.id }
                    if (targetIndex < 0) {
                        withoutMoved.size
                    } else if (
                        moved.placement.folderId == targetFolderId &&
                            sourceIndex >= 0 &&
                            sourceIndex < targetIndex
                    ) {
                        targetIndex - 1
                    } else {
                        targetIndex
                    }
                } else {
                    withoutMoved.size
                }
                pendingMove.value = PendingTreeMove.Chat(
                    moved.history.id,
                    targetFolderId,
                    destination.coerceIn(0, withoutMoved.size),
                )
                activeInsertionKey = null
                activeFolderTargetId = (target as? FolderTreeItem.Folder)?.value?.id
            }
            is FolderTreeItem.FolderInsertion -> return@rememberReorderableLazyListState
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 22.dp),
    ) {
        items(treeItems, key = { it.key }) { item ->
            ReorderableItem(
                reorderableState,
                key = item.key,
                enabled = when (item) {
                    is FolderTreeItem.FolderInsertion -> dragActive && draggingFolderId != null &&
                        item.parentFolderId !in forbiddenFolderDropIds
                    is FolderTreeItem.Folder -> item.value.id !in
                        (forbiddenFolderDropIds - draggingFolderId)
                    is FolderTreeItem.Chat -> true
                },
            ) { isDragging ->
                when (item) {
                    is FolderTreeItem.FolderInsertion -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(7.dp)
                                .padding(start = (item.depth * 14 + 8).dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (activeInsertionKey == item.key) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }
                    is FolderTreeItem.Folder -> {
                        val expanded = item.value.id !in collapsedFolderIds
                        val isDropTarget = activeFolderTargetId == item.value.id
                        Surface(
                            color = if (isDropTarget) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            border = if (isDropTarget) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                null
                            },
                            shadowElevation = if (isDragging) 8.dp else if (isDropTarget) 4.dp else 1.dp,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (item.depth * 14).dp, top = 3.dp, bottom = 3.dp)
                                .combinedClickable(
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
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .draggableHandle(
                                            enabled = draggingEnabled && !commitInProgress,
                                            onDragStarted = { beginDrag(item.value.id) },
                                            onDragStopped = { finishDrag() },
                                        )
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
                                Text(
                                    item.value.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (item.value.pinned) {
                                    Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(15.dp))
                                }
                                if (isDropTarget) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.release_to_move_into_folder),
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                    )
                                }
                                Icon(
                                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
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
                            shadowElevation = if (isDragging) 8.dp else 0.dp,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (item.depth * 14).dp, top = 2.dp, bottom = 2.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerInput(item.history.id) {
                                        detectTapGestures(
                                            onTap = { onSelectChat(item.history.id) },
                                            onLongPress = { onChatLongPress(item.history) },
                                        )
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .draggableHandle(
                                            enabled = draggingEnabled && !commitInProgress,
                                            onDragStarted = { beginDrag() },
                                            onDragStopped = { finishDrag() },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Default.DragHandle, contentDescription = stringResource(R.string.drag_item, item.history.title))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.history.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
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
}
