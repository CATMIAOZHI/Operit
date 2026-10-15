package com.ai.assistance.operit.ui.features.orbit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.features.orbit.OrbitTaskRepository
import com.ai.assistance.operit.features.orbit.OrbitTaskScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitTasksPage(orbitId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { OrbitTaskRepository(context) }
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable(orbitId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var userNote by rememberSaveable(selected) { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var confirmCancel by remember { mutableStateOf<OrbitTaskEntity?>(null) }
    var resolveReceipt by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val tasks by produceState<List<OrbitTaskEntity>?>(null, orbitId, retry) {
        value = null
        try { repository.dao.observeTasks(orbitId).collect { value = it.filter { task -> task.kind != "ONCE" } } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = true }
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = false
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = true; errorDetail = e.message }
            finally { busy = false }
        }
    }
    fun back() { if (selected != null) selected = null else onBack() }
    BackHandler { back() }
    val detail = tasks?.firstOrNull { it.id == selected }
    val listState = rememberLazyListState()
    val detailState = key(selected) { rememberLazyListState() }
    val lastRun by produceState<com.ai.assistance.operit.data.model.OrbitRunEntity?>(
        null, detail?.id, retry) {
        value = null
        try { detail?.let { repository.dao.observeLatestRun(it.id).collect { run -> value = run } } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = true }
    }
    val receipts by produceState(emptyList<com.ai.assistance.operit.data.model.OrbitActionEntity>(), detail?.id) {
        value = emptyList()
        try { detail?.let { repository.dao.observeActions(it.id).collect { rows -> value = rows } } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = true }
    }
    val input by produceState<com.ai.assistance.operit.data.model.OrbitTaskInputEntity?>(
        null, detail?.id, detail?.version, detail?.state, busy) {
        value = detail?.let { repository.dao.input(it.id) }
    }
    Scaffold(
        topBar = { TopAppBar(
            title = { Text(stringResource(R.string.orbit_tasks_title)) },
            navigationIcon = { IconButton(onClick = ::back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            } },
        ) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            state = if (detail == null) listState else detailState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (error) item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.orbit_task_action_failed))
                        errorDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { error = false; retry++ }) {
                            Text(stringResource(R.string.orbit_memory_retry))
                        }
                    }
                }
            }
            if (tasks == null && !error) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (detail == null) {
                item { Text(stringResource(R.string.orbit_tasks_help),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (tasks?.isEmpty() == true) item { Text(stringResource(R.string.orbit_tasks_empty)) }
                items(tasks.orEmpty(), key = { it.id }) { task ->
                    Card(onClick = { selected = task.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(taskState(task), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                            if (task.state == "ACTIVE") Text(
                                stringResource(R.string.orbit_task_next, DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT, DateFormat.SHORT).format(Date(task.nextRunAt))),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(detail.title, style = MaterialTheme.typography.headlineSmall)
                            Text(taskState(detail), color = MaterialTheme.colorScheme.primary)
                            Text(detail.instruction.ifBlank { stringResource(R.string.orbit_tasks_daily_default) })
                            if (detail.source.isNotBlank()) Text(detail.source,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(if (detail.notifyPolicy == "ALWAYS")
                                R.string.orbit_task_report_always else R.string.orbit_task_report_changes))
                            if (detail.state == "ACTIVE") Text(stringResource(R.string.orbit_task_next,
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(detail.nextRunAt))))
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.orbit_task_last_result), fontWeight = FontWeight.SemiBold)
                            Text(if (lastRun?.state == "RUNNING") stringResource(R.string.orbit_task_running)
                                else lastRun?.error?.takeIf { it.isNotBlank() }
                                ?: lastRun?.observation?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.orbit_task_no_result))
                        }
                    }
                }
                if (detail.state !in setOf("CANCELLED", "DONE")) item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (detail.state == "WAITING_USER") {
                            if (!input?.requestedUrl.isNullOrBlank()) {
                                OutlinedButton(enabled = !busy, onClick = { action {
                                    com.ai.assistance.operit.features.orbit.OrbitBrowserHandoff.open(context, detail)
                                } }) { Text(stringResource(R.string.orbit_browser_open)) }
                                Text(stringResource(R.string.orbit_browser_handoff_help),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedTextField(value = userNote, onValueChange = { if (it.length <= 4000) userNote = it },
                                modifier = Modifier.fillMaxWidth(), enabled = !busy,
                                label = { Text(stringResource(R.string.orbit_task_user_note)) })
                            FilledTonalButton(enabled = !busy, onClick = { action {
                                com.ai.assistance.operit.features.orbit.OrbitBrowserHandoff.resume(context, detail, userNote)
                                userNote = ""
                            } }) { Text(stringResource(R.string.orbit_task_continue)) }
                        }
                        if (detail.state != "WAITING_USER")
                        FilledTonalButton(modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && (detail.state == "ACTIVE" ||
                                receipts.none { it.state in setOf("STARTED", "UNKNOWN") }), onClick = {
                            action { check(repository.changeState(orbitId, detail.id,
                                if (detail.state == "ACTIVE") "PAUSED" else "ACTIVE")) }
                        }) { Text(stringResource(if (detail.state == "ACTIVE") R.string.orbit_pause else R.string.orbit_resume)) }
                        if (detail.state == "ACTIVE") OutlinedButton(
                            modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                                action {
                                    repository.dao.enqueueManual(detail.id, java.util.UUID.randomUUID().toString(),
                                        System.currentTimeMillis())
                                    OrbitTaskScheduler.reschedule(context)
                                }
                            }) { Text(stringResource(R.string.orbit_run_now)) }
                        if (detail.kind != "DAILY") TextButton(enabled = !busy,
                            onClick = { confirmCancel = detail }) {
                            Text(stringResource(R.string.orbit_task_cancel), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                items(receipts, key = { "receipt-${it.id}" }) { receipt ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(if (receipt.state == "UNKNOWN") R.string.orbit_action_verify
                                else R.string.orbit_action_receipt), fontWeight = FontWeight.SemiBold)
                            Text(receipt.summary, style = MaterialTheme.typography.bodyMedium)
                            if (receipt.state == "UNKNOWN") {
                                Text(stringResource(R.string.orbit_action_verify_help))
                                TextButton(enabled = !busy, onClick = { resolveReceipt = receipt.id to true }) {
                                    Text(stringResource(R.string.orbit_action_verified))
                                }
                                TextButton(enabled = !busy, onClick = { resolveReceipt = receipt.id to false }) {
                                    Text(stringResource(R.string.orbit_action_retry_allowed))
                                }
                            }
                        }
                    }
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        }
    }
    confirmCancel?.let { task ->
        AlertDialog(onDismissRequest = { confirmCancel = null },
            title = { Text(stringResource(R.string.orbit_task_cancel)) },
            text = { Text(stringResource(R.string.orbit_task_cancel_message, task.title)) },
            confirmButton = { TextButton(onClick = {
                confirmCancel = null
                action { check(repository.changeState(orbitId, task.id, "CANCELLED")) }
            }) { Text(stringResource(R.string.orbit_task_cancel)) } },
            dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text(stringResource(R.string.cancel)) } })
    }
    resolveReceipt?.let { (id, completed) ->
        AlertDialog(onDismissRequest = { resolveReceipt = null },
            title = { Text(stringResource(if (completed) R.string.orbit_action_verified else R.string.orbit_action_retry_allowed)) },
            text = { Text(stringResource(R.string.orbit_action_verify_confirm)) },
            confirmButton = { TextButton(onClick = {
                resolveReceipt = null
                detail?.let { task -> action { repository.resolveAction(orbitId, task.id, id, completed) } }
            }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { resolveReceipt = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun taskState(task: OrbitTaskEntity): String = stringResource(when (task.state) {
    "ACTIVE" -> R.string.orbit_task_active
    "PAUSED" -> R.string.orbit_task_paused
    "WAITING_USER" -> R.string.orbit_task_waiting_user
    "VERIFY" -> R.string.orbit_action_verify
    "DONE" -> R.string.orbit_task_done
    else -> R.string.orbit_task_cancelled
})
