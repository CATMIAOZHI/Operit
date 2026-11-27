package com.ai.assistance.operit.ui.features.orbit

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitBrowserWatch
import com.ai.assistance.operit.features.orbit.OrbitMailAccount
import com.ai.assistance.operit.features.orbit.OrbitMailAccounts
import com.ai.assistance.operit.features.orbit.OrbitMailAuth
import com.ai.assistance.operit.features.orbit.OrbitMailAuthError
import com.ai.assistance.operit.features.orbit.OrbitMailProvider
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsRowDivider
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A sign-in being shown, step by step. [expected] is the address when signing in again. */
private sealed interface MailSignIn {
    /** The service's sign-in page is open in Orbit's browser, in [tab] once it shows. */
    data class Waiting(val provider: OrbitMailProvider, val expected: String?, val tab: String? = null) : MailSignIn
    /** It failed with [error]; offers to start over. */
    data class Failed(val provider: OrbitMailProvider, val expected: String?, val error: String) : MailSignIn
    /** The service answered; the mailbox is being checked and saved. */
    object Connecting : MailSignIn
}

/** A failure in the user's words. */
private fun describe(context: Context, e: Throwable): String = when {
    e is OrbitMailAuthError -> when (e.kind) {
        OrbitMailAuthError.Kind.TIMEOUT -> context.getString(R.string.orbit_connectors_err_timeout)
        OrbitMailAuthError.Kind.NO_BROWSER -> context.getString(R.string.orbit_connectors_err_browser)
        OrbitMailAuthError.Kind.REFUSED -> context.getString(R.string.orbit_connectors_err_refused)
        OrbitMailAuthError.Kind.WRONG_ACCOUNT -> context.getString(R.string.orbit_connectors_err_wrong_account, e.email.orEmpty())
        OrbitMailAuthError.Kind.NO_ADDRESS -> context.getString(R.string.orbit_connectors_err_no_address)
        OrbitMailAuthError.Kind.IMAP_REFUSED -> context.getString(R.string.orbit_connectors_err_imap)
        OrbitMailAuthError.Kind.SMTP_REFUSED -> context.getString(R.string.orbit_connectors_err_smtp)
        OrbitMailAuthError.Kind.NO_REFRESH -> context.getString(R.string.orbit_connectors_err_no_refresh)
        OrbitMailAuthError.Kind.TOO_MANY -> context.getString(R.string.orbit_connectors_err_too_many)
        OrbitMailAuthError.Kind.BUSY -> context.getString(R.string.orbit_connectors_err_busy)
    }
    e is java.io.IOException || e.cause is java.io.IOException || e is javax.mail.MessagingException ->
        context.getString(R.string.orbit_connectors_err_network)
    else -> context.getString(R.string.orbit_connectors_err_other)
}

