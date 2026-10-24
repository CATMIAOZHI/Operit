package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitVault
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** The vault's entries, read again on every save or delete. Null while loading. */
@Composable
private fun vaultEntries(orbitId: String, reload: Int): Result<List<OrbitVault.Entry>>? {
    val context = LocalContext.current
    val resources = LocalResources.current
    val saved by OrbitVault.version.collectAsState()
    val entries by produceState<Result<List<OrbitVault.Entry>>?>(null, orbitId, reload, saved) {
        value = runCatching { OrbitVault.entries(context, orbitId) }
    }
    return entries
}

private fun OrbitVault.Entry.isLogin() = kind == OrbitVault.LOGIN

/**
 * The password vault as a page: every saved login and API key, newest first. A tap opens the
 * entry. Nothing is added here; Orbit asks with a card in the chat when it needs a login or key.
 */
@Composable
internal fun OrbitVaultPage(orbit: OrbitProfile, onBack: () -> Unit, onOpenEntry: (String) -> Unit) {
    OrbitPageBack(onBack = onBack)
    var reload by remember { mutableStateOf(0) }
    val loaded = vaultEntries(orbit.id, reload)
    val list = loaded?.getOrNull()?.sortedByDescending { it.savedAt }
    // A new password keeps the old one, so one user can have two rows; their save time tells them apart.
    val twins = remember(list) { list.orEmpty().groupingBy { Triple(it.kind, it.site, it.username) }.eachCount() }
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
    ) {
        item(key = "header") {
            OrbitPageHeader(stringResource(R.string.orbit_vault_title), onBack)
            Text(
                stringResource(R.string.orbit_vault_desc, orbit.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 16.dp),
            )
        }
        when {
            loaded == null -> item(key = "loading") { OrbitDelayedSpinner() }
            list == null -> item(key = "failed") {
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.orbit_vault_load_failed), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { reload++ }) { Text(stringResource(R.string.orbit_vault_retry)) }
                }
            }
            list.isEmpty() -> item(key = "empty") { VaultEmpty(orbit.name) }
            else -> itemsIndexed(list, key = { _, entry -> entry.id }) { index, entry ->
                FolderSlice(first = index == 0, last = index == list.lastIndex) {
                    if (index > 0) HorizontalDivider(
                        modifier = Modifier.padding(start = 70.dp, end = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    VaultRow(entry, (twins[Triple(entry.kind, entry.site, entry.username)] ?: 0) > 1) { onOpenEntry(entry.id) }
                }
            }
        }
    }
}

