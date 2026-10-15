package com.ai.assistance.operit.ui.features.orbit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.orbit.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitWorkspacePage(orbitId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember(context) { OrbitTaskRepository(context).dao }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable(orbitId) { mutableIntStateOf(0) }
    var taskId by rememberSaveable(orbitId) { mutableStateOf<String?>(null) }
    var goalId by rememberSaveable(orbitId) { mutableStateOf<String?>(null) }
    var artifactId by rememberSaveable(orbitId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editingGoal by remember { mutableStateOf<OrbitGoalEntity?>(null) }
    var goalEditor by remember { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var plan by rememberSaveable { mutableStateOf("") }
    var revisionRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var previewOffset by rememberSaveable(artifactId) { mutableIntStateOf(0) }
    val goals by remember(orbitId) { dao.observeGoals(orbitId).catch { error = it.message ?: it.toString() } }.collectAsState(initial = null)
    val tasks by remember(orbitId) { dao.observeTasks(orbitId).catch { error = it.message ?: it.toString() } }.collectAsState(initial = null)
    val feed by remember(orbitId) { dao.observeFeed(orbitId).catch { error = it.message ?: it.toString() } }.collectAsState(initial = null)
    val artifacts by remember(orbitId) { dao.observeArtifacts(orbitId).catch { error = it.message ?: it.toString() } }.collectAsState(initial = null)
    var policy by remember(orbitId) { mutableStateOf<OrbitNotificationEntity?>(null) }
    val lists = List(5) { rememberLazyListState() }
    val detailList = key(goalId, artifactId) { rememberLazyListState() }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    LaunchedEffect(orbitId) {
        try { policy = dao.notificationPolicy(orbitId) ?: OrbitNotificationEntity(orbitId) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(artifactId) {
        preview = null
        artifactId?.let { id ->
            try { preview = OrbitArtifactRepository(context).read(orbitId, id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
        }
    }
    fun back() {
        when { taskId != null -> taskId = null; artifactId != null -> artifactId = null
            goalId != null -> goalId = null; else -> onBack() }
    }
    BackHandler { back() }
    if (taskId != null) {
        OrbitTasksPage(orbitId, onBack = { taskId = null }, initialTaskId = taskId)
        return
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.orbit_workspace)) },
        navigationIcon = { IconButton(onClick = ::back) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
        } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val labels = listOf(R.string.orbit_tab_tasks, R.string.orbit_tab_goals, R.string.orbit_tab_feed,
                R.string.orbit_tab_artifacts, R.string.orbit_tab_notifications)
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                labels.forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index; goalId = null; artifactId = null },
                        text = { Text(stringResource(label)) })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { message -> Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.fillMaxSize(), state = if (goalId != null || artifactId != null) detailList else lists[tab], contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> {
                        item { Text(stringResource(R.string.orbit_tasks_help)) }
                        if (tasks == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        items(tasks.orEmpty().filter { it.kind != "ONCE" }, key = { it.id }) { task ->
                            OrbitProductCard(task.title, taskState(task)) { taskId = task.id }
                        }
                        if (tasks?.none { it.kind != "ONCE" } == true) item { Text(stringResource(R.string.orbit_tasks_empty)) }
                    }
                    1 -> {
                        item { FilledTonalButton(enabled = !busy, onClick = {
                            editingGoal = null; title = ""; plan = ""; goalEditor = true
                        }) { Text(stringResource(R.string.orbit_goal_create)) } }
                        val selected = goals?.firstOrNull { it.id == goalId }
                        if (selected == null) {
                            if (goals == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            if (goals?.isEmpty() == true) item { Text(stringResource(R.string.orbit_goals_empty)) }
                            items(goals.orEmpty(), key = { it.id }) { goal ->
                                val steps = tasks.orEmpty().filter { it.goalId == goal.id }
                                val state = stringResource(when(goal.state) {
                                    "ACTIVE" -> R.string.orbit_task_active
                                    "PAUSED" -> R.string.orbit_task_paused
                                    else -> R.string.orbit_task_cancelled
                                })
                                OrbitProductCard(goal.title, "$state · " + stringResource(R.string.orbit_goal_progress,
                                    steps.count { it.state == "DONE" }, steps.size)) { goalId = goal.id }
                            }
                        } else {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(selected.title, style = MaterialTheme.typography.headlineSmall)
                                    Text(selected.plan)
                                    TextButton(enabled = !busy, onClick = {
                                        editingGoal = selected; title = selected.title; plan = selected.plan; goalEditor = true
                                    }) { Text(stringResource(R.string.orbit_goal_edit)) }
                                    if (selected.state != "CANCELLED") FilledTonalButton(enabled = !busy, onClick = { action {
                                        OrbitGoalRepository(context).update(selected, selected.title, selected.plan,
                                            if (selected.state == "ACTIVE") "PAUSED" else "ACTIVE")
                                    } }) { Text(stringResource(if (selected.state == "ACTIVE") R.string.orbit_pause else R.string.orbit_resume)) }
                                    Text(stringResource(R.string.orbit_goal_steps_help), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            items(tasks.orEmpty().filter { it.goalId == selected.id }, key = { it.id }) { task ->
                                OrbitProductCard(task.title, taskState(task)) { taskId = task.id }
                            }
                        }
                    }
                    2 -> {
                        if (feed == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        if (feed?.isEmpty() == true) item { Text(stringResource(R.string.orbit_feed_empty)) }
                        items(feed.orEmpty(), key = { it.id }) { result ->
                            Card(onClick = { taskId = result.taskId }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(result.title, fontWeight = FontWeight.SemiBold)
                                    Text(result.body)
                                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(result.createdAt)),
                                        style = MaterialTheme.typography.labelSmall)
                                    if (result.state == "PENDING") Text(stringResource(R.string.orbit_feed_queued),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    3 -> {
                        val selected = artifacts?.firstOrNull { it.id == artifactId }
                        if (selected == null) {
                            if (artifacts == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            if (artifacts?.isEmpty() == true) item { Text(stringResource(R.string.orbit_artifacts_empty)) }
                            items(artifacts.orEmpty(), key = { it.id }) { version ->
                                OrbitProductCard(version.name, DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT, DateFormat.SHORT).format(Date(version.createdAt))) { artifactId = version.id }
                            }
                        } else {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(selected.name, style = MaterialTheme.typography.titleLarge)
                                    Row {
                                        TextButton(onClick = { taskId = selected.taskId }) { Text(stringResource(R.string.orbit_artifact_source)) }
                                        TextButton(enabled = !busy && preview != null,
                                            onClick = { revisionRequest = "" }) { Text(stringResource(R.string.orbit_artifact_revise)) }
                                    }
                                    if (preview == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    SelectionContainer { Text(preview.orEmpty().drop(previewOffset).take(20_000)) }
                                    Row {
                                        if (previewOffset > 0) TextButton(onClick = { previewOffset = (previewOffset - 20_000).coerceAtLeast(0) }) {
                                            Text(stringResource(R.string.orbit_previous_page))
                                        }
                                        if (previewOffset + 20_000 < preview.orEmpty().length) TextButton(onClick = { previewOffset += 20_000 }) {
                                            Text(stringResource(R.string.orbit_next_page))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    4 -> policy?.let { settings ->
                        item {
                            Card {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(stringResource(R.string.orbit_notifications_help))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(stringResource(R.string.orbit_quiet_hours))
                                        Switch(checked = settings.quietEnabled, enabled = !busy, onCheckedChange = { enabled -> action {
                                            OrbitExecutionGate.serialized {
                                                val current = dao.notificationPolicy(orbitId) ?: settings
                                                dao.saveNotificationPolicy(current.copy(quietEnabled = enabled))
                                            }
                                            policy = dao.notificationPolicy(orbitId)
                                            OrbitTaskScheduler.reschedule(context)
                                        } })
                                    }
                                    Text(stringResource(R.string.orbit_delivery_interval))
                                    listOf(0, 15, 60).forEach { minutes ->
                                        Row {
                                            RadioButton(selected = settings.intervalMinutes == minutes, enabled = !busy, onClick = { action {
                                                OrbitExecutionGate.serialized {
                                                    val current = dao.notificationPolicy(orbitId) ?: settings
                                                    dao.saveNotificationPolicy(current.copy(intervalMinutes = minutes))
                                                }
                                                policy = dao.notificationPolicy(orbitId)
                                                OrbitTaskScheduler.reschedule(context)
                                            } })
                                            Text(if (minutes == 0) stringResource(R.string.orbit_delivery_immediate)
                                                else stringResource(R.string.orbit_delivery_minutes, minutes), Modifier.padding(top = 12.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (goalEditor) AlertDialog(onDismissRequest = { if (!busy) goalEditor = false },
        title = { Text(stringResource(R.string.orbit_goal_edit)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { if (it.length <= 80) title = it }, label = { Text(stringResource(R.string.orbit_goal_title)) })
            OutlinedTextField(plan, { if (it.length <= 8000) plan = it }, minLines = 4, maxLines = 8,
                label = { Text(stringResource(R.string.orbit_goal_plan)) })
        } },
        confirmButton = { TextButton(enabled = !busy && title.isNotBlank(), onClick = { action {
            val repository = OrbitGoalRepository(context)
            val old = editingGoal
            if (old == null) repository.create(orbitId, title, plan, UUID.randomUUID().toString())
            else repository.update(old, title, plan, old.state)
            goalEditor = false
        } }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { goalEditor = false }) { Text(stringResource(R.string.cancel)) } })
    revisionRequest?.let { request ->
        AlertDialog(onDismissRequest = { if (!busy) revisionRequest = null },
            title = { Text(stringResource(R.string.orbit_artifact_revise)) },
            text = { OutlinedTextField(request, { if (it.length <= 4000) revisionRequest = it },
                label = { Text(stringResource(R.string.orbit_revision_request)) }, minLines = 3) },
            confirmButton = { TextButton(enabled = !busy && request.isNotBlank(), onClick = { action {
                val orbit = checkNotNull(OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == orbitId })
                val version = checkNotNull(artifacts?.firstOrNull { it.id == artifactId })
                OrbitTaskRepository(context).create(orbit, version.name.take(80), request, "", 60, "ALWAYS",
                    UUID.randomUUID().toString(), kind = "ASSIST", scheduleMode = "ONCE", sourceArtifactVersionId = version.id)
                revisionRequest = null
                tab = 0
                artifactId = null
            } }) { Text(stringResource(R.string.orbit_task_continue)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { revisionRequest = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun OrbitProductCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
