package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.features.orbit.OrbitFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private fun toast(context: android.content.Context, id: Int) =
    android.widget.Toast.makeText(context, context.getString(id), android.widget.Toast.LENGTH_SHORT).show()

/** Deleting is permanent, so the menu item asks first, naming the file. */
@Composable
private fun deleteAction(path: String, run: suspend () -> Unit): OrbitFileAction {
    val context = LocalContext.current
    return OrbitFileAction(
        stringResource(R.string.orbit_file_delete), "trash-2",
        confirm = stringResource(R.string.orbit_file_delete_title) to
            (friendlyName(path) + "\n" + stringResource(R.string.orbit_file_delete_text)),
    ) {
        run()
        toast(context, R.string.orbit_file_deleted)
    }
}

/** A page frame like the profile's: round back button, title, then a list. */
@Composable
private fun OrbitListPage(
    title: String,
    onBack: () -> Unit,
    interceptBack: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    OrbitPageBack(intercept = interceptBack, onBack = onBack)
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                OrbitCircleButton(orbitIcon("chevron-left"), stringResource(R.string.back), onBack)
                Spacer(Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        content()
    }
}

@Composable
private fun SectionEmpty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun LoadFailed(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.orbit_file_load_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.orbit_memory_retry)) }
    }
}

private data class Notebook(val path: String, val title: Int, val note: Int, val icon: String)

private val notebooks = listOf(
    Notebook(OrbitFiles.LESSONS, R.string.orbit_file_lessons, R.string.orbit_file_lessons_note, "notebook-pen"),
    Notebook(OrbitFiles.PROACTIVE, R.string.orbit_file_proactive, R.string.orbit_file_proactive_note, "bell-ring"),
    Notebook(OrbitFiles.TIMEZONES, R.string.orbit_file_timezones, R.string.orbit_file_timezones_note, "globe"),
    Notebook(OrbitFiles.ALIGNMENT, R.string.orbit_file_alignment, R.string.orbit_file_alignment_note, "compass"),
    Notebook(OrbitFiles.DEVICES, R.string.orbit_file_devices, R.string.orbit_file_devices_note, "monitor-smartphone"),
)

private val DAILY = Regex("memory/\\d{4}-\\d{2}-\\d{2}\\.md")

/** A name a person understands for one of Orbit's files, e.g. "Person note · Alex". */
@Composable
private fun friendlyName(path: String): String {
    val name = path.substringAfterLast('/')
    notebooks.firstOrNull { it.path == path }?.let { return stringResource(it.title) }
    return when {
        path.startsWith("memory/people/") -> stringResource(R.string.orbit_file_kind_person, name.removeSuffix(".md"))
        path.startsWith("memory/groups/") -> stringResource(R.string.orbit_file_kind_group, name.removeSuffix(".md"))
        DAILY.matches(path) -> stringResource(R.string.orbit_file_kind_daily, name.removeSuffix(".md"))
        else -> stringResource(R.string.orbit_file_kind_file, name)
    }
}

