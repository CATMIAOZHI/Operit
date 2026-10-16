package com.ai.assistance.operit.ui.features.orbit

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.UserProfileDocumentRepository
import com.ai.assistance.operit.features.orbit.OrbitPromptFiles
import com.ai.assistance.operit.features.orbit.OrbitSoulRepository
import com.ai.assistance.operit.ui.common.markdown.StreamMarkdownRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** An extra item in a file page's "more" menu. */
internal class OrbitFileAction(val label: String, val icon: String, val run: suspend () -> Unit)

/**
 * Muse-style page for one of Orbit's own Markdown files: a short note on what the file is, the
 * file rendered as Markdown, and a pencil that turns it into a plain text editor.
 */
@Composable
internal fun OrbitFilePage(
    title: String,
    note: String,
    load: suspend () -> String,
    save: suspend (String) -> Unit,
    onBack: () -> Unit,
    actions: List<OrbitFileAction> = emptyList(),
    maxChars: Int = OrbitPromptFiles.MAX_CHARS,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var content by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        failed = false
        content = try { load() } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true; null }
    }
    fun toast(id: Int) = android.widget.Toast.makeText(context, context.getString(id), android.widget.Toast.LENGTH_SHORT).show()
    fun leaveEditor() { editing = false; draft = "" }
    fun back() {
        when {
            editing && draft != content -> confirmDiscard = true
            editing -> leaveEditor()
            else -> onBack()
        }
    }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(R.string.orbit_file_save_failed) }
            finally { busy = false }
        }
    }
    BackHandler { back() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrbitCircleButton(icon = orbitIcon("chevron-left"), contentDescription = stringResource(R.string.back), onClick = ::back)
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                shadowElevation = 3.dp,
            ) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (editing) {
                        IconButton(enabled = !busy, onClick = {
                            val body = draft
                            run {
                                save(body)
                                content = load()
                                leaveEditor()
                            }
                        }) { Icon(orbitIcon("check"), stringResource(R.string.orbit_file_save)) }
                    } else {
                        IconButton(enabled = content != null, onClick = {
                            draft = content.orEmpty()
                            editing = true
                        }) { Icon(orbitIcon("square-pen"), stringResource(R.string.orbit_file_edit)) }
                    }
                    if (actions.isNotEmpty() || editing) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(orbitIcon("ellipsis"), stringResource(R.string.orbit_file_more))
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                if (editing) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.orbit_file_discard)) },
                                        leadingIcon = { Icon(orbitIcon("x"), null) },
                                        onClick = { showMenu = false; leaveEditor() },
                                    )
                                }
                                actions.forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(action.label) },
                                        leadingIcon = { Icon(orbitIcon(action.icon), null) },
                                        onClick = {
                                            showMenu = false
                                            run {
                                                action.run()
                                                leaveEditor()
                                                reload++
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        when {
            failed -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.orbit_file_load_failed))
                TextButton(onClick = { reload++ }) { Text(stringResource(R.string.orbit_memory_retry)) }
            }
            content == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            editing -> OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(maxChars) },
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxSize().imePadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 40.dp),
            ) {
                item(key = "note") {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 20.dp)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                        Text(
                            note,
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                }
                item(key = "body") {
                    StreamMarkdownRenderer(
                        content = content.orEmpty(),
                        backgroundColor = MaterialTheme.colorScheme.background,
                        fillMaxWidth = true,
                    )
                }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.orbit_file_discard_title)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; leaveEditor() }) { Text(stringResource(R.string.orbit_file_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.orbit_file_keep_editing)) } },
        )
    }
}

@Composable
internal fun OrbitIdentityFilePage(orbitId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    OrbitFilePage(
        title = OrbitPromptFiles.IDENTITY_FILE,
        note = stringResource(R.string.orbit_identity_note),
        load = { OrbitPromptFiles.identity(context, orbitId) },
        save = { OrbitPromptFiles.saveIdentity(context, orbitId, it) },
        onBack = onBack,
    )
}

