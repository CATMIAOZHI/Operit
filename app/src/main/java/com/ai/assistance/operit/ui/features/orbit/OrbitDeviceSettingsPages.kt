package com.ai.assistance.operit.ui.features.orbit

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.settings.components.SettingsDialogStyle
import com.ai.assistance.operit.ui.features.settings.components.SettingsDialogs
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import com.ai.assistance.operit.ui.features.settings.screens.ChatBackupSettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.GlobalDisplaySettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.LayoutAdjustmentSettingsScreen
import com.ai.assistance.operit.ui.features.settings.sections.ThemeSettingsFontSection
import com.ai.assistance.operit.util.FileUtils
import kotlinx.coroutines.launch

/** A shared settings page under Orbit's own header; the page keeps its own scrolling. */
@Composable
private fun OrbitEmbeddedSettingsPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    OrbitPageBack(onBack = onBack)
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding()
    ) {
        OrbitPageHeader(title, onBack, Modifier.padding(horizontal = 20.dp).padding(top = 8.dp))
        // The app's own pages draw their cards and dialogs in Orbit's style here.
        Box(Modifier.fillMaxWidth().weight(1f)) { SettingsDialogs(SettingsDialogStyle.ORBIT) { content() } }
    }
}

/**
 * Font, size and text spacing. These are the app's text settings, so Orbit's chat follows them;
 * Orbit keeps its own chat margins, so the margin settings are left out.
 */
@Composable
internal fun OrbitTextSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val preferences = remember { UserPreferencesManager.getInstance(context) }
    val activePrompts = remember { ActivePromptManager.getInstance(context) }

    // Same as the theme page: a theme bound to the active character card keeps the change.
    val save: (suspend () -> Unit) -> Unit = { action ->
        scope.launch {
            action()
            when (val prompt = activePrompts.getActivePrompt()) {
                is ActivePrompt.CharacterGroup -> preferences.saveCurrentThemeToCharacterGroup(prompt.id)
                is ActivePrompt.CharacterCard -> preferences.saveCurrentThemeToCharacterCard(prompt.id)
                else -> Unit
            }
        }
    }

    val useCustomFont by preferences.useCustomFont.collectAsState(initial = false)
    val fontType by preferences.fontType.collectAsState(initial = UserPreferencesManager.FONT_TYPE_SYSTEM)
    val systemFontName by preferences.systemFontName.collectAsState(initial = UserPreferencesManager.SYSTEM_FONT_DEFAULT)
    val customFontPath by preferences.customFontPath.collectAsState(initial = null)
    val fontScale by preferences.fontScale.collectAsState(initial = 1.0f)
    var useCustomFontInput by remember { mutableStateOf(useCustomFont) }
    var fontTypeInput by remember { mutableStateOf(fontType) }
    var systemFontNameInput by remember { mutableStateOf(systemFontName) }
    var customFontPathInput by remember { mutableStateOf(customFontPath) }
    var fontScaleInput by remember { mutableStateOf(fontScale) }
    LaunchedEffect(useCustomFont, fontType, systemFontName, customFontPath, fontScale) {
        useCustomFontInput = useCustomFont
        fontTypeInput = fontType
        systemFontNameInput = systemFontName
        customFontPathInput = customFontPath
        fontScaleInput = fontScale
    }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val extension = FileUtils.getFileExtension(context, uri)?.lowercase()
            if (extension != "ttf" && extension != "otf" && extension != "ttc") {
                Toast.makeText(context, resources.getString(R.string.unsupported_font_format), Toast.LENGTH_LONG).show()
                return@launch
            }
            val saved = FileUtils.copyFileToInternalStorage(context, uri, "custom_font")
            if (saved == null) {
                Toast.makeText(context, resources.getString(R.string.font_file_save_failed), Toast.LENGTH_LONG).show()
                return@launch
            }
            save {
                preferences.saveThemeSettings(customFontPath = saved.toString(), fontType = UserPreferencesManager.FONT_TYPE_FILE)
            }
            Toast.makeText(context, resources.getString(R.string.font_file_saved, extension), Toast.LENGTH_SHORT).show()
        }
    }

    OrbitEmbeddedSettingsPage(stringResource(R.string.orbit_text_settings_title), onBack) {
        LayoutAdjustmentSettingsScreen(onNavigateBack = onBack, textOnly = true) {
            ThemeSettingsFontSection(
                cardColors = CardDefaults.cardColors(containerColor = settingsCardColor(false)),
                context = context,
                preferencesManager = preferences,
                saveThemeSettingsWithCharacterCard = save,
                useCustomFontInput = useCustomFontInput,
                onUseCustomFontInputChange = { useCustomFontInput = it },
                fontTypeInput = fontTypeInput,
                onFontTypeInputChange = { fontTypeInput = it },
                systemFontNameInput = systemFontNameInput,
                onSystemFontNameInputChange = { systemFontNameInput = it },
                customFontPathInput = customFontPathInput,
                onCustomFontPathInputChange = { customFontPathInput = it },
                fontScaleInput = fontScaleInput,
                onFontScaleInputChange = { fontScaleInput = it },
                onPickFont = { fontPicker.launch("*/*") },
            )
        }
    }
}

