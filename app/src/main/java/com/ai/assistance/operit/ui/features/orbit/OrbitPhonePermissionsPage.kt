package com.ai.assistance.operit.ui.features.orbit

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitPermissionCards
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Adding a system capability only needs a row and its status/request behavior here. */
private enum class PhonePermission(
    val title: Int, val note: Int, val icon: String, val permissions: List<String> = emptyList(),
) {
    NOTIFY(R.string.orbit_phone_notify, R.string.orbit_phone_notify_note, "bell",
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()),
    NOTIFICATION_READ(R.string.orbit_phone_notification_read, R.string.orbit_phone_notification_read_note, "bell-ring"),
    SMS(R.string.orbit_phone_sms, R.string.orbit_phone_sms_note, "message-square", listOf(Manifest.permission.READ_SMS)),
    CONTACTS(R.string.orbit_phone_contacts, R.string.orbit_phone_contacts_note, "contact", listOf(Manifest.permission.READ_CONTACTS)),
    CALENDAR(R.string.orbit_phone_calendar, R.string.orbit_phone_calendar_note, "calendar-days", listOf(Manifest.permission.READ_CALENDAR)),
    MICROPHONE(R.string.orbit_phone_microphone, R.string.orbit_phone_microphone_note, "mic", listOf(Manifest.permission.RECORD_AUDIO)),
    LOCATION(R.string.orbit_phone_location, R.string.orbit_phone_location_note, "map-pin",
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)),
    BATTERY(R.string.orbit_phone_battery, R.string.orbit_phone_battery_note, "battery"),
}

@Composable
internal fun OrbitPhonePermissionsPage(onBack: () -> Unit, onOpenDeviceAccess: (() -> Unit)? = null) {
    PhonePermissionsPage(
        entries = PhonePermission.entries.filter { it != PhonePermission.NOTIFY },
        title = R.string.orbit_phone_permissions,
        help = R.string.orbit_phone_permissions_note,
        includeKeepAlive = true,
        onBack = onBack,
        onOpenDeviceAccess = onOpenDeviceAccess,
    )
}

/**
 * The system notification permission, then this Orbit's own choice: whether what it says while
 * the app is closed also shows as a notification. The permission only allows notifications at all.
 */
@Composable
internal fun OrbitNotificationSettingsPage(orbit: OrbitProfile, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var enabled by remember(orbit.id, orbit.messageNotifications) { mutableStateOf(orbit.messageNotifications) }
    var saving by remember { mutableStateOf(false) }
    val name = orbitName(orbit)
    PhonePermissionsPage(
        entries = listOf(PhonePermission.NOTIFY),
        title = R.string.orbit_notification_settings,
        help = R.string.orbit_notification_settings_note,
        includeKeepAlive = false,
        onBack = onBack,
    ) {
        val label = stringResource(R.string.orbit_message_notify_title)
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.orbit_message_notify_note, name), Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(modifier = Modifier.semantics { contentDescription = label },
                    checked = enabled, enabled = !saving, onCheckedChange = { on ->
                        val before = enabled
                        enabled = on
                        saving = true
                        scope.launch {
                            try {
                                OrbitPreferences.getInstance(app).setMessageNotifications(orbit.id, on)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                enabled = before
                                Toast.makeText(app, R.string.orbit_phone_settings_failed, Toast.LENGTH_SHORT).show()
                            } finally {
                                saving = false
                            }
                        }
                    })
            }
        }
    }
}

