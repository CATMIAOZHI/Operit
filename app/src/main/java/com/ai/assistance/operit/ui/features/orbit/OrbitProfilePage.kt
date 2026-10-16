package com.ai.assistance.operit.ui.features.orbit

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.dao.OrbitActionRow
import com.ai.assistance.operit.data.dao.OrbitActivityRow
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitCompanion
import com.ai.assistance.operit.features.orbit.OrbitTaskRepository
import com.ai.assistance.operit.util.FileUtils
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private enum class OrbitProfileTab(val icon: String, val label: Int) {
    ACTIVITY("list", R.string.orbit_profile_tab_activity),
    APPROVALS("shield-check", R.string.orbit_profile_tab_approvals),
    SCHEDULES("alarm-clock", R.string.orbit_profile_tab_schedules),
    IDENTITY("fingerprint", R.string.orbit_profile_tab_identity),
}

/**
 * Muse-style profile of the running Orbit: its look, name and state on top, then four tabs for
 * what it did, what it needed approval for, what it has scheduled, and who it is.
 */
@Composable
internal fun OrbitProfilePage(
    orbit: OrbitProfile,
    visible: Boolean,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenSoul: () -> Unit,
    onOpenMemory: () -> Unit,
) {
    val context = LocalContext.current
    val dao = remember(context) { OrbitTaskRepository(context).dao }
    val running by OrbitCompanion.running.collectAsState()
    var tabIndex by rememberSaveable(orbit.id) { mutableIntStateOf(0) }
    val tab = OrbitProfileTab.entries[tabIndex]
    // A failed read shows an empty list; the profile stays usable.
    val activity by remember(orbit.id) { dao.observeActivity(orbit.id).catch { emit(emptyList()) } }
        .collectAsState(initial = null)
    val actions by remember(orbit.id) { dao.observeOrbitActions(orbit.id).catch { emit(emptyList()) } }
        .collectAsState(initial = null)
    val tasks by remember(orbit.id) { dao.observeTasks(orbit.id).catch { emit(emptyList()) } }
        .collectAsState(initial = null)
    BackHandler(enabled = visible, onBack = onClose)

    LazyColumn(
        modifier =
            // Always opaque: the chat stays underneath this page.
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
    ) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth()) {
                    OrbitCircleButton(
                        icon = orbitIcon("x"),
                        contentDescription = stringResource(R.string.close),
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                    OrbitCircleButton(
                        icon = orbitIcon("settings"),
                        contentDescription = stringResource(R.string.orbit_settings),
                        onClick = onOpenSettings,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                    EditableOrbitAvatar(orbit = orbit, modifier = Modifier.align(Alignment.TopCenter))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = orbit.name.ifBlank { stringResource(R.string.nav_orbit) },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(if (running) R.string.orbit_profile_status_busy else R.string.orbit_profile_status_online),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                OrbitTabBar(selected = tab, onSelect = { tabIndex = it.ordinal })
                Spacer(Modifier.height(12.dp))
            }
        }
        when (tab) {
            OrbitProfileTab.ACTIVITY -> activitySection(activity, onOpenTask)
            OrbitProfileTab.APPROVALS -> approvalsSection(tasks, actions, onOpenTask)
            OrbitProfileTab.SCHEDULES -> schedulesSection(tasks, onOpenTask)
            OrbitProfileTab.IDENTITY -> item(key = "identity") {
                IdentitySection(orbit = orbit, onOpenSoul = onOpenSoul, onOpenMemory = onOpenMemory)
            }
        }
    }
}

internal fun orbitIcon(name: String): ImageVector =
    com.ai.assistance.operit.ui.common.dynamicui.CardIcons.get("lucide:$name")
        ?: Icons.Default.Info

/** The round floating button used on Orbit's pages instead of a top bar. */
@Composable
internal fun OrbitCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = 3.dp,
        modifier = modifier.size(52.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
        }
    }
}

/** Orbit's picture with a small pencil: the user picks a new picture or goes back to the copy. */
@Composable
private fun EditableOrbitAvatar(orbit: OrbitProfile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { OrbitPreferences.getInstance(context) }
    var showMenu by remember { mutableStateOf(false) }
    val latestOrbitId by rememberUpdatedState(orbit.id)
    val pickImage =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val orbitId = latestOrbitId
            scope.launch {
                val saved = FileUtils.copyFileToInternalStorage(context, uri, "orbit_avatar")
                if (saved == null) {
                    android.widget.Toast.makeText(
                        context, context.getString(R.string.orbit_avatar_save_failed), android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    preferences.setAvatar(orbitId, saved.toString())
                }
            }
        }
    Box(modifier) {
        OrbitAvatar(orbit = orbit, size = 96, modifier = Modifier.clickable { showMenu = true })
        Surface(
            onClick = { showMenu = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 2.dp).size(30.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    orbitIcon("pencil"),
                    contentDescription = stringResource(R.string.orbit_change_avatar),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.orbit_change_avatar)) },
                leadingIcon = { Icon(orbitIcon("image"), contentDescription = null) },
                onClick = {
                    showMenu = false
                    pickImage.launch("image/*")
                },
            )
            if (orbit.avatarUri != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.orbit_reset_avatar)) },
                    leadingIcon = { Icon(orbitIcon("rotate-ccw"), contentDescription = null) },
                    onClick = {
                        showMenu = false
                        scope.launch { preferences.setAvatar(orbit.id, null) }
                    },
                )
            }
        }
    }
}