/** Orbit's own files: notebooks, people, groups, the daily log and files it made. */
@Composable
internal fun OrbitFilesPage(orbitId: String, onBack: () -> Unit, onDraft: (String) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val files = remember(orbitId) { OrbitFiles.of(context, orbitId) }
    val who = rememberOrbitName(orbitId)
    var reload by remember { mutableIntStateOf(0) }
    val result by produceState<Result<List<OrbitFiles.Entry>>?>(null, orbitId, reload) {
        value = try { Result.success(files.list()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    var openPath by rememberSaveable(orbitId) { mutableStateOf<String?>(null) }
    // The one file to open straight in the editor: a person or group just created.
    var editPath by rememberSaveable(orbitId) { mutableStateOf<String?>(null) }
    var showAllDays by rememberSaveable(orbitId) { mutableStateOf(false) }
    var fileActions by remember { mutableStateOf<OrbitFiles.Entry?>(null) }
    var previewFile by remember { mutableStateOf<java.io.File?>(null) }
    val list = result?.getOrNull()
    val days = remember(list) { list.orEmpty().filter { DAILY.matches(it.path) }.sortedByDescending { it.path } }

    val closeFile: () -> Unit = { openPath = null; editPath = null; reload++ }
    val detail: @Composable (String) -> Unit = { path ->
        if (path == "daily") {
            OrbitListPage(stringResource(R.string.orbit_files_daily), onBack = { showAllDays = false }) {
                when {
                    result == null -> item(key = "loading") {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 24.dp))
                    }
                    list == null -> item(key = "failed") { LoadFailed { reload++ } }
                    days.isEmpty() -> item(key = "empty") { SectionEmpty(stringResource(R.string.orbit_files_daily_empty)) }
                    else -> items(days, key = { it.path }) { entry ->
                        OrbitListRow(orbitIcon("calendar-days"), entry.path.substringAfterLast('/').removeSuffix(".md"),
                            null, null, onClick = { openPath = entry.path }, showChevron = true)
                    }
                }
            }
        } else if (path.startsWith("files/")) {
            OrbitMadeFilePage(files, path, who, onBack = closeFile)
        } else {
        val notebook = notebooks.firstOrNull { it.path == path }
        val title = notebook?.let { stringResource(it.title) } ?: path.substringAfterLast('/').removeSuffix(".md")
        // A notebook that is still its empty template has nothing to delete.
        val exists = list?.firstOrNull { it.path == path }?.let { it.size >= 0 } ?: (notebook == null)
        OrbitEditableOrReader(files, path, title, closeFile, onDelete = if (exists) ({ files.delete(path) }) else null) {
        val note = when {
            notebook != null -> stringResource(notebook.note, who)
            path.startsWith("memory/people/") -> stringResource(R.string.orbit_file_person_note, who)
            path.startsWith("memory/groups/") -> stringResource(R.string.orbit_file_group_note, who)
            else -> stringResource(R.string.orbit_file_daily_note, who)
        }
        val editor = rememberOrbitFileEditor(files, path)
        OrbitFilePage(
            title = title,
            note = note,
            load = editor.load,
            save = editor.save,
            onEditStart = editor.onEditStart,
            onBack = closeFile,
            actions = if (!exists) emptyList() else listOf(deleteAction(path) {
                files.delete(path)
                openPath = null
                editPath = null
                reload++
            }),
            maxChars = editor.maxChars,
            measure = editor.measure,
            holdAtLimit = editor.holdAtLimit,
            startEditing = editPath == path,
        )
        }
        }
    }
    OrbitPushHost(
        entries = listOfNotNull(if (showAllDays) "daily" else null, openPath),
        onPop = { if (openPath != null) closeFile() else showAllDays = false },
        base = {
    OrbitListPage(stringResource(R.string.orbit_files_title), onBack) {
        item(key = "help") {
            Text(stringResource(R.string.orbit_files_help, who), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(key = "notebooks") { SectionTitle(stringResource(R.string.orbit_files_notebooks)) }
        items(notebooks, key = { it.path }) { book ->
            val entry = list?.firstOrNull { it.path == book.path }
            OrbitListRow(orbitIcon(book.icon), stringResource(book.title), stringResource(book.note, who).substringBefore("。").substringBefore(". "),
                entry?.takeIf { it.size >= 0 }?.let { shortTime(it.modifiedAt) }, onClick = { openPath = book.path }, showChevron = true)
        }
        if (result == null) {
            item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 24.dp)) }
            return@OrbitListPage
        }
        if (list == null) {
            item(key = "failed") { LoadFailed { reload++ } }
            return@OrbitListPage
        }
        listOf(
            Triple("memory/people", R.string.orbit_files_people, R.string.orbit_files_people_empty),
            Triple("memory/groups", R.string.orbit_files_groups, R.string.orbit_files_groups_empty),
        ).forEach { (folder, title, empty) ->
            item(key = "title-$folder") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle(stringResource(title)) }
                    // A person or group is added by asking Orbit; the prompt waits in the chat box.
                    TextButton(onClick = {
                        onDraft(resources.getString(if (folder.endsWith("people")) R.string.orbit_person_new_draft else R.string.orbit_group_new_draft))
                    }, modifier = Modifier.padding(top = 14.dp)) {
                        Text(stringResource(R.string.orbit_files_create))
                    }
                }
            }
            val inFolder = list.filter { it.path.startsWith("$folder/") && !it.path.endsWith("/INDEX.md") }
            if (inFolder.isEmpty()) item(key = "empty-$folder") { SectionEmpty(stringResource(empty, who)) }
            items(inFolder, key = { it.path }) { entry ->
                OrbitListRow(orbitIcon(if (folder.endsWith("people")) "user-round" else "users-round"), entry.title,
                    null, shortTime(entry.modifiedAt), onClick = { openPath = entry.path }, showChevron = true)
            }
        }
        item(key = "daily-title") { SectionTitle(stringResource(R.string.orbit_files_daily)) }
        if (days.isEmpty()) item(key = "daily-empty") { SectionEmpty(stringResource(R.string.orbit_files_daily_empty)) }
        items(days.take(3), key = { it.path }) { entry ->
            OrbitListRow(orbitIcon("calendar-days"), entry.path.substringAfterLast('/').removeSuffix(".md"), null, null,
                onClick = { openPath = entry.path }, showChevron = true)
        }
        if (days.size > 3) item(key = "daily-more") {
            TextButton(onClick = { showAllDays = true }) { Text(stringResource(R.string.orbit_files_show_all)) }
        }
        item(key = "made-title") { SectionTitle(stringResource(R.string.orbit_files_made, who)) }
        val made = list.filter { it.path.startsWith("files/") }.sortedByDescending { it.modifiedAt }
        if (made.isEmpty()) item(key = "made-empty") { SectionEmpty(stringResource(R.string.orbit_builds_files_empty, who)) }
        items(made, key = { it.path }) { entry ->
            OrbitMadeFileRow(entry) {
                if (OrbitFiles.isText(entry.path)) openPath = entry.path else fileActions = entry
            }
        }
    }

    fileActions?.let { entry ->
        OrbitMadeFileActions(files, entry, onDismiss = { fileActions = null }, onDeleted = { reload++ },
            onPreview = { previewFile = it })
    }
    previewFile?.let { file -> OrbitMediaPreview.Screen(file) { previewFile = null } }
        },
        page = detail,
    )
}

