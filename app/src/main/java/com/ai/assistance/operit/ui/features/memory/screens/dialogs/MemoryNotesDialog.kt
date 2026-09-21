package com.ai.assistance.operit.ui.features.memory.screens.dialogs

import androidx.compose.foundation.layout.*
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
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.MemoryNotesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MemoryNotesDialog(profileId: String, profileName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(profileId) { MemoryNotesRepository(context, profileId) }
    val settings = remember(profileId) {
        com.ai.assistance.operit.data.preferences.MemorySearchSettingsPreferences(context, profileId)
    }
    val inject by remember(settings) { settings.observeInjectNotes() }.collectAsState(initial = settings.shouldInjectNotes())
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf<MemoryNotesRepository.Snapshot?>(null) }
    var draft by rememberSaveable(profileId) { mutableStateOf<String?>(null) }
    var openedVersion by rememberSaveable(profileId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf<MemoryNotesRepository.Snapshot?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val ioError = stringResource(R.string.memory_notes_io_error)
    val conflictError = stringResource(R.string.memory_notes_conflict)
    LaunchedEffect(repository) {
        try {
            base = repository.load()
            if (draft == null) draft = base!!.markdown
            if (openedVersion != null && openedVersion != base!!.version) {
                conflict = base
                error = conflictError
            }
            if (openedVersion == null) openedVersion = base!!.version
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = ioError
        }
    }
    fun dismiss() {
        if (!busy) {
            if (draft != null && draft != base?.markdown) confirmDiscard = true else onDismiss()
        }
    }
    fun save(version: String) {
        scope.launch {
            busy = true
            try {
                val latest = repository.load()
                if (latest.version != version) throw MemoryNotesRepository.NotesException(
                    MemoryNotesRepository.Failure.CONFLICT
                )
                val reviews = com.ai.assistance.operit.data.preferences.MemoryReviewRepository(context, profileId)
                val change = reviews.proposeNotes(latest, draft.orEmpty())
                reviews.decide(context, change.id, true, "user", context.getString(R.string.memory_review_manual_edit))
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MemoryNotesRepository.NotesException) {
                error = if (e.reason == MemoryNotesRepository.Failure.CONFLICT) conflictError
                    else context.getString(R.string.memory_notes_full)
                try {
                    conflict = repository.load()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = ioError
                }
            } catch (e: Exception) {
                error = ioError
            } finally {
                busy = false
            }
        }
    }
    MemoryLibraryPage(stringResource(R.string.memory_notes_title), profileId, ::dismiss) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.memory_notes_inject), modifier = Modifier.weight(1f))
                    Switch(checked = inject, onCheckedChange = {
                        settings.setInjectNotes(it)
                    })
                }
                Text(stringResource(R.string.memory_notes_editor_hint), style = MaterialTheme.typography.bodySmall)
                if (base == null && error == null) CircularProgressIndicator()
                OutlinedTextField(
                    value = draft.orEmpty(),
                    onValueChange = { draft = it },
                    enabled = base != null && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 12, maxLines = 24,
                    isError = draft.orEmpty().length > MemoryNotesRepository.MAX_CHARS,
                    supportingText = {
                        Text("${draft.orEmpty().length} / ${MemoryNotesRepository.MAX_CHARS}")
                    }
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                conflict?.let { latest ->
                    Text(stringResource(R.string.memory_notes_latest), style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = latest.markdown, onValueChange = {}, readOnly = true,
                        modifier = Modifier.fillMaxWidth(), maxLines = 6
                    )
                    TextButton(
                        enabled = !busy && draft.orEmpty().length <= MemoryNotesRepository.MAX_CHARS,
                        onClick = { save(latest.version) }
                    ) { Text(stringResource(R.string.memory_notes_overwrite)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = ::dismiss, enabled = !busy) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = { base?.let { save(it.version) } },
                        enabled = base != null && conflict == null && !busy &&
                            draft.orEmpty().length <= MemoryNotesRepository.MAX_CHARS
                    ) { Text(stringResource(R.string.save_action)) }
                }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.memory_notes_discard_title)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
