package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.animation.animateContentSize
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
import com.ai.assistance.operit.data.preferences.canAddSideChat
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
    val resources = LocalResources.current
    val preferences = remember { OrbitPreferences.getInstance(context) }
    // Settings already read open the page at once; only the very first visit waits for them.
    val settings by preferences.settingsFlow.collectAsState(initial = preferences.loadedSnapshot())
    val loadedSettings = settings ?: return
    val activeOrbitId = loadedSettings.activeOrbitId

    // A fresh install with no Orbit yet starts with the quick setup (provider + key + models).
    // It stays up while setup runs, even after the new Orbit is saved, until every step is done.
    val setupRunning by com.ai.assistance.operit.features.orbit.OrbitSetup.inProgress.collectAsState()
    var showSetup by rememberSaveable { mutableStateOf(!com.ai.assistance.operit.features.orbit.OrbitSetup.skipped(context)) }
    // A setup the app was closed in the middle of is undone first, then the setup page runs again.
    var recovering by remember { mutableStateOf(com.ai.assistance.operit.features.orbit.OrbitSetup.interrupted(context)) }
    if (recovering) LaunchedEffect(Unit) {
        com.ai.assistance.operit.features.orbit.OrbitSetup.recoverInterrupted(context)
        showSetup = true
        recovering = false
    }
    // A welcome that never reached Orbit (the app closed first) is sent now.
    LaunchedEffect(Unit) { com.ai.assistance.operit.features.orbit.OrbitSetup.resumeWelcome(context) }
    if (setupRunning || recovering || (activeOrbitId == null && showSetup && loadedSettings.orbits.isEmpty())) {
        val setupBack = rememberOrbitBackMotion()
        // Opened again from the list: back returns to the list. On first start it leaves the app.
        val fromList = com.ai.assistance.operit.features.orbit.OrbitSetup.skipped(context)
        OrbitBackHandler(setupBack, enabled = fromList || setupRunning, canDismiss = !setupRunning) {
            showSetup = false
        }
        Box(Modifier.orbitDismissLayer(setupBack)) {
            OrbitSetupPage(onSkip = {
                com.ai.assistance.operit.features.orbit.OrbitSetup.skip(context)
                showSetup = false
            })
        }
        return
    }

    if (activeOrbitId == null) {
        OrbitHome(
            settings = loadedSettings,
            hasBackgroundImage = hasBackgroundImage,
            onManageRoleCards = onManageRoleCards,
            onOpenSetup = { showSetup = true },
        )
        return
    }

    // Make sure the running Orbit still has its chat (the user may have deleted it) before showing it.
    // A chat this Orbit already had ready shows at once while that check runs again.
    val readyChatId by
        produceState(initialValue = OrbitReadyChats.get(activeOrbitId), activeOrbitId) {
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
            OrbitReadyChats.put(activeOrbitId, value)
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
    // The Feed and Ideas routines, like Muse's: made once per Orbit, then paused or rescheduled in
    // the schedule list. The Feed writes its first post right away.
    LaunchedEffect(orbit.id) {
        runCatching { com.ai.assistance.operit.features.orbit.OrbitRoutines.ensureFeed(context.applicationContext, orbit) }
            .exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        runCatching { com.ai.assistance.operit.features.orbit.OrbitRoutines.ensureIdeas(context.applicationContext, orbit) }
            .exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        // The nightly alignment review keeps the synthesis every chat carries, like Muse's dream.
        runCatching { com.ai.assistance.operit.features.orbit.OrbitRoutines.ensureAlignment(context.applicationContext, orbit) }
            .exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        // The proactive check looks for what was decided but nobody follows up.
        runCatching { com.ai.assistance.operit.features.orbit.OrbitRoutines.ensureProactiveScan(context.applicationContext, orbit) }
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
    // The name the user gave it, for words about the companion inside its chat and pages.
    CompositionLocalProvider(com.ai.assistance.operit.ui.features.chat.components.LocalOrbitName provides orbitName(orbit)) {
        OrbitAppearanceTheme(orbit) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                OrbitChatPage(
                    orbit = orbit,
                    settings = loadedSettings,
                    hasBackgroundImage = false,
                    onManageRoleCards = onManageRoleCards,
                    chatContent = chatContent,
                )
            }
        }
    }
}