@Composable
private fun VaultEmpty(name: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VaultIcon("shield-check", 64.dp, 28.dp)
        Text(
            stringResource(R.string.orbit_vault_empty, name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VaultIcon(name: String, size: androidx.compose.ui.unit.Dp, iconSize: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(orbitIcon(name), contentDescription = null, modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VaultRow(entry: OrbitVault.Entry, showTime: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VaultIcon(if (entry.isLogin()) "key-round" else "plug", 40.dp, 20.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.site, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (entry.isLogin()) OrbitVault.masked(entry.username).ifBlank { stringResource(R.string.orbit_vault_password) }
                else stringResource(if (entry.kind == OrbitVault.OAUTH) R.string.orbit_oauth_login else R.string.orbit_vault_api_key)) +
                    (if (entry.envName.isNotEmpty()) " · " + stringResource(R.string.orbit_vault_env_badge) else "") +
                    (if (showTime) " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                        .format(Date(entry.savedAt)) else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(orbitIcon("chevron-right"), contentDescription = null, modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One saved login or key, read-only. The user can see and copy what they typed, or delete it;
 * to change it they delete it and let Orbit ask again with a card. Screenshots are blocked here.
 */
@Composable
internal fun OrbitVaultEntryPage(
    orbit: OrbitProfile,
    entryId: String,
    onBack: () -> Unit,
    onDraft: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var reload by remember { mutableStateOf(0) }
    val loaded = vaultEntries(orbit.id, reload)
    val entry = loaded?.getOrNull()?.firstOrNull { it.id == entryId }
    var revealed by remember(entryId) { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // Going back is off while a delete runs; a finished delete leaves by itself. [onBack] only
    // leaves while this page is on top, so it is safe to ask twice.
    OrbitPageBack(intercept = deleting) { if (!deleting) onBack() }
    SecureWindow()
    val copiedText = stringResource(R.string.orbit_vault_copied)
    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
    ) {
        OrbitPageHeader(entry?.site ?: stringResource(R.string.orbit_vault_title), { if (!deleting) onBack() })
        when {
            loaded == null -> OrbitDelayedSpinner()
            loaded.isFailure -> Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.orbit_vault_load_failed), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { reload++ }) { Text(stringResource(R.string.orbit_vault_retry)) }
            }
            // Deleted here or elsewhere: nothing left to show.
            entry == null -> Text(
                stringResource(R.string.orbit_vault_entry_gone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            else -> {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    VaultIcon(if (entry.isLogin()) "key-round" else "plug", 64.dp, 28.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(entry.site, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center)
                    Text(
                        stringResource(
                            R.string.orbit_vault_saved_on,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.savedAt)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                    Column {
                        val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        if (entry.isLogin()) {
                            VaultField(
                                label = stringResource(R.string.orbit_vault_username),
                                value = entry.username.ifBlank { "—" },
                                onCopy = entry.username.takeIf { it.isNotBlank() }?.let { { copy(it) } },
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = divider)
                        }
                        if (entry.kind == OrbitVault.OAUTH) {
                            VaultField(label = stringResource(R.string.orbit_oauth_login),
                                value = stringResource(R.string.orbit_oauth_protected))
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = divider)
                            VaultField(
                                label = stringResource(R.string.orbit_oauth_expires),
                                value = entry.oauth?.expiresAt?.takeIf { it > 0 }?.let {
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
                                } ?: stringResource(R.string.orbit_oauth_expiry_unknown),
                            )
                        } else {
                            VaultField(
                                label = stringResource(if (entry.isLogin()) R.string.orbit_vault_password else R.string.orbit_vault_api_key),
                                value = if (revealed) entry.secret else "••••••••",
                                monospace = revealed,
                                onCopy = { copy(entry.secret) },
                                reveal = revealed,
                                onToggleReveal = { revealed = !revealed },
                            )
                        }
                        if (!entry.isLogin() && entry.apiHosts.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = divider)
                            VaultField(
                                label = stringResource(R.string.orbit_vault_hosts_label),
                                value = entry.apiHosts.joinToString("\n"),
                            )
                        }
                        if (entry.envName.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = divider)
                            VaultField(
                                label = stringResource(R.string.orbit_vault_env_label),
                                value = "$" + entry.envName,
                                monospace = true,
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.orbit_vault_readonly_note, orbit.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 24.dp),
                )
                // Orbit does the change: the prompt waits in the main chat's input box, and once sent
                // Orbit shows the update card (or reconnects an OAuth login).
                val draft = stringResource(
                    if (entry.kind == OrbitVault.OAUTH) R.string.orbit_credential_reconnect_draft
                    else R.string.orbit_credential_edit_draft,
                    entry.site, entry.id,
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(50))
                        .clickable(enabled = !deleting, role = Role.Button) { onDraft(draft) },
                ) {
                    Text(
                        stringResource(R.string.orbit_vault_edit),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                        .clickable(enabled = !deleting, role = Role.Button) { confirming = true },
                ) {
                    Text(
                        stringResource(R.string.delete),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp),
                    )
                }
            }
        }
    }

    if (confirming && entry != null) {
        TaskConfirmSheet(
            icon = "trash-2",
            title = stringResource(R.string.orbit_vault_delete_title),
            message = stringResource(R.string.orbit_vault_delete_confirm, entry.site),
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            destructive = true,
            onDismiss = { if (!deleting) confirming = false },
            onConfirm = {
                if (deleting) return@TaskConfirmSheet
                deleting = true
                scope.launch {
                    val ok = try {
                        OrbitVault.delete(context, orbit.id, entry.id)
                        true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                    deleting = false
                    confirming = false
                    if (ok) onBack()
                    else Toast.makeText(context, resources.getString(R.string.orbit_vault_delete_failed), Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
private fun VaultField(
    label: String,
    value: String,
    monospace: Boolean = false,
    onCopy: (() -> Unit)? = null,
    reveal: Boolean? = null,
    onToggleReveal: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = if (monospace) FontFamily.Monospace else null,
                )
            }
        }
        if (onToggleReveal != null && reveal != null) {
            IconButton(onClick = onToggleReveal) {
                Icon(
                    orbitIcon(if (reveal) "eye-off" else "eye"),
                    contentDescription = stringResource(if (reveal) R.string.orbit_vault_hide else R.string.orbit_vault_show),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onCopy != null) {
            IconButton(onClick = onCopy) {
                Icon(orbitIcon("copy"), contentDescription = stringResource(R.string.orbit_vault_copy),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
