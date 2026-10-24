package com.ai.assistance.operit.ui.features.chat.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SharedFileTargetDialog(
    fileCount: Int,
    isTextShare: Boolean = false,
    recentChats: List<ChatHistory>,
    currentChatId: String?,
    onCreateNewChat: () -> Unit,
    onSelectChat: (String) -> Unit,
    onDismiss: () -> Unit,
    /** The running Orbit's name; with [onSendToOrbit], offers to hand the share to Orbit. */
    orbitName: String? = null,
    onSendToOrbit: (() -> Unit)? = null,
) {
    val dateTimeFormatter = remember {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    }
    val newConversationText = stringResource(R.string.new_conversation)
    val currentConversationBadge = stringResource(R.string.chat_shared_file_target_current_badge)
    val configuration = LocalConfiguration.current
    val maxDialogHeight = configuration.screenHeightDp.dp * 0.8f
    val scrollState = rememberScrollState()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .heightIn(max = maxDialogHeight),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_shared_file_target_dialog_title),
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text =
                            if (isTextShare) {
                                stringResource(R.string.chat_shared_text_target_dialog_message)
                            } else {
                                stringResource(
                                    R.string.chat_shared_file_target_dialog_message,
                                    fileCount
                                )
                            },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (orbitName != null && onSendToOrbit != null) {
                        OutlinedCard(
                            onClick = onSendToOrbit,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.orbit_share_target, orbitName),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(R.string.orbit_share_target_desc, orbitName),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    OutlinedCard(
                        onClick = onCreateNewChat,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.new_conversation),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text =
                                    if (isTextShare) {
                                        stringResource(R.string.chat_shared_text_target_new_chat_hint)
                                    } else {
                                        stringResource(R.string.chat_shared_file_target_new_chat_hint)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (recentChats.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_shared_file_target_recent_section),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        recentChats.forEach { chat ->
                            val title = if (chat.title.isBlank()) newConversationText else chat.title
                            val subtitle = if (chat.id == currentChatId) {
                                "${chat.updatedAt.format(dateTimeFormatter)}  $currentConversationBadge"
                            } else {
                                chat.updatedAt.format(dateTimeFormatter)
                            }

                            OutlinedCard(
                                onClick = { onSelectChat(chat.id) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.chat_shared_file_target_no_recent),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}

@Composable
fun SharedIncomingContentHandler(
    sharedFiles: List<Uri>?,
    sharedFileText: String?,
    sharedText: String?,
    chatHistories: List<ChatHistory>,
    currentChatId: String?,
    onHandleSharedFiles: (List<Uri>, String?, String?) -> Unit,
    onHandleSharedText: (String, String?) -> Unit,
    onClearSharedFiles: () -> Unit,
    onClearSharedText: () -> Unit,
    /** Opens the Orbit page; hosts that cannot show it pass null, and Orbit is then not offered. */
    onOpenOrbit: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val orbitSettings by remember { OrbitPreferences.getInstance(context).settingsFlow }.collectAsState(initial = null)
    // Only a running Orbit can take a share; a paused one would have nobody to answer.
    val activeOrbit = orbitSettings?.active?.takeIf { onOpenOrbit != null }
    var pendingSharedFilesForSelection by remember { mutableStateOf<List<Uri>?>(null) }
    var pendingSharedFileTextForSelection by remember { mutableStateOf<String?>(null) }
    var pendingSharedTextForSelection by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sharedFiles, sharedFileText) {
        if (!sharedFiles.isNullOrEmpty()) {
            pendingSharedFilesForSelection = sharedFiles
            pendingSharedFileTextForSelection = sharedFileText
        }
    }

    LaunchedEffect(sharedText, pendingSharedFilesForSelection) {
        if (pendingSharedFilesForSelection != null) {
            return@LaunchedEffect
        }

        val text = sharedText?.trim()
        if (!text.isNullOrBlank()) {
            pendingSharedTextForSelection = text
        }
    }

    val recentChatTargets = remember(chatHistories) {
        chatHistories
            .sortedWith(
                compareByDescending<ChatHistory> { chat -> chat.updatedAt }
                    .thenByDescending { chat -> chat.createdAt }
            )
            .take(5)
    }

    fun consumePendingSharedFiles(targetChatId: String?) {
        val uris = pendingSharedFilesForSelection ?: return
        val text = pendingSharedFileTextForSelection?.trim()
        pendingSharedFilesForSelection = null
        pendingSharedFileTextForSelection = null
        onClearSharedFiles()
        onHandleSharedFiles(uris, targetChatId, text)
    }

    fun consumePendingSharedText(targetChatId: String?) {
        val text = pendingSharedTextForSelection?.trim()
        if (text.isNullOrBlank()) return

        pendingSharedTextForSelection = null
        onClearSharedText()
        onHandleSharedText(text, targetChatId)
    }

    fun openOrbit(orbit: OrbitProfile) {
        com.ai.assistance.operit.features.orbit.OrbitNavigation.openChat(orbit.chatId)
        onOpenOrbit?.invoke()
    }

    fun shareTextToOrbit(orbit: OrbitProfile) {
        val text = pendingSharedTextForSelection?.trim()
        pendingSharedTextForSelection = null
        onClearSharedText()
        if (text.isNullOrBlank()) return
        com.ai.assistance.operit.ui.features.orbit.sendToOrbit(context, orbit, text)
        openOrbit(orbit)
    }

    /** Files go into Orbit's own folder first, then Orbit is told where they are. */
    fun shareFilesToOrbit(orbit: OrbitProfile) {
        val uris = pendingSharedFilesForSelection ?: return
        val note = pendingSharedFileTextForSelection?.trim().orEmpty()
        pendingSharedFilesForSelection = null
        pendingSharedFileTextForSelection = null
        onClearSharedFiles()
        scope.launch {
            val saved = mutableListOf<String>()
            val files = com.ai.assistance.operit.features.orbit.OrbitFiles.of(context, orbit.id)
            try {
                val paths = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val name = sharedFileName(context, uri)
                        context.contentResolver.openInputStream(uri).use { input ->
                            files.saveBytes("files/shared/$name", checkNotNull(input)).path.also { saved += it }
                        }
                    }
                }
                val message = listOf(note, resources.getString(R.string.orbit_share_files_message, paths.size,
                    paths.joinToString("\n") { "- $it" })).filter { it.isNotBlank() }.joinToString("\n\n")
                com.ai.assistance.operit.ui.features.orbit.sendToOrbit(context, orbit, message)
                openOrbit(orbit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Files saved before the failure go to the trash, so a half share does not linger unannounced.
                saved.forEach { path -> runCatching { files.delete(path) } }
                android.widget.Toast.makeText(context, resources.getString(R.string.orbit_share_failed, orbit.name),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    pendingSharedFilesForSelection?.let { sharedUris ->
        SharedFileTargetDialog(
            fileCount = sharedUris.size,
            recentChats = recentChatTargets,
            currentChatId = currentChatId,
            onCreateNewChat = { consumePendingSharedFiles(targetChatId = null) },
            onSelectChat = { chatId -> consumePendingSharedFiles(targetChatId = chatId) },
            onDismiss = {
                pendingSharedFilesForSelection = null
                pendingSharedFileTextForSelection = null
                onClearSharedFiles()
            },
            orbitName = activeOrbit?.name,
            onSendToOrbit = activeOrbit?.let { orbit -> { shareFilesToOrbit(orbit) } },
        )
    }

    pendingSharedTextForSelection?.let {
        SharedFileTargetDialog(
            fileCount = 0,
            isTextShare = true,
            recentChats = recentChatTargets,
            currentChatId = currentChatId,
            onCreateNewChat = { consumePendingSharedText(targetChatId = null) },
            onSelectChat = { chatId -> consumePendingSharedText(targetChatId = chatId) },
            onDismiss = {
                pendingSharedTextForSelection = null
                onClearSharedText()
            },
            orbitName = activeOrbit?.name,
            onSendToOrbit = activeOrbit?.let { orbit -> { shareTextToOrbit(orbit) } },
        )
    }
}

/** The shared file's own name, made safe for Orbit's folder. */
private fun sharedFileName(context: android.content.Context, uri: Uri): String {
    val display = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    return display.orEmpty().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.')
        .take(100).ifBlank { "shared-${System.currentTimeMillis()}" }
}