@Composable
private fun OrbitChatPage(
    orbit: OrbitProfile,
    settings: OrbitSettings,
    hasBackgroundImage: Boolean,
    onManageRoleCards: () -> Unit,
    chatContent: @Composable (chatId: String, header: @Composable () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var showProfile by rememberSaveable(orbit.id) { mutableStateOf(false) }
    // The chat screen's own view model (same owner, same key), for quoting a post above its input box.
    val chatViewModel: com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel {
            com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel(context.applicationContext)
        }
    // Pages pushed over the profile, as names so the stack survives recreation.
    var stackNames by rememberSaveable(orbit.id) { mutableStateOf("") }
    val stack = remember(stackNames) {
        // A saved stack from before history was removed returns to the Soul file.
        stackNames.split(',').filter { it.isNotEmpty() && it != "SOUL_HISTORY" }.map(OrbitSubPage::valueOf)
    }
    val subPage = stack.lastOrNull()
    fun push(page: OrbitSubPage) { stackNames = (stack + page).joinToString(",") { it.name } }
    fun pop() { stackNames = stack.dropLast(1).joinToString(",") { it.name } }
    // Chat cards open pages over the chat; this reads the stack when tapped, so it can be kept.
    val manageRoleCards by androidx.compose.runtime.rememberUpdatedState(onManageRoleCards)
    val openCardPage: (OrbitCardPage) -> Unit = remember(orbit.id) {
        { page ->
            val sub = when (page) {
                OrbitCardPage.SPEECH_SETTINGS -> OrbitSubPage.SPEECH_SETTINGS
                OrbitCardPage.MODEL_CONFIG -> OrbitSubPage.MODEL_CONFIG
                OrbitCardPage.DEVICE_ACCESS -> OrbitSubPage.DEVICE_ACCESS
                OrbitCardPage.WEIXIN -> OrbitSubPage.WEIXIN
                // Role cards are managed in the app's role card page; back returns here.
                OrbitCardPage.ROLE_CARDS -> null
            }
            if (sub == null) manageRoleCards() else {
                val current = stackNames.split(',').filter { it.isNotEmpty() }
                if (current.lastOrNull() != sub.name) stackNames = (current + sub.name).joinToString(",")
            }
        }
    }
    // Answers the user gave while Orbit could not hear them (the app closed while it was busy).
    LaunchedEffect(orbit.id) {
        runCatching { com.ai.assistance.operit.features.orbit.OrbitApprovals.deliverPending(context.applicationContext, orbit) }
            .exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
    }
    var openTaskId by rememberSaveable(orbit.id) { mutableStateOf<String?>(null) }
    var openVaultEntryId by rememberSaveable(orbit.id) { mutableStateOf<String?>(null) }
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
    var showImport by remember { mutableStateOf(false) }
    val pushState = remember(orbit.id) { OrbitPushState() }
    // Developer option: the same chat drawn as a normal Operit chat (thinking, tools, hidden turns,
    // the full long-press menu) instead of Orbit's messaging look. Turning the option off leaves it.
    var devChatView by rememberSaveable(orbit.id) { mutableStateOf(false) }
    val devView = devChatView && settings.developerMode
    // In the developer view: a subagent's chat shown instead of Orbit's own. A back preview only
    // changes what is drawn: keep the chat and its presentation selected until leaving is certain
    // (see leaveDevView), or the developer option is turned off.
    var devSubChatId by remember(orbit.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.developerMode) { if (!settings.developerMode) devSubChatId = null }
    // The developer view is opened from the profile and goes back to it, like a page pushed over it:
    // the profile stays where it was, kept but hidden, while the developer view shows. Leaving slides
    // it off to the right: 0 is in place, 1 is off screen. As the back starts, what the developer view
    // shows is recorded and the page it goes back to (the profile, or the Orbit chat if the profile
    // was closed meanwhile) shows underneath, so the recording slides away over it. Let go early and
    // it slides back and the same developer view is revealed, without switching chats.
    val devLeave = remember(orbit.id) { androidx.compose.animation.core.Animatable(0f) }
    var devLeaving by remember(orbit.id) { mutableStateOf(false) }
    var devGesture by remember(orbit.id) { mutableStateOf(false) }
    var devSettling by remember(orbit.id) { mutableStateOf(false) }
    var devPicture by remember(orbit.id) { mutableStateOf<android.graphics.Picture?>(null) }
    var devPictureWanted by remember(orbit.id) { mutableStateOf(false) }
    val devRecorded = remember(orbit.id) { arrayOfNulls<android.graphics.Picture>(1) }
    var devSwap by remember(orbit.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val devScope = rememberCoroutineScope()
    // Records the developer view on the next frame, then shows the recording in its place.
    suspend fun swapToPicture() {
        // Recordings take image bitmaps only from Android 9; before that the view moves itself.
        if (devPicture != null || !devChatView || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            devPictureWanted = false
            return
        }
        devPictureWanted = true
        try {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            val picture = devRecorded[0]
            if (picture != null && devChatView) {
                devPicture = picture
            }
        } finally {
            // Cancelled or done, nothing recorded is kept past this point.
            devPictureWanted = false
            devRecorded[0] = null
        }
    }
    fun leaveDevView() {
        // A swipe still settling back owns the view until it is done; the button waits for it.
        if (devLeaving || devSettling) return
        devLeaving = true
        devScope.launch {
            try {
                devSwap?.join()
                swapToPicture()
                // Without a recording there is nothing to slide; it goes straight to the Orbit chat.
                if (devPicture != null) {
                    val rest = (OrbitMotion.PUSH_MS * (1f - devLeave.value)).toInt().coerceAtLeast(120)
                    devLeave.animateTo(1f, tween(rest, easing = OrbitMotion.Easing))
                }
                devChatView = false
                devSubChatId = null
            } finally {
                devPicture = null
                devLeaving = false
                withContext(kotlinx.coroutines.NonCancellable) { devLeave.snapTo(0f) }
            }
        }
    }
    // A cancelled back only removes the recording; the live chat has stayed selected and composed.
    fun settleDevView() {
        devSettling = true
        devScope.launch {
            try {
                // Cancelled at once, so it can no longer swap the recording in behind this.
                val swap = devSwap
                swap?.cancel()
                if (devPicture == null) {
                    // Let go before the recording took over: the view never moved, so it stays put.
                    devLeave.snapTo(0f)
                    swap?.join()
                } else {
                    swap?.join()
                    devLeave.animateTo(0f, tween(220, easing = OrbitMotion.Easing))
                }
            } finally {
                devPicture = null
                devSettling = false
            }
        }
    }
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
    var forkingMessage by remember(orbit.id) { mutableStateOf(false) }
    fun forkMessage(message: com.ai.assistance.operit.data.model.ChatMessage) {
        if (forkingMessage) return
        forkingMessage = true
        reactionScope.launch {
            try {
                val created = OrbitCompanion.forkSideChat(context, orbit.id, message.timestamp)
                // The reply chip is global, unlike per-chat drafts; do not carry an unrelated quote.
                chatViewModel.clearReplyToMessage()
                justCreatedChatId = created.chatId
                talkChatId = created.chatId
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, resources.getString(R.string.orbit_chats_failed),
                    android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                forkingMessage = false
            }
        }
    }
    val userReactions = remember(userReactionMap, currentChatId) {
        com.ai.assistance.operit.ui.features.chat.components.OrbitUserReactions(userReactionMap) { key, emoji, said ->
            // Taking a tapback off says nothing to Orbit; putting one on lets it decide what to do.
            val adding = userReactionMap[key] != emoji
            val chatId = currentChatId
            reactionScope.launch {
                try {
                    userReactionStore.toggle(chatId, key, emoji)
                    if (adding) OrbitReactionSender.onReacted(context, orbit.id, chatId, key, emoji, said)
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
    // A button that asks Orbit for something: its prompt waits in the main chat's input box, and
    // every page over the chat closes so the user can finish it and decide whether to send.
    fun draftToChat(text: String) {
        draftToOrbit(context, orbit, text)
        talkChatId = orbit.chatId
        tab = OrbitTab.CHAT
        showProfile = false
        stackNames = ""
    }
    // A tapped report notification or a share opens that chat, over whatever page was showing.
    val openRequest by com.ai.assistance.operit.features.orbit.OrbitNavigation.pendingChat.collectAsState()
    LaunchedEffect(openRequest, orbit.talkChatIds) {
        val id = openRequest ?: return@LaunchedEffect
        if (id in orbit.talkChatIds) {
            // An explicit chat navigation leaves the inspector too. Otherwise it would keep a
            // subagent selected after closing the profile that its back preview needs underneath.
            devSwap?.cancel()
            devPicture = null
            devChatView = false
            devSubChatId = null
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
    // The profile is revealed under the back preview without changing the selected chat.
    val profileShown = showProfile && (!devView || devPicture != null)
    val chatOnScreen = tab == OrbitTab.CHAT && !showChats && !profileShown &&
        subPage == null && !showImport &&
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
    // grows out of Orbit's picture over it, and the pages opened from it slide in from the right.
    val sharedAvatar = remember(orbit.id) {
        OrbitSharedAvatar(androidx.compose.animation.core.Animatable(if (showProfile) 0f else 1f))
    }
    CompositionLocalProvider(
        LocalOrbitActivity provides rememberOrbitActivity(orbit, currentChatId),
        LocalOrbitSharedAvatar provides sharedAvatar,
    ) {
    androidx.compose.runtime.SideEffect { sharedAvatar.onTab = tab != OrbitTab.CHAT }
    Box(Modifier.fillMaxSize()) {
        val tabBack = rememberOrbitBackMotion()
        OrbitBackHandler(tabBack, enabled = tab != OrbitTab.CHAT && !showProfile && !devView && stack.isEmpty()) {
            tab = OrbitTab.CHAT
        }
        // The back swipe slides the developer view to the right under the finger, like leaving a page.
        // Stays on while the view moves, so a second back is swallowed here instead of leaving Orbit.
        androidx.activity.compose.PredictiveBackHandler(
            enabled = devLeaving || devGesture || devSettling ||
                (devView && !showChats && stack.isEmpty())
        ) { events ->
            if (devLeaving || devSettling) {
                events.collect { }
                return@PredictiveBackHandler
            }
            devGesture = true
            // Held in place until the recording takes over, so no bare page shows beside it.
            if (devChatView && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) devPictureWanted = true
            devSwap = devScope.launch { swapToPicture() }
            try {
                events.collect { devLeave.snapTo(it.progress) }
                leaveDevView()
                devGesture = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Let go before the edge: it settles back in place.
                if (!devLeaving) settleDevView()
                devGesture = false
                throw e
            }
        }
        val pageColor = MaterialTheme.colorScheme.background
        // While the menu is open, screen readers only see the menu, not the page behind it.
        Column(
            Modifier.fillMaxSize().graphicsLayer {
                // A page opened from the settings over the chat moves it a little to the left, like iOS.
                // Leaving the developer view, the Orbit chat waits a little to the left under its
                // recording. The developer view itself never moves: without a recording (Android 8,
                // or a swipe let go before it was ready) it simply stays, so no bare page shows.
                translationX = (if (showProfile) 0f else -size.width * 0.3f * pushState.baseCover()) +
                    (if (devPicture != null && !showProfile) -size.width * 0.3f * (1f - devLeave.value) else 0f)
            }.drawWithContent {
                if (devPictureWanted && devView) {
                    // Recording the developer view once for its way out; drawn from the recording too.
                    val picture = devRecorded[0] ?: android.graphics.Picture().also { picture ->
                        val pictureCanvas = androidx.compose.ui.graphics.Canvas(
                            picture.beginRecording(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                        )
                        draw(this, layoutDirection, pictureCanvas, size) {
                            drawRect(pageColor)
                            this@drawWithContent.drawContent()
                        }
                        picture.endRecording()
                        devRecorded[0] = picture
                    }
                    drawIntoCanvas { it.nativeCanvas.drawPicture(picture) }
                } else if (devPicture == null) {
                    drawContent()
                } else {
                    // Keep the live chat laid out, but only draw its recording during the gesture.
                    drawRect(pageColor)
                }
            }.then(
                // Pages on the stack cover the chat; screen readers only see the top one.
                // The recording on its way out covers it too, until the Orbit chat is fully shown.
                if (showChats || stack.isNotEmpty() || profileShown || editingFile != null || devPicture != null) Modifier.clearAndSetSemantics { }
                else Modifier
            )
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // A subagent's chat is switched to like a side chat, so back leaves the developer
                // view the same way the top-left button does, with the same slide.
                CompositionLocalProvider(
                    LocalOrbitChatLook provides !devView,
                    LocalOrbitOpenPage provides openCardPage,
                    LocalOrbitForkMessage provides
                        if (!devView && currentChatId == orbit.chatId && !forkingMessage && orbit.canAddSideChat)
                            ::forkMessage else null,
                    com.ai.assistance.operit.ui.features.chat.components.LocalShowHiddenChatRows provides devView,
                    com.ai.assistance.operit.ui.features.chat.components.LocalOrbitReactions provides reactions,
                    com.ai.assistance.operit.ui.features.chat.components.LocalOrbitUserReactions provides userReactions,
                    // Orbit's browser cards, each where it first showed in the chat.
                    com.ai.assistance.operit.ui.features.chat.components.LocalOrbitChatFooter provides
                        if (devView) null else {
                            // Covered chats stop redrawing the page picture.
                            val cardVisible = chatOnScreen && stack.isEmpty() && editingFile == null
                            remember(currentChatId, cardVisible) {
                                { color: androidx.compose.ui.graphics.Color, slot: com.ai.assistance.operit.ui.features.chat.components.OrbitCardSlot ->
                                    OrbitBrowserCard(currentChatId, color, cardVisible, slot)
                                }
                            }
                        },
                    // In the developer view a subagent row opens that subagent's chat right here.
                    com.ai.assistance.operit.ui.features.chat.components.LocalSubagentChatHost provides
                        if (devView) {
                            val parent = devSubChatId ?: currentChatId
                            remember(parent) {
                                com.ai.assistance.operit.ui.features.chat.components.SubagentChatHost(parent) { devSubChatId = it }
                            }
                        } else null,
                ) {
                    chatContent(if (devView) devSubChatId ?: currentChatId else currentChatId) {
                        if (devView) {
                            OrbitDevHeader(
                                orbit = orbit, currentChatId = devSubChatId ?: currentChatId,
                                onSelectChat = {
                                    devSubChatId = null
                                    talkChatId = it
                                },
                                onSelectSubagent = { devSubChatId = it },
                                onExit = { leaveDevView() },
                            )
                        } else {
                            Column {
                                OrbitHeader(
                                    orbit = orbit, sideTitle = currentSide?.title, hasUnread = hasUnread,
                                    activity = orbitActivity(),
                                    avatarAnimated = chatOnScreen,
                                    onOpenProfile = { showProfile = true }, onOpenMenu = { showChats = true },
                                )
                                currentSide?.forkOrigin?.let { origin ->
                                    OrbitForkReference(origin, onOpenMain = {
                                        com.ai.assistance.operit.ui.features.chat.components.TranscriptJumpHost
                                            .requestMessage(origin.chatId, origin.messageId)
                                        talkChatId = orbit.chatId
                                    })
                                }
                            }
                        }
                    }
                }
                // Tabs switch at once, like an iOS tab bar: a fade would let the chat underneath show
                // through for a moment. Each page draws the same header in the same place as the
                // chat, so Orbit's picture stays put on top while the content changes.
                // The tab layer stays composed on the chat tab (unplaced, header only), so a pet
                // avatar is not loaded again and does not flash each time a tab is opened.
                if (!devView) {
                    val onTab = tab != OrbitTab.CHAT
                    val tabHeader: @Composable () -> Unit = remember(orbit) {
                        {
                            OrbitHeader(
                                orbit = orbit, sideTitle = null, hasUnread = hasUnreadState.value,
                                activity = orbitActivity(),
                                avatarAnimated = tab != OrbitTab.CHAT && !showProfile && !showChats &&
                                    stackNames.isEmpty() && editingFile == null && !showImport,
                                onOpenProfile = { showProfile = true }, onOpenMenu = { showChats = true },
                                onTabLayer = true,
                            )
                        }
                    }
                    CompositionLocalProvider(LocalOrbitTabHeader provides tabHeader) {
                        OrbitTabPage(orbit, pageTab, visible = onTab,
                            contentLayer = Modifier.orbitDismissLayer(tabBack),
                            onDraft = { text ->
                                // The prompt waits in the main chat's input box for the user to finish.
                                draftToOrbit(context, orbit, text)
                                talkChatId = orbit.chatId
                                tab = OrbitTab.CHAT
                            },
                            onQuote = { quoted ->
                                // Quoted above the main chat's input box, like replying to a message.
                                chatViewModel.setReplyToMessage(quoted)
                                talkChatId = orbit.chatId
                                tab = OrbitTab.CHAT
                            },
                            filesVersion = filesVersion, onOpenFile = { editingFile = it })
                    }
                }
            }
            // The keyboard slides over the tabs: as it rises the tab bar shrinks under it, and as it
            // goes down the bar comes back with it, frame by frame, so the input never jumps.
            if (!devView) {
                OrbitBottomBar(selected = tab, onSelect = {
                    if (it != OrbitTab.CHAT) pageTab = it
                    tab = it
                }, modifier = Modifier.tuckUnderKeyboard(androidx.compose.foundation.layout.WindowInsets.ime))
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
            onOpenSettings = { push(OrbitSubPage.SETTINGS) },
            onLeaveOrbit = leaveOrbit,
        )
        // Keeps the last file on screen while the page slides away.
        var shownFile by remember(orbit.id) { mutableStateOf(editingFile) }
        LaunchedEffect(editingFile) { editingFile?.let { shownFile = it } }
        OrbitCoverPage(
            visible = editingFile != null,
            onDismiss = { editingFile = null; filesVersion++ },
        ) {
            (editingFile ?: shownFile)?.let { path ->
                val files = remember(orbit.id) { com.ai.assistance.operit.features.orbit.OrbitFiles.of(context, orbit.id) }
                OrbitMadeFilePage(files, path, orbitName(orbit), onBack = { editingFile = null; filesVersion++ })
            }
        }
        // The profile grows out of Orbit's picture while the picture itself moves on top into the
        // profile's place; the back swipe shrinks it back under the finger. Under the developer
        // view it is kept but not laid out, so it draws nothing and takes no touch, and comes
        // back exactly as it was; closed meanwhile, it finishes closing out of sight. Its back goes to
        // a dispatcher nobody presses until the developer view is fully gone. While the developer view
        // slides off, it waits a little to the left under it.
        val devOwnsBack = devView || devPicture != null || devLeaving || devGesture || devSettling
        val realBackOwner = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
        val inertBackOwner = rememberInertBackOwner()
        Box(
            Modifier.fillMaxSize()
                .then(
                    // Closed while hidden, it stays out of sight until the developer view is fully gone.
                    if ((devView && devPicture == null) || (!showProfile && devOwnsBack)) Modifier.layout { _, constraints ->
                        layout(constraints.maxWidth, constraints.maxHeight) {}
                    } else Modifier
                )
                .graphicsLayer {
                    if (devPicture != null) translationX = -size.width * 0.3f * (1f - devLeave.value)
                }
                // Screen readers reach it only once the developer view is gone.
                .then(if (devPicture != null) Modifier.clearAndSetSemantics { } else Modifier)
        ) {
        CompositionLocalProvider(
            androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides
                (if (devOwnsBack || realBackOwner == null) inertBackOwner else realBackOwner),
        ) {
        OrbitCoverPage(
            visible = showProfile,
            onDismiss = { showProfile = false },
            backEnabled = stack.isEmpty(),
            progress = sharedAvatar.profile,
            zoom = { s -> sharedAvatar.pageZoom(this, s) },
        ) {
            Box(Modifier.fillMaxSize().onPlaced { sharedAvatar.profilePage = it }) {
                // Pushed pages move the profile a little to the left underneath them.
                Box(
                    Modifier.fillMaxSize().graphicsLayer { translationX = -size.width * 0.3f * pushState.baseCover() }
                        .then(if (stack.isNotEmpty()) Modifier.clearAndSetSemantics { } else Modifier)
                ) {
                    OrbitProfilePage(
                        orbit = orbit,
                        visible = profileShown && subPage == null,
                        onClose = { showProfile = false },
                        onOpenSettings = { push(OrbitSubPage.SETTINGS) },
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
                        onDraft = ::draftToChat,
                        onOpenRawChat = {
                            // The chat drawn as a normal Operit chat, over the profile it goes back to.
                            devChatView = true
                            tab = OrbitTab.CHAT
                            stackNames = ""
                        },
                    )
                }
            }
        }
        // Above the profile, under pages pushed from it: Orbit's picture always stays on top.
        // The one avatar plays only while one of its places is in view.
        OrbitSharedAvatarLayer(
            orbit, sharedAvatar,
            playing = chatOnScreen || (profileShown && subPage == null) ||
                (tab != OrbitTab.CHAT && !showProfile && !showChats && stackNames.isEmpty() &&
                    editingFile == null && !showImport &&
                    lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)),
        )
        }
        }
        // Pages opened from the profile or the settings slide in over whatever is showing, so
        // opening one from the settings never shows the profile on the way.
        OrbitPushHost(
            entries = stack,
            onPop = { pop() },
            state = pushState,
        ) { page ->
            val back = { pop() }
            when (page) {
                OrbitSubPage.SETTINGS -> OrbitSettingsPage(
                    orbit = orbit,
                    settings = settings,
                    hasBackgroundImage = false,
                    onBack = back,
                    onOpenPrompts = { push(OrbitSubPage.PROMPTS) },
                    onOpenPackages = { push(OrbitSubPage.PACKAGES) },
                    onOpenToolPermissions = { push(OrbitSubPage.TOOL_PERMISSIONS) },
                    onOpenToolAccess = { push(OrbitSubPage.TOOL_ACCESS) },
                    onOpenVault = { push(OrbitSubPage.VAULT) },
                    onOpenImport = { showImport = true },
                    onOpenUsage = { push(OrbitSubPage.USAGE) },
                    onOpenAppearance = { push(OrbitSubPage.APPEARANCE) },
                    onOpenModels = { push(OrbitSubPage.MODELS) },
                    onOpenContext = { push(OrbitSubPage.CONTEXT) },
                    onOpenPhonePermissions = { push(OrbitSubPage.PHONE_PERMISSIONS) },
                    onOpenNotificationSettings = { push(OrbitSubPage.NOTIFICATION_SETTINGS) },
                    onOpenChannels = { push(OrbitSubPage.CHANNELS) },
                    onPause = back,
                )
                OrbitSubPage.CHANNELS -> OrbitChannelsPage(orbit, onBack = back,
                    onOpenWeixin = { push(OrbitSubPage.WEIXIN) })
                OrbitSubPage.WEIXIN -> OrbitWeixinPage(orbit, onBack = back, onOpenChat = { chat ->
                    talkChatId = chat
                    tab = OrbitTab.CHAT
                    showProfile = false
                    stackNames = ""
                })
                OrbitSubPage.APPEARANCE -> OrbitAppearancePage(orbit, onBack = back)
                OrbitSubPage.MODELS -> OrbitModelsPage(orbit, onBack = back,
                    onOpenModelConfig = { push(OrbitSubPage.MODEL_CONFIG) })
                OrbitSubPage.CONTEXT -> OrbitContextPage(orbit, onBack = back)
                OrbitSubPage.MODEL_CONFIG -> OrbitModelConfigPage(onBack = back)
                OrbitSubPage.SPEECH_SETTINGS -> OrbitSpeechSettingsPage(onBack = back)
                OrbitSubPage.PHONE_PERMISSIONS -> OrbitPhonePermissionsPage(onBack = back,
                    onOpenDeviceAccess = { push(OrbitSubPage.DEVICE_ACCESS) })
                OrbitSubPage.DEVICE_ACCESS -> OrbitDeviceAccessPage(orbitName(orbit), onBack = back, onDraft = ::draftToChat)
                OrbitSubPage.NOTIFICATION_SETTINGS -> OrbitNotificationSettingsPage(orbit, onBack = back)
                OrbitSubPage.USAGE -> OrbitUsagePage(orbit, onBack = back,
                    onOpenTrends = { push(OrbitSubPage.USAGE_TRENDS) })
                OrbitSubPage.USAGE_TRENDS -> OrbitUsageTrendsPage(orbit, onBack = back)
                OrbitSubPage.SOUL -> OrbitSoulFilePage(orbit.id, onBack = back)
                OrbitSubPage.IDENTITY -> OrbitIdentityFilePage(orbit.id, onBack = back)
                OrbitSubPage.USER -> OrbitUserFilePage(orbit, onBack = back)
                OrbitSubPage.MEMORY -> OrbitMemoryFilePage(orbit, onBack = back)
                OrbitSubPage.PROMPT -> OrbitPromptFilePage(orbit.id, onBack = back)
                OrbitSubPage.ALIGNMENT -> OrbitAlignmentFilePage(orbit.id, onBack = back)
                OrbitSubPage.PROMPTS -> OrbitPromptLayersPage(
                    orbit = orbit,
                    onBack = back,
                    onOpenPrompt = { push(OrbitSubPage.PROMPT) },
                    onOpenSoul = { push(OrbitSubPage.SOUL) },
                    onOpenIdentity = { push(OrbitSubPage.IDENTITY) },
                    onOpenAlignment = { push(OrbitSubPage.ALIGNMENT) },
                    onOpenMemory = { push(OrbitSubPage.MEMORY) },
                    onOpenUser = { push(OrbitSubPage.USER) },
                )
                OrbitSubPage.PACKAGES -> OrbitPackagesPage(orbit, onBack = back)
                OrbitSubPage.VAULT -> OrbitVaultPage(orbit, onBack = back, onOpenEntry = { id ->
                    openVaultEntryId = id
                    push(OrbitSubPage.VAULT_ENTRY)
                })
                OrbitSubPage.VAULT_ENTRY -> {
                    val id = openVaultEntryId
                    // Leaves only while this page is still on top, read when it is asked:
                    // a delete that finishes after the user went back must not pop the list.
                    if (id != null) OrbitVaultEntryPage(orbit, id, onBack = {
                        val now = stackNames.split(',').filter { it.isNotEmpty() }
                        if (now.lastOrNull() == OrbitSubPage.VAULT_ENTRY.name) {
                            stackNames = now.dropLast(1).joinToString(",")
                        }
                    }, onDraft = { draftToChat(it) })
                    else LaunchedEffect(Unit) { pop() }
                }
                OrbitSubPage.TOOL_PERMISSIONS -> {
                    if (settings.developerMode) OrbitToolPermissionsPage(onBack = back)
                    else LaunchedEffect(Unit) { back() }
                }
                OrbitSubPage.TOOL_ACCESS -> OrbitToolAccessPage(onBack = back)
                OrbitSubPage.NOTIFICATIONS -> OrbitNotificationsPage(orbit.id, onBack = back)
                OrbitSubPage.FILES -> OrbitFilesPage(orbit.id, onBack = back, onDraft = ::draftToChat)
                // No id opens the grouped list of every task.
                OrbitSubPage.TASK -> OrbitTasksPage(orbit.id, onBack = back, initialTaskId = openTaskId, onDraft = ::draftToChat)
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
                        OrbitLearningPage(id, orbit.chatId, back, onDraft = ::draftToChat)
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
        // The recorded developer view sliding off the page it goes back to, over every page of this
        // Orbit, with the usual dim over that page. It takes every touch while it moves. The recording
        // is played into its own layer once and only the layer moves, so the swipe does not replay the
        // whole page each frame.
        devPicture?.let { picture ->
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                    }
                    .drawBehind {
                        val dim = 0.1f * (1f - devLeave.value)
                        if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
                    }
            )
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { translationX = size.width * devLeave.value }
                    .drawBehind { drawIntoCanvas { it.nativeCanvas.drawPicture(picture) } }
            )
        }
    }

    }

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

private enum class OrbitSubPage { SETTINGS, TASK, PROCESS, SOUL, MEMORY, MEMORY_LIBRARY, NOTIFICATIONS, IDENTITY, USER, PROMPTS, PROMPT, ALIGNMENT, FILES, PACKAGES, TOOL_PERMISSIONS, TOOL_ACCESS, VAULT, VAULT_ENTRY, USAGE, USAGE_TRENDS, APPEARANCE, MODELS, CONTEXT, MODEL_CONFIG, PHONE_PERMISSIONS, DEVICE_ACCESS, NOTIFICATION_SETTINGS, SPEECH_SETTINGS, CHANNELS, WEIXIN }

/** iOS-like timing: a quick start that settles softly (UIKit's default presentation curve). */
internal object OrbitMotion {
    val Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    const val PRESENT_MS = 500
    const val DISMISS_MS = 380
    const val PUSH_MS = 420
    /** The profile growing out of Orbit's picture, and shrinking back into it. */
    const val ZOOM_IN_MS = 360
    const val ZOOM_OUT_MS = 300
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
    /** What Orbit is doing now ([orbitActivity]); null while it rests. */
    activity: String? = null,
    avatarAnimated: Boolean = true,
    /** The copy the other bottom tabs show, in the same place as the chat's. */
    onTabLayer: Boolean = false,
) {
    val pageColor = MaterialTheme.colorScheme.background
    val avatarSize = orbit.appearance.avatarSize.dp
    // In the main chat the activity line appears only while Orbit works. The name block then
    // reports its height without that line, so the header (and the chat's top padding, which
    // follows it) does not change and the messages never jump; the extra line just draws a
    // little lower. The line's own measured height is taken off, so no earlier idle frame is
    // needed, and the header still takes the taller of the name block and the menu button.
    val growsWithActivity = sideTitle == null && activity != null
    val activityLineHeight = remember { intArrayOf(0) }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to pageColor,
                        // Like iOS: solid behind the status bar, then messages show through more
                        // and more toward the name, so they fade out under the header.
                        0.22f to pageColor.copy(alpha = 0.94f),
                        0.55f to pageColor.copy(alpha = 0.72f),
                        0.82f to pageColor.copy(alpha = 0.32f),
                        1f to pageColor.copy(alpha = 0f),
                    )
                )
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 22.dp)
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
        Box(
            modifier = Modifier.align(Alignment.TopCenter)
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val reported = if (growsWithActivity) {
                        (placeable.height - activityLineHeight[0]).coerceAtLeast(0)
                    } else placeable.height
                    layout(placeable.width, reported) { placeable.placeRelative(0, 0) }
                }
                .then(
                if (avatarSize == 0) Modifier.fillMaxWidth().padding(horizontal = 60.dp) else Modifier
            ),
            contentAlignment = Alignment.TopCenter,
        ) {
            val shared = LocalOrbitSharedAvatar.current
            if (avatarSize > 0 && shared != null) OrbitAvatarSlot(
                shared = shared,
                size = avatarSize,
                modifier = Modifier.zIndex(1f),
                onClickLabel = stringResource(R.string.orbit_profile_open, orbitName(orbit)),
                onClick = onOpenProfile,
                report = { if (onTabLayer) shared.tabHeader = it else shared.chatHeader = it },
            ) else if (avatarSize > 0) OrbitAvatar(
                orbit = orbit,
                size = avatarSize,
                modifier = Modifier.zIndex(1f),
                animatePet = true,
                petVisible = avatarAnimated,
                onClickLabel = stringResource(R.string.orbit_profile_open, orbitName(orbit)),
                onClick = onOpenProfile,
            )
            // The avatar stays above the pill's top edge; its overlap never covers the name.
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                modifier =
                    Modifier.padding(top = (avatarSize - 6).coerceAtLeast(12).dp)
                        .widthIn(max = 240.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onOpenProfile),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = (if (sideTitle != null) Modifier.animateContentSize(androidx.compose.animation.core.tween(180)) else Modifier)
                        .padding(start = 16.dp, end = 16.dp, top = if (avatarSize > 0) 10.dp else 6.dp, bottom = 6.dp),
                ) {
                    Text(
                        text = orbit.name.ifBlank { stringResource(R.string.nav_orbit) },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // While Orbit works, what it does sits under the name; otherwise in a side chat
                    // its topic does, so the user knows where they are.
                    val subtitle = activity ?: sideTitle
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.layout { measurable, constraints ->
                                val line = measurable.measure(constraints)
                                activityLineHeight[0] = line.height
                                layout(line.width, line.height) { line.placeRelative(0, 0) }
                            },
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
    onSelectSubagent: (String) -> Unit,
    onExit: () -> Unit,
) {
    val pageColor = MaterialTheme.colorScheme.background
    val mainTitle = stringResource(R.string.orbit_chats_main)
    var pickerOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val resources = LocalResources.current
    // Subagents under Orbit's chats, read again each time the list opens.
    val subagents by androidx.compose.runtime.produceState(
        emptyList<com.ai.assistance.operit.data.model.SubagentRunEntity>(), orbit.talkChatIds, pickerOpen,
    ) {
        value = try {
            OrbitSubagents.underChats(context, orbit.talkChatIds)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            value
        }
    }
    val currentTitle = orbit.sideChats.firstOrNull { it.chatId == currentChatId }?.title
        ?: subagents.firstOrNull { it.childChatId == currentChatId }?.let { OrbitSubagents.nameOf(it) }
        ?: if (currentChatId in orbit.talkChatIds) mainTitle else stringResource(R.string.orbit_dev_chats_subagents)
    // Always open: subagents may start after this header showed, and the list reloads on open.
    val canSwitch = true
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
                        .clickable(enabled = canSwitch, onClickLabel = switchLabel,
                            role = androidx.compose.ui.semantics.Role.DropdownList) { pickerOpen = true },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 18.dp, end = if (canSwitch) 10.dp else 18.dp, top = 6.dp, bottom = 6.dp),
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
                    if (canSwitch) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp).size(20.dp),
                        )
                    }
                }
            }
        }
    }
    if (pickerOpen) {
        OrbitDevChatSheet(orbit, currentChatId, subagents, onSelect = onSelectChat, onSelectSubagent = onSelectSubagent,
            onDismiss = { pickerOpen = false })
    }
}

