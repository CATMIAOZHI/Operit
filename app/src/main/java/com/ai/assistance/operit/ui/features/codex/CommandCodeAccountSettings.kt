package com.ai.assistance.operit.ui.features.codex

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.CommandCodeCallbackServer
import com.ai.assistance.operit.data.api.ProviderAccountManager
import com.ai.assistance.operit.data.api.ProviderQuota
import com.ai.assistance.operit.ui.features.settings.components.ProviderQuotaPanel
import com.ai.assistance.operit.ui.features.settings.sections.ApiKeyVisualTransformation
import kotlinx.coroutines.*

@Composable
internal fun CommandCodeAccountSettings(manager: ProviderAccountManager, onAccountChanged: () -> Unit) {
    val context = LocalContext.current
    val account by manager.account.collectAsState()
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var manualJob by remember { mutableStateOf<Job?>(null) }
    var browserLogin by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val busy = manualJob != null || browserLogin
    var quota by remember { mutableStateOf<ProviderQuota?>(null) }
    var quotaLoading by remember { mutableStateOf(false) }
    var quotaFailed by remember { mutableStateOf(false) }
    var quotaRequestId by remember { mutableLongStateOf(0L) }
    fun refreshQuota() {
        if (account == null) return
        quotaFailed = false
        val requestId = ++quotaRequestId
        quotaLoading = true
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { manager.fetchCommandCodeQuota() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            // A slow probe for a superseded account must not land on the new one's panel.
            if (requestId != quotaRequestId) return@launch
            quotaLoading = false
            // A transient failure keeps the last good numbers; only the note changes.
            if (result != null) quota = result
            quotaFailed = result == null
        }
    }
    // A different account is a different meter, so never show the previous one's numbers; bumping
    // the request id also discards a probe still in flight for the account we just left.
    LaunchedEffect(account?.accessToken) {
        // The stored key is this account's own credential, so showing it back keeps the field from
        // reading as "the key I entered vanished" after a save, and from being blank again when the
        // page is reopened. It stays masked until focused, and typing replaces it.
        key = account?.accessToken.orEmpty()
        quota = null
        quotaFailed = false
        quotaLoading = false
        quotaRequestId++
        if (account != null) refreshQuota()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (account == null) R.string.provider_account_not_logged_in else R.string.provider_account_logged_in))
        account?.email?.takeIf { it.isNotBlank() }?.let { Text(it) }
        Text(stringResource(R.string.command_code_login_hint), style = MaterialTheme.typography.bodySmall)
        // Same affordance as an ordinary provider: the stored key stays visible (middle-masked) and
        // turns into plain text while focused, so a wrong key can be corrected in place.
        val keyInteractionSource = remember { MutableInteractionSource() }
        val isKeyFocused by keyInteractionSource.collectIsFocusedAsState()
        OutlinedTextField(
            value = key, onValueChange = {
                // An ordinary provider strips whitespace as it is typed; a key pasted with stray
                // spaces would otherwise be stored verbatim and fail /alpha/whoami, reporting a
                // misleading login failure for what is really a formatting problem.
                key = it.replace("\n", "").replace("\r", "").replace(" ", ""); failed = false
            }, singleLine = true,
            label = { Text(stringResource(R.string.api_key)) },
            visualTransformation = if (isKeyFocused || key.isEmpty()) VisualTransformation.None else ApiKeyVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            interactionSource = keyInteractionSource,
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        )
        if (failed) Text(stringResource(R.string.command_code_login_failed), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Applies an edit rather than re-checking what is already stored, so the account's own
            // working key can never be replaced by a no-op press that happens to fail to validate.
            Button(enabled = !busy && key.isNotBlank() && key.trim() != account?.accessToken, onClick = {
                failed = false
                manualJob = scope.launch {
                    try {
                        manager.saveCommandCodeApiKey(key)
                        onAccountChanged()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { manualJob = null }
                }
            }) { Text(stringResource(R.string.command_code_save_key)) }
            OutlinedButton(enabled = !busy, onClick = { failed = false; browserLogin = true }) {
                Text(stringResource(R.string.provider_account_login))
            }
        }
        if (manualJob != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            TextButton(onClick = { manualJob?.cancel() }) { Text(stringResource(R.string.cancel_action)) }
        }
        if (account != null) TextButton(enabled = !busy, onClick = {
            scope.launch { manager.logout(); key = ""; onAccountChanged() }
        }) { Text(stringResource(R.string.provider_account_logout)) }
        if (account != null) {
            ProviderQuotaPanel(
                quota = quota,
                loading = quotaLoading,
                failed = quotaFailed,
                onRefresh = { refreshQuota() },
            )
        }
    }
    if (browserLogin) {
        var active by remember { mutableStateOf<CommandCodeCallbackServer?>(null) }
        var loginJob by remember { mutableStateOf<Job?>(null) }
        fun cancelLogin() {
            loginJob?.cancel()
            active?.close()
            browserLogin = false
        }
        LaunchedEffect(Unit) {
            loginJob = currentCoroutineContext()[Job]
            var server: CommandCodeCallbackServer? = null
            try {
                withTimeout(120_000) {
                    withContext(Dispatchers.IO) { server = CommandCodeCallbackServer.open() }
                    val current = requireNotNull(server)
                    active = current
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current.authorizationUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    val receivedKey = current.awaitKey()
                    manager.saveCommandCodeApiKey(receivedKey)
                    // The account effect only re-seeds when the token changes, so a re-login that
                    // returns the same key would otherwise leave the field blank again.
                    key = receivedKey.trim()
                    onAccountChanged()
                }
                browserLogin = false
            } catch (_: TimeoutCancellationException) {
                failed = true; browserLogin = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true; browserLogin = false }
            finally { server?.close() }
        }
        AlertDialog(
            onDismissRequest = { cancelLogin() },
            title = { Text(stringResource(R.string.provider_account_login)) },
            text = { Text(stringResource(R.string.codex_login_waiting)) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { cancelLogin() }) {
                Text(stringResource(R.string.cancel_action))
            } },
        )
    }
}
