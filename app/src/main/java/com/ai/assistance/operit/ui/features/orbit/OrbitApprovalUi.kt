package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitApprovalActions
import com.ai.assistance.operit.features.orbit.OrbitApprovals
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Icon of an approval or a vault save in the chat and the list. */
internal fun approvalIcon(a: OrbitApprovals.Approval?): String = when {
    a == null -> "shield-question"
    a.kind == OrbitApprovals.VAULT_SAVED -> "lock-keyhole"
    a.state == OrbitApprovals.APPROVED -> "shield-check"
    a.state == OrbitApprovals.DENIED -> "shield-x"
    OrbitApprovalActions.type(a.action) == OrbitApprovalActions.IMPORT_PACKAGES -> "package-plus"
    OrbitApprovalActions.type(a.action) == OrbitApprovalActions.CREATE_MODEL -> "cpu"
    else -> "shield-question"
}

@Composable
internal fun approvalStateText(a: OrbitApprovals.Approval): String = stringResource(when {
    a.kind == OrbitApprovals.VAULT_SAVED ->
        if (a.detail == com.ai.assistance.operit.features.orbit.OrbitVault.LOGIN) R.string.orbit_approval_vault_login
        else R.string.orbit_approval_vault_key
    a.state == OrbitApprovals.APPROVED -> R.string.orbit_approval_state_approved
    a.state == OrbitApprovals.DENIED -> R.string.orbit_approval_state_denied
    else -> R.string.orbit_approval_state_pending
})

/** The Orbit an approval belongs to, read from the settings; null while loading or once deleted. */
@Composable
private fun rememberOrbit(orbitId: String): OrbitProfile? {
    val context = LocalContext.current
    val flow = remember(orbitId) {
        OrbitPreferences.getInstance(context).settingsFlow.map { s -> s.orbits.firstOrNull { it.id == orbitId } }
    }
    return flow.collectAsState(initial = null).value
}

/**
 * An approval request in the chat: what Orbit wants to do, and approve / decline while it waits.
 * Once answered it shows the answer; the same request is in the approval list.
 */
@Composable
internal fun OrbitApprovalCard(card: JSONObject) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val orbitId = card.optString("orbit_id")
    val id = card.optString("approval_id")
    if (orbitId.isBlank() || id.isBlank()) return
    val version by OrbitApprovals.version.collectAsState()
    // Starts from the last known state, so a card scrolled back into view keeps its height;
    // "gone" only shows once the list was really read.
    val known = remember(orbitId, id) { OrbitApprovals.cached(orbitId, id) }
    val lookup by produceState<Pair<Boolean, OrbitApprovals.Approval?>>((known != null) to known, orbitId, id, version) {
        value = true to runCatching { OrbitApprovals.find(context, orbitId, id) }.getOrNull()
    }
    val approval = lookup.second
    val loading = !lookup.first
    val orbit = rememberOrbit(orbitId)
    var sheet by remember { mutableStateOf<Boolean?>(null) } // null: closed; true: approve; false: decline
    var busy by remember { mutableStateOf(false) }
    var cut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val a = approval
    val title = a?.title ?: card.optString("title")
    val detail = a?.detail ?: if (loading) card.optString("detail") else ""
    // While the list is read the card is laid out as a waiting request, which it nearly always is.
    val waiting = a?.pending ?: loading
    Column(
        Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
            .orbitCardGestures(onClick = { if (a != null) sheet = true })
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(orbitIcon(approvalIcon(a)), null, Modifier.size(26.dp),
                        tint = if (a?.state == OrbitApprovals.DENIED) colors.error else colors.onSurface)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        a != null -> approvalStateText(a)
                        lookup.first -> stringResource(R.string.orbit_approval_card_gone)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1,
                )
            }
        }
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis,
                onTextLayout = { cut = it.hasVisualOverflow })
        }
        if (waiting) {
            val ready = a != null && orbit != null && !busy
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    onClick = { sheet = false },
                    enabled = ready,
                    shape = RoundedCornerShape(50),
                    color = colors.surface,
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.orbit_approval_deny), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Surface(
                    onClick = {
                        val target = a ?: return@Surface
                        val owner = orbit ?: return@Surface
                        // Part of the request is hidden, or a key is to be typed: that is done in the sheet.
                        if (cut || OrbitApprovalActions.needsSecret(target.action)) { sheet = true; return@Surface }
                        busy = true
                        scope.launch {
                            val result = runCatching { OrbitApprovals.decide(context, owner, target.id, true, "") }
                            busy = false
                            when {
                                result.isFailure -> Toast.makeText(context, R.string.orbit_approval_failed, Toast.LENGTH_SHORT).show()
                                result.getOrNull() == null -> Toast.makeText(context, R.string.orbit_approval_already, Toast.LENGTH_SHORT).show()
                                // The app just did something for Orbit: show what came of it.
                                target.action.isNotEmpty() -> sheet = true
                            }
                        }
                    },
                    enabled = ready,
                    shape = RoundedCornerShape(50),
                    color = colors.primary,
                    contentColor = colors.onPrimary,
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.orbit_approval_approve), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        } else {
            // Answered: the same height as the two buttons, so the card does not shrink once read.
            Spacer(Modifier.height(14.dp))
            Surface(
                onClick = { if (a != null) sheet = true },
                enabled = a != null && orbit != null,
                shape = RoundedCornerShape(50),
                color = colors.surface,
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.orbit_approval_view), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
    val open = sheet
    if (open != null && a != null && orbit != null) {
        OrbitApprovalSheet(orbit, a, startDeclining = open == false, onDismiss = { sheet = null })
    }
}

