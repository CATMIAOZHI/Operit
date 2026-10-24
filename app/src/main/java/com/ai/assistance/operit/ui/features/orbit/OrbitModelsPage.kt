package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.model.multimodalCapabilitiesForModel
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.OrbitModelPreferences
import com.ai.assistance.operit.data.preferences.OrbitModelRef
import com.ai.assistance.operit.data.preferences.OrbitModelSettings
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.thinkingQualityLevelLabelRes
import com.ai.assistance.operit.ui.features.settings.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun OrbitModelsPage(orbit: OrbitProfile, onBack: () -> Unit, onOpenModelConfig: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { OrbitModelPreferences(context) }
    val configs = remember(context) { ModelConfigManager(context) }
    val summaries by configs.configSummariesFlow.collectAsState(emptyList())
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picker by rememberSaveable(orbit.id) { mutableStateOf<String?>(null) }
    var more by rememberSaveable { mutableStateOf(false) }
    val settings by produceState<OrbitModelSettings?>(null, orbit.id) {
        try {
            value = preferences.current(orbit.id)
            preferences.observe(orbit.id).collect { value = it }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
    }
    val assistant = settings?.assistant
    val supportsImages by produceState<Boolean?>(null, assistant) {
        value = null
        if (assistant != null) configs.getModelConfigFlow(assistant.configId).collect {
            val index = assistant.resolveIndex(it.modelName, it.apiProviderTypeId)
            value = index >= 0 && it.multimodalCapabilitiesForModel(index).image
        }
    }
    fun update(change: (OrbitModelSettings) -> OrbitModelSettings) {
        if (busy) return
        busy = true
        scope.launch {
            try { preferences.update(orbit.id, change); error = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OrbitPageHeader(stringResource(R.string.orbit_models_title), onBack)
        Text(stringResource(R.string.orbit_models_note, orbitName(orbit)),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsCard(backgroundColor = settingsCardColor(false)) {
            SettingsNavRow(stringResource(R.string.orbit_models_config_entry), onClick = onOpenModelConfig,
                subtitle = stringResource(R.string.orbit_models_config_entry_note), searchable = false)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val current = settings
        if (current == null) {
            if (error == null) CircularProgressIndicator()
        } else {
            SettingsCard(backgroundColor = settingsCardColor(false)) {
                SettingsSwitchRow(stringResource(R.string.orbit_models_advanced), current.advanced,
                    { value -> update { it.copy(advanced = value) } },
                    subtitle = stringResource(if (current.advanced) R.string.orbit_models_advanced_note else R.string.orbit_models_simple_note),
                    enabled = !busy, searchable = false)
            }
            SettingsCard(backgroundColor = settingsCardColor(false)) {
                ModelRow(stringResource(R.string.orbit_model_main), current.main, summaries) { if (!busy) picker = "main" }
                SettingsRowDivider(inset = false)
                ModelRow(stringResource(R.string.orbit_model_assistant), current.assistant, summaries) { if (!busy) picker = "assistant" }
                SettingsRowDivider(inset = false)
                AssistantReasoning(current.assistantThinkingLevel, enabled = !busy) { level ->
                    update { it.copy(assistantThinkingLevel = level) }
                }
            }
            Text(stringResource(R.string.orbit_model_assistant_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (assistant != null && supportsImages == false) {
                SettingsCard(backgroundColor = settingsCardColor(false)) {
                    ModelRow(stringResource(R.string.orbit_model_multimodal), current.multimodal, summaries) {
                        if (!busy) picker = "multimodal"
                    }
                }
                Text(stringResource(R.string.orbit_model_multimodal_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (current.advanced) {
                SettingsSectionTitle(stringResource(R.string.orbit_models_overrides), orbitIcon("brain"))
                val primary = listOf(FunctionType.PERMISSION_REVIEWER, FunctionType.PERMISSION_RISK_SCORER,
                    FunctionType.TITLE_GENERATION, FunctionType.MEMORY, FunctionType.IMAGE_RECOGNITION)
                SettingsCard(backgroundColor = settingsCardColor(false)) {
                    primary.forEachIndexed { index, function ->
                        if (index > 0) SettingsRowDivider(inset = false)
                        ModelRow(modelFunctionTitle(function), current.overrides[function.name], summaries, follow = true) {
                            if (!busy) picker = function.name
                        }
                    }
                }
                SettingsCard(backgroundColor = settingsCardColor(false)) {
                    SettingsNavRow(stringResource(R.string.orbit_models_more), onClick = { more = !more }, searchable = false)
                    if (more) listOf(FunctionType.SUMMARY, FunctionType.UI_CONTROLLER, FunctionType.TRANSLATION,
                        FunctionType.AUDIO_RECOGNITION, FunctionType.VIDEO_RECOGNITION).forEach { function ->
                        SettingsRowDivider(inset = false)
                        ModelRow(modelFunctionTitle(function), current.overrides[function.name], summaries, follow = true) {
                            if (!busy) picker = function.name
                        }
                    }
                }
            }
        }
    }
    picker?.let { key ->
        val function = FunctionType.entries.firstOrNull { it.name == key }
        OrbitModelPicker(
            title = when (key) {
                "main" -> stringResource(R.string.orbit_model_main)
                "assistant" -> stringResource(R.string.orbit_model_assistant)
                "multimodal" -> stringResource(R.string.orbit_model_multimodal)
                else -> modelFunctionTitle(checkNotNull(function))
            },
            summaries = summaries,
            current = settings?.let { current -> when (key) {
                "main" -> current.main
                "assistant" -> current.assistant
                "multimodal" -> current.multimodal
                else -> current.overrides[key]
            } },
            allowFollow = function != null,
            imageOnly = key == "multimodal" || function == FunctionType.IMAGE_RECOGNITION || function == FunctionType.UI_CONTROLLER,
            onDismiss = { picker = null },
            onSelect = { ref ->
                picker = null
                update { current -> when (key) {
                    "main" -> current.copy(main = ref)
                    "assistant" -> current.copy(assistant = ref)
                    "multimodal" -> current.copy(multimodal = ref)
                    else -> current.copy(overrides = if (ref == null) current.overrides - key else current.overrides + (key to ref))
                } }
            },
        )
    }
}

@Composable
private fun ModelRow(title: String, ref: OrbitModelRef?, summaries: List<ModelConfigSummary>,
    follow: Boolean = false, onClick: () -> Unit) {
    val summary = summaries.firstOrNull { it.id == ref?.configId }
    val valid = ref != null && summary != null && ref.resolveIndex(summary.modelName, summary.apiProviderTypeId) >= 0
    val modelName = ref?.modelName?.ifEmpty { stringResource(R.string.orbit_model_plugin_default) }.orEmpty()
    SettingsNavRow(title, onClick, searchable = false, subtitle = when {
        ref == null -> stringResource(if (follow) R.string.orbit_model_follow else R.string.orbit_model_not_set)
        valid -> "${summary?.name} / $modelName"
        else -> stringResource(R.string.orbit_model_invalid, modelName)
    })
}

/** Background reasoning for the assistant model, in the main app's five steps. */
@Composable
private fun AssistantReasoning(level: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val min = ApiPreferences.MIN_THINKING_QUALITY_LEVEL
    val max = ApiPreferences.MAX_THINKING_QUALITY_LEVEL
    var pending by remember(level) { mutableFloatStateOf(level.coerceIn(min, max).toFloat()) }
    val shown = pending.roundToInt()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.orbit_model_assistant_reasoning), Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(thinkingQualityLevelLabelRes(shown)), style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = pending, onValueChange = { pending = it.roundToInt().toFloat() },
            onValueChangeFinished = { if (pending.roundToInt() != level) onChange(pending.roundToInt()) },
            enabled = enabled, valueRange = min.toFloat()..max.toFloat(), steps = max - min - 1)
        Text(stringResource(R.string.orbit_model_assistant_reasoning_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private class PickerGroup(val summary: ModelConfigSummary, val refs: List<OrbitModelRef>)

/** Grouped by configuration like the main app: a configuration with several models opens to list them. */
@Composable
private fun OrbitModelPicker(title: String, summaries: List<ModelConfigSummary>, current: OrbitModelRef?,
    allowFollow: Boolean, imageOnly: Boolean, onDismiss: () -> Unit, onSelect: (OrbitModelRef?) -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { ModelConfigManager(context) }
    val pluginDefaultName = stringResource(R.string.orbit_model_plugin_default)
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(current?.configId) }
    val groups by produceState<List<PickerGroup>?>(null, summaries, imageOnly) {
        value = summaries.mapNotNull { summary ->
            val config = if (imageOnly) manager.getModelConfig(summary.id) else null
            // A config may list one model twice; the list keys need each once.
            val refs = getModelList(summary.modelName).distinct().map { OrbitModelRef(summary.id, it) }
                .ifEmpty { listOfNotNull(OrbitModelRef.fromSelection(summary, 0)) }
                .filter { !imageOnly || config?.multimodalCapabilitiesForModel(it.modelName)?.image == true }
            refs.takeIf { it.isNotEmpty() }?.let { PickerGroup(summary, it) }
        }
    }
    fun label(ref: OrbitModelRef) = ref.modelName.ifEmpty { pluginDefaultName }
    OrbitDialog(onDismissRequest = onDismiss, title = title, scrollable = false, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.orbit_models_search)) }, singleLine = true)
            val searching = query.isNotBlank()
            val shown = groups?.mapNotNull { group ->
                if (!searching || group.summary.name.contains(query, true)) group
                else group.refs.filter { label(it).contains(query, true) }.takeIf { it.isNotEmpty() }
                    ?.let { PickerGroup(group.summary, it) }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (allowFollow) item("follow") {
                    PickerRow(stringResource(R.string.orbit_model_follow), null, selected = current == null,
                        trailing = null) { onSelect(null) }
                }
                if (shown == null) item("loading") { CircularProgressIndicator() }
                else if (shown.isEmpty()) item("empty") {
                    Text(stringResource(if (imageOnly) R.string.orbit_models_no_image else R.string.orbit_models_empty),
                        Modifier.padding(vertical = 12.dp))
                }
                else shown.forEach { group ->
                    val id = group.summary.id
                    val single = group.refs.size == 1
                    val open = !single && (searching || expanded == id)
                    val holdsCurrent = current?.configId == id
                    item("config/$id") {
                        if (single) {
                            val ref = group.refs.first()
                            PickerRow(group.summary.name, label(ref), selected = ref == current,
                                trailing = null) { onSelect(ref) }
                        } else {
                            PickerRow(group.summary.name,
                                stringResource(R.string.functional_config_model_count, group.refs.size),
                                selected = holdsCurrent, trailing = if (open) "chevron-down" else "chevron-right") {
                                if (!searching) expanded = if (expanded == id) null else id
                            }
                        }
                    }
                    if (open) items(group.refs, key = { "model/$id/${it.modelName}" }) { ref ->
                        PickerRow(label(ref), null, selected = ref == current, trailing = null, nested = true) {
                            onSelect(ref)
                        }
                    }
                }
            }
        }
    }, dismissButton = {
        OrbitDialogAction(onDismiss, secondary = true) { Text(stringResource(R.string.cancel)) }
    })
}

@Composable
private fun PickerRow(title: String, subtitle: String?, selected: Boolean, trailing: String?,
    nested: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = if (nested) 16.dp else 0.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(if (selected) colors.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = if (nested) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (selected) colors.primary else colors.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                style = if (nested) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
        val icon = trailing ?: "check".takeIf { selected }
        icon?.let {
            Icon(orbitIcon(it), null, Modifier.padding(start = 8.dp).size(18.dp),
                tint = if (trailing == null) colors.primary else colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun modelFunctionTitle(function: FunctionType): String = stringResource(when (function) {
    FunctionType.PERMISSION_REVIEWER -> R.string.function_type_permission_reviewer
    FunctionType.PERMISSION_RISK_SCORER -> R.string.function_type_permission_risk_scorer
    FunctionType.TITLE_GENERATION -> R.string.function_type_title_generation
    FunctionType.MEMORY -> R.string.function_type_memory
    FunctionType.IMAGE_RECOGNITION -> R.string.function_type_image_recognition
    FunctionType.SUMMARY -> R.string.function_type_summary
    FunctionType.UI_CONTROLLER -> R.string.function_type_ui_controller
    FunctionType.TRANSLATION -> R.string.function_type_translation
    FunctionType.AUDIO_RECOGNITION -> R.string.function_type_audio_recognition
    FunctionType.VIDEO_RECOGNITION -> R.string.function_type_video_recognition
    else -> R.string.orbit_model_assistant
})

/** Task changes stay conversational, just like pause/resume in the task details. */
@Composable
internal fun OrbitTaskModelCard(task: com.ai.assistance.operit.data.model.OrbitTaskEntity,
    onDraft: (String) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val name = rememberOrbitName(task.orbitId)
    var picker by rememberSaveable(task.id) { mutableStateOf(false) }
    val preferences = remember(context) { OrbitModelPreferences(context) }
    val manager = remember(context) { ModelConfigManager(context) }
    val summaries by manager.configSummariesFlow.collectAsState(emptyList())
    val settings by produceState<OrbitModelSettings?>(null, task.orbitId) {
        value = preferences.current(task.orbitId)
        preferences.observe(task.orbitId).collect { value = it }
    }
    val role = com.ai.assistance.operit.features.orbit.OrbitModels.taskRole(task)
    val roleText = stringResource(if (role == "MAIN") R.string.orbit_model_main else R.string.orbit_model_assistant)
    val ref = if (role == "MAIN") settings?.main else settings?.assistant
    val summary = summaries.firstOrNull { it.id == ref?.configId }
    val modelName = ref?.modelName?.ifEmpty { stringResource(R.string.orbit_model_plugin_default) }.orEmpty()
    val modelText = when {
        settings == null -> "…"
        ref == null -> stringResource(R.string.orbit_model_not_set)
        summary == null || ref.resolveIndex(summary.modelName, summary.apiProviderTypeId) < 0 ->
            stringResource(R.string.orbit_model_invalid, modelName)
        else -> "${summary.name} / $modelName"
    }
    SettingsCard(backgroundColor = settingsCardColor(false)) {
        SettingsNavRow(stringResource(R.string.orbit_task_model),
            onClick = { if (task.state != "CANCELLED") picker = true }, searchable = false,
            subtitle = stringResource(R.string.orbit_task_model_actual, roleText, modelText))
    }
    if (picker) OrbitDialog(onDismissRequest = { picker = false },
        title = stringResource(R.string.orbit_task_model),
        text = {
            Text(stringResource(R.string.orbit_task_model_help, name))
            listOf("DEFAULT" to R.string.orbit_task_model_default, "MAIN" to R.string.orbit_model_main,
                "ASSISTANT" to R.string.orbit_model_assistant).forEach { (value, labelId) ->
                val label = stringResource(labelId)
                Text(label, Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    picker = false
                    onDraft(resources.getString(R.string.orbit_task_model_draft, task.title, task.id, label,
                        value.lowercase(java.util.Locale.ROOT)))
                }.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurface)
            }
        }, dismissButton = {
            OrbitDialogAction({ picker = false }, secondary = true) { Text(stringResource(R.string.cancel)) }
        })
}

/** The app's shared model configurations, opened inside Orbit so back returns to the models page. */
@Composable
internal fun OrbitModelConfigPage(onBack: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().navigationBarsPadding()) {
        OrbitPageHeader(stringResource(R.string.orbit_models_config_entry), onBack,
            Modifier.padding(horizontal = 20.dp).padding(top = 8.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            com.ai.assistance.operit.ui.features.settings.screens.ModelConfigScreen()
        }
    }
}
