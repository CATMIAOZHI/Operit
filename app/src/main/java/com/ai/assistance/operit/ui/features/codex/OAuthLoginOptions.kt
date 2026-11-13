package com.ai.assistance.operit.ui.features.codex

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.settings.components.LocalSettingsDialogStyle
import com.ai.assistance.operit.ui.features.settings.components.SettingsDialogStyle
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select

/**
 * Turns what the user pasted after signing in (the address the browser ended on, its query, or only
 * the code) into the callback this login waits for, on [redirectUri]. Null when it is not this
 * login's: an address or a query has to carry this login's [state]; a bare code may add it after `#`.
 */
internal fun pastedOAuthCallback(input: String, redirectUri: String, state: String): String? {
    val value = input.trim()
    if (value.isEmpty() || value.length > MAX_PASTE || value.any { it.isWhitespace() }) return null
    val query = when {
        ADDRESS.containsMatchIn(value) -> value.substringAfter('?', "").substringBefore('#')
        // Also an address the browser showed without its scheme, like localhost:1455/auth/callback?code=…
        "code=" in value || "error=" in value ->
            (if ('?' in value) value.substringAfter('?') else value.removePrefix("#")).substringBefore('#')
        else -> {
            val code = value.substringBefore('#')
            val given = value.substringAfter('#', "")
            if (code.isEmpty() || given.isNotEmpty() && given != state) return null
            return "$redirectUri?code=${encode(code)}&state=${encode(state)}"
        }
    }
    val params = query.split('&').filter { it.isNotEmpty() }.map {
        decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
    }
    fun all(name: String) = params.filter { it.first == name }.map { it.second }
    val states = all("state")
    if (states.size != 1 || states[0] != state) return null
    if (all("code").singleOrNull().isNullOrEmpty() && all("error").singleOrNull().isNullOrEmpty()) return null
    return "$redirectUri?$query"
}

private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
private fun decode(value: String) = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
private val ADDRESS = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
private const val MAX_PASTE = 16_384

/** Opens the sign-in page in the browser; false when there is none, so the link can be copied instead. */
internal fun openOAuthInBrowser(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    Toast.makeText(context, R.string.oauth_login_no_browser, Toast.LENGTH_LONG).show()
    false
}

internal fun copyOAuthLink(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.oauth_login_link_label), url))
    Toast.makeText(context, R.string.oauth_login_link_copied, Toast.LENGTH_SHORT).show()
}

/** Where a sign-in stands: choosing how, waiting for the browser, or waiting for a pasted callback. */
@Stable
internal class OAuthLoginChoice {
    enum class Step { CHOOSE, BROWSER, PASTE }

    var step by mutableStateOf(Step.CHOOSE)
    var input by mutableStateOf("")
    var invalid by mutableStateOf(false)
    private val pasted = CompletableDeferred<Uri>()

    /** Checks the pasted text against this login; the callback when it belongs to it. */
    fun submit(redirectUri: String, state: String): Uri? {
        val callback = pastedOAuthCallback(input, redirectUri, state)?.let(Uri::parse)
        invalid = callback == null
        if (callback != null) pasted.complete(callback)
        return callback
    }

    /** The browser coming back to [server] or a pasted callback, whichever comes first. */
    suspend fun awaitCallback(server: CodexOAuthLoopbackCallbackServer): Uri {
        // Not joined: a connection still being read can take seconds to give up, and a paste must
        // not wait for it. The caller closes the server once sign-in ends.
        val browser = CoroutineScope(Dispatchers.IO).async { server.awaitCallback() }
        return try {
            select { browser.onAwait { it }; pasted.onAwait { it } }
        } finally {
            browser.cancel()
            server.dropClient()
        }
    }
}

/** Dialog text for the options: Orbit's dialogs scroll by themselves, Material's do not. */
@Composable
internal fun OAuthDialogText(content: @Composable () -> Unit) {
    if (LocalSettingsDialogStyle.current == SettingsDialogStyle.ORBIT) content()
    else Column(Modifier.verticalScroll(rememberScrollState())) { content() }
}

/**
 * The two ways to sign in: in this phone's browser, or by copying the link to any browser and pasting
 * the callback back. [working] replaces them with a progress line (preparing or finishing).
 */
@Composable
internal fun OAuthLoginOptions(
    choice: OAuthLoginChoice,
    working: String?,
    onBrowser: () -> Unit,
    onCopy: () -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pill = RoundedCornerShape(50)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (working != null) {
            Progress(working)
            return@Column
        }
        when (choice.step) {
            OAuthLoginChoice.Step.CHOOSE -> {
                Button(onClick = onBrowser, shape = pill, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.oauth_login_browser))
                }
                OutlinedButton(onClick = onCopy, shape = pill, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.oauth_login_copy))
                }
                Text(stringResource(R.string.oauth_login_copy_hint), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            OAuthLoginChoice.Step.BROWSER -> {
                Progress(stringResource(R.string.codex_login_waiting))
                TextButton(onClick = { choice.step = OAuthLoginChoice.Step.PASTE }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.oauth_login_browser_stuck))
                }
            }
            OAuthLoginChoice.Step.PASTE -> {
                Text(stringResource(R.string.oauth_login_paste_hint), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
                OutlinedTextField(
                    value = choice.input,
                    onValueChange = { if (it.length <= MAX_PASTE) { choice.input = it; choice.invalid = false } },
                    label = { Text(stringResource(R.string.oauth_login_callback_label)) },
                    isError = choice.invalid,
                    supportingText = if (choice.invalid) {
                        { Text(stringResource(R.string.oauth_login_callback_invalid)) }
                    } else null,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onSubmit, enabled = choice.input.isNotBlank(), shape = pill,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.oauth_login_finish))
                }
                TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.oauth_login_copy_again))
                }
            }
        }
    }
}

@Composable
private fun Progress(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
