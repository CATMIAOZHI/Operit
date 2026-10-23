package com.ai.assistance.operit.ui.features.orbit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.OrbitActionEntity
import com.ai.assistance.operit.data.model.OrbitDeliveryEntity
import com.ai.assistance.operit.data.model.OrbitRunEntity
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.model.OrbitTaskInputEntity
import com.ai.assistance.operit.features.orbit.OrbitRoutines
import com.ai.assistance.operit.features.orbit.OrbitGeofence
import com.ai.assistance.operit.features.orbit.OrbitGeofenceDefinition
import com.ai.assistance.operit.features.orbit.OrbitChatDecision
import com.ai.assistance.operit.features.orbit.OrbitHookChecks
import com.ai.assistance.operit.features.orbit.OrbitTaskRepository
import com.ai.assistance.operit.features.orbit.OrbitTaskScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/*
 * An Orbit task in the profile's look: a round back button instead of a top bar, quiet gray
 * cards, round icons, and one dark button for the main action. Opened from the schedule list or
 * a goal it shows one task; with no task given it lists them all in groups.
 */

private val TaskCardShape = RoundedCornerShape(20.dp)
private val TaskCardTop = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private val TaskCardBottom = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)

@Composable
private fun taskCardColor(): Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitTasksPage(
    orbitId: String,
    onBack: () -> Unit,
    initialTaskId: String? = null,
    /** Puts a prompt in the main chat's input box and shows the chat; nothing is sent. */
    onDraft: (String) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { OrbitTaskRepository(context) }
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable(orbitId) { mutableStateOf(initialTaskId) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    // The task id is kept with the choice, so the answer lands on that task even if the page
    // has started going back to the list.
    var resolveReceipt by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    // The task whose run history is open.
    var historyTask by remember { mutableStateOf<String?>(null) }
    // Results of a button press show next to the buttons, where the user is looking.
    var actionError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val tasks by produceState<List<OrbitTaskEntity>?>(null, orbitId, retry) {
        value = null
        try { repository.dao.observeTasks(orbitId).collect { value = it } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = true }
    }
    // A run put off (busy chat, quiet hours) waits on its event; that is the real next time.
    val dues by produceState(emptyMap<String, Long>(), orbitId, retry) {
        try { repository.dao.observeTaskDues(orbitId).collect { rows -> value = rows.associate { it.taskId to it.dueAt } } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { }
    }
    // Each task's newest run, so the list says how it last went (a skipped review included).
    val latestRuns by produceState(emptyMap<String, OrbitRunEntity>(), orbitId, retry) {
        try { repository.dao.observeLatestRuns(orbitId).collect { rows -> value = rows.associateBy { it.taskId } } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { }
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        actionError = null
        notice = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { actionError = orbitActionError(context, e) }
            finally { busy = false }
        }
    }
    // A message from one task's buttons does not follow the user to another task.
    fun clearFeedback() { actionError = null; notice = null }
    fun back() { clearFeedback(); if (selected != null && initialTaskId == null) selected = null else onBack() }
    // An open task in the list is left by the push host below, with the back swipe.
    OrbitPageBack { back() }

    val listState = rememberLazyListState()
    val errorBanner: LazyListScope.() -> Unit = {
        if (error) item(key = "error") {
            TaskErrorCard(errorDetail) { error = false; errorDetail = null; retry++ }
        }
    }
    val feedback: LazyListScope.() -> Unit = {
        actionError?.let { detail ->
            item(key = "action-error") {
                TaskErrorCard(detail, stringResource(R.string.orbit_task_dismiss)) { actionError = null }
            }
        }
        notice?.let { text ->
            item(key = "notice") {
                TaskCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(orbitIcon("circle-check"), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
    val pageColor = MaterialTheme.colorScheme.background
    val detail: @Composable (String) -> Unit = { shown ->
        Box(Modifier.fillMaxSize().background(pageColor)) {
            key(shown) {
            TaskDetail(
                orbitId = orbitId,
                taskId = shown,
                tasks = tasks,
                dueAt = dues[shown],
                repository = repository,
                busy = busy,
                retry = retry,
                onError = { error = true },
                onBack = ::back,
                errorBanner = errorBanner,
                loadFailed = error,
                feedback = feedback,
                action = ::action,
                onNotice = { notice = it },
                // Pausing, resuming and cancelling go through Orbit: the request waits in the main
                // chat's input box for the user to send.
                onCancel = { onDraft(context.getString(R.string.orbit_task_cancel_draft, it.title)) },
                onResolve = { id, completed -> resolveReceipt = Triple(shown, id, completed) },
                onRecord = { id -> historyTask = id },
                onDraft = onDraft,
            )
            }
        }
    }
    Box(Modifier.fillMaxSize().background(pageColor)) {
        if (initialTaskId != null) {
            // Opened on one task: back leaves the page.
            detail(initialTaskId)
        } else {
            // Opening a task pushes its page in from the right over the list, like iOS; the back
            // swipe slides it away under the finger.
            OrbitPushHost(
                entries = listOfNotNull(selected),
                onPop = { clearFeedback(); selected = null },
                base = {
                    Box(Modifier.fillMaxSize().background(pageColor)) {
                        TaskList(rememberOrbitName(orbitId), tasks, dues, latestRuns, listState, ::back, errorBanner, loadFailed = error) { clearFeedback(); selected = it }
                    }
                },
                page = detail,
            )
        }
    }

    historyTask?.let { id ->
        tasks?.firstOrNull { it.id == id }?.let { task ->
            RunHistorySheet(orbitId, task, repository) { historyTask = null }
        }
    }
    resolveReceipt?.let { (taskId, id, completed) ->
        val label = stringResource(if (completed) R.string.orbit_action_verified else R.string.orbit_action_retry_allowed)
        TaskConfirmSheet(
            icon = if (completed) "circle-check" else "rotate-ccw",
            title = label,
            message = stringResource(R.string.orbit_action_verify_confirm),
            confirmText = label,
            dismissText = stringResource(R.string.cancel),
            destructive = false,
            onDismiss = { resolveReceipt = null },
        ) {
            resolveReceipt = null
            action { repository.resolveAction(orbitId, taskId, id, completed) }
        }
    }
}

/** How one run went, in a line: the skipped, unchanged and in-chat runs in words. */
@Composable
private fun runOutcome(run: OrbitRunEntity, task: OrbitTaskEntity): String {
    val context = LocalContext.current
    val text = when {
        run.state == "RUNNING" -> stringResource(R.string.orbit_task_running)
        run.state == "CANCELLED" -> stringResource(R.string.orbit_run_cancelled)
        run.error.isNotBlank() -> run.error
        run.state == "FAILED" -> stringResource(R.string.orbit_run_failed_unknown)
        run.decision == OrbitRunEntity.SKIPPED -> OrbitRoutines.skippedReason(context, task)
        run.decision == OrbitRunEntity.UNCHANGED -> stringResource(R.string.orbit_decision_unchanged)
        OrbitChatDecision.isChat(run.decision) -> OrbitChatDecision.describe(context, run.decision, rememberOrbitName(task.orbitId))
        run.observation.isNotBlank() -> run.observation
        run.decision.isNotBlank() -> run.decision
        else -> stringResource(R.string.orbit_task_no_result)
    }
    return OrbitWork.listLine(text)
}

private fun runIcon(run: OrbitRunEntity): String = when {
    run.state == "RUNNING" -> "loader"
    run.state == "CANCELLED" -> "circle-slash"
    run.state == "FAILED" || run.error.isNotBlank() -> "circle-alert"
    run.decision == OrbitRunEntity.SKIPPED -> "skip-forward"
    OrbitChatDecision.isChat(run.decision) -> "message-circle"
    else -> "circle-check"
}

/**
 * Every run of one task, newest first. A run opens to what it saw and, when it ran in the
 * background, its full transcript; a skipped or in-chat run says why there is none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunHistorySheet(orbitId: String, task: OrbitTaskEntity, repository: OrbitTaskRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // A read that fails says so with a retry; it is never shown as "no runs" or "no record".
    var reload by remember { mutableIntStateOf(0) }
    var listFailed by remember { mutableStateOf<String?>(null) }
    var recordFailed by remember { mutableStateOf<String?>(null) }
    val runs by produceState<List<OrbitRunEntity>?>(null, task.id, reload) {
        listFailed = null
        try { repository.dao.observeRuns(task.id, 50).collect { value = it } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { listFailed = e.message ?: e.toString() }
    }
    var openRunId by remember(task.id) { mutableStateOf<String?>(null) }
    val openRun = openRunId?.let { id -> runs?.firstOrNull { it.id == id } }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    // Kept outside the list, so coming back from one run returns to where the user was.
    val historyState = rememberLazyListState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (openRun == null) {
                Text(stringResource(R.string.orbit_run_history_title), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                Text(task.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val list = runs
                when {
                    listFailed != null -> TaskErrorCard(listFailed) { reload++ }
                    list == null -> TaskLoading()
                    list.isEmpty() -> EmptyHint(stringResource(R.string.orbit_run_history_empty))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), state = historyState) {
                        items(list, key = { it.id }, contentType = { "run" }) { run ->
                            val failed = run.state == "FAILED" || run.error.isNotBlank()
                            OrbitListRow(
                                icon = orbitIcon(runIcon(run)),
                                title = format.format(Date(run.startedAt)),
                                detail = runOutcome(run, task),
                                meta = relativeTime(run.finishedAt.takeIf { it > 0 } ?: run.startedAt),
                                iconTint = if (failed) com.ai.assistance.operit.ui.common.readableErrorColor()
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                showChevron = true,
                                onClick = { openRunId = run.id },
                            )
                        }
                    }
                }
            } else {
                BackHandler { openRunId = null }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { openRunId = null }) {
                        Icon(orbitIcon("arrow-left"), stringResource(R.string.back))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(format.format(Date(openRun.startedAt)), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(runOutcome(openRun, task), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                val record by produceState<String?>(null, openRun.id, openRun.state, reload) {
                    recordFailed = null
                    value = try { repository.runRecord(task.id, openRun.id) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { recordFailed = e.message ?: e.toString(); null }
                }
                val body = record
                if (body == null && recordFailed != null) TaskErrorCard(recordFailed) { reload++ }
                else Surface(shape = RoundedCornerShape(16.dp), color = taskCardColor(), modifier = Modifier.fillMaxWidth()) {
                    if (body == null) TaskLoading()
                    else SelectionContainer {
                        Text(
                            body.ifBlank {
                                // No background transcript: what the run saw, and why there is nothing more.
                                listOf(
                                    openRun.error.ifBlank { openRun.observation.takeIf { openRun.decision != OrbitRunEntity.SKIPPED }.orEmpty() },
                                    if (OrbitChatDecision.isChat(openRun.decision))
                                        OrbitChatDecision.describe(context, openRun.decision, rememberOrbitName(orbitId)) + "\n" +
                                            stringResource(R.string.orbit_run_record_in_chat, rememberOrbitName(orbitId))
                                    else stringResource(R.string.orbit_run_record_none, rememberOrbitName(orbitId)),
                                ).filter { it.isNotBlank() }.joinToString("\n\n")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A confirmation as a bottom sheet: a round icon, what will happen, and two full-width pills. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskConfirmSheet(
    icon: String,
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TaskIcon(icon, size = 56.dp, iconSize = 26.dp, background = taskCardColor())
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
            Spacer(Modifier.height(4.dp))
            TaskButton(confirmText, icon, primary = !destructive, destructive = destructive, onClick = onConfirm)
            TaskButton(dismissText, "x", onClick = onDismiss)
        }
    }
}

@Composable
private fun TaskList(
    who: String,
    tasks: List<OrbitTaskEntity>?,
    dues: Map<String, Long>,
    latestRuns: Map<String, OrbitRunEntity>,
    listState: LazyListState,
    onBack: () -> Unit,
    errorBanner: LazyListScope.() -> Unit,
    loadFailed: Boolean,
    onOpen: (String) -> Unit,
) {
    val groups = remember(tasks) {
        val all = tasks.orEmpty()
        listOf(
            R.string.orbit_tasks_group_attention to all.filter { it.state == "WAITING_USER" || it.state == "VERIFY" },
            R.string.orbit_tasks_group_active to all.filter { it.state == "ACTIVE" },
            R.string.orbit_tasks_group_paused to all.filter { it.state == "PAUSED" },
            R.string.orbit_tasks_group_finished to all.filter { it.state == "DONE" || it.state == "CANCELLED" },
        ).filter { it.second.isNotEmpty() }
    }
    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
    ) {
        item(key = "header") {
            OrbitPageHeader(stringResource(R.string.orbit_tasks_title), onBack)
            Text(stringResource(R.string.orbit_tasks_help, who), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        errorBanner()
        if (tasks == null && !loadFailed) item(key = "loading") { TaskLoading() }
        else if (tasks != null && tasks.isEmpty()) item(key = "empty") { EmptyHint(stringResource(R.string.orbit_tasks_empty, who)) }
        groups.forEach { (title, group) ->
            item(key = "group-$title") { SectionTitle(stringResource(title)) }
            itemsIndexed(group, key = { _, task -> task.id }, contentType = { _, _ -> "task" }) { index, task ->
                val shape: Shape = when {
                    group.size == 1 -> TaskCardShape
                    index == 0 -> TaskCardTop
                    index == group.lastIndex -> TaskCardBottom
                    else -> RectangleShape
                }
                Column(Modifier.fillMaxWidth().padding(top = if (index == 0) 6.dp else 0.dp)
                    .background(taskCardColor(), shape)) {
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 70.dp, end = 16.dp), color = divider)
                    TaskListRow(task, tasks.orEmpty(), dues[task.id], latestRuns[task.id], shape) { onOpen(task.id) }
                }
            }
        }
    }
}

@Composable
private fun TaskListRow(task: OrbitTaskEntity, tasks: List<OrbitTaskEntity>, dueAt: Long?, lastRun: OrbitRunEntity?,
    shape: Shape, onClick: () -> Unit) {
    val icon = taskScheduleIcon(task)
    Row(
        Modifier.fillMaxWidth().clip(shape).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskIcon(taskIconName(task, icon))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (task.state == "ACTIVE") taskSchedule(task, tasks, dueAt) else taskState(task),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            // How the last run went, so a quiet "nothing to review" still shows the task ran.
            lastRun?.let { run ->
                val time = run.finishedAt.takeIf { it > 0 } ?: run.startedAt
                Text(stringResource(R.string.orbit_task_last_run_line, relativeTime(time), runOutcome(run, task)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(orbitIcon("chevron-right"), null, Modifier.padding(start = 8.dp).size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun TaskDetail(
    orbitId: String,
    taskId: String,
    tasks: List<OrbitTaskEntity>?,
    dueAt: Long?,
    repository: OrbitTaskRepository,
    busy: Boolean,
    retry: Int,
    onError: () -> Unit,
    onBack: () -> Unit,
    errorBanner: LazyListScope.() -> Unit,
    loadFailed: Boolean,
    feedback: LazyListScope.() -> Unit,
    action: (suspend () -> Unit) -> Unit,
    onNotice: (String) -> Unit,
    onCancel: (OrbitTaskEntity) -> Unit,
    onResolve: (String, Boolean) -> Unit,
    onRecord: (String) -> Unit,
    onDraft: (String) -> Unit,
) {
    val context = LocalContext.current
    val task = tasks?.firstOrNull { it.id == taskId }
    var userNote by rememberSaveable(taskId) { mutableStateOf("") }
    val lastRun by produceState<OrbitRunEntity?>(null, taskId, retry) {
        try { repository.dao.observeLatestRun(taskId).collect { run -> value = run } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { onError() }
    }
    // What Orbit did with the run's draft: told the user or left it out, and why.
    val lastItem by produceState<OrbitDeliveryEntity?>(null, lastRun?.id) {
        val runId = lastRun?.id ?: return@produceState
        try { repository.dao.observeDeliveryOfRun(runId).collect { value = it } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { value = null }
    }
    val receipts by produceState(emptyList<OrbitActionEntity>(), taskId) {
        try { repository.dao.observeActions(taskId).collect { rows -> value = rows } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { onError() }
    }
    val input by produceState<OrbitTaskInputEntity?>(null, taskId, task?.version, task?.state) {
        value = if (task == null) null else repository.dao.input(taskId)
    }
    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") { OrbitPageHeader(stringResource(R.string.orbit_task_detail_title), onBack) }
        errorBanner()
        if (task == null) {
            item(key = "missing") {
                if (tasks != null) EmptyHint(stringResource(R.string.orbit_task_missing)) else if (!loadFailed) TaskLoading()
            }
            return@LazyColumn
        }
        item(key = "hero") { TaskHero(task) }
        item(key = "about") {
            val routine = OrbitRoutines.isRoutine(task)
            TaskCard {
                Text(
                    when {
                        routine && task.source == OrbitRoutines.GOALS_SOURCE -> stringResource(R.string.orbit_routine_goal_briefing_desc, rememberOrbitName(orbitId))
                        routine && task.source == OrbitRoutines.FEED_SOURCE -> stringResource(R.string.orbit_routine_feed_desc, rememberOrbitName(orbitId))
                        routine && task.source == OrbitRoutines.IDEAS_SOURCE -> stringResource(R.string.orbit_routine_ideas_desc, rememberOrbitName(orbitId))
                        routine && task.source == OrbitRoutines.ALIGNMENT_SOURCE -> stringResource(R.string.orbit_routine_alignment_desc, rememberOrbitName(orbitId))
                        routine && task.source == OrbitRoutines.PROACTIVE_SOURCE -> stringResource(R.string.orbit_routine_proactive_desc, rememberOrbitName(orbitId))
                        routine -> stringResource(R.string.orbit_routine_self_review_desc, rememberOrbitName(orbitId))
                        else -> task.instruction.ifBlank { stringResource(R.string.orbit_tasks_daily_default) }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        item(key = "plan") {
            CardLabel(stringResource(R.string.orbit_task_section_plan))
            TaskCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), spacing = 0.dp) {
                val (icon, whenText) = taskIconAndSchedule(task, tasks)
                InfoRow(taskIconName(task, icon), stringResource(R.string.orbit_task_row_when), whenText, divider, first = true)
                if (task.state == "ACTIVE" && task.scheduleMode != "EVENT" &&
                    (task.kind != OrbitGeofence.KIND || dueAt != null)) {
                    val next = dueAt ?: task.nextRunAt
                    InfoRow("clock", stringResource(R.string.orbit_task_row_next),
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(next)) +
                            " · " + relativeTime(next), divider)
                }
                if (task.kind == OrbitGeofence.KIND) {
                    val place = remember(task.source) { OrbitGeofenceDefinition.parse(task.source) }
                    InfoRow("map-pin", stringResource(R.string.orbit_geofence_place),
                        stringResource(R.string.orbit_geofence_place_detail, place.name, place.radius.toInt(),
                            place.latitude.toString(), place.longitude.toString()), divider)
                } else if (task.source.isNotBlank()) InfoRow("eye", stringResource(R.string.orbit_task_row_watch), watchLabel(task), divider)
                InfoRow("message-circle", stringResource(R.string.orbit_task_row_report), stringResource(when (task.notifyPolicy) {
                    "ALWAYS" -> R.string.orbit_task_report_always
                    "DIGEST" -> R.string.orbit_report_digest
                    else -> R.string.orbit_task_report_changes
                }), divider)
                // Only a subagent's background run has a time limit; Orbit's own chat turn has none.
                val limit = OrbitTaskRepository.timeoutMinutes(task)
                InfoRow(if (task.useSubagent) "hourglass" else "messages-square", stringResource(R.string.orbit_task_row_run), when {
                    !task.useSubagent -> stringResource(R.string.orbit_task_in_chat, rememberOrbitName(orbitId))
                    limit >= 120 && limit % 60 == 0 -> stringResource(R.string.orbit_task_timeout_hours, limit / 60)
                    else -> stringResource(R.string.orbit_task_timeout, limit)
                }, divider)
            }
        }
        if (task.kind == OrbitGeofence.KIND) item(key = "location-status") { GeofenceStatus(task) }
        item(key = "result") {
            val run = lastRun
            val running = run?.state == "RUNNING"
            CardLabel(stringResource(R.string.orbit_task_last_result))
            TaskCard {
                if (running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.orbit_task_running), style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    val failed = run?.state == "FAILED" && !run.error.isNullOrBlank()
                    // A skipped run keeps the previous observation; it says why it was skipped instead.
                    val skipped = run?.decision == OrbitRunEntity.SKIPPED && run.error.isBlank()
                    Text(
                        if (skipped) OrbitRoutines.skippedReason(context, task)
                        else run?.error?.takeIf { it.isNotBlank() } ?: run?.observation?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.orbit_task_no_result),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (failed) com.ai.assistance.operit.ui.common.readableErrorColor() else MaterialTheme.colorScheme.onSurface,
                        maxLines = 12, overflow = TextOverflow.Ellipsis,
                    )
                }
                run?.decision?.takeIf { it.isNotBlank() && !running && it != OrbitRunEntity.SKIPPED }?.let { decision ->
                    Text(stringResource(R.string.orbit_task_decision,
                        if (OrbitChatDecision.isChat(decision)) OrbitChatDecision.describe(context, decision, rememberOrbitName(orbitId))
                        else when (decision) {
                        OrbitRunEntity.UNCHANGED -> stringResource(R.string.orbit_decision_unchanged)
                        OrbitRunEntity.IN_CHAT -> stringResource(R.string.orbit_decision_in_chat, rememberOrbitName(orbitId))
                        else -> decision
                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                lastItem?.takeIf { !running && it.verdict.isNotEmpty() }?.let { item ->
                    Text(proactiveVerdictText(item, rememberOrbitName(orbitId)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                run?.finishedAt?.takeIf { it > 0 && !running }?.let {
                    Text(relativeTime(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // A watch that lost a permission says so; give the user the way to turn it back on.
                val permission = run?.error?.let { watchPermissionOf(context, it) }
                if (permission != null && run?.state == "FAILED") {
                    TaskButton(stringResource(R.string.orbit_watch_open_settings), "settings", primary = true) {
                        openWatchPermission(context, permission)
                    }
                }
                if (run != null) {
                    HorizontalDivider(color = divider)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !busy, role = Role.Button) { onRecord(task.id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(orbitIcon("scroll-text"), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.orbit_run_history_open), style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f))
                        Icon(orbitIcon("chevron-right"), null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }
        }
        if (task.state == "WAITING_USER") item(key = "waiting") {
            CardLabel(stringResource(R.string.orbit_tasks_group_attention))
            TaskCard {
                if (!input?.requestedUrl.isNullOrBlank()) {
                    Text(stringResource(R.string.orbit_browser_handoff_help), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TaskButton(stringResource(R.string.orbit_browser_open), "globe", enabled = !busy) {
                        action { com.ai.assistance.operit.features.orbit.OrbitBrowserHandoff.open(context, task) }
                    }
                }
                OutlinedTextField(
                    value = userNote, onValueChange = { if (it.length <= 4000) userNote = it },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy, shape = RoundedCornerShape(14.dp),
                    label = { Text(stringResource(R.string.orbit_task_user_note)) },
                )
                TaskButton(stringResource(R.string.orbit_task_continue), "check", primary = true, enabled = !busy) {
                    action {
                        com.ai.assistance.operit.features.orbit.OrbitBrowserHandoff.resume(context, task, userNote)
                        userNote = ""
                    }
                }
            }
        }
        // Receipts to check come before the buttons: resuming waits on them.
        items(receipts, key = { "receipt-${it.id}" }, contentType = { "receipt" }) { receipt ->
            val unknown = receipt.state == "UNKNOWN"
            TaskCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TaskIcon(if (unknown) "shield-alert" else "receipt", size = 36.dp, iconSize = 18.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(if (unknown) R.string.orbit_action_verify else R.string.orbit_action_receipt),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Text(receipt.summary, style = MaterialTheme.typography.bodyMedium)
                if (unknown) {
                    Text(stringResource(R.string.orbit_action_verify_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TaskButton(stringResource(R.string.orbit_action_verified), "check", primary = true,
                        enabled = !busy) { onResolve(receipt.id, true) }
                    TaskButton(stringResource(R.string.orbit_action_retry_allowed), "rotate-ccw",
                        enabled = !busy) { onResolve(receipt.id, false) }
                }
            }
        }
        if (task.state !in setOf("CANCELLED", "DONE")) item(key = "actions") {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (task.state == "PAUSED" && OrbitHookChecks.needed(task) && !OrbitHookChecks.passed(context, task)) {
                    Text(stringResource(R.string.orbit_hook_needs_dry_run), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (task.state != "WAITING_USER") {
                    val active = task.state == "ACTIVE"
                    val blocked = !active && receipts.any { it.state in setOf("STARTED", "UNKNOWN") }
                    val pauseOrResume: @Composable (Modifier) -> Unit = { modifier ->
                        // Orbit is told first: the request waits in the main chat's input box.
                        TaskButton(
                            stringResource(if (active) R.string.orbit_task_pause else R.string.orbit_task_resume),
                            if (active) "pause" else "play",
                            primary = !active,
                            enabled = !busy && !blocked,
                            modifier = modifier,
                        ) {
                            onDraft(context.getString(
                                if (active) R.string.orbit_task_pause_draft else R.string.orbit_task_resume_draft, task.title))
                        }
                    }
                    val runNow: @Composable (Modifier) -> Unit = { modifier ->
                        // Asks Orbit in the main chat; the request waits in the input box for the
                        // user to send, so a tap only to see what happens runs nothing.
                        TaskButton(stringResource(R.string.orbit_run_now), "zap", primary = true,
                            enabled = !busy, modifier = modifier) {
                            onDraft(context.getString(R.string.orbit_task_run_now_draft, task.title))
                        }
                    }
                    // Two pills side by side; with large text or a narrow screen they stack so
                    // neither label is cut off.
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val stack = LocalDensity.current.fontScale > 1.15f || maxWidth < 340.dp
                        if (!active || !stack) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pauseOrResume(Modifier.weight(1f))
                                if (active) runNow(Modifier.weight(1f))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                runNow(Modifier)
                                pauseOrResume(Modifier)
                            }
                        }
                    }
                    if (blocked) Text(stringResource(R.string.orbit_task_resume_blocked), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                }
                // The Feed and Ideas routines only pause; the tabs live on them.
                if (task.kind != "DAILY" && !OrbitRoutines.isProtected(task)) TextButton(
                    enabled = !busy, onClick = { onCancel(task) },
                    modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.orbit_task_cancel), color = com.ai.assistance.operit.ui.common.readableErrorColor()) }
            }
        }
        feedback()
        if (busy) item(key = "busy") { LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape)) }
    }
}

/** Big round icon, the title and a state pill, centered like the profile's top. */
@Composable
private fun TaskHero(task: OrbitTaskEntity) {
    val icon = taskScheduleIcon(task)
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TaskIcon(taskIconName(task, icon), size = 76.dp, iconSize = 34.dp, background = taskCardColor())
        Text(task.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        StatePill(task)
    }
}

/** A colored dot and the state in words: green runs, orange waits for the user, gray is stopped. */
@Composable
private fun StatePill(task: OrbitTaskEntity) {
    val dot = when (task.state) {
        "ACTIVE" -> Color(0xFF34C759)
        "WAITING_USER", "VERIFY" -> Color(0xFFFF9500)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(
        Modifier.clip(CircleShape).background(taskCardColor()).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(taskState(task), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Routines get their own picture; everything else uses the icon of its schedule. */
private fun taskIconName(task: OrbitTaskEntity, scheduleIcon: String): String = when {
    OrbitRoutines.isRoutine(task) && task.source == OrbitRoutines.GOALS_SOURCE -> "target"
    OrbitRoutines.isRoutine(task) && task.source == OrbitRoutines.FEED_SOURCE -> "newspaper"
    OrbitRoutines.isRoutine(task) && task.source == OrbitRoutines.IDEAS_SOURCE -> "lightbulb"
    OrbitRoutines.isRoutine(task) && task.source == OrbitRoutines.ALIGNMENT_SOURCE -> "compass"
    OrbitRoutines.isRoutine(task) && task.source == OrbitRoutines.PROACTIVE_SOURCE -> "radar"
    OrbitRoutines.isRoutine(task) -> "moon-star"
    task.kind == "DAILY" -> "heart-handshake"
    else -> scheduleIcon
}

@Composable
private fun TaskIcon(
    name: String,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    background: Color = MaterialTheme.colorScheme.surface,
) {
    Box(Modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Icon(orbitIcon(name), contentDescription = null, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun TaskCard(
    contentPadding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = TaskCardShape, color = taskCardColor(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

@Composable
private fun CardLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 8.dp))
}

/** One fact about the task: round icon, a small label and its value. */
@Composable
private fun InfoRow(icon: String, label: String, value: String, divider: Color, first: Boolean = false) {
    if (!first) HorizontalDivider(Modifier.padding(start = 50.dp), color = divider)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TaskIcon(icon, size = 36.dp, iconSize = 18.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A round pill button; the main action is dark like Orbit's send button, the other is gray. */
@Composable
private fun TaskButton(
    text: String,
    icon: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val (red, onRed) = com.ai.assistance.operit.ui.common.destructiveButtonColors()
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = when { destructive -> red; primary -> MaterialTheme.colorScheme.onSurface; else -> taskCardColor() },
        contentColor = when { destructive -> onRed; primary -> MaterialTheme.colorScheme.surface; else -> MaterialTheme.colorScheme.onSurface },
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).alpha(if (enabled) 1f else 0.4f),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(orbitIcon(icon), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TaskErrorCard(detail: String?, actionLabel: String? = null, onRetry: () -> Unit) {
    Surface(shape = TaskCardShape, color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(orbitIcon("circle-alert"), null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.orbit_task_action_failed), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                detail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = onRetry) {
                Text(actionLabel ?: stringResource(R.string.orbit_memory_retry), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun TaskLoading() {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
    }
}
@Composable
private fun taskSchedule(task: OrbitTaskEntity, tasks: List<OrbitTaskEntity>, dueAt: Long?): String =
    if (task.kind == OrbitGeofence.KIND) geofenceSchedule(task)
    else if (task.scheduleMode == "EVENT") stringResource(R.string.orbit_task_event_wait,
        tasks.firstOrNull { it.id == task.triggerTaskId }?.title ?: task.triggerTaskId)
    else stringResource(R.string.orbit_task_next, DateFormat.getDateTimeInstance(
        DateFormat.SHORT, DateFormat.SHORT).format(Date(dueAt ?: task.nextRunAt))) +
        if (task.scheduleMode == "INTERVAL") " · " + intervalText(task.intervalMinutes) else ""

@Composable
internal fun taskState(task: OrbitTaskEntity): String = stringResource(when (task.state) {
    "ACTIVE" -> R.string.orbit_task_active
    "PAUSED" -> R.string.orbit_task_paused
    "WAITING_USER" -> R.string.orbit_task_waiting_user
    "VERIFY" -> R.string.orbit_action_verify
    "DONE" -> R.string.orbit_task_done
    else -> R.string.orbit_task_cancelled
})

/** What a task reads: a web page as is, a WATCH task's source in words. */
@Composable
internal fun watchLabel(task: OrbitTaskEntity): String {
    if (task.kind == OrbitGeofence.KIND) return OrbitGeofenceDefinition.parse(task.source).name
    when (task.source) {
        com.ai.assistance.operit.features.orbit.OrbitRoutines.CHAT_SOURCE -> return stringResource(R.string.orbit_routine_source_chat)
        com.ai.assistance.operit.features.orbit.OrbitRoutines.IDEAS_SOURCE -> return stringResource(R.string.orbit_routine_source_chat)
        com.ai.assistance.operit.features.orbit.OrbitRoutines.ALIGNMENT_SOURCE -> return stringResource(R.string.orbit_routine_source_chat)
        com.ai.assistance.operit.features.orbit.OrbitRoutines.PROACTIVE_SOURCE -> return stringResource(R.string.orbit_routine_source_chat)
        com.ai.assistance.operit.features.orbit.OrbitRoutines.FEED_SOURCE -> return stringResource(R.string.orbit_routine_source_feed)
        com.ai.assistance.operit.features.orbit.OrbitRoutines.GOALS_SOURCE -> return stringResource(R.string.orbit_routine_source_goals)
    }
    if (task.kind != com.ai.assistance.operit.features.orbit.OrbitWatchSources.KIND) return task.source
    val type = task.source.substringBefore(':')
    val arg = task.source.substringAfter(':', "").trim()
    return when (type) {
        "notifications" -> if (arg.isEmpty()) stringResource(R.string.orbit_watch_notifications)
            else stringResource(R.string.orbit_watch_notifications_apps, appNames(arg))
        "sms" -> if (arg.isEmpty()) stringResource(R.string.orbit_watch_sms) else stringResource(R.string.orbit_watch_sms_from, arg)
        "calendar" -> stringResource(R.string.orbit_watch_calendar, arg.toIntOrNull() ?: 7)
        else -> stringResource(R.string.orbit_watch_script,
            com.ai.assistance.operit.features.orbit.OrbitWatchSources.SCRIPT.matchEntire(task.source)?.groupValues?.get(1)
                ?.substringAfterLast('/')?.replace(SCRIPT_SUFFIX, "") ?: task.source)
    }
}

@Composable
internal fun geofenceSchedule(task: OrbitTaskEntity): String {
    val place = remember(task.source) { OrbitGeofenceDefinition.parse(task.source) }
    return stringResource(if (place.transition == "ENTER") R.string.orbit_geofence_enter else R.string.orbit_geofence_exit,
        place.name) + " · " + stringResource(if (task.scheduleMode == "ONCE")
        R.string.orbit_geofence_once else R.string.orbit_geofence_repeat)
}

@Composable
private fun GeofenceStatus(task: OrbitTaskEntity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var problem by remember(task.id, task.version) { mutableStateOf(OrbitGeofence.status(context, task)) }
    LaunchedEffect(context, task.id, task.version) {
        OrbitGeofence.observeStatus(context, task).collect { problem = it }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (task.state == "ACTIVE") scope.launch {
            try {
                OrbitTaskScheduler.reschedule(context)
                problem = OrbitGeofence.status(context, task)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem = context.getString(R.string.orbit_geofence_unavailable) }
        }
    }
    TaskCard {
        Text(problem ?: stringResource(R.string.orbit_geofence_help), style = MaterialTheme.typography.bodyMedium)
        if (task.state == "ACTIVE" && OrbitGeofence.permissionProblem(context) != null) {
            TaskButton(stringResource(R.string.orbit_watch_open_settings), "settings", primary = true) {
                openWatchPermission(context, if (problem == context.getString(R.string.orbit_geofence_needs_location)) "location" else "app")
            }
        }
    }
}

private val SCRIPT_SUFFIX = Regex("-[0-9a-f]{8}(?=\\.sh$)")

/** Package names shown as app names where the phone knows them. */
@Composable
private fun appNames(packages: String): String {
    val context = LocalContext.current
    return remember(packages) {
        packages.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") { name ->
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString()
            }.getOrDefault(name)
        }
    }
}

/** " · what it watches" for rows whose main text is a date or a trigger. */
@Composable
internal fun watchSuffix(task: OrbitTaskEntity): String =
    if (task.kind == com.ai.assistance.operit.features.orbit.OrbitWatchSources.KIND) " · " + watchLabel(task) else ""

/** "Orbit told you: a deadline is coming", or why it left a draft out. */
@Composable
private fun proactiveVerdictText(item: OrbitDeliveryEntity, name: String): String {
    val reason = stringResource(when {
        item.verdictNote == OrbitDeliveryEntity.EXPIRED -> R.string.orbit_item_reason_expired
        else -> when (item.verdictReason) {
            "time_sensitive" -> R.string.orbit_item_reason_time_sensitive
            "changed" -> R.string.orbit_item_reason_changed
            "requested" -> R.string.orbit_item_reason_requested
            "ready_for_decision" -> R.string.orbit_item_reason_ready
            "new_opportunity" -> R.string.orbit_item_reason_new
            "moot" -> R.string.orbit_item_reason_moot
            "already_handled" -> R.string.orbit_item_reason_handled
            "unconfirmed" -> R.string.orbit_item_reason_unconfirmed
            "preferences" -> R.string.orbit_item_reason_preferences
            "not_useful_now" -> R.string.orbit_item_reason_not_now
            else -> R.string.orbit_item_reason_undecided
        }
    })
    return stringResource(if (item.verdict == OrbitDeliveryEntity.SEND) R.string.orbit_item_told else R.string.orbit_item_skipped,
        name, reason)
}

/** Which permission a WATCH failure is about, from the saved reason. */
private fun watchPermissionOf(context: android.content.Context, error: String): String? = when (error) {
    context.getString(R.string.orbit_watch_needs_notifications), context.getString(R.string.orbit_watch_notifications_stuck) -> "notifications"
    context.getString(R.string.orbit_watch_needs_sms), context.getString(R.string.orbit_watch_needs_calendar) -> "app"
    else -> null
}

private fun openWatchPermission(context: android.content.Context, permission: String) {
    val intent = if (permission == "notifications")
        android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    else if (permission == "location") android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    else android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null))
    try {
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, R.string.orbit_watch_open_settings_failed, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** The list icon for a WATCH task, by what it watches. */
internal fun watchIcon(task: OrbitTaskEntity): String = when (task.source.substringBefore(':')) {
    "notifications" -> "bell-ring"
    "sms" -> "message-square-text"
    "calendar" -> "calendar-search"
    else -> "terminal"
}
