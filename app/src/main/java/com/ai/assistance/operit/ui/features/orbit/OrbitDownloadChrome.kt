package com.ai.assistance.operit.ui.features.orbit

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.components.resourceDownloadFraction
import com.ai.assistance.operit.ui.components.resourceDownloadProgressText
import com.ai.assistance.operit.util.OnDemandResources
import com.ai.assistance.operit.util.PendingResourceRequest
import com.ai.assistance.operit.util.ResourceDownloadState

/**
 * The color scheme of the Orbit page while it is on screen, null otherwise. App-wide overlays drawn
 * outside the Orbit page (download prompts and progress) use it to look like the rest of Orbit.
 */
internal object OrbitActiveTheme {
    var scheme by mutableStateOf<ColorScheme?>(null)
}

/** The download question as an Orbit sheet: what it is, how big, and yes or not now. */
@Composable
internal fun OrbitDownloadConfirmSheet(request: PendingResourceRequest, onApprove: () -> Unit, onDecline: () -> Unit) {
    val context = LocalContext.current
    TaskConfirmSheet(
        icon = "download",
        title = stringResource(R.string.resource_download_confirm_title, request.name),
        message = stringResource(
            R.string.resource_download_confirm_message,
            request.name,
            Formatter.formatFileSize(context, request.bytes),
        ),
        confirmText = stringResource(R.string.resource_download_confirm_ok),
        dismissText = stringResource(R.string.resource_download_confirm_cancel),
        destructive = false,
        onDismiss = onDecline,
        onConfirm = onApprove,
    )
}

/** Download progress as soft floating Orbit cards, one per download. */
@Composable
internal fun OrbitDownloadPanel(downloads: Collection<ResourceDownloadState>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        downloads.forEach { state ->
            val title = stringResource(
                when {
                    state.error == null -> R.string.resource_download_title
                    state.unconfirmed -> R.string.resource_download_unconfirmed_title
                    state.cancelled -> R.string.resource_download_cancelled_title
                    else -> R.string.resource_download_failed_title
                },
                state.name,
            )
            Surface(
                shape = RoundedCornerShape(if (state.collapsed) 28.dp else 24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.collapsed) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (state.error == null) {
                            Spacer(Modifier.width(8.dp))
                            Text(resourceDownloadProgressText(state), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PillButton(stringResource(R.string.resource_download_expand)) { OnDemandResources.setCollapsed(state.id, false) }
                    }
                } else {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(orbitIcon(if (state.error == null) "download" else "circle-alert"), null, Modifier.size(20.dp),
                                    tint = if (state.error == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (state.error == null) resourceDownloadProgressText(state) else state.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (state.error == null) {
                            LinearProgressIndicator(
                                progress = { resourceDownloadFraction(state) },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                strokeCap = StrokeCap.Round,
                                gapSize = 0.dp,
                                drawStopIndicator = {},
                            )
                            Text(stringResource(R.string.resource_download_once), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (!state.cancelled) {
                            Text(stringResource(R.string.resource_download_retry_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (state.error == null) {
                                PillButton(stringResource(R.string.resource_download_cancel)) { OnDemandResources.cancel(state.id) }
                                PillButton(stringResource(R.string.resource_download_collapse), primary = true) {
                                    OnDemandResources.setCollapsed(state.id, true)
                                }
                            } else {
                                PillButton(stringResource(R.string.resource_download_dismiss)) { OnDemandResources.dismiss(state.id) }
                                PillButton(stringResource(R.string.resource_download_retry), primary = true) {
                                    OnDemandResources.retry(context, state.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PillButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (primary) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
        contentColor = if (primary) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
    }
}