@Composable
internal fun OrbitPromptFilePage(orbitId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    OrbitFilePage(
        title = OrbitPromptFiles.PROMPT_FILE,
        note = stringResource(R.string.orbit_prompt_note),
        load = { OrbitPromptFiles.prompt(context, orbitId) },
        save = { OrbitPromptFiles.savePrompt(context, orbitId, it) },
        onBack = onBack,
        actions = listOf(OrbitFileAction(stringResource(R.string.orbit_file_reset_default), "rotate-ccw") {
            OrbitPromptFiles.resetPrompt(context, orbitId)
        }),
    )
}

/**
 * Orbit's own user.md. User edits go through [UserProfileDocumentRepository.saveUserEdit], the
 * same reviewed path as the memory settings, and are refused if Orbit changed the file meanwhile.
 */
@Composable
internal fun OrbitUserFilePage(orbit: OrbitProfile, onBack: () -> Unit) {
    val context = LocalContext.current
    var loaded by remember(orbit.id) { mutableStateOf<String?>(null) }
    suspend fun repository(): UserProfileDocumentRepository {
        val spaceId = com.ai.assistance.operit.features.orbit.OrbitMemoryScope.ensureInitialized(context, orbit)
        return UserProfileDocumentRepository.forMemorySpace(context, spaceId)
    }
    OrbitFilePage(
        title = UserProfileDocumentRepository.USER_FILE_NAME,
        note = stringResource(R.string.orbit_user_note),
        load = { repository().load().also { loaded = it } },
        save = { body ->
            val expected = checkNotNull(loaded)
            if (body != expected) repository().saveUserEdit(body, expected)
        },
        onBack = onBack,
        maxChars = UserProfileDocumentRepository.MAX_CONTENT_CHARS,
    )
}

@Composable
internal fun OrbitSoulFilePage(orbitId: String, onBack: () -> Unit, onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(orbitId) { OrbitSoulRepository(context, orbitId) }
    val reason = stringResource(R.string.orbit_soul_user_edit)
    OrbitFilePage(
        title = "SOUL.md",
        note = stringResource(R.string.orbit_soul_note),
        load = { repository.snapshot().current.body },
        save = { body ->
            val current = repository.snapshot().current
            if (body != current.body) repository.update(body, reason, current.id)
        },
        onBack = onBack,
        actions = listOf(OrbitFileAction(stringResource(R.string.orbit_file_history), "history") { onOpenHistory() }),
    )
}

private class LayerInfo(
    val layers: Map<OrbitPromptFiles.Layer, Boolean>,
    val promptBytes: Long,
    val promptCustom: Boolean,
    val soulBytes: Long,
    val identityBytes: Long,
    val userBytes: Long?,
)

/**
 * What goes into Orbit's system prompt, in order, like Codex's prompt layers page: each layer
 * with its size, a switch where it can be left out, and a tap to read or edit it.
 */
