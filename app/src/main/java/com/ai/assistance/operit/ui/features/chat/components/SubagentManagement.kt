package com.ai.assistance.operit.ui.features.chat.components

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.agent.AgentProfileRepository
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.data.model.SubagentRunStatus
import com.ai.assistance.operit.ui.features.chat.components.part.formatToolExecutionDuration
import com.ai.assistance.operit.ui.features.chat.components.part.resolveSubagentDisplayedTool
import com.ai.assistance.operit.ui.permissions.PermissionReviewOutcome
import com.ai.assistance.operit.ui.permissions.PermissionReviewEvent
import com.ai.assistance.operit.ui.permissions.PermissionReviewEventRepository
import com.ai.assistance.operit.ui.permissions.PermissionReviewFailureKind
import com.ai.assistance.operit.ui.permissions.PermissionReviewAuthorization
import com.ai.assistance.operit.ui.permissions.PermissionReviewRiskLevel
import com.ai.assistance.operit.ui.permissions.PermissionReviewStatus
import com.ai.assistance.operit.ui.permissions.PermissionReviewResponsePolicy
import kotlinx.coroutines.delay

internal enum class SubagentListFilter {
    ALL,
    RUNNING,
    QUEUED,
    COMPLETED,
    AUTO_REVIEW,
    ERROR,
    ARCHIVED,
}

private enum class SubagentManagementPage {
    RUNS,
    RECENT_DENIALS,
}

internal enum class PermissionReviewRunDisplayState {
    ALLOWED,
    DENIED,
    INVALID_OUTPUT,
    CANCELLED_OR_TIMED_OUT,
    ERROR,
}

internal suspend fun resolvePermissionReviewRunDisplayState(
    status: SubagentRunStatus,
    finalAssistantText: String?,
    reviewEvent: PermissionReviewEvent? = null,
): PermissionReviewRunDisplayState? =
    when (reviewEvent?.status) {
        PermissionReviewStatus.APPROVED -> PermissionReviewRunDisplayState.ALLOWED
        PermissionReviewStatus.DENIED -> PermissionReviewRunDisplayState.DENIED
        PermissionReviewStatus.TIMED_OUT,
        PermissionReviewStatus.ABORTED -> PermissionReviewRunDisplayState.CANCELLED_OR_TIMED_OUT
        PermissionReviewStatus.FAILED ->
            if (reviewEvent.failureKind == PermissionReviewFailureKind.INVALID_OUTPUT) {
                PermissionReviewRunDisplayState.INVALID_OUTPUT
            } else {
                PermissionReviewRunDisplayState.ERROR
            }
        PermissionReviewStatus.IN_PROGRESS -> null
        null -> when (status) {
        SubagentRunStatus.COMPLETED -> {
            val decision =
                if (finalAssistantText == null) {
                    null
                } else {
                    PermissionReviewResponsePolicy.extractToolCallAndEnforce(finalAssistantText)
                }
            when (decision?.outcome) {
                PermissionReviewOutcome.ALLOW -> PermissionReviewRunDisplayState.ALLOWED
                PermissionReviewOutcome.DENY -> PermissionReviewRunDisplayState.DENIED
                null -> PermissionReviewRunDisplayState.INVALID_OUTPUT
            }
        }
        SubagentRunStatus.CANCELLED -> PermissionReviewRunDisplayState.CANCELLED_OR_TIMED_OUT
        SubagentRunStatus.FAILED,
        SubagentRunStatus.INTERRUPTED -> PermissionReviewRunDisplayState.ERROR
        SubagentRunStatus.CREATED,
        SubagentRunStatus.QUEUED,
        SubagentRunStatus.RUNNING -> null
        }
    }

