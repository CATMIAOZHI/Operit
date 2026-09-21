package com.ai.assistance.operit.ui.features.memory.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.MessageEntity
import com.ai.assistance.operit.data.preferences.MemoryExtractionLog
import com.ai.assistance.operit.data.preferences.MemoryExtractionLogRepository
import com.ai.assistance.operit.data.repository.MemoryExtractionAuditRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun MemoryExtractionAuditPage(initial: MemoryExtractionLog, profileId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { MemoryExtractionAuditRepository(context) }
    val logs = remember(profileId) { MemoryExtractionLogRepository(context, profileId) }
    var log by remember(initial.id) { mutableStateOf(initial) }
    var offset by remember(initial.id) { mutableIntStateOf(0) }
    var page by remember(initial.id) { mutableStateOf<MemoryExtractionAuditRepository.Page?>(null) }
    var error by remember(initial.id) { mutableStateOf(false) }
    var selected by remember(initial.id) { mutableStateOf<MessageEntity?>(null) }
    var chunk by remember(initial.id) { mutableIntStateOf(0) }
    val listState = remember(initial.id, offset) { androidx.compose.foundation.lazy.LazyListState() }
    LaunchedEffect(initial.id, offset) {
        page = null
        while (true) {
            try {
                log = logs.list().find { it.id == initial.id } ?: log
                page = repo.load(log, offset)
                error = false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = true }
            delay(3000)
        }
    }
    selected?.let { message ->
        val text = message.content
        val length = text.codePointCount(0, text.length)
        val start = (chunk * 8000).coerceAtMost(length)
        val end = (start + 8000).coerceAtMost(length)
        MemoryLibraryPage(stringResource(R.string.memory_audit_message), profileId, { selected = null }) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("${message.sender} · ${chunk + 1} / ${((length + 7999) / 8000).coerceAtLeast(1)}")
                key(message.messageId, chunk) {
                    SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text(text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end)))
                    }
                }
                Row {
                    TextButton(enabled = chunk > 0, onClick = { chunk-- }) { Text(stringResource(R.string.memory_recall_previous)) }
                    TextButton(enabled = end < length, onClick = { chunk++ }) { Text(stringResource(R.string.memory_recall_continue)) }
                }
            }
        }
        return
    }
    MemoryLibraryPage(stringResource(R.string.memory_audit_title), profileId, onBack) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            if (error) Text(stringResource(R.string.memory_notes_io_error), color = MaterialTheme.colorScheme.error)
            key(offset) {
                LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(stringResource(R.string.memory_audit_hint), style = MaterialTheme.typography.bodySmall)
                        Text(DateFormat.getDateTimeInstance().format(Date(log.startedAt)))
                        Text(stringResource(memoryExtractionStatusResource(log.status)))
                        if (log.runId.isNotBlank()) {
                            Text(stringResource(R.string.memory_audit_counts, log.modelRounds, log.toolCalls, log.proposals))
                        } else Text(stringResource(R.string.memory_audit_legacy_counts, log.proposals))
                        if (log.detail.isNotBlank()) SelectionContainer { Text(log.detail) }
                        page?.run?.let { run ->
                            Text(stringResource(R.string.memory_audit_run, run.id, run.status), style = MaterialTheme.typography.bodySmall)
                            if (log.status == "failed" && run.status == "COMPLETED") {
                                Text(stringResource(R.string.memory_audit_legacy_status))
                            }
                            run.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        if (page == null && !error) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (page != null && page?.run == null) Text(stringResource(R.string.memory_audit_unavailable))
                    }
                    items(page?.messages.orEmpty(), key = { it.messageId }) { message ->
                        Column(Modifier.fillMaxWidth().clickable { chunk = 0; selected = message }.padding(vertical = 8.dp)) {
                            Text(message.sender, style = MaterialTheme.typography.titleSmall)
                            Text(message.content.take(400), maxLines = 5, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.memory_audit_message), color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider()
                    }
                }
            }
            Row {
                TextButton(enabled = offset > 0, onClick = { offset = (offset - 10).coerceAtLeast(0) }) {
                    Text(stringResource(R.string.memory_audit_previous_page))
                }
                TextButton(enabled = page?.more == true, onClick = { offset += 10 }) {
                    Text(stringResource(R.string.memory_audit_next_page))
                }
            }
        }
    }
}

internal fun memoryExtractionStatusResource(status: String): Int = when (status) {
    "success" -> R.string.memory_extraction_success
    "warnings" -> R.string.memory_extraction_warnings
    "partial" -> R.string.memory_extraction_partial
    "timeout" -> R.string.memory_extraction_timeout
    "failed" -> R.string.memory_extraction_failed
    "cancelled" -> R.string.memory_extraction_cancelled
    else -> R.string.memory_extraction_running
}
