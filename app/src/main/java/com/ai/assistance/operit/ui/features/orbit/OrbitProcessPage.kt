package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.ui.features.chat.components.LocalSubagentChatHost
import com.ai.assistance.operit.ui.features.chat.components.SubagentChatHost
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
import kotlinx.coroutines.flow.first

/** What the process page has read: the messages, whether reading failed, whether work goes on. */
private data class ProcessRecord(
    val messages: List<ChatMessage>,
    val failed: Boolean,
    val running: Boolean,
    /** The subagents this work started, oldest first. */
    val subagents: List<OrbitSubagentRecord> = emptyList(),
)

/**
 * The full record of one piece of Orbit's work: what it was asked, how it thought, which tools it
 * called and what they returned, then each subagent it started (Muse's MAIN / SUBAGENT 01). Read
 * only; the chat itself shows only what was said. A subagent's whole record opens over this one.
 */
@Composable
internal fun OrbitProcessPage(
    process: OrbitProcess,
    onBack: () -> Unit,
    onOpenTask: ((String) -> Unit)?,
    developer: Boolean = false,
) {
    var opened by remember(process) { mutableStateOf(listOf<OrbitProcess.Subagent>()) }
    OrbitPushHost(
        entries = opened,
        onPop = { opened = opened.dropLast(1) },
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        base = { ProcessRecordPage(process, onBack, onOpenTask, developer) { opened = opened + it } },
    ) { sub ->
        ProcessRecordPage(
            sub,
            // Leaves only while it is on top, so a late tap cannot pop another page.
            onBack = { if (opened.lastOrNull() == sub) opened = opened.dropLast(1) },
            onOpenTask = null,
            developer = developer,
        ) { opened = opened + it }
    }
}

@Composable
private fun ProcessRecordPage(
    process: OrbitProcess,
    onBack: () -> Unit,
    onOpenTask: ((String) -> Unit)?,
    developer: Boolean,
    onOpenSubagent: (OrbitProcess.Subagent) -> Unit,
) {
    val context = LocalContext.current
    val subagentCache = remember(process) { OrbitSubagents.Cache() }
    OrbitPageBack(onBack = onBack)
    // Covered by a page opened from here, it stops reading until it is on top again.
    val active = androidx.compose.runtime.rememberUpdatedState(LocalOrbitPageActive.current)
    // Back from a page opened from here: read again, and ask whether the work runs again (a
    // finished subagent can be given more work meanwhile).
    var returned by remember(process) { androidx.compose.runtime.mutableIntStateOf(0) }
    OrbitOnPageReturn { returned++ }
    // Work still going on (a background run, a subagent) is read again every few seconds until it ends.
    val record by produceState<ProcessRecord?>(null, process, developer, returned) {
        suspend fun selfRunning(): Boolean = try {
            when (process) {
                is OrbitProcess.TaskRun -> OrbitTaskRepository(context).dao.run(process.runId)?.state == "RUNNING"
                is OrbitProcess.Learning -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.ai.assistance.operit.data.preferences.MemoryExtractionLogRepository(context, process.spaceId)
                        .list().firstOrNull { it.id == process.logId }?.status == "running"
                }
                is OrbitProcess.Subagent -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.ai.assistance.operit.data.db.AppDatabase.getDatabase(context).subagentRunDao()
                        .getByChildChatId(process.chatId)?.status in OrbitSubagents.LIVE
                }
                is OrbitProcess.ChatTurn -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        var running = if (returned > 0) selfRunning() else when (process) {
            is OrbitProcess.TaskRun -> process.running
            is OrbitProcess.Learning -> process.running
            is OrbitProcess.Subagent -> process.running
            is OrbitProcess.ChatTurn -> false
        }
        var readMessages = true
        while (true) {
            value = try {
                val messages = if (readMessages || value == null) OrbitWork.messages(context, process, raw = developer)
                    else value!!.messages
                val subagents = value?.subagents?.takeIf { it.isNotEmpty() && !readMessages }
                    ?.let { OrbitSubagents.refresh(context, it, subagentCache) }
                    ?: OrbitSubagents.of(context, process, messages, subagentCache)
                ProcessRecord(messages, failed = false, running = running || subagents.any { it.running }, subagents)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.ai.assistance.operit.util.AppLogger.w("OrbitProcess", "Could not load a process: ${e.message}")
                ProcessRecord(value?.messages.orEmpty(), failed = value == null, running = running, value?.subagents.orEmpty())
            }
            if (value?.running != true) break
            delay(5_000)
            androidx.compose.runtime.snapshotFlow { active.value }.first { it }
            // The work's own record grows only while it runs; after that only its subagents change.
            readMessages = running
            running = running && selfRunning()
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
                        process.title.ifBlank { stringResource(R.string.orbit_turn_untitled, com.ai.assistance.operit.ui.features.chat.components.currentOrbitName()) },
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
                current.messages.isEmpty() && current.subagents.isEmpty() && !current.running ->
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(if (current.failed) R.string.orbit_process_failed else R.string.orbit_process_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                else -> {
                    val subagents = current.subagents
                    val host = remember(process, subagents) {
                        processChatId(process)?.let { parent ->
                            SubagentChatHost(parent) { child ->
                                onOpenSubagent(
                                    subagents.firstOrNull { it.run.childChatId == child }?.process()
                                        ?: OrbitProcess.Subagent(child, "", "", process.time)
                                )
                            }
                        }
                    }
                    CompositionLocalProvider(LocalSubagentChatHost provides host) {
                        ProcessMessages(current.messages, developer, subagents,
                            assigned = process is OrbitProcess.Subagent, onOpenSubagent = onOpenSubagent)
                    }
                }
            }
        }
    }
}

