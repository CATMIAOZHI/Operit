package com.ai.assistance.operit.ui.features.settings.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.speech.SpeechServiceFactory.SpeechServiceType
import com.ai.assistance.operit.api.voice.MimoVoiceProvider
import com.ai.assistance.operit.api.voice.VoiceServiceFactory.VoiceServiceType
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences
import com.ai.assistance.operit.ui.features.settings.components.SettingsAlertDialog
import com.ai.assistance.operit.ui.features.settings.components.SettingsDialogTextButton
import com.ai.assistance.operit.ui.features.settings.components.isOrbitDialogStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ai.assistance.operit.data.preferences.SpeechOrigin
import com.ai.assistance.operit.data.preferences.SpeechEngineDrafts
import java.io.File
import java.util.UUID

/**
 * Where a speech service's key comes from, which chat provider may already hold one, and the
 * service's own address, used when a setting leaves the address empty.
 */
internal enum class SpeechKeyFamily(val chatProvider: ApiProviderType?, val consoleUrl: String, val officialUrl: String) {
    MIMO(ApiProviderType.MIMO, MimoVoiceProvider.API_KEYS_URL, MimoVoiceProvider.DEFAULT_ENDPOINT_URL),
    SILICONFLOW(ApiProviderType.SILICONFLOW, "https://cloud.siliconflow.cn/account/ak", "https://api.siliconflow.cn"),
    MINIMAX(null, "https://platform.minimaxi.com/user-center/basic-information/interface-key", "https://api.minimaxi.com"),
    DOUBAO(null, "https://console.volcengine.com/speech/app", "https://openspeech.bytedance.com"),
    OPENAI(ApiProviderType.OPENAI, "https://platform.openai.com/api-keys", "https://api.openai.com"),
    DEEPGRAM(null, "https://console.deepgram.com/", "https://api.deepgram.com");

    companion object {
        fun of(type: VoiceServiceType): SpeechKeyFamily? = when (type) {
            VoiceServiceType.MIMO_TTS -> MIMO
            VoiceServiceType.SILICONFLOW_TTS -> SILICONFLOW
            VoiceServiceType.MINIMAX_TTS -> MINIMAX
            VoiceServiceType.DOUBAO_TTS -> DOUBAO
            VoiceServiceType.OPENAI_TTS, VoiceServiceType.OPENAI_WS_TTS -> OPENAI
            else -> null
        }

        fun of(type: SpeechServiceType): SpeechKeyFamily? = when (type) {
            SpeechServiceType.MIMO_STT -> MIMO
            SpeechServiceType.OPENAI_STT -> OPENAI
            SpeechServiceType.DEEPGRAM_STT -> DEEPGRAM
            else -> null
        }
    }
}

/** A key found elsewhere in the app; [endpoint] is set only when the same address also fits here. */
internal data class FoundSpeechKey(val key: String, val endpoint: String, val source: String)

/** A key saved somewhere in the app; [owner] is `tts:<id>`, `stt:<id>` or `chat:<id>`. */
internal data class SpeechKeySource(
    val owner: String,
    val family: SpeechKeyFamily,
    val key: String,
    val url: String,
    val name: String,
)

/**
 * Whether a key used at [sourceUrl] may be offered for a service at [targetUrl], so a key is only
 * ever sent to the server (scheme, host and port) it was made for. Returns null when it may not,
 * "" when the address stays, or the MiMo address to switch to (a Token Plan key only works on its
 * own host).
 */
internal fun keyEndpointFor(family: SpeechKeyFamily, sourceUrl: String, targetUrl: String): String? {
    val source = SpeechOrigin.of(sourceUrl.ifBlank { family.officialUrl }) ?: return null
    val target = SpeechOrigin.of(targetUrl.ifBlank { family.officialUrl }) ?: return null
    if (source == target) return ""
    // MiMo's own hosts only, and only when this service also points at one of them.
    fun mimoHost(host: String) = host == "xiaomimimo.com" || host.endsWith(".xiaomimimo.com")
    if (family != SpeechKeyFamily.MIMO || !source.secure || !mimoHost(source.host) || !mimoHost(target.host)) return null
    val url = sourceUrl.trim().trimEnd('/')
    return when {
        url.endsWith("/chat/completions") -> url
        url.endsWith("/v1") -> "$url/chat/completions"
        else -> null
    }
}

