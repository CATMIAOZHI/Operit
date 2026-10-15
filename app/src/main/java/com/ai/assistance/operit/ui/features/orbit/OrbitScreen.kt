package com.ai.assistance.operit.ui.features.orbit

import android.Manifest
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import coil.compose.rememberAsyncImagePainter
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.OrbitRunOutcome
import com.ai.assistance.operit.data.preferences.OrbitSettings
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.features.orbit.OrbitCompanion
import com.ai.assistance.operit.ui.common.dynamicui.CardIcons
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsIconBadge
import com.ai.assistance.operit.ui.features.settings.components.SettingsInfoBanner
import com.ai.assistance.operit.ui.features.settings.components.SettingsNavRow
import com.ai.assistance.operit.ui.features.settings.components.SettingsRowDivider
import com.ai.assistance.operit.ui.features.settings.components.SettingsSectionTitle
import com.ai.assistance.operit.ui.features.settings.components.SettingsSwitchRow
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.FileUtils
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "OrbitScreen"

private fun lucide(name: String): ImageVector = CardIcons.get("lucide:$name") ?: Icons.Rounded.Person

private fun formatMinuteOfDay(minuteOfDay: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

private fun toast(context: android.content.Context, messageRes: Int) {
    Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
}

/**
 * The Orbit page. While an Orbit is running it is that Orbit's chat, with its look and name on
 * top; while every Orbit is paused it lists them and lets the user start one.
 *
 * [chatContent] draws the regular chat screen for a chat id with the given header, so this page
 * keeps the model, thinking, permission, attachment and voice controls of the main chat.
 */
@Composable
fun OrbitScreen(
    hasBackgroundImage: Boolean,
    onOpenMemory: () -> Unit,
    onManageRoleCards: () -> Unit,
    chatContent: @Composable (chatId: String, header: @Composable () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { OrbitPreferences.getInstance(context) }
    val settings by preferences.settingsFlow.collectAsState(initial = null)
    val loadedSettings = settings ?: return
    val activeOrbitId = loadedSettings.activeOrbitId

    if (activeOrbitId == null) {
        OrbitHome(
            settings = loadedSettings,
            hasBackgroundImage = hasBackgroundImage,
            onManageRoleCards = onManageRoleCards,
        )
        return
    }

    // Make sure the running Orbit still has its chat (the user may have deleted it) before showing it.
    val readyChatId by
        produceState<String?>(initialValue = null, activeOrbitId) {
            value =
                runCatching { OrbitCompanion.ensureActiveChat(context)?.chatId }
                    .onFailure { AppLogger.e(TAG, "Could not prepare the Orbit chat", it) }
                    .getOrNull()
            if (value == null) {
                // The chat could not be made: show the list instead.
                toast(context, R.string.orbit_open_chat_failed)
                OrbitCompanion.pause(context)
            }
        }
    val orbit = loadedSettings.active
    val chatId = readyChatId
    if (orbit == null || chatId == null || orbit.chatId != chatId) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    OrbitChatPage(
        orbit = orbit,
        settings = loadedSettings,
        hasBackgroundImage = hasBackgroundImage,
        onOpenMemory = onOpenMemory,
        chatContent = chatContent,
    )
}

@Composable
private fun OrbitChatPage(
    orbit: OrbitProfile,
    settings: OrbitSettings,
    hasBackgroundImage: Boolean,
    onOpenMemory: () -> Unit,
    chatContent: @Composable (chatId: String, header: @Composable () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showMemory by remember(orbit.id) { mutableStateOf(false) }
    var showTasks by remember(orbit.id) { mutableStateOf(false) }
    var memoryRetry by remember(orbit.id) { mutableStateOf(0) }
    val memoryResult by produceState<Result<String>?>(null, orbit.id, memoryRetry) {
        value = null
        value = try {
            Result.success(com.ai.assistance.operit.features.orbit.OrbitMemoryScope.ensureInitialized(context, orbit))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // The chat screen follows the app-wide current chat. It shows this Orbit while the page is
    // open; put the chat the user had back when the page closes. Sending in an Orbit chat always
    // uses Orbit's own Soul.md, whatever role card is active elsewhere.
    DisposableEffect(orbit.chatId) {
        val core = ChatRuntimeHolder.getInstance(context.applicationContext).getCore(ChatRuntimeSlot.MAIN)
        val previousChatId = core.currentChatId.value?.takeIf { it != orbit.chatId }
        onDispose {
            if (previousChatId != null && core.currentChatId.value == orbit.chatId) {
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                    core.switchChat(previousChatId, scrollToBottom = false)
                }
            }
        }
    }

    if (showTasks) {
        OrbitTasksPage(orbit.id, onBack = { showTasks = false })
        return
    }
    if (showMemory) {
        val id = memoryResult?.getOrNull()
        if (id != null) {
            com.ai.assistance.operit.ui.features.memory.screens.MemoryLibraryPage(
                stringResource(R.string.orbit_memory_row), id, { showMemory = false }
            ) { com.ai.assistance.operit.ui.features.memory.screens.MemoryScreen(fixedMemorySpaceId = id) }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (memoryResult == null) CircularProgressIndicator()
                else {
                    Text(stringResource(R.string.orbit_memory_unavailable))
                    TextButton(onClick = { memoryRetry++ }) { Text(stringResource(R.string.orbit_memory_retry)) }
                }
                TextButton(onClick = { showMemory = false }) { Text(stringResource(R.string.back)) }
            }
        }
        return
    }
    chatContent(orbit.chatId) {
        OrbitHeader(orbit = orbit, onOpenSettings = { showSettings = true })
    }

    if (showSettings) {
        OrbitSettingsSheet(
            orbit = orbit,
            settings = settings,
            hasBackgroundImage = hasBackgroundImage,
            onDismiss = { showSettings = false },
            onOpenMemory = {
                showSettings = false
                showMemory = true
            },
            onOpenTasks = {
                showSettings = false
                showTasks = true
            },
            onPause = {
                showSettings = false
            },
        )
    }
}

/** Muse-style top: Orbit's look in the middle with its name below, and a settings button. */
@Composable
private fun OrbitHeader(orbit: OrbitProfile, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { OrbitPreferences.getInstance(context) }
    var showAvatarMenu by remember { mutableStateOf(false) }
    val latestOrbitId by rememberUpdatedState(orbit.id)
    val pickImage =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val orbitId = latestOrbitId
            scope.launch {
                val saved = FileUtils.copyFileToInternalStorage(context, uri, "orbit_avatar")
                if (saved == null) {
                    toast(context, R.string.orbit_avatar_save_failed)
                } else {
                    preferences.setAvatar(orbitId, saved.toString())
                }
            }
        }

    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                OrbitAvatar(
                    orbit = orbit,
                    size = 84,
                    modifier = Modifier.clickable { showAvatarMenu = true },
                )
                DropdownMenu(expanded = showAvatarMenu, onDismissRequest = { showAvatarMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.orbit_change_avatar)) },
                        leadingIcon = { Icon(lucide("image"), contentDescription = null) },
                        onClick = {
                            showAvatarMenu = false
                            pickImage.launch("image/*")
                        },
                    )
                    if (orbit.avatarUri != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.orbit_reset_avatar)) },
                            leadingIcon = { Icon(lucide("rotate-ccw"), contentDescription = null) },
                            onClick = {
                                showAvatarMenu = false
                                scope.launch { preferences.setAvatar(orbit.id, null) }
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            // Only Orbit renames itself, through its tool; a tap explains that.
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                modifier =
                    Modifier.widthIn(max = 240.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { toast(context, R.string.orbit_name_hint) },
            ) {
                Text(
                    text = orbit.name.ifBlank { stringResource(R.string.nav_orbit) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                )
            }
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp)) {
            Icon(
                imageVector = lucide("settings-2"),
                contentDescription = stringResource(R.string.orbit_settings),
            )
        }
    }
}

