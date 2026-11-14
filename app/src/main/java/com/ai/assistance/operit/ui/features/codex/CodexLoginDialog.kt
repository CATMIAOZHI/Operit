package com.ai.assistance.operit.ui.features.codex

import android.content.Context
import android.widget.Toast
import com.ai.assistance.operit.ui.features.settings.components.SettingsAlertDialog as AlertDialog
import androidx.compose.material3.Text
import com.ai.assistance.operit.ui.features.settings.components.SettingsDialogTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.CodexDeviceCode
import com.ai.assistance.operit.data.preferences.CodexAuthState
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * What a ChatGPT login shows while it runs, for screens that draw it in their own style
 * (Orbit draws it as a sheet). [code] is set for the device-code login once it has one;
 * [options] is the browser login's choice between this phone's browser and a copied link.
 */
class CodexLoginView(
    val title: String,
    val status: String,
    val working: Boolean,
    val code: CodexDeviceCode? = null,
    val instructions: String? = null,
    val safety: String? = null,
    val onCopy: (() -> Unit)? = null,
    val onOpen: (() -> Unit)? = null,
    val canCancel: Boolean = true,
    val onCancel: () -> Unit,
    val options: (@Composable () -> Unit)? = null,
)

@Composable
fun CodexLoginDialog(
    onDismissRequest: () -> Unit,
    onLoginSuccess: ((CodexAuthState) -> Unit)? = null,
    presenter: (@Composable (CodexLoginView) -> Unit)? = null,
) {
    val context = LocalContext.current
    val loginSuccessText = stringResource(R.string.codex_login_success)
    val coordinator = remember { CodexOAuthCoordinator(context) }
    var session by remember { mutableStateOf<CodexOAuthLoginSession?>(null) }
    var isCompleting by remember { mutableStateOf(false) }
    var cancelRequested by remember { mutableStateOf(false) }
    val choice = remember { OAuthLoginChoice() }

    LaunchedEffect(Unit) {
        var activeSession: CodexOAuthLoginSession? = null
        try {
            val started = withContext(Dispatchers.IO) {
                coordinator.startLogin().also { activeSession = it }
            }
            session = started
            val remainingMillis = started.expiresAt - System.currentTimeMillis()
            if (remainingMillis <= 0L) {
                throw IllegalStateException("Codex OAuth session expired before sign-in started")
            }
            val callbackUri = withTimeout(remainingMillis) {
                choice.awaitCallback(started.callbackServer)
            }
            isCompleting = true
            val state = coordinator.completeLogin(started, callbackUri)
            Toast.makeText(
                context,
                loginSuccessText,
                Toast.LENGTH_LONG,
            ).show()
            onLoginSuccess?.invoke(state)
            onDismissRequest()
        } catch (error: TimeoutCancellationException) {
            AppLogger.e(TAG, "Codex OAuth login timed out", error)
            showLoginFailure(context, error)
            onDismissRequest()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!cancelRequested) {
                AppLogger.e(TAG, "Codex OAuth login failed", error)
                showLoginFailure(context, error)
                onDismissRequest()
            }
        } finally {
            activeSession?.callbackServer?.close()
            withContext(NonCancellable) {
                session = null
            }
        }
    }

    val cancelLogin = {
        cancelRequested = true
        session?.callbackServer?.close()
        onDismissRequest()
    }

    val options: @Composable () -> Unit = {
        val current = session
        OAuthLoginOptions(
            choice = choice,
            working = when {
                isCompleting -> stringResource(R.string.provider_account_completing)
                current == null -> stringResource(R.string.codex_login_starting)
                else -> null
            },
            onBrowser = {
                if (current != null && openOAuthInBrowser(context, current.authorizationUrl)) {
                    choice.step = OAuthLoginChoice.Step.BROWSER
                }
            },
            onCopy = {
                if (current != null) {
                    copyOAuthLink(context, current.authorizationUrl)
                    choice.step = OAuthLoginChoice.Step.PASTE
                }
            },
            onSubmit = { if (current != null) choice.submit(current.redirectUri, current.state) },
        )
    }

    if (presenter != null) {
        presenter(
            CodexLoginView(
                title = stringResource(R.string.codex_login_title),
                status = "",
                working = false,
                canCancel = !isCompleting,
                onCancel = { if (!isCompleting) cancelLogin() },
                options = options,
            )
        )
        return
    }

    AlertDialog(
        onDismissRequest = {
            if (!isCompleting) cancelLogin()
        },
        title = { Text(stringResource(R.string.codex_login_title)) },
        text = { OAuthDialogText(options) },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = cancelLogin) {
                Text(stringResource(R.string.cancel_action))
            }
        },
    )
}

private fun showLoginFailure(context: Context, error: Throwable) {
    Toast.makeText(
        context,
        context.getString(R.string.codex_login_failed, error.message.orEmpty()),
        Toast.LENGTH_LONG,
    ).show()
}

private const val TAG = "CodexLoginDialog"
