package com.ai.assistance.operit.ui.features.orbit

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitVault
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Keeps the window out of screenshots and the recent-apps preview while a secret is on screen. */
@Composable
private fun SecureWindow() {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * Muse's secure entry card. Orbit asked for a login or an API key; what the user types here goes
 * straight into the encrypted vault and never into the chat, so Orbit cannot see it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitVaultEntrySheet(orbit: OrbitProfile, request: OrbitVault.Request) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val login = request.kind == OrbitVault.LOGIN
    var username by remember(request) { mutableStateOf(request.usernameHint) }
    var secret by remember(request) { mutableStateOf("") }
    var visible by remember(request) { mutableStateOf(false) }
    var saving by remember(request) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    SecureWindow()
    // The card closes by itself when the user did not answer within five minutes; say why.
    DisposableEffect(request) {
        onDispose {
            if (request.expired) {
                Toast.makeText(context.applicationContext, context.getString(R.string.orbit_vault_expired), Toast.LENGTH_LONG).show()
            }
        }
    }
    fun close(id: String?) {
        scope.launch {
            runCatching { sheetState.hide() }
            OrbitVault.finish(request, id)
        }
    }
    ModalBottomSheet(
        onDismissRequest = { if (!saving) OrbitVault.finish(request, null) },
        sheetState = sheetState,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()
                .padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center) {
                    Icon(orbitIcon("lock-keyhole"), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.orbit_vault_entry_title), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text(request.site, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                stringResource(if (login) R.string.orbit_vault_entry_login_desc else R.string.orbit_vault_entry_key_desc, orbit.name, request.site),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (login) {
                OutlinedTextField(
                    username, { if (it.length <= 200) username = it }, singleLine = true, enabled = !saving,
                    label = { Text(stringResource(R.string.orbit_vault_username)) }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrect = false),
                )
            }
            OutlinedTextField(
                secret, { if (it.length <= 4000) secret = it }, singleLine = true, enabled = !saving,
                label = { Text(stringResource(if (login) R.string.orbit_vault_password else R.string.orbit_vault_api_key)) },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(orbitIcon(if (visible) "eye-off" else "eye"),
                            stringResource(if (visible) R.string.orbit_vault_hide else R.string.orbit_vault_show))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!login) {
                Text(stringResource(R.string.orbit_vault_entry_hosts, request.apiHosts.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(orbitIcon("shield-check"), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (login) stringResource(R.string.orbit_vault_entry_promise, orbit.name, request.site)
                        else stringResource(R.string.orbit_vault_entry_promise_key, orbit.name),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { close(null) }, enabled = !saving, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    enabled = !saving && secret.isNotEmpty() && (!login || username.isNotBlank()),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        saving = true
                        scope.launch {
                            // The card already timed out or was replaced; the AI was told nothing arrived.
                            if (request.expired || request.result.isCompleted) return@launch
                            try {
                                val id = OrbitVault.save(context, orbit.id, request, username, secret)
                                runCatching { sheetState.hide() }
                                OrbitVault.finish(request, id)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                saving = false
                                Toast.makeText(context, context.getString(R.string.orbit_vault_save_failed, OrbitVault.MAX_ENTRIES), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.orbit_vault_save))
                }
            }
        }
    }
}

/** What is in the vault: sites and masked user names only, never a saved value. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitVaultSheet(orbit: OrbitProfile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var deleting by remember { mutableStateOf<OrbitVault.Entry?>(null) }
    var busy by remember { mutableStateOf(false) }
    // null while loading; a failure is kept apart from an empty vault.
    val entries by produceState<Result<List<OrbitVault.Entry>>?>(null, version) {
        value = runCatching { OrbitVault.entries(context, orbit.id) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.orbit_vault_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.orbit_vault_desc, orbit.name), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            val loaded = entries
            val list = loaded?.getOrNull()
            when {
                loaded == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list == null -> Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.orbit_vault_load_failed), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { version++ }) { Text(stringResource(R.string.orbit_vault_retry)) }
                }
                else -> if (list.isEmpty()) {
                    Text(stringResource(R.string.orbit_vault_empty, orbit.name), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp))
                } else list.sortedByDescending { it.savedAt }.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center) {
                            Icon(orbitIcon(if (entry.kind == OrbitVault.LOGIN) "key-round" else "plug"), null, Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.site, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(
                                    if (entry.kind == OrbitVault.LOGIN) OrbitVault.masked(entry.username)
                                    else stringResource(R.string.orbit_vault_api_key),
                                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.savedAt)),
                                ).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { deleting = entry }) {
                            Icon(orbitIcon("trash-2"), stringResource(R.string.delete) + " " + entry.site,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text(stringResource(R.string.orbit_vault_delete_title)) },
            text = { Text(stringResource(R.string.orbit_vault_delete_confirm, entry.site)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            OrbitVault.delete(context, orbit.id, entry.id)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(context, context.getString(R.string.orbit_vault_delete_failed), Toast.LENGTH_SHORT).show()
                        } finally {
                            busy = false; deleting = null; version++
                        }
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Muse's Import memory: copy a prompt into another AI assistant, paste its answer back, and Orbit
 * sorts the lasting facts and preferences into its own memory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitImportMemorySheet(orbit: OrbitProfile, onDismiss: () -> Unit, onSent: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prompt = stringResource(R.string.orbit_import_prompt)
    var pasted by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val tooLong = pasted.length > MAX_IMPORT
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()
                .padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.orbit_import_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.orbit_import_step1), style = MaterialTheme.typography.titleSmall)
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                Text(prompt, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
            FilledTonalButton(onClick = {
                clipboard.setText(AnnotatedString(prompt))
                Toast.makeText(context, context.getString(R.string.orbit_import_copied), Toast.LENGTH_SHORT).show()
            }) {
                Icon(orbitIcon("copy"), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.orbit_import_copy))
            }
            Text(stringResource(R.string.orbit_import_step2), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                pasted, { pasted = it },
                label = { Text(stringResource(R.string.orbit_import_paste_label)) },
                placeholder = { Text(stringResource(R.string.orbit_import_paste_hint)) },
                minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth(), isError = tooLong,
                supportingText = {
                    Text(if (tooLong) stringResource(R.string.orbit_import_too_long, pasted.length, MAX_IMPORT)
                        else "${pasted.length} / $MAX_IMPORT")
                },
            )
            Button(
                enabled = !sending && pasted.isNotBlank() && !tooLong, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    sending = true
                    sendToOrbit(context, orbit, context.getString(R.string.orbit_import_message, pasted.trim()))
                    onSent()
                },
            ) { Text(stringResource(R.string.orbit_import_send, orbit.name)) }
            Text(stringResource(R.string.orbit_import_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val MAX_IMPORT = 20_000
