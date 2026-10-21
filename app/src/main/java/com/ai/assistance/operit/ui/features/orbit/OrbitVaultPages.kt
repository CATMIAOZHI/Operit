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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitVault
import com.ai.assistance.operit.features.orbit.OrbitVaultNotice
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Keeps the window out of screenshots and the recent-apps preview while a secret is on screen. */
@Composable
internal fun SecureWindow() {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** 20 characters from letters, digits and a few symbols that every site accepts, from a secure source. */
private fun strongPassword(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_.!@#"
    val random = java.security.SecureRandom()
    while (true) {
        val made = String(CharArray(20) { chars[random.nextInt(chars.length)] })
        // Most sign-up forms ask for each kind at least once.
        if (made.any { it.isUpperCase() } && made.any { it.isLowerCase() } && made.any { it.isDigit() } &&
            made.any { !it.isLetterOrDigit() }) return made
    }
}

/**
 * Muse's secure entry sheet, opened from a card in the chat. What the user types goes straight
 * into the encrypted vault and never into the chat, so Orbit cannot see it. Saving tells Orbit in
 * a hidden note so it can carry on; closing tells it nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitVaultEntrySheet(orbit: OrbitProfile, request: OrbitVault.Request) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val login = request.kind == OrbitVault.LOGIN
    // Signing up or resetting: the password is new, so it is typed twice (or generated).
    val fresh = login && request.newPassword
    var username by remember(request) { mutableStateOf(request.usernameHint) }
    var secret by remember(request) { mutableStateOf("") }
    var again by remember(request) { mutableStateOf("") }
    var visible by remember(request) { mutableStateOf(false) }
    var saving by remember(request) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = MaterialTheme.colorScheme
    SecureWindow()
    fun dismiss() {
        scope.launch {
            runCatching { sheetState.hide() }
            OrbitVault.close(request)
        }
    }
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
    )
    ModalBottomSheet(
        onDismissRequest = { if (!saving) OrbitVault.close(request) },
        sheetState = sheetState,
        containerColor = colors.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()
                .padding(horizontal = 24.dp).padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = RoundedCornerShape(18.dp), color = colors.surface, shadowElevation = 1.dp, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(orbitIcon("shield-check"), null, Modifier.size(30.dp), tint = colors.onSurface)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.orbit_vault_sheet_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(when {
                    fresh -> R.string.orbit_vault_sheet_new_password
                    login -> R.string.orbit_vault_sheet_add_login
                    else -> R.string.orbit_vault_sheet_add_key
                }),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            // What it is for: the site a login fills in, or the hosts a key is sent to.
            Surface(shape = RoundedCornerShape(22.dp), color = colors.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Text(stringResource(R.string.orbit_vault_sheet_for), style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant)
                    Text(
                        if (login) request.pageUrl.ifBlank { "https://${request.site}" }.substringBefore('?')
                        else "${request.site} · ${request.apiHosts.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(22.dp), color = colors.surface, modifier = Modifier.fillMaxWidth()) {
                Column {
                    if (login) {
                        TextField(
                            username, { if (it.length <= 200) username = it }, singleLine = true, enabled = !saving,
                            label = { Text(stringResource(R.string.orbit_vault_username)) }, colors = fieldColors,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrect = false),
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = colors.outlineVariant.copy(alpha = 0.6f))
                    }
                    TextField(
                        secret, { if (it.length <= 4000) secret = it }, singleLine = true, enabled = !saving,
                        label = { Text(stringResource(if (login) R.string.orbit_vault_password else R.string.orbit_vault_api_key)) },
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                        colors = fieldColors,
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(orbitIcon(if (visible) "eye-off" else "eye"),
                                    stringResource(if (visible) R.string.orbit_vault_hide else R.string.orbit_vault_show))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    )
                    if (fresh) {
                        HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = colors.outlineVariant.copy(alpha = 0.6f))
                        TextField(
                            again, { if (it.length <= 4000) again = it }, singleLine = true, enabled = !saving,
                            label = { Text(stringResource(R.string.orbit_vault_password_confirm)) },
                            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                            colors = fieldColors,
                            isError = again.isNotEmpty() && again != secret,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        )
                    }
                }
            }
            if (fresh) {
                if (again.isNotEmpty() && again != secret) {
                    Text(stringResource(R.string.orbit_vault_password_mismatch), style = MaterialTheme.typography.bodySmall,
                        color = colors.error, modifier = Modifier.fillMaxWidth().padding(start = 18.dp, top = 6.dp))
                }
                TextButton(
                    onClick = {
                        val made = strongPassword()
                        secret = made
                        again = made
                        visible = true
                    },
                    enabled = !saving,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(orbitIcon("wand-sparkles"), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.orbit_vault_generate))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.Top) {
                Icon(orbitIcon("shield-check"), null, Modifier.size(16.dp).padding(top = 2.dp), tint = colors.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (login) stringResource(R.string.orbit_vault_entry_promise, orbit.name, request.site)
                    else stringResource(R.string.orbit_vault_entry_promise_key, orbit.name),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(28.dp))
            Button(
                enabled = !saving && secret.isNotEmpty() && (!login || username.isNotBlank()) && (!fresh || again == secret),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val id = OrbitVault.save(context, orbit.id, request, username, secret)
                            secret = ""
                            again = ""
                            // The approval list notes the save (only the site and the time), before
                            // the sheet and its scope go away.
                            runCatching {
                                com.ai.assistance.operit.features.orbit.OrbitApprovals.recordVaultSave(
                                    context, orbit.id, request.chatId, request.site, login)
                            }
                            runCatching { sheetState.hide() }
                            OrbitVault.close(request)
                            OrbitVaultNotice.send(context, orbit, request, id)
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
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(if (fresh) R.string.orbit_vault_set else R.string.orbit_vault_add),
                    style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = { dismiss() }, enabled = !saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.cancel), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            }
        }
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
