package com.ai.assistance.operit.ui.common.dynamicui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.engine.model.A2uiCoreSurfaceModel
import androidx.a2ui.model.processor.A2uiActionInterceptor
import androidx.a2ui.model.protocol.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.ui.Alignment
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.common.markdown.EnhancedCodeBlock
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

data class DynamicUiOwner(val chatId: String, val timestamp: Long, val variant: Int, val content: String)
val LocalDynamicUiOwner = staticCompositionLocalOf<DynamicUiOwner?> { null }
val LocalDynamicUiSubmit = staticCompositionLocalOf<((DynamicUiOwner, String) -> Boolean)?> { null }

@Composable
fun DynamicUiCard(source: String, closed: Boolean, streaming: Boolean, blockIndex: Int) {
    val owner = LocalDynamicUiOwner.current
    // A completed revision owns independent state. Streaming cards are display-only.
    val revision = remember(source, closed) { if (closed) DynamicUiProtocol.fingerprint(source) else "streaming" }
    key(owner?.chatId, owner?.timestamp, owner?.variant, revision, blockIndex) {
        DynamicUiCardContent(source, closed, streaming, owner, blockIndex)
    }
}

@Composable
private fun DynamicUiCardContent(source: String, closed: Boolean, streaming: Boolean, owner: DynamicUiOwner?, blockIndex: Int) {
    val context = LocalContext.current
    val submit by rememberUpdatedState(LocalDynamicUiSubmit.current)
    val currentOwner by rememberUpdatedState(owner)
    val storageSource = "$blockIndex:$source"
    val storageFiles = remember(owner?.chatId, owner?.timestamp, owner?.variant, storageSource) {
        mutableMapOf<String, java.io.File>()
    }
    fun stateFile(surfaceId: String): java.io.File? = currentOwner?.let { identity ->
        storageFiles.getOrPut(surfaceId) { DynamicUiStateStore.file(context, identity, storageSource, surfaceId) }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var validated by remember { mutableStateOf(false) }
    var processed by remember { mutableStateOf(emptyList<String>()) }
    var components by remember { mutableStateOf<Map<String, Map<String, JsonObject>>>(emptyMap()) }
    val surfaceErrors = remember { mutableStateMapOf<String, String>() }
    val iconWarnings = remember { mutableStateMapOf<String, Boolean>() }
    val loggedErrors = remember { mutableSetOf<String>() }
    val ready = remember { mutableStateMapOf<String, Boolean>() }
    val saved = remember { mutableStateMapOf<String, JsonElement>() }
    val results = remember { mutableStateMapOf<String, Boolean>() }
    var pending by remember { mutableStateOf<A2uiClientEventMessage?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showSource by remember { mutableStateOf(false) }
    val storageFailures by DynamicUiStateStore.failures.collectAsState()
    val processor = remember {
        A2uiMessageProcessor(
            listOf(operitDynamicUiCatalog),
            listOf(A2uiActionInterceptor { action ->
                if (action is A2uiEventAction && action.eventName in DynamicUiProtocol.actions) action else null
            }),
        )
    }
    val surfaces by processor.activeSurfaces.collectAsState()
    val invalidText = stringResource(R.string.dynamic_ui_invalid)
    val failedText = stringResource(R.string.dynamic_ui_action_failed)
    val sentText = stringResource(R.string.dynamic_ui_sent)
    val copiedText = stringResource(R.string.dynamic_ui_copied)
    val localSavedText = stringResource(R.string.dynamic_ui_local_saved)
    val requiredText = stringResource(R.string.dynamic_ui_required)
    val tooLargeText = stringResource(R.string.dynamic_ui_state_limit)
    val submitFailedText = stringResource(R.string.dynamic_ui_submit_failed)

    fun logIssue(phase: String, surfaceId: String = "", category: String = "") {
        val safeCategory = category.filter { it.isLetterOrDigit() || it == '_' }.take(64)
        if (loggedErrors.size < 64 && loggedErrors.add("$phase:$surfaceId:$safeCategory")) {
            AppLogger.w("DynamicUi", "phase=$phase block=$blockIndex source=${DynamicUiProtocol.fingerprint(source).take(12)} surface=${surfaceId.take(80)} category=$safeCategory")
        }
    }

    fun safely(action: () -> Unit) {
        try { action() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { logIssue("local_action", category = e.javaClass.simpleName); notice = failedText }
    }

    fun model(surface: A2uiCoreSurfaceModel): JsonElement {
        val raw = DynamicUiProtocol.toJson(surface.dataModel[A2uiDataPath("/")]) as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(raw.filterKeys { it != DynamicUiProtocol.MARKER }).also {
            require(it.toString().length <= DynamicUiProtocol.MAX_STATE)
        }
    }
    fun restore(surface: A2uiCoreSurfaceModel, data: JsonElement) {
        surface.dataModel.update(A2uiDataPath("/"), DynamicUiProtocol.toValue(data))
        surface.dataModel.update(A2uiDataPath("/${DynamicUiProtocol.MARKER}"), "ready")
    }
    fun persist(surface: A2uiCoreSurfaceModel, data: JsonElement) {
        val file = stateFile(surface.id) ?: return
        DynamicUiStateStore.save(file, buildJsonObject {
            put("data", data)
            saved[surface.id]?.let { put("saved", it) }
            put("result", results[surface.id] == true)
        })
    }
    LaunchedEffect(processor) {
        // Start collecting errors/events before replay, otherwise a parser failure could be missed.
        launch(start = CoroutineStart.UNDISPATCHED) {
            processor.outboundEvents.collect { event ->
                when (event) {
                    is A2uiClientErrorMessage -> {
                        // AndroidX already marks the failing component. Preserve the rest of the
                        // surface, but freeze its actions if data/binding integrity is uncertain.
                        surfaceErrors[event.surfaceId] = event.code
                        if (pending?.surfaceId == event.surfaceId) pending = null
                        logIssue("engine", event.surfaceId, event.code)
                    }
                    is A2uiClientEventMessage -> {
                        if (!closed || !validated || error != null || ready[event.surfaceId] != true ||
                            event.surfaceId in surfaceErrors || pending != null ||
                            (results[event.surfaceId] == true && event.type == "local_action")) return@collect
                        val surface = surfaces.firstOrNull { it.id == event.surfaceId } as? A2uiCoreSurfaceModel ?: return@collect
                        try {
                            val actionContext = if (event.type == "local_action")
                                resolveLocalCardContext(surface, event.componentId, components[event.surfaceId]?.get(event.componentId), event.context)
                                else event.context
                            val payload = DynamicUiProtocol.toJson(actionContext)
                            require(payload.toString().length <= DynamicUiProtocol.MAX_STATE)
                            when (event.type) {
                                "local_action" -> when (actionContext["op"]) {
                                    "set", "add" -> {
                                        val path = actionContext["path"] as? String ?: error("Missing path")
                                        DynamicUiProtocol.validatePath(path)
                                        val next = if (actionContext["op"] == "add") {
                                            val old = surface.dataModel[A2uiDataPath(path)] as? Number ?: 0
                                            val delta = actionContext["value"] as? Number ?: error("Missing number")
                                            (old.toDouble() + delta.toDouble()).also { require(it.isFinite() && kotlin.math.abs(it) <= 1e9) }
                                        } else actionContext["value"]
                                        DynamicUiProtocol.toJson(next)
                                        val before = model(surface)
                                        surface.dataModel.update(A2uiDataPath(path), next)
                                        try { model(surface) } catch (e: Exception) { restore(surface, before); throw e }
                                    }
                                    "save" -> { saved[surface.id] = model(surface); results[surface.id] = true; notice = localSavedText }
                                    "edit" -> results[surface.id] = false
                                    "cancel" -> { saved[surface.id]?.let { restore(surface, it) }; results[surface.id] = saved.containsKey(surface.id) }
                                    else -> error("Unknown local action")
                                }
                                "ai_submit", "tool_action" -> {
                                    val missing = (event.context["required"] as? List<*>).orEmpty().any {
                                        val path = it as? String ?: error("Invalid required path")
                                        DynamicUiProtocol.validatePath(path)
                                        surface.dataModel[A2uiDataPath(path)]?.toString().isNullOrBlank()
                                    }
                                    if (missing) notice = requiredText else pending = event
                                }
                                "open_url" -> {
                                    val url = event.context["url"] as? String ?: error("Missing URL")
                                    require(DynamicUiProtocol.safeUrl(url))
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                                "copy" -> {
                                    val text = event.context["text"] as? String ?: error("Missing text")
                                    require(text.length <= 8192)
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                        .setPrimaryClip(ClipData.newPlainText("", text))
                                    notice = copiedText
                                }
                            }
                            persist(surface, model(surface))
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { logIssue("action", event.surfaceId, e.javaClass.simpleName); notice = failedText }
                    }
                }
            }
        }
        processor.collectMessages()
    }
    LaunchedEffect(source, closed) {
        try {
            val batch = withContext(Dispatchers.Default) { DynamicUiProtocol.parse(source, closed) }
            components = batch.componentsBySurface
            require(batch.lines.take(processed.size) == processed) { "Card changed during streaming" }
            val parser = A2uiMessageParser()
            // Parse everything before feeding anything: malformed updates never reach the renderer.
            val messages = withContext(Dispatchers.Default) { batch.lines.drop(processed.size).map { parser.parse(it) } }
            messages.forEach { processor.processMessage(it) }
            processed = batch.lines
            if (closed) {
                batch.surfaceIds.forEach { id ->
                    val record = stateFile(id)?.let { DynamicUiStateStore.read(it) }
                    record?.get("data")?.let {
                        processor.processMessage(A2uiUpdateDataModelMessage(id, "/", DynamicUiProtocol.toValue(it)))
                    }
                    record?.get("saved")?.let { saved[id] = it }
                    results[id] = record?.get("result")?.jsonPrimitive?.booleanOrNull == true
                    processor.processMessage(A2uiUpdateDataModelMessage(id, "/${DynamicUiProtocol.MARKER}", "ready"))
                }
            }
            validated = closed
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            logIssue("protocol", category = "${DynamicUiProtocol.diagnosticCode(e)}_line_${DynamicUiProtocol.diagnosticLine(e) ?: 0}")
            error = e.message?.take(240) ?: invalidText
        }
    }
    surfaces.forEach { candidate ->
        val surface = candidate as A2uiCoreSurfaceModel
        LaunchedEffect(surface, validated) {
            if (!validated) return@LaunchedEffect
            snapshotFlow { surface.dataModel[A2uiDataPath("/${DynamicUiProtocol.MARKER}")] }.first { it == "ready" }
            ready[surface.id] = true
            snapshotFlow { runCatching { model(surface) }.getOrNull() }.collect { data ->
                if (data != null && error == null && surface.id !in surfaceErrors) persist(surface, data)
                else if (data == null) notice = tooLargeText
            }
        }
    }
    // Use the app's fonts and colors, with form-sized body text instead of chat prose sizing.
    MaterialTheme(
        colorScheme = cardColorScheme(MaterialTheme.colorScheme),
        shapes = MaterialTheme.shapes.copy(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(20.dp)),
        typography = MaterialTheme.typography.copy(bodyLarge = MaterialTheme.typography.bodyMedium),
    ) {
    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (error != null) {
                Text(invalidText, color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.dynamic_ui_regenerate), style = MaterialTheme.typography.bodySmall)
            } else {
                if (!closed) Text(stringResource(if (streaming) R.string.dynamic_ui_generating else R.string.dynamic_ui_interrupted))
                surfaces.forEach { surface ->
                    key(surface.id) {
                        val readOnly = results[surface.id] == true
                        val enabled = closed && ready[surface.id] == true && pending == null && !readOnly && surface.id !in surfaceErrors
                        CompositionLocalProvider(
                            LocalCardRootAlreadyFramed provides
                                (components[surface.id]?.get("root")?.get("component")?.jsonPrimitive?.content == "Card"),
                            LocalCardInteractive provides enabled,
                            LocalCardBrowse provides (closed && ready[surface.id] == true && pending == null && surface.id !in surfaceErrors),
                            LocalCardReadOnly provides readOnly,
                            LocalCardInputError provides { logIssue("field_update", surface.id); notice = failedText },
                            LocalCardIconWarning provides {
                                iconWarnings[surface.id] = true
                                logIssue("icon", surface.id, "unknown_token")
                            },
                        ) {
                            A2uiSurface(surface, modifier = Modifier.fillMaxWidth(), transitionSpec = null, errorContent = {
                                LaunchedEffect(it) {
                                    surfaceErrors[surface.id] = it.code
                                    logIssue("render", surface.id, it.code)
                                }
                                Text(invalidText, color = MaterialTheme.colorScheme.error)
                            })
                        }
                        if (surface.id in surfaceErrors) Text(
                            stringResource(R.string.dynamic_ui_partial_error), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (readOnly) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.dynamic_ui_saved), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            TextButton(enabled = pending == null && surface.id !in surfaceErrors, onClick = { safely { results[surface.id] = false; persist(surface as A2uiCoreSurfaceModel, model(surface)) } }) {
                                Text(stringResource(R.string.dynamic_ui_edit))
                            }
                        } else {
                            if (saved.containsKey(surface.id)) TextButton(onClick = { safely {
                                restore(surface as A2uiCoreSurfaceModel, saved.getValue(surface.id))
                                results[surface.id] = true
                                persist(surface, model(surface))
                            } }, enabled = enabled) { Text(stringResource(R.string.dynamic_ui_cancel_edit)) }
                        }
                    }
                }
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (owner != null && surfaces.any {
                stateFile(it.id) in storageFailures
            }) Text(stringResource(R.string.dynamic_ui_save_failed), color = MaterialTheme.colorScheme.error)
            pending?.let { event ->
                HorizontalDivider()
                Text(stringResource(if (event.type == "tool_action") R.string.dynamic_ui_confirm_tool else R.string.dynamic_ui_confirm_send))
                val graph = components[event.surfaceId].orEmpty()
                val definition = (graph[event.componentId]?.get("action") as? JsonObject)?.get("event")
                    ?.jsonObject?.get("context")
                val rows = remember(event, definition, graph) {
                    cardSubmissionRows(DynamicUiProtocol.toJson(event.context), definition, graph.values)
                }
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { (label, value) ->
                        Column {
                            if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val text = when {
                                value is JsonPrimitive && value.booleanOrNull != null && !value.isString ->
                                    stringResource(if (value.boolean) R.string.dynamic_ui_yes else R.string.dynamic_ui_no)
                                value is JsonPrimitive -> value.content.takeUnless { value is JsonNull }.orEmpty()
                                else -> value.toString()
                            }
                            Text(text.ifBlank { stringResource(R.string.dynamic_ui_empty_value) }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { safely {
                        check(pending === event && error == null && event.surfaceId !in surfaceErrors) { "Card is no longer ready" }
                        // Clear pending first: a double click cannot enqueue the same receipt twice.
                        pending = null
                        val identity = currentOwner
                        val surface = surfaces.firstOrNull { it.id == event.surfaceId } as? A2uiCoreSurfaceModel
                            ?: error("Surface missing")
                        val submittedState = model(surface) // Validate before any model request.
                        val request = buildJsonObject {
                            put("card", event.surfaceId); put("action", event.type)
                            put("data", DynamicUiProtocol.toJson(event.context))
                        }
                        val accepted = identity != null && submit?.invoke(identity,
                            context.getString(R.string.dynamic_ui_request) + "\n" + request.toString()) == true
                        if (accepted) {
                            saved[surface.id] = submittedState
                            results[surface.id] = true
                            persist(surface, submittedState)
                        }
                        notice = if (accepted) sentText else submitFailedText
                    } }) { Text(stringResource(R.string.dynamic_ui_send)) }
                    TextButton(onClick = { pending = null }) { Text(stringResource(android.R.string.cancel)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (iconWarnings.isNotEmpty()) Text(stringResource(R.string.dynamic_ui_icon_missing), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { showSource = !showSource }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Code, stringResource(R.string.dynamic_ui_source), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showSource) {
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                EnhancedCodeBlock(source.take(DynamicUiProtocol.MAX_SOURCE), "json", enableDialogs = false)
            }
        }
    }
    }
    }
}