/**
 * One approval in full. While it waits: approve, or decline with an optional reason that Orbit
 * gets with the answer. Once answered, or for a vault save, it only shows what happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitApprovalSheet(
    orbit: OrbitProfile,
    approval: OrbitApprovals.Approval,
    startDeclining: Boolean = false,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var note by remember(approval.id) { mutableStateOf("") }
    var declining by remember(approval.id) { mutableStateOf(startDeclining) }
    var busy by remember(approval.id) { mutableStateOf(false) }
    // A model's API key, typed here and handed straight to the app's model settings.
    val needsKey = approval.pending && OrbitApprovalActions.needsSecret(approval.action)
    var key by remember(approval.id) { mutableStateOf("") }
    var keyVisible by remember(approval.id) { mutableStateOf(false) }
    if (needsKey) SecureWindow()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = remember(approval.id) {
            { value: androidx.compose.material3.SheetValue -> value != androidx.compose.material3.SheetValue.Hidden || !busy }
        },
    )
    fun close() {
        scope.launch {
            runCatching { sheetState.hide() }
            onDismiss()
        }
    }
    fun answer(approve: Boolean) {
        busy = true
        scope.launch {
            val result = runCatching {
                OrbitApprovals.decide(context, orbit, approval.id, approve, note,
                    secret = if (approve && needsKey) key else null)
            }
            busy = false
            if (result.isFailure) {
                if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
                // Nothing was saved: keep the sheet and what the user wrote, so they can try again.
                Toast.makeText(context, R.string.orbit_approval_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            key = ""
            val decided = result.getOrNull()
            if (decided == null) Toast.makeText(context, R.string.orbit_approval_already, Toast.LENGTH_SHORT).show()
            // When the app carried something out, stay open so the result can be read here.
            if (decided == null || !approve || approval.action.isEmpty()) close()
        }
    }
    OrbitModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        dismissEnabled = !busy,
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
                    Icon(orbitIcon(approvalIcon(approval)), null, Modifier.size(30.dp),
                        tint = if (approval.state == OrbitApprovals.DENIED) colors.error else colors.onSurface)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (approval.kind == OrbitApprovals.VAULT_SAVED) stringResource(R.string.orbit_approval_vault_title)
                else approval.title,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (approval.pending) stringResource(R.string.orbit_approval_sheet_asks, orbit.name.ifBlank { stringResource(R.string.nav_orbit) })
                else approvalStateText(approval) + " · " +
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(approval.decidedAt.takeIf { it > 0 } ?: approval.createdAt)),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Surface(shape = RoundedCornerShape(22.dp), color = colors.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Text(
                        stringResource(if (approval.kind == OrbitApprovals.VAULT_SAVED) R.string.orbit_approval_vault_site
                            else R.string.orbit_approval_what),
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (approval.kind == OrbitApprovals.VAULT_SAVED) approval.title else approval.detail,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (!approval.pending && approval.note.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(22.dp), color = colors.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        Text(stringResource(R.string.orbit_approval_your_note), style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(approval.note, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (!approval.pending && approval.result.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(22.dp), color = colors.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        Text(stringResource(R.string.orbit_approval_result), style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(approval.result, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (needsKey && !declining) {
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(22.dp), color = colors.surface, modifier = Modifier.fillMaxWidth()) {
                    TextField(
                        key, { if (it.length <= 4000) key = it }, singleLine = true, enabled = !busy,
                        label = { Text(stringResource(R.string.orbit_vault_api_key)) },
                        visualTransformation = if (keyVisible) androidx.compose.ui.text.input.VisualTransformation.None
                            else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrect = false),
                        trailingIcon = {
                            androidx.compose.material3.IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(orbitIcon(if (keyVisible) "eye-off" else "eye"),
                                    stringResource(if (keyVisible) R.string.orbit_vault_hide else R.string.orbit_vault_show))
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.Top) {
                    Icon(orbitIcon("shield-check"), null, Modifier.size(16.dp).padding(top = 2.dp), tint = colors.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.orbit_approval_model_key_promise, orbit.name.ifBlank { stringResource(R.string.nav_orbit) }),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if (approval.pending) {
                if (declining) {
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(22.dp), color = colors.surface, modifier = Modifier.fillMaxWidth()) {
                        TextField(
                            note, { if (it.length <= OrbitApprovals.MAX_NOTE) note = it }, enabled = !busy,
                            placeholder = { Text(stringResource(R.string.orbit_approval_reason_hint)) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                if (declining) {
                    Button(
                        onClick = { answer(false) }, enabled = !busy, shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.onError)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.orbit_approval_deny_send), style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Button(
                        onClick = { answer(true) }, enabled = !busy && (!needsKey || key.isNotBlank()), shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.orbit_approval_approve), style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = { declining = true }, enabled = !busy, shape = RoundedCornerShape(50),
                        color = colors.surface, modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.orbit_approval_deny), style = MaterialTheme.typography.titleMedium,
                                color = colors.error)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = { close() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(stringResource(if (approval.pending) R.string.orbit_approval_later else R.string.floating_close),
                    style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            }
        }
    }
}
