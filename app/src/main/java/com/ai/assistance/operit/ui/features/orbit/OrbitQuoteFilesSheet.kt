package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitFiles
import com.ai.assistance.operit.features.orbit.OrbitTaskRepository
import com.ai.assistance.operit.features.orbit.OrbitUploads
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** One file the user can quote: its real place on disk, and what the row shows. */
private data class QuoteItem(
    val key: String,
    val name: String,
    val file: File,
    val time: Long,
    val size: Long,
    val directory: String = "",
    val work: Boolean = false,
    val seed: String = name,
)

/** The three kinds of file, in the order of the switch: works and made files, the Library, notes. */
private class QuoteLists(val made: List<QuoteItem>, val library: List<QuoteItem>, val notes: List<QuoteItem>) {
    operator fun get(section: Int) = when (section) { 0 -> made; 1 -> library; else -> notes }
    val all get() = made + library + notes
}

/**
 * Picks some of Orbit's own files to quote in the chat: what it made, the Library of what the user
 * sent, and its notes. Quoting attaches the files where they are, so only their names and places
 * reach Orbit, which reads them when it needs to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitQuoteFilesSheet(orbit: OrbitProfile, onDismiss: () -> Unit, onQuote: (List<File>) -> Unit) {
    val context = LocalContext.current
    val who = orbitName(orbit)
    var section by rememberSaveable(orbit.id) { mutableIntStateOf(0) }
    var query by rememberSaveable(orbit.id) { mutableStateOf("") }
    val chosen = remember(orbit.id) { mutableStateListOf<String>() }
    var reload by remember { mutableIntStateOf(0) }
    val lists by produceState<Result<QuoteLists>?>(null, orbit.id, reload) {
        value = try {
            Result.success(loadQuoteLists(context.applicationContext, orbit.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("OrbitQuoteFiles", "Could not list Orbit's files", e)
            Result.failure(e)
        }
    }
    OrbitModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).navigationBarsPadding().imePadding()) {
            val result = lists
            val loaded = result?.getOrNull()
            val all = loaded?.get(section).orEmpty()
            val shown = remember(all, query) {
                val q = query.trim()
                if (q.isEmpty()) all else all.filter {
                    it.name.contains(q, ignoreCase = true) || it.directory.contains(q, ignoreCase = true)
                }
            }
            // The header scrolls with the list, so with the keyboard up or large text the button below stays.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                item(key = "header") {
                    Column(Modifier.padding(horizontal = 4.dp)) {
                        Text(stringResource(R.string.orbit_quote_title, who), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.orbit_quote_hint, who),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )
                        SearchPill(query, { query = it }, stringResource(R.string.orbit_quote_search))
                        Spacer(Modifier.height(10.dp))
                        QuoteSections(section) { section = it }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                when {
                    result == null -> item(key = "loading") {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 16.dp))
                    }
                    loaded == null -> item(key = "failed") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.orbit_file_load_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { reload++ }) { Text(stringResource(R.string.orbit_memory_retry)) }
                        }
                    }
                    shown.isEmpty() -> item(key = "empty") {
                        Text(
                            stringResource(if (all.isEmpty()) R.string.orbit_quote_empty else R.string.orbit_quote_no_match),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 24.dp),
                        )
                    }
                    else -> items(shown, key = { it.key }) { item ->
                        QuoteRow(item, picked = item.key in chosen) {
                            if (item.key in chosen) chosen.remove(item.key) else chosen.add(item.key)
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val byKey = lists?.getOrNull()?.all.orEmpty().associateBy { it.key }
                    val files = chosen.mapNotNull { byKey[it]?.file }
                    onDismiss()
                    onQuote(files)
                },
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).height(52.dp),
            ) {
                Text(stringResource(R.string.orbit_quote_confirm, chosen.size))
            }
        }
    }
}

/** Reads the three lists on the IO thread, keeping only files that are really there. */
private suspend fun loadQuoteLists(context: android.content.Context, orbitId: String): QuoteLists = withContext(Dispatchers.IO) {
    val root = OrbitPreferences.getInstance(context).orbitDir(orbitId).canonicalFile
    // A work's newest version, the one its page opens on.
    val works = OrbitTaskRepository(context).dao.observeArtifacts(orbitId).first()
        .groupBy { it.artifactId }.values.map { group -> group.maxBy { it.createdAt } }
        .mapNotNull { v ->
            val file = runCatching { File(root, v.relativePath).canonicalFile }.getOrNull()
                ?.takeIf { it.toPath().startsWith(root.toPath()) && it.isFile } ?: return@mapNotNull null
            QuoteItem("work:" + v.artifactId, v.name, file, v.createdAt, file.length(), work = true, seed = v.artifactId)
        }
    val orbitFiles = OrbitFiles.of(context, orbitId)
    // Names only: the picker shows file names, so no file is opened to find its title.
    val entries = orbitFiles.list(titles = false).filter { it.size >= 0 }
    fun item(prefix: String, entry: OrbitFiles.Entry): QuoteItem? {
        val file = runCatching { orbitFiles.fileFor(entry.path) }.getOrNull()?.takeIf { it.isFile } ?: return null
        return QuoteItem(
            prefix + entry.path, entry.path.substringAfterLast('/'), file, entry.modifiedAt, entry.size,
            directory = entry.path.substringBeforeLast('/', ""),
        )
    }
    val (madeEntries, noteEntries) = entries.partition { it.path.startsWith("files/") }
    val made = (works + madeEntries.mapNotNull { item("file:", it) }).sortedByDescending { it.time }
    val notes = noteEntries.mapNotNull { item("note:", it) }.sortedByDescending { it.time }
    val library = OrbitUploads.list(context, orbitId).map { upload ->
        QuoteItem("upload:" + upload.file.absolutePath, upload.name, upload.file, upload.addedAt, upload.size)
    }.sortedByDescending { it.time }
    QuoteLists(made, library, notes)
}

@Composable
private fun QuoteSections(selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50), color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(4.dp)) {
            listOf(R.string.orbit_builds_made, R.string.orbit_builds_uploads, R.string.orbit_quote_notes).forEachIndexed { index, label ->
                val on = index == selected
                Surface(
                    onClick = { onSelect(index) },
                    shape = RoundedCornerShape(50),
                    color = if (on) colors.onSurface.copy(alpha = 0.07f) else Color.Transparent,
                    modifier = Modifier.weight(1f).height(40.dp).semantics { this.selected = on },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(label), style = MaterialTheme.typography.titleSmall,
                            color = if (on) colors.onSurface else colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteRow(item: QuoteItem, picked: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val isImage = !item.work && OrbitFiles.kindOf(item.name) == OrbitFiles.Kind.IMAGE
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .toggleable(value = picked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTile(item.name, image = item.file.takeIf { isImage }, imageExpected = isImage, work = item.work, seed = item.seed)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.directory.isNotEmpty()) {
                Text(
                    item.directory,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                (if (item.work) stringResource(R.string.orbit_builds_work) else typeLabel(item.name)) + " · " +
                    android.text.format.Formatter.formatShortFileSize(context, item.size) + " · " + shortDate(item.time),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(checked = picked, onCheckedChange = null)
    }
}
