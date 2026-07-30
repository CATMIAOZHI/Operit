package com.ai.assistance.operit.ui.features.settings.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.agent.AgentProfile
import com.ai.assistance.operit.core.agent.AgentProfileRepository
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import kotlinx.coroutines.launch

private data class AgentModelOption(
    val configId: String,
    val modelIndex: Int,
    val label: String,
)

@Composable
fun AgentProfileSettingsScreen() {
    val context = LocalContext.current
    val repository =
        remember(context) {
            AgentProfileRepository.instance.apply { initialize(context.applicationContext) }
        }
    val modelConfigManager = remember(context) { ModelConfigManager(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val profiles by repository.profiles.collectAsState()
    var modelConfigs by remember { mutableStateOf<List<ModelConfigSummary>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(true) }
    var modelLoadFailed by remember { mutableStateOf(false) }
    var editingProfileId by remember { mutableStateOf<String?>(null) }

    suspend fun loadModelConfigs() {
        isLoadingModels = true
        modelLoadFailed = false
        runCatching {
                modelConfigManager.initializeIfNeeded()
                modelConfigManager.getAllConfigSummaries()
            }
            .onSuccess { modelConfigs = it }
            .onFailure { modelLoadFailed = true }
        isLoadingModels = false
    }

    LaunchedEffect(Unit) { loadModelConfigs() }

    val editingProfile = editingProfileId?.let(repository::getById)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 12.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.agent_profile_info_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.agent_profile_info_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        items(profiles, key = AgentProfile::id) { profile ->
            AgentProfileCard(
                profile = profile,
                modelLabel = modelBindingLabel(profile, modelConfigs),
                onEdit = { editingProfileId = profile.id },
            )
        }
    }

    if (editingProfile != null) {
        AgentProfileEditorDialog(
            profile = editingProfile,
            modelConfigs = modelConfigs,
            isLoadingModels = isLoadingModels,
            modelLoadFailed = modelLoadFailed,
            onReloadModels = { scope.launch { loadModelConfigs() } },
            onDismiss = { editingProfileId = null },
            onSave = { prompt, configId, modelIndex ->
                runCatching {
                        repository.updateSettings(
                            id = editingProfile.id,
                            systemPrompt = prompt,
                            modelConfigId = configId,
                            modelIndex = modelIndex,
                        )
                    }
                    .onSuccess {
                        Toast.makeText(
                                context,
                                R.string.agent_profile_saved,
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                        editingProfileId = null
                    }
                    .onFailure {
                        Toast.makeText(
                                context,
                                R.string.agent_profile_save_failed,
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
            },
            onReset = {
                repository.resetSettings(editingProfile.id)
                Toast.makeText(context, R.string.agent_profile_reset_done, Toast.LENGTH_SHORT).show()
                editingProfileId = null
            },
        )
    }
}

@Composable
private fun AgentProfileCard(
    profile: AgentProfile,
    modelLabel: String,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = profile.id,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = profile.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.agent_profile_model_value, modelLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text =
                        stringResource(
                            R.string.agent_profile_prompt_preview,
                            profile.systemPrompt.replace('\n', ' '),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.agent_profile_edit),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentProfileEditorDialog(
    profile: AgentProfile,
    modelConfigs: List<ModelConfigSummary>,
    isLoadingModels: Boolean,
    modelLoadFailed: Boolean,
    onReloadModels: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String?, Int?) -> Unit,
    onReset: () -> Unit,
) {
    var prompt by remember(profile.id) { mutableStateOf(profile.systemPrompt) }
    var selectedConfigId by remember(profile.id) { mutableStateOf(profile.modelConfigId) }
    var selectedModelIndex by remember(profile.id) { mutableStateOf(profile.modelIndex) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val options =
        remember(modelConfigs) {
            modelConfigs.flatMap { config ->
                getModelList(config.modelName).mapIndexed { index, model ->
                    AgentModelOption(
                        configId = config.id,
                        modelIndex = index,
                        label = "${config.name} · $model",
                    )
                }
            }
        }
    val selectedOption =
        options.firstOrNull {
            it.configId == selectedConfigId && it.modelIndex == (selectedModelIndex ?: 0)
        }
    val selectedLabel =
        when {
            selectedConfigId == null -> stringResource(R.string.agent_profile_model_inherit)
            selectedOption != null -> selectedOption.label
            else ->
                stringResource(
                    R.string.agent_profile_model_unavailable,
                    selectedConfigId.orEmpty(),
                    selectedModelIndex ?: 0,
                )
        }
    val promptIsValid = prompt.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.agent_profile_edit_title, profile.name),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.agent_profile_stable_id, profile.id),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.agent_profile_system_prompt)) },
                    supportingText = {
                        Text(
                            if (promptIsValid) {
                                stringResource(R.string.agent_profile_system_prompt_hint)
                            } else {
                                stringResource(R.string.agent_profile_prompt_required)
                            }
                        )
                    },
                    isError = !promptIsValid,
                    minLines = 7,
                    maxLines = 14,
                )

                ExposedDropdownMenuBox(
                    expanded = modelMenuExpanded,
                    onExpandedChange = { modelMenuExpanded = !modelMenuExpanded },
                ) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        readOnly = true,
                        label = { Text(stringResource(R.string.agent_profile_model_binding)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded)
                        },
                    )
                    ExposedDropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.agent_profile_model_inherit)) },
                            onClick = {
                                selectedConfigId = null
                                selectedModelIndex = null
                                modelMenuExpanded = false
                            },
                        )
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    selectedConfigId = option.configId
                                    selectedModelIndex = option.modelIndex
                                    modelMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                when {
                    isLoadingModels ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(R.string.agent_profile_loading_models),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    modelLoadFailed ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.agent_profile_model_load_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onReloadModels) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    else ->
                        Text(
                            text = stringResource(R.string.agent_profile_model_binding_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(prompt, selectedConfigId, selectedModelIndex) },
                enabled = promptIsValid,
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.agent_profile_reset_defaults))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun modelBindingLabel(
    profile: AgentProfile,
    modelConfigs: List<ModelConfigSummary>,
): String {
    val configId = profile.modelConfigId
        ?: return stringResource(R.string.agent_profile_model_inherit)
    val config = modelConfigs.firstOrNull { it.id == configId }
    val model = config?.let { getModelList(it.modelName).getOrNull(profile.modelIndex ?: 0) }
    return if (config != null && model != null) {
        "${config.name} · $model"
    } else {
        stringResource(
            R.string.agent_profile_model_unavailable,
            configId,
            profile.modelIndex ?: 0,
        )
    }
}