/** One file Orbit made for the user, as a list row. */
@Composable
internal fun OrbitMadeFileRow(entry: OrbitFiles.Entry, onClick: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val name = entry.path.substringAfterLast('/')
    OrbitListRow(
        icon = orbitIcon(orbitFileIcon(name)),
        title = name,
        detail = entry.path.removePrefix("files/").substringBeforeLast('/', "").ifEmpty { null },
        meta = shortTime(entry.modifiedAt) + " · " + android.text.format.Formatter.formatShortFileSize(context, entry.size),
        onClick = onClick,
        showChevron = true,
    )
}

/**
 * Loading and saving for the user's edit of one of Orbit's files. The save starts from the text
 * the page opened with, so it is refused (and nothing is lost on disk) when Orbit or a background
 * task changed the file while the user was editing.
 */
internal class OrbitFileEditor(
    val load: suspend () -> String,
    val save: suspend (String) -> Unit,
    /** Pass to [OrbitFilePage]: the text a new draft starts from becomes the base its save checks. */
    val onEditStart: () -> Unit,
    /** Pass to [OrbitFilePage] as its maxChars. */
    val maxChars: Int,
    /**
     * Pass to [OrbitFilePage]: for a notebook every chat carries in full, a book written before its
     * size limit is kept whole in the editor and may be saved as long as it does not grow. For any
     * other file, its size in UTF-8 bytes, so a draft too big for the editor is refused before saving.
     */
    val measure: (String) -> Int,
    /** Pass to [OrbitFilePage]: a notebook every chat carries refuses input that would grow it past its limit. */
    val holdAtLimit: Boolean,
)

@Composable
internal fun rememberOrbitFileEditor(files: OrbitFiles, path: String): OrbitFileEditor {
    // The draft is restored after the app is rebuilt, so the text it started from is too; a fresh
    // read on restore must not become the base, or an old draft would overwrite a newer file.
    val base = rememberSaveable(path) { mutableStateOf<String?>(null) }
    return remember(files, path) {
        var loaded: String? = null
        val cap = OrbitFiles.promptCap(path)
        OrbitFileEditor(
            // Read once, strictly: a file that became too big or not UTF-8 since it was opened fails
            // to load here rather than reaching the editor, where saving would overwrite it.
            load = { files.readForEditor(path).also { loaded = it } },
            save = { text ->
                files.writeIfUnchanged(path, checkNotNull(base.value) { "The file was not opened" }, text, strict = true)
                base.value = text
            },
            onEditStart = { base.value = loaded },
            maxChars = cap ?: com.ai.assistance.operit.features.orbit.OrbitTextFiles.EDIT_MAX_BYTES,
            measure = if (cap != null) { text: String -> if (text.length <= (loaded?.length ?: 0)) 0 else text.length }
            else { text: String ->
                val max = com.ai.assistance.operit.features.orbit.OrbitTextFiles.EDIT_MAX_BYTES
                // At most 3 bytes a character: a short text needs no counting, a long one is over anyway.
                if (text.length * 3 <= max || text.length > max) text.length else text.toByteArray(Charsets.UTF_8).size
            },
            // Every file the editor opens fits its limit, so input that would grow it past that is not taken.
            holdAtLimit = true,
        )
    }
}