internal fun filterAndSortSubagentRuns(
    runs: List<SubagentRunEntity>,
    filter: SubagentListFilter,
    query: String = "",
    autoReviewDisplayName: String = "",
): List<SubagentRunEntity> {
    val normalizedQuery = query.trim()
    val filtered =
        runs.filter { run ->
            val status = run.status.toSubagentRunStatus()
            val isAutoReview =
                run.agentProfileId == AgentProfileRepository.PERMISSION_REVIEWER_ID
            val matchesFilter =
                when (filter) {
                    SubagentListFilter.ALL -> run.archivedAt == null && !isAutoReview
                    SubagentListFilter.RUNNING ->
                        run.archivedAt == null &&
                            !isAutoReview &&
                            (status == SubagentRunStatus.CREATED ||
                                status == SubagentRunStatus.RUNNING)
                    SubagentListFilter.QUEUED ->
                        run.archivedAt == null &&
                            !isAutoReview &&
                            status == SubagentRunStatus.QUEUED
                    SubagentListFilter.COMPLETED ->
                        run.archivedAt == null &&
                            !isAutoReview &&
                            status == SubagentRunStatus.COMPLETED
                    SubagentListFilter.AUTO_REVIEW -> run.archivedAt == null && isAutoReview
                    SubagentListFilter.ERROR ->
                        run.archivedAt == null &&
                            !isAutoReview &&
                            (status == SubagentRunStatus.FAILED ||
                                status == SubagentRunStatus.INTERRUPTED)
                    SubagentListFilter.ARCHIVED -> run.archivedAt != null
                }
            matchesFilter &&
                (normalizedQuery.isEmpty() ||
                    run.agentProfileId.contains(normalizedQuery, ignoreCase = true) ||
                    run.title.contains(normalizedQuery, ignoreCase = true) ||
                    (isAutoReview &&
                        autoReviewDisplayName.contains(normalizedQuery, ignoreCase = true)))
        }
    return filtered.sortedWith(
        compareBy<SubagentRunEntity> {
                if (filter == SubagentListFilter.ARCHIVED) {
                    0
                } else {
                    when (it.status.toSubagentRunStatus()) {
                        SubagentRunStatus.CREATED,
                        SubagentRunStatus.RUNNING -> 0
                        SubagentRunStatus.QUEUED -> 1
                        else -> 2
                    }
                }
            }
            .thenByDescending { it.archivedAt ?: it.createdAt }
            .thenByDescending { it.id }
    )
}

internal fun SubagentRunEntity.isActiveSubagentRun(): Boolean =
    when (status.toSubagentRunStatus()) {
        SubagentRunStatus.CREATED,
        SubagentRunStatus.QUEUED,
        SubagentRunStatus.RUNNING -> true
        else -> false
    }

internal fun initialSubagentListFilter(
    runs: List<SubagentRunEntity>,
    hasPermissionReviewEvents: Boolean,
): SubagentListFilter {
    val hasOrdinaryRuns =
        runs.any { run ->
            run.archivedAt == null &&
                run.agentProfileId != AgentProfileRepository.PERMISSION_REVIEWER_ID
        }
    val hasAutoReviewRecords =
        hasPermissionReviewEvents ||
            runs.any { run ->
                run.archivedAt == null &&
                    run.agentProfileId == AgentProfileRepository.PERMISSION_REVIEWER_ID
            }
    return if (!hasOrdinaryRuns && hasAutoReviewRecords) {
        SubagentListFilter.AUTO_REVIEW
    } else if (!hasOrdinaryRuns && runs.any { it.archivedAt != null }) {
        SubagentListFilter.ARCHIVED
    } else {
        SubagentListFilter.ALL
    }
}

internal fun findPermissionReviewEventForRun(
    events: List<PermissionReviewEvent>,
    run: SubagentRunEntity,
): PermissionReviewEvent? =
    events.lastOrNull { event ->
        event.parentChatId == run.parentChatId &&
            (
                event.reviewerTaskId == run.id ||
                    (
                        event.reviewerTaskId == null &&
                            !run.parentToolCallId.isNullOrBlank() &&
                            event.action.targetId == run.parentToolCallId
                        )
                )
    }