@Composable
private fun OrbitTabBar(selected: OrbitProfileTab, onSelect: (OrbitProfileTab) -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(6.dp)) {
            OrbitProfileTab.entries.forEach { tab ->
                val isSelected = tab == selected
                val label = stringResource(tab.label)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                                else Color.Transparent
                            )
                            .clickable(role = Role.Tab) { onSelect(tab) }
                            .semantics {
                                contentDescription = label
                                this.selected = isSelected
                            },
                ) {
                    Icon(
                        orbitIcon(tab.icon),
                        contentDescription = null,
                        tint =
                            if (isSelected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/** One row of a profile list: a round icon, a title, up to two lines of detail and a time. */
@Composable
private fun OrbitListRow(
    icon: ImageVector,
    title: String,
    detail: String?,
    meta: String?,
    onClick: () -> Unit,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconBackground: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
    showChevron: Boolean = false,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(iconBackground),
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!meta.isNullOrBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        if (showChevron) {
            Icon(
                orbitIcon("chevron-right"),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 8.dp, top = 10.dp).size(20.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp),
    )
}

private fun LazyListScope.loading() = item(key = "loading") {
    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 24.dp))
}

@Composable
private fun dayLabel(time: Long): String = when {
    DateUtils.isToday(time) -> stringResource(R.string.orbit_profile_today)
    DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> stringResource(R.string.orbit_profile_yesterday)
    else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
}

private fun clockTime(time: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))

private fun startOfDay(time: Long): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = time
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

private fun LazyListScope.activitySection(rows: List<OrbitActivityRow>?, onOpenTask: (String) -> Unit) {
    if (rows == null) return loading()
    if (rows.isEmpty()) {
        item(key = "empty") { EmptyHint(stringResource(R.string.orbit_profile_activity_empty)) }
        return
    }
    rows.groupBy { startOfDay(it.startedAt) }.forEach { (day, dayRows) ->
        item(key = "day-$day") { SectionTitle(dayLabel(day)) }
        items(dayRows, key = { it.id }) { run ->
            val failed = run.state == "FAILED" || run.state == "INTERRUPTED"
            OrbitListRow(
                icon = orbitIcon(
                    when {
                        run.state == "RUNNING" -> "loader"
                        failed -> "circle-alert"
                        run.kind == "DAILY" -> "heart-handshake"
                        run.kind == "URL_CHANGE" -> "eye"
                        else -> "sparkles"
                    }
                ),
                title = run.title,
                detail = runDetail(run),
                meta = clockTime(run.startedAt),
                iconTint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onOpenTask(run.taskId) },
            )
        }
    }
}

@Composable
private fun runDetail(run: OrbitActivityRow): String = when (run.state) {
    "RUNNING" -> stringResource(R.string.orbit_task_running)
    "CANCELLED" -> stringResource(R.string.orbit_run_cancelled)
    "INTERRUPTED" -> stringResource(R.string.orbit_profile_run_interrupted)
    "FAILED" -> run.error.ifBlank { stringResource(R.string.orbit_run_failed_unknown) }
    else -> run.observation.ifBlank { stringResource(R.string.orbit_task_no_result) }
}.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()

private fun LazyListScope.approvalsSection(
    tasks: List<OrbitTaskEntity>?,
    actions: List<OrbitActionRow>?,
    onOpenTask: (String) -> Unit,
) {
    if (tasks == null || actions == null) return loading()
    val waiting = tasks.filter { it.state == "WAITING_USER" || it.state == "VERIFY" }
    if (waiting.isEmpty() && actions.isEmpty()) {
        item(key = "empty") { EmptyHint(stringResource(R.string.orbit_profile_approvals_empty)) }
        return
    }
    if (waiting.isNotEmpty()) {
        item(key = "waiting-title") { SectionTitle(stringResource(R.string.orbit_profile_waiting)) }
        items(waiting, key = { "waiting-${it.id}" }) { task ->
            OrbitListRow(
                icon = orbitIcon("hand"),
                title = task.title,
                detail = taskState(task),
                meta = relativeTime(task.updatedAt),
                iconTint = MaterialTheme.colorScheme.primary,
                iconBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                showChevron = true,
                onClick = { onOpenTask(task.id) },
            )
        }
    }
    if (actions.isNotEmpty()) {
        item(key = "actions-title") { SectionTitle(stringResource(R.string.orbit_profile_approval_records)) }
        items(actions, key = { "action-${it.id}" }) { action ->
            val uncertain = action.state == "UNKNOWN" || action.state == "STARTED"
            OrbitListRow(
                icon = orbitIcon(if (uncertain) "shield-alert" else "shield-check"),
                title = action.summary.ifBlank { action.toolName },
                detail = action.taskTitle,
                meta = actionState(action.state) + " · " + relativeTime(action.updatedAt),
                iconTint = if (uncertain) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onOpenTask(action.taskId) },
            )
        }
    }
}

