package com.ai.assistance.operit.ui.features.orbit

import android.Manifest
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
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
import com.ai.assistance.operit.ui.features.chat.components.LocalOrbitChatLook
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
                try {
                    OrbitCompanion.ensureActiveChat(context)?.chatId
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Leaving this page (or switching Orbit) is not a failure; never pause for it.
                    throw e
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Could not prepare the Orbit chat", e)
                    null
                }
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
    // The nightly self-review, like Muse's self-improvement. Made once per Orbit; after that it is an
    // ordinary task the user keeps, pauses or cancels in the schedule list.
    LaunchedEffect(orbit.id) {
        runCatching { com.ai.assistance.operit.features.orbit.OrbitRoutines.ensureSelfReview(context.applicationContext, orbit) }
            .exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        // Goals made before the briefing existed get it too.
        runCatching { com.ai.assistance.operit.features.orbit.OrbitRoutines.ensureGoalBriefingIfGoals(context.applicationContext, orbit) }
            .exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        // Muse's preinstalled skills, copied once into this Orbit's own skill folder.
        runCatching { com.ai.assistance.operit.features.orbit.OrbitBundledSkills.ensureInstalled(context.applicationContext, orbit.id) }
            .exceptionOrNull()?.let {
                if (it is kotlinx.coroutines.CancellationException) throw it
                AppLogger.w(TAG, "Could not install the bundled Orbit skills: ${it.message}")
            }
    }
    OrbitChatPage(
        orbit = orbit,
        settings = loadedSettings,
        hasBackgroundImage = hasBackgroundImage,
        chatContent = chatContent,
    )
}