internal fun findSubagentRunForPermissionReviewEvent(
    runs: List<SubagentRunEntity>,
    event: PermissionReviewEvent,
): SubagentRunEntity? =
    runs.firstOrNull { run ->
        run.parentChatId == event.parentChatId &&
            run.agentProfileId == AgentProfileRepository.PERMISSION_REVIEWER_ID &&
            (
                run.id == event.reviewerTaskId ||
                    (
                        event.reviewerTaskId == null &&
                            !run.parentToolCallId.isNullOrBlank() &&
                            run.parentToolCallId == event.action.targetId
                        )
                )
    }

internal fun visiblePermissionReviewEvents(
    runs: List<SubagentRunEntity>,
    events: List<PermissionReviewEvent>,
): List<PermissionReviewEvent> =
    events.filter { event ->
        findSubagentRunForPermissionReviewEvent(runs, event)?.archivedAt == null
    }

private fun String.toSubagentRunStatus(): SubagentRunStatus =
    runCatching { SubagentRunStatus.valueOf(this) }.getOrDefault(SubagentRunStatus.FAILED)

@Composable
internal fun SubagentManageButton(
    hasActiveRun: Boolean,
    reviewCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(32.dp),
    ) {
        Box {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription =
                    if (hasActiveRun && reviewCount > 0) {
                        stringResource(
                            R.string.subagent_manage_active_review_count,
                            reviewCount,
                        )
                    } else if (hasActiveRun) {
                        stringResource(R.string.subagent_manage_active)
                    } else if (reviewCount > 0) {
                        stringResource(R.string.subagent_manage_review_count, reviewCount)
                    } else {
                        stringResource(R.string.subagent_manage)
                    },
                modifier = Modifier.size(20.dp),
            )
            if (hasActiveRun || reviewCount > 0) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (reviewCount > 0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubagentSwitcherSheet(
    runs: List<SubagentRunEntity>,
    currentChildChatId: String,
    onSelect: (SubagentRunEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    remember(context) { PermissionReviewEventRepository.initialize(context); true }
    val reviewEvents by PermissionReviewEventRepository.events.collectAsState()
    val sortedRuns = remember(runs, currentChildChatId) {
        val currentIsAutoReview =
            runs.firstOrNull { it.childChatId == currentChildChatId }?.agentProfileId ==
                AgentProfileRepository.PERMISSION_REVIEWER_ID
        filterAndSortSubagentRuns(
            runs,
            if (currentIsAutoReview) {
                SubagentListFilter.AUTO_REVIEW
            } else {
                SubagentListFilter.ALL
            },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.subagent_switch_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (sortedRuns.isEmpty()) {
            Text(
                text = stringResource(R.string.subagent_manage_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(
                    items = sortedRuns,
                    key = { it.id },
                ) { run ->
                    SubagentRunRow(
                        run = run,
                        reviewEvent = findPermissionReviewEventForRun(reviewEvents, run),
                        selected = run.childChatId == currentChildChatId,
                        showActions = false,
                        onClick = { onSelect(run) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubagentManagementDialog(
    parentChatId: String,
    parentTitle: String,
    runs: List<SubagentRunEntity>,
    currentChildChatId: String?,
    onSelect: (SubagentRunEntity) -> Unit,
    onStop: (SubagentRunEntity) -> Unit,
    onArchive: (SubagentRunEntity) -> Unit,
    onRestore: (SubagentRunEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    remember(context) { PermissionReviewEventRepository.initialize(context); true }
    val autoReviewDisplayName = stringResource(R.string.agent_profile_builtin_permission_reviewer_name)
    val reviewEvents by PermissionReviewEventRepository.events.collectAsState()
    val parentReviewEvents =
        remember(reviewEvents, parentChatId) {
            reviewEvents.filter { event -> event.parentChatId == parentChatId }
        }
    val activeParentReviewEvents =
        remember(parentReviewEvents, runs) {
            visiblePermissionReviewEvents(runs, parentReviewEvents)
        }
    val initialFilter =
        initialSubagentListFilter(
            runs = runs,
            hasPermissionReviewEvents = activeParentReviewEvents.isNotEmpty(),
        )
    var selectedFilter by
        remember(parentChatId) {
            mutableStateOf(
                initialFilter
            )
        }
    var searchQuery by remember(parentChatId) { mutableStateOf("") }
    var currentPage by remember(parentChatId) { mutableStateOf(SubagentManagementPage.RUNS) }
    LaunchedEffect(parentChatId, initialFilter) {
        if (initialFilter != SubagentListFilter.ALL && selectedFilter == SubagentListFilter.ALL) {
            selectedFilter = initialFilter
        }
    }
    val deniedReviewEvents =
        remember(activeParentReviewEvents) {
            activeParentReviewEvents
                .filter { event ->
                    event.status == PermissionReviewStatus.DENIED
                }
                .sortedByDescending { event -> event.completedAt ?: event.startedAt }
        }
    val recentDeniedReviews =
        remember(deniedReviewEvents) {
            deniedReviewEvents
                .distinctBy { event -> event.actionFingerprint }
                .take(10)
        }
    val visibleRuns = remember(runs, selectedFilter, searchQuery, autoReviewDisplayName) {
        filterAndSortSubagentRuns(
            runs,
            selectedFilter,
            searchQuery,
            autoReviewDisplayName = autoReviewDisplayName,
        )
    }
    val orphanReviewEvents =
        remember(activeParentReviewEvents, runs, selectedFilter, searchQuery) {
            if (selectedFilter != SubagentListFilter.AUTO_REVIEW) {
                emptyList()
            } else {
                val normalizedQuery = searchQuery.trim()
                activeParentReviewEvents
                    .asSequence()
                    .filter { event -> findSubagentRunForPermissionReviewEvent(runs, event) == null }
                    .filter { event ->
                        normalizedQuery.isEmpty() ||
                            event.action.toolName.contains(normalizedQuery, ignoreCase = true) ||
                            event.action.summary.contains(normalizedQuery, ignoreCase = true) ||
                            event.rationale.orEmpty().contains(normalizedQuery, ignoreCase = true)
                    }
                    .sortedByDescending { event -> event.completedAt ?: event.startedAt }
                    .toList()
            }
        }
    val counts =
        remember(runs, activeParentReviewEvents) {
            SubagentListFilter.entries.associateWith { filter ->
                val runCount = filterAndSortSubagentRuns(runs, filter).size
                if (filter == SubagentListFilter.AUTO_REVIEW) {
                    val eventsWithoutRuns =
                        activeParentReviewEvents.count { event ->
                            findSubagentRunForPermissionReviewEvent(runs, event) == null
                        }
                    runCount + eventsWithoutRuns
                } else {
                    runCount
                }
            }
        }

    Dialog(
        onDismissRequest = {
            if (currentPage == SubagentManagementPage.RECENT_DENIALS) {
                currentPage = SubagentManagementPage.RUNS
            } else {
                onDismiss()
            }
        },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text =
                                    stringResource(
                                        if (currentPage == SubagentManagementPage.RECENT_DENIALS) {
                                            R.string.permission_review_recent_denials
                                        } else {
                                            R.string.subagent_manage
                                        }
                                    ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = parentTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (currentPage == SubagentManagementPage.RECENT_DENIALS) {
                                    currentPage = SubagentManagementPage.RUNS
                                } else {
                                    onDismiss()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
            ) {
                if (currentPage == SubagentManagementPage.RECENT_DENIALS) {
                    RecentPermissionDenialsPage(
                        deniedEvents = deniedReviewEvents,
                        recentDeniedEvents = recentDeniedReviews,
                        runs = runs,
                        onSelectRun = onSelect,
                    )
                } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item(key = "search") {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            placeholder = {
                                Text(stringResource(R.string.subagent_search_hint))
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription =
                                                stringResource(R.string.clear_search),
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                        )
                    }
                    item(key = "filters") {
                        SubagentFilterRow(
                            selectedFilter = selectedFilter,
                            counts = counts,
                            onSelect = { selectedFilter = it },
                        )
                        HorizontalDivider()
                    }
                    if (selectedFilter == SubagentListFilter.AUTO_REVIEW) {
                        item(key = "recent_denied_reviews") {
                            Card(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .clickable {
                                            currentPage = SubagentManagementPage.RECENT_DENIALS
                                        },
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.permission_review_recent_denials),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.permission_review_recent_denials_summary,
                                            deniedReviewEvents.size,
                                            deniedReviewEvents.distinctBy {
                                                event -> event.actionFingerprint
                                            }.size,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        stringResource(R.string.permission_review_recent_denials_open),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (visibleRuns.isEmpty() && orphanReviewEvents.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text =
                                    if (searchQuery.isNotBlank()) {
                                        stringResource(R.string.subagent_search_empty)
                                    } else if (selectedFilter == SubagentListFilter.ARCHIVED) {
                                        stringResource(R.string.subagent_archived_empty)
                                    } else {
                                        stringResource(R.string.subagent_manage_empty)
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    } else {
                        items(
                            items = visibleRuns,
                            key = { it.id },
                        ) { run ->
                            SubagentRunRow(
                                run = run,
                                reviewEvent =
                                    findPermissionReviewEventForRun(reviewEvents, run),
                                selected = run.childChatId == currentChildChatId,
                                showActions = true,
                                onClick = { onSelect(run) },
                                onStop = { onStop(run) },
                                onArchive = { onArchive(run) },
                                onRestore = { onRestore(run) },
                            )
                        }
                        if (selectedFilter == SubagentListFilter.AUTO_REVIEW) {
                            items(
                                items = orphanReviewEvents,
                                key = { event -> "review-event-${event.id}" },
                            ) { event ->
                                PermissionReviewEventRow(event)
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun permissionReviewStatusText(status: PermissionReviewStatus): String =
    stringResource(
        when (status) {
            PermissionReviewStatus.IN_PROGRESS -> R.string.permission_review_lifecycle_in_progress
            PermissionReviewStatus.APPROVED -> R.string.permission_review_lifecycle_approved
            PermissionReviewStatus.DENIED -> R.string.permission_review_lifecycle_denied
            PermissionReviewStatus.TIMED_OUT -> R.string.permission_review_lifecycle_timed_out
            PermissionReviewStatus.ABORTED -> R.string.permission_review_lifecycle_aborted
            PermissionReviewStatus.FAILED -> R.string.permission_review_lifecycle_failed
        }
    )

@Composable
private fun PermissionReviewEventRow(event: PermissionReviewEvent) {
    var showDetails by remember(event.id) { mutableStateOf(false) }
    val statusText = permissionReviewStatusText(event.status)
    val eventTime = event.completedAt ?: event.startedAt
    val statusColor =
        if (event.status == PermissionReviewStatus.DENIED ||
            event.status == PermissionReviewStatus.FAILED ||
            event.status == PermissionReviewStatus.TIMED_OUT
        ) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }

    Card(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clickable { showDetails = true },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "[${event.batchPosition}/${event.batchSize}] ${event.action.summary}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )
            }
            Text(
                text = event.action.toolName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text =
                    DateUtils.getRelativeTimeSpanString(
                            eventTime,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                        )
                        .toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(stringResource(R.string.permission_review_detail_title)) },
            text = {
                Column(
                    modifier =
                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.permission_review_detail_status, statusText),
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(
                            R.string.permission_review_detail_action,
                            event.action.summary,
                        )
                    )
                    event.riskLevel?.let { risk ->
                        Text(
                            stringResource(
                                R.string.permission_review_detail_risk,
                                stringResource(
                                    when (risk) {
                                        PermissionReviewRiskLevel.LOW ->
                                            R.string.permission_review_value_low
                                        PermissionReviewRiskLevel.MEDIUM ->
                                            R.string.permission_review_value_medium
                                        PermissionReviewRiskLevel.HIGH ->
                                            R.string.permission_review_value_high
                                        PermissionReviewRiskLevel.CRITICAL ->
                                            R.string.permission_review_value_critical
                                    }
                                ),
                            )
                        )
                    }
                    event.userAuthorization?.let { authorization ->
                        Text(
                            stringResource(
                                R.string.permission_review_detail_authorization,
                                stringResource(
                                    when (authorization) {
                                        PermissionReviewAuthorization.UNKNOWN ->
                                            R.string.permission_review_value_unknown
                                        PermissionReviewAuthorization.LOW ->
                                            R.string.permission_review_value_low
                                        PermissionReviewAuthorization.MEDIUM ->
                                            R.string.permission_review_value_medium
                                        PermissionReviewAuthorization.HIGH ->
                                            R.string.permission_review_value_high
                                    }
                                ),
                            )
                        )
                    }
                    event.failureKind?.let { failure ->
                        Text(
                            stringResource(
                                R.string.permission_review_detail_failure,
                                stringResource(
                                    when (failure) {
                                        PermissionReviewFailureKind.INVALID_OUTPUT ->
                                            R.string.permission_review_failure_invalid_output
                                        PermissionReviewFailureKind.TIMED_OUT ->
                                            R.string.permission_review_failure_timed_out
                                        PermissionReviewFailureKind.REVIEWER_ERROR ->
                                            R.string.permission_review_failure_reviewer_error
                                    }
                                ),
                            )
                        )
                    }
                    event.rationale?.takeIf(String::isNotBlank)?.let { rationale ->
                        Text(
                            stringResource(
                                R.string.permission_review_detail_rationale,
                                rationale,
                            )
                        )
                    }
                    Text(
                        stringResource(R.string.permission_review_subagent_unavailable),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun RecentPermissionDenialsPage(
    deniedEvents: List<PermissionReviewEvent>,
    recentDeniedEvents: List<PermissionReviewEvent>,
    runs: List<SubagentRunEntity>,
    onSelectRun: (SubagentRunEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "denial_statistics") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.permission_review_recent_denials_statistics),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PermissionReviewStatisticCard(
                        value = deniedEvents.size.toString(),
                        label = stringResource(R.string.permission_review_denial_total),
                        modifier = Modifier.weight(1f),
                    )
                    PermissionReviewStatisticCard(
                        value =
                            deniedEvents.distinctBy { event -> event.actionFingerprint }
                                .size
                                .toString(),
                        label = stringResource(R.string.permission_review_denial_unique_actions),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = stringResource(R.string.permission_review_recent_denials_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (recentDeniedEvents.isEmpty()) {
            item(key = "recent_denials_empty") {
                Text(
                    text = stringResource(R.string.permission_review_recent_denials_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        } else {
            items(
                items = recentDeniedEvents,
                key = { event -> event.id },
            ) { event ->
                val run = findSubagentRunForPermissionReviewEvent(runs, event)
                val eventTime = event.completedAt ?: event.startedAt
                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable(enabled = run != null) {
                                run?.let(onSelectRun)
                            },
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text =
                                "[${event.batchPosition}/${event.batchSize}] " +
                                    event.action.summary,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = event.action.toolName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        event.rationale?.takeIf(String::isNotBlank)?.let { rationale ->
                            Text(
                                text = rationale,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text =
                                DateUtils.getRelativeTimeSpanString(
                                        eventTime,
                                        System.currentTimeMillis(),
                                        DateUtils.MINUTE_IN_MILLIS,
                                    )
                                    .toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text =
                                stringResource(
                                    if (run != null) {
                                        R.string.permission_review_open_subagent_details
                                    } else {
                                        R.string.permission_review_subagent_unavailable
                                    }
                                ),
                            style = MaterialTheme.typography.labelMedium,
                            color =
                                if (run != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionReviewStatisticCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubagentFilterRow(
    selectedFilter: SubagentListFilter,
    counts: Map<SubagentListFilter, Int>,
    onSelect: (SubagentListFilter) -> Unit,
) {
    val filterRows =
        listOf(
            listOf(
                SubagentListFilter.ALL,
                SubagentListFilter.RUNNING,
                SubagentListFilter.QUEUED,
                SubagentListFilter.COMPLETED,
            ),
            listOf(
                SubagentListFilter.AUTO_REVIEW,
                SubagentListFilter.ERROR,
                SubagentListFilter.ARCHIVED,
            ),
        )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filterRows.forEach { rowFilters ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowFilters.forEach { filter ->
                    val count = counts[filter] ?: 0
                    FilterChip(
                        selected = filter == selectedFilter,
                        onClick = { onSelect(filter) },
                        modifier = Modifier.weight(1f),
                        label = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = subagentFilterLabel(filter),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = count.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun subagentFilterLabel(filter: SubagentListFilter): String =
    when (filter) {
        SubagentListFilter.ALL -> stringResource(R.string.subagent_filter_all)
        SubagentListFilter.RUNNING -> stringResource(R.string.subagent_filter_running)
        SubagentListFilter.QUEUED -> stringResource(R.string.subagent_filter_queued)
        SubagentListFilter.COMPLETED -> stringResource(R.string.subagent_filter_completed)
        SubagentListFilter.AUTO_REVIEW -> stringResource(R.string.subagent_filter_auto_review)
        SubagentListFilter.ERROR -> stringResource(R.string.subagent_filter_error)
        SubagentListFilter.ARCHIVED -> stringResource(R.string.subagent_filter_archived)
    }

@Composable
private fun SubagentRunRow(
    run: SubagentRunEntity,
    reviewEvent: PermissionReviewEvent? = null,
    selected: Boolean,
    showActions: Boolean,
    onClick: () -> Unit,
    onStop: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val chatCore =
        remember(context) {
            ChatRuntimeHolder.getInstance(context.applicationContext)
                .getCore(ChatRuntimeSlot.MAIN)
        }
    val processingStates by chatCore.inputProcessingStateByChatId.collectAsState()
    val lastToolNames by chatCore.lastToolNameByChatId.collectAsState()
    val chatHistories by chatCore.chatHistories.collectAsState()
    val lastTurnToolInvocationCounts by
        chatCore.lastTurnToolInvocationCountByChatId.collectAsState()
    val status = run.status.toSubagentRunStatus()
    val isAutoReview =
        run.agentProfileId == AgentProfileRepository.PERMISSION_REVIEWER_ID
    val finalAssistantText =
        if (isAutoReview) {
            chatHistories
                .firstOrNull { it.id == run.childChatId }
                ?.messages
                ?.lastOrNull { it.sender == "ai" }
                ?.content
        } else {
            null
        }
    val reviewDisplayState by
        produceState<PermissionReviewRunDisplayState?>(
            initialValue = null,
            isAutoReview,
            status,
            finalAssistantText,
            reviewEvent,
        ) {
            value =
                if (isAutoReview) {
                    resolvePermissionReviewRunDisplayState(
                        status,
                        finalAssistantText,
                        reviewEvent,
                    )
                } else {
                    null
                }
        }
    var nowMs by remember(run.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status) {
        while (run.isActiveSubagentRun()) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val currentTool =
        resolveSubagentDisplayedTool(
            childProcessingState = processingStates[run.childChatId],
            lastToolName = lastToolNames[run.childChatId],
        )
    val toolCount =
        maxOf(
            run.toolInvocationCount,
            lastTurnToolInvocationCounts[run.childChatId] ?: 0,
        )
    val duration =
        formatToolExecutionDuration(
            context,
            ((run.completedAt ?: nowMs) - (run.startedAt ?: run.createdAt))
                .coerceAtLeast(0L),
        )
    val statusText =
        reviewDisplayState?.let { permissionReviewRunStatusText(it) }
            ?: subagentRunStatusText(
                status = status,
                currentTool = currentTool,
                toolCount = toolCount,
            )
    val statusColor =
        when (reviewDisplayState) {
            PermissionReviewRunDisplayState.ALLOWED -> MaterialTheme.colorScheme.primary
            PermissionReviewRunDisplayState.DENIED -> MaterialTheme.colorScheme.tertiary
            PermissionReviewRunDisplayState.INVALID_OUTPUT,
            PermissionReviewRunDisplayState.CANCELLED_OR_TIMED_OUT,
            PermissionReviewRunDisplayState.ERROR -> MaterialTheme.colorScheme.error
            null -> when (status) {
            SubagentRunStatus.FAILED,
            SubagentRunStatus.INTERRUPTED -> MaterialTheme.colorScheme.error
            SubagentRunStatus.RUNNING -> MaterialTheme.colorScheme.primary
            SubagentRunStatus.QUEUED -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        }
    var showMenu by remember(run.id) { mutableStateOf(false) }

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        Color.Transparent
                    }
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(statusColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        if (isAutoReview) {
                            stringResource(R.string.agent_profile_builtin_permission_reviewer_name)
                        } else {
                            run.agentProfileId.ifBlank { "subagent" }
                        },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = run.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$statusText · $duration",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showActions) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.subagent_more_options),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        if (run.isActiveSubagentRun()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.subagent_stop_task)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    onStop?.invoke()
                                },
                            )
                        } else if (run.archivedAt == null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.subagent_archive)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Archive, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    onArchive?.invoke()
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.subagent_restore)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Restore, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    onRestore?.invoke()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun permissionReviewRunStatusText(state: PermissionReviewRunDisplayState): String =
    when (state) {
        PermissionReviewRunDisplayState.ALLOWED ->
            stringResource(R.string.permission_review_status_allowed)
        PermissionReviewRunDisplayState.DENIED ->
            stringResource(R.string.permission_review_status_denied)
        PermissionReviewRunDisplayState.INVALID_OUTPUT ->
            stringResource(R.string.permission_review_status_invalid)
        PermissionReviewRunDisplayState.CANCELLED_OR_TIMED_OUT ->
            stringResource(R.string.permission_review_status_timeout_or_cancelled)
        PermissionReviewRunDisplayState.ERROR ->
            stringResource(R.string.permission_review_status_error)
    }

@Composable
private fun subagentRunStatusText(
    status: SubagentRunStatus,
    currentTool: String?,
    toolCount: Int,
): String =
    when (status) {
        SubagentRunStatus.CREATED -> stringResource(R.string.subagent_status_creating)
        SubagentRunStatus.QUEUED -> stringResource(R.string.subagent_filter_queued)
        SubagentRunStatus.RUNNING ->
            if (currentTool.isNullOrBlank()) {
                stringResource(R.string.subagent_status_thinking)
            } else {
                stringResource(R.string.subagent_status_calling_tool, currentTool)
            }
        SubagentRunStatus.COMPLETED ->
            if (toolCount > 0) {
                stringResource(
                    R.string.subagent_status_completed_with_tool_count,
                    toolCount,
                )
            } else {
                stringResource(R.string.subagent_status_completed)
            }
        SubagentRunStatus.CANCELLED -> stringResource(R.string.subagent_status_cancelled)
        SubagentRunStatus.FAILED,
        SubagentRunStatus.INTERRUPTED -> stringResource(R.string.subagent_status_error)
    }

@Composable
internal fun SubagentHeaderTitle(
    parentTitle: String,
    agentName: String,
    taskTitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = parentTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = " / ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = agentName.ifBlank { "subagent" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = stringResource(R.string.subagent_switch_title),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = taskTitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
