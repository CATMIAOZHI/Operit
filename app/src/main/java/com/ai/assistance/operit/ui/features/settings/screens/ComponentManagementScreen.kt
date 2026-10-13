package com.ai.assistance.operit.ui.features.settings.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.speech.LocalVoiceModels
import com.ai.assistance.operit.util.OnDemandResources
import java.io.File
import kotlinx.coroutines.*

private data class ComponentStorage(val bytes: Long, val ready: Boolean)

/** A fixed catalog, never a file browser: only replaceable component resources can be removed. */
@Composable
fun ComponentManagementScreen() {
    com.ai.assistance.operit.ui.components.InlineResourceDownloadHost()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups = remember(context) { OnDemandResources.componentGroups(context) }
    val downloads by OnDemandResources.downloads.collectAsState()
    val pending by OnDemandResources.pendingRequests.collectAsState()
    var storage by remember { mutableStateOf<Map<String, ComponentStorage>>(emptyMap()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun label(id: String): String = LocalVoiceModels.models.firstOrNull { "voice-${it.id}" == id }?.name
        ?: context.getString(groups.first { it.id == id }.labelRes)

    LaunchedEffect(context) {
        while (isActive) {
            val snapshot = withContext(Dispatchers.IO) {
                groups.associate { group ->
                    group.id to ComponentStorage(
                        group.items.sumOf { it.target.length() + File(it.target.parentFile, "${it.target.name}.part").length() },
                        group.items.all { it.target.isFile && it.target.length() == it.resource.bytes }
                    )
                }
            }
            storage = snapshot
            selected = OnDemandResources.selectedVoiceModels(context)
            installed = withContext(Dispatchers.IO) { OnDemandResources.terminalInstalled(context) }
            delay(1000)
        }
    }
    fun active(id: String) = busy == id || pending.any { it.id == id } || OnDemandResources.isGroupActive(id)
    fun protected(id: String) = id.removePrefix("voice-") in selected

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(stringResource(R.string.components_intro), style = MaterialTheme.typography.bodyMedium)
        }
        error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        items(groups, key = { it.id }) { group ->
            val saved = storage[group.id]
            val progress = downloads[group.id]
            val working = active(group.id)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label(group.id), style = MaterialTheme.typography.titleMedium)
                    val model = LocalVoiceModels.models.firstOrNull { "voice-${it.id}" == group.id }
                    Text(stringResource(model?.descriptionRes ?: when (group.labelRes) {
                        R.string.resource_terminal -> R.string.component_terminal_detail
                        R.string.resource_offline_speech -> R.string.voice_legacy_description
                        else -> R.string.component_export_detail
                    }), style = MaterialTheme.typography.bodySmall)
                    if (group.labelRes == R.string.resource_terminal && installed)
                        Text(stringResource(R.string.component_terminal_installed), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.component_download_size,
                        Formatter.formatFileSize(context, group.items.sumOf { it.resource.bytes })),
                        style = MaterialTheme.typography.bodySmall)
                    Text(if (saved == null) stringResource(R.string.component_loading) else
                        stringResource(R.string.component_storage,
                            stringResource(when {
                                saved.ready -> R.string.component_ready
                                saved.bytes > 0 -> R.string.component_partial
                                else -> R.string.component_missing
                            }), Formatter.formatFileSize(context, saved.bytes)),
                        style = MaterialTheme.typography.bodySmall)
                    if (protected(group.id)) Text(stringResource(R.string.voice_model_delete_selected),
                        style = MaterialTheme.typography.bodySmall)
                    if (working) {
                        if (progress != null && progress.error == null) {
                            LinearProgressIndicator(
                                progress = { (progress.downloaded.toFloat() / progress.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth())
                            Text("${Formatter.formatFileSize(context, progress.downloaded)} / ${Formatter.formatFileSize(context, progress.total)}")
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    progress?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (working) {
                            TextButton(onClick = {
                                OnDemandResources.decline(group.id)
                                OnDemandResources.cancel(group.id)
                            }) { Text(stringResource(R.string.resource_download_cancel)) }
                        } else {
                            TextButton(enabled = saved != null && !saved.ready && busy == null, onClick = {
                                busy = group.id
                                error = null
                                scope.launch {
                                    try { withContext(Dispatchers.IO) { OnDemandResources.downloadComponent(context, group.id) } }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message ?: context.getString(R.string.resource_download_failed_generic) }
                                    finally { busy = null }
                                }
                            }) { Text(stringResource(if (progress?.error != null) R.string.resource_download_retry else R.string.component_download)) }
                        }
                        TextButton(enabled = saved != null && saved.bytes > 0 && !working &&
                            busy == null && !protected(group.id), onClick = { deletion = group.id }) {
                            Text(stringResource(R.string.voice_model_delete_action))
                        }
                    }
                }
            }
        }
    }
    deletion?.let { id ->
        AlertDialog(
            onDismissRequest = { deletion = null },
            title = { Text(stringResource(R.string.voice_model_delete_title, label(id))) },
            text = { Text(stringResource(R.string.component_delete_detail)) },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text(stringResource(R.string.voice_model_keep)) } },
            confirmButton = {
                TextButton(enabled = !active(id) && !protected(id), onClick = {
                    deletion = null
                    busy = id
                    error = null
                    scope.launch {
                        try { OnDemandResources.deleteComponent(context, id) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: context.getString(R.string.voice_model_delete_failed) }
                        finally { busy = null }
                    }
                }) { Text(stringResource(R.string.voice_model_delete_action)) }
            }
        )
    }
}