/** Keys in the saved voice profiles; read-aloud and voice input share MiMo's. */
internal fun profileKeySources(
    tts: List<SpeechServiceProfilesPreferences.TtsProfile>,
    stt: List<SpeechServiceProfilesPreferences.SttProfile>,
): List<SpeechKeySource> = buildList {
    tts.forEach { p ->
        val family = SpeechKeyFamily.of(p.serviceType) ?: return@forEach
        // SiliconFlow read-aloud ignores the stored URL and always calls its own server.
        val url = if (p.serviceType == VoiceServiceType.SILICONFLOW_TTS) "" else p.httpConfig.urlTemplate
        if (p.httpConfig.apiKey.isNotBlank()) add(SpeechKeySource("tts:${p.id}", family, p.httpConfig.apiKey, url, p.name))
    }
    stt.forEach { p ->
        val family = SpeechKeyFamily.of(p.serviceType) ?: return@forEach
        if (p.httpConfig.apiKey.isNotBlank()) add(SpeechKeySource("stt:${p.id}", family, p.httpConfig.apiKey, p.httpConfig.endpointUrl, p.name))
    }
}

/** Keys in the chat model configs, read once when the page opens. */
internal suspend fun chatKeySources(context: Context): List<SpeechKeySource> = withContext(Dispatchers.IO) {
    val families = SpeechKeyFamily.values().filter { it.chatProvider != null }
    val manager = ModelConfigManager(context.applicationContext)
    manager.getAllConfigSummaries().mapNotNull { summary ->
        val family = families.firstOrNull { it.chatProvider == summary.apiProviderType } ?: return@mapNotNull null
        val config = manager.getModelConfigFlow(summary.id).first()
        val key = config.apiKey.ifBlank { config.apiKeyPool.firstOrNull { it.isEnabled && it.key.isNotBlank() }?.key.orEmpty() }
        if (key.isBlank()) null else SpeechKeySource("chat:${summary.id}", family, key, config.apiEndpoint, summary.name)
    }
}

/**
 * The first saved key of the same provider and server, other than the one in the field and other
 * than the profile being edited ([owner]); voice profiles come before chat configs.
 */
internal fun pickSpeechKey(
    sources: List<SpeechKeySource>,
    family: SpeechKeyFamily,
    currentKey: String,
    targetUrl: String,
    owner: String,
): FoundSpeechKey? {
    for (s in sources) {
        if (s.family != family || s.owner == owner || s.key == currentKey) continue
        val endpoint = keyEndpointFor(family, s.url, targetUrl) ?: continue
        return FoundSpeechKey(s.key, endpoint, s.name)
    }
    return null
}

/** The keys a settings section can offer, and which profile that section edits. */
internal class SpeechKeyScope(val sources: List<SpeechKeySource>, val owner: String)

internal val LocalSpeechKeyScope = androidx.compose.runtime.compositionLocalOf { SpeechKeyScope(emptyList(), "") }

/** One choice in the service picker. */
internal data class SpeechEngineChoice(val id: String, val title: String, val summary: String, val badge: String? = null)