/**
 * The picture an Orbit shows: only its own. Without one it shows the Orbit logo, never the
 * picture of the role card its chat runs under.
 */
internal fun orbitPictureUri(orbit: OrbitProfile): String? = orbit.avatarUri?.takeIf { it.isNotBlank() }

/** Orbit's own picture, or a role card's avatar in the picker. */
@Composable
internal fun OrbitAvatar(
    orbit: OrbitProfile?,
    size: Int,
    modifier: Modifier = Modifier,
    card: CharacterCard? = null,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
    animatePet: Boolean = false,
    petVisible: Boolean = true,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    // An Orbit keeps the copy it made of its picture, so it never reads the role card again.
    val roleCardId = if (orbit == null) card?.id else null
    val customUri = orbit?.let(::orbitPictureUri)
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
    val petId = orbit?.avatarPetId
    val pet = if (petId != null) {
        val pets by remember(context) { com.ai.assistance.operit.pet.PetPreferences.get(context).pets }.collectAsState()
        pets.firstOrNull { it.id == petId }
    } else null
    var lastPetActivity by remember(orbit?.id, petId) { mutableStateOf(com.ai.assistance.operit.pet.PetActivity.IDLE) }
    val petActivity = if (petVisible) LocalOrbitActivity.current.value.pet else lastPetActivity
    androidx.compose.runtime.SideEffect { if (petVisible) lastPetActivity = petActivity }
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
                .background(Color.White)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val uri = avatarUri
        val still: @Composable () -> Unit = {
            if (!uri.isNullOrBlank()) {
                Image(
                    painter = rememberAsyncImagePainter(model = Uri.parse(uri)),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (petId != null) ContentScale.Fit else ContentScale.Crop,
                )
            } else if (orbit != null) {
                // An Orbit without a picture shows its own logo, which moves with what it is doing.
                OrbitLogoAvatar(
                    activity = if (animatePet) petActivity else com.ai.assistance.operit.pet.PetActivity.IDLE,
                    animate = animatePet && petVisible,
                    modifier = Modifier.fillMaxSize().padding((size * 0.18f).dp),
                    userColor = orbitLogoColor(orbit.appearance.logoUserColor),
                    orbitColor = orbitLogoColor(orbit.appearance.logoOrbitColor),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = Color(0xFF47474B),
                    modifier = Modifier.size((size / 2).dp),
                )
            }
        }
        if (pet != null && animatePet) {
            // Reuse the pet's own atlas rows / state artwork, with no floating-pet controls.
            com.ai.assistance.operit.pet.PetSprite(
                activity = petActivity,
                settings = pet.settings,
                interaction = 0,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                playbackEnabled = petVisible,
                placeholder = still,
            )
        } else {
            Box(Modifier.fillMaxSize().padding(if (petId != null) 4.dp else 0.dp), contentAlignment = Alignment.Center) {
                still()
            }
        }
    }
}