/** A text file Orbit made for the user: read, edit, share or delete. */
@Composable
internal fun OrbitMadeFilePage(files: OrbitFiles, path: String, who: String, onBack: () -> Unit, interceptBack: Boolean = false) {
    OrbitEditableOrReader(files, path, path.substringAfterLast('/'), onBack, onDelete = { files.delete(path) }) {
        OrbitMadeFileEditorPage(files, path, who, onBack, interceptBack)
    }
}

/**
 * Opens [path] with [editor] when it is small UTF-8 text. A bigger file, or one in another encoding
 * (a novel saved as GBK), opens read-only a part at a time: the editor would hold all of it, and
 * saving text it decoded wrongly would overwrite the file with garbled characters.
 */
@Composable
internal fun OrbitEditableOrReader(
    files: OrbitFiles,
    path: String,
    title: String,
    onBack: () -> Unit,
    /** Where the editor page offered delete, the reader does too; the reader closes after it. */
    onDelete: (suspend () -> Unit)? = null,
    editor: @Composable () -> Unit,
) {
    // Null while deciding; then the file to read, or null in the result for the editor. Only a file
    // that reads whole as UTF-8 goes to the editor; one that cannot be checked is only read.
    val decided by androidx.compose.runtime.produceState<Result<java.io.File?>?>(null, files, path) {
        value = Result.success(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val file = runCatching { files.fileFor(path) }.getOrNull()
            when {
                // Not there yet (a notebook's template) or not one of Orbit's files: the editor says so.
                file == null || !file.isFile -> null
                runCatching { com.ai.assistance.operit.features.orbit.OrbitTextFiles.editable(file) }.getOrDefault(false) -> null
                else -> file
            }
        })
    }
    when (val d = decided) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // A spinner only for a slow check, so a page never flashes one.
            var slow by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(400); slow = true }
            if (slow) androidx.compose.material3.CircularProgressIndicator()
        }
        else -> d.getOrNull()?.let { file ->
            // A web page too big to edit shows its source too, with the same note and delete.
            OrbitTextPreview.Screen(
                file, onBack, title = title,
                notice = stringResource(R.string.orbit_file_reader_only_note), onDelete = onDelete,
            )
        } ?: editor()
    }
}

@Composable
private fun OrbitMadeFileEditorPage(files: OrbitFiles, path: String, who: String, onBack: () -> Unit, interceptBack: Boolean) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val editor = rememberOrbitFileEditor(files, path)
    val isPage = OrbitWebPreview.canShow(path)
    // The page is drawn inside the app: phone browsers often cannot open a file kept inside it.
    var previewPage by remember(path) { mutableStateOf<java.io.File?>(null) }
    val scope = rememberCoroutineScope()
    OrbitFilePage(
        title = path.substringAfterLast('/'),
        note = stringResource(R.string.orbit_user_file_note, who),
        load = editor.load,
        save = editor.save,
        onEditStart = editor.onEditStart,
        onBack = onBack,
        interceptBack = interceptBack,
        actions = listOfNotNull(
            // The editor only shows a web page's source; the preview shows the page itself.
            if (isPage) {
                OrbitFileAction(stringResource(R.string.orbit_file_preview_web), "globe", endsEditing = false) {
                    scope.launch {
                        val file = runCatching {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { files.fileFor(path) }
                        }.getOrNull()
                        if (file != null && file.isFile) previewPage = file else toast(context, R.string.orbit_file_open_failed)
                    }
                }
            } else null,
            OrbitFileAction(stringResource(R.string.orbit_file_share), "share-2", endsEditing = false) {
                shareOrbitFile(context) { files.fileFor(path) }
            },
            deleteAction(path) {
                files.delete(path)
                onBack()
            },
        ),
        maxChars = editor.maxChars,
        measure = editor.measure,
        holdAtLimit = editor.holdAtLimit,
    )
    previewPage?.let { file -> OrbitWebPreview.Screen(file) { previewPage = null } }
}