/** The service in use, as a tappable card that opens [SpeechEnginePicker]. */
@Composable
internal fun SpeechEngineCard(heading: String, choice: SpeechEngineChoice, onClick: () -> Unit) {
    Text(heading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 6.dp))
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(choice.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    choice.badge?.let { SpeechBadge(it) }
                }
                Text(choice.summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.speech_engine_change), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SpeechBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.padding(start = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** Grouped list of services with a line each on what they need, so a newcomer can choose. */
@Composable
internal fun SpeechEnginePicker(
    title: String,
    groups: List<Pair<String, List<SpeechEngineChoice>>>,
    selectedId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // The Orbit dialog scrolls its body itself; a second scroll inside it cannot be measured.
            Column((if (isOrbitDialogStyle()) Modifier else Modifier.verticalScroll(rememberScrollState())).selectableGroup()) {
                groups.forEach { (group, choices) ->
                    Text(group, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    choices.forEach { choice ->
                        val selected = choice.id == selectedId
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(choice.id) }),
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(choice.title, style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                    choice.badge?.let { SpeechBadge(it) }
                                }
                                Text(choice.summary, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { SettingsDialogTextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/**
 * The key field of an online service: a link to where the key is made, and a one-tap reuse of a
 * key the user already entered elsewhere in the app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeechApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    family: SpeechKeyFamily?,
    endpoint: String,
    onImport: (FoundSpeechKey) -> Unit,
    label: String = stringResource(R.string.speech_api_key_label),
) {
    val uriHandler = LocalUriHandler.current
    val keys = LocalSpeechKeyScope.current
    // Worked out from saved keys already in memory, so it always matches the key and address shown.
    val found = remember(keys, family, value, endpoint) {
        family?.let { pickSpeechKey(keys.sources, it, value, endpoint, keys.owner) }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(stringResource(R.string.speech_api_key_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    FlowRow(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        if (family != null) {
            TextButton(onClick = { runCatching { uriHandler.openUri(family.consoleUrl) } }) {
                Text(stringResource(R.string.speech_api_key_get))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
            }
        }
        found?.let { key ->
            TextButton(onClick = { onImport(key) }) {
                Text(stringResource(R.string.speech_api_key_reuse, key.source))
            }
        }
    }
}

/** Settings most people never need, folded away under one row. */
@Composable
internal fun SpeechAdvancedSection(content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (open) R.string.speech_advanced_expanded else R.string.speech_advanced_collapsed)
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button) { open = !open }
            .semantics { stateDescription = state }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.speech_advanced_settings), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AnimatedVisibility(open) { Column { content() } }
}

/**
 * MiMo read-aloud's optional model name. It only changes the model within the voice source
 * chosen above: a name of another source is not taken, and an empty box means that source's own
 * default, so typing here never switches the source behind the user's back.
 */
@Composable
internal fun MimoModelField(model: String, onModelChange: (String) -> Unit) {
    val mode = MimoVoiceProvider.Mode.of(model)
    val shown = model.takeIf { it.trim() != mode.model }.orEmpty()
    // A draft while typing: half-typed names are never saved. It follows the saved model when
    // that changes elsewhere (another profile, a source switched back).
    var text by remember(model) { mutableStateOf(shown) }
    var focused by remember { mutableStateOf(false) }
    val fits = text.isBlank() || MimoVoiceProvider.Mode.of(text) == mode
    // Applied when editing ends; a name of another source is not taken and the old model stays.
    fun commit() {
        val v = text.trim()
        when {
            v.isEmpty() || v == mode.model -> onModelChange(if (mode == MimoVoiceProvider.Mode.PRESET) "" else mode.model)
            MimoVoiceProvider.Mode.of(v) == mode -> onModelChange(v)
        }
    }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.speech_services_mimo_model_name)) },
        placeholder = { Text(mode.model) },
        isError = !fits,
        supportingText = {
            Text(stringResource(if (fits) R.string.speech_services_mimo_model_name_hint else R.string.mimo_model_other_source))
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            if (focused && !state.isFocused) commit()
            focused = state.isFocused
        },
        singleLine = true,
    )
    // Leaving the page while still typing keeps a name that fits, as finishing would. The latest
    // commit is used, since the draft is replaced whenever the saved model changes.
    val latestCommit by rememberUpdatedState({ if (focused) commit() })
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { latestCommit() } }
}

/** Plays a short sample through the saved read-aloud settings. */
@Composable
internal fun SpeechPreviewButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.VolumeUp, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (busy) R.string.speech_preview_busy else R.string.speech_preview))
    }
}

/** "wav" or "mp3" from a file's first bytes; MiMo takes nothing else. */
internal fun voiceSampleFormat(head: ByteArray): String? = MimoVoiceProvider.sampleFormat(head)

/**
 * Copies a picked recording into the app so the profile only has to remember its file name.
 * Older recordings no profile or draft still names are removed afterwards.
 */
internal suspend fun keepVoiceSample(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val dir = MimoVoiceProvider.sampleDir(context).apply { mkdirs() }
    val id = UUID.randomUUID().toString()
    val part = File(dir, "$id.part")
    try {
        val head = ByteArray(12)
        var headSize = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (headSize < head.size) {
                        val take = minOf(n, head.size - headSize)
                        System.arraycopy(buffer, 0, head, headSize, take)
                        headSize += take
                    }
                    total += n
                    if (total > MimoVoiceProvider.MAX_SAMPLE_BYTES) {
                        throw IllegalArgumentException(context.getString(R.string.mimo_tts_error_voice_sample_too_large))
                    }
                    output.write(buffer, 0, n)
                }
            }
        } ?: throw IllegalArgumentException(context.getString(R.string.mimo_tts_error_voice_sample_missing))
        val ext = voiceSampleFormat(head.copyOf(headSize))
            ?: throw IllegalArgumentException(context.getString(R.string.mimo_clone_sample_format))
        val out = File(dir, "$id.$ext")
        if (!part.renameTo(out)) throw IllegalStateException(context.getString(R.string.mimo_tts_error_voice_sample_missing))
        runCatching { dropUnusedVoiceSamples(context, keep = out.name) }
        out
    } finally {
        part.delete()
    }
}