/**
 * The settings, a full page on the same stack as the pages it opens, so going back from one of
 * them slides back to the settings, like iOS.
 */
@Composable
private fun OrbitSettingsPage(
    orbit: OrbitProfile,
    settings: OrbitSettings,
    hasBackgroundImage: Boolean,
    onBack: () -> Unit,
    onOpenPrompts: () -> Unit,
    onOpenPackages: () -> Unit,
    onOpenToolPermissions: () -> Unit,
    onOpenToolAccess: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenContext: () -> Unit,
    onOpenPhonePermissions: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenChannels: () -> Unit,
    onPause: () -> Unit,
) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val preferences = remember { OrbitPreferences.getInstance(context) }
    val cardColor = settingsCardColor(hasBackgroundImage)
    val toolPermissions = remember { com.ai.assistance.operit.ui.permissions.ToolPermissionSystem.getInstance(context) }
    val fullAccess by toolPermissions.orbitFullAccessFlow.collectAsState(initial = null)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            OrbitPageHeader(stringResource(R.string.orbit_settings), onBack, Modifier.padding(start = 4.dp))

            // Show the weekly and extra allowance together.
            OrbitQuotaCard(orbit = orbit, cardColor = cardColor, onOpenUsage = onOpenUsage)

            SettingsCard(backgroundColor = cardColor) {
                SettingsNavRow(
                    title = stringResource(R.string.orbit_models_title),
                    subtitle = stringResource(R.string.orbit_models_entry_note),
                    icon = lucide("brain"),
                    searchable = false,
                    onClick = onOpenModels,
                )
                SettingsRowDivider(inset = false)
                SettingsNavRow(
                    title = stringResource(R.string.orbit_context_title),
                    subtitle = stringResource(R.string.orbit_context_row),
                    icon = lucide("archive"),
                    searchable = false,
                    onClick = onOpenContext,
                )
            }

            SettingsCard(backgroundColor = cardColor) {
                com.ai.assistance.operit.ui.features.settings.components.LaunchModeSettingsRow(searchable = false)
                SettingsRowDivider(inset = false)
                SettingsNavRow(
                    title = stringResource(R.string.orbit_notification_settings),
                    subtitle = stringResource(R.string.orbit_phone_notify_note),
                    icon = lucide("bell"),
                    searchable = false,
                    onClick = onOpenNotificationSettings,
                )
                SettingsRowDivider(inset = false)
                SettingsNavRow(
                    title = stringResource(R.string.orbit_appearance_title),
                    subtitle = stringResource(R.string.orbit_appearance_note),
                    icon = lucide("paintbrush"),
                    searchable = false,
                    onClick = onOpenAppearance,
                )
                SettingsRowDivider(inset = false)
                SettingsNavRow(
                    title = stringResource(R.string.orbit_phone_permissions),
                    subtitle = stringResource(R.string.orbit_phone_permissions_row),
                    icon = lucide("hand"),
                    searchable = false,
                    onClick = onOpenPhonePermissions,
                )
                SettingsRowDivider(inset = false)
                SettingsNavRow(
                    title = stringResource(R.string.orbit_channels_title),
                    subtitle = stringResource(R.string.orbit_channels_row),
                    icon = lucide("message-circle"),
                    searchable = false,
                    onClick = onOpenChannels,
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
                    SettingsRowDivider(inset = false)
                    SettingsNavRow(
                        title = stringResource(R.string.orbit_packages_title),
                        subtitle = stringResource(R.string.orbit_packages_row_desc),
                        icon = lucide("package"),
                        searchable = false,
                        onClick = onOpenPackages,
                    )
                }
            }

            Column {
                SettingsSectionTitle(
                    text = stringResource(R.string.orbit_section_privacy),
                    icon = lucide("shield-check"),
                )
                SettingsCard(backgroundColor = cardColor) {
                    SettingsNavRow(
                        title = stringResource(R.string.orbit_access_title),
                        subtitle = stringResource(R.string.orbit_access_row_desc),
                        icon = lucide("shield"),
                        searchable = false,
                        onClick = onOpenToolAccess,
                        trailing = {
                            fullAccess?.let { full ->
                                Text(
                                    text = stringResource(orbitAccessLabel(full)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                    SettingsRowDivider(inset = false)
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
                    SettingsRowDivider(inset = false)
                    val separateLoginsSupported = remember {
                        com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserProfiles.isSupported()
                    }
                    // Switching logins either way asks first: it changes which accounts Orbit acts as.
                    var pendingShare by remember { mutableStateOf<Boolean?>(null) }
                    SettingsSwitchRow(
                        title = stringResource(R.string.orbit_browser_share_login),
                        subtitle =
                            when {
                                !separateLoginsSupported -> stringResource(R.string.orbit_browser_share_login_unsupported)
                                orbit.browserSharesMainLogin -> stringResource(R.string.orbit_browser_share_login_on)
                                else -> stringResource(R.string.orbit_browser_share_login_off)
                            },
                        checked = orbit.browserSharesMainLogin || !separateLoginsSupported,
                        enabled = separateLoginsSupported,
                        searchable = false,
                        onCheckedChange = { shared ->
                            pendingShare = shared
                        },
                    )
                    pendingShare?.let { shared ->
                        TaskConfirmSheet(
                            icon = if (shared) "link" else "link-2-off",
                            title = stringResource(
                                if (shared) R.string.orbit_browser_share_confirm_on_title
                                else R.string.orbit_browser_share_confirm_off_title
                            ),
                            message = stringResource(
                                if (shared) R.string.orbit_browser_share_confirm_on
                                else R.string.orbit_browser_share_confirm_off,
                                orbit.name,
                            ),
                            confirmText = stringResource(R.string.orbit_browser_share_confirm_ok),
                            dismissText = stringResource(R.string.cancel),
                            destructive = false,
                            onDismiss = { pendingShare = null },
                            onConfirm = {
                                pendingShare = null
                                scope.launch { preferences.setBrowserSharesMainLogin(orbit.id, shared) }
                            },
                        )
                    }
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
                    if (settings.developerMode) {
                        SettingsRowDivider(inset = false)
                        SettingsNavRow(
                            title = stringResource(R.string.orbit_tools_row),
                            subtitle = stringResource(R.string.orbit_tools_row_desc),
                            icon = lucide("shield-check"),
                            searchable = false,
                            onClick = onOpenToolPermissions,
                        )
                    }
                }
            }

            SettingsCard(backgroundColor = cardColor) {
                SettingsNavRow(
                    title = stringResource(R.string.orbit_pause, orbitName(orbit)),
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
}

/** The page while every Orbit is paused: what Orbit is, the user's Orbits, and how to start one. */
@Composable
private fun OrbitHome(
    settings: OrbitSettings,
    hasBackgroundImage: Boolean,
    onManageRoleCards: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
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

        if (settings.orbits.isEmpty()) {
            SettingsCard(backgroundColor = cardColor) {
                SettingsNavRow(
                    title = stringResource(R.string.orbit_setup_entry),
                    subtitle = stringResource(R.string.orbit_setup_entry_note),
                    icon = lucide("key-round"),
                    searchable = false,
                    onClick = onOpenSetup,
                )
            }
        }

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
                            // New Orbits run under the default card; only older ones made from a role show it.
                            roleName = card?.name?.takeIf { orbit.sourceRoleCardId != CharacterCardManager.DEFAULT_CHARACTER_CARD_ID },
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

        // There is one Orbit: a new one can be made only when there is none.
        if (settings.orbits.isEmpty()) Button(
            enabled = !starting && removingId == null,
            // Starts with the main app's models and greets the user, like the setup page does.
            onClick = { start { com.ai.assistance.operit.features.orbit.OrbitSetup.startWithExisting(context) } },
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

/** The chat each Orbit last had ready, so opening it again need not wait on a spinner. */
private object OrbitReadyChats {
    private val chats = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun get(orbitId: String): String? = chats[orbitId]
    fun put(orbitId: String, chatId: String?) {
        if (chatId == null) chats.remove(orbitId) else chats[orbitId] = chatId
    }
}
