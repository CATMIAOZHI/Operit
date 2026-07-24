package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val treeItems = remember(folders, placements, histories, collapsedFolderIds, matchingHistoryIds) {
        val historiesById = histories.associateBy { it.id }
        val foldersByParent = folders.groupBy { it.parentFolderId }
        val placementsByFolder = placements.groupBy { it.folderId }
        val result = mutableListOf<FolderTreeItem>()
        val visited = mutableSetOf<String>()

        fun appendFolder(folder: ChatFolderEntity, depth: Int) {
            if (!visited.add(folder.id)) return
            result += FolderTreeItem.Folder(folder, depth)
            if (folder.id in collapsedFolderIds) return
            foldersByParent[folder.id].orEmpty()
                .sortedWith(compareByDescending<ChatFolderEntity> { it.pinned }.thenBy { it.displayOrder })
                .forEach { appendFolder(it, depth + 1) }
            placementsByFolder[folder.id].orEmpty()
                .sortedBy { it.displayOrder }
                .forEach { placement ->
                    historiesById[placement.chatId]
                        ?.takeIf { it.id in matchingHistoryIds }
                        ?.let { result += FolderTreeItem.Chat(it, placement, depth + 1) }
                }
        }

        foldersByParent[null].orEmpty()
            .sortedWith(compareByDescending<ChatFolderEntity> { it.pinned }.thenBy { it.displayOrder })
            .forEach { appendFolder(it, 0) }
        placementsByFolder[null].orEmpty()
            .sortedBy { it.displayOrder }
            .forEach { placement ->
                historiesById[placement.chatId]
                    ?.takeIf { it.id in matchingHistoryIds }
                    ?.let { result += FolderTreeItem.Chat(it, placement, 0) }
            }
        result
    }

    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val moved = treeItems.getOrNull(from.index) ?: return@rememberReorderableLazyListState
        val target = treeItems.getOrNull(to.index) ?: return@rememberReorderableLazyListState
        coroutineScope.launch {
            runCatching {
                when (moved) {
                    is FolderTreeItem.Folder -> {
                        val targetParentId = when {
                            target is FolderTreeItem.Folder &&
                                target.value.parentFolderId == moved.value.parentFolderId &&
                                target.value.id !in collapsedFolderIds -> {
                                target.value.parentFolderId
                            }
                            target is FolderTreeItem.Folder -> target.value.id
                            target is FolderTreeItem.Chat -> target.placement.folderId
                            else -> null
                        }
                        val siblingFolders = folders
                            .filter {
                                it.parentFolderId == targetParentId && it.id != moved.value.id
                            }
                            .sortedWith(
                                compareByDescending<ChatFolderEntity> { it.pinned }
                                    .thenBy { it.displayOrder }
                            )
                        val targetIndex = when {
                            target is FolderTreeItem.Folder && target.value.parentFolderId == targetParentId -> {
                                siblingFolders.indexOfFirst { it.id == target.value.id }
                                    .takeIf { it >= 0 } ?: siblingFolders.size
                            }
                            else -> siblingFolders.size
                        }
                        manager.moveFolder(moved.value.id, targetParentId, targetIndex)
                        if (target is FolderTreeItem.Folder && targetParentId == target.value.id) {
                            collapsedFolderIds = collapsedFolderIds - target.value.id
                        }
                    }
                    is FolderTreeItem.Chat -> {
                        val targetFolderId = when (target) {
                            is FolderTreeItem.Folder -> target.value.id
                            is FolderTreeItem.Chat -> target.placement.folderId
                        }
                        val siblingIds = placements
                            .filter { it.folderId == targetFolderId }
                            .sortedBy { it.displayOrder }
                            .map { it.chatId }
                            .filter { it != moved.history.id }
                        val targetIndex = when (target) {
                            is FolderTreeItem.Folder -> siblingIds.size
                            is FolderTreeItem.Chat -> siblingIds.indexOf(target.history.id)
                                .takeIf { it >= 0 } ?: siblingIds.size
                        }
                        manager.moveChat(moved.history.id, scope, targetFolderId, targetIndex)
                        if (target is FolderTreeItem.Folder) {
                            collapsedFolderIds = collapsedFolderIds - target.value.id
                        }
                    }
                }
            }
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 22.dp),
    ) {
        items(treeItems, key = { it.key }) { item ->
            ReorderableItem(reorderableState, key = item.key) { isDragging ->
                when (item) {
                    is FolderTreeItem.Folder -> {
                        val expanded = item.value.id !in collapsedFolderIds
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shadowElevation = if (isDragging) 8.dp else 1.dp,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (item.depth * 14).dp, top = 3.dp, bottom = 3.dp)
                                .pointerInput(item.value.id) {
                                    detectTapGestures(
                                        onTap = {
                                            collapsedFolderIds = if (expanded) {
                                                collapsedFolderIds + item.value.id
                                            } else {
                                                collapsedFolderIds - item.value.id
                                            }
                                        },
                                        onLongPress = { onFolderLongPress(item.value) },
                                    )
                                },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier
                                        .size(36.dp)
                                        .draggableHandle(enabled = draggingEnabled)
                                        .semantics {
                                            contentDescription = item.value.name
                                        },
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
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier
                                        .size(36.dp)
                                        .draggableHandle(enabled = draggingEnabled),
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
