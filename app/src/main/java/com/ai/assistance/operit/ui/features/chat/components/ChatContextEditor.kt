package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatContextPolicy
import com.ai.assistance.operit.ui.features.chat.util.parseContextK

data class ChatContextEditorTarget(
    val chatId: String,
    val configId: String,
    val policy: ChatContextPolicy,
    val capacityK: Float,
    val draft: String? = null,
    val draftRevision: Long = 0,
)

@Composable
fun ChatContextPolicyEditor(
    initial: ChatContextPolicy,
    onSave: (ChatContextPolicy) -> Unit,
    saving: Boolean = false,
) {
    var window by remember(initial) { mutableStateOf("${initial.windowK}K") }
    var maxMode by remember(initial) { mutableStateOf(initial.maxMode) }
    var enabled by remember(initial) { mutableStateOf(initial.autoCompact) }
    var trigger by remember(initial) { mutableStateOf(initial.compactAtK?.let { "${it}K" } ?: "") }
    var retainTurnLimit by remember(initial) { mutableStateOf(initial.compactEveryUserTurns != null) }
    val windowValue = parseContextK(window)
    val triggerValue = parseContextK(trigger)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = window, onValueChange = { window = it }, singleLine = true, enabled = !saving,
            label = { Text(stringResource(R.string.chat_context_budget)) },
            supportingText = { Text(stringResource(R.string.chat_context_budget_hint)) },
            isError = windowValue == null, modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.max_mode_title), modifier = Modifier.weight(1f))
            Switch(maxMode, { maxMode = it }, enabled = !saving)
        }
        Text(stringResource(R.string.chat_context_max_hint), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.settings_enable_summary), modifier = Modifier.weight(1f))
            Switch(enabled, { enabled = it }, enabled = !saving)
        }
        OutlinedTextField(
            value = trigger, onValueChange = { trigger = it }, singleLine = true,
            label = { Text(stringResource(R.string.chat_context_compact_at)) },
            supportingText = { Text(stringResource(R.string.chat_context_compact_hint)) },
            enabled = enabled && !saving, isError = trigger.isNotBlank() && triggerValue == null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (initial.compactEveryUserTurns != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.chat_context_legacy_turns, initial.compactEveryUserTurns),
                    modifier = Modifier.weight(1f))
                Switch(retainTurnLimit, { retainTurnLimit = it }, enabled = enabled && !saving)
            }
        }
        Button(
            enabled = !saving && windowValue != null && (trigger.isBlank() || triggerValue != null),
            onClick = {
                onSave(initial.copy(windowK = requireNotNull(windowValue), maxMode = maxMode,
                    autoCompact = enabled, compactAtK = triggerValue,
                    compactEveryUserTurns = initial.compactEveryUserTurns.takeIf { retainTurnLimit }))
            },
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.save))
        }
    }
}

@Composable
fun ChatContextEditorDialog(target: ChatContextEditorTarget, onDismiss: () -> Unit, onSave: (ChatContextPolicy) -> Unit, saving: Boolean = false) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.chat_context_current)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.chat_context_capacity, target.capacityK.toString()))
                Spacer(Modifier.height(12.dp))
                ChatContextPolicyEditor(target.policy, onSave, saving)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
    )
}
