package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import kotlinx.coroutines.delay

/** Code fences Orbit uses for plain text the user should copy, such as a prompt for another AI. */
internal val ORBIT_COPY_BLOCK_LANGUAGES = setOf("", "text", "txt", "plain", "plaintext", "prompt", "markdown", "md")

/**
 * Long text scrolls inside the block, so the block stays the same size in the chat while it streams.
 * Lines are laid out lazily: only the visible ones are measured, and a streaming update re-measures
 * just the line that changed.
 */
private val ORBIT_COPY_BLOCK_MAX_HEIGHT = 320.dp

/** A paragraph longer than this is measured in pieces, so one huge paragraph never lays out whole. */
private const val ORBIT_COPY_BLOCK_PIECE = 600

/**
 * Splits the text into lines, and a very long line into pieces ending at a sentence end or a space
 * when one is near, so each piece is laid out on its own. Pieces before the last stay the same while
 * Orbit writes on, so a streaming update only re-measures the tail.
 */
internal fun orbitCopyBlockPieces(body: String): List<String> {
    val out = ArrayList<String>()
    for (line in body.split('\n')) {
        var start = 0
        while (line.length - start > ORBIT_COPY_BLOCK_PIECE) {
            val limit = start + ORBIT_COPY_BLOCK_PIECE
            val floor = start + ORBIT_COPY_BLOCK_PIECE / 2
            var cut = (limit downTo floor).firstOrNull { line[it - 1] in "。！？.!?；; " } ?: limit
            if (Character.isHighSurrogate(line[cut - 1])) cut -= 1
            out += line.substring(start, cut)
            start = cut
        }
        out += line.substring(start)
    }
    return out
}

/** Plain text Orbit hands over to copy: a soft card with a copy button at the bottom right. */
@Composable
internal fun OrbitCopyBlock(text: String, textColor: Color) {
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    val body = remember(text) { text.trimEnd('\n') }
    val lines = remember(body) { orbitCopyBlockPieces(body) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Surface(
        color = colors.surface.copy(alpha = 0.7f),
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .border(1.dp, colors.outline.copy(alpha = 0.35f), shape),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 2.dp)) {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = ORBIT_COPY_BLOCK_MAX_HEIGHT)
                    .padding(end = 8.dp),
            ) {
                itemsIndexed(lines) { _, line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = textColor)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = {
                        clipboard.setText(AnnotatedString(body))
                        copied = true
                    },
                    shape = RoundedCornerShape(50),
                    color = Color.Transparent,
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(if (copied) R.string.orbit_copy_block_copied else R.string.orbit_copy_block_copy),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.primary,
                        )
                    }
                }
            }
        }
    }
}
