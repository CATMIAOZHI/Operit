package com.ai.assistance.operit.ui.features.orbit

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.features.orbit.OrbitFavorites
import com.ai.assistance.operit.features.orbit.OrbitReactions
import com.ai.assistance.operit.ui.features.chat.components.OrbitPiece
import com.ai.assistance.operit.ui.features.chat.components.OrbitSpeech
import com.ai.assistance.operit.ui.features.chat.components.cleanMessageContentForCopy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Which Orbit chat is on screen, for keeping, copying and sharing its messages. [scope] outlives
 * the menu a message is kept from, so saving finishes after the menu closes. [orbit] is the Orbit
 * as it is now, for the picture a share draws.
 */
internal class OrbitMessageHost(
    val orbitId: String,
    val orbitName: String,
    val chatId: String,
    val scope: kotlinx.coroutines.CoroutineScope,
    val orbit: () -> com.ai.assistance.operit.data.preferences.OrbitProfile? = { null },
)

/** Set by the Orbit chat; null in every other chat. */
internal val LocalOrbitMessageHost = compositionLocalOf<OrbitMessageHost?> { null }

/**
 * Keeping, copying and sharing messages of an Orbit chat. The messages are the ones the chat
 * shows (see OrbitSpeech.history): Orbit's words without thinking or tools, and its cards.
 */
internal object OrbitMessageActions {
    private fun english(context: Context) =
        !context.resources.configuration.locales[0].language.startsWith("zh")

    /** The words of [message] as the chat shows them; a card reads as what it is. */
    fun shownText(context: Context, message: ChatMessage): String =
        if (message.sender == "ai") {
            OrbitSpeech.pieceList(message.content, message.timestamp, english(context))
                .joinToString("\n\n") { piece ->
                    piece.summary ?: cleanMessageContentForCopy(
                        OrbitSpeech.voiceAsWords(piece.content, context.getString(R.string.ai_message_quoted_voice)),
                    ).trim()
                }
                .trim()
        } else {
            OrbitWork.userText(message.content, context.getString(R.string.orbit_user_sent_attachment)).trim()
        }

    /**
     * The bubbles [message] shows as, the way the chat on screen draws them (a voice line is one),
     * in the language the chat draws them in, so the split the chat made is reused.
     */
    private fun bubblesOf(context: Context, message: ChatMessage): List<OrbitPiece> =
        OrbitSpeech.pieceList(message.content, message.timestamp, english(context), splitVoice = true)

    /** How many bubbles [message] shows as. */
    fun bubbleCount(context: Context, message: ChatMessage): Int =
        if (message.sender == "ai") bubblesOf(context, message).size.coerceAtLeast(1) else 1

    /** [bubbleCount] when the chat has already split [message], else null; quick on the main thread. */
    fun knownBubbleCount(context: Context, message: ChatMessage): Int? =
        if (message.sender != "ai") 1
        else OrbitSpeech.cachedPieceList(message.content, message.timestamp, english(context), splitVoice = true)
            ?.size?.coerceAtLeast(1)

    /**
     * [message] with only its bubbles at [pieces] (places top to bottom), each still its own bubble;
     * null when none of them is there any more (the reply changed since they were picked).
     */
    fun partOf(context: Context, message: ChatMessage, pieces: Set<Int>): ChatMessage? {
        val kept = bubblesOf(context, message).filterIndexed { i, _ -> i in pieces }
        if (kept.isEmpty()) return null
        return message.copy(content = kept.joinToString("\n<message_break/>\n") { it.content }, contentStream = null)
    }

    /** The bubbles of [message], in order (see [bubblesOf]). */
    fun bubbles(context: Context, message: ChatMessage): List<OrbitPiece> = bubblesOf(context, message)

    /**
     * [message], or one card of it ([piece]), as a favorite of [host]'s chat. [part]: only these
     * bubbles of it were picked (see [partOf]); one card alone is kept as that card.
     */
    fun favoriteOf(
        context: Context,
        host: OrbitMessageHost,
        message: ChatMessage,
        piece: OrbitPiece? = null,
        part: Set<Int>? = null,
    ): OrbitFavorites.Favorite {
        if (part != null && piece == null) {
            val kept = bubblesOf(context, message).filterIndexed { i, _ -> i in part }
            kept.singleOrNull()?.takeIf { it.summary != null }?.let { return favoriteOf(context, host, message, it) }
            return OrbitFavorites.Favorite(
                // Its own entry, so keeping another part of the same reply keeps that too.
                id = OrbitFavorites.idOf(host.chatId, "${message.timestamp}#${part.sorted().joinToString(",")}"),
                chatId = host.chatId,
                timestamp = message.timestamp,
                sender = message.sender,
                text = partOf(context, message, part)?.let { shownText(context, it) }.orEmpty(),
                savedAt = System.currentTimeMillis(),
            )
        }
        val card = piece?.takeIf { it.summary != null }
        // A card moved to the end of its reply still points at the message it came from.
        val time = card?.let { OrbitReactions.timeOf(it.key) }?.takeIf { it > 0 } ?: message.timestamp
        return OrbitFavorites.Favorite(
            id = OrbitFavorites.idOf(host.chatId, card?.key ?: message.timestamp.toString()),
            chatId = host.chatId,
            timestamp = time,
            sender = message.sender,
            text = card?.summary ?: shownText(context, message),
            savedAt = System.currentTimeMillis(),
        )
    }

