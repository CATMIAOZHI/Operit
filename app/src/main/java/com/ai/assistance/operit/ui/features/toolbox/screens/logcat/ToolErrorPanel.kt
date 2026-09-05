package com.ai.assistance.operit.ui.features.toolbox.screens.logcat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.ToolErrorPage
import com.ai.assistance.operit.core.tools.ToolErrorRecord
import com.ai.assistance.operit.core.tools.ToolErrorRepository
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
internal fun ToolErrorPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { ToolErrorRepository.getInstance(context) }
    val scope = rememberCoroutineScope()
    var tool by rememberSaveable { mutableStateOf<String?>(null) }
    var days by rememberSaveable { mutableStateOf(0) }
    var page by rememberSaveable { mutableStateOf(0) }
    var data by remember { mutableStateOf<ToolErrorPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    val toolListState = rememberLazyListState()
    val recordListState = rememberLazyListState()
    var previousQuery by rememberSaveable { mutableStateOf("$days:$page:$tool") }
    var failure by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ToolErrorRecord?>(null) }
    val recordingFailed by repository.recordingFailed.collectAsState()
    val since = remember(days) { if (days == 0) 0L else System.currentTimeMillis() - days * 86_400_000L }
    val savedFormat = stringResource(R.string.logcat_saved_to)
    val failedFormat = stringResource(R.string.logcat_save_failed)
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    LaunchedEffect(tool, days, page) {
        loading = true
        failure = null
        val query = "$days:$page:$tool"
        if (previousQuery != query) recordListState.scrollToItem(0)
        previousQuery = query
        repository.changes.collectLatest {
            try {
                data = repository.load(tool, since, page)
                failure = null
                loading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
                loading = false
            }
        }
    }

    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tool_errors_scope), style = MaterialTheme.typography.bodySmall)
        if (recordingFailed) {
            Text(stringResource(R.string.tool_errors_recording_failed), color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to R.string.tool_errors_all_time, 1 to R.string.tool_errors_day, 7 to R.string.tool_errors_week).forEach { (value, label) ->
                FilterChip(selected = days == value, onClick = {
                    days = value; tool = null; page = 0; exportMessage = null
                }, label = { Text(stringResource(label)) })
            }
        }
        data?.let { snapshot ->
            Text(
                stringResource(R.string.tool_errors_totals, snapshot.counts.sumOf { it.count }, snapshot.counts.size),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.tool_errors_breakdown, snapshot.counts.sumOf { it.failures }, snapshot.counts.sumOf { it.parameterIssues }),
                style = MaterialTheme.typography.bodySmall,
            )
            LazyRow(state = toolListState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = tool == null, onClick = { tool = null; page = 0 },
                        label = { Text(stringResource(R.string.tool_errors_all_tools)) })
                }
                items(snapshot.counts, key = { it.toolName }) { count ->
                    FilterChip(selected = tool == count.toolName,
                        onClick = { tool = count.toolName; page = 0 },
                        label = { Text("${count.toolName} · ${count.count}") })
                }
            }
        }
        Button(
            enabled = !exporting && !loading && failure == null && (data?.total ?: 0) > 0,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                exporting = true
                exportMessage = null
                val selectedTool = tool
                val selectedSince = since
                scope.launch {
                    try {
                        val path = ToolErrorExportHelper.export(context, selectedTool, selectedSince)
                        exportMessage = savedFormat.format(path)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        exportMessage = failedFormat.format(e.message ?: e.javaClass.simpleName)
                    } finally {
                        exporting = false
                    }
                }
            },
        ) {
            Text(stringResource(if (exporting) R.string.tool_errors_exporting else R.string.tool_errors_export))
        }
        exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
        else Spacer(Modifier.height(4.dp))
        val snapshot = data
        if (snapshot != null) {
            LazyColumn(state = recordListState, modifier = Modifier.weight(1f)) {
                if (snapshot.records.isEmpty()) item { Text(stringResource(R.string.tool_errors_empty)) }
                items(snapshot.records, key = { it.id }) { record ->
                    Column(
                        Modifier.fillMaxWidth().clickable(enabled = !loading) { detail = record }.padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(record.toolName, style = MaterialTheme.typography.titleSmall)
                        Text(dateFormat.format(Date(record.occurredAt)), style = MaterialTheme.typography.labelSmall)
                        Text(stringResource(if (record.executionFailed) R.string.tool_errors_failed else R.string.tool_errors_parameter_only),
                            style = MaterialTheme.typography.labelSmall)
                        Text(record.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.tool_errors_parameters, record.parameters.joinToString { it.name }),
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider()
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !loading && page > 0, onClick = { page-- }) { Text(stringResource(R.string.tool_errors_previous)) }
                Text(stringResource(R.string.tool_errors_page, page + 1, ((snapshot.total + ToolErrorRepository.PAGE_SIZE - 1) / ToolErrorRepository.PAGE_SIZE).coerceAtLeast(1)))
                TextButton(enabled = !loading && (page + 1L) * ToolErrorRepository.PAGE_SIZE < snapshot.total, onClick = { page++ }) {
                    Text(stringResource(R.string.tool_errors_next))
                }
            }
        }
    }
    detail?.let { record ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(record.toolName) },
            text = {
                SelectionContainer {
                    LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Text(dateFormat.format(Date(record.occurredAt))) }
                        item { Text(stringResource(if (record.executionFailed) R.string.tool_errors_failed else R.string.tool_errors_parameter_only)) }
                        item { Text(record.error) }
                        items(record.parameters.withIndex().toList(), key = { it.index }) { (_, parameter) ->
                            Column {
                                Text(parameter.name, style = MaterialTheme.typography.titleSmall)
                                Text(parameter.value)
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text(stringResource(R.string.tool_errors_close)) } },
        )
    }
}
