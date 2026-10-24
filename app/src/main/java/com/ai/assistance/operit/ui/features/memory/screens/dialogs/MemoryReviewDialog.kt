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
fun MemoryReviewDialog(profileId: String, onDismiss: () -> Unit, initialSkillName: String? = null) {
    val context = LocalContext.current
    val reviewErrorFormat = stringResource(R.string.memory_review_error)
    val repo = remember(profileId) { MemoryReviewRepository(context, profileId) }
    val settings = remember(profileId) { MemorySearchSettingsPreferences(context, profileId) }
    val api = remember { ApiPreferences.getInstance(context) }
    val autoSave by api.enableMemoryAutoUpdateFlow.collectAsState(initial = false)
    var aiDecisions by remember { mutableStateOf(settings.mayAiReviewChanges()) }
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<MemoryReviewChange>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = records.find { it.id == selectedId }
    var history by rememberSaveable { mutableStateOf(initialSkillName != null) }
    var body by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discardTarget by remember { mutableStateOf<String?>(null) }
    var evidenceText by remember(selectedId) { mutableStateOf<String?>(null) }
    val memorySpace by remember(profileId) { UserPreferencesManager.getInstance(context).getMemorySpaceFlow(profileId) }
        .collectAsState(initial = null)
    // A typed review reason is part of the draft too, so leaving must not drop it silently.
    val dirty = selected?.let { it.status == "pending" &&
        (body != it.body || description != it.description || reason.isNotBlank()) } == true
    fun leave(target: String) {
        if (busy) return
        if (dirty) discardTarget = target
        else if (target == "close") onDismiss() else selectedId = null
    }
    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try { block(); records = repo.list(); loaded = true }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = reviewErrorFormat.format(e.message.orEmpty()) }
            finally { busy = false }
        }
    }
    LaunchedEffect(repo) {
        try { records = repo.list(); loaded = true }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = reviewErrorFormat.format(e.message.orEmpty()) }
    }
    // A record opens over the list like an iOS page; in an Orbit the back swipe slides it away.
    val status: @Composable () -> Unit = {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    com.ai.assistance.operit.ui.features.orbit.OrbitPushHost(
        entries = listOfNotNull(selectedId),
        onPop = { selectedId = null },
        base = {
    MemoryLibraryPage(stringResource(R.string.memory_review_title), profileId, { leave("close") }) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                kotlin.run {
                    if (memorySpace?.ownerOrbitId != null) Text(stringResource(R.string.orbit_memory_correction_help),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    val visible = records.filter { (it.status !in setOf("pending", "applying")) == history &&
                        (initialSkillName == null || it.kind.startsWith("skill") && it.title == initialSkillName) }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // The draft in the detail pane is restored before the list reloads, so an
                        // empty list here must not be reported as "no records" while still loading.
                        if (!loaded && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        else if (visible.isEmpty()) item { Text(stringResource(R.string.memory_review_empty)) }
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
                }
                status()
            }
        }
    }
        },
    ) { shownId ->
    val record = records.find { it.id == shownId }
    MemoryLibraryPage(record?.title ?: stringResource(R.string.memory_review_title), profileId,
        { leave("back") }, interceptBack = dirty || busy, backEnabled = !busy) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (record == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    val pending = record.status == "pending"
                    val changed = body != record.body || description != record.description
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(record.title + (if(record.kind=="skill_file") "/${record.path}" else "") + " · " + reviewStatus(record.status))
                        if (record.origin == "learning") Text(stringResource(R.string.orbit_memory_inferred),
                            style = MaterialTheme.typography.bodySmall)
                        if (record.evidence.isNotBlank() && record.evidence != "[]") {
                            TextButton(enabled = !busy, onClick = { run {
                                val sources = org.json.JSONArray(record.evidence)
                                val ids = (0 until sources.length()).map {
                                    sources.getJSONObject(it).getLong("message_id")
                                }.distinct().take(8)
                                val recall = com.ai.assistance.operit.data.repository.ChatRecallRepository(context, record.sourceChatId)
                                evidenceText = ids.mapNotNull { recall.message(it)?.content?.take(2000) }.joinToString("\n\n")
                                    .ifBlank { context.getString(R.string.orbit_memory_source_missing) }
                            } }) { Text(stringResource(R.string.orbit_memory_view_evidence)) }
                            evidenceText?.let {
                                Text(stringResource(R.string.orbit_memory_evidence_current), style = MaterialTheme.typography.bodySmall)
                                Text(it)
                            }
                        }
                        if (record.before.isNotEmpty()) {
                            Text(stringResource(R.string.memory_review_before))
                            OutlinedTextField(record.before, {}, readOnly = true, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                        }
                        if (record.kind == "skill") OutlinedTextField(description, { description = it },
                            readOnly = !pending, enabled = !busy, label = { Text(stringResource(R.string.memory_review_description)) })
                        if (record.kind=="skill_delete") Text(stringResource(R.string.memory_review_delete_skill))
                        else if (record.kind == "skill_file" && record.operation == "remove") Text(stringResource(R.string.orbit_skill_restore_removes_file))
                        else OutlinedTextField(body, { body = it }, readOnly = !pending, enabled = !busy,
                            label = { Text(stringResource(R.string.memory_review_after)) },
                            modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 12)
                        record.files.toSortedMap().forEach { (path, text) ->
                            OutlinedTextField(text, {}, readOnly = true,
                                label = { Text(path) }, modifier = Modifier.fillMaxWidth(),
                                minLines = 2, maxLines = 8)
                        }
                        if (record.audits.isNotBlank()) Text(record.audits)
                        if (memorySpace?.ownerOrbitId != null && record.kind == "skill_file" && record.status == "approved") {
                            Text(stringResource(R.string.orbit_skill_restore_help), style = MaterialTheme.typography.bodySmall)
                            for (beforeChange in listOf(true, false)) {
                                TextButton(enabled = !busy, onClick = { run {
                                    val proposal = repo.proposeSkillFileRestore(context, record.id, beforeChange)
                                    selectedId = proposal.id; body = proposal.body; description = proposal.description; reason = ""
                                } }) { Text(stringResource(if (beforeChange) R.string.orbit_skill_restore_before else R.string.orbit_skill_restore_after)) }
                            }
                        }
                        if (record.reviewedAt > 0) {
                            val detailReason = if (record.reason.startsWith("Memory was corrected") ||
                                record.reason == "Memory changed while this proposal was being applied")
                                stringResource(R.string.orbit_memory_proposal_obsolete) else record.reason
                            Text("${record.reviewer} · ${DateFormat.getDateTimeInstance().format(Date(record.reviewedAt))}\n$detailReason")
                        }
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
                status()
                Row {
                    TextButton(onClick = { leave("back") }, enabled = !busy) { Text(stringResource(R.string.chat_recall_back)) }
                }
            }
        }
    }
    }
    if (discardTarget != null) {
        val discard = {
            val target = discardTarget
            discardTarget = null
            if (target == "close") onDismiss() else selectedId = null
        }
        if (MemorySearchSettingsPreferences.isOrbitSpace(profileId)) {
            com.ai.assistance.operit.ui.features.orbit.OrbitConfirmDialog(
                title = stringResource(R.string.memory_notes_discard_title),
                onConfirm = discard,
                onDismiss = { discardTarget = null },
                destructive = true,
            )
        } else {
            AlertDialog(
                onDismissRequest = { discardTarget = null },
                title = { Text(stringResource(R.string.memory_notes_discard_title)) },
                confirmButton = { TextButton(onClick = discard) { Text(stringResource(R.string.confirm)) } },
                dismissButton = { TextButton(onClick = { discardTarget = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

@Composable
private fun reviewStatus(status: String): String = stringResource(when (status) {
    "approved" -> R.string.memory_review_approved
    "rejected" -> R.string.memory_review_rejected
    "superseded" -> R.string.memory_review_superseded
    "applying" -> R.string.memory_review_applying
    else -> R.string.memory_review_pending
})
