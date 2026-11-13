package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitOAuth
import com.ai.assistance.operit.features.orbit.OrbitOAuthProtocol
import com.ai.assistance.operit.features.orbit.OrbitVault
import com.ai.assistance.operit.ui.features.codex.OAuthLoginChoice
import com.ai.assistance.operit.ui.features.codex.OAuthLoginOptions
import com.ai.assistance.operit.ui.features.codex.copyOAuthLink
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitOAuthSheet(orbit: OrbitProfile, request: OrbitVault.Request) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var started by remember(request) { mutableStateOf<OrbitOAuth.Started?>(null) }
    var choice by remember(request) { mutableStateOf(OAuthLoginChoice()) }
    // What runs now (opening the browser, finishing a pasted callback); the sheet stays while it does.
    var working by remember(request) { mutableStateOf<Int?>(null) }
    val busy = working != null
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = remember(request) {
            { value: androidx.compose.material3.SheetValue -> value != androidx.compose.material3.SheetValue.Hidden || working == null }
        },
    )
    // Every authorization this sheet started, also ones it has since given up on after a failed paste.
    val ours = remember(request) { mutableSetOf<String>() }
    suspend fun prepared(): OrbitOAuth.Started =
        started ?: OrbitOAuth.prepare(context, request).also { started = it; ours += it.state }
    // Signed in through the browser after copying the link: the callback screen finished it, maybe
    // while a paste of the same sign-in lost the race and reset this sheet.
    val finished by OrbitOAuth.finished.collectAsState()
    LaunchedEffect(finished, busy) {
        if (finished != null && finished in ours && working == null) {
            sheet.hide()
            OrbitVault.close(request)
        }
    }
    OrbitModalBottomSheet(
        onDismissRequest = { if (!busy) OrbitVault.close(request) },
        dismissEnabled = !busy,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 24.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.orbit_oauth_title, request.site), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.orbit_oauth_desc, orbit.name), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.orbit_oauth_authorize_at, OrbitOAuthProtocol.origin(request.authorizationUrl)),
                style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.orbit_oauth_token_at, OrbitOAuthProtocol.origin(request.tokenUrl)),
                style = MaterialTheme.typography.bodyMedium)
            if (request.scope.isNotBlank()) Text(stringResource(R.string.orbit_oauth_scope, request.scope),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.orbit_vault_entry_hosts, request.apiHosts.joinToString()),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.orbit_oauth_limit), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OAuthLoginOptions(
                choice = choice,
                working = working?.let { stringResource(it) },
                onBrowser = browser@{
                    if (working != null) return@browser
                    working = R.string.orbit_oauth_opening
                    scope.launch {
                        try {
                            OrbitOAuth.open(context, prepared())
                            working = null
                            sheet.hide()
                            OrbitVault.close(request)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            working = null
                            Toast.makeText(context, R.string.orbit_oauth_open_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onCopy = copy@{
                    if (working != null) return@copy
                    working = R.string.oauth_login_preparing
                    scope.launch {
                        try {
                            copyOAuthLink(context, prepared().url)
                            working = null
                            choice.step = OAuthLoginChoice.Step.PASTE
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            working = null
                            Toast.makeText(context, R.string.orbit_oauth_open_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onSubmit = submit@{
                    if (working != null) return@submit
                    val current = started ?: return@submit
                    val callback = choice.submit(OrbitOAuth.redirectUri(context), current.state) ?: return@submit
                    working = R.string.orbit_oauth_completing
                    scope.launch {
                        var result = try {
                            OrbitOAuth.complete(context, callback)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        // The browser's own callback got there first and already finished it (and said so).
                        if (result == false && OrbitOAuth.finished.value == current.state) result = null
                        working = null
                        if (result != null) Toast.makeText(context,
                            if (result) R.string.orbit_oauth_connected else R.string.orbit_oauth_failed,
                            Toast.LENGTH_LONG).show()
                        if (result == false) {
                            // The callback was used up or had expired: sign in again from the start.
                            started = null
                            choice = OAuthLoginChoice()
                        } else {
                            sheet.hide()
                            OrbitVault.close(request)
                        }
                    }
                },
            )
            TextButton(onClick = { OrbitVault.close(request) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.cancel)) }
        }
    }
}