@Composable
private fun actionState(state: String): String = stringResource(when (state) {
    "PREPARED" -> R.string.orbit_profile_action_prepared
    "STARTED" -> R.string.orbit_profile_action_started
    "UNKNOWN" -> R.string.orbit_profile_action_unknown
    "VERIFIED" -> R.string.orbit_profile_action_verified
    "RETRY_ALLOWED" -> R.string.orbit_profile_action_retry
    else -> R.string.orbit_profile_action_done
})

private fun relativeTime(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

private fun LazyListScope.schedulesSection(tasks: List<OrbitTaskEntity>?, onOpenTask: (String) -> Unit) {
    if (tasks == null) return loading()
    val live = tasks.filter { it.state != "CANCELLED" && it.state != "DONE" && it.kind != "ONCE" }
    if (live.isEmpty()) {
        item(key = "empty") { EmptyHint(stringResource(R.string.orbit_profile_schedules_empty)) }
        return
    }
    val groups = listOf(
        R.string.orbit_profile_schedule_once to live.filter { it.kind != "DAILY" && it.scheduleMode == "ONCE" },
        R.string.orbit_profile_schedule_daily to live.filter { it.kind == "DAILY" },
        R.string.orbit_profile_schedule_interval to live.filter { it.kind != "DAILY" && it.scheduleMode == "INTERVAL" },
        R.string.orbit_profile_schedule_event to live.filter { it.kind != "DAILY" && it.scheduleMode == "EVENT" },
    )
    groups.forEach { (label, group) ->
        if (group.isEmpty()) return@forEach
        item(key = "schedule-title-$label") { SectionTitle(stringResource(label)) }
        items(group, key = { "schedule-${it.id}" }) { task ->
            val paused = task.state != "ACTIVE"
            val trigger = tasks.firstOrNull { it.id == task.triggerTaskId }?.title.orEmpty()
            val (icon, detail) = when {
                task.kind == "DAILY" -> "calendar-clock" to stringResource(
                    R.string.orbit_profile_schedule_daily_at,
                    String.format(Locale.ROOT, "%02d:%02d", task.dailyMinute / 60, task.dailyMinute % 60),
                )
                task.scheduleMode == "ONCE" -> "bell" to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(task.nextRunAt))
                task.scheduleMode == "EVENT" -> "zap" to stringResource(R.string.orbit_task_event_wait, trigger)
                else -> "timer" to intervalText(task.intervalMinutes)
            }
            OrbitListRow(
                icon = orbitIcon(icon),
                title = task.title,
                detail = detail,
                meta = if (paused) taskState(task)
                    else if (task.scheduleMode == "EVENT") null
                    else stringResource(R.string.orbit_task_next, relativeTime(task.nextRunAt)),
                showChevron = true,
                onClick = { onOpenTask(task.id) },
            )
        }
    }
}

@Composable
private fun intervalText(minutes: Int): String =
    if (minutes % 60 == 0) stringResource(R.string.orbit_profile_every_hours, minutes / 60)
    else stringResource(R.string.orbit_profile_every_minutes, minutes)

@Composable
private fun IdentitySection(orbit: OrbitProfile, onOpenSoul: () -> Unit, onOpenMemory: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            orbit.name.ifBlank { stringResource(R.string.nav_orbit) },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Text(
            stringResource(R.string.orbit_profile_identity_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IdentityCard(
                title = stringResource(R.string.orbit_profile_soul_card),
                icon = orbitIcon("heart"),
                colors = listOf(Color(0xFF8E7A6B), Color(0xFFA77E86), Color(0xFF9A6070)),
                onClick = onOpenSoul,
                modifier = Modifier.weight(1f),
            )
            IdentityCard(
                title = stringResource(R.string.orbit_profile_memory_card),
                icon = orbitIcon("brain"),
                colors = listOf(Color(0xFF5DAA45), Color(0xFF3F7F3A), Color(0xFF2E6A2B)),
                onClick = onOpenMemory,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun IdentityCard(
    title: String,
    icon: ImageVector,
    colors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier.aspectRatio(1.12f)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(colors))
                .clickable(onClick = onClick)
                .padding(18.dp),
    ) {
        Column(Modifier.align(Alignment.TopStart)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                stringResource(R.string.orbit_profile_card_caution),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).size(26.dp),
        )
    }
}