/** Settings → Connectors: the connected mailboxes, and connecting Gmail or Outlook. */
@Composable
internal fun OrbitConnectorsPage(orbit: OrbitProfile, onBack: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by OrbitMailAccounts.changes.collectAsState()
    val accounts by produceState<List<OrbitMailAccount>?>(null, orbit.id, version) {
        value = runCatching { OrbitMailAccounts.list(context, orbit.id) }.getOrDefault(emptyList())
    }
    var signIn by remember { mutableStateOf<MailSignIn?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var disconnect by remember { mutableStateOf<OrbitMailAccount?>(null) }
    var disconnecting by remember { mutableStateOf(false) }
    val cardColor = settingsCardColor(false)

    fun stop() {
        job?.cancel()
        job = null
        signIn = null
    }

    fun finished(account: OrbitMailAccount?) {
        Toast.makeText(context, if (account != null) context.getString(R.string.orbit_connectors_done, account.email)
            else context.getString(R.string.orbit_connectors_cancelled), Toast.LENGTH_SHORT).show()
        signIn = null
        job = null
    }

    fun start(provider: OrbitMailProvider, expected: String?) {
        job?.cancel()
        signIn = MailSignIn.Waiting(provider, expected)
        job = scope.launch {
            // The sign-in tabs this opened; they close once the service answered, or when it stops.
            val tabs = mutableListOf<String>()
            try {
                finished(OrbitMailAuth.signIn(context, orbit.id, provider, expected,
                    open = { url, tab ->
                        // Listed before the tab exists, so stopping halfway still closes it.
                        tabs += tab
                        OrbitBrowserWatch.openSignIn(context, orbit.id, url, tab)
                            .also { if (it) signIn = MailSignIn.Waiting(provider, expected, tab) }
                    },
                    onConnecting = {
                        signIn = MailSignIn.Connecting
                        val done = tabs.toList()
                        scope.launch { OrbitBrowserWatch.closeSignIn(done) }
                    }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                signIn = MailSignIn.Failed(provider, expected, describe(context, e))
            } finally {
                OrbitBrowserWatch.closeSignIn(tabs.toList())
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OrbitPageHeader(stringResource(R.string.orbit_connectors_title), onBack, Modifier.padding(start = 4.dp))
            Text(
                stringResource(R.string.orbit_connectors_intro, orbit.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            val connected = accounts.orEmpty()
            if (connected.isNotEmpty()) {
                SectionLabel(stringResource(R.string.orbit_connectors_connected))
                SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(0.dp)) {
                    connected.forEachIndexed { index, account ->
                        if (index > 0) SettingsRowDivider(inset = false)
                        AccountRow(account, onSignInAgain = { start(account.provider, account.email) }, onDisconnect = { disconnect = account })
                    }
                }
            }
            SectionLabel(stringResource(R.string.orbit_connectors_add))
            SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(0.dp)) {
                ServiceRow("Gmail", stringResource(R.string.orbit_connectors_gmail_desc)) { start(OrbitMailProvider.GMAIL, null) }
                SettingsRowDivider(inset = false)
                ServiceRow(stringResource(R.string.orbit_connectors_outlook), stringResource(R.string.orbit_connectors_outlook_desc)) {
                    start(OrbitMailProvider.OUTLOOK, null)
                }
            }
            Text(
                stringResource(R.string.orbit_connectors_privacy) + "\n\n" + stringResource(R.string.orbit_connectors_client_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }

    val cancelButton: @Composable () -> Unit = {
        TextButton(onClick = ::stop, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
    }
    when (val shown = signIn) {
        is MailSignIn.Waiting -> OrbitDialog(
            onDismissRequest = ::stop,
            title = titleOf(shown.provider),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    shown.expected?.let { Text(stringResource(R.string.orbit_connectors_use_account, it), fontWeight = FontWeight.Medium) }
                    Waiting(stringResource(R.string.orbit_connectors_waiting))
                }
            },
            confirmButton = {
                // Back to the sign-in tab after leaving the browser window before finishing.
                shown.tab?.let { tab ->
                    OrbitDialogAction(onClick = { scope.launch { OrbitBrowserWatch.showSignIn(context, tab) } }, secondary = true) {
                        Text(stringResource(R.string.orbit_connectors_open_again))
                    }
                }
            },
            dismissButton = cancelButton,
        )
        is MailSignIn.Failed -> OrbitDialog(
            onDismissRequest = ::stop,
            title = titleOf(shown.provider),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    shown.expected?.let { Text(stringResource(R.string.orbit_connectors_use_account, it), fontWeight = FontWeight.Medium) }
                    Text(stringResource(R.string.orbit_connectors_failed, shown.error), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                OrbitDialogAction(onClick = { start(shown.provider, shown.expected) }) { Text(stringResource(R.string.orbit_connectors_retry)) }
            },
            dismissButton = cancelButton,
        )
        MailSignIn.Connecting -> OrbitDialog(
            onDismissRequest = ::stop,
            title = stringResource(R.string.orbit_connectors_title),
            text = { Waiting(stringResource(R.string.orbit_connectors_connecting)) },
            dismissButton = cancelButton,
        )
        null -> Unit
    }

    disconnect?.let { account ->
        OrbitConfirmDialog(
            title = stringResource(R.string.orbit_connectors_disconnect_title, account.email),
            message = stringResource(R.string.orbit_connectors_disconnect_text),
            confirmLabel = stringResource(R.string.orbit_connectors_disconnect),
            destructive = true,
            onDismiss = { if (!disconnecting) disconnect = null },
            onConfirm = {
                if (disconnecting) return@OrbitConfirmDialog
                disconnecting = true
                scope.launch {
                    try {
                        OrbitMailAccounts.remove(context, orbit.id, account.id)
                        disconnect = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(context, R.string.orbit_connectors_disconnect_failed, Toast.LENGTH_SHORT).show()
                    } finally {
                        disconnecting = false
                    }
                }
            },
        )
    }
}

@Composable
private fun Waiting(text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    Spacer(Modifier.width(10.dp))
    Text(text)
}

@Composable
private fun titleOf(provider: OrbitMailProvider) = stringResource(
    if (provider == OrbitMailProvider.GMAIL) R.string.orbit_connectors_gmail_title else R.string.orbit_connectors_outlook_title)

@Composable
private fun SectionLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 4.dp, top = 12.dp),
)

@Composable
private fun MailMark() = Box(
    Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
    contentAlignment = Alignment.Center,
) {
    Icon(orbitIcon("mail"), contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ServiceRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            MailMark()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(orbitIcon("plus"), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AccountRow(account: OrbitMailAccount, onSignInAgain: () -> Unit, onDisconnect: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MailMark()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(account.email, style = MaterialTheme.typography.titleMedium)
            Text(
                if (account.needsSignIn) stringResource(R.string.orbit_connectors_needs_sign_in) else account.provider.label,
                style = MaterialTheme.typography.bodySmall,
                color = if (account.needsSignIn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (account.needsSignIn) TextButton(onClick = onSignInAgain, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.orbit_connectors_sign_in_again))
            }
        }
        TextButton(onClick = onDisconnect) {
            Text(stringResource(R.string.orbit_connectors_disconnect), color = MaterialTheme.colorScheme.error)
        }
    }
}
