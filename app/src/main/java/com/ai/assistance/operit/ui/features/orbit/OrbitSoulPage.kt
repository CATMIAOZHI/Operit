package com.ai.assistance.operit.ui.features.orbit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.features.orbit.OrbitSoulRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitSoulPage(orbitId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(orbitId) { OrbitSoulRepository(context, orbitId) }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<OrbitSoulRepository.Snapshot?>(null) }
    var selected by rememberSaveable(orbitId) { mutableStateOf<String?>(null) }
    var restore by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { error = null; block(); snapshot = repository.snapshot() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = context.getString(R.string.orbit_soul_action_failed)
                try { snapshot = repository.snapshot() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The retry action remains available for a missing document. */ }
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(orbitId, retry) { act {} }
    fun back() { if (selected != null) selected = null else onBack() }
    BackHandler { back() }
    val list = rememberLazyListState()
    val detailList = key(selected) { rememberLazyListState() }
    val detail = snapshot?.history?.firstOrNull { it.id == selected }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.orbit_soul_title)) },
        navigationIcon = { IconButton(onClick = ::back) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
        } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = if (detail == null) list else detailList,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { retry++ }) { Text(stringResource(R.string.orbit_memory_retry)) }
            } }
            if (detail == null) {
                item { Text(stringResource(R.string.orbit_soul_help)) }
                items(snapshot?.history.orEmpty().filter { it.state == "approved" }.sortedWith(compareBy<OrbitSoulRepository.Version> {
                    if (it.id == snapshot?.current?.id) 0 else 1
                }.thenByDescending { it.createdAt }), key = { it.id }) { version ->
                    Card(onClick = { selected = version.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(when {
                                version.id == snapshot?.current?.id -> R.string.orbit_soul_current
                                else -> R.string.orbit_soul_history
                            }), style = MaterialTheme.typography.titleMedium)
                            Text(soulReason(version))
                            Text(DateFormat.getDateTimeInstance().format(Date(version.createdAt)),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                item {
                    Text(soulReason(detail), style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(detail.body) }
                }
                item {
                    if (detail.state == "approved" && detail.id != snapshot?.current?.id) {
                        FilledTonalButton(enabled = !busy, onClick = { restore = detail.id }) {
                            Text(stringResource(R.string.orbit_soul_restore))
                        }
                    }
                }
            }
        }
    }
    restore?.let { id ->
        AlertDialog(onDismissRequest = { if (!busy) restore = null },
            title = { Text(stringResource(R.string.orbit_soul_restore)) },
            text = { Text(stringResource(R.string.orbit_soul_restore_help)) },
            confirmButton = { TextButton(enabled = !busy, onClick = { act {
                repository.restore(id, checkNotNull(snapshot).current.id)
                restore = null; selected = null
            } }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { restore = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun soulReason(version: OrbitSoulRepository.Version): String = when (version.source) {
    "seed" -> stringResource(R.string.orbit_soul_seed)
    "import" -> stringResource(R.string.orbit_soul_import)
    "restore" -> stringResource(R.string.orbit_soul_restored)
    else -> version.reason
}
