package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiBlockIndex
import com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiOwner
import com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiPartOffset
import com.ai.assistance.operit.ui.common.markdown.LocalTranscriptMarkdownSlice
import com.ai.assistance.operit.ui.common.markdown.StreamMarkdownRenderer
import com.ai.assistance.operit.ui.features.chat.components.LocalOrbitChatLook
import com.ai.assistance.operit.ui.features.settings.components.LocalSettingsDialogStyle
import com.ai.assistance.operit.ui.features.settings.components.SettingsDialogStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Simple text files Orbit sends open right in the chat, with share and open-elsewhere at the top.
 * A Markdown file shows formatted; a button at the top switches to its plain text, to select and copy.
 */
internal object OrbitTextPreview {
    private val MARKDOWN_TYPES = setOf("md", "markdown")
    /** Read a part at a time: the next part loads as the reader nears the end of what is shown. */
    private const val PART_BYTES = 512 * 1024
    /**
     * Past this much shown, the rest is a tap away in another app, so memory stays bounded: enough
     * for a long novel. Markdown is kept twice (formatted and plain), so it stops sooner.
     */
    private const val MAX_SHOWN_BYTES = 16L * 1024 * 1024
    private const val MAX_SHOWN_MARKDOWN_BYTES = 4L * 1024 * 1024
    /** Lines drawn together, so a long file scrolls without building one huge text block. */
    private const val LINES_PER_BLOCK = 40
    /** A block never grows past this, so one very long line (minified JSON, logs) is split too. */
    private const val CHARS_PER_BLOCK = 6000

    private fun type(name: String) = name.substringAfterLast('.', "").lowercase()

    fun canShow(name: String): Boolean = com.ai.assistance.operit.features.orbit.OrbitTextFiles.isText(name)

    /** Why the text ends before the file does. */
    private enum class Stop { LIMIT, FAILED, CHANGED }

    /**
     * What is shown so far: [blocks] is the plain text; [pages] the same text as Markdown to format,
     * empty for other files. [next] is where the next part starts; [more] whether there is one;
     * [stop] why the rest is not shown. [stamp] is the file as reading began, so parts never mix versions.
     */
    private class Loaded(
        val blocks: List<String>, val pages: List<String>, val charset: java.nio.charset.Charset?,
        val next: Long, val more: Boolean, val stop: Stop?, val failed: Boolean,
        val stamp: com.ai.assistance.operit.features.orbit.OrbitTextFiles.Stamp? = null,
    ) {
        fun stopped(why: Stop) = Loaded(blocks, pages, charset, next, false, why, false, stamp)
    }

    private val FAILED = Loaded(emptyList(), emptyList(), null, 0, false, null, true)

    /** The first part of [file]; its encoding is told once, from its beginning. */
    private fun first(file: File, markdown: Boolean): Loaded = runCatching {
        val stamp = com.ai.assistance.operit.features.orbit.OrbitTextFiles.Stamp.of(file)
        val charset = com.ai.assistance.operit.features.orbit.OrbitTextFiles.charsetOf(file)
        append(Loaded(emptyList(), emptyList(), charset, 0, true, null, false, stamp), file, markdown)
    }.getOrElse { FAILED }

    /** [shown] with the next part of [file] added; a part that cannot be read ends the file there. */
    private fun append(shown: Loaded, file: File, markdown: Boolean): Loaded {
        val charset = shown.charset ?: return shown
        val limit = if (markdown) MAX_SHOWN_MARKDOWN_BYTES else MAX_SHOWN_BYTES
        // Room for at least a few characters, so the last part never ends in half of one.
        if (limit - shown.next < 64) return shown.stopped(Stop.LIMIT)
        val part = runCatching {
            val bytes = minOf(PART_BYTES.toLong(), limit - shown.next).toInt()
            com.ai.assistance.operit.features.orbit.OrbitTextFiles.part(file, shown.next, bytes, charset, shown.stamp)
        }.getOrElse {
            return when {
                shown.next == 0L -> FAILED
                it is com.ai.assistance.operit.features.orbit.OrbitTextFiles.Changed -> shown.stopped(Stop.CHANGED)
                else -> shown.stopped(Stop.FAILED)
            }
        }
        // A part ends with its line break; dropping it here keeps an empty line from appearing between parts.
        val text = if (part.more) part.text.removeSuffix("\n").removeSuffix("\r") else part.text
        return Loaded(
            blocks = shown.blocks + toBlocks(text),
            pages = if (markdown) shown.pages + markdownPages(text, first = shown.next == 0L) else emptyList(),
            charset = charset, next = part.next, more = part.more, stop = null, failed = false, stamp = shown.stamp,
        )
    }