@Composable
internal fun OrbitPromptLayersPage(
    orbit: OrbitProfile,
    onBack: () -> Unit,
    onOpenPrompt: () -> Unit,
    onOpenSoul: () -> Unit,
    onOpenIdentity: () -> Unit,
    onOpenUser: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val info by produceState<LayerInfo?>(null, orbit.id, reload) {
        value = runCatching {
            fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size.toLong()
            LayerInfo(
                layers = OrbitPromptFiles.layers(context, orbit.id),
                promptBytes = bytes(OrbitPromptFiles.prompt(context, orbit.id)),
                promptCustom = OrbitPromptFiles.isPromptCustom(context, orbit.id),
                soulBytes = bytes(OrbitPreferences.getInstance(context).readSoul(orbit.id)),
                identityBytes = bytes(OrbitPromptFiles.identity(context, orbit.id)),
                userBytes = orbit.memorySpaceId?.let {
                    bytes(UserProfileDocumentRepository.forMemorySpace(context, it).load())
                },
            )
        }.getOrNull()
    }
    BackHandler(onBack = onBack)
    fun toggle(layer: OrbitPromptFiles.Layer, enabled: Boolean) {
        scope.launch {
            runCatching { OrbitPromptFiles.setLayer(context, orbit.id, layer, enabled) }
            reload++
        }
    }
    fun size(bytes: Long?) = bytes?.let { Formatter.formatShortFileSize(context, it) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrbitCircleButton(icon = orbitIcon("chevron-left"), contentDescription = stringResource(R.string.back), onClick = onBack)
            Text(
                stringResource(R.string.orbit_prompt_layers),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
        val current = info
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
        ) {
            item(key = "help") {
                Text(
                    stringResource(R.string.orbit_prompt_layers_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 14.dp),
                )
            }
            item(key = "layers") {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                    Column {
                        val layers = current.layers
                        LayerRow(1, stringResource(R.string.orbit_layer_base), OrbitPromptFiles.PROMPT_FILE,
                            stringResource(R.string.orbit_layer_base_desc) +
                                if (current.promptCustom) " · " + stringResource(R.string.orbit_layer_custom) else "",
                            size(current.promptBytes), layers[OrbitPromptFiles.Layer.BASE] == true,
                            { toggle(OrbitPromptFiles.Layer.BASE, it) }, onOpenPrompt)
                        HorizontalDivider(Modifier.padding(start = 52.dp))
                        LayerRow(2, stringResource(R.string.orbit_layer_soul), "SOUL.md",
                            stringResource(R.string.orbit_layer_soul_desc), size(current.soulBytes), null, null, onOpenSoul)
                        HorizontalDivider(Modifier.padding(start = 52.dp))
                        LayerRow(3, stringResource(R.string.orbit_layer_identity), OrbitPromptFiles.IDENTITY_FILE,
                            stringResource(R.string.orbit_layer_identity_desc), size(current.identityBytes),
                            layers[OrbitPromptFiles.Layer.IDENTITY] == true,
                            { toggle(OrbitPromptFiles.Layer.IDENTITY, it) }, onOpenIdentity)
                        HorizontalDivider(Modifier.padding(start = 52.dp))
                        LayerRow(4, stringResource(R.string.orbit_layer_user), "user.md",
                            stringResource(R.string.orbit_layer_user_desc), size(current.userBytes),
                            layers[OrbitPromptFiles.Layer.USER] == true,
                            { toggle(OrbitPromptFiles.Layer.USER, it) }, onOpenUser)
                        HorizontalDivider(Modifier.padding(start = 52.dp))
                        LayerRow(5, stringResource(R.string.orbit_layer_runtime), null,
                            stringResource(R.string.orbit_layer_runtime_desc), null, null, null, null)
                        HorizontalDivider(Modifier.padding(start = 52.dp))
                        LayerRow(6, stringResource(R.string.orbit_layer_tools), null,
                            stringResource(R.string.orbit_layer_tools_desc), null, null, null, null)
                    }
                }
            }
        }
    }
}

/** [checked] null means the layer is always included and has no switch. */
@Composable
private fun LayerRow(
    index: Int,
    title: String,
    file: String?,
    description: String,
    size: String?,
    checked: Boolean?,
    onToggle: ((Boolean) -> Unit)?,
    onClick: (() -> Unit)?,
) {
    val included = checked != false
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // An accent bar marks the layers that go into the prompt, as on Codex's page.
        Box(
            Modifier.width(4.dp).fillMaxHeight().padding(vertical = 12.dp)
                .background(if (included) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background.copy(alpha = 0f), RoundedCornerShape(2.dp)),
        )
        Text(
            index.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp).padding(start = 14.dp),
        )
        Column(Modifier.weight(1f).padding(vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (file != null) {
                    Text(
                        file,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp, end = 14.dp)) {
            if (checked != null && onToggle != null) {
                Switch(checked = checked, onCheckedChange = onToggle)
            } else {
                Text(
                    stringResource(R.string.orbit_layer_always),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (size != null) {
                Text(size, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
