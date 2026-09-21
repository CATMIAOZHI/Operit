package com.ai.assistance.operit.ui.features.chat.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    var exists by remember(chatId) { mutableStateOf(false) }
    var error by remember(chatId) { mutableStateOf(false) }
    val latestBusy by rememberUpdatedState(busy)
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) {
        while (true) {
            try {
                val status = repository.status(context)
                pending = status.needsRebuild
                exists = status.exists
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { error = true }
            delay(1000)
        }
    }
    if (!exists && !error) return
    Surface(color = if (pending || error) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (pending || error) Text(stringResource(if (error) R.string.system_prefix_error else R.string.system_prefix_pending),
                style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = {
                if (!latestBusy) scope.launch {
                    try {
                        repository.delete()
                        pending = false
                        exists = false
                        error = false
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { error = true }
                }
            }) { Text(stringResource(R.string.system_prefix_rebuild)) }
        }
    }
}
