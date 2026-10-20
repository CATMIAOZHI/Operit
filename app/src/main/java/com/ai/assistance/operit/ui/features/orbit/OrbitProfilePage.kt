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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.produceState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.ai.assistance.operit.pet.displayName
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
    /** Opens every task in groups, including finished and cancelled ones. */
    onOpenAllTasks: () -> Unit,
    onOpenProcess: (OrbitProcess) -> Unit,
    onOpenSoul: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenMemoryLibrary: () -> Unit,
    onOpenIdentity: () -> Unit,
    onOpenUser: () -> Unit,
    onOpenFiles: () -> Unit,
    developerMode: Boolean = false,
    onOpenRawChat: () -> Unit = {},
) {
    val context = LocalContext.current
    val dao = remember(context) { OrbitTaskRepository(context).dao }
    val running by OrbitCompanion.running.collectAsState()
    var tabIndex by rememberSaveable(orbit.id) { mutableIntStateOf(0) }
    val tab = OrbitProfileTab.entries[tabIndex]
    // A failed read shows an empty list; the profile stays usable.
    val activity by remember(orbit.id) { dao.observeActivity(orbit.id).catch { emit(emptyList()) } }
        .collectAsState(initial = null)
    val approvals by remember(orbit.id) {
        com.ai.assistance.operit.features.orbit.OrbitApprovals.observe(context, orbit.id).catch { emit(emptyList()) }
    }.collectAsState(initial = null)
    var openApproval by remember(orbit.id) { mutableStateOf<com.ai.assistance.operit.features.orbit.OrbitApprovals.Approval?>(null) }
    val tasks by remember(orbit.id) { dao.observeTasks(orbit.id).catch { emit(emptyList()) } }
        .collectAsState(initial = null)
    // Work done while chatting. Read when the profile comes into view, then now and then while open.
    val showingActivity = visible && tab == OrbitProfileTab.ACTIVITY
    val chatTurns by produceState<OrbitChatTurns?>(null, orbit.talkChatIds, showingActivity) {
        while (showingActivity) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                OrbitWork.chatTurns(context, orbit.talkChatIds)
            }
            kotlinx.coroutines.delay(15_000)
        }
    }
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
            OrbitProfileTab.ACTIVITY -> {
                // Developer option: the whole conversation as stored, hidden turns included.
                if (developerMode) item(key = "developer-raw") {
                    OrbitListRow(
                        icon = orbitIcon("code-xml"),
                        title = stringResource(R.string.orbit_dev_raw_row),
                        detail = stringResource(R.string.orbit_dev_raw_row_desc),
                        meta = null,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        showChevron = true,
                        onClick = onOpenRawChat,
                    )
                }
                activitySection(activity, chatTurns, onOpenProcess)
            }
            OrbitProfileTab.APPROVALS -> approvalsSection(tasks, approvals, onOpenTask) { openApproval = it }
            OrbitProfileTab.SCHEDULES -> schedulesSection(tasks, onOpenTask, onOpenAllTasks)
            OrbitProfileTab.IDENTITY -> item(key = "identity") {
                IdentitySection(
                    orbit = orbit,
                    onOpenIdentity = onOpenIdentity,
                    onOpenSoul = onOpenSoul,
                    onOpenMemory = onOpenMemory,
                    onOpenMemoryLibrary = onOpenMemoryLibrary,
                    onOpenUser = onOpenUser,
                    onOpenFiles = onOpenFiles,
                )
            }
        }
    }
    openApproval?.let { a ->
        // The newest copy, so an answer given from the chat card shows here at once.
        val current = approvals?.firstOrNull { it.id == a.id } ?: a
        OrbitApprovalSheet(orbit, current, onDismiss = { openApproval = null })
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

/** Orbit's picture with a small pencil. Tapping either opens the avatar sheet. */
@Composable
private fun EditableOrbitAvatar(orbit: OrbitProfile, modifier: Modifier = Modifier) {
    var showSheet by remember { mutableStateOf(false) }
    Box(modifier) {
        OrbitAvatar(
            orbit = orbit,
            size = 96,
            onClickLabel = stringResource(R.string.orbit_change_avatar),
            onClick = { showSheet = true },
        )
        Surface(
            onClick = { showSheet = true },
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
    }
    if (showSheet) OrbitAvatarSheet(orbit = orbit, onDismiss = { showSheet = false })
}

/**
 * One sheet for every way to change the picture: a photo, the role card's own picture, or one
 * of the user's pets shown as round previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrbitAvatarSheet(orbit: OrbitProfile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { OrbitPreferences.getInstance(context) }
    val latestOrbitId by rememberUpdatedState(orbit.id)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var busy by remember { mutableStateOf(false) }
    fun failed() = android.widget.Toast.makeText(
        context, context.getString(R.string.orbit_avatar_save_failed), android.widget.Toast.LENGTH_SHORT,
    ).show()
    fun apply(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = try { block(); true } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                failed()
                false
            } finally { busy = false }
            // The user may have closed the sheet while the picture was being saved.
            if (ok && sheetState.isVisible) {
                sheetState.hide()
                onDismiss()
            }
        }
    }
    val pickImage =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val orbitId = latestOrbitId
            apply {
                val saved = FileUtils.copyFileToInternalStorage(context, uri, "orbit_avatar")
                if (saved == null) failed() else preferences.setAvatar(orbitId, saved.toString())
            }
        }
    val pets by remember { com.ai.assistance.operit.pet.PetPreferences.get(context).pets }.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.orbit_change_avatar),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AvatarSourceTile(
                    icon = orbitIcon("image"),
                    label = stringResource(R.string.orbit_avatar_from_photos),
                    enabled = !busy,
                    onClick = { pickImage.launch("image/*") },
                    modifier = Modifier.weight(1f),
                )
                AvatarSourceTile(
                    icon = orbitIcon("rotate-ccw"),
                    label = stringResource(R.string.orbit_reset_avatar),
                    enabled = !busy,
                    onClick = { apply { com.ai.assistance.operit.features.orbit.OrbitCompanion.resetAvatar(context, latestOrbitId) } },
                    modifier = Modifier.weight(1f),
                )
            }
            if (pets.isNotEmpty()) {
                Text(
                    stringResource(R.string.orbit_avatar_use_pet),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp, bottom = 12.dp, start = 4.dp),
                )
                // A few columns of round previews; the list is short, so a plain flow layout is enough.
                pets.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                        row.forEach { pet ->
                            PetAvatarChoice(
                                pet = pet,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val orbitId = latestOrbitId
                                    apply {
                                        val file = com.ai.assistance.operit.pet.PetAvatar.export(
                                            context, pet, OrbitPreferences.AVATAR_FILE_PREFIX,
                                        )
                                        if (file == null) failed()
                                        else preferences.setAvatar(orbitId, Uri.fromFile(file).toString())
                                    }
                                },
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarSourceTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PetAvatarChoice(
    pet: com.ai.assistance.operit.pet.PetProfile,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preview by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, pet) {
        value = com.ai.assistance.operit.pet.PetAvatar.preview(context, pet)
    }
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val image = preview
            if (image != null) {
                androidx.compose.foundation.Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(orbitIcon("cat"), contentDescription = null, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            pet.settings.displayName(),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
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
internal fun OrbitListRow(
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
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
    )
}

@Composable
internal fun EmptyHint(text: String) {
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

/** One row of the activity list: a background run or a chat turn Orbit worked on. */
private sealed interface OrbitWorkRow {
    val key: String
    val time: Long
    data class Run(val run: OrbitActivityRow) : OrbitWorkRow {
        override val key get() = "run-${run.id}"
        override val time get() = run.startedAt
    }
    data class Turn(val turn: OrbitChatTurn) : OrbitWorkRow {
        override val key get() = "turn-${turn.process.chatId}-${turn.process.timestamps.lastOrNull()}"
        override val time get() = turn.process.time
    }
}

private fun LazyListScope.activitySection(
    runs: List<OrbitActivityRow>?,
    chat: OrbitChatTurns?,
    onOpenProcess: (OrbitProcess) -> Unit,
) {
    if (runs == null) return loading()
    // Background runs show at once; the chat turns, read from every chat, join them when ready.
    if (chat == null) loading()
    if (chat?.failed == true) {
        item(key = "turns-failed") { EmptyHint(stringResource(R.string.orbit_activity_load_failed)) }
    }
    val rows = (runs.map { OrbitWorkRow.Run(it) } + chat?.turns.orEmpty().map { OrbitWorkRow.Turn(it) })
        .distinctBy { it.key }
        .sortedByDescending { it.time }
        .take(200)
    if (chat == null && rows.isEmpty()) return
    if (rows.isEmpty() && chat?.failed != true) {
        item(key = "empty") { EmptyHint(stringResource(R.string.orbit_profile_activity_empty)) }
        return
    }
    rows.groupBy { startOfDay(it.time) }.forEach { (day, dayRows) ->
        item(key = "day-$day") { SectionTitle(dayLabel(day)) }
        items(dayRows, key = { it.key }) { row ->
            when (row) {
                is OrbitWorkRow.Run -> {
                    val run = row.run
                    val failed = run.state == "FAILED" || run.state == "INTERRUPTED"
                    OrbitListRow(
                        icon = orbitIcon(
                            when {
                                run.state == "RUNNING" -> "loader"
                                failed -> "circle-alert"
                                run.kind == "DAILY" -> "heart-handshake"
                                run.kind == "URL_CHANGE" -> "eye"
                                run.kind == com.ai.assistance.operit.features.orbit.OrbitWatchSources.KIND -> "radar"
                                else -> "sparkles"
                            }
                        ),
                        title = OrbitWork.listLine(run.title).ifBlank { stringResource(R.string.orbit_turn_untitled) },
                        detail = runDetail(run),
                        meta = clockTime(run.startedAt),
                        iconTint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        showChevron = true,
                        onClick = {
                            onOpenProcess(OrbitProcess.TaskRun(run.id, run.generation, run.taskId, OrbitWork.listLine(run.title),
                                run.startedAt, running = run.state == "RUNNING"))
                        },
                    )
                }
                is OrbitWorkRow.Turn -> {
                    val turn = row.turn
                    OrbitListRow(
                        icon = orbitIcon("message-circle"),
                        title = turn.process.title.ifBlank { stringResource(R.string.orbit_turn_untitled) },
                        detail = turn.reply.ifBlank { stringResource(R.string.orbit_turn_no_reply) },
                        meta = clockTime(turn.process.time),
                        showChevron = true,
                        onClick = { onOpenProcess(turn.process) },
                    )
                }
            }
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
}.let(OrbitWork::listLine)

private fun LazyListScope.approvalsSection(
    tasks: List<OrbitTaskEntity>?,
    approvals: List<com.ai.assistance.operit.features.orbit.OrbitApprovals.Approval>?,
    onOpenTask: (String) -> Unit,
    onOpenApproval: (com.ai.assistance.operit.features.orbit.OrbitApprovals.Approval) -> Unit,
) {
    if (tasks == null || approvals == null) return loading()
    val waiting = tasks.filter { it.state == "WAITING_USER" || it.state == "VERIFY" }
    val asking = approvals.filter { it.pending }
    val records = approvals.filterNot { it.pending }
    if (waiting.isEmpty() && approvals.isEmpty()) {
        item(key = "empty") { EmptyHint(stringResource(R.string.orbit_profile_approvals_empty)) }
        return
    }
    if (asking.isNotEmpty() || waiting.isNotEmpty()) {
        item(key = "waiting-title") { SectionTitle(stringResource(R.string.orbit_profile_waiting)) }
        items(asking, key = { "asking-${it.id}" }) { a ->
            OrbitListRow(
                icon = orbitIcon(approvalIcon(a)),
                title = a.title,
                detail = OrbitWork.listLine(a.detail),
                meta = relativeTime(a.createdAt),
                iconTint = MaterialTheme.colorScheme.primary,
                iconBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                showChevron = true,
                onClick = { onOpenApproval(a) },
            )
        }
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
    if (records.isNotEmpty()) {
        item(key = "actions-title") { SectionTitle(stringResource(R.string.orbit_profile_approval_records)) }
        items(records, key = { "record-${it.id}" }) { a ->
            val vault = a.kind == com.ai.assistance.operit.features.orbit.OrbitApprovals.VAULT_SAVED
            val denied = a.state == com.ai.assistance.operit.features.orbit.OrbitApprovals.DENIED
            OrbitListRow(
                icon = orbitIcon(approvalIcon(a)),
                title = if (vault) stringResource(R.string.orbit_approval_vault_row, a.title) else a.title,
                detail = if (vault) null else OrbitWork.listLine(a.detail),
                meta = approvalStateText(a) + " · " + relativeTime(a.decidedAt.takeIf { it > 0 } ?: a.createdAt),
                iconTint = if (denied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onOpenApproval(a) },
            )
        }
    }
}

internal fun relativeTime(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

private fun LazyListScope.schedulesSection(
    tasks: List<OrbitTaskEntity>?,
    onOpenTask: (String) -> Unit,
    onOpenAll: () -> Unit,
) {
    if (tasks == null) return loading()
    // Finished, cancelled and one-off tasks are not schedules, but the user can still find them here.
    if (tasks.isNotEmpty()) item(key = "all-tasks") {
        OrbitListRow(
            icon = orbitIcon("list-checks"),
            title = stringResource(R.string.orbit_tasks_all),
            detail = stringResource(R.string.orbit_tasks_all_desc, tasks.size),
            meta = null,
            showChevron = true,
            onClick = onOpenAll,
        )
    }
    val live = tasks.filter { it.state != "CANCELLED" && it.state != "DONE" && it.kind != "ONCE" }
    if (live.isEmpty()) {
        item(key = "empty") { EmptyHint(stringResource(R.string.orbit_profile_schedules_empty)) }
        return
    }
    val groups = listOf(
        R.string.orbit_profile_schedule_once to live.filter { it.kind != "DAILY" && it.scheduleMode == "ONCE" }
            .sortedBy { it.nextRunAt },
        R.string.orbit_profile_schedule_daily to live.filter { it.kind == "DAILY" || it.scheduleMode == "DAILY" }
            .sortedBy { it.dailyMinute },
        R.string.orbit_profile_schedule_weekly to live.filter { it.kind != "DAILY" && it.scheduleMode == "WEEKLY" }
            .sortedBy { it.nextRunAt },
        R.string.orbit_profile_schedule_monthly to live.filter { it.kind != "DAILY" && it.scheduleMode == "MONTHLY" }
            .sortedBy { it.nextRunAt },
        R.string.orbit_profile_schedule_yearly to live.filter { it.kind != "DAILY" && it.scheduleMode == "YEARLY" }
            .sortedBy { it.nextRunAt },
        R.string.orbit_profile_schedule_interval to live.filter { it.kind != "DAILY" && it.scheduleMode == "INTERVAL" },
        R.string.orbit_profile_schedule_event to live.filter { it.kind != "DAILY" && it.scheduleMode == "EVENT" },
    )
    groups.forEach { (label, group) ->
        if (group.isEmpty()) return@forEach
        item(key = "schedule-title-$label") { SectionTitle(stringResource(label)) }
        items(group, key = { "schedule-${it.id}" }) { task ->
            val paused = task.state != "ACTIVE"
            val (icon, detail) = taskIconAndSchedule(task, tasks)
            OrbitListRow(
                icon = orbitIcon(icon),
                title = task.title,
                detail = detail,
                meta = if (paused) taskState(task)
                    else if (task.scheduleMode == "EVENT") null
                    else if (task.scheduleMode == "ONCE") DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(task.nextRunAt))
                    else stringResource(R.string.orbit_task_next, relativeTime(task.nextRunAt)),
                showChevron = true,
                onClick = { onOpenTask(task.id) },
            )
        }
    }
}

/** The icon for a task's schedule; cheap, for rows that do not show the time text. */
internal fun taskScheduleIcon(task: OrbitTaskEntity): String = when {
    task.kind == "DAILY" || task.scheduleMode == "DAILY" -> "calendar-clock"
    task.scheduleMode == "WEEKLY" -> "calendar-range"
    task.scheduleMode == "MONTHLY" -> "calendar-days"
    task.scheduleMode == "YEARLY" -> "calendar-heart"
    task.scheduleMode == "ONCE" -> "bell"
    task.scheduleMode == "EVENT" -> "zap"
    task.kind == com.ai.assistance.operit.features.orbit.OrbitWatchSources.KIND -> watchIcon(task)
    else -> "timer"
}

/** The icon and the "when" line of a task, the same in the schedule list and the task page. */
@Composable
internal fun taskIconAndSchedule(task: OrbitTaskEntity, tasks: List<OrbitTaskEntity>): Pair<String, String> {
    val trigger = tasks.firstOrNull { it.id == task.triggerTaskId }?.title ?: task.triggerTaskId
    return when {
        task.kind == "DAILY" || task.scheduleMode == "DAILY" -> "calendar-clock" to stringResource(
            R.string.orbit_profile_schedule_daily_at,
            String.format(Locale.ROOT, "%02d:%02d", task.dailyMinute / 60, task.dailyMinute % 60),
        )
        task.scheduleMode == "WEEKLY" -> "calendar-range" to calendarScheduleText(task)
        task.scheduleMode == "MONTHLY" -> "calendar-days" to calendarScheduleText(task)
        task.scheduleMode == "YEARLY" -> "calendar-heart" to calendarScheduleText(task)
        task.scheduleMode == "ONCE" -> "bell" to (DateFormat.getDateInstance(DateFormat.MEDIUM)
            .format(Date(task.nextRunAt)) + watchSuffix(task))
        task.scheduleMode == "EVENT" -> "zap" to (stringResource(R.string.orbit_task_event_wait, trigger) + watchSuffix(task))
        task.kind == com.ai.assistance.operit.features.orbit.OrbitWatchSources.KIND ->
            watchIcon(task) to (watchLabel(task) + " · " + intervalText(task.intervalMinutes))
        else -> "timer" to intervalText(task.intervalMinutes)
    }
}

@Composable
internal fun intervalText(minutes: Int): String =
    if (minutes % 60 == 0) stringResource(R.string.orbit_profile_every_hours, minutes / 60)
    else stringResource(R.string.orbit_profile_every_minutes, minutes)

/** "每周一、周三 09:00", "每月 15 日 09:00" or "每年 12月3日 09:00" in the app's language. */
@Composable
private fun calendarScheduleText(task: OrbitTaskEntity): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val resources = LocalContext.current.resources
    val time = String.format(Locale.ROOT, "%02d:%02d", task.dailyMinute / 60, task.dailyMinute % 60)
    val schedule = com.ai.assistance.operit.features.orbit.OrbitCalendarSchedule
    return runCatching {
        when (task.scheduleMode) {
            "WEEKLY" -> resources.getString(
                R.string.orbit_profile_schedule_weekly_at,
                schedule.weekdays(task.calendarRule).joinToString(resources.getString(R.string.orbit_profile_weekday_separator)) {
                    schedule.dayOfWeek(it).getDisplayName(java.time.format.TextStyle.SHORT, locale)
                },
                time,
            )
            "MONTHLY" -> resources.getString(R.string.orbit_profile_schedule_monthly_at, task.calendarRule.toInt(), time)
            else -> {
                val (month, day) = schedule.monthDay(task.calendarRule)
                val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
                val date = java.time.MonthDay.of(month, day)
                    .format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
                resources.getString(R.string.orbit_profile_schedule_yearly_at, date, time)
            }
        }
    }.getOrDefault(time)
}

@Composable
private fun IdentitySection(
    orbit: OrbitProfile,
    onOpenIdentity: () -> Unit,
    onOpenSoul: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenMemoryLibrary: () -> Unit,
    onOpenUser: () -> Unit,
    onOpenFiles: () -> Unit,
) {
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
            modifier = Modifier.padding(bottom = 12.dp),
        )
        // Like Muse: one wide button opens IDENTITY.md, Orbit's name, character and vibe.
        Surface(
            onClick = onOpenIdentity,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(orbitIcon("pencil"), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.orbit_profile_edit_identity), style = MaterialTheme.typography.titleMedium)
            }
        }
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
        // user.md: what Orbit knows about the user. A wide card under the two square ones.
        IdentityCard(
            title = stringResource(R.string.orbit_profile_user_card),
            icon = orbitIcon("user-round"),
            colors = listOf(Color(0xFF6B8FC7), Color(0xFF4F6FA8), Color(0xFF3B5590)),
            onClick = onOpenUser,
            aspectRatio = 2.4f,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )
        // The full memory library (profile, notes, skills, review history) sits below the cards;
        // the Memory card itself opens memory.md.
        IdentityNavRow("folder-open", stringResource(R.string.orbit_files_row), stringResource(R.string.orbit_files_row_desc), onOpenFiles)
        IdentityNavRow("sparkles", stringResource(R.string.orbit_memory_row), stringResource(R.string.orbit_memory_row_desc), onOpenMemoryLibrary)
        OrbitExportRow(orbit)
    }
}

