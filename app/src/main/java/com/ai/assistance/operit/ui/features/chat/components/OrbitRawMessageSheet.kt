package com.ai.assistance.operit.ui.features.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Developer view: one message exactly as it is stored (thinking, tool calls and results, hidden
 * tags and placeholders), to read and copy, never to change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitRawMessageSheet(message: ChatMessage, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // A long tool result is one huge string; it is drawn in slices so the list stays smooth.
    // Cut off the main thread: a tool result can be megabytes long.
    val chunks by produceState<List<String>>(emptyList(), message.content) {
        value = withContext(Dispatchers.Default) { rawChunks(message.content) }
    }
    val meta = remember(message) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(message.timestamp))
        "${message.sender} · ${message.displayMode.name.lowercase()} · $time · ${message.content.length}"
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.orbit_dev_view_raw), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { copyRaw(context, message.content) }) {
                    Text(stringResource(R.string.copy_message))
                }
            }
            Text(
                stringResource(R.string.orbit_dev_view_raw_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
            ) {
                SelectionContainer {
                    LazyColumn(contentPadding = PaddingValues(14.dp)) {
                        items(chunks) { chunk ->
                            Text(
                                chunk,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The text in slices of about 40 lines, with a very long line cut every 4000 characters. */
private fun rawChunks(text: String): List<String> {
    if (text.isEmpty()) return listOf("")
    val out = ArrayList<String>()
    // Walks the text once by index, so even one huge line is copied only once.
    var start = 0 // first character of the slice being built
    var lines = 0
    var i = 0
    while (i < text.length) {
        val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
        if (end - i > MAX_LINE) {
            if (i > start) out += text.substring(start, i - 1)
            var j = i
            while (end - j > MAX_LINE) {
                // Never split an emoji (a surrogate pair) between two pieces.
                var cut = j + MAX_LINE
                if (Character.isHighSurrogate(text[cut - 1])) cut--
                out += text.substring(j, cut)
                j = cut
            }
            start = j
            lines = 0
        }
        if (++lines >= 40 || end == text.length) {
            out += text.substring(start, end)
            start = end + 1
            lines = 0
        }
        i = end + 1
    }
    if (start < text.length && lines > 0) out += text.substring(start)
    return out
}

private const val MAX_LINE = 4000

private fun copyRaw(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(context, R.string.orbit_dev_view_raw_copied, Toast.LENGTH_SHORT).show()
}
