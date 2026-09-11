package com.ai.assistance.operit.ui.features.codex

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.CommandCodeCallbackServer
import com.ai.assistance.operit.data.api.ProviderAccountManager
import com.ai.assistance.operit.data.api.ProviderQuota
import com.ai.assistance.operit.ui.features.settings.components.ProviderQuotaPanel
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
        OutlinedTextField(
            value = key, onValueChange = { key = it; failed = false }, singleLine = true,
            label = { Text(stringResource(R.string.api_key)) }, visualTransformation = PasswordVisualTransformation(),
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        )
        if (failed) Text(stringResource(R.string.command_code_login_failed), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && key.isNotBlank(), onClick = {
                failed = false
                manualJob = scope.launch {
                    try {
                        manager.saveCommandCodeApiKey(key)
                        key = ""
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
                    key = ""
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
