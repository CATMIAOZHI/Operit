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
import com.ai.assistance.operit.data.api.AccountProvider
import com.ai.assistance.operit.data.api.ProviderAccountManager
import com.ai.assistance.operit.data.api.ProviderLoginSession
import com.ai.assistance.operit.data.api.ProviderQuota
import com.ai.assistance.operit.ui.features.settings.components.ProviderQuotaPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Composable
fun ProviderAccountSettings(provider: AccountProvider, onAccountChanged: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(provider) { ProviderAccountManager.get(context, provider) }
    if (provider == AccountProvider.COMMAND_CODE) {
        CommandCodeAccountSettings(manager, onAccountChanged)
        return
    }
    val account by manager.account.collectAsState()
    val scope = rememberCoroutineScope()
    var login by remember(provider) { mutableStateOf(false) }
    var showClientConfig by remember(provider) { mutableStateOf(false) }
    var clientId by remember(provider) { mutableStateOf("") }
    var clientSecret by remember(provider) { mutableStateOf("") }
    var error by remember(provider) { mutableStateOf<String?>(null) }
    // Grok's own account row is the credential, so a probe cannot cross accounts the way a free-text
    // key field can; it still runs through the same request-id guard as the other account panels.
    val isGrok = provider == AccountProvider.GROK
    var quota by remember(provider) { mutableStateOf<ProviderQuota?>(null) }
    var quotaLoading by remember(provider) { mutableStateOf(false) }
    var quotaFailed by remember(provider) { mutableStateOf(false) }
    var quotaRequestId by remember(provider) { mutableLongStateOf(0L) }
    fun refreshQuota() {
        if (account == null) return
        val requestId = ++quotaRequestId
        quotaFailed = false
        quotaLoading = true
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { manager.fetchGrokQuota() }
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
    // A different account is a different meter, so never show the previous one's numbers.
    LaunchedEffect(account?.accessToken) {
        quota = null
        quotaFailed = false
        quotaLoading = false
        quotaRequestId++
        // Antigravity shares this composable but has no billing endpoint, so never probe for it.
        if (isGrok && account != null) refreshQuota()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (account == null) R.string.provider_account_not_logged_in
            else R.string.provider_account_logged_in), style = MaterialTheme.typography.bodyMedium)
        account?.email?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (provider == AccountProvider.ANTIGRAVITY) {
            TextButton(onClick = { showClientConfig = !showClientConfig }) {
                Text(stringResource(R.string.provider_oauth_client_settings))
            }
            if (showClientConfig) {
                OutlinedTextField(value = clientId, onValueChange = { clientId = it },
                    label = { Text("OAuth Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = clientSecret, onValueChange = { clientSecret = it },
                    label = { Text("OAuth Client Secret") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.provider_oauth_client_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { error = null; login = true }) {
                Text(stringResource(R.string.provider_account_login))
            }
            if (account != null) TextButton(onClick = {
                scope.launch { manager.logout(); onAccountChanged() }
            }) { Text(stringResource(R.string.provider_account_logout)) }
        }
        if (isGrok && account != null) {
            ProviderQuotaPanel(
                quota = quota,
                loading = quotaLoading,
                failed = quotaFailed,
                onRefresh = { refreshQuota() },
            )
        }
    }
    if (login) {
        var session by remember { mutableStateOf<ProviderLoginSession?>(null) }
        var completing by remember { mutableStateOf(false) }
        val failureText = stringResource(R.string.provider_account_login_failed)
        LaunchedEffect(provider) {
            var active: ProviderLoginSession? = null
            try {
                withTimeout(300_000) {
                    withContext(Dispatchers.IO) {
                        active = manager.startLogin(clientId, clientSecret)
                    }
                    session = active
                    val current = requireNotNull(active)
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current.authorizationUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    val callback = current.server.awaitCallback()
                    completing = true
                    manager.completeLogin(current, callback)
                    clientSecret = ""
                    clientId = ""
                    onAccountChanged()
                }
                login = false
            } catch (_: TimeoutCancellationException) {
                error = failureText
                login = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "$failureText: ${failure.message.orEmpty()}"
                login = false
            } finally {
                active?.server?.close()
            }
        }
        AlertDialog(
            onDismissRequest = { session?.server?.close(); login = false },
            title = { Text(stringResource(R.string.provider_account_login)) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(if (completing) R.string.provider_account_completing
                        else R.string.codex_login_waiting))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { session?.server?.close(); login = false }) {
                Text(stringResource(R.string.cancel_action))
            } },
        )
    }
}