/** Orbit's own picture, or a role card's avatar in the picker. */
@Composable
private fun OrbitAvatar(orbit: OrbitProfile?, size: Int, modifier: Modifier = Modifier, card: CharacterCard? = null) {
    val context = LocalContext.current
    // An Orbit keeps the copy it made of its picture, so it never reads the role card again.
    val roleCardId = if (orbit == null) card?.id else null
    val customUri = orbit?.avatarUri
    val avatarUri by
        produceState<String?>(initialValue = customUri, roleCardId, customUri) {
            value =
                customUri
                    ?: roleCardId?.let {
                        UserPreferencesManager.getInstance(context)
                            .getResolvedAiAvatarForCharacterCardFlow(it)
                            .first()
                    }
        }
    Box(
        modifier =
            modifier.size(size.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val uri = avatarUri
        if (!uri.isNullOrBlank()) {
            Image(
                painter = rememberAsyncImagePainter(model = Uri.parse(uri)),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size((size / 2).dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrbitSettingsSheet(
    orbit: OrbitProfile,
    settings: OrbitSettings,
    hasBackgroundImage: Boolean,
    onDismiss: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenTasks: () -> Unit,
    onPause: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { OrbitPreferences.getInstance(context) }
    val running by OrbitCompanion.running.collectAsState()
    val cardColor = settingsCardColor(hasBackgroundImage)
    var showTimePicker by remember { mutableStateOf(false) }
    var runRequestedAt by remember { mutableStateOf<Long?>(null) }
    var notificationsAllowed by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsAllowed = granted
        }
    val showRunning =
        running || runRequestedAt?.let { requestedAt -> settings.lastRunAt < requestedAt } == true

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = stringResource(R.string.orbit_settings),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            Column {
                SettingsSectionTitle(
                    text = stringResource(R.string.orbit_section_proactive),
                    icon = lucide("heart-handshake"),
                )
                SettingsCard(backgroundColor = cardColor) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.orbit_proactive_switch),
                        subtitle =
                            if (settings.proactiveEnabled && !notificationsAllowed) {
                                stringResource(R.string.orbit_notification_permission_denied)
                            } else {
                                stringResource(R.string.orbit_proactive_switch_desc)
                            },
                        checked = settings.proactiveEnabled,
                        searchable = false,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                preferences.setProactiveEnabled(enabled)
                                OrbitCompanion.applySchedule(context)
                            }
                            if (
                                enabled &&
                                    !notificationsAllowed &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                    SettingsRowDivider(inset = false)
                    SettingsNavRow(
                        title = stringResource(R.string.orbit_check_in_time),
                        icon = lucide("calendar-clock"),
                        searchable = false,
                        onClick = { showTimePicker = true },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = formatMinuteOfDay(settings.checkInMinuteOfDay),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                SettingsInfoBanner(
                    text = stringResource(R.string.orbit_proactive_banner),
                    backgroundColor = cardColor,
                )
            }

            Column {
                SettingsSectionTitle(
                    text = stringResource(R.string.orbit_section_tasks),
                    icon = lucide("calendar-clock"),
                )
                SettingsCard(backgroundColor = cardColor) {
                    SettingsNavRow(
                        title = stringResource(R.string.orbit_tasks_title),
                        subtitle = stringResource(R.string.orbit_tasks_subtitle),
                        icon = lucide("list-checks"),
                        searchable = false,
                        onClick = onOpenTasks,
                    )
                    SettingsRowDivider(inset = false)
                    FocusNotesField(
                        initialNotes = settings.focusNotes,
                        onSave = { notes ->
                            preferences.setFocusNotes(notes)
                            OrbitCompanion.applySchedule(context)
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            enabled = !showRunning,
                            onClick = {
                                runRequestedAt = System.currentTimeMillis()
                                scope.launch {
                                    try { OrbitCompanion.runNow(context) }
                                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch (e: Exception) {
                                        runRequestedAt = 0L
                                        toast(context, R.string.orbit_run_failed_unknown)
                                    }
                                }
                            },
                        ) {
                            if (showRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.orbit_running))
                            } else {
                                Text(stringResource(R.string.orbit_run_now))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = lastRunText(settings),
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (settings.lastRunOutcome == OrbitRunOutcome.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            SettingsCard(backgroundColor = cardColor) {
                SettingsNavRow(
                    title = stringResource(R.string.orbit_memory_row),
                    subtitle = stringResource(R.string.orbit_memory_row_desc),
                    icon = lucide("brain"),
                    searchable = false,
                    onClick = onOpenMemory,
                )
                SettingsRowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.orbit_pause),
                    subtitle = stringResource(R.string.orbit_pause_desc),
                    icon = lucide("pause"),
                    searchable = false,
                    onClick = {
                        onPause()
                        // Closing the sheet cancels its scope, so pausing runs outside it.
                        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                            OrbitCompanion.pause(context)
                        }
                    },
                )
            }
        }
    }

    if (showTimePicker) {
        CheckInTimeDialog(
            minuteOfDay = settings.checkInMinuteOfDay,
            onDismiss = { showTimePicker = false },
            onConfirm = { minuteOfDay ->
                showTimePicker = false
                scope.launch {
                    preferences.setCheckInMinuteOfDay(minuteOfDay)
                    OrbitCompanion.applySchedule(context)
                }
            },
        )
    }
}

/** The page while every Orbit is paused: what Orbit is, the user's Orbits, and how to start one. */
@Composable
private fun OrbitHome(
    settings: OrbitSettings,
    hasBackgroundImage: Boolean,
    onManageRoleCards: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The chat page puts its buttons in the top bar; this list has none.
    val setTopBarActions = com.ai.assistance.operit.ui.main.LocalTopBarActions.current
    LaunchedEffect(Unit) { setTopBarActions {} }
    val cardManager = remember { CharacterCardManager.getInstance(context) }
    val cardIds by cardManager.characterCardListFlow.collectAsState(initial = emptyList())
    val cards by
        produceState(initialValue = emptyList<CharacterCard>(), cardIds) {
            value = cardManager.getAllCharacterCards()
        }
    val cardColor = settingsCardColor(hasBackgroundImage)
    var showPicker by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<OrbitProfile?>(null) }
    var starting by remember { mutableStateOf(false) }
    var removingId by remember { mutableStateOf<String?>(null) }

    fun start(action: suspend () -> Unit) {
        if (starting) return
        starting = true
        scope.launch {
            runCatching { action() }
                .onFailure {
                    AppLogger.e(TAG, "Could not start Orbit", it)
                    toast(context, R.string.orbit_open_chat_failed)
                }
            starting = false
        }
    }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        OrbitHeroCard(cardColor = cardColor)

        if (settings.orbits.isNotEmpty()) {
            Column {
                SettingsSectionTitle(
                    text = stringResource(R.string.orbit_section_my_orbits),
                    icon = lucide("orbit"),
                )
                SettingsCard(backgroundColor = cardColor) {
                    settings.orbits.sortedByDescending { it.createdAt }.forEachIndexed { index, orbit ->
                        if (index > 0) SettingsRowDivider()
                        val card = cards.firstOrNull { it.id == orbit.sourceRoleCardId }
                        OrbitRow(
                            orbit = orbit,
                            roleName = card?.name,
                            enabled = !starting && removingId == null,
                            removing = removingId == orbit.id,
                            onResume = { start { OrbitCompanion.resume(context, orbit.id) } },
                            onDelete = { pendingDelete = orbit },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                SettingsInfoBanner(
                    text = stringResource(R.string.orbit_paused_hint),
                    backgroundColor = cardColor,
                )
            }
        }

        SettingsCard(backgroundColor = cardColor) {
            SettingsNavRow(
                title = stringResource(R.string.orbit_manage_role_cards),
                subtitle = stringResource(R.string.orbit_manage_role_cards_desc),
                icon = lucide("square-user"),
                searchable = false,
                onClick = onManageRoleCards,
            )
        }

        Button(
            enabled = !starting && removingId == null,
            onClick = { showPicker = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            if (starting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(imageVector = lucide("orbit"), contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.orbit_create))
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showPicker) {
        CompanionPickerDialog(
            cards = cards,
            orbitRoleCardIds = settings.orbits.map { it.sourceRoleCardId }.toSet(),
            onDismiss = { showPicker = false },
            onSelect = { card ->
                showPicker = false
                start { OrbitCompanion.startFromRoleCard(context, card.id) }
            },
        )
    }

    pendingDelete?.let { orbit ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.orbit_delete_title, orbit.name)) },
            text = { Text(stringResource(R.string.orbit_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (removingId != null) return@TextButton
                        pendingDelete = null
                        removingId = orbit.id
                        scope.launch {
                            try {
                                OrbitCompanion.remove(context, orbit.id)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Orbit deletion did not finish", e)
                                toast(context, R.string.orbit_delete_incomplete)
                            } finally {
                                removingId = null
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.orbit_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun OrbitRow(
    orbit: OrbitProfile,
    roleName: String?,
    enabled: Boolean,
    removing: Boolean,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OrbitAvatar(orbit = orbit, size = 44)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = orbit.name.ifBlank { stringResource(R.string.nav_orbit) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The role card only seeded this Orbit; once it is deleted there is nothing to show.
            if (orbit.deleting) {
                Text(
                    stringResource(if (removing) R.string.orbit_deleting else R.string.orbit_delete_incomplete),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (roleName != null) {
                Text(
                    text = stringResource(R.string.orbit_role_label, roleName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(enabled = enabled, onClick = onDelete) {
            Icon(
                imageVector = lucide("trash-2"),
                contentDescription = stringResource(R.string.orbit_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(enabled = enabled, onClick = if (orbit.deleting) onDelete else onResume) {
            if (removing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(if (orbit.deleting) R.string.orbit_retry_delete else R.string.orbit_resume))
        }
    }
}

@Composable
private fun lastRunText(settings: OrbitSettings): String {
    val outcome = settings.lastRunOutcome ?: return stringResource(R.string.orbit_last_run_never)
    val time =
        DateUtils.getRelativeTimeSpanString(
            settings.lastRunAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    return when (outcome) {
        OrbitRunOutcome.SPOKE -> stringResource(R.string.orbit_last_run_spoke, time)
        OrbitRunOutcome.SILENT -> stringResource(R.string.orbit_last_run_silent, time)
        OrbitRunOutcome.FAILED -> stringResource(R.string.orbit_last_run_failed, time, settings.lastRunDetail)
    }
}

@Composable
private fun OrbitHeroCard(cardColor: Color) {
    SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIconBadge(
                icon = lucide("orbit"),
                size = 56,
                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = stringResource(R.string.nav_orbit),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.orbit_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        Text(text = stringResource(R.string.orbit_intro), style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(14.dp))
        val pillars =
            listOf(
                Triple("contact-round", R.string.orbit_pillar_role_title, R.string.orbit_pillar_role_desc),
                Triple("brain", R.string.orbit_pillar_memory_title, R.string.orbit_pillar_memory_desc),
                Triple("heart-handshake", R.string.orbit_pillar_proactive_title, R.string.orbit_pillar_proactive_desc),
                Triple("calendar-clock", R.string.orbit_pillar_task_title, R.string.orbit_pillar_task_desc),
            )
        BoxWithConstraints {
            // Two columns fit a six-character title from about 260dp; narrower screens stack them.
            val columns = if (maxWidth < 260.dp) 1 else 2
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pillars.chunked(columns).forEach { rowPillars ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowPillars.forEach { (icon, title, description) ->
                            PillarTile(
                                icon = lucide(icon),
                                title = stringResource(title),
                                description = stringResource(description),
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PillarTile(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier.clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                .padding(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompanionPickerDialog(
    cards: List<CharacterCard>,
    orbitRoleCardIds: Set<String>,
    onDismiss: () -> Unit,
    onSelect: (CharacterCard) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.orbit_choose_companion)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(cards, key = { it.id }) { card ->
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onSelect(card) }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OrbitAvatar(orbit = null, card = card, size = 36)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = card.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (card.id in orbitRoleCardIds) {
                                Text(
                                    text = stringResource(R.string.orbit_card_has_orbit),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckInTimeDialog(minuteOfDay: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state =
        rememberTimePickerState(
            initialHour = minuteOfDay / 60,
            initialMinute = minuteOfDay % 60,
            is24Hour = true,
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.orbit_check_in_time)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FocusNotesField(initialNotes: String, onSave: suspend (String) -> Unit) {
    var notes by remember { mutableStateOf(initialNotes) }
    var savedNotes by remember { mutableStateOf(initialNotes) }
    // Save after typing pauses, and once more when the sheet goes away, instead of on every key.
    LaunchedEffect(notes) {
        kotlinx.coroutines.delay(600)
        if (notes != savedNotes) {
            onSave(notes)
            savedNotes = notes
        }
    }
    val latestNotes by rememberUpdatedState(notes)
    val latestSaved by rememberUpdatedState(savedNotes)
    DisposableEffect(Unit) {
        onDispose {
            if (latestNotes != latestSaved) {
                val pending = latestNotes
                CoroutineScope(Dispatchers.IO).launch { onSave(pending) }
            }
        }
    }
    OutlinedTextField(
        value = notes,
        onValueChange = { value -> notes = value.take(OrbitSettings.MAX_FOCUS_NOTES_LENGTH) },
        label = { Text(stringResource(R.string.orbit_focus_label)) },
        placeholder = { Text(stringResource(R.string.orbit_focus_placeholder)) },
        minLines = 3,
        maxLines = 8,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}
