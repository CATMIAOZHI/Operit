package com.ai.assistance.operit.ui.features.orbit

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.orbit.OrbitPermissionCards
import com.ai.assistance.operit.features.orbit.OrbitTaskBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A permission card in the chat ([OrbitPermissionCards]): each setting Orbit needs, with its own
 * button while it is off, and "Don't remind me". The settings are read again whenever the user comes
 * back to Operit, so a setting turned on in the system screen shows as on. Keep-alive has two
 * settings; every other kind is one phone permission.
 */
@Composable
internal fun OrbitPermissionCard(card: JSONObject) {
    val context = LocalContext.current
    val orbitId = card.optString("orbit_id")
    val cardId = card.optString("card_id")
    val permission = card.optString("permission")
    if (orbitId.isBlank() || cardId.isBlank() || permission !in OrbitPermissionCards.kinds) return
    val orbitFlow = remember(orbitId) {
        OrbitPreferences.getInstance(context).settingsFlow.map { s -> s.orbits.firstOrNull { it.id == orbitId } }
    }
    val orbit = orbitFlow.collectAsState(initial = null).value
    val name = orbit?.name.orEmpty().ifBlank { stringResource(R.string.nav_orbit) }
    var tick by rememberSaveable(cardId) { mutableIntStateOf(0) }
    // Only a tap while this card was off starts an attempt. Restoring or revisiting an old card does not.
    var grantPending by rememberSaveable(cardId) { mutableStateOf(false) }
    var grantAfterTick by rememberSaveable(cardId) { mutableIntStateOf(0) }
    val onBeginGrant: (Boolean) -> Unit = { wasOff ->
        grantPending = wasOff
        grantAfterTick = tick
    }
    val onChecked: (Boolean, Int) -> Unit = { on, checkedTick ->
        // An older read may finish after the tap. Only a read started after the result/return counts.
        if (grantPending && checkedTick > grantAfterTick) {
            grantPending = false
            if (on) OrbitPermissionCards.grantedFromCard(context, orbitId, card.optString("chat_id"), permission, cardId)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val onDismiss = {
        orbit?.let { OrbitPermissionCards.dismiss(context, it, card.optString("chat_id"), permission, cardId) }
        Unit
    }
    Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).orbitCardGestures().padding(vertical = 4.dp)) {
        val on = when (permission) {
            OrbitPermissionCards.KEEP_ALIVE ->
                KeepAliveBody(name, tick, onRefresh = { tick++ }, onBeginGrant, onChecked)
            OrbitPermissionCards.DEVICE_ACCESS -> DeviceAccessBody(name, tick, onRefresh = { tick++ }, onBeginGrant, onChecked)
            else -> PhoneBody(permission, cardId, name, tick, onRefresh = { tick++ }, onBeginGrant, onChecked)
        }
        if (!on) DismissFooter(cardId, name, enabled = orbit != null, onDismiss)
    }
}

/**
 * Operit's own elevated access (ADB debugging through Shizuku, device admin, Root). It is set up on
 * the app's access page, which opens over the chat; coming back re-reads it. Returns whether it works.
 */
@Composable
private fun DeviceAccessBody(name: String, tick: Int, onRefresh: () -> Unit,
    onBeginGrant: (Boolean) -> Unit, onChecked: (Boolean, Int) -> Unit): Boolean {
    val context = LocalContext.current
    val openPage = LocalOrbitOpenPage.current
    // The access page opens inside the app, so coming back is no resume; it reports its close instead.
    val closed by OrbitPermissionCards.deviceAccessPageClosed.collectAsState()
    var seenClosed by remember { mutableIntStateOf(closed) }
    // Setting it up often means a trip to Shizuku or a root app and back while the access page is
    // still open. Those returns only update what the card shows; a tap is settled by the first read
    // after the page closes (from [settleTick] on), so finishing on the page still tells Orbit.
    var settleTick by remember { mutableIntStateOf(-1) }
    LaunchedEffect(closed) {
        if (closed != seenClosed) {
            seenClosed = closed
            settleTick = tick + 1
            onRefresh()
        }
    }
    val access by produceState(OrbitPermissionCards.lastDeviceAccess, tick) {
        val fresh = settleTick in 0..tick
        val read = withContext(Dispatchers.IO) {
            runCatching { OrbitPermissionCards.deviceAccess(context.applicationContext, fresh) }.getOrNull()
        }
        value = read ?: value
        if (read != null && fresh) onChecked(read.working, tick)
    }
    val english = remember(context) { com.ai.assistance.operit.util.LocaleUtils.usesEnglishPrompt(context) }
    val a = access
    val on = a?.working == true
    CardHeader("shield-check", stringResource(R.string.orbit_device_access_card_title, name),
        if (on) stringResource(R.string.orbit_device_access_card_on, OrbitPermissionCards.levelLabel(english, a?.level))
        else stringResource(R.string.orbit_device_access_note),
        subMaxLines = Int.MAX_VALUE)
    Spacer(Modifier.height(14.dp))
    PermissionRow(
        label = stringResource(R.string.orbit_device_access_title),
        on = a?.working,
        action = stringResource(R.string.orbit_permission_open_settings),
        enabled = openPage != null,
    ) {
        settleTick = -1
        onBeginGrant(!on)
        // The page opens over the chat; leaving it brings the card back on screen, which reads again.
        openPage?.invoke(OrbitCardPage.DEVICE_ACCESS)
    }
    return on
}