/**
 * Moves older inline voice recordings (whole `data:audio/...` strings) out of MiMo read-aloud
 * profiles and drafts into kept files, leaving the file name. A profile is only changed if it
 * still holds the same recording. One that can never be sent (too large for MiMo, or broken) is
 * cleared, so the page shows that a recording should be picked again.
 */
internal suspend fun moveInlineVoiceSamples(context: Context, prefs: SpeechServiceProfilesPreferences) =
    withContext(Dispatchers.IO) {
        // The file name; "" for a recording that can never be sent; null (left as is) when the
        // file could not be written this time.
        fun keep(value: String): String? = try {
            MimoVoiceProvider.keepInlineSample(context, value)?.name.orEmpty()
        } catch (_: com.ai.assistance.operit.api.voice.TtsException) {
            ""
        } catch (_: Exception) {
            null
        }
        prefs.ttsProfilesFlow.first().forEach { p ->
            if (p.serviceType != VoiceServiceType.MIMO_TTS) return@forEach
            val voice = p.httpConfig.voiceId
            if (!voice.trim().startsWith("data:audio/")) return@forEach
            keep(voice)?.let { prefs.replaceTtsVoice(p.id, voice, it) }
        }
        SpeechEngineDrafts.rewriteMimoVoices(context) { value ->
            if (value.trim().startsWith("data:audio/")) keep(value) ?: value else value
        }
    }

/** Removes recordings no saved profile or draft names, leaving anything picked in the last day. */
private suspend fun dropUnusedVoiceSamples(context: Context, keep: String) {
    val dir = MimoVoiceProvider.sampleDir(context)
    val used = (SpeechServiceProfilesPreferences(context.applicationContext).ttsProfilesFlow.first()
        .asSequence().map { it.httpConfig.voiceId } + SpeechEngineDrafts.allFields(context))
        .mapNotNull { MimoVoiceProvider.inlineSampleName(it) ?: MimoVoiceProvider.sampleName(it) }
        .toHashSet()
    val old = System.currentTimeMillis() - 24L * 60 * 60 * 1000
    dir.listFiles()?.forEach { f ->
        if (f.name != keep && f.name !in used && f.lastModified() < old) f.delete()
    }
}

