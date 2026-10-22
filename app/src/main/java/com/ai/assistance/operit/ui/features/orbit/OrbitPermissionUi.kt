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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.orbit.OrbitPermissionCards
import com.ai.assistance.operit.features.orbit.OrbitTaskBlock
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A permission card in the chat ([OrbitPermissionCards]): each setting Orbit needs, with its own
 * button while it is off, and "Don't remind me". The settings are read again whenever the user comes
 * back to Operit, so a setting turned on in the system screen shows as on.
 */
@Composable
internal fun OrbitPermissionCard(card: JSONObject) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val orbitId = card.optString("orbit_id")
    val cardId = card.optString("card_id")
    val permission = card.optString("permission")
    if (orbitId.isBlank() || cardId.isBlank() || permission != OrbitPermissionCards.KEEP_ALIVE) return
    val orbitFlow = remember(orbitId) {
        OrbitPreferences.getInstance(context).settingsFlow.map { s -> s.orbits.firstOrNull { it.id == orbitId } }
    }
    val orbit = orbitFlow.collectAsState(initial = null).value
    val name = orbit?.name.orEmpty().ifBlank { stringResource(R.string.nav_orbit) }
    val dismissedIds by OrbitPermissionCards.dismissed(context).collectAsState()
    val dismissed = dismissedIds.orEmpty().contains(cardId)
    val sendingIds by OrbitPermissionCards.sending.collectAsState()
    val sending = cardId in sendingIds
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val status by produceState(OrbitPermissionCards.lastStatus, tick) {
        val read = runCatching { OrbitTaskBlock.status(context) }.getOrNull()
        if (read != null) OrbitPermissionCards.lastStatus = read
        value = read ?: value
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val s = status
    val allOn = s?.allOn == true
    Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).orbitCardGestures().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(orbitIcon("battery-charging"), null, Modifier.size(26.dp), tint = colors.onSurface)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.orbit_permission_keep_alive_title, name),
                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (allOn) R.string.orbit_permission_all_on else R.string.orbit_permission_keep_alive_sub),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        PermissionRow(
            label = stringResource(R.string.enable_background_keep_alive),
            on = s?.keepAlive,
            action = stringResource(R.string.orbit_permission_turn_on),
            enabled = !busy,
        ) {
            busy = true
            scope.launch {
                runCatching { OrbitPermissionCards.enableKeepAlive(context) }
                    .onFailure { Toast.makeText(context, R.string.orbit_permission_failed, Toast.LENGTH_SHORT).show() }
                busy = false
                tick++
            }
        }
        Spacer(Modifier.height(8.dp))
        PermissionRow(
            label = stringResource(R.string.orbit_permission_battery),
            on = s?.battery,
            action = stringResource(R.string.orbit_permission_allow),
            enabled = true,
        ) {
            if (!OrbitPermissionCards.openBatteryExemption(context)) {
                Toast.makeText(context, R.string.orbit_permission_failed, Toast.LENGTH_SHORT).show()
            }
        }
        if (!allOn) {
            Spacer(Modifier.height(6.dp))
            if (dismissed || sending) {
                Text(stringResource(if (dismissed) R.string.orbit_permission_dismissed else R.string.orbit_permission_dismissing, name),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else {
                Surface(
                    onClick = {
                        val owner = orbit ?: return@Surface
                        OrbitPermissionCards.dismiss(context, owner, card.optString("chat_id"), permission, cardId)
                    },
                    enabled = orbit != null,
                    shape = RoundedCornerShape(50),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                ) {
                    Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.orbit_permission_dont_remind),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** One setting: its name, and a button while it is off. [on] null while it is being read. */
@Composable
private fun PermissionRow(label: String, on: Boolean?, action: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surface, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            when (on) {
                true -> Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = colors.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.orbit_permission_on), style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant)
                }
                false -> Surface(
                    onClick = onClick,
                    enabled = enabled,
                    shape = RoundedCornerShape(50),
                    color = colors.primary,
                    contentColor = colors.onPrimary,
                    modifier = Modifier.heightIn(min = 36.dp),
                ) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(action, style = MaterialTheme.typography.labelLarge)
                    }
                }
                null -> Unit
            }
        }
    }
}
