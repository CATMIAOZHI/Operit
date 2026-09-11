package com.ai.assistance.operit.ui.features.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.ProviderQuota
import com.ai.assistance.operit.data.api.ProviderQuotaWindow
import com.ai.assistance.operit.data.api.QuotaWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Provider-reported subscription usage for the account configured above. Read-only by design: it
 * only mirrors what the account endpoint last reported, so a stale or missing number can never gate
 * a conversation.
 */
@Composable
fun ProviderQuotaPanel(
    quota: ProviderQuota?,
    loading: Boolean,
    failed: Boolean,
    onRefresh: () -> Unit,
) {
    // The countdown is the only part of this panel that ages without new data, so it drives its own
    // clock rather than making every caller remember to tick.
    var nowEpochMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val countdownRunning = quota?.windows?.any { it.resetAtEpochMillis != null } == true
    LaunchedEffect(countdownRunning) {
        while (isActive && countdownRunning) {
            nowEpochMillis = System.currentTimeMillis()
            delay(60_000L)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.quota_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = !loading, onClick = onRefresh) {
                Text(stringResource(R.string.quota_refresh))
            }
        }
        when {
            loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            quota == null -> Text(
                text = stringResource(
                    if (failed) R.string.quota_unavailable else R.string.quota_not_fetched,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            else -> {
                if (quota.windows.isEmpty()) {
                    Text(
                        text = stringResource(R.string.quota_no_windows),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                quota.windows.forEach { QuotaWindowRow(it, nowEpochMillis) }
                quota.creditsRemainingUsd?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (quota.creditsLimitUsd != null && quota.creditsLimitUsd > 0) {
                            stringResource(
                                R.string.quota_credits,
                                it,
                                quota.creditsLimitUsd,
                                quota.creditsUsedUsd ?: 0.0,
                            )
                        } else {
                            stringResource(R.string.quota_credits_remaining, it)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // On a transient failure the last good numbers stay put with a note, rather than
                // being replaced by an error that says nothing about the account's real usage.
                if (failed) {
                    Text(
                        text = stringResource(R.string.quota_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuotaWindowRow(window: ProviderQuotaWindow, nowEpochMillis: Long) {
    val label = stringResource(
        when (window.window) {
            QuotaWindow.FIVE_HOUR -> R.string.quota_window_five_hour
            QuotaWindow.WEEKLY -> R.string.quota_window_weekly
            QuotaWindow.MONTHLY -> R.string.quota_window_monthly
        }
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(
                // Truncated, not rounded: rounding 99.86% up to 100% would claim a window is spent
                // while the provider still allows requests against it.
                text = stringResource(R.string.quota_percent, window.percent.toInt().coerceIn(0, 100)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LinearProgressIndicator(
            progress = (window.percent / 100.0).toFloat().coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth(),
        )
        window.resetAtEpochMillis?.let { resetAt ->
            val remaining = resetAt - nowEpochMillis
            if (remaining > 0) {
                Text(
                    text = stringResource(R.string.quota_resets_in, formatQuotaCountdown(remaining)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Compact remaining-time label; quotas reset on the scale of hours to a month. */
internal fun formatQuotaCountdown(remainingMillis: Long): String {
    val minutes = remainingMillis / 60_000
    val days = minutes / (60 * 24)
    val hours = (minutes / 60) % 24
    return when {
        minutes < 1 -> "<1m"
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}
