package com.ai.assistance.operit.ui.features.packages.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.system.Terminal
import com.ai.assistance.operit.data.terminal.startup.TerminalStartupLaunchMode
import com.ai.assistance.operit.data.terminal.startup.TerminalStartupServiceConfig
import com.ai.assistance.operit.data.terminal.startup.TerminalStartupServiceManager
import com.ai.assistance.operit.data.terminal.startup.TerminalStartupServiceRepository
import com.ai.assistance.operit.data.terminal.startup.TerminalStartupServiceState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalStartupServicesScreen(onOpenTerminal: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { TerminalStartupServiceRepository.getInstance(context) }
    val loadErrorMessage = remember(repository) { repository.loadErrorMessage() }
    val manager = remember { TerminalStartupServiceManager.getInstance(context) }
    val services by repository.services.collectAsState()
    val statuses by manager.statuses.collectAsState()
    val logs by manager.logs.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<TerminalStartupServiceConfig?>(null) }
    var creating by remember { mutableStateOf(false) }
    var logServiceId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<TerminalStartupServiceConfig?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (loadErrorMessage == null) {
                FloatingActionButton(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.terminal_startup_add))
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.terminal_startup_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (loadErrorMessage != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = loadErrorMessage,
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            } else if (services.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.terminal_startup_empty),
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
            items(services, key = { it.id }) { service ->
                val status = statuses[service.id]
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(service.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = statusLabel(status?.state ?: TerminalStartupServiceState.STOPPED),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = statusColor(status?.state ?: TerminalStartupServiceState.STOPPED)
                                )
                            }
                            Switch(
                                checked = service.enabled,
                                onCheckedChange = { enabled ->
                                    val updated = service.copy(enabled = enabled)
                                    scope.launch {
                                        runCatching {
                                            repository.upsert(updated)
                                            if (enabled) manager.startServiceAsync(updated) else manager.stopServiceAsync(service.id)
                                        }.onFailure { snackbar.showSnackbar(it.message.orEmpty()) }
                                    }
                                }
                            )
                        }
                        Text(
                            text = if (service.launchMode == TerminalStartupLaunchMode.COMMAND) service.command else service.scriptDisplayName.orEmpty(),
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        HorizontalDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { manager.startServiceAsync(service) },
                                enabled = service.enabled
                            ) { Icon(Icons.Default.PlayArrow, stringResource(R.string.terminal_startup_start)) }
                            IconButton(onClick = { manager.stopServiceAsync(service.id) }) {
                                Icon(Icons.Default.Stop, stringResource(R.string.terminal_startup_stop))
                            }
                            IconButton(
                                onClick = {
                                    val sessionId = manager.getManagedSessionId(service.id) ?: return@IconButton
                                    Terminal.getInstance(context).switchToSession(sessionId)
                                    onOpenTerminal()
                                },
                                enabled = manager.getManagedSessionId(service.id) != null
                            ) { Icon(Icons.Default.Terminal, stringResource(R.string.terminal_startup_open_terminal)) }
                            IconButton(onClick = { logServiceId = service.id }) {
                                Icon(Icons.Default.ListAlt, stringResource(R.string.terminal_startup_logs))
                            }
                            IconButton(onClick = { editing = service }) {
                                Icon(Icons.Default.Edit, stringResource(R.string.edit))
                            }
                            IconButton(onClick = { deleting = service }) {
                                Icon(Icons.Default.Delete, stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        TerminalStartupServiceEditor(
            initial = editing,
            newId = remember(creating) { if (creating) repository.newServiceId() else editing?.id.orEmpty() },
            onDismiss = { creating = false; editing = null },
            onSave = { config, selectedScript, selectedScriptName ->
                scope.launch {
                    runCatching {
                        val saved = if (selectedScript != null) {
                            val (path, name) = repository.importScript(config.id, selectedScript, selectedScriptName)
                            config.copy(scriptPath = path, scriptDisplayName = name)
                        } else config
                        repository.upsert(saved)
                        if (saved.enabled) manager.startServiceAsync(saved) else manager.stopServiceAsync(saved.id)
                        creating = false
                        editing = null
                    }.onFailure { snackbar.showSnackbar(it.message.orEmpty()) }
                }
            }
        )
    }

    logServiceId?.let { serviceId ->
        AlertDialog(
            onDismissRequest = { logServiceId = null },
            title = { Text(stringResource(R.string.terminal_startup_logs)) },
            text = {
                Text(
                    text = logs[serviceId].orEmpty().ifBlank { stringResource(R.string.terminal_startup_no_logs) },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = { TextButton(onClick = { logServiceId = null }) { Text(stringResource(R.string.close)) } }
        )
    }

    deleting?.let { service ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.terminal_startup_delete_title)) },
            text = { Text(stringResource(R.string.terminal_startup_delete_message, service.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        manager.stopService(service.id)
                        repository.delete(service.id)
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun TerminalStartupServiceEditor(
    initial: TerminalStartupServiceConfig?,
    newId: String,
    onDismiss: () -> Unit,
    onSave: (TerminalStartupServiceConfig, Uri?, String?) -> Unit
) {
    val context = LocalContext.current
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var mode by remember(initial) { mutableStateOf(initial?.launchMode ?: TerminalStartupLaunchMode.COMMAND) }
    var command by remember(initial) { mutableStateOf(initial?.command.orEmpty()) }
    var cwd by remember(initial) { mutableStateOf(initial?.workingDirectory.orEmpty()) }
    var envText by remember(initial) { mutableStateOf(initial?.environment?.entries?.joinToString("\n") { "${it.key}=${it.value}" }.orEmpty()) }
    var host by remember(initial) { mutableStateOf(initial?.healthCheckHost ?: "127.0.0.1") }
    var portText by remember(initial) { mutableStateOf(initial?.healthCheckPort?.toString().orEmpty()) }
    var timeoutText by remember(initial) { mutableStateOf(((initial?.startupTimeoutMs ?: 30_000L) / 1000).toString()) }
    var autoRestart by remember(initial) { mutableStateOf(initial?.autoRestart ?: true) }
    var selectedUri by remember(initial) { mutableStateOf<Uri?>(null) }
    var selectedName by remember(initial) { mutableStateOf(initial?.scriptDisplayName) }
    var error by remember(initial) { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.terminal_startup_add else R.string.terminal_startup_edit)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.terminal_startup_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { mode = TerminalStartupLaunchMode.COMMAND }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.terminal_startup_command))
                    }
                    FilledTonalButton(onClick = { mode = TerminalStartupLaunchMode.SCRIPT }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.terminal_startup_script))
                    }
                }
                if (mode == TerminalStartupLaunchMode.COMMAND) {
                    OutlinedTextField(command, { command = it }, label = { Text(stringResource(R.string.terminal_startup_command)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                } else {
                    OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/x-sh", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedName ?: stringResource(R.string.terminal_startup_select_script))
                    }
                }
                OutlinedTextField(cwd, { cwd = it }, label = { Text(stringResource(R.string.terminal_startup_working_directory)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(envText, { envText = it }, label = { Text(stringResource(R.string.terminal_startup_environment)) }, supportingText = { Text(stringResource(R.string.terminal_startup_environment_hint)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.terminal_startup_health_host)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(portText, { portText = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.terminal_startup_health_port)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(timeoutText, { timeoutText = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.terminal_startup_timeout)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.terminal_startup_auto_restart), modifier = Modifier.weight(1f))
                    Switch(checked = autoRestart, onCheckedChange = { autoRestart = it })
                }
                Text(stringResource(R.string.terminal_startup_restart_limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val environment = linkedMapOf<String, String>()
                val envError = runCatching {
                    envText.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                        val separator = line.indexOf('=')
                        require(separator > 0)
                        val key = line.substring(0, separator).trim()
                        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key))
                        environment[key] = line.substring(separator + 1)
                    }
                }.exceptionOrNull()
                val port = portText.toIntOrNull()
                val timeout = timeoutText.toLongOrNull()
                error = when {
                    name.isBlank() -> context.getString(R.string.terminal_startup_error_name)
                    mode == TerminalStartupLaunchMode.COMMAND && command.isBlank() -> context.getString(R.string.terminal_startup_error_command)
                    mode == TerminalStartupLaunchMode.SCRIPT && selectedUri == null && initial?.scriptPath.isNullOrBlank() -> context.getString(R.string.terminal_startup_error_script)
                    envError != null -> context.getString(R.string.terminal_startup_error_environment)
                    portText.isNotBlank() && port !in 1..65535 -> context.getString(R.string.terminal_startup_error_port)
                    timeout == null || timeout !in 1..300 -> context.getString(R.string.terminal_startup_error_timeout)
                    else -> null
                }
                if (error == null) {
                    onSave(
                        TerminalStartupServiceConfig(
                            id = initial?.id ?: newId,
                            name = name.trim(),
                            launchMode = mode,
                            command = command,
                            scriptPath = initial?.scriptPath,
                            scriptDisplayName = initial?.scriptDisplayName,
                            workingDirectory = cwd.trim(),
                            environment = environment,
                            enabled = initial?.enabled ?: true,
                            healthCheckHost = host.trim().ifBlank { "127.0.0.1" },
                            healthCheckPort = port,
                            startupTimeoutMs = timeout!! * 1000,
                            autoRestart = autoRestart,
                            maxRestartAttempts = 3
                        ),
                        selectedUri,
                        selectedName
                    )
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun statusLabel(state: TerminalStartupServiceState): String =
    stringResource(
        when (state) {
            TerminalStartupServiceState.STOPPED -> R.string.terminal_startup_status_stopped
            TerminalStartupServiceState.STARTING -> R.string.terminal_startup_status_starting
            TerminalStartupServiceState.RUNNING -> R.string.terminal_startup_status_running
            TerminalStartupServiceState.RESTARTING -> R.string.terminal_startup_status_restarting
            TerminalStartupServiceState.FAILED -> R.string.terminal_startup_status_failed
        }
    )

@Composable
private fun statusColor(state: TerminalStartupServiceState) =
    when (state) {
        TerminalStartupServiceState.RUNNING -> MaterialTheme.colorScheme.primary
        TerminalStartupServiceState.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
