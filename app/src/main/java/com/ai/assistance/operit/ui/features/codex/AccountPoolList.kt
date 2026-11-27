package com.ai.assistance.operit.ui.features.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.settings.components.SettingsAlertDialog as AlertDialog
import com.ai.assistance.operit.ui.features.settings.components.SettingsDialogTextButton as TextButton

/** One signed-in account as the settings list shows it. */
data class AccountPoolRow(val id: String, val label: String, val inUse: Boolean, val needsLogin: Boolean)

/**
 * The signed-in accounts of one provider: which one is in use, and buttons to use another or
 * remove one. Removing asks first, since that account has to sign in again to come back.
 */
@Composable
fun AccountPoolList(
    rows: List<AccountPoolRow>,
    enabled: Boolean = true,
    onUse: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var removing by remember { mutableStateOf<AccountPoolRow?>(null) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = if (row.inUse) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.label, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val status = when {
                            row.needsLogin -> stringResource(R.string.provider_account_needs_login)
                            row.inUse -> stringResource(R.string.provider_account_in_use)
                            else -> null
                        }
                        status?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = if (row.needsLogin) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary)
                        }
                    }
                    // An expired sign-in cannot answer; it comes back by signing in again.
                    if (!row.inUse && !row.needsLogin) {
                        TextButton(enabled = enabled, onClick = { onUse(row.id) }) {
                            Text(stringResource(R.string.provider_account_use))
                        }
                    }
                    TextButton(enabled = enabled, onClick = { removing = row }) {
                        Text(stringResource(R.string.provider_account_remove))
                    }
                }
            }
        }
        if (rows.isNotEmpty()) {
            Text(stringResource(R.string.provider_account_pool_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    removing?.let { row ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.provider_account_remove_title)) },
            text = { Text(stringResource(R.string.provider_account_remove_message, row.label)) },
            confirmButton = {
                TextButton(onClick = { removing = null; onRemove(row.id) }, destructive = true) {
                    Text(stringResource(R.string.provider_account_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel_action)) }
            },
        )
    }
}
/** The list rows for [accounts], unnamed ones numbered in the order they were added. */
@Composable
internal fun accountPoolRows(
    accounts: List<com.ai.assistance.operit.data.api.ProviderAccount>,
    activeId: String?,
): List<AccountPoolRow> = accounts.mapIndexed { index, value ->
    AccountPoolRow(
        id = value.identity,
        label = value.email.ifBlank { stringResource(R.string.provider_account_unnamed, index + 1) },
        inUse = value.identity == activeId,
        needsLogin = value.needsLogin,
    )
}