/** The chat a piece of work ran in, which its subagent rows belong to. */
private fun processChatId(process: OrbitProcess): String? = when (process) {
    is OrbitProcess.ChatTurn -> process.chatId
    is OrbitProcess.TaskRun -> process.chatId
    is OrbitProcess.Subagent -> process.chatId
    is OrbitProcess.Learning -> null
}

@Composable
private fun ProcessMessages(
    messages: List<ChatMessage>,
    developer: Boolean,
    subagents: List<OrbitSubagentRecord>,
    /** A subagent's record: its first message is the task it was handed, not the user speaking. */
    assigned: Boolean,
    onOpenSubagent: (OrbitProcess.Subagent) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val attachmentOnly = stringResource(R.string.orbit_user_sent_attachment)
    // The record always shows every step, whatever the chat's own display settings are.
    CompositionLocalProvider(LocalOrbitChatLook provides false) {
        com.ai.assistance.operit.ui.theme.ProvideAiMarkdownTextLayoutSettings {
            LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // With subagents the work reads in parts, like Muse: the main agent first, then each one.
                if (subagents.isNotEmpty() && messages.isNotEmpty()) {
                    item(key = "main-title") { SectionTitle(stringResource(R.string.orbit_process_main)) }
                }
                // A restored chat can hold two rows with one time; the position keeps keys unique.
                itemsIndexed(messages, key = { index, message -> "${message.timestamp}:$index" }) { _, message ->
                    if (developer && message.sender == "user") {
                        // Developers see the whole prompt the run was given, not just the request.
                        OrbitRawMessage(message)
                    } else if (message.sender == "user") {
                        if (assigned) {
                            YouSaid(OrbitSubagents.payload(message.content).trim(), stringResource(R.string.orbit_process_subagent_task))
                        } else {
                            YouSaid(OrbitWork.userText(message.content, attachmentOnly), stringResource(R.string.orbit_process_you_said))
                        }
                    } else {
                        // Developers see which of Orbit's rows the chat does not show, and why.
                        Column {
                            if (developer && message.displayMode != com.ai.assistance.operit.data.model.ChatMessageDisplayMode.NORMAL) {
                                // Lined up with the message text, which is inset 16dp.
                                OrbitHiddenBadge(message.displayMode, Modifier.padding(start = 16.dp, bottom = 4.dp))
                            }
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
                subagents.forEachIndexed { index, sub ->
                    item(key = "sub-title:${sub.run.id}") {
                        SectionTitle(stringResource(R.string.orbit_process_subagent, index + 1), Modifier.padding(top = 12.dp))
                    }
                    item(key = "sub:${sub.run.id}") { SubagentCard(sub) { onOpenSubagent(sub.process()) } }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = modifier.padding(horizontal = 8.dp),
    )
}

/** One subagent: its name and state, what it came back with, its last steps and a way into all of it. */
@Composable
private fun SubagentCard(sub: OrbitSubagentRecord, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sub.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                SubagentStatus(sub)
            }
            if (sub.result.isNotBlank()) {
                Text(sub.result, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
            }
            if (sub.steps.isEmpty()) {
                Text(stringResource(R.string.orbit_process_subagent_no_steps), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            } else {
                if (sub.stepCount > sub.steps.size) {
                    Text(stringResource(R.string.orbit_process_subagent_earlier, sub.stepCount - sub.steps.size),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                sub.steps.forEach { step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            orbitIcon(if (step.ok) "check" else "x"), contentDescription = null,
                            tint = if (step.ok) Color(0xFF34A853) else colors.error,
                            modifier = Modifier.padding(top = 2.dp).size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(step.tool, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (step.line.isNotBlank()) {
                                Text(step.line, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.orbit_process_subagent_open), style = MaterialTheme.typography.labelLarge,
                    color = colors.primary)
                Icon(orbitIcon("chevron-right"), contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SubagentStatus(sub: OrbitSubagentRecord) {
    val colors = MaterialTheme.colorScheme
    val (label, tint) = when {
        sub.running -> R.string.orbit_process_subagent_running to colors.primary
        sub.failed -> R.string.orbit_process_subagent_failed to colors.error
        sub.stopped -> R.string.orbit_process_subagent_stopped to colors.onSurfaceVariant
        else -> R.string.orbit_process_subagent_done to Color(0xFF34A853)
    }
    Surface(shape = RoundedCornerShape(50), color = tint.copy(alpha = 0.12f)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = tint,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** The request at the top of a record, as plain words: what the user said, or a subagent's task. */
@Composable
private fun YouSaid(text: String, label: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            OrbitFoldableText(
                text.ifBlank { "…" },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                foldedLines = 8,
            )
        }
    }
}