/**
 * MiMo read-aloud: pick how the voice is made, then a preset voice, a voice description or a
 * recording to copy, plus an optional speaking style.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MimoTtsSettings(
    profileId: String,
    model: String,
    onModelChange: (String) -> Unit,
    voice: String,
    onVoiceChange: (String) -> Unit,
    instruction: String,
    onInstructionChange: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode = MimoVoiceProvider.Mode.of(model)
    // While a recording is copied in, the mode stays put so the result cannot land in another mode.
    var copying by remember { mutableStateOf(false) }
    val currentMode by rememberUpdatedState(mode)
    val setVoice by rememberUpdatedState(onVoiceChange)
    val modes = listOf(
        MimoVoiceProvider.Mode.PRESET to R.string.mimo_mode_preset,
        MimoVoiceProvider.Mode.DESIGN to R.string.mimo_mode_design,
        MimoVoiceProvider.Mode.CLONE to R.string.mimo_mode_clone,
    )
    Text(stringResource(R.string.mimo_voice_source), style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, (m, label) ->
            SegmentedButton(
                selected = mode == m,
                enabled = !copying,
                onClick = {
                    // Each voice source keeps its own voice, recording, description or style and
                    // model, so going back to one finds it as it was left.
                    if (mode != m) {
                        val prefix = "tts:$profileId:${VoiceServiceType.MIMO_TTS.name}#"
                        SpeechEngineDrafts.save(context, prefix + mode.name, listOf(model, voice, instruction))
                        val back = SpeechEngineDrafts.load(context, prefix + m.name, 3)
                        val backModel = back?.get(0)?.takeIf { MimoVoiceProvider.Mode.of(it) == m }
                        onModelChange(backModel ?: if (m == MimoVoiceProvider.Mode.PRESET) "" else m.model)
                        onVoiceChange(back?.get(1)
                            ?: if (m == MimoVoiceProvider.Mode.PRESET) MimoVoiceProvider.DEFAULT_VOICE_ID else "")
                        onInstructionChange(back?.get(2) ?: "")
                    }
                },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }
    Text(
        stringResource(when (mode) {
            MimoVoiceProvider.Mode.PRESET -> R.string.mimo_mode_preset_hint
            MimoVoiceProvider.Mode.DESIGN -> R.string.mimo_mode_design_hint
            MimoVoiceProvider.Mode.CLONE -> R.string.mimo_mode_clone_hint
        }),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
    )
    when (mode) {
        MimoVoiceProvider.Mode.PRESET -> {
            val current = MimoVoiceProvider.presetVoice(voice)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MimoVoiceProvider.AVAILABLE_VOICES.forEach { v ->
                    val detail = when {
                        v.id == MimoVoiceProvider.DEFAULT_VOICE_ID -> stringResource(R.string.mimo_voice_default)
                        else -> stringResource(
                            if (v.gender == "FEMALE") R.string.mimo_voice_female else R.string.mimo_voice_male,
                        ) + " · " + stringResource(if (v.locale.startsWith("zh")) R.string.mimo_voice_zh else R.string.mimo_voice_en)
                    }
                    FilterChip(
                        selected = current == v.id,
                        onClick = { onVoiceChange(v.id) },
                        label = {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(if (v.id == MimoVoiceProvider.DEFAULT_VOICE_ID) stringResource(R.string.mimo_voice_auto) else v.name)
                                Text(detail, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
        }
        MimoVoiceProvider.Mode.DESIGN -> Unit
        MimoVoiceProvider.Mode.CLONE -> {
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null && !copying) {
                    copying = true
                    scope.launch {
                        runCatching { keepVoiceSample(context, uri) }
                            // The old recording stays until nothing names it; another profile may share it.
                            .onSuccess { file -> if (currentMode == MimoVoiceProvider.Mode.CLONE) setVoice(file.name) }
                            .onFailure { onError(it.message ?: context.getString(R.string.mimo_tts_error_voice_sample_missing)) }
                        copying = false
                    }
                }
            }
            // Size in KB once read; -1 while it is being read, null when there is no recording.
            // Started afresh for each recording, so an older result never shows for a new one.
            val sampleKb by androidx.compose.runtime.key(voice) {
                produceState<Long?>(-1L) {
                    value = withContext(Dispatchers.IO) { MimoVoiceProvider.sampleFile(context, voice)?.length()?.div(1024) }
                }
            }
            val sample = when {
                copying -> stringResource(R.string.mimo_clone_sample_copying)
                voice.startsWith("data:") -> stringResource(R.string.mimo_clone_sample_legacy)
                sampleKb == -1L -> stringResource(R.string.mimo_clone_sample_checking)
                sampleKb != null -> stringResource(R.string.mimo_clone_sample_ready, sampleKb ?: 0L)
                voice.isNotBlank() -> stringResource(R.string.mimo_clone_sample_lost)
                else -> stringResource(R.string.mimo_clone_sample_none)
            }
            Text(sample, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
            OutlinedButton(onClick = { picker.launch("audio/*") }, enabled = !copying, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.mimo_clone_pick))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = instruction,
        onValueChange = onInstructionChange,
        label = {
            Text(stringResource(if (mode == MimoVoiceProvider.Mode.DESIGN) R.string.mimo_design_label else R.string.mimo_style_label))
        },
        placeholder = {
            Text(stringResource(if (mode == MimoVoiceProvider.Mode.DESIGN) R.string.mimo_design_placeholder else R.string.mimo_style_placeholder))
        },
        supportingText = if (mode == MimoVoiceProvider.Mode.DESIGN) null else {
            { Text(stringResource(R.string.mimo_style_hint)) }
        },
        isError = mode == MimoVoiceProvider.Mode.DESIGN && instruction.isBlank(),
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 5,
    )
}

/** Built-in address for a service picked for the first time; empty where the service has its own. */
internal fun defaultTtsEndpoint(type: VoiceServiceType): String = when (type) {
    VoiceServiceType.DOUBAO_TTS -> com.ai.assistance.operit.api.voice.DoubaoVoiceProvider.DEFAULT_ENDPOINT_URL
    VoiceServiceType.OPENAI_TTS -> "https://api.openai.com/v1/audio/speech"
    VoiceServiceType.OPENAI_WS_TTS -> "wss://api.openai.com/v1/realtime"
    else -> ""
}

