package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.library.MemoryLearningJournal
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.preferences.MemoryExtractionLog
import com.ai.assistance.operit.data.preferences.MemoryExtractionLogRepository
import com.ai.assistance.operit.data.repository.MemoryExtractionAuditRepository
import com.ai.assistance.operit.ui.features.memory.screens.dialogs.memoryExtractionStatusResource
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What the log list read: the records, chat names, how many wait, and whether reading failed. */
@Immutable
private class LogsState(
    val logs: List<MemoryExtractionLog>,
    val titles: Map<String, String>,
    val pending: Int,
    val failed: Boolean,
)

/**
 * Orbit's memory extraction records in its own look. Each record that ran a helper opens its
 * whole run, read only, inside Orbit ([OrbitMemoryLogPage]); nothing leaves for the main app.
 */
@Composable
internal fun OrbitMemoryLogsPage(
    spaceId: String,
    mainChatId: String,
    onBack: () -> Unit,
    onOpen: (MemoryExtractionLog) -> Unit,
) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val repo = remember(spaceId) { MemoryExtractionLogRepository(context, spaceId) }
    val mainTitle = stringResource(R.string.orbit_chats_main)
    // Covered by a record, or with the app in the background, the list stops reading.
    val active = rememberUpdatedState(LocalOrbitPageActive.current)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf<LogsState?>(null) }
    LaunchedEffect(repo, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                snapshotFlow { active.value }.first { it }
                val before = state
                state = try {
                    withContext(Dispatchers.IO) {
                        val logs = repo.list()
                        val dao = AppDatabase.getDatabase(context).chatDao()
                        val known = before?.titles.orEmpty()
                        val titles = known + logs.map { it.sourceChatId }.distinct()
                            .filter { it.isNotBlank() && it !in known }
                            .associateWith { id -> if (id == mainChatId) mainTitle else dao.getChatById(id)?.title.orEmpty() }
                        LogsState(
                            logs = logs,
                            titles = titles,
                            pending = MemoryLearningJournal.pending(context).count { it.first == spaceId },
                            failed = false,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogsState(before?.logs.orEmpty(), before?.titles.orEmpty(), before?.pending ?: 0, failed = true)
                }
                delay(3_000)
            }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            OrbitPageHeader(stringResource(R.string.memory_extraction_logs), onBack)
            Text(
                stringResource(R.string.orbit_memory_logs_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
            )
        }
        val current = state
        if (current == null) {
            item(key = "loading") { OrbitDelayedSpinner() }
            return@LazyColumn
        }
        if (current.failed) {
            item(key = "failed") {
                Text(stringResource(R.string.memory_notes_io_error), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (current.pending > 0) {
            item(key = "pending") {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(orbitIcon("hourglass"), contentDescription = null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.memory_extraction_pending_count, current.pending),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (current.logs.isEmpty() && !current.failed) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.orbit_memory_logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                )
            }
        }
        items(current.logs, key = { it.id }) { log ->
            LogCard(log, current.titles[log.sourceChatId]) { onOpen(log) }
        }
    }
}

private val formatter: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

/** One record as a gray card: which chat, when, what it wrote, how it ended. */
@Composable
private fun LogCard(log: MemoryExtractionLog, title: String?, onClick: () -> Unit) {
    val time = remember(log.startedAt) { formatter.format(Date(log.startedAt)) }
    val (dot, statusColor) = statusColors(log.status)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                // A range never handed to a helper has no run to show.
                .clickable(enabled = log.reviewable, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.memory_extraction_unknown_source),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(memoryExtractionStatusResource(log.status)),
                        style = MaterialTheme.typography.labelLarge, color = statusColor)
                    val parts = listOfNotNull(
                        if (log.notes) "memory.md" else null,
                        if (log.skills) stringResource(R.string.memory_extraction_skills) else null,
                    )
                    if (parts.isNotEmpty()) {
                        Text(" · " + parts.joinToString(" · "), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val numbers = listOfNotNull(
                    if (log.finishedAt > 0) stringResource(R.string.memory_extraction_result,
                        (log.finishedAt - log.startedAt) / 1000, log.proposals) else null,
                    // Records from before the counters existed have neither.
                    if (log.modelRounds > 0 || log.toolCalls > 0)
                        stringResource(R.string.memory_audit_counts, log.modelRounds, log.toolCalls) else null,
                )
                if (numbers.isNotEmpty()) {
                    Text(numbers.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (log.detail.isNotBlank()) {
                    Text(log.detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (log.reviewable) {
                Icon(orbitIcon("chevron-right"), contentDescription = stringResource(R.string.memory_audit_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 8.dp).size(20.dp))
            }
        }
    }
}

@Composable
private fun statusColors(status: String): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (status) {
        "success", "batch_complete" -> Color(0xFF34C759) to colors.onSurface
        "warnings", "partial", "timeout", "interrupted" -> Color(0xFFFF9F0A) to colors.onSurface
        "failed" -> colors.error to colors.error
        "cancelled" -> colors.outline to colors.onSurfaceVariant
        else -> colors.primary to colors.onSurface
    }
}

/**
 * One record's whole run, read only: the instructions the helper got, how it thought, and the
 * tools it called. Shown with Orbit's process page.
 */
@Composable
internal fun OrbitMemoryLogPage(spaceId: String, logId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val title = stringResource(R.string.memory_audit_title)
    // null while reading; a failure keeps the reason.
    val process by produceState<Result<OrbitProcess.Learning>?>(null, spaceId, logId) {
        value = try {
            withContext(Dispatchers.IO) {
                val log = MemoryExtractionLogRepository(context, spaceId).list().firstOrNull { it.id == logId }
                val run = log?.let { MemoryExtractionAuditRepository(context).resolve(it) }
                if (log == null || run == null) Result.failure(IllegalStateException())
                else Result.success(OrbitProcess.Learning(
                    run.childChatId, title, log.startedAt, spaceId, logId, running = log.status == "running",
                ))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    val loaded = process
    val learning = loaded?.getOrNull()
    if (learning != null) {
        OrbitProcessPage(learning, onBack = onBack, onOpenTask = null, developer = true)
        return
    }
    OrbitPageBack(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
            OrbitPageHeader(title, onBack)
            if (loaded == null) {
                OrbitDelayedSpinner()
            } else {
                Text(
                    stringResource(R.string.memory_audit_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 12.dp, end = 12.dp),
                )
            }
        }
    }
}