    /**
     * The file as pages of Markdown, each small enough to format quickly. A data block at the very
     * top (`---` … `---`) is left out here; the plain text has it.
     */
    private fun markdownPages(text: String, first: Boolean): List<String> {
        val content = if (first) splitFrontMatter(text).second else text
        return artifactPages(content, markdown = true)
            .map { it.opens + content.substring(it.start, it.end) + it.closes }
            .filter { it.isNotBlank() }
    }

    private fun toBlocks(text: String): List<String> {
        val blocks = ArrayList<String>()
        val current = StringBuilder()
        var lines = 0
        fun flush() {
            if (lines > 0) blocks += current.toString()
            current.setLength(0)
            lines = 0
        }
        for (line in text.lines()) {
            var rest = line
            var split = false
            while (rest.length > CHARS_PER_BLOCK) {
                flush()
                var end = CHARS_PER_BLOCK
                if (Character.isHighSurrogate(rest[end - 1])) end--
                blocks += rest.substring(0, end)
                rest = rest.substring(end)
                split = true
            }
            if (split && rest.isEmpty()) continue
            if (lines >= LINES_PER_BLOCK || current.length + rest.length > CHARS_PER_BLOCK) flush()
            if (lines > 0) current.append('\n')
            current.append(rest)
            lines++
        }
        flush()
        return blocks
    }

