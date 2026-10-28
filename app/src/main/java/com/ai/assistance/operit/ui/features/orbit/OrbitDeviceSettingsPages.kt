package com.ai.assistance.operit.ui.features.orbit

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.terminal.data.MirrorSource
import com.ai.assistance.operit.terminal.data.PackageManagerType
import com.ai.assistance.operit.terminal.utils.SourceManager
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsDialogStyle
import com.ai.assistance.operit.ui.features.settings.components.SettingsNavRow
import com.ai.assistance.operit.ui.features.settings.components.SettingsRowDivider
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
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

/**
 * Font, size and text spacing. These are the app's text settings, so Orbit's chat follows them;
 * Orbit keeps its own chat margins, so the margin settings are left out.
 */
@Composable
internal fun OrbitTextSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
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
                Toast.makeText(context, context.getString(R.string.unsupported_font_format), Toast.LENGTH_LONG).show()
                return@launch
            }
            val saved = FileUtils.copyFileToInternalStorage(context, uri, "custom_font")
            if (saved == null) {
                Toast.makeText(context, context.getString(R.string.font_file_save_failed), Toast.LENGTH_LONG).show()
                return@launch
            }
            save {
                preferences.saveThemeSettings(customFontPath = saved.toString(), fontType = UserPreferencesManager.FONT_TYPE_FILE)
            }
            Toast.makeText(context, context.getString(R.string.font_file_saved, extension), Toast.LENGTH_SHORT).show()
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
        GlobalDisplaySettingsScreen(onBackPressed = onBack, automationOnly = true)
    }
}

/** Backup and restore of chats, memory, character cards, model configs and the database. */
@Composable
internal fun OrbitBackupSettingsPage(onBack: () -> Unit) {
    OrbitEmbeddedSettingsPage(stringResource(R.string.orbit_backup_title), onBack) {
        ChatBackupSettingsScreen(dialogStyle = SettingsDialogStyle.ORBIT)
    }
}

/**
 * Where the terminal downloads packages from, one mirror per package manager. The terminal reads
 * the choice when it starts, so a change applies the next time it opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitTerminalSourcesPage(onBack: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val sources = remember { SourceManager(context.applicationContext) }
    val managers = remember { PackageManagerType.entries.toList() }
    val lists = remember { managers.associateWith { sourcesOf(sources, it) } }
    fun read() = managers.associateWith { sources.getSelectedSourceId(it) }
    var selected by remember { mutableStateOf(read()) }
    var picking by remember { mutableStateOf<PackageManagerType?>(null) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OrbitPageHeader(stringResource(R.string.orbit_terminal_sources_title), onBack, Modifier.padding(start = 4.dp))
        SettingsCard(backgroundColor = settingsCardColor(false)) {
            managers.forEachIndexed { index, pm ->
                if (index > 0) SettingsRowDivider(inset = false)
                val current = lists[pm].orEmpty().find { it.id == selected[pm] }
                SettingsNavRow(
                    title = pm.displayName,
                    subtitle = current?.let { mirrorName(it) }.orEmpty(),
                    icon = orbitIcon("square-terminal"),
                    searchable = false,
                    onClick = { picking = pm },
                )
            }
        }
        Text(
            stringResource(R.string.orbit_terminal_sources_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }

    picking?.let { pm ->
        OrbitModalBottomSheet(
            onDismissRequest = { picking = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = 20.dp).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(pm.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                    Column {
                        lists[pm].orEmpty().forEachIndexed { index, source ->
                            if (index > 0) HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                            val isSelected = source.id == selected[pm]
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 60.dp)
                                    .clickable(role = Role.RadioButton) {
                                        sources.setSelectedSourceId(pm, source.id)
                                        selected = read()
                                        picking = null
                                    }
                                    .semantics { this.selected = isSelected }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(mirrorName(source), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(
                                        source.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isSelected) {
                                    Icon(orbitIcon("check"), contentDescription = null, modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sourcesOf(manager: SourceManager, pm: PackageManagerType): List<MirrorSource> =
    when (pm) {
        PackageManagerType.APT -> manager.aptSources
        PackageManagerType.PIP -> manager.pipSources
        PackageManagerType.NPM -> manager.npmSources
        PackageManagerType.RUST -> manager.rustSources
    }

/** Built-in mirrors are named by their id (stored names are Chinese); custom ones keep the user's name. */
@Composable
private fun mirrorName(source: MirrorSource): String {
    val res = when (source.id.substringBefore('_')) {
        "tuna" -> R.string.orbit_mirror_tuna
        "bfsu" -> R.string.orbit_mirror_bfsu
        "aliyun" -> R.string.orbit_mirror_aliyun
        "ustc" -> R.string.orbit_mirror_ustc
        "official" -> R.string.orbit_mirror_official
        "taobao" -> R.string.orbit_mirror_taobao
        "tencent" -> R.string.orbit_mirror_tencent
        "huawei" -> R.string.orbit_mirror_huawei
        "sjtu" -> R.string.orbit_mirror_sjtu
        else -> null
    }
    return res?.let { stringResource(it) } ?: source.name
}
