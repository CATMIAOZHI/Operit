package com.ai.assistance.operit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.OnDemandResources

/** Render only in the Activity, never in a floating-window Compose tree. */
@Composable
fun ResourceDownloadPanel(modifier: Modifier = Modifier) {
    val downloads by OnDemandResources.downloads.collectAsState()
    Column(modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        downloads.values.forEach { state ->
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(
                        if (state.error == null) R.string.resource_download_title else R.string.resource_download_failed_title,
                        state.name
                    ))
                    if (state.error == null) {
                        Text(stringResource(R.string.resource_download_size,
                            state.downloaded / 1024 / 1024, state.total / 1024 / 1024))
                        LinearProgressIndicator(
                            progress = { (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(stringResource(R.string.resource_download_once))
                        TextButton(onClick = { OnDemandResources.cancel(state.id) }) {
                            Text(stringResource(R.string.resource_download_cancel))
                        }
                    } else {
                        Text(state.error)
                        Text(stringResource(R.string.resource_download_retry_hint))
                        TextButton(onClick = { OnDemandResources.dismiss(state.id) }) {
                            Text(stringResource(R.string.resource_download_dismiss))
                        }
                    }
                }
            }
        }
    }
}
