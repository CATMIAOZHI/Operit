package com.ai.assistance.operit.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.OnDemandResources
import com.ai.assistance.operit.util.PendingResourceRequest
import com.ai.assistance.operit.util.ResourceDownloadState

/** Activities that can host the in-app download confirmation dialog. */
interface ResourceDownloadDialogHost

/** Download size, plus the file counter when a group holds several files. */
@Composable
fun resourceDownloadProgressText(state: ResourceDownloadState): String {
    val context = LocalContext.current
    val size =
        stringResource(
            R.string.resource_download_size,
            Formatter.formatFileSize(context, state.downloaded),
            Formatter.formatFileSize(context, state.total)
        )
    return if (state.itemCount > 1) {
        size +
            " · " +
            stringResource(R.string.resource_download_item_progress, state.itemIndex, state.itemCount)
    } else {
        size
    }
}

fun resourceDownloadFraction(state: ResourceDownloadState): Float =
    if (state.total > 0L) (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) else 0f

/** Every download starts here, so nothing is ever fetched without the user saying yes. */
@Composable
fun ResourceDownloadConfirmDialog(
    request: PendingResourceRequest,
    onApprove: () -> Unit,
    onDecline: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.resource_download_confirm_title, request.name)) },
        text = {
            Text(
                stringResource(
                    R.string.resource_download_confirm_message,
                    request.name,
                    Formatter.formatFileSize(context, request.bytes)
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onApprove) {
                Text(stringResource(R.string.resource_download_confirm_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.resource_download_confirm_cancel))
            }
        }
    )
}

@Composable
fun ResourceDownloadProgressDialog(
    state: ResourceDownloadState,
    onCancel: () -> Unit,
    onKeepDownloading: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.resource_download_title, state.name)) },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = { resourceDownloadFraction(state) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    resourceDownloadProgressText(state),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.resource_download_once),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onKeepDownloading) {
                Text(stringResource(R.string.resource_download_background))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.resource_download_cancel))
            }
        }
    )
}

@Composable
fun ResourceDownloadFailedDialog(
    state: ResourceDownloadState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when {
                        state.unconfirmed -> R.string.resource_download_unconfirmed_title
                        state.cancelled -> R.string.resource_download_cancelled_title
                        else -> R.string.resource_download_failed_title
                    },
                    state.name
                )
            )
        },
        text = { Text(state.error ?: stringResource(R.string.resource_download_failed_generic)) },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.resource_download_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.resource_download_dismiss))
            }
        }
    )
}

/** Hosts requests raised while the app itself is on screen. */
@Composable
fun ResourceDownloadConfirmHost() {
    val pending by OnDemandResources.pendingRequests.collectAsState()
    // Only requests routed here; out-of-app ones belong to the dedicated dialog Activity, and
    // showing them twice would put two dialogs on screen at once.
    val request = pending.firstOrNull { it.hostInApp } ?: return
    ResourceDownloadConfirmDialog(
        request = request,
        onApprove = { OnDemandResources.approve(request.id) },
        onDecline = { OnDemandResources.decline(request.id) }
    )
}
