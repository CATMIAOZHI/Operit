package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.model.multimodalCapabilitiesForModel
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.OrbitModelPreferences
import com.ai.assistance.operit.data.preferences.OrbitModelRef
import com.ai.assistance.operit.data.preferences.OrbitModelSettings
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.ui.features.settings.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OrbitModelsPage(orbit: OrbitProfile, onBack: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
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

@Composable
private fun OrbitModelPicker(title: String, summaries: List<ModelConfigSummary>, allowFollow: Boolean,
    imageOnly: Boolean, onDismiss: () -> Unit, onSelect: (OrbitModelRef?) -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { ModelConfigManager(context) }
    val pluginDefaultName = stringResource(R.string.orbit_model_plugin_default)
    var query by rememberSaveable { mutableStateOf("") }
    val choices by produceState<List<Pair<OrbitModelRef, String>>?>(null, summaries, imageOnly) {
        value = summaries.flatMap { summary ->
            val config = if (imageOnly) manager.getModelConfig(summary.id) else null
            val refs = getModelList(summary.modelName).map { OrbitModelRef(summary.id, it) }
                .ifEmpty { listOfNotNull(OrbitModelRef.fromSelection(summary, 0)) }
            refs.filter { !imageOnly || config?.multimodalCapabilitiesForModel(it.modelName)?.image == true }
                .map { it to summary.name }
        }.distinctBy { it.first } // A config may list one model twice; the list keys need each once.
    }
    OrbitDialog(onDismissRequest = onDismiss, title = title, scrollable = false, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.orbit_models_search)) }, singleLine = true)
            val filtered = choices?.filter { (ref, name) ->
                query.isBlank() || ref.modelName.ifEmpty { pluginDefaultName }.contains(query, true) || name.contains(query, true)
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                if (allowFollow) item("follow") {
                    Text(stringResource(R.string.orbit_model_follow),
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelect(null) }.padding(vertical = 16.dp))
                }
                if (filtered == null) item("loading") { CircularProgressIndicator() }
                else if (filtered.isEmpty()) item("empty") {
                    Text(stringResource(if (imageOnly) R.string.orbit_models_no_image else R.string.orbit_models_empty),
                        Modifier.padding(vertical = 12.dp))
                }
                else items(filtered, key = { "${it.first.configId}/${it.first.modelName}" }) { (ref, name) ->
                    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelect(ref) }
                        .padding(vertical = 12.dp)) {
                        Text(ref.modelName.ifEmpty { pluginDefaultName },
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }, dismissButton = {
        OrbitDialogAction(onDismiss, secondary = true) { Text(stringResource(R.string.cancel)) }
    })
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
                    onDraft(context.getString(R.string.orbit_task_model_draft, task.title, task.id, label,
                        value.lowercase(java.util.Locale.ROOT)))
                }.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurface)
            }
        }, dismissButton = {
            OrbitDialogAction({ picker = false }, secondary = true) { Text(stringResource(R.string.cancel)) }
        })
}