/** Screenshots, the virtual screen, Root execution and keep-alive: what Orbit uses to work the phone. */
@Composable
internal fun OrbitAutomationSettingsPage(onBack: () -> Unit) {
    OrbitEmbeddedSettingsPage(stringResource(R.string.orbit_automation_settings_title), onBack) {
        GlobalDisplaySettingsScreen(
            onBackPressed = onBack,
            automationOnly = true,
            leadingContent = { OrbitScreenAndWebCard() },
        )
    }
}

/**
 * Keeping the screen on while Orbit replies, and how long Orbit's visit_web waits for a page.
 * The wait is Orbit's own: its pages are never handed over to the user, so a short wait is enough.
 */
@Composable
private fun OrbitScreenAndWebCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiPreferences = remember { com.ai.assistance.operit.data.preferences.ApiPreferences.getInstance(context) }
    val orbitPreferences = remember { com.ai.assistance.operit.data.preferences.OrbitPreferences.getInstance(context) }
    val keepScreenOn by apiPreferences.keepScreenOnFlow.collectAsState(initial = true)
    val settings by orbitPreferences.settingsFlow.collectAsState(initial = orbitPreferences.loadedSnapshot())
    val saved = settings?.webWaitSeconds ?: com.ai.assistance.operit.data.preferences.OrbitSettings.DEFAULT_WEB_WAIT_SECONDS
    // The slider moves freely and is saved when let go.
    var wait by remember(saved) { androidx.compose.runtime.mutableFloatStateOf(saved.toFloat()) }
    com.ai.assistance.operit.ui.features.settings.components.SettingsCard(
        backgroundColor = settingsCardColor(false),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        com.ai.assistance.operit.ui.features.settings.components.SettingsSwitchRow(
            title = stringResource(R.string.keep_screen_on),
            subtitle = stringResource(R.string.keep_screen_on_description),
            checked = keepScreenOn,
            onCheckedChange = { on -> scope.launch { apiPreferences.saveKeepScreenOn(on) } },
        )
        com.ai.assistance.operit.ui.features.settings.components.SettingsRowDivider(inset = false)
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.orbit_web_wait_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.orbit_web_wait_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                val max = com.ai.assistance.operit.data.preferences.OrbitSettings.MAX_WEB_WAIT_SECONDS
                androidx.compose.material3.Slider(
                    value = wait,
                    onValueChange = { wait = kotlin.math.round(it) },
                    onValueChangeFinished = { scope.launch { orbitPreferences.setWebWaitSeconds(wait.toInt()) } },
                    valueRange = 0f..max.toFloat(),
                    steps = max - 1,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
                Text(
                    stringResource(R.string.visit_web_wait_time_value, wait.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** Backup and restore of chats, memory, character cards, model configs and the database. */
@Composable
internal fun OrbitBackupSettingsPage(onBack: () -> Unit) {
    OrbitEmbeddedSettingsPage(stringResource(R.string.orbit_backup_title), onBack) {
        ChatBackupSettingsScreen(dialogStyle = SettingsDialogStyle.ORBIT)
    }
}

/** Version, updates, the update log, the project page and licenses; its dialogs follow Orbit's style. */
@Composable
internal fun OrbitAboutPage(onBack: () -> Unit, onOpenUpdateHistory: () -> Unit) {
    OrbitEmbeddedSettingsPage(stringResource(R.string.nav_about), onBack) {
        com.ai.assistance.operit.ui.features.about.screens.AboutScreen(
            navigateToUpdateHistory = onOpenUpdateHistory,
            dialogStyle = SettingsDialogStyle.ORBIT,
        )
    }
}

/** The release notes opened from the about page; back returns to it. */
@Composable
internal fun OrbitUpdateHistoryPage(onBack: () -> Unit) {
    OrbitEmbeddedSettingsPage(stringResource(R.string.update_history), onBack) {
        com.ai.assistance.operit.ui.features.update.screens.UpdateScreen(onNavigateToThemeSettings = {})
    }
}

/** The app's language. Changing it redraws the app in place, so back still returns to Orbit's settings. */
@Composable
internal fun OrbitLanguagePage(onBack: () -> Unit) {
    val context = LocalContext.current
    OrbitEmbeddedSettingsPage(stringResource(R.string.language_settings), onBack) {
        com.ai.assistance.operit.ui.features.settings.screens.LanguageSettingsScreen(
            onBackPressed = onBack,
            restartInPlace = { context.orbitActivity()?.recreate() },
        )
    }
}

private tailrec fun android.content.Context.orbitActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.orbitActivity()
    else -> null
}

/** Space used by media, components and data, with the preview cache to clear. */
@Composable
internal fun OrbitStoragePage(onBack: () -> Unit, onOpenComponents: () -> Unit, onOpenBackup: () -> Unit) {
    OrbitEmbeddedSettingsPage(stringResource(R.string.storage_title), onBack) {
        com.ai.assistance.operit.ui.features.settings.screens.StorageManagementScreen(
            onManageComponents = onOpenComponents,
            // The app's normal chats are managed in the app; Orbit's chats are in its chat list.
            onManageChats = null,
            onBackup = onOpenBackup,
        )
    }
}

/** Downloadable parts (offline speech models, the terminal and more): download or delete them. */
@Composable
internal fun OrbitComponentsPage(onBack: () -> Unit) {
    OrbitEmbeddedSettingsPage(stringResource(R.string.components_title), onBack) {
        com.ai.assistance.operit.ui.features.settings.screens.ComponentManagementScreen()
    }
}

/** Adding, editing and deploying the app's MCP servers; Orbit's own switches stay on its package page. */
@Composable
internal fun OrbitMcpConfigPage(onBack: () -> Unit, onOpenStartupServices: () -> Unit) {
    OrbitEmbeddedSettingsPage(stringResource(R.string.orbit_mcp_config_title), onBack) {
        com.ai.assistance.operit.ui.features.packages.screens.MCPConfigScreen(
            onOpenTerminalStartupServices = onOpenStartupServices,
        )
    }
}

/** Services the terminal starts by itself (what local MCP servers run on). */
@Composable
internal fun OrbitTerminalStartupPage(onBack: () -> Unit) {
    OrbitEmbeddedSettingsPage(stringResource(R.string.terminal_startup_title), onBack) {
        // Orbit uses the terminal through its tools, so there is no terminal screen to open here.
        com.ai.assistance.operit.ui.features.packages.screens.TerminalStartupServicesScreen(onOpenTerminal = null)
    }
}

/** A plugin's own settings page, drawn by the plugin. */
@Composable
internal fun OrbitPluginSettingsPage(module: com.ai.assistance.operit.core.tools.packTool.PackageManager.ToolPkgToolboxUiModule, onBack: () -> Unit) {
    OrbitEmbeddedSettingsPage(module.title, onBack) {
        // Orbit's header shows the title; the plugin never writes into the app's top bar from here.
        androidx.compose.runtime.CompositionLocalProvider(
            com.ai.assistance.operit.ui.main.LocalTopBarTitleContent provides {},
        ) {
            com.ai.assistance.operit.ui.common.composedsl.ToolPkgComposeDslToolScreen(
                routeInstanceId = "orbit:${module.containerPackageName}:${module.uiModuleId}",
                containerPackageName = module.containerPackageName,
                uiModuleId = module.uiModuleId,
                fallbackTitle = module.title,
            )
        }
    }
}

/** The app's online user guide. */
@Composable
internal fun OrbitHelpPage(onBack: () -> Unit) {
    OrbitEmbeddedSettingsPage(stringResource(R.string.nav_help), onBack) {
        com.ai.assistance.operit.ui.features.help.screens.HelpScreen(onBackPressed = onBack)
    }
}

/** The user agreement and privacy policy, to read again. */
@Composable
internal fun OrbitAgreementPage(onBack: () -> Unit) {
    OrbitEmbeddedSettingsPage(stringResource(R.string.agreement_title), onBack) {
        com.ai.assistance.operit.ui.features.agreement.screens.AgreementDocument(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * A view model store that lives as long as the page: the app's pages expect their route to own
 * their view models, and here the Orbit page does.
 */
@Composable
private fun OrbitPageViewModels(key: String, content: @Composable () -> Unit) {
    // Kept by the screen's own owner, so a rotation or language change keeps the page's filters;
    // the store is cleared only when the page really closes.
    val holder: OrbitPageStores = androidx.lifecycle.viewmodel.compose.viewModel()
    val owner = remember(holder, key) {
        val store = holder.stores.getOrPut(key) { androidx.lifecycle.ViewModelStore() }
        object : androidx.lifecycle.ViewModelStoreOwner {
            override val viewModelStore = store
        }
    }
    val activity = LocalContext.current.orbitActivity()
    androidx.compose.runtime.DisposableEffect(owner) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                holder.stores.remove(key)
                owner.viewModelStore.clear()
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides owner,
        content = content,
    )
}

/** The stores of Orbit's embedded app pages, outliving configuration changes. */
internal class OrbitPageStores : androidx.lifecycle.ViewModel() {
    val stores = mutableMapOf<String, androidx.lifecycle.ViewModelStore>()

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/** The whole app's usage and costs (normal chats included), as on the app's usage page. */
@Composable
internal fun OrbitAppCostsPage(onBack: () -> Unit, onOpenPricing: () -> Unit, onOpenGroups: () -> Unit) {
    OrbitEmbeddedSettingsPage(stringResource(R.string.orbit_app_costs_title), onBack) {
        OrbitPageViewModels("app_costs") {
            com.ai.assistance.operit.ui.features.tokenstats.TokenUsageStatisticsScreen(
                onBackPressed = onBack,
                onOpenGroupManagement = onOpenGroups,
                onOpenPricingManagement = onOpenPricing,
            )
        }
    }
}

/** Model groups and prices for the cost figures; [pricing] opens on the prices. */
@Composable
internal fun OrbitAppPricingPage(pricing: Boolean, onBack: () -> Unit) {
    OrbitEmbeddedSettingsPage(stringResource(R.string.token_stats_management_title), onBack) {
        OrbitPageViewModels("app_pricing") {
            com.ai.assistance.operit.ui.features.tokenstats.TokenStatsManagementScreen(initialPricingTab = pricing)
        }
    }
}
