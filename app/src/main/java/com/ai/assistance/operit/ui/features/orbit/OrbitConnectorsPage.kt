package com.ai.assistance.operit.ui.features.orbit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.features.orbit.OrbitConnectorRepository
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitConnectorsPage(orbitId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(orbitId) { OrbitConnectorRepository(context, orbitId) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<OrbitConnectorRepository.Description>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var pendingToggle by remember { mutableStateOf<OrbitConnectorRepository.Description?>(null) }
    var pendingDelete by remember { mutableStateOf<OrbitConnectorRepository.Description?>(null) }
    // Secrets must not enter saved instance state.
    var name by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var bearer by remember { mutableStateOf("") }
    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { error = false; block(); entries = repository.list(); loaded = true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    fun back() {
        if (busy) return
        if (adding) { adding = false; bearer = ""; url = "" } else onBack()
    }
    BackHandler(onBack = ::back)
    LaunchedEffect(orbitId) { act {} }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.orbit_connections_title)) },
        navigationIcon = { IconButton(onClick = ::back, enabled = !busy) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
        } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.orbit_connections_help), style = MaterialTheme.typography.bodyMedium) }
            if (busy || !loaded && !error) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error) item {
                Text(stringResource(R.string.orbit_connections_error), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { act {} }) { Text(stringResource(R.string.memory_review_refresh)) }
            }
            if (adding) item {
                SettingsCard(backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(name, { name = it.take(80) }, enabled = !busy,
                            label = { Text(stringResource(R.string.orbit_connection_name)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(purpose, { purpose = it.take(500) }, enabled = !busy,
                            label = { Text(stringResource(R.string.orbit_connection_purpose)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(url, { url = it.take(2048) }, enabled = !busy, singleLine = true,
                            label = { Text(stringResource(R.string.orbit_connection_url)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(bearer, { bearer = it.take(8192) }, enabled = !busy, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text(stringResource(R.string.orbit_connection_token)) }, modifier = Modifier.fillMaxWidth())
                        val valid = runCatching { OrbitConnectorRepository.validate(name, purpose, url, bearer) }.isSuccess
                        Button(enabled = !busy && valid, onClick = { act {
                            repository.add(name, purpose, url, bearer)
                            adding = false; name = ""; purpose = ""; url = ""; bearer = ""
                        } }) { Text(stringResource(R.string.orbit_connection_save_disabled)) }
                        TextButton(onClick = ::back, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                    }
                }
            } else item {
                Button(enabled = !busy && loaded && entries.size < 20, onClick = { adding = true }) {
                    Text(stringResource(R.string.orbit_connection_add))
                }
            }
            items(entries, key = { it.id }) { connection ->
                SettingsCard(backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text(connection.name, style = MaterialTheme.typography.titleMedium)
                                Text(connection.host, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(connection.enabled, onCheckedChange = {
                                if (it) pendingToggle = connection else act { repository.setEnabled(connection.id, false) }
                            }, enabled = !busy)
                        }
                        Text(connection.purpose)
                        TextButton(enabled = !busy, onClick = { pendingDelete = connection }) {
                            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    pendingToggle?.let { connection ->
        AlertDialog(onDismissRequest = { pendingToggle = null },
            title = { Text(stringResource(R.string.orbit_connection_enable)) },
            text = { Text(stringResource(R.string.orbit_connection_enable_help, connection.name, connection.host)) },
            confirmButton = { TextButton(onClick = {
                pendingToggle = null; act { repository.setEnabled(connection.id, true) }
            }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { pendingToggle = null }) { Text(stringResource(R.string.cancel)) } })
    }
    pendingDelete?.let { connection ->
        AlertDialog(onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.orbit_connection_delete_help, connection.name)) },
            confirmButton = { TextButton(onClick = {
                pendingDelete = null; act { repository.delete(connection.id) }
            }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
