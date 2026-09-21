package com.ai.assistance.operit.ui.features.memory.screens.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.features.memory.screens.MemoryLibraryPage
import com.ai.assistance.operit.ui.features.memory.screens.MemoryExtractionAuditPage
import com.ai.assistance.operit.ui.features.memory.screens.memoryExtractionStatusResource
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.preferences.MemoryExtractionLog
import com.ai.assistance.operit.data.preferences.MemoryExtractionLogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun MemoryExtractionLogDialog(profileId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(profileId) { MemoryExtractionLogRepository(context, profileId) }
    var logs by remember { mutableStateOf<List<MemoryExtractionLog>>(emptyList()) }
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf(false) }
    var selected by remember(profileId) { mutableStateOf<MemoryExtractionLog?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(repo) {
        while (true) {
            try {
                logs = repo.list()
                val dao = AppDatabase.getDatabase(context).chatDao()
                titles = logs.map { it.sourceChatId }.distinct().filter { it.isNotBlank() }
                    .associateWith { dao.getChatById(it)?.title.orEmpty() }
                error = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = true }
            delay(3000)
        }
    }
    selected?.let { log ->
        MemoryExtractionAuditPage(log, profileId) { selected = null }
        return
    }
    MemoryLibraryPage(stringResource(R.string.memory_extraction_logs), profileId, onDismiss) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.memory_extraction_logs_hint), style = MaterialTheme.typography.bodySmall)
                if (error) Text(stringResource(R.string.memory_notes_io_error), color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (logs.isEmpty()) item { Text(stringResource(R.string.memory_review_empty)) }
                    items(logs, key = { it.id }) { log ->
                        Column(Modifier.fillMaxWidth().clickable { selected = log }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(DateFormat.getDateTimeInstance().format(Date(log.startedAt)))
                                Text(titles[log.sourceChatId]?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.memory_extraction_unknown_source))
                                Text(listOfNotNull(
                                    if (log.graph) stringResource(R.string.memory_extraction_graph) else null,
                                    if (log.notes) "memory.md" else null,
                                    if (log.skills) stringResource(R.string.memory_extraction_skills) else null
                                ).joinToString(" · "))
                                Text(stringResource(memoryExtractionStatusResource(log.status)))
                                if (log.finishedAt > 0) Text(stringResource(R.string.memory_extraction_result,
                                    (log.finishedAt - log.startedAt) / 1000, log.proposals))
                                if (log.detail.isNotBlank()) Text(log.detail, style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.memory_audit_title), color = MaterialTheme.colorScheme.primary)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
