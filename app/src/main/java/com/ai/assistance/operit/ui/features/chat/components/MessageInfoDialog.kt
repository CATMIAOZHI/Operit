package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.ui.features.orbit.OrbitAwareAlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageInfoDialog(
    message: ChatMessage,
    onDismiss: () -> Unit,
    /** The chat the message is in; the developer view shows the request behind a reply with it. */
    chatId: String? = null,
    /**
     * Where this reply's turn ends when the reply has no end time of its own (a part sealed when
     * the user cut in): the next row's time. Without it the turn runs to now, as a live reply does.
     */
    turnEnd: Long? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Developer view: the requests this reply's turn really sent, the last one's parameters shown.
    val devRequests = LocalShowHiddenChatRows.current && message.sender == "ai" && !chatId.isNullOrBlank()
    // Read again as the recorder notes more and as the reply finishes, so an open dialog stays true.
    val recorderChanges by com.ai.assistance.operit.core.chat.OrbitDevRecorder.changes.collectAsState()
    // Where a sealed part's turn ends, once found in the database; a later row never moves it.
    var foundEnd by androidx.compose.runtime.remember(chatId, message.timestamp) { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    val turnRequests by androidx.compose.runtime.produceState<DevTurnRequests?>(
        null, devRequests, chatId, message.timestamp, message.sentAt, message.completedAt, turnEnd, recorderChanges,
    ) {
        if (!devRequests || chatId == null) return@produceState
        value = try {
            // From when the turn's first request was sent to when its reply finished, nothing more:
            // a next turn started right after must not count here.
            val recorder = com.ai.assistance.operit.core.chat.OrbitDevRecorder
            val from = message.sentAt.takeIf { it > 0L } ?: message.timestamp
            // A part sealed when the user cut in ends where the next row starts, even when that row
            // is not loaded on screen; only the reply still being written runs to now.
            val until = message.completedAt.takeIf { it > 0L } ?: turnEnd ?: foundEnd
                ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.ai.assistance.operit.data.db.AppDatabase.getDatabase(context).chatContentDao()
                        .nextMessageAt(chatId, message.timestamp)
                }?.also { foundEnd = it }
                ?: System.currentTimeMillis()
            val marks = recorder.marks(context, chatId).filter { it.at in from..until }
            val sent = marks.count { it.sent }
            // The body built for the last request really sent (or the last built, when none went out):
            // a later one that never left is not passed off as what was sent.
            val lastSentAt = marks.filter { it.sent }.maxOfOrNull { it.at }
            val last = marks.filter { !it.sent && (lastSentAt == null || it.at <= lastSentAt) }.maxOfOrNull { it.at }
                ?.let { lastAt -> recorder.requests(context, chatId).firstOrNull { it.at == lastAt } }
            DevTurnRequests(sent, marks.any { !it.sent }, last)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DevTurnRequests(0, false, null)
        }
    }
    // The last request's parameters, read and parsed again only when that request changes.
    val lastRequest = turnRequests?.last
    val lastParams by androidx.compose.runtime.produceState<List<Pair<String, String>>>(emptyList(), lastRequest?.file?.path) {
        val request = lastRequest ?: return@produceState
        value = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                (com.ai.assistance.operit.core.chat.OrbitDevRecorder.read(request).opt("body") as? org.json.JSONObject)
                    ?.let { com.ai.assistance.operit.ui.features.orbit.requestParams(it) }.orEmpty()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    var openRequest by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    openRequest?.let { name ->
        if (chatId != null) {
            com.ai.assistance.operit.ui.features.orbit.OrbitDevRequestSheet(chatId, onDismiss = { openRequest = null }, initial = name)
        }
    }
    val unavailable = stringResource(R.string.message_info_unavailable)
    val dateFormatter = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }
    val displaySender =
        when (message.sender) {
            "user" -> stringResource(R.string.message_role_user)
            "ai" -> "AI"
            else -> message.sender
        }

    fun formatTimestamp(value: Long): String {
        return if (value > 0L) {
            dateFormatter.format(Date(value))
        } else {
            unavailable
        }
    }

    fun formatDuration(value: Long): String {
        return if (value > 0L) {
            if (value >= 1000L) {
                String.format(Locale.getDefault(), "%.2f s (%d ms)", value / 1000.0, value)
            } else {
                "$value ms"
            }
        } else {
            "0 ms"
        }
    }

    OrbitAwareAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.message_info_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MessageInfoCard {
                    MessageInfoTwoColumnRow(
                        leftLabel = stringResource(R.string.message_info_sender),
                        leftValue = displaySender,
                        rightLabel = stringResource(R.string.message_info_role_name),
                        rightValue = message.roleName.ifBlank { unavailable }
                    )
                    MessageInfoTwoColumnRow(
                        leftLabel = stringResource(R.string.message_info_provider),
                        leftValue = message.provider.ifBlank { unavailable },
                        rightLabel = stringResource(R.string.message_info_model_name),
                        rightValue = message.modelName.ifBlank { unavailable }
                    )
                }

                MessageInfoCard {
                    MessageInfoTwoColumnRow(
                        leftLabel = stringResource(R.string.message_info_message_time),
                        leftValue = formatTimestamp(message.timestamp),
                        rightLabel = stringResource(R.string.message_info_sent_at),
                        rightValue = formatTimestamp(message.sentAt)
                    )
                    MessageInfoTwoColumnRow(
                        leftLabel = stringResource(R.string.message_info_wait_duration),
                        leftValue = formatDuration(message.waitDurationMs),
                        rightLabel = stringResource(R.string.message_info_output_duration),
                        rightValue = formatDuration(message.outputDurationMs)
                    )
                }

                MessageInfoCard {
                    MessageInfoTokenRow(
                        inputTokens = message.inputTokens,
                        outputTokens = message.outputTokens,
                        cachedInputTokens = message.cachedInputTokens
                    )
                }

                turnRequests?.let { turn ->
                    MessageInfoCard {
                        Text(
                            text = stringResource(R.string.orbit_dev_info_request),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Built but never on the network: it failed before going out (auth, the call).
                        val unsent = turn.built && turn.count == 0
                        if (unsent) {
                            Text(
                                text = stringResource(R.string.orbit_dev_info_request_unsent),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (turn.lastFile == null) {
                            if (!unsent)
                            Text(
                                text = if (turn.count > 0) stringResource(R.string.orbit_dev_info_request_count_only, turn.count)
                                else stringResource(R.string.orbit_dev_info_request_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            if (turn.count > 0) {
                            Text(
                                text = stringResource(R.string.orbit_dev_info_request_count, turn.count),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            }
                            lastParams.forEach { (key, value) ->
                                Text(
                                    text = "$key: $value",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            TextButton(onClick = { openRequest = turn.lastFile }) {
                                Text(text = stringResource(R.string.orbit_dev_info_open_request))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.floating_close))
            }
        }
    )
}

@Composable
private fun MessageInfoCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun MessageInfoTwoColumnRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = leftLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = leftValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = rightLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = rightValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun MessageInfoTokenRow(
    inputTokens: Int,
    outputTokens: Int,
    cachedInputTokens: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.message_info_token_summary),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text =
                buildString {
                    append(stringResource(R.string.message_info_token_input_short))
                    append(" ")
                    append(inputTokens)
                    append("  ")
                    append(stringResource(R.string.message_info_token_output_short))
                    append(" ")
                    append(outputTokens)
                    append("  ")
                    append(stringResource(R.string.message_info_token_cached_short))
                    append(" ")
                    append(cachedInputTokens)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** The requests one reply's turn sent, as the developer view keeps them. */
/** [count] exchanges really sent; [built] whether any request was built at all; [last] the body kept for the last sent. */
private class DevTurnRequests(
    val count: Int,
    val built: Boolean,
    val last: com.ai.assistance.operit.core.chat.OrbitDevRecorder.Request?,
) {
    val lastFile: String? get() = last?.file?.name
}
