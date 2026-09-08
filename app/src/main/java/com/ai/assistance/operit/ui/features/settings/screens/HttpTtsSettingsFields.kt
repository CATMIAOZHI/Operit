package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R

@Composable
internal fun HttpTtsSettingsFields(
    ttsUrlTemplateInput: String,
    onTtsUrlTemplateInputChange: (String) -> Unit,
    ttsApiKeyInput: String,
    onTtsApiKeyInputChange: (String) -> Unit,
    ttsHeadersInput: String,
    onTtsHeadersInputChange: (String) -> Unit,
    ttsContentTypeInput: String,
    onTtsContentTypeInputChange: (String) -> Unit,
    ttsRequestBodyInput: String,
    onTtsRequestBodyInputChange: (String) -> Unit,
    ttsResponsePipelineInput: String,
    onTtsResponsePipelineInputChange: (String) -> Unit,
    ttsHttpMethodInput: String,
    onHttpMethodChange: (String) -> Unit,
    httpMethodDropdownExpanded: Boolean,
    onHttpMethodDropdownExpandedChange: (Boolean) -> Unit,
    ttsHeadersJsonError: String?,
    ttsResponsePipelineJsonError: String?,
) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = stringResource(R.string.speech_services_http_tts_config),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = ttsUrlTemplateInput,
            onValueChange = onTtsUrlTemplateInputChange,
            label = { Text(stringResource(R.string.speech_services_http_url_template)) },
            placeholder = { Text(stringResource(R.string.speech_services_http_url_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = ttsApiKeyInput,
            onValueChange = onTtsApiKeyInputChange,
            label = { Text(stringResource(R.string.speech_services_http_api_key)) },
            placeholder = { Text(stringResource(R.string.speech_services_http_api_key_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = ttsHeadersInput,
            onValueChange = onTtsHeadersInputChange,
            label = { Text(stringResource(R.string.speech_services_http_headers)) },
            placeholder = { Text(stringResource(R.string.speech_services_http_headers_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            isError = ttsHeadersJsonError != null
        )

        if (ttsHeadersJsonError != null) {
            Text(
                text = ttsHeadersJsonError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = ttsHttpMethodInput,
                onValueChange = { },
                label = { Text(stringResource(R.string.speech_services_http_method)) },
                readOnly = true,
                modifier = Modifier.weight(1f),
                trailingIcon = {
                    DropdownMenu(
                        expanded = httpMethodDropdownExpanded,
                        onDismissRequest = { onHttpMethodDropdownExpandedChange(false) }
                    ) {
                        listOf("GET", "POST").forEach { method ->
                            DropdownMenuItem(
                                text = { Text(method) },
                                onClick = {
                                    onHttpMethodChange(method)
                                }
                            )
                        }
                    }
                    IconButton(onClick = { onHttpMethodDropdownExpandedChange(true) }) {
                        Icon(Icons.Default.ArrowDropDown, stringResource(R.string.speech_services_http_method_select))
                    }
                }
            )

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedTextField(
                value = ttsContentTypeInput,
                onValueChange = onTtsContentTypeInputChange,
                label = { Text(stringResource(R.string.speech_services_http_content_type)) },
                placeholder = { Text(stringResource(R.string.speech_services_http_content_type_placeholder)) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        if (ttsHttpMethodInput == "POST") {
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = ttsRequestBodyInput,
                onValueChange = onTtsRequestBodyInputChange,
                label = { Text(stringResource(R.string.speech_services_http_request_body)) },
                placeholder = { Text(stringResource(R.string.speech_services_http_request_body_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = ttsResponsePipelineInput,
            onValueChange = onTtsResponsePipelineInputChange,
            label = { Text(stringResource(R.string.speech_services_http_response_pipeline)) },
            placeholder = { Text(stringResource(R.string.speech_services_http_response_pipeline_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 6,
            isError = ttsResponsePipelineJsonError != null,
            supportingText = {
                Text(
                    text = stringResource(R.string.speech_services_http_response_pipeline_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )

        if (ttsResponsePipelineJsonError != null) {
            Text(
                text = ttsResponsePipelineJsonError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
