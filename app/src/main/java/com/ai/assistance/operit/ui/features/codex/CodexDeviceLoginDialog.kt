package com.ai.assistance.operit.ui.features.codex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.CodexDeviceCode
import com.ai.assistance.operit.data.api.CodexDeviceCodeDisabledException
import com.ai.assistance.operit.data.preferences.CodexAuthState
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

@Composable
fun CodexDeviceLoginDialog(
    onDismissRequest: () -> Unit,
    onLoginSuccess: (CodexAuthState) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val coordinator = remember { CodexOAuthCoordinator(context) }
    val successText = stringResource(R.string.codex_login_success)
    var code by remember { mutableStateOf<CodexDeviceCode?>(null) }
    var cancelRequested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val started = coordinator.startDeviceLogin()
            code = started
            val state = coordinator.completeDeviceLogin(started)
            Toast.makeText(context, successText, Toast.LENGTH_LONG).show()
            onLoginSuccess(state)
            onDismissRequest()
        } catch (error: TimeoutCancellationException) {
            if (!cancelRequested) {
                Toast.makeText(context, R.string.codex_device_login_expired, Toast.LENGTH_LONG).show()
                onDismissRequest()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!cancelRequested) {
                AppLogger.e("CodexDeviceLoginDialog", "Codex device login failed", error)
                val message = if (error is CodexDeviceCodeDisabledException) {
                    resources.getString(R.string.codex_device_login_disabled)
                } else {
                    resources.getString(R.string.codex_login_failed, error.message.orEmpty())
                }
                Toast.makeText(
                    context,
                    message,
                    Toast.LENGTH_LONG,
                ).show()
                onDismissRequest()
            }
        }
    }

    val dismiss = {
        cancelRequested = true
        onDismissRequest()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.codex_device_login_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val current = code
                if (current == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.codex_device_login_starting))
                    }
                } else {
                    Text(stringResource(R.string.codex_device_login_instructions))
                    SelectionContainer {
                        Text(current.verificationUrl)
                    }
                    SelectionContainer {
                        Text(
                            current.userCode,
                            fontWeight = FontWeight.Bold,
                            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("Codex device code", current.userCode)
                            )
                        }) {
                            Text(stringResource(R.string.codex_device_login_copy))
                        }
                        Button(onClick = {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(current.verificationUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {
                                Toast.makeText(
                                    context,
                                    R.string.codex_device_login_open_elsewhere,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }) {
                            Text(stringResource(R.string.codex_device_login_open))
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(stringResource(R.string.codex_device_login_waiting))
                    Text(stringResource(R.string.codex_device_login_safety))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel_action))
            }
        },
    )
}