    @Composable
    fun Screen(
        file: File,
        onDismiss: () -> Unit,
        /** Shown at the top instead of the file name, such as a notebook's own name. */
        title: String? = null,
        /** A line above the text, such as why this file opens read-only. */
        notice: String? = null,
        /** Offers delete (after asking); the window closes once the file is gone. */
        onDelete: (suspend () -> Unit)? = null,
    ) {
        val context = LocalContext.current
        val colors = MaterialTheme.colorScheme
        val isMarkdown = type(file.name) in MARKDOWN_TYPES
        var loaded by remember(file) { mutableStateOf<Loaded?>(null) }
        var loading by remember(file) { mutableStateOf(false) }
        LaunchedEffect(file) { loaded = withContext(Dispatchers.IO) { first(file, isMarkdown) } }
        val scope = rememberCoroutineScope()
        // Each part is read once, off the main thread, when the reader nears the end of what is shown.
        val loadMore: () -> Unit = {
            val shown = loaded
            if (shown != null && shown.more && !loading) {
                loading = true
                scope.launch {
                    try {
                        loaded = withContext(Dispatchers.IO) { append(shown, file, isMarkdown) }
                    } finally {
                        loading = false
                    }
                }
            }
        }
        val markdown = loaded?.pages?.isNotEmpty() == true
        // The formatted view is drawn, not text; the plain view is there to select and copy.
        var showSource by rememberSaveable(file.path) { mutableStateOf(false) }
        // Each view keeps its own reading place, so switching to the plain text and back returns to it.
        val formattedList = rememberLazyListState()
        val sourceList = rememberLazyListState()
        // Links ask before leaving for the browser, as in the feed.
        var pendingLink by remember { mutableStateOf<String?>(null) }
        var confirmDelete by remember { mutableStateOf(false) }
        val systemUriHandler = LocalUriHandler.current
        val askingUriHandler = remember {
            object : UriHandler {
                override fun openUri(uri: String) { pendingLink = uri }
            }
        }
        // A card's "open link" button in the document asks the same way.
        val askingOpenUrl = remember { { url: String -> pendingLink = url } }
        OrbitModalWindow(
            onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // This window opens from inside a chat bubble: the file is drawn as a document of its
            // own, in the page's ink, never as the message it was sent in or as one of Orbit's cards.
            // A picture in it still opens in Orbit's own window.
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides colors.onBackground,
                LocalTranscriptMarkdownSlice provides null,
                LocalOrbitChatLook provides false,
                LocalSettingsDialogStyle provides SettingsDialogStyle.ORBIT,
                LocalDynamicUiOwner provides null,
                LocalDynamicUiBlockIndex provides null,
                LocalDynamicUiPartOffset provides null,
                LocalUriHandler provides askingUriHandler,
                com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiOpenUrl provides askingOpenUrl,
            ) {
            Column(Modifier.fillMaxSize().background(colors.background).then(readableWidth)) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopButton(orbitIcon("arrow-left"), stringResource(R.string.common_close), onDismiss)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        title ?: file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (markdown) {
                        TopButton(
                            orbitIcon(if (showSource) "file-text" else "code"),
                            stringResource(if (showSource) R.string.orbit_artifact_show_formatted else R.string.orbit_artifact_show_source),
                        ) { showSource = !showSource }
                        Spacer(Modifier.width(8.dp))
                    }
                    TopButton(orbitIcon("share-2"), stringResource(R.string.orbit_file_share)) {
                        shareOrbitFile(context) { file }
                    }
                    Spacer(Modifier.width(8.dp))
                    TopButton(orbitIcon("external-link"), stringResource(R.string.orbit_chat_text_open_other)) {
                        openOrbitFile(context) { file }
                    }
                    if (onDelete != null) {
                        Spacer(Modifier.width(8.dp))
                        TopButton(orbitIcon("trash-2"), stringResource(R.string.orbit_file_delete)) { confirmDelete = true }
                    }
                }
                if (notice != null) {
                    Text(
                        notice, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
                val current = loaded
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        current == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
                        current.failed -> Text(
                            stringResource(R.string.orbit_file_open_failed),
                            color = colors.onSurfaceVariant, modifier = Modifier.align(Alignment.Center),
                        )
                        markdown && !showSource -> LazyColumn(
                            state = formattedList,
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                        ) {
                            itemsIndexed(current.pages) { _, page ->
                                StreamMarkdownRenderer(
                                    content = page,
                                    textColor = colors.onBackground,
                                    backgroundColor = Color.Transparent,
                                    onLinkClick = { pendingLink = it },
                                    fillMaxWidth = true,
                                )
                            }
                            tail(current)
                        }
                        else -> SelectionContainer {
                            LazyColumn(
                                state = sourceList,
                                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp),
                                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                            ) {
                                itemsIndexed(current.blocks) { _, block ->
                                    Text(block, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                                }
                                tail(current)
                            }
                        }
                    }
                }
                LoadNearEnd(if (markdown && !showSource) formattedList else sourceList, current, loadMore)
            }
            }
            pendingLink?.let { link ->
                OrbitConfirmDialog(
                    title = stringResource(R.string.orbit_link_confirm_title),
                    message = stringResource(R.string.orbit_link_confirm_message, link),
                    confirmLabel = stringResource(R.string.orbit_link_confirm_open),
                    onConfirm = {
                        pendingLink = null
                        runCatching { systemUriHandler.openUri(link) }
                    },
                    onDismiss = { pendingLink = null },
                )
            }
            if (confirmDelete && onDelete != null) {
                OrbitConfirmDialog(
                    title = stringResource(R.string.orbit_file_delete_title),
                    message = (title ?: file.name) + "\n" + stringResource(R.string.orbit_file_delete_text),
                    confirmLabel = stringResource(R.string.orbit_file_delete),
                    onConfirm = {
                        confirmDelete = false
                        scope.launch {
                            try {
                                onDelete()
                                android.widget.Toast.makeText(context, R.string.orbit_file_deleted, android.widget.Toast.LENGTH_SHORT).show()
                                onDismiss()
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                android.widget.Toast.makeText(context, R.string.orbit_file_action_failed, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onDismiss = { confirmDelete = false },
                )
            }
        }
    }

    @Composable
    private fun EndNote(text: Int) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }

    /** The end of the list: a spinner while more of the file is coming, or why the rest is not here. */
    private fun androidx.compose.foundation.lazy.LazyListScope.tail(current: Loaded) {
        when {
            current.more -> item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            current.stop == Stop.LIMIT -> item(key = "stop") { EndNote(R.string.orbit_chat_text_cut) }
            current.stop == Stop.FAILED -> item(key = "stop") { EndNote(R.string.orbit_file_reader_failed) }
            current.stop == Stop.CHANGED -> item(key = "stop") { EndNote(R.string.orbit_file_reader_changed) }
        }
    }

    /** Asks for the next part once the last few items of [list] are on screen. */
    @Composable
    private fun LoadNearEnd(list: androidx.compose.foundation.lazy.LazyListState, current: Loaded?, loadMore: () -> Unit) {
        val nearEnd by remember(list) {
            androidx.compose.runtime.derivedStateOf {
                val info = list.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
            }
        }
        // Keyed on where the next part starts, so a short part that still leaves the end in view asks again.
        LaunchedEffect(nearEnd, current?.next, current?.more) { if (nearEnd && current?.more == true) loadMore() }
    }

    @Composable
    private fun TopButton(icon: ImageVector, label: String, onClick: () -> Unit) {
        Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(22.dp)) }
        }
    }
}
