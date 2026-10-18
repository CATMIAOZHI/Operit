package com.ai.assistance.operit.ui.features.orbit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.features.orbit.OrbitTaskRepository
import com.ai.assistance.operit.ui.features.chat.components.LocalOrbitChatLook
import com.ai.assistance.operit.ui.features.chat.components.style.cursor.CursorStyleChatMessage
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What the process page has read: the messages, whether reading failed, whether work goes on. */
private data class ProcessRecord(val messages: List<ChatMessage>, val failed: Boolean, val running: Boolean)

/**
 * The full record of one piece of Orbit's work: what it was asked, how it thought, which tools it
 * called and what they returned. Read only; the chat itself shows only what was said.
 */
@Composable
internal fun OrbitProcessPage(process: OrbitProcess, onBack: () -> Unit, onOpenTask: ((String) -> Unit)?) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    // A background run still working is read again every few seconds until it ends.
    val record by produceState<ProcessRecord?>(null, process) {
        var running = (process as? OrbitProcess.TaskRun)?.running == true
        while (true) {
            value = try {
                ProcessRecord(OrbitWork.messages(context, process), failed = false, running = running)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.ai.assistance.operit.util.AppLogger.w("OrbitProcess", "Could not load a process: ${e.message}")
                ProcessRecord(value?.messages.orEmpty(), failed = value == null, running = running)
            }
            if (!running || process !is OrbitProcess.TaskRun) break
            delay(5_000)
            running = try {
                OrbitTaskRepository(context).dao.run(process.runId)?.state == "RUNNING"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
    }
    val time = remember(process.time) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(process.time))
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OrbitCircleButton(orbitIcon("chevron-left"), stringResource(R.string.back), onBack)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        process.title.ifBlank { stringResource(R.string.orbit_turn_untitled) },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val taskId = (process as? OrbitProcess.TaskRun)?.taskId
                if (taskId != null && onOpenTask != null) {
                    TextButton(onClick = { onOpenTask(taskId) }, shape = RoundedCornerShape(50)) {
                        Text(stringResource(R.string.orbit_process_open_task))
                    }
                }
            }
            val current = record
            if (current?.running == true) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.orbit_process_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            when {
                current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                current.messages.isEmpty() && !current.running ->
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(if (current.failed) R.string.orbit_process_failed else R.string.orbit_process_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                else -> ProcessMessages(current.messages)
            }
        }
    }
}

@Composable
private fun ProcessMessages(messages: List<ChatMessage>) {
    val colors = MaterialTheme.colorScheme
    // The record always shows every step, whatever the chat's own display settings are.
    CompositionLocalProvider(LocalOrbitChatLook provides false) {
        com.ai.assistance.operit.ui.theme.ProvideAiMarkdownTextLayoutSettings {
            LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A restored chat can hold two rows with one time; the position keeps keys unique.
                itemsIndexed(messages, key = { index, message -> "${message.timestamp}:$index" }) { _, message ->
                    if (message.sender == "user") {
                        YouSaid(OrbitWork.userText(message.content, stringResource(R.string.orbit_user_sent_attachment)))
                    } else {
                        CursorStyleChatMessage(
                            message = message,
                            userMessageColor = colors.surfaceContainerHigh,
                            aiMessageColor = Color.Transparent,
                            userTextColor = colors.onSurface,
                            aiTextColor = colors.onSurface,
                            systemMessageColor = colors.surfaceVariant,
                            systemTextColor = colors.onSurfaceVariant,
                            thinkingBackgroundColor = colors.surfaceContainerLow,
                            thinkingTextColor = colors.onSurfaceVariant,
                            supportToolMarkup = true,
                            forceShowThinkingProcess = true,
                            enableDialogs = true,
                            showAssistantHeader = false,
                        )
                    }
                }
            }
        }
    }
}

/** The user's request at the top of a record, as plain words. */
@Composable
private fun YouSaid(text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(
            stringResource(R.string.orbit_process_you_said),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(
                text.ifBlank { "…" },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