    /** Keeps [items] and says so in a toast. */
    suspend fun keep(context: Context, host: OrbitMessageHost, items: List<OrbitFavorites.Favorite>) {
        val usable = items.filter { it.text.isNotBlank() }
        val text = try {
            if (usable.isEmpty()) context.getString(R.string.orbit_favorite_nothing)
            else {
                val added = OrbitFavorites.add(context, host.orbitId, usable)
                if (added == 0) context.getString(R.string.orbit_favorite_already)
                else context.getString(R.string.orbit_favorite_saved, added)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            context.getString(R.string.orbit_favorite_failed)
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    /** Picked messages as one text; [cut] when it was too long and only the start is kept. */
    class Transcript(val text: String, val cut: Boolean)

    /**
     * [messages] as one text, oldest first: who said it and when, then the words. Built off the
     * main thread and stopped at [MAX_TEXT] characters, so a huge selection cannot freeze the chat
     * or overflow the clipboard or the share sheet.
     */
    suspend fun transcript(context: Context, host: OrbitMessageHost, messages: List<ChatMessage>): Transcript =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val format = SimpleDateFormat(
                android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMMMdHHmm"), Locale.getDefault(),
            )
            val me = context.getString(R.string.orbit_transcript_me)
            val out = StringBuilder()
            var cut = false
            for (message in messages.sortedBy { it.timestamp }) {
                val words = shownText(context, message).takeIf { it.isNotBlank() } ?: continue
                val who = if (message.sender == "ai") host.orbitName else me
                if (out.isNotEmpty()) out.append("\n\n")
                out.append(who).append("  ").append(format.format(Date(message.timestamp))).append('\n').append(words)
                if (out.length > MAX_TEXT) {
                    out.setLength(MAX_TEXT)
                    cut = true
                    break
                }
            }
            Transcript(out.toString(), cut)
        }

    fun copy(context: Context, transcript: Transcript) {
        if (transcript.text.isBlank()) {
            Toast.makeText(context, context.getString(R.string.orbit_favorite_nothing), Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val copied = runCatching {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Orbit", transcript.text))
        }.isSuccess
        val note = when {
            !copied -> R.string.orbit_selection_copy_failed
            transcript.cut -> R.string.orbit_selection_cut
            else -> R.string.orbit_selection_copied
        }
        Toast.makeText(context, context.getString(note), Toast.LENGTH_SHORT).show()
    }

    /** About 300 KB at most, well inside what the clipboard and another app can take. */
    private const val MAX_TEXT = 100_000
}

/**
 * The bar that takes the place of the input while messages are selected: how many are picked and
 * close on the left, then copy, share and keep. Nothing here can change what was said.
 */
@androidx.compose.runtime.Composable
internal fun OrbitSelectionBar(
    count: Int,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onKeep: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        color = colors.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.material3.IconButton(onClick = onClose) {
                androidx.compose.material3.Icon(
                    orbitIcon("x"), androidx.compose.ui.res.stringResource(R.string.exit_multi_select),
                    androidx.compose.ui.Modifier.size(22.dp), tint = colors.onSurface,
                )
            }
            androidx.compose.material3.Text(
                if (count == 0) androidx.compose.ui.res.stringResource(R.string.orbit_selection_pick)
                else androidx.compose.ui.res.stringResource(R.string.selected_count, count),
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                modifier = androidx.compose.ui.Modifier.weight(1f),
            )
            SelectionAction("copy", androidx.compose.ui.res.stringResource(R.string.orbit_selection_copy), count > 0, onCopy)
            SelectionAction("share-2", androidx.compose.ui.res.stringResource(R.string.orbit_selection_share), count > 0, onShare)
            SelectionAction("star", androidx.compose.ui.res.stringResource(R.string.orbit_favorite_action), count > 0, onKeep)
        }
    }
}

@androidx.compose.runtime.Composable
private fun SelectionAction(icon: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val tint = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.Icon(orbitIcon(icon), null, androidx.compose.ui.Modifier.size(22.dp), tint = tint)
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(2.dp))
        androidx.compose.material3.Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = tint)
    }
}