/** Open, share or delete a file Orbit made that is not text, such as a picture or a voice message. */
@Composable
internal fun OrbitMadeFileActions(
    files: OrbitFiles,
    entry: OrbitFiles.Entry,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
    pinned: Boolean? = null,
    onTogglePin: () -> Unit = {},
    onPreview: ((java.io.File) -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // Deleting is permanent, so the menu turns into a question first.
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (confirmDelete) {
        OrbitDialog(
            onDismissRequest = { if (!deleting) onDismiss() },
            dismissEnabled = !deleting,
            title = stringResource(R.string.orbit_file_delete_title),
            text = { Text(entry.path.substringAfterLast('/') + "\n" + stringResource(R.string.orbit_file_delete_text)) },
            confirmButton = {
                OrbitDialogAction(enabled = !deleting, destructive = true, onClick = {
                    deleting = true
                    scope.launch {
                        try {
                            files.delete(entry.path)
                            toast(context, R.string.orbit_file_deleted)
                        } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            toast(context, R.string.orbit_file_action_failed)
                        }
                        onDismiss()
                        onDeleted()
                    }
                }) { Text(stringResource(R.string.orbit_file_delete)) }
            },
            dismissButton = {
                OrbitDialogAction(enabled = !deleting, onClick = onDismiss, secondary = true) { Text(stringResource(android.R.string.cancel)) }
            },
        )
        return
    }
    OrbitDialog(
        onDismissRequest = onDismiss,
        title = entry.path.substringAfterLast('/'),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pinned != null) {
                    OrbitDialogAction(onClick = { onDismiss(); onTogglePin() }, secondary = true) {
                        Text(stringResource(if (pinned) R.string.orbit_library_unpin else R.string.orbit_library_pin), Modifier.fillMaxWidth())
                    }
                }
                OrbitDialogAction(onClick = {
                    onDismiss()
                    val file = runCatching { files.fileFor(entry.path) }.getOrNull()
                    if (onPreview != null && file != null && file.isFile && OrbitMediaPreview.canPreview(file.name)) onPreview(file)
                    else openOrbitFile(context) { files.fileFor(entry.path) }
                }, secondary = true) {
                    Text(stringResource(R.string.orbit_file_open), Modifier.fillMaxWidth())
                }
                OrbitDialogAction(onClick = { onDismiss(); shareOrbitFile(context) { files.fileFor(entry.path) } }, secondary = true) {
                    Text(stringResource(R.string.orbit_file_share), Modifier.fillMaxWidth())
                }
                OrbitDialogAction(onClick = { confirmDelete = true }, destructive = true) {
                    Text(stringResource(R.string.orbit_file_delete), Modifier.fillMaxWidth())
                }
            }
        },
        dismissButton = { OrbitDialogAction(onClick = onDismiss, secondary = true) { Text(stringResource(android.R.string.cancel)) } },
    )
}

internal fun orbitFileIcon(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "png", "jpg", "jpeg", "webp", "gif" -> "image"
    "mp3", "wav", "m4a", "ogg", "aac", "flac", "opus", "oga" -> "audio-lines"
    "mp4", "webm", "mov" -> "film"
    "pdf" -> "file-type"
    "doc", "docx" -> "file-pen-line"
    "html", "htm" -> "app-window"
    "json", "csv", "xml", "yaml", "yml" -> "file-code"
    else -> "file-text"
}

private fun orbitFileUri(context: android.content.Context, file: java.io.File) =
    androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

private fun mimeOf(file: java.io.File) =
    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

/** Opens one of Orbit's files in another app. */
internal fun openOrbitFile(context: android.content.Context, fileOf: () -> java.io.File) {
    try {
        val file = fileOf()
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(orbitFileUri(context, file), mimeOf(file))
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        // This app takes any file too ("open with"); opening one here would only bring it back as a
        // share. When the phone would ask or pick this app, the choice leaves this app out.
        val pm = context.packageManager
        val own = context.packageName
        val chosen = pm.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        // "android" / the intent resolver: no default yet, the phone would ask, listing this app.
        if (chosen == own || chosen == "android" || chosen?.contains("intentresolver") == true) {
            val handlers = pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            if (handlers.none { it.activityInfo.packageName != own }) throw android.content.ActivityNotFoundException()
            val mine = handlers.filter { it.activityInfo.packageName == own }
                .map { android.content.ComponentName(own, it.activityInfo.name) }.toTypedArray()
            context.startActivity(android.content.Intent.createChooser(intent, file.name)
                .putExtra(android.content.Intent.EXTRA_EXCLUDE_COMPONENTS, mine)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            // Started directly so a phone with no app for this kind of file reports it to us.
            context.startActivity(intent)
        }
    } catch (e: android.content.ActivityNotFoundException) {
        toast(context, R.string.orbit_file_no_app)
    } catch (e: Exception) {
        toast(context, R.string.orbit_file_open_failed)
    }
}

/** Shares one of Orbit's files through the system share sheet. */
internal fun shareOrbitFile(context: android.content.Context, fileOf: () -> java.io.File) {
    try {
        val file = fileOf()
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType(mimeOf(file))
            .putExtra(android.content.Intent.EXTRA_STREAM, orbitFileUri(context, file))
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(android.content.Intent.createChooser(intent, file.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(context, R.string.orbit_file_share_failed)
    }
}

private fun shortTime(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
