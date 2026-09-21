package com.ai.assistance.operit.ui.features.memory.screens.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun MemoryReviewDialog(profileId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(profileId) { MemoryReviewRepository(context, profileId) }
    val settings = remember(profileId) { MemorySearchSettingsPreferences(context, profileId) }
    val api = remember { ApiPreferences.getInstance(context) }
    val oldExtraction by api.enableMemoryAutoUpdateFlow.collectAsState(initial = false)
    var newExtraction by remember { mutableStateOf(settings.shouldExtractNewMemory()) }
    var skillsExtraction by remember { mutableStateOf(settings.shouldExtractSkills()) }
    var aiDecisions by remember { mutableStateOf(settings.mayAiReviewChanges()) }
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<MemoryReviewChange>>(emptyList()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = records.find { it.id == selectedId }
    var history by rememberSaveable { mutableStateOf(false) }
    var body by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discardTarget by remember { mutableStateOf<String?>(null) }
    val dirty = selected?.let { it.status == "pending" && (body != it.body || description != it.description) } == true
    fun leave(target: String) {
        if (busy) return
        if (dirty) discardTarget = target
        else if (target == "close") onDismiss() else selectedId = null
    }
    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try { block(); records = repo.list() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = context.getString(R.string.memory_review_error, e.message.orEmpty()) }
            finally { busy = false }
        }
    }
    LaunchedEffect(repo) {
        try { records = repo.list() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = context.getString(R.string.memory_review_error, e.message.orEmpty()) }
    }
    Dialog(onDismissRequest = { leave("close") }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.95f).fillMaxHeight(.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.memory_review_title), style = MaterialTheme.typography.titleLarge)
                if (selected == null) {
                    Row {
                        Text(stringResource(R.string.memory_extract_old), Modifier.weight(1f))
                        Switch(oldExtraction, { run { api.saveEnableMemoryAutoUpdate(it) } }, enabled = !busy)
                    }
                    Row {
                        Text(stringResource(R.string.memory_extract_new), Modifier.weight(1f))
                        Switch(newExtraction, { settings.setExtractNewMemory(it); newExtraction = it })
                    }
                    Row {
                        Text(stringResource(R.string.memory_extract_skills), Modifier.weight(1f))
                        Switch(skillsExtraction, { settings.setExtractSkills(it); skillsExtraction = it }, enabled = newExtraction)
                    }
                    Text(stringResource(R.string.memory_packages_hint), style = MaterialTheme.typography.bodySmall)
                    Row {
                        Text(stringResource(R.string.memory_review_ai_allowed), Modifier.weight(1f))
                        Switch(aiDecisions, { settings.setAiReviewChanges(it); aiDecisions = it })
                    }
                    Row {
                        TextButton(onClick = { history = false }) { Text(stringResource(R.string.memory_review_pending)) }
                        TextButton(onClick = { history = true }) { Text(stringResource(R.string.memory_review_history)) }
                        TextButton(onClick = { run {} }, enabled = !busy) { Text(stringResource(R.string.memory_review_refresh)) }
                    }
                    val visible = records.filter { (it.status !in setOf("pending", "applying")) == history }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (visible.isEmpty()) item { Text(stringResource(R.string.memory_review_empty)) }
                        items(visible, key = { it.id }) { record ->
                            OutlinedButton(onClick = {
                                selectedId = record.id; body = record.body; description = record.description; reason = ""
                            }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(record.title)
                                    Text(reviewStatus(record.status) + " · " + DateFormat.getDateTimeInstance().format(Date(record.createdAt)))
                                }
                            }
                        }
                    }
                } else {
                    val record = selected!!
                    val pending = record.status == "pending"
                    val changed = body != record.body || description != record.description
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(record.title + " · " + reviewStatus(record.status))
                        if (record.before.isNotEmpty()) {
                            Text(stringResource(R.string.memory_review_before))
                            OutlinedTextField(record.before, {}, readOnly = true, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                        }
                        if (record.kind == "skill") OutlinedTextField(description, { description = it },
                            readOnly = !pending, enabled = !busy, label = { Text(stringResource(R.string.memory_review_description)) })
                        OutlinedTextField(body, { body = it }, readOnly = !pending, enabled = !busy,
                            label = { Text(stringResource(R.string.memory_review_after)) },
                            modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 12)
                        if (record.audits.isNotBlank()) Text(record.audits)
                        if (record.reviewedAt > 0) Text("${record.reviewer} · ${DateFormat.getDateTimeInstance().format(Date(record.reviewedAt))}\n${record.reason}")
                        if (pending || record.status == "applying") {
                            OutlinedTextField(reason, { reason = it }, enabled = !busy,
                                label = { Text(stringResource(R.string.memory_review_reason)) }, modifier = Modifier.fillMaxWidth())
                            if (changed) Button(onClick = { run {
                                selectedId = repo.revise(record.id, body, description).id
                            } }, enabled = !busy) { Text(stringResource(R.string.memory_review_save_revision)) }
                            Row {
                                Button(onClick = { run {
                                    repo.decide(context, record.id, true, "user", reason); selectedId = null
                                } }, enabled = !busy && !changed) { Text(stringResource(R.string.memory_review_approve)) }
                                TextButton(onClick = { run {
                                    repo.decide(context, record.id, false, "user", reason); selectedId = null
                                } }, enabled = !busy && pending && !changed) { Text(stringResource(R.string.memory_review_reject)) }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row {
                    if (selected != null) TextButton(onClick = { leave("back") }, enabled = !busy) { Text(stringResource(R.string.chat_recall_back)) }
                    TextButton(onClick = { leave("close") }, enabled = !busy) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
    if (discardTarget != null) AlertDialog(
        onDismissRequest = { discardTarget = null },
        title = { Text(stringResource(R.string.memory_notes_discard_title)) },
        confirmButton = { TextButton(onClick = {
            val target = discardTarget
            discardTarget = null
            if (target == "close") onDismiss() else selectedId = null
        }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { discardTarget = null }) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun reviewStatus(status: String): String = stringResource(when (status) {
    "approved" -> R.string.memory_review_approved
    "rejected" -> R.string.memory_review_rejected
    "superseded" -> R.string.memory_review_superseded
    "applying" -> R.string.memory_review_applying
    else -> R.string.memory_review_pending
})
