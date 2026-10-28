package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.ProviderAccountManager
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences
import com.ai.assistance.operit.features.orbit.OrbitChatCards
import com.ai.assistance.operit.features.orbit.OrbitDataExport
import com.ai.assistance.operit.features.orbit.OrbitVaultNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Orbit pages a chat card can open over the chat. */
internal enum class OrbitCardPage {
    SPEECH_SETTINGS, MODEL_CONFIG, DEVICE_ACCESS, WEIXIN, ROLE_CARDS, TOOL_ACCESS, TOOL_PERMISSIONS, ORBIT_MODELS, NOTIFICATIONS,
}

/** Opens an Orbit page from a card; null where the chat cannot open pages (then the button is off). */
internal val LocalOrbitOpenPage = compositionLocalOf<((OrbitCardPage) -> Unit)?> { null }

/** A card's title row: its tile, a title and one line under it. */
@Composable
private fun CardHeading(icon: String, title: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CardTile(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The round button at the bottom of a card, the same as the login card's. */
@Composable
private fun CardButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = colors.surface,
        modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50))
            .orbitCardGestures(onClick = onClick, enabled = enabled)
            .semantics { if (!enabled) disabled() },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleMedium,
                color = if (enabled) Color.Unspecified else colors.onSurface.copy(alpha = 0.38f))
        }
    }
}

@Composable
private fun CardColumn(content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).orbitCardGestures().padding(vertical = 4.dp)) {
        content()
    }
}