@Composable
private fun PhonePermissionsPage(
    entries: List<PhonePermission>, title: Int, help: Int, includeKeepAlive: Boolean, onBack: () -> Unit,
    onOpenDeviceAccess: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val app = context.applicationContext
    val activity = remember(context) { context.findPermissionActivity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active = LocalOrbitPageActive.current
    var refresh by remember { mutableIntStateOf(0) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOnly by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val scope = rememberCoroutineScope()
    val display = remember { DisplayPreferencesManager.getInstance(app) }
    val keepAlive by display.enableBackgroundKeepAlive.collectAsState(initial = null)
    var savingKeepAlive by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle, active) {
        val observer = LifecycleEventObserver { _, event ->
            if (active && event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(active) { if (active) refresh++ }
    val result by produceState<Result<Map<PhonePermission, Boolean>>?>(null, refresh) {
        value = withContext(Dispatchers.IO) { runCatching { entries.associateWith { it.granted(app) } } }
    }
    // Operit's own elevated access; reading it may ask Shizuku or su, so it is read off the main thread.
    val deviceAccess by produceState<OrbitPermissionCards.DeviceAccess?>(OrbitPermissionCards.lastDeviceAccess, refresh) {
        if (onOpenDeviceAccess != null) {
            // Each return to this page may follow a change on the access page, so it probes anew.
            value = withContext(Dispatchers.IO) { runCatching { OrbitPermissionCards.deviceAccess(app, fresh = true) }.getOrNull() } ?: value
        }
    }
    fun openSettings(kind: PhonePermission? = null) {
        val intent = when (kind) {
            PhonePermission.NOTIFY -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
            PhonePermission.NOTIFICATION_READ -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            PhonePermission.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(context, R.string.orbit_phone_settings_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val kind = PhonePermission.entries.firstOrNull { it.name == pending }
        if (kind != null && grants.isNotEmpty() && activity != null &&
            grants.any { (permission, granted) -> !granted && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) }) {
            settingsOnly = (settingsOnly + kind.name).distinct()
        }
        pending = null
        refresh++
    }
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") { OrbitPageHeader(stringResource(title), onBack) }
        item("help") { Text(stringResource(help),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (onOpenDeviceAccess != null) item("device-access") {
            val access = deviceAccess
            val english = com.ai.assistance.operit.util.LocaleUtils.usesEnglishPrompt(app)
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(orbitIcon("shield-check"), null, Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.orbit_device_access_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(when {
                            access == null -> stringResource(R.string.orbit_phone_checking)
                            access.working -> OrbitPermissionCards.levelLabel(english, access.level)
                            else -> stringResource(R.string.orbit_phone_not_granted)
                        }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.orbit_device_access_note), Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onOpenDeviceAccess, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.orbit_phone_manage))
                    }
                }
            }
        }
        items(entries, key = { it.name }) { kind ->
            val granted = result?.getOrNull()?.get(kind)
            val settings = granted == true || kind.name in settingsOnly ||
                kind == PhonePermission.NOTIFICATION_READ || kind.permissions.isEmpty() ||
                (kind == PhonePermission.NOTIFY && kind.permissions.all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED })
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(orbitIcon(kind.icon), null, Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(kind.title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(when {
                            result == null -> R.string.orbit_phone_checking
                            result?.isFailure == true -> R.string.orbit_phone_unknown
                            granted == true -> R.string.orbit_phone_granted
                            else -> R.string.orbit_phone_not_granted
                        }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(kind.note), Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(enabled = pending == null && result != null, onClick = {
                        if (kind == PhonePermission.BATTERY && granted != true) {
                            if (!OrbitPermissionCards.openBatteryExemption(context)) openSettings(kind)
                        } else if (settings || activity == null) {
                            openSettings(kind)
                        } else {
                            pending = kind.name
                            try {
                                request.launch(kind.permissions.toTypedArray())
                            } catch (_: Exception) {
                                pending = null
                                openSettings(kind)
                            }
                        }
                    }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(if (settings) R.string.orbit_phone_manage else R.string.orbit_phone_allow))
                    }
                }
            }
        }
        if (includeKeepAlive) item("keep-alive") {
            val keepAliveLabel = stringResource(R.string.orbit_phone_keep_alive)
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(keepAliveLabel, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.orbit_phone_keep_alive_note), Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(modifier = Modifier.semantics { contentDescription = keepAliveLabel },
                        checked = keepAlive == true, enabled = keepAlive != null && !savingKeepAlive, onCheckedChange = { enabled ->
                        savingKeepAlive = true
                        scope.launch {
                            try {
                                display.saveDisplaySettings(enableBackgroundKeepAlive = enabled)
                                AIForegroundService.refreshBackgroundKeepAlive(app)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                Toast.makeText(context, R.string.orbit_phone_settings_failed, Toast.LENGTH_SHORT).show()
                            } finally {
                                savingKeepAlive = false
                            }
                        }
                    })
                }
            }
        }
        if (trailing != null) item("trailing") { trailing() }
    }
}

private fun PhonePermission.granted(context: Context): Boolean = when (this) {
    PhonePermission.NOTIFY -> NotificationManagerCompat.from(context).areNotificationsEnabled()
    PhonePermission.NOTIFICATION_READ -> context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    PhonePermission.BATTERY -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    PhonePermission.LOCATION -> permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    else -> permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

private fun Context.findPermissionActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findPermissionActivity() else null
    else -> null
}
