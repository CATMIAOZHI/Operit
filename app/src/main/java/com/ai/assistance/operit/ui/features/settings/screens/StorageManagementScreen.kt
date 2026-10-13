package com.ai.assistance.operit.ui.features.settings.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.util.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(coil.annotation.ExperimentalCoilApi::class)
@Composable
fun StorageManagementScreen(
    onManageComponents: () -> Unit,
    onManageChats: () -> Unit,
    onBackup: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val refresh: (Boolean) -> Unit = { clearPreview ->
        if (!busy) {
            busy = true
            failed = false
            cleared = false
            scope.launch {
                try {
                    usage = withContext(Dispatchers.IO) {
                        if (clearPreview) {
                            // Let Coil synchronize its own journal; never delete its directory externally.
                            OperitApplication.globalImageLoader.diskCache?.clear()
                        }
                        val dataDir = File(context.applicationInfo.dataDir)
                        val roots = listOf(dataDir) +
                            context.getExternalFilesDirs(null).filterNotNull() +
                            context.externalCacheDirs.filterNotNull()
                        measureStorage(
                            roots,
                            classify = { storageCategory(it.relativeTo(dataDir).path) },
                            checkCancelled = { ensureActive() }
                        )
                    }
                    cleared = clearPreview
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    AppLogger.e("StorageManagement", "Storage operation failed", e)
                } finally {
                    busy = false
                }
            }
        }
    }
    val latestRefresh by rememberUpdatedState(refresh)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) latestRefresh(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { latestRefresh(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.storage_data_total), style = MaterialTheme.typography.titleMedium)
                Text(
                    usage?.let { Formatter.formatFileSize(context, it.totalBytes) }
                        ?: stringResource(R.string.storage_calculating),
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            IconButton(onClick = { refresh(false) }, enabled = !busy) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.storage_refresh))
            }
        }
        Text(
            stringResource(R.string.storage_scope),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) Text(stringResource(R.string.storage_failed), color = MaterialTheme.colorScheme.error)
        if (cleared) Text(stringResource(R.string.storage_cache_cleared))
        if ((usage?.skippedEntries ?: 0) > 0) Text(stringResource(R.string.storage_partial))
        StorageCategory.entries.forEach { category ->
            val title = when (category) {
                StorageCategory.MEDIA -> R.string.storage_media
                StorageCategory.COMPONENTS -> R.string.storage_components
                StorageCategory.DATABASES -> R.string.storage_databases
                StorageCategory.PREVIEW_CACHE -> R.string.storage_preview_cache
                StorageCategory.OTHER -> R.string.storage_other
            }
            val description = when (category) {
                StorageCategory.MEDIA -> R.string.storage_media_hint
                StorageCategory.COMPONENTS -> R.string.storage_components_hint
                StorageCategory.DATABASES -> R.string.storage_databases_hint
                StorageCategory.PREVIEW_CACHE -> R.string.storage_preview_hint
                StorageCategory.OTHER -> R.string.storage_other_hint
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(usage?.let { Formatter.formatFileSize(context, it.bytes.getValue(category)) } ?: "—")
                    }
                    Text(stringResource(description), style = MaterialTheme.typography.bodySmall)
                    when (category) {
                        StorageCategory.COMPONENTS -> TextButton(onClick = onManageComponents, enabled = !busy) {
                            Text(stringResource(R.string.components_title))
                        }
                        StorageCategory.DATABASES -> TextButton(onClick = onManageChats, enabled = !busy) {
                            Text(stringResource(R.string.settings_chat_history_management))
                        }
                        StorageCategory.PREVIEW_CACHE -> TextButton(
                            onClick = { confirmClear = true },
                            enabled = !busy && (usage?.bytes?.get(category) ?: 0L) > 0L
                        ) { Text(stringResource(R.string.storage_clear_preview)) }
                        else -> Unit
                    }
                }
            }
        }
        OutlinedButton(onClick = onBackup, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_data_backup))
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.storage_clear_preview)) },
            text = { Text(stringResource(R.string.storage_clear_preview_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; refresh(true) }) {
                    Text(stringResource(R.string.storage_clear_preview))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
