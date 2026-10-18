package com.ai.assistance.operit.ui.features.memory.screens.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.features.memory.screens.MemoryLibraryPage
import com.ai.assistance.operit.ui.features.memory.screens.MemoryAutomationPage
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.MemorySearchSettingsPreferences
import com.ai.assistance.operit.data.skill.SkillRepository
import com.ai.assistance.operit.ui.features.packages.screens.SkillConfigScreen
import com.ai.assistance.operit.ui.features.settings.components.SettingsNavRow
import com.ai.assistance.operit.ui.features.settings.components.SettingsRowDivider
import com.ai.assistance.operit.ui.features.settings.components.SettingsSwitchRow
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun LearnedSkillsDialog(profileId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val skillRepository by produceState<SkillRepository?>(null, profileId) {
        value = SkillRepository.forMemorySpace(context, profileId)
    }
    val prefs = remember(profileId) { MemorySearchSettingsPreferences(context,profileId) }
    var extract by remember { mutableStateOf(prefs.shouldExtractSkills()) }
    var revise by remember { mutableStateOf(prefs.mayReviseLearnedSkills()) }
    val snackbar = remember { SnackbarHostState() }
    val api = remember(context) { ApiPreferences.getInstance(context) }
    val masterEnabled by api.enableMemoryAutoUpdateFlow.collectAsState(initial = true)
    var showAutomation by remember { mutableStateOf(false) }
    LaunchedEffect(showAutomation) {
        if (!showAutomation) {
            extract = prefs.shouldExtractSkills()
            revise = prefs.mayReviseLearnedSkills()
        }
    }
    if (showAutomation) {
        MemoryAutomationPage(profileId) { showAutomation = false }
        return
    }
    MemoryLibraryPage(stringResource(R.string.memory_learned_skills), profileId, onDismiss) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val controlsMaxHeight = maxHeight * 0.45f
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(
                    Modifier.heightIn(max = controlsMaxHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.memory_learned_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                    if (!masterEnabled) Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.memory_auto_master_off), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                    // One gray card of switches, in the look of the settings and Orbit pages.
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            SettingsSwitchRow(stringResource(R.string.memory_extract_skills), extract,
                                { prefs.setExtractSkills(it); extract = it }, searchable = false)
                            SettingsRowDivider(inset = false)
                            SettingsSwitchRow(stringResource(R.string.memory_learned_revise), revise,
                                { prefs.setReviseLearnedSkills(it); revise = it }, searchable = false)
                            SettingsRowDivider(inset = false)
                            SettingsNavRow(stringResource(R.string.memory_automation_title), { showAutomation = true },
                                searchable = false)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f)) {
                    skillRepository?.let { SkillConfigScreen(it, snackbar, learnedOnlyProfileId = profileId) }
                }
                SnackbarHost(snackbar)
            }
        }
    }
}