internal fun defaultSttEndpoint(type: SpeechServiceType): String = when (type) {
    SpeechServiceType.OPENAI_STT -> "https://api.openai.com/v1/audio/transcriptions"
    SpeechServiceType.DEEPGRAM_STT -> "https://api.deepgram.com/v1/listen"
    else -> ""
}

internal fun defaultSttModel(type: SpeechServiceType): String = when (type) {
    SpeechServiceType.OPENAI_STT -> "gpt-4o-mini-transcribe"
    SpeechServiceType.DEEPGRAM_STT -> "nova-2"
    else -> ""
}

/** Read-aloud services grouped by what they need from the user, easiest first. */
@Composable
internal fun ttsEngineGroups(): List<Pair<String, List<SpeechEngineChoice>>> {
    fun c(type: VoiceServiceType, title: String, summary: String, badge: String? = null) =
        SpeechEngineChoice(type.name, title, summary, badge)
    val recommended = stringResource(R.string.speech_engine_badge_recommended)
    val free = stringResource(R.string.speech_engine_badge_free)
    return listOf(
        stringResource(R.string.speech_engine_group_recommended) to listOf(
            c(VoiceServiceType.MIMO_TTS, stringResource(R.string.speech_engine_mimo), stringResource(R.string.speech_engine_mimo_tts_desc), recommended),
        ),
        stringResource(R.string.speech_engine_group_offline) to listOf(
            c(VoiceServiceType.SIMPLE_TTS, stringResource(R.string.speech_engine_system), stringResource(R.string.speech_engine_system_desc), free),
            c(VoiceServiceType.SHERPA_ONNX, stringResource(R.string.voice_local_engine), stringResource(R.string.speech_engine_local_tts_desc), free),
            c(VoiceServiceType.VITS_TTS, stringResource(R.string.speech_engine_vits), stringResource(R.string.speech_engine_vits_desc), free),
        ),
        stringResource(R.string.speech_engine_group_online) to listOf(
            c(VoiceServiceType.SILICONFLOW_TTS, stringResource(R.string.speech_engine_siliconflow), stringResource(R.string.speech_engine_siliconflow_desc)),
            c(VoiceServiceType.MINIMAX_TTS, "MiniMax", stringResource(R.string.speech_engine_minimax_desc)),
            c(VoiceServiceType.DOUBAO_TTS, stringResource(R.string.speech_engine_doubao), stringResource(R.string.speech_engine_doubao_desc)),
            c(VoiceServiceType.OPENAI_TTS, stringResource(R.string.speech_engine_openai), stringResource(R.string.speech_engine_openai_tts_desc)),
            c(VoiceServiceType.OPENAI_WS_TTS, stringResource(R.string.speech_engine_openai_realtime), stringResource(R.string.speech_engine_openai_realtime_desc)),
        ),
        stringResource(R.string.speech_engine_group_custom) to listOf(
            c(VoiceServiceType.HTTP_TTS, stringResource(R.string.speech_engine_http), stringResource(R.string.speech_engine_http_desc)),
        ),
    )
}

/** Voice-input services grouped the same way. */
@Composable
internal fun sttEngineGroups(): List<Pair<String, List<SpeechEngineChoice>>> {
    fun c(type: SpeechServiceType, title: String, summary: String, badge: String? = null) =
        SpeechEngineChoice(type.name, title, summary, badge)
    val free = stringResource(R.string.speech_engine_badge_free)
    return listOf(
        stringResource(R.string.speech_engine_group_recommended) to listOf(
            c(SpeechServiceType.MIMO_STT, stringResource(R.string.speech_engine_mimo), stringResource(R.string.speech_engine_mimo_stt_desc),
                stringResource(R.string.speech_engine_badge_recommended)),
        ),
        stringResource(R.string.speech_engine_group_offline) to listOf(
            c(SpeechServiceType.SHERPA_ONNX, stringResource(R.string.voice_local_engine), stringResource(R.string.speech_engine_local_stt_desc), free),
            c(SpeechServiceType.SHERPA_NCNN, stringResource(R.string.speech_services_stt_type_sherpa), stringResource(R.string.speech_engine_ncnn_desc), free),
        ),
        stringResource(R.string.speech_engine_group_online) to listOf(
            c(SpeechServiceType.OPENAI_STT, stringResource(R.string.speech_engine_openai), stringResource(R.string.speech_engine_openai_stt_desc)),
            c(SpeechServiceType.DEEPGRAM_STT, "Deepgram", stringResource(R.string.speech_engine_deepgram_desc)),
        ),
    )
}
