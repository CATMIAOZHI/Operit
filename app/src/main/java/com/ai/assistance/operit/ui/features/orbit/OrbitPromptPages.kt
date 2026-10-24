package com.ai.assistance.operit.ui.features.orbit

import android.text.format.Formatter
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
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
    /** Null for a read-only file: no pencil, no editor. */
    save: (suspend (String) -> Unit)?,
    onBack: () -> Unit,
    actions: List<OrbitFileAction> = emptyList(),
    maxChars: Int = OrbitPromptFiles.MAX_CHARS,
    startEditing: Boolean = false,
    /** For a page inside another page (a file in the files list): back returns to that page. */
    interceptBack: Boolean = false,
    /** Captures the revision behind a newly opened draft; not called when restoring that draft. */
    onEditStart: () -> Unit = {},
    /**
     * The stored length of a draft, when saving changes it (memory.md adds its marker lines back).
     * With it a long draft is kept whole and only its saving is refused, so nothing is cut off.
     */
    measure: ((String) -> Int)? = null,
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
    var autoEdited by rememberSaveable { mutableStateOf(false) }
    var warnedTooLong by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        failed = false
        content = try { load() } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true; null }
        if (startEditing && !autoEdited) {
            content?.let { onEditStart(); draft = it; editing = true; autoEdited = true }
        }
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
    fun run(failedMessage: Int = R.string.orbit_file_save_failed, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(failedMessage) }
            finally { busy = false }
        }
    }
    OrbitPageBack(intercept = editing || interceptBack) { back() }

    // The empty tap handler keeps taps on the title or blank areas from reaching what is underneath.
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) { detectTapGestures() }
            .statusBarsPadding(),
    ) {
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
                            if (measure != null && measure(body) > maxChars) toast(R.string.orbit_file_too_long)
                            else run {
                                checkNotNull(save)(body)
                                content = load()
                                leaveEditor()
                            }
                        }) { Icon(orbitIcon("check"), stringResource(R.string.orbit_file_save)) }
                    } else if (save != null) {
                        IconButton(enabled = content != null, onClick = {
                            onEditStart()
                            draft = content.orEmpty()
                            editing = true
                        }) { Icon(orbitIcon("square-pen"), stringResource(R.string.orbit_file_edit)) }
                    } else {
                        // Read only: a lock in the pill where the pencil would be.
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Icon(orbitIcon("lock"), stringResource(R.string.orbit_file_read_only), Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
                                            run(R.string.orbit_trash_failed) {
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
            content == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Files load in a moment; a spinner only for a slow read, so a page never flashes one.
                var slow by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(400); slow = true }
                if (slow) CircularProgressIndicator()
            }
            editing -> OutlinedTextField(
                value = draft,
                onValueChange = {
                    val length = measure?.invoke(it) ?: it.length
                    if (length > maxChars && !warnedTooLong) {
                        warnedTooLong = true
                        toast(R.string.orbit_file_too_long)
                    }
                    draft = if (measure != null) it else it.take(maxChars)
                },
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
                    // Section markers in memory.md and user.md are for the app; the reader sees the headings.
                    val shown = remember(content) {
                        com.ai.assistance.operit.data.preferences.MemoryNoteSections.withoutMarkers(content.orEmpty())
                    }
                    StreamMarkdownRenderer(
                        content = shown,
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
        note = stringResource(R.string.orbit_identity_note, rememberOrbitName(orbitId)),
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
        save = null,
        onBack = onBack,
    )
}

/** The alignment synthesis, opened from the prompt layers; the nightly review rewrites it. */
@Composable
internal fun OrbitAlignmentFilePage(orbitId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val files = remember(orbitId) { com.ai.assistance.operit.features.orbit.OrbitFiles.of(context, orbitId) }
    OrbitFilePage(
        title = stringResource(R.string.orbit_file_alignment),
        note = stringResource(R.string.orbit_file_alignment_note, rememberOrbitName(orbitId)),
        load = { files.read(com.ai.assistance.operit.features.orbit.OrbitFiles.ALIGNMENT) },
        save = { files.write(com.ai.assistance.operit.features.orbit.OrbitFiles.ALIGNMENT, it) },
        onBack = onBack,
        maxChars = com.ai.assistance.operit.features.orbit.OrbitFiles.MAX_ALIGNMENT_PROMPT,
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
        note = stringResource(R.string.orbit_user_note, orbitName(orbit)),
        load = { repository().load().also { loaded = it } },
        save = { body ->
            val expected = checkNotNull(loaded)
            if (body != expected) repository().saveUserEdit(body, expected)
        },
        onBack = onBack,
        maxChars = UserProfileDocumentRepository.MAX_CONTENT_CHARS,
    )
}

/**
 * Orbit's own memory.md, opened straight from the Memory card. A user edit is saved through the
 * reviewed notes path like the memory settings, and is refused if Orbit changed the file meanwhile.
 */
@Composable
internal fun OrbitMemoryFilePage(orbit: OrbitProfile, onBack: () -> Unit) {
    val context = LocalContext.current
    val reason = stringResource(R.string.memory_review_manual_edit)
    var loaded by remember(orbit.id) {
        mutableStateOf<com.ai.assistance.operit.data.preferences.MemoryNotesRepository.Snapshot?>(null)
    }
    suspend fun spaceId() = com.ai.assistance.operit.features.orbit.OrbitMemoryScope.ensureInitialized(context, orbit)
    // The page shows and edits every section heading without the app's marker lines.
    suspend fun notes() = com.ai.assistance.operit.data.preferences.MemoryNotesRepository(context, spaceId())
    val english = remember { com.ai.assistance.operit.util.LocaleUtils.usesEnglishPrompt(context.applicationContext) }
    OrbitFilePage(
        title = "memory.md",
        note = stringResource(R.string.orbit_memory_note, orbitName(orbit)),
        load = {
            val repository = notes()
            repository.editorText(repository.load().also { loaded = it }.markdown)
        },
        save = { body ->
            val opened = checkNotNull(loaded)
            val repository = notes()
            if (body != repository.editorText(opened.markdown)) {
                val space = spaceId()
                val latest = repository.load()
                check(latest.version == opened.version) { "memory.md changed while editing" }
                val reviews = com.ai.assistance.operit.data.preferences.MemoryReviewRepository(context, space)
                val change = reviews.proposeNotes(latest, repository.fromEditor(body))
                check(reviews.decide(context, change.id, true, "user", reason).status == "approved") {
                    "memory.md changed while editing"
                }
            }
        },
        onBack = onBack,
        maxChars = com.ai.assistance.operit.data.preferences.MemoryNotesRepository.MAX_CHARS,
        // The editor's text has no marker lines; the limit applies to the text saving stores.
        measure = { text ->
            com.ai.assistance.operit.data.preferences.MemoryNoteSections.parse(text).markdown(english).length
        },
    )
}

@Composable
internal fun OrbitSoulFilePage(orbitId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(orbitId) { OrbitSoulRepository(context, orbitId) }
    var loaded by remember(orbitId) { mutableStateOf<OrbitSoulRepository.Version?>(null) }
    var editVersion by rememberSaveable(orbitId) { mutableStateOf<String?>(null) }
    val reason = stringResource(R.string.orbit_soul_user_edit)
    OrbitFilePage(
        title = "SOUL.md",
        note = stringResource(R.string.orbit_soul_note, rememberOrbitName(orbitId)),
        load = { repository.snapshot().current.also { loaded = it }.body },
        save = { body ->
            // The version the editor opened: a background update must not be overwritten by an
            // old draft. Save it alongside the draft through configuration changes as well.
            repository.update(body, reason, checkNotNull(editVersion))
        },
        onEditStart = { editVersion = checkNotNull(loaded).id },
        onBack = onBack,
    )
}
