package com.ai.assistance.operit.ui.features.chat.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.LearningPromptSnapshotRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun SystemPromptRebuildNotice(chatId: String?, busy: Boolean) {
    if (chatId.isNullOrBlank()) return
    val context = LocalContext.current
    val repository = remember(chatId) { LearningPromptSnapshotRepository(context, chatId) }
    var pending by remember(chatId) { mutableStateOf(false) }
    var error by remember(chatId) { mutableStateOf(false) }
    var showInfo by remember(chatId) { mutableStateOf(false) }
    var refreshing by remember(chatId) { mutableStateOf(false) }
    val latestBusy by rememberUpdatedState(busy)
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) {
        while (true) {
            try {
                val status = repository.status(context)
                if (!refreshing) {
                    pending = status.needsRebuild
                    if (!pending) showInfo = false
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A read failure is not evidence that the system prefix changed.
            }
            delay(1000)
        }
    }
    if (!pending) return
    Box(Modifier.fillMaxWidth().padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
        IconButton(onClick = { error = false; showInfo = true }) {
            Icon(Icons.Outlined.ErrorOutline,
                contentDescription = stringResource(R.string.system_prefix_updated),
                tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { if (!refreshing) showInfo = false },
            title = { Text(stringResource(R.string.system_prefix_updated)) },
            text = {
                Column {
                    Text(stringResource(R.string.system_prefix_pending))
                    if (error) Text(stringResource(R.string.system_prefix_error),
                        color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(enabled = !busy && !refreshing, onClick = {
                if (!latestBusy && !refreshing) scope.launch {
                    refreshing = true
                    try {
                        repository.delete()
                        pending = false
                        showInfo = false
                        error = false
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { error = true }
                    finally { refreshing = false }
                }
            }) { Text(stringResource(R.string.system_prefix_rebuild)) } },
            dismissButton = { TextButton(enabled = !refreshing, onClick = { showInfo = false }) {
                Text(stringResource(R.string.system_prefix_later))
            } }
        )
    }
}
