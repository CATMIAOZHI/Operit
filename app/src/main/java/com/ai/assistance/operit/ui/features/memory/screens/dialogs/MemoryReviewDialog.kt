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
import com.ai.assistance.operit.ui.features.memory.screens.MemoryLibraryPage
import androidx.compose.foundation.clickable
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
    val autoSave by api.enableMemoryAutoUpdateFlow.collectAsState(initial = false)
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
    MemoryLibraryPage(selected?.title ?: stringResource(R.string.memory_review_title), profileId,
        { leave(if (selectedId == null) "close" else "back") }) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selected == null) {
                    if (!autoSave) Text(stringResource(R.string.memory_auto_master_off), style = MaterialTheme.typography.bodySmall)
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
                            Column(modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                selectedId = record.id; body = record.body; description = record.description; reason = ""
                            }) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                                    Text(record.title, style = MaterialTheme.typography.titleMedium)
                                    Text(reviewStatus(record.status) + " · " + DateFormat.getDateTimeInstance().format(Date(record.createdAt)),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                } else {
                    val record = selected!!
                    val pending = record.status == "pending"
                    val changed = body != record.body || description != record.description
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(record.title + (if(record.kind=="skill_file") "/${record.path}" else "") + " · " + reviewStatus(record.status))
                        if (record.before.isNotEmpty()) {
                            Text(stringResource(R.string.memory_review_before))
                            OutlinedTextField(record.before, {}, readOnly = true, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                        }
                        if (record.kind == "skill") OutlinedTextField(description, { description = it },
                            readOnly = !pending, enabled = !busy, label = { Text(stringResource(R.string.memory_review_description)) })
                        if (record.kind=="skill_delete") Text(stringResource(R.string.memory_review_delete_skill))
                        else OutlinedTextField(body, { body = it }, readOnly = !pending, enabled = !busy,
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