/** Background keep-alive and the battery exemption; returns whether both are on. */
@Composable
private fun KeepAliveBody(name: String, tick: Int, onRefresh: () -> Unit,
    onBeginGrant: (Boolean) -> Unit, onChecked: (Boolean, Int) -> Unit): Boolean {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val status by produceState(OrbitPermissionCards.lastStatus, tick) {
        val read = runCatching { OrbitTaskBlock.status(context) }.getOrNull()
        if (read != null) OrbitPermissionCards.lastStatus = read
        value = read ?: value
        if (read != null) onChecked(read.allOn, tick)
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val s = status
    val allOn = s?.allOn == true
    CardHeader("battery-charging", stringResource(R.string.orbit_permission_keep_alive_title, name),
        stringResource(if (allOn) R.string.orbit_permission_all_on else R.string.orbit_permission_keep_alive_sub))
    Spacer(Modifier.height(14.dp))
    PermissionRow(
        label = stringResource(R.string.enable_background_keep_alive),
        on = s?.keepAlive,
        action = stringResource(R.string.orbit_permission_turn_on),
        enabled = !busy,
    ) {
        busy = true
        scope.launch {
            onBeginGrant(!OrbitTaskBlock.status(context).allOn)
            runCatching { OrbitPermissionCards.enableKeepAlive(context) }
                .onFailure { Toast.makeText(context, R.string.orbit_permission_failed, Toast.LENGTH_SHORT).show() }
            busy = false
            onRefresh()
        }
    }
    Spacer(Modifier.height(8.dp))
    PermissionRow(
        label = stringResource(R.string.orbit_permission_battery),
        on = s?.battery,
        action = stringResource(R.string.orbit_permission_allow),
        enabled = !busy,
    ) {
        busy = true
        scope.launch {
            onBeginGrant(!OrbitTaskBlock.status(context).allOn)
            if (!OrbitPermissionCards.openBatteryExemption(context)) {
                onBeginGrant(false)
                Toast.makeText(context, R.string.orbit_permission_failed, Toast.LENGTH_SHORT).show()
            }
            busy = false
        }
    }
    return allOn
}

private class PhoneLook(val icon: String, val title: Int, val note: Int)

private fun phoneLook(permission: String) = when (permission) {
    OrbitPermissionCards.NOTIFY -> PhoneLook("bell", R.string.orbit_phone_notify, R.string.orbit_phone_notify_note)
    OrbitPermissionCards.NOTIFICATION_READ ->
        PhoneLook("bell-ring", R.string.orbit_phone_notification_read, R.string.orbit_phone_notification_read_note)
    OrbitPermissionCards.SMS -> PhoneLook("message-square", R.string.orbit_phone_sms, R.string.orbit_phone_sms_note)
    OrbitPermissionCards.CONTACTS -> PhoneLook("contact", R.string.orbit_phone_contacts, R.string.orbit_phone_contacts_note)
    OrbitPermissionCards.CALENDAR -> PhoneLook("calendar-days", R.string.orbit_phone_calendar, R.string.orbit_phone_calendar_note)
    OrbitPermissionCards.MICROPHONE -> PhoneLook("mic", R.string.orbit_phone_microphone, R.string.orbit_phone_microphone_note)
    OrbitPermissionCards.GEOFENCE -> PhoneLook("map-pin", R.string.orbit_tool_geofence_title, R.string.orbit_tool_geofence_note)
    else -> PhoneLook("map-pin", R.string.orbit_tool_current_location_title, R.string.orbit_tool_current_location_note)
}

/**
 * One phone permission. The button asks in the system dialog while it can; once the system stops
 * showing the dialog (denied for good, or a kind only a settings screen turns on) it opens settings.
 * Returns whether the permission is on.
 */
@Composable
private fun PhoneBody(permission: String, cardId: String, name: String, tick: Int, onRefresh: () -> Unit,
    onBeginGrant: (Boolean) -> Unit, onChecked: (Boolean, Int) -> Unit): Boolean {
    val context = LocalContext.current
    val look = phoneLook(permission)
    val activity = remember(context) { context.findActivity() }
    var asking by rememberSaveable(cardId) { mutableStateOf(false) }
    val status by produceState(OrbitPermissionCards.lastPhoneStatus[permission], permission, tick) {
        val read = withContext(Dispatchers.IO) {
            runCatching { OrbitPermissionCards.phoneStatus(context.applicationContext, permission) }.getOrNull()
        }
        value = read ?: value
        // ON_RESUME can arrive before the runtime-permission result; wait for that result first.
        if (!asking && read != null) onChecked(read.on, tick)
    }
    // Denied with "don't ask again": the system no longer shows the dialog, so the button opens settings.
    var settingsOnly by rememberSaveable(cardId) { mutableStateOf(false) }
    // Whether the system would explain the request before asking; with the answer after, this tells
    // a refusal with no dialog (refused for good earlier) from the user denying in the dialog now.
    var rationaleBefore by rememberSaveable(cardId) { mutableStateOf(false) }
    val title = stringResource(look.title)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        asking = false
        val nowOn = runCatching { OrbitPermissionCards.isOn(context, permission) }.getOrDefault(false)
        val wanted = grants.keys
        val rationaleAfter = activity != null && wanted
            .any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (!nowOn && grants.isNotEmpty() && !grants.values.all { it } && activity != null && !rationaleAfter) {
            settingsOnly = true
            if (!rationaleBefore) {
                Toast.makeText(context, context.getString(R.string.orbit_permission_in_settings, title),
                    Toast.LENGTH_LONG).show()
            }
        }
        onRefresh()
    }
    val s = status
    val on = s?.on == true
    val viaSettings = settingsOnly || activity == null || s?.canAsk == false
    val geofenceBackground = permission == OrbitPermissionCards.GEOFENCE && s?.location?.optBoolean("fine") == true
    val needsLocationService = geofenceBackground && s?.location?.optBoolean("background") == true &&
        s?.location?.optBoolean("location_services") == false
    CardHeader(look.icon, stringResource(R.string.orbit_permission_phone_title, name, title),
        if (on) stringResource(R.string.orbit_permission_phone_on, name)
        else if (needsLocationService) stringResource(R.string.orbit_tool_geofence_services_note)
        else if (geofenceBackground) stringResource(R.string.orbit_tool_geofence_background_note)
        else stringResource(look.note),
        subMaxLines = Int.MAX_VALUE)
    Spacer(Modifier.height(14.dp))
    PermissionRow(
        label = title,
        on = s?.on,
        action = stringResource(if (viaSettings) R.string.orbit_permission_open_settings else R.string.orbit_permission_turn_on),
        enabled = !asking,
    ) {
        onBeginGrant(!OrbitPermissionCards.isOn(context, permission))
        val asked = !viaSettings && runCatching {
            asking = true
            val wanted = OrbitPermissionCards.requestPermissions(context, permission)
            rationaleBefore = activity != null && wanted.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
            launcher.launch(wanted.toTypedArray())
        }.isSuccess
        if (!asked) {
            asking = false
            if (!OrbitPermissionCards.openSettings(context, permission)) {
                onBeginGrant(false)
                Toast.makeText(context, R.string.orbit_permission_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    return on
}

@Composable
private fun CardHeader(icon: String, title: String, sub: String, subMaxLines: Int = 2) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(orbitIcon(icon), null, Modifier.size(26.dp), tint = colors.onSurface)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                maxLines = subMaxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Don't remind me", or what happened to it, while the card still has something off. */
@Composable
private fun DismissFooter(cardId: String, name: String, enabled: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val dismissedIds by OrbitPermissionCards.dismissed(context).collectAsState()
    val dismissed = dismissedIds.orEmpty().contains(cardId)
    val sendingIds by OrbitPermissionCards.sending.collectAsState()
    val sending = cardId in sendingIds
    Spacer(Modifier.height(6.dp))
    if (dismissed || sending) {
        Text(stringResource(if (dismissed) R.string.orbit_permission_dismissed else R.string.orbit_permission_dismissing, name),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 10.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    } else {
        Surface(
            onClick = onDismiss,
            enabled = enabled,
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
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
