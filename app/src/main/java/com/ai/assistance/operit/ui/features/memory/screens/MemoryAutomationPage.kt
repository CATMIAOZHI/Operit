package com.ai.assistance.operit.ui.features.memory.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.MemorySearchSettingsPreferences
import kotlinx.coroutines.launch

@Composable
fun MemoryAutomationPage(profileId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val api = remember { ApiPreferences.getInstance(context) }
    val prefs = remember(profileId) { MemorySearchSettingsPreferences(context, profileId) }
    val master by api.enableMemoryAutoUpdateFlow.collectAsState(initial = false)
    val graph by api.enableLegacyMemoryExtractionFlow.collectAsState(initial = false)
    var autoApprove by remember(profileId) { mutableStateOf(prefs.shouldAutoApproveChanges()) }
    var notes by remember(profileId) { mutableStateOf(prefs.shouldExtractNewMemory()) }
    var skills by remember(profileId) { mutableStateOf(prefs.shouldExtractSkills()) }
    var revise by remember(profileId) { mutableStateOf(prefs.mayReviseLearnedSkills()) }
    var learningDelay by remember(profileId) { mutableStateOf(prefs.learningDelayMinutes()) }
    var memoryInterval by remember(profileId) { mutableStateOf(prefs.memoryReviewInterval()) }
    var skillInterval by remember(profileId) { mutableStateOf(prefs.skillReviewInterval()) }
    var delayMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MemoryLibraryPage(stringResource(R.string.memory_automation_title), profileId, onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            AutomationSwitch(stringResource(R.string.memory_auto_update),
                stringResource(R.string.memory_auto_update_desc), master) {
                scope.launch { api.saveEnableMemoryAutoUpdate(it) }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            AutomationSwitch(stringResource(R.string.memory_auto_approve),
                stringResource(R.string.memory_auto_approve_desc), autoApprove) {
                prefs.setAutoApproveChanges(it); autoApprove = it
            }
            if (!master) Text(stringResource(R.string.memory_auto_master_off),
                Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodyMedium)
            AutomationSwitch(stringResource(R.string.memory_extract_old),
                stringResource(R.string.memory_graph_path_desc), graph) {
                scope.launch { api.saveEnableLegacyMemoryExtraction(it) }
            }
            AutomationSwitch(stringResource(R.string.memory_extract_new),
                stringResource(R.string.memory_notes_path_desc), notes) {
                prefs.setExtractNewMemory(it); notes = it
            }
            AutomationSwitch(stringResource(R.string.memory_extract_skills),
                stringResource(R.string.memory_skills_path_desc), skills) {
                prefs.setExtractSkills(it); skills = it
            }
            LearningIntervalSetting(stringResource(R.string.memory_review_interval), memoryInterval) {
                prefs.setMemoryReviewInterval(it); memoryInterval = it
            }
            LearningIntervalSetting(stringResource(R.string.skill_review_interval), skillInterval) {
                prefs.setSkillReviewInterval(it); skillInterval = it
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.memory_learning_delay)) },
                supportingContent = { Text(stringResource(R.string.memory_learning_delay_desc)) },
                trailingContent = {
                    Box {
                        TextButton(onClick = { delayMenu = true }) {
                            Text(stringResource(R.string.memory_learning_delay_minutes, learningDelay))
                        }
                        DropdownMenu(expanded = delayMenu, onDismissRequest = { delayMenu = false }) {
                            listOf(1, 5, 10, 30, 60).forEach { minutes ->
                                DropdownMenuItem(text = {
                                    Text(stringResource(R.string.memory_learning_delay_minutes, minutes))
                                }, onClick = {
                                    prefs.setLearningDelayMinutes(minutes)
                                    learningDelay = minutes
                                    delayMenu = false
                                })
                            }
                        }
                    }
                }
            )
            AutomationSwitch(stringResource(R.string.memory_learned_revise),
                stringResource(R.string.memory_learned_hint), revise) {
                prefs.setReviseLearnedSkills(it); revise = it
            }
        }
    }
}

@Composable
private fun LearningIntervalSetting(title: String, value: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(headlineContent = { Text(title) }, trailingContent = {
        Box {
            TextButton(onClick = { expanded = true }) { Text(value.toString()) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                listOf(5, 10, 20, 50).forEach { interval ->
                    DropdownMenuItem(text = { Text(interval.toString()) }, onClick = {
                        onChange(interval); expanded = false
                    })
                }
            }
        }
    })
}

@Composable
private fun AutomationSwitch(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(value, onChange) }
    )
}
