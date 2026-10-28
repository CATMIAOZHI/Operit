package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitOAuthSheet(orbit: OrbitProfile, request: OrbitVault.Request) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember(request) { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = remember(request) {
            { value: androidx.compose.material3.SheetValue -> value != androidx.compose.material3.SheetValue.Hidden || !opening }
        },
    )
    OrbitModalBottomSheet(
        onDismissRequest = { if (!opening) OrbitVault.close(request) },
        dismissEnabled = !opening,
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
            Button(
                enabled = !opening,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    opening = true
                    scope.launch {
                        try {
                            OrbitOAuth.start(context, request)
                            opening = false
                            sheet.hide()
                            OrbitVault.close(request)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            opening = false
                            Toast.makeText(context, R.string.orbit_oauth_open_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                },
            ) { Text(stringResource(if (opening) R.string.orbit_oauth_opening else R.string.orbit_oauth_continue)) }
            TextButton(onClick = { OrbitVault.close(request) }, enabled = !opening,
                modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.cancel)) }
        }
    }
}
