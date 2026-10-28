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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Simple text files Orbit sends open right in the chat, with share and open-elsewhere at the top. */
internal object OrbitTextPreview {
    private val TEXT_TYPES = setOf(
        "txt", "text", "md", "markdown", "log", "json", "csv", "tsv", "xml", "yaml", "yml",
        "ini", "cfg", "conf", "properties", "toml",
    )
    /** A bigger file shows its beginning; the rest is a tap away in another app. */
    private const val MAX_BYTES = 512 * 1024
    /** Lines drawn together, so a long file scrolls without building one huge text block. */
    private const val LINES_PER_BLOCK = 40
    /** A block never grows past this, so one very long line (minified JSON, logs) is split too. */
    private const val CHARS_PER_BLOCK = 6000

    fun canShow(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in TEXT_TYPES

    private class Loaded(val blocks: List<String>, val cut: Boolean, val failed: Boolean)

    private fun read(file: File): Loaded = runCatching {
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
        val cut = bytes.size > MAX_BYTES
        val body = if (cut) bytes.copyOf(MAX_BYTES) else bytes
        val text = decode(body, cut)
        Loaded(toBlocks(text.removePrefix("\uFEFF")), cut, false)
    }.getOrElse { Loaded(emptyList(), false, true) }

    /** Strict UTF-8 first; anything that is not valid UTF-8 (often GBK from Windows) reads as GB18030. */
    private fun decode(body: ByteArray, cut: Boolean): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        // A cut file may end in the middle of one character; drop up to 3 trailing bytes for that.
        for (trim in 0..(if (cut) minOf(3, body.size) else 0)) {
            runCatching {
                return utf8.reset().decode(java.nio.ByteBuffer.wrap(body, 0, body.size - trim)).toString()
            }
        }
        return String(body, java.nio.charset.Charset.forName("GB18030"))
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
    fun Screen(file: File, onDismiss: () -> Unit) {
        val context = LocalContext.current
        val colors = MaterialTheme.colorScheme
        val loaded by produceState<Loaded?>(null, file) { value = withContext(Dispatchers.IO) { read(file) } }
        OrbitModalWindow(
            onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(Modifier.fillMaxSize().background(colors.background)) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopButton(orbitIcon("arrow-left"), stringResource(R.string.common_close), onDismiss)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TopButton(orbitIcon("share-2"), stringResource(R.string.orbit_file_share)) {
                        shareOrbitFile(context) { file }
                    }
                    Spacer(Modifier.width(8.dp))
                    TopButton(orbitIcon("external-link"), stringResource(R.string.orbit_chat_text_open_other)) {
                        openOrbitFile(context) { file }
                    }
                }
                val current = loaded
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        current == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
                        current.failed -> Text(
                            stringResource(R.string.orbit_file_open_failed),
                            color = colors.onSurfaceVariant, modifier = Modifier.align(Alignment.Center),
                        )
                        else -> SelectionContainer {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp),
                                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                            ) {
                                itemsIndexed(current.blocks) { _, block ->
                                    Text(block, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                                }
                                if (current.cut) item {
                                    Text(
                                        stringResource(R.string.orbit_chat_text_cut),
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TopButton(icon: ImageVector, label: String, onClick: () -> Unit) {
        Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(22.dp)) }
        }
    }
}
