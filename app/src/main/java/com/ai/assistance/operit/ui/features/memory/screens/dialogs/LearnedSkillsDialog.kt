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

@Composable
fun LearnedSkillsDialog(profileId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
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
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Column(Modifier.heightIn(max = controlsMaxHeight).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.memory_learned_hint),style=MaterialTheme.typography.bodySmall)
                if (!masterEnabled) Text(stringResource(R.string.memory_auto_master_off),
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showAutomation = true }) {
                    Text(stringResource(R.string.memory_automation_title))
                }
                Row {
                    Text(stringResource(R.string.memory_extract_skills),Modifier.weight(1f))
                    Switch(extract,{ prefs.setExtractSkills(it); extract=it })
                }
                Row {
                    Text(stringResource(R.string.memory_learned_revise),Modifier.weight(1f))
                    Switch(revise,{ prefs.setReviseLearnedSkills(it); revise=it })
                }
                }
                Box(Modifier.weight(1f)) {
                    SkillConfigScreen(SkillRepository.getInstance(context),snackbar,learnedOnlyProfileId=profileId)
                }
                SnackbarHost(snackbar)
            }
        }
    }
}
