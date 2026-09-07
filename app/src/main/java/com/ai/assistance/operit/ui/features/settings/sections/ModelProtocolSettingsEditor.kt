package com.ai.assistance.operit.ui.features.settings.sections

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ModelProtocol
import com.ai.assistance.operit.data.model.ModelProtocolSettings
import com.ai.assistance.operit.ui.common.input.bringIntoViewOnImeFocus

@StringRes
private fun ModelProtocol.label(): Int = when (this) {
    ModelProtocol.INHERIT -> R.string.model_protocol_inherit
    ModelProtocol.CHAT_COMPLETIONS -> R.string.model_protocol_chat
    ModelProtocol.CHAT_REASONING -> R.string.model_protocol_chat_reasoning
    ModelProtocol.RESPONSES -> R.string.model_protocol_responses
    ModelProtocol.ANTHROPIC -> R.string.model_protocol_anthropic
    ModelProtocol.GEMINI -> R.string.model_protocol_gemini
    ModelProtocol.DEEPSEEK -> R.string.model_protocol_deepseek
    ModelProtocol.KIMI -> R.string.model_protocol_kimi
    ModelProtocol.MIMO -> R.string.model_protocol_mimo
}

@Composable
internal fun ModelProtocolSettingsEditor(
    modelName: String,
    settings: ModelProtocolSettings,
    onChange: (ModelProtocolSettings) -> Unit,
) {
    var showPicker by remember(modelName) { mutableStateOf(false) }
    SettingsSelectorRow(
        title = stringResource(R.string.model_protocol_title),
        subtitle = stringResource(R.string.model_protocol_desc),
        value = stringResource(settings.protocol.label()),
        onClick = { showPicker = true },
    )
    if (settings.protocol != ModelProtocol.INHERIT) {
        OutlinedTextField(
            value = settings.endpoint,
            onValueChange = { onChange(settings.copy(endpoint = it)) },
            label = { Text(stringResource(R.string.model_protocol_endpoint)) },
            supportingText = { Text(stringResource(R.string.model_protocol_endpoint_desc)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().bringIntoViewOnImeFocus(),
        )
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.model_protocol_title)) },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).selectableGroup()
                ) {
                    ModelProtocol.entries.forEach { protocol ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = settings.protocol == protocol,
                                    role = Role.RadioButton,
                                    onClick = {
                                        onChange(settings.copy(protocol = protocol))
                                        showPicker = false
                                    },
                                ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.protocol == protocol, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(protocol.label()))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