/** Like Muse's data export: one zip with Orbit's files, memory, records and chat, handed to the share sheet. */
@Composable
private fun OrbitExportRow(orbit: OrbitProfile) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    // The zip holds every chat and memory, so a tap only asks first.
    var confirming by remember { mutableStateOf(false) }
    fun startExport() {
        if (exporting) return
        exporting = true
        scope.launch {
            try {
                val zip = com.ai.assistance.operit.features.orbit.OrbitDataExport.export(context.applicationContext, orbit)
                shareOrbitFile(context) { zip }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, R.string.orbit_export_failed, android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                exporting = false
            }
        }
    }
    IdentityNavRow(
        if (exporting) "loader" else "archive",
        stringResource(if (exporting) R.string.orbit_export_running else R.string.orbit_export_row),
        stringResource(R.string.orbit_export_row_desc),
    ) {
        if (!exporting) confirming = true
    }
    if (confirming) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.orbit_export_confirm_title)) },
            text = { Text(stringResource(R.string.orbit_export_confirm_message)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirming = false
                    startExport()
                }) { Text(stringResource(R.string.orbit_export_confirm)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirming = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun IdentityNavRow(icon: String, title: String, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(orbitIcon(icon), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(orbitIcon("chevron-right"), contentDescription = null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    aspectRatio: Float = 1.12f,
) {
    Box(
        modifier =
            modifier.aspectRatio(aspectRatio)
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
