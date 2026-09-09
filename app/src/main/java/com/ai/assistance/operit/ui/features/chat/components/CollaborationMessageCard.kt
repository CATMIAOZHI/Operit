package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatMessage

internal data class CollaborationDisplayMessage(val sender: String, val kind: String, val body: String)

internal fun collaborationDisplayMessages(content: String, sender: String): List<CollaborationDisplayMessage> {
    val header = Regex(
        "^Message ID: [0-9a-f-]{36}\\nMessage Type: (MESSAGE|NEW_TASK|FINAL_ANSWER|STATUS)\\n" +
            "Task name: [^\\n]+\\nSender: ([^\\n]+)\\nPayload:\\n",
    )
    val match = header.find(content)
    if (match == null) {
        return listOf(CollaborationDisplayMessage(sender, "NEW_TASK", content))
    }
    return listOf(
        CollaborationDisplayMessage(
            sender, match.groupValues[1],
            content.substring(match.range.last + 1).trimEnd(),
        )
    )
}

/** Only host-marked collaboration rows use this display; normal user prose is never reclassified. */
@Composable
fun CollaborationMessageCard(message: ChatMessage) {
    val events = remember(message.content, message.roleName) {
        collaborationDisplayMessages(message.content, message.roleName)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            events.forEach { event ->
                val kind = stringResource(when (event.kind) {
                    "FINAL_ANSWER" -> R.string.subagent_message_result
                    "STATUS" -> R.string.subagent_message_status
                    "NEW_TASK" -> R.string.subagent_message_task
                    else -> R.string.subagent_message_note
                })
                Text(
                    stringResource(R.string.subagent_message_header, event.sender, kind),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                SelectionContainer {
                    Text(event.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