@Composable
private fun OrbitChatPage(
    orbit: OrbitProfile,
    settings: OrbitSettings,
    hasBackgroundImage: Boolean,
    chatContent: @Composable (chatId: String, header: @Composable () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showProfile by rememberSaveable(orbit.id) { mutableStateOf(false) }
    // Pages pushed over the profile, as names so the stack survives recreation.
    var stackNames by rememberSaveable(orbit.id) { mutableStateOf("") }
    val stack = remember(stackNames) { stackNames.split(',').filter { it.isNotEmpty() }.map(OrbitSubPage::valueOf) }
    val subPage = stack.lastOrNull()
    fun push(page: OrbitSubPage) { stackNames = (stack + page).joinToString(",") { it.name } }
    fun pop() { stackNames = stack.dropLast(1).joinToString(",") { it.name } }
    var openTaskId by rememberSaveable(orbit.id) { mutableStateOf<String?>(null) }
    // Not saved across process death; the page then just closes back to the profile.
    var openProcess by remember(orbit.id) { mutableStateOf<OrbitProcess?>(null) }
    // Which of Orbit's chats is shown: the main chat or a side chat. A deleted side chat falls back to main.
    var talkChatId by rememberSaveable(orbit.id) { mutableStateOf(orbit.chatId) }
    // A side chat made a moment ago may not be in the settings yet; keep showing it meanwhile.
    var justCreatedChatId by remember(orbit.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(orbit.talkChatIds) { if (justCreatedChatId in orbit.talkChatIds) justCreatedChatId = null }
    val currentChatId = talkChatId.takeIf { it in orbit.talkChatIds || it == justCreatedChatId } ?: orbit.chatId
    val currentSide = orbit.sideChats.firstOrNull { it.chatId == currentChatId }
    var showChats by remember { mutableStateOf(false) }
    var showVault by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    // Developer option: the same chat drawn as a normal Operit chat (thinking, tools, hidden turns,
    // the full long-press menu) instead of Orbit's messaging look. Turning the option off leaves it.
    var devChatView by rememberSaveable(orbit.id) { mutableStateOf(false) }
    val devView = devChatView && settings.developerMode
    // Reports that reached a chat the user is not looking at show as a dot until it is opened.
    val unreadChats by com.ai.assistance.operit.features.orbit.OrbitDelivery.unreadChats.collectAsState()
    val reactions by remember(orbit.id, currentChatId) {
        com.ai.assistance.operit.features.orbit.OrbitReactions.of(context, orbit.id).observe(currentChatId)
    }.collectAsState(initial = emptyMap())
    // The user's own tapbacks on Orbit's messages, set from the long-press menu.
    val userReactionStore = remember(orbit.id) { com.ai.assistance.operit.features.orbit.OrbitReactions.ofUser(context, orbit.id) }
    val userReactionMap by remember(userReactionStore, currentChatId) {
        userReactionStore.observe(currentChatId)
    }.collectAsState(initial = emptyMap())
    val reactionScope = rememberCoroutineScope()
    val userReactions = remember(userReactionMap, currentChatId) {
        com.ai.assistance.operit.ui.features.chat.components.OrbitUserReactions(userReactionMap) { timestamp, emoji ->
            // Taking a tapback off says nothing to Orbit; putting one on lets it decide what to do.
            val adding = userReactionMap[timestamp] != emoji
            val chatId = currentChatId
            reactionScope.launch {
                try {
                    userReactionStore.toggle(chatId, timestamp, emoji)
                    if (adding) OrbitReactionSender.onReacted(context, orbit.id, chatId, timestamp, emoji)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.ai.assistance.operit.util.AppLogger.w(TAG, "Could not save a reaction: ${e.message}")
                }
            }
        }
    }
    // The bottom tabs. The chat stays composed under the other tabs so going back never reloads it.
    var tab by rememberSaveable(orbit.id) { mutableStateOf(OrbitTab.CHAT) }
    // A tapped report notification or a share opens that chat, over whatever page was showing.
    val openRequest by com.ai.assistance.operit.features.orbit.OrbitNavigation.pendingChat.collectAsState()
    LaunchedEffect(openRequest, orbit.talkChatIds) {
        val id = openRequest ?: return@LaunchedEffect
        if (id in orbit.talkChatIds) {
            talkChatId = id
            tab = OrbitTab.CHAT
            showProfile = false
            stackNames = ""
            showChats = false
        }
        com.ai.assistance.operit.features.orbit.OrbitNavigation.consume(id)
    }
    // A report counts as seen only while its chat is really on screen: chat tab, app in front.
    val lifecycleState by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    // Covered by the menu, the profile or a full page over it, the chat is not being read.
    val chatOnScreen = tab == OrbitTab.CHAT && !showChats && !showProfile &&
        !showSettings && !showVault && !showImport &&
        lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    LaunchedEffect(currentChatId, unreadChats, chatOnScreen) {
        if (chatOnScreen && currentChatId in unreadChats) com.ai.assistance.operit.features.orbit.OrbitDelivery.markSeen(currentChatId)
    }
    val hasUnread = unreadChats.any { (it != currentChatId || !chatOnScreen) && it in orbit.talkChatIds }
    // The tab pages keep one header; it reads the newest unread state from here.
    val hasUnreadState = rememberUpdatedState(hasUnread)
    val leaveOrbit = com.ai.assistance.operit.ui.main.LocalLeaveOrbit.current
    var pageTab by rememberSaveable(orbit.id) { mutableStateOf(OrbitTab.FEED) }
    // A text file from the Builds tab opens full screen, over the bottom tabs, so a stray tap
    // on a tab cannot drop an unsaved edit.
    var editingFile by rememberSaveable(orbit.id) { mutableStateOf<String?>(null) }
    var filesVersion by remember(orbit.id) { mutableStateOf(0) }
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(LocalDensity.current) > 0
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
    // Orbit's memory changes never wait for approval; clear any left from before, off the path
    // that opens the memory pages. A failure here only gets logged.
    val memorySpaceId = memoryResult?.getOrNull()
    LaunchedEffect(memorySpaceId) {
        val spaceId = memorySpaceId ?: return@LaunchedEffect
        try {
            com.ai.assistance.operit.features.orbit.OrbitMemoryScope.approveWaitingChanges(context, spaceId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.ai.assistance.operit.util.AppLogger.w(TAG, "Could not approve waiting memory changes: ${e.message}")
        }
    }

    // The chat screen follows the app-wide current chat. It shows this Orbit while the page is
    // open; put the chat the user had back when the page closes. Sending in an Orbit chat always
    // uses Orbit's own Soul.md, whatever role card is active elsewhere.
    DisposableEffect(orbit.id) {
        val core = ChatRuntimeHolder.getInstance(context.applicationContext).getCore(ChatRuntimeSlot.MAIN)
        val orbitPreferences = OrbitPreferences.getInstance(context.applicationContext)
        fun isThisOrbit(chatId: String?) = orbitPreferences.snapshot.orbitForChat(chatId)?.id == orbit.id
        val previousChatId = core.currentChatId.value?.takeIf { !isThisOrbit(it) }
        onDispose {
            if (previousChatId != null) {
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                    // Read the settings fresh: a side chat made a moment ago may not be in the snapshot yet.
                    val shown = core.currentChatId.value
                    if (orbitPreferences.current().orbitForChat(shown)?.id == orbit.id) {
                        core.switchChat(previousChatId, scrollToBottom = false)
                    }
                }
            }
        }
    }

    // The chat always stays underneath, so closing the profile never reloads it. The profile
    // slides up over it and the pages opened from the profile slide in from the right, like iOS.
    Box(Modifier.fillMaxSize()) {
        BackHandler(enabled = tab != OrbitTab.CHAT && !showProfile && !devView) { tab = OrbitTab.CHAT }
        BackHandler(enabled = devView && !showProfile && !showChats) { devChatView = false }
        // While the menu is open, screen readers only see the menu, not the page behind it.
        Column(
            Modifier.fillMaxSize().then(
                if (showChats) Modifier.clearAndSetSemantics { } else Modifier
            )
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(
                    LocalOrbitChatLook provides !devView,
                    com.ai.assistance.operit.ui.features.chat.components.LocalShowHiddenChatRows provides devView,
                    com.ai.assistance.operit.ui.features.chat.components.LocalOrbitReactions provides reactions,
                    com.ai.assistance.operit.ui.features.chat.components.LocalOrbitUserReactions provides userReactions,
                ) {
                    chatContent(currentChatId) {
                        if (devView) {
                            OrbitDevHeader(
                                orbit = orbit, currentChatId = currentChatId,
                                onSelectChat = { talkChatId = it },
                                onExit = { devChatView = false },
                            )
                        } else {
                            OrbitHeader(
                                orbit = orbit, sideTitle = currentSide?.title, hasUnread = hasUnread,
                                onOpenProfile = { showProfile = true }, onOpenMenu = { showChats = true },
                            )
                        }
                    }
                }
                // Tabs switch at once, like an iOS tab bar: a fade would let the chat underneath show
                // through for a moment. Each page draws the same header in the same place as the
                // chat, so Orbit's picture stays put on top while the content changes.
                if (tab != OrbitTab.CHAT && !devView) {
                    val tabHeader: @Composable () -> Unit = remember(orbit) {
                        {
                            OrbitHeader(
                                orbit = orbit, sideTitle = null, hasUnread = hasUnreadState.value,
                                onOpenProfile = { showProfile = true }, onOpenMenu = { showChats = true },
                            )
                        }
                    }
                    CompositionLocalProvider(LocalOrbitTabHeader provides tabHeader) {
                        OrbitTabPage(orbit, pageTab, onOpenChat = { tab = OrbitTab.CHAT },
                            filesVersion = filesVersion, onOpenFile = { editingFile = it })
                    }
                }
            }
            // Hidden while typing so the input sits right on the keyboard.
            if (!imeVisible && !devView) {
                OrbitBottomBar(selected = tab, onSelect = {
                    if (it != OrbitTab.CHAT) pageTab = it
                    tab = it
                })
            }
        }
        // Orbit's own menu, opened only by the round button at the top left.
        OrbitSideMenu(
            visible = showChats,
            orbit = orbit, currentChatId = currentChatId, unread = unreadChats,
            onSelect = {
                if (it !in orbit.talkChatIds) justCreatedChatId = it
                talkChatId = it
                tab = OrbitTab.CHAT
            },
            onDismiss = { showChats = false },
            onOpenSettings = { showSettings = true },
            onLeaveOrbit = leaveOrbit,
        )
        // Keeps the last file on screen while the page slides away.
        var shownFile by remember(orbit.id) { mutableStateOf(editingFile) }
        LaunchedEffect(editingFile) { editingFile?.let { shownFile = it } }
        AnimatedVisibility(
            visible = editingFile != null,
            enter = slideInHorizontally(tween(OrbitMotion.PUSH_MS, easing = OrbitMotion.Easing)) { it },
            exit = slideOutHorizontally(tween(OrbitMotion.PUSH_MS, easing = OrbitMotion.Easing)) { it },
        ) {
            (editingFile ?: shownFile)?.let { path ->
                val files = remember(orbit.id) { com.ai.assistance.operit.features.orbit.OrbitFiles.of(context, orbit.id) }
                OrbitMadeFilePage(files, path, onBack = { editingFile = null; filesVersion++ })
            }
        }
        val profileDim by animateFloatAsState(
            targetValue = if (showProfile) 0.18f else 0f,
            animationSpec = tween(OrbitMotion.PRESENT_MS, easing = OrbitMotion.Easing),
            label = "orbitProfileDim",
        )
        if (profileDim > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = profileDim)))
        }
        AnimatedVisibility(
            visible = showProfile,
            enter = slideInVertically(tween(OrbitMotion.PRESENT_MS, easing = OrbitMotion.Easing)) { it },
            exit = slideOutVertically(tween(OrbitMotion.DISMISS_MS, easing = OrbitMotion.Easing)) { it },
        ) {
            Box(Modifier.fillMaxSize()) {
                // Pushed pages move the profile a little to the left underneath them.
                val pushOffset by animateFloatAsState(
                    targetValue = if (subPage != null) -0.3f else 0f,
                    animationSpec = tween(OrbitMotion.PUSH_MS, easing = OrbitMotion.Easing),
                    label = "orbitProfileParallax",
                )
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = size.width * pushOffset }) {
                    OrbitProfilePage(
                        orbit = orbit,
                        visible = showProfile && subPage == null,
                        onClose = { showProfile = false },
                        onOpenSettings = { showSettings = true },
                        onOpenTask = {
                            openTaskId = it
                            push(OrbitSubPage.TASK)
                        },
                        onOpenAllTasks = {
                            openTaskId = null
                            push(OrbitSubPage.TASK)
                        },
                        onOpenProcess = {
                            openProcess = it
                            push(OrbitSubPage.PROCESS)
                        },
                        onOpenSoul = { push(OrbitSubPage.SOUL) },
                        onOpenMemory = { push(OrbitSubPage.MEMORY) },
                        onOpenMemoryLibrary = { push(OrbitSubPage.MEMORY_LIBRARY) },
                        onOpenIdentity = { push(OrbitSubPage.IDENTITY) },
                        onOpenUser = { push(OrbitSubPage.USER) },
                        onOpenFiles = { push(OrbitSubPage.FILES) },
                        developerMode = settings.developerMode,
                        onOpenRawChat = {
                            // Back on the chat, now drawn as a normal Operit chat.
                            devChatView = true
                            tab = OrbitTab.CHAT
                            stackNames = ""
                            showProfile = false
                        },
                    )
                }
                AnimatedContent(
                    targetState = stack,
                    transitionSpec = {
                        val forward = targetState.size > initialState.size
                        if (forward) {
                            slideInHorizontally(tween(OrbitMotion.PUSH_MS, easing = OrbitMotion.Easing)) { it } togetherWith
                                ExitTransition.None
                        } else {
                            EnterTransition.None togetherWith
                                slideOutHorizontally(tween(OrbitMotion.PUSH_MS, easing = OrbitMotion.Easing)) { it }
                        }
                    },
                    label = "orbitSubPage",
                ) { pages ->
                    val page = pages.lastOrNull()
                    val back = { pop() }
                    when (page) {
                        null -> Box(Modifier.fillMaxSize())
                        OrbitSubPage.SOUL -> OrbitSoulFilePage(orbit.id, onBack = back,
                            onOpenHistory = { push(OrbitSubPage.SOUL_HISTORY) })
                        OrbitSubPage.SOUL_HISTORY -> OrbitSoulPage(orbit.id, onBack = back)
                        OrbitSubPage.IDENTITY -> OrbitIdentityFilePage(orbit.id, onBack = back)
                        OrbitSubPage.USER -> OrbitUserFilePage(orbit, onBack = back)
                        OrbitSubPage.MEMORY -> OrbitMemoryFilePage(orbit, onBack = back)
                        OrbitSubPage.PROMPT -> OrbitPromptFilePage(orbit.id, onBack = back)
                        OrbitSubPage.PROMPTS -> OrbitPromptLayersPage(
                            orbit = orbit,
                            onBack = back,
                            onOpenPrompt = { push(OrbitSubPage.PROMPT) },
                            onOpenSoul = { push(OrbitSubPage.SOUL) },
                            onOpenIdentity = { push(OrbitSubPage.IDENTITY) },
                            onOpenUser = { push(OrbitSubPage.USER) },
                        )
                        OrbitSubPage.NOTIFICATIONS -> OrbitNotificationsPage(orbit.id, onBack = back)
                        OrbitSubPage.FILES -> OrbitFilesPage(orbit.id, onBack = back)
                        // No id opens the grouped list of every task.
                        OrbitSubPage.TASK -> OrbitTasksPage(orbit.id, onBack = back, initialTaskId = openTaskId)
                        OrbitSubPage.PROCESS -> {
                            val process = openProcess
                            if (process != null) {
                                OrbitProcessPage(process, onBack = back, onOpenTask = { taskId ->
                                    openTaskId = taskId
                                    push(OrbitSubPage.TASK)
                                }, developer = settings.developerMode)
                            } else {
                                LaunchedEffect(Unit) { pop() }
                            }
                        }
                        OrbitSubPage.MEMORY_LIBRARY -> {
                            val id = memoryResult?.getOrNull()
                            if (id != null) {
                                OrbitLearningPage(id, back)
                            } else {
                                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (memoryResult == null) CircularProgressIndicator()
                                        else {
                                            Text(stringResource(R.string.orbit_memory_unavailable))
                                            TextButton(onClick = { memoryRetry++ }) { Text(stringResource(R.string.orbit_memory_retry)) }
                                        }
                                        TextButton(onClick = back) { Text(stringResource(R.string.back)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        OrbitSettingsSheet(
            orbit = orbit,
            settings = settings,
            hasBackgroundImage = hasBackgroundImage,
            onDismiss = { showSettings = false },
            onOpenNotifications = {
                showSettings = false
                push(OrbitSubPage.NOTIFICATIONS)
            },
            onOpenPrompts = {
                showSettings = false
                push(OrbitSubPage.PROMPTS)
            },
            onOpenVault = {
                showSettings = false
                showVault = true
            },
            onOpenImport = {
                showSettings = false
                showImport = true
            },
            onPause = {
                showSettings = false
            },
        )
    }
    if (showVault) OrbitVaultSheet(orbit = orbit, onDismiss = { showVault = false })
    if (showImport) {
        OrbitImportMemorySheet(
            orbit = orbit,
            onDismiss = { showImport = false },
            onSent = {
                showImport = false
                talkChatId = orbit.chatId
                tab = OrbitTab.CHAT
                showProfile = false
                stackNames = ""
            },
        )
    }
    // Orbit asked for a login or a key: the secure entry card, over every page of this Orbit.
    val vaultRequest by com.ai.assistance.operit.features.orbit.OrbitVault.request.collectAsState()
    vaultRequest?.takeIf { it.orbitId == orbit.id }?.let { OrbitVaultEntrySheet(orbit = orbit, request = it) }
}

private enum class OrbitSubPage { TASK, PROCESS, SOUL, SOUL_HISTORY, MEMORY, MEMORY_LIBRARY, NOTIFICATIONS, IDENTITY, USER, PROMPTS, PROMPT, FILES }

/** iOS-like timing: a quick start that settles softly (UIKit's default presentation curve). */
internal object OrbitMotion {
    val Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    const val PRESENT_MS = 500
    const val DISMISS_MS = 380
    const val PUSH_MS = 420
}

/**
 * Muse-style top over the chat: a round menu button on the left, and Orbit's look in the middle
 * with its name over its lower edge. The page color fades out below them, so messages that
 * scroll up slide softly under the header instead of running into it.
 */
@Composable
internal fun OrbitHeader(
    orbit: OrbitProfile,
    sideTitle: String?,
    hasUnread: Boolean,
    onOpenProfile: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val pageColor = MaterialTheme.colorScheme.background
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to pageColor,
                        0.55f to pageColor.copy(alpha = 0.92f),
                        1f to pageColor.copy(alpha = 0f),
                    )
                )
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp)
    ) {
        // Orbit's own menu (its chats, settings, back to Operit). Only this button opens it.
        Box(Modifier.align(Alignment.TopStart)) {
            val unreadState = stringResource(R.string.orbit_chats_unread)
            OrbitCircleButton(
                icon = orbitIcon("equal"),
                contentDescription = stringResource(R.string.menu),
                onClick = onOpenMenu,
                modifier = Modifier.semantics {
                    if (hasUnread) stateDescription = unreadState
                },
            )
            // A new report waits in another of Orbit's chats.
            if (hasUnread) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(12.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Box(modifier = Modifier.align(Alignment.TopCenter), contentAlignment = Alignment.TopCenter) {
            OrbitAvatar(
                orbit = orbit,
                size = 76,
                onClickLabel = stringResource(R.string.orbit_profile_open),
                onClick = onOpenProfile,
            )
            // The name pill sits over the bottom edge of the picture, like Muse.
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                modifier =
                    Modifier.padding(top = 62.dp)
                        .widthIn(max = 240.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onOpenProfile),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = orbit.name.ifBlank { stringResource(R.string.nav_orbit) },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // In a side chat, its topic sits under the name so the user knows where they are.
                    if (sideTitle != null) {
                        Text(
                            text = sideTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Top of the developer view: the same chat drawn as a normal Operit chat. A round button leaves it,
 * and the pill in the middle names the chat and switches between Orbit's main and side chats.
 */
@Composable
private fun OrbitDevHeader(
    orbit: OrbitProfile,
    currentChatId: String,
    onSelectChat: (String) -> Unit,
    onExit: () -> Unit,
) {
    val pageColor = MaterialTheme.colorScheme.background
    val mainTitle = stringResource(R.string.orbit_chats_main)
    val chats = remember(orbit, mainTitle) {
        listOf(orbit.chatId to mainTitle) + orbit.sideChats.map { it.chatId to it.title }
    }
    val currentTitle = chats.firstOrNull { it.first == currentChatId }?.second ?: mainTitle
    var pickerOpen by remember { mutableStateOf(false) }
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to pageColor,
                        0.6f to pageColor.copy(alpha = 0.94f),
                        1f to pageColor.copy(alpha = 0f),
                    )
                )
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp)
    ) {
        OrbitCircleButton(
            icon = orbitIcon("chevron-left"),
            contentDescription = stringResource(R.string.orbit_dev_view_exit),
            onClick = onExit,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Box(Modifier.align(Alignment.TopCenter).padding(top = 4.dp)) {
            val switchLabel = stringResource(R.string.orbit_dev_view_switch_chat)
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                shadowElevation = 3.dp,
                modifier =
                    Modifier.widthIn(max = 240.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = chats.size > 1, onClickLabel = switchLabel,
                            role = androidx.compose.ui.semantics.Role.DropdownList) { pickerOpen = true },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 18.dp, end = if (chats.size > 1) 10.dp else 18.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.orbit_dev_view_title),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${orbit.name.ifBlank { stringResource(R.string.nav_orbit) }} · $currentTitle",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (chats.size > 1) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp).size(20.dp),
                        )
                    }
                }
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                chats.forEach { (id, title) ->
                    val isCurrent = id == currentChatId
                    DropdownMenuItem(
                        text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = if (isCurrent) {
                            { Icon(orbitIcon("check"), contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        modifier = Modifier.semantics { selected = isCurrent },
                        onClick = {
                            pickerOpen = false
                            onSelectChat(id)
                        },
                    )
                }
            }
        }
    }
}

/** Orbit's own picture, or a role card's avatar in the picker. */
@Composable
internal fun OrbitAvatar(
    orbit: OrbitProfile?,
    size: Int,
    modifier: Modifier = Modifier,
    card: CharacterCard? = null,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // An Orbit keeps the copy it made of its picture, so it never reads the role card again.
    val roleCardId = if (orbit == null) card?.id else null
    // A built-in role's picture ships with the app, so an Orbit without its own picture uses it.
    val customUri = orbit?.avatarUri
        ?: orbit?.sourceRoleCardId?.let { UserPreferencesManager.getBuiltInCharacterAvatarUri(it) }
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
    // A tap shrinks the round picture a little, like iOS, instead of a square ripple.
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.93f else 1f, tween(140), label = "orbitAvatarPress")
    Box(
        modifier =
            modifier.size(size.dp)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .clip(CircleShape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = onClickLabel,
                            onClick = onClick,
                        )
                    } else Modifier
                )
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
    onOpenNotifications: () -> Unit,
    onOpenPrompts: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenImport: () -> Unit,
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
    // A run that found the chat busy is put off without a record; once the work has started and
    // stopped, or after a while with nothing happening, the button is free again.
    var sawRunning by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (running) sawRunning = true
        else if (sawRunning) { sawRunning = false; runRequestedAt = null }
    }
    LaunchedEffect(runRequestedAt) {
        if (runRequestedAt != null) { kotlinx.coroutines.delay(90_000); if (!running) runRequestedAt = null }
    }

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
                    text = stringResource(R.string.orbit_section_prompts),
                    icon = lucide("file-text"),
                )
                SettingsCard(backgroundColor = cardColor) {
                    SettingsNavRow(
                        title = stringResource(R.string.orbit_prompt_layers),
                        subtitle = stringResource(R.string.orbit_prompt_layers_desc),
                        icon = lucide("layers"),
                        searchable = false,
                        onClick = onOpenPrompts,
                    )
                }
            }

            Column {
                SettingsSectionTitle(
                    text = stringResource(R.string.orbit_section_tasks),
                    icon = lucide("calendar-clock"),
                )
                SettingsCard(backgroundColor = cardColor) {
                    SettingsNavRow(
                        title = stringResource(R.string.orbit_notifications_row),
                        subtitle = stringResource(R.string.orbit_notifications_row_desc),
                        icon = lucide("bell"),
                        searchable = false,
                        onClick = onOpenNotifications,
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

            Column {
                SettingsSectionTitle(
                    text = stringResource(R.string.orbit_section_privacy),
                    icon = lucide("shield-check"),
                )
                SettingsCard(backgroundColor = cardColor) {
                    SettingsNavRow(
                        title = stringResource(R.string.orbit_vault_row),
                        subtitle = stringResource(R.string.orbit_vault_row_desc),
                        icon = lucide("key-round"),
                        searchable = false,
                        onClick = onOpenVault,
                    )
                    SettingsRowDivider(inset = false)
                    SettingsNavRow(
                        title = stringResource(R.string.orbit_import_row),
                        subtitle = stringResource(R.string.orbit_import_row_desc),
                        icon = lucide("download"),
                        searchable = false,
                        onClick = onOpenImport,
                    )
                }
            }

            Column {
                SettingsSectionTitle(
                    text = stringResource(R.string.orbit_section_developer),
                    icon = lucide("code-xml"),
                )
                SettingsCard(backgroundColor = cardColor) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.orbit_developer_switch),
                        subtitle = stringResource(R.string.orbit_developer_switch_desc),
                        checked = settings.developerMode,
                        searchable = false,
                        onCheckedChange = { enabled -> scope.launch { preferences.setDeveloperMode(enabled) } },
                    )
                }
            }

            SettingsCard(backgroundColor = cardColor) {
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
    val openDrawer = com.ai.assistance.operit.ui.main.LocalOpenAppDrawer.current

    fun start(action: suspend () -> Unit) {
        if (starting) return
        starting = true
        scope.launch {
            try {
                action()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Starting an Orbit swaps this list for its chat, which cancels this scope. That is success.
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Could not start Orbit", e)
                toast(context, R.string.orbit_open_chat_failed)
            }
            starting = false
        }
    }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        OrbitCircleButton(
            icon = orbitIcon("equal"),
            contentDescription = stringResource(R.string.menu),
            onClick = openDrawer,
        )
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
        OrbitDeleteDialog(
            orbit = orbit,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                if (removingId == null) {
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
                }
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
