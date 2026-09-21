package com.ai.assistance.operit.ui.features.memory.screens.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.library.MemoryLearningService
import com.ai.assistance.operit.data.dao.ChatRecallHit
import com.ai.assistance.operit.data.repository.ChatRecallRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatRecallDialog(profileId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { ChatRecallRepository(context) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf(emptyList<ChatRecallHit>()) }
    var nearby by remember { mutableStateOf<List<ChatRecallHit>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun search(more: Boolean = false) {
        scope.launch {
            busy = true
            message = null
            try {
                val page = repository.search(query, if (more) hits.size else 0)
                hits = if (more) (hits + page).distinctBy { it.messageId } else page
                hasMore = page.size == 20
                nearby = null
                if (hits.isEmpty()) message = context.getString(R.string.chat_recall_empty)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = context.getString(R.string.chat_recall_error) }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(.95f).fillMaxHeight(.9f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.chat_recall_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.chat_recall_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = query, onValueChange = { query = it; hasMore = false },
                    label = { Text(stringResource(R.string.chat_recall_query)) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { search() }, enabled = !busy && query.trim().length in 1..200) {
                        Text(stringResource(R.string.chat_recall_search))
                    }
                    if (nearby != null) TextButton(onClick = { nearby = null }, enabled = !busy) {
                        Text(stringResource(R.string.chat_recall_back))
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(nearby ?: hits, key = { it.messageId }) { hit ->
                        Card(onClick = {
                            if (!busy && nearby == null) scope.launch {
                                busy = true
                                try {
                                    nearby = repository.context(hit.messageId)
                                    if (nearby!!.isEmpty()) message = context.getString(R.string.chat_recall_empty)
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { message = context.getString(R.string.chat_recall_error) }
                                finally { busy = false }
                            }
                        }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(hit.chatTitle, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${if (hit.sender == "user") context.getString(R.string.chat_recall_user) else "AI"} · " +
                                        DateFormat.getDateTimeInstance().format(Date(hit.timestamp)),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(hit.excerpt, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (nearby == null && hasMore) item {
                        TextButton(onClick = { search(true) }, enabled = !busy) {
                            Text(stringResource(R.string.chat_recall_more))
                        }
                    }
                }
                val sourceChat = nearby?.firstOrNull()?.chatId
                if (sourceChat != null) {
                    Text(stringResource(R.string.skill_draft_manual_hint), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        scope.launch {
                            busy = true
                            try {
                                MemoryLearningService.extract(context, profileId, sourceChat)
                                message = context.getString(R.string.skill_draft_extracted)
                            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                message = context.getString(R.string.skill_draft_extract_failed)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = context.getString(R.string.skill_draft_extract_failed) }
                            finally { busy = false }
                        }
                    }, enabled = !busy) {
                        Text(stringResource(R.string.skill_draft_extract))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        }
    }
}