/** Signs in to a provider account (Claude, Grok, …); once signed in, Orbit adds its model from the chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitAccountLoginCard(card: JSONObject) {
    val context = LocalContext.current
    val provider = OrbitChatCards.accountProviders[card.optString("provider")] ?: return
    val orbitId = card.optString("orbit_id")
    val chatId = card.optString("chat_id")
    if (orbitId.isBlank()) return
    val label = OrbitChatCards.accountLabel(provider)
    // The account store is encrypted; opening it is kept off the main thread.
    val manager by produceState<ProviderAccountManager?>(null, provider) {
        value = withContext(Dispatchers.IO) { ProviderAccountManager.get(context.applicationContext, provider) }
    }
    val signedIn = manager?.account?.collectAsState()?.value != null
    // Asked to sign in again: an old sign-in is saved but no longer works. Done once a new one is made here.
    var relogged by rememberSaveable { mutableStateOf(false) }
    val relogin = card.optBoolean("relogin") && !relogged
    val orbit = remember { OrbitPreferences.getInstance(context).settingsFlow }
        .collectAsState(initial = null).value?.orbits?.firstOrNull { it.id == orbitId }
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val who = orbit?.let { orbitName(it) } ?: stringResource(R.string.nav_orbit)
    CardColumn {
        CardHeading("log-in", stringResource(R.string.orbit_account_card_title, label),
            when {
                relogin -> stringResource(R.string.orbit_login_card_relogin_sub)
                signedIn -> stringResource(R.string.orbit_account_card_done, who)
                else -> stringResource(R.string.orbit_account_card_sub)
            })
        if (relogin) {
            Spacer(Modifier.height(14.dp))
            CardButton(stringResource(R.string.orbit_login_card_relogin), enabled = manager != null && orbit != null) { open = true }
        } else if (!signedIn) {
            Spacer(Modifier.height(14.dp))
            CardButton(stringResource(R.string.orbit_chatgpt_card_login), enabled = manager != null && orbit != null) { open = true }
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
    val current = manager
    if (open && current != null) {
        OrbitModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            ) {
                Text(stringResource(R.string.orbit_account_card_title, label), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                com.ai.assistance.operit.ui.features.settings.components.SettingsDialogs(
                    com.ai.assistance.operit.ui.features.settings.components.SettingsDialogStyle.ORBIT,
                ) {
                    com.ai.assistance.operit.ui.features.codex.ProviderAccountSettings(provider) {
                        // Called after a sign-in or a sign-out; only a sign-in made here tells Orbit.
                        if (current.account.value != null) {
                            open = false
                            relogged = true
                            orbit?.let { OrbitVaultNotice.sendAccountLogin(context, it, chatId, label, provider.type.name) }
                        }
                        scope.launch {
                            runCatching { com.ai.assistance.operit.api.chat.EnhancedAIService.refreshAllServices(context.applicationContext) }
                        }
                    }
                }
            }
        }
    }
}

/** Links WeChat right from the chat: the QR scan opens over the chat, and Orbit hears once it is done. */
@Composable
internal fun OrbitWeixinLoginCard(card: JSONObject) {
    val context = LocalContext.current
    val orbitId = card.optString("orbit_id")
    val chatId = card.optString("chat_id")
    if (orbitId.isBlank()) return
    val orbit = remember { OrbitPreferences.getInstance(context).settingsFlow }
        .collectAsState(initial = null).value?.orbits?.firstOrNull { it.id == orbitId }
    androidx.compose.runtime.LaunchedEffect(orbitId) {
        com.ai.assistance.operit.features.orbit.OrbitWeixin.refreshStatus(context.applicationContext, orbitId)
    }
    val status = com.ai.assistance.operit.features.orbit.OrbitWeixin.status.collectAsState().value
        ?.takeIf { it.orbitId == orbitId }
    val linked = status?.connected == true && !status.loginExpired
    var open by remember { mutableStateOf(false) }
    val who = orbit?.let { orbitName(it) } ?: stringResource(R.string.nav_orbit)
    CardColumn {
        CardHeading("message-circle", stringResource(R.string.orbit_weixin_card_title),
            when {
                linked -> stringResource(R.string.orbit_weixin_card_done, who)
                status?.loginExpired == true -> stringResource(R.string.orbit_weixin_expired)
                else -> stringResource(R.string.orbit_weixin_card_sub, who)
            })
        if (!linked) {
            Spacer(Modifier.height(14.dp))
            CardButton(
                stringResource(if (status?.loginExpired == true) R.string.orbit_weixin_reconnect else R.string.orbit_weixin_connect),
                enabled = orbit != null && status != null,
            ) { open = true }
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
    val current = orbit
    if (open && current != null) {
        WeixinConnectSheet(current, onDismiss = { open = false }, onConnected = {
            OrbitVaultNotice.sendWeixinConnected(context, current, chatId)
        })
    }
}

/** Shows which voice services are in use and opens the voice settings page inside Orbit. */
@Composable
internal fun OrbitSpeechSettingsCard() {
    val context = LocalContext.current
    val profiles = remember { SpeechServiceProfilesPreferences(context.applicationContext) }
    val tts by profiles.currentTtsProfileOrNullFlow.collectAsState(initial = null)
    val stt by profiles.currentSttProfileOrNullFlow.collectAsState(initial = null)
    val openPage = LocalOrbitOpenPage.current
    CardColumn {
        CardHeading("volume-2", stringResource(R.string.orbit_speech_card_title),
            stringResource(R.string.orbit_speech_card_sub, tts?.name ?: "…", stt?.name ?: "…"))
        Spacer(Modifier.height(14.dp))
        CardButton(stringResource(R.string.orbit_speech_card_open), enabled = openPage != null) {
            openPage?.invoke(OrbitCardPage.SPEECH_SETTINGS)
        }
    }
}

/** Opens one settings page from the chat: the WeChat link, the role cards, or the model settings. */
@Composable
internal fun OrbitOpenPageCard(card: JSONObject) {
    val openPage = LocalOrbitOpenPage.current
    data class Look(val icon: String, val title: Int, val sub: Int, val button: Int, val page: OrbitCardPage)
    val look = when (card.optString("page")) {
        OrbitChatCards.PAGE_WEIXIN -> Look("message-circle", R.string.orbit_page_card_weixin_title,
            R.string.orbit_page_card_weixin_sub, R.string.orbit_page_card_weixin_open, OrbitCardPage.WEIXIN)
        OrbitChatCards.PAGE_ROLE_CARDS -> Look("square-user", R.string.orbit_page_card_roles_title,
            R.string.orbit_page_card_roles_sub, R.string.orbit_page_card_roles_open, OrbitCardPage.ROLE_CARDS)
        OrbitChatCards.PAGE_MODEL_CONFIG -> Look("key-round", R.string.orbit_page_card_models_title,
            R.string.orbit_page_card_models_sub, R.string.orbit_page_card_models_open, OrbitCardPage.MODEL_CONFIG)
        OrbitChatCards.PAGE_TOOL_ACCESS -> Look("shield-check", R.string.orbit_page_card_access_title,
            R.string.orbit_page_card_access_sub, R.string.orbit_page_card_access_open, OrbitCardPage.TOOL_ACCESS)
        OrbitChatCards.PAGE_TOOL_PERMISSIONS -> Look("sliders-horizontal", R.string.orbit_page_card_tools_title,
            R.string.orbit_page_card_tools_sub, R.string.orbit_page_card_tools_open, OrbitCardPage.TOOL_PERMISSIONS)
        OrbitChatCards.PAGE_ORBIT_MODELS -> Look("brain", R.string.orbit_page_card_orbit_models_title,
            R.string.orbit_page_card_orbit_models_sub, R.string.orbit_page_card_orbit_models_open, OrbitCardPage.ORBIT_MODELS)
        OrbitChatCards.PAGE_NOTIFICATIONS -> Look("bell", R.string.orbit_page_card_notify_title,
            R.string.orbit_page_card_notify_sub, R.string.orbit_page_card_notify_open, OrbitCardPage.NOTIFICATIONS)
        else -> return
    }
    CardColumn {
        CardHeading(look.icon, stringResource(look.title), stringResource(look.sub))
        Spacer(Modifier.height(14.dp))
        CardButton(stringResource(look.button), enabled = openPage != null) { openPage?.invoke(look.page) }
    }
}

/** Packs this Orbit's data into a zip after the user confirms, and opens the share sheet. */
@Composable
internal fun OrbitDataExportCard(card: JSONObject) {
    val context = LocalContext.current
    val orbitId = card.optString("orbit_id")
    val orbit = remember { OrbitPreferences.getInstance(context).settingsFlow }
        .collectAsState(initial = null).value?.orbits?.firstOrNull { it.id == orbitId && !it.deleting }
    val exporting by OrbitDataExport.running.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    val name = orbit?.let { orbitName(it) } ?: stringResource(R.string.nav_orbit)
    CardColumn {
        CardHeading(if (exporting) "loader" else "archive", stringResource(R.string.orbit_export_card_title, name),
            stringResource(R.string.orbit_export_card_sub))
        Spacer(Modifier.height(14.dp))
        CardButton(stringResource(if (exporting) R.string.orbit_export_running else R.string.orbit_export_card_button),
            enabled = orbit != null && !exporting) { confirming = true }
    }
    if (confirming && orbit != null) {
        OrbitDialog(
            onDismissRequest = { confirming = false },
            title = stringResource(R.string.orbit_export_confirm_title, name),
            text = { Text(stringResource(R.string.orbit_export_confirm_message)) },
            confirmButton = {
                OrbitDialogAction(onClick = {
                    confirming = false
                    startOrbitExport(context, orbit)
                }) { Text(stringResource(R.string.orbit_export_confirm)) }
            },
            dismissButton = {
                OrbitDialogAction(onClick = { confirming = false }, secondary = true) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/** Starts the one app-wide export; it keeps going when the card or page leaves the screen. */
internal fun startOrbitExport(context: android.content.Context, orbit: com.ai.assistance.operit.data.preferences.OrbitProfile) {
    OrbitDataExport.start(context, orbit) { zip ->
        if (zip != null) shareOrbitFile(context) { zip }
        else Toast.makeText(context.applicationContext, R.string.orbit_export_failed, Toast.LENGTH_SHORT).show()
    }
}

/**
 * The app's voice settings inside Orbit, opened from settings or the voice settings card: which
 * voice Orbit's voice messages use, then the read-aloud and voice input settings it shares with the app.
 */
@Composable
internal fun OrbitSpeechSettingsPage(orbitId: String, onBack: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    var readAloud by remember(orbitId) {
        mutableStateOf(com.ai.assistance.operit.features.orbit.OrbitVoice.usesReadAloud(context, orbitId))
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().navigationBarsPadding()) {
        OrbitPageHeader(stringResource(R.string.orbit_speech_card_title), onBack,
            Modifier.padding(horizontal = 20.dp).padding(top = 8.dp))
        com.ai.assistance.operit.ui.features.settings.components.SettingsCard(
            backgroundColor = com.ai.assistance.operit.ui.features.settings.components.settingsCardColor(false),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            com.ai.assistance.operit.ui.features.settings.components.SettingsSwitchRow(
                title = stringResource(R.string.orbit_voice_read_aloud_title),
                subtitle = stringResource(if (readAloud) R.string.orbit_voice_read_aloud_on else R.string.orbit_voice_read_aloud_off),
                checked = readAloud,
                onCheckedChange = {
                    readAloud = it
                    com.ai.assistance.operit.features.orbit.OrbitVoice.setUsesReadAloud(context, orbitId, it)
                },
                searchable = false,
            )
        }
        Text(
            stringResource(R.string.orbit_voice_shared_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 28.dp).padding(bottom = 4.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            com.ai.assistance.operit.ui.features.settings.screens.SpeechServicesSettingsScreen(
                onBackPressed = onBack,
                dialogStyle = com.ai.assistance.operit.ui.features.settings.components.SettingsDialogStyle.ORBIT,
            )
        }
    }
}

/** The app's own access page (ADB debugging through Shizuku, device admin, Root), opened inside Orbit. */
@Composable
internal fun OrbitDeviceAccessPage(name: String, onBack: () -> Unit, onDraft: (String) -> Unit) {
    OrbitPageBack(onBack = onBack)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { com.ai.assistance.operit.features.orbit.OrbitPermissionCards.deviceAccessPageClosed() }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().navigationBarsPadding()) {
        OrbitPageHeader(stringResource(R.string.orbit_device_access_title), onBack,
            Modifier.padding(horizontal = 20.dp).padding(top = 8.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val terminalDraft = stringResource(R.string.orbit_device_access_terminal_draft)
            val terminalLabel = stringResource(R.string.orbit_device_access_terminal_button, name)
            // The page's terminal setup button asks Orbit, which installs the terminal itself.
            com.ai.assistance.operit.ui.features.demo.screens.ShizukuDemoScreen(
                navigateTo = { screen ->
                    if (screen == com.ai.assistance.operit.ui.main.screens.Screen.TerminalSetup) onDraft(terminalDraft)
                },
                terminalSetupLabel = terminalLabel,
            )
        }
    }
}
