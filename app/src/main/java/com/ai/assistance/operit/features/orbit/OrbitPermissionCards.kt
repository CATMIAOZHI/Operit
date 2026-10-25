package com.ai.assistance.operit.features.orbit

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Permission cards Orbit puts in the chat, so the user turns on what it needs with one tap:
 * keep-alive (Operit's background keep-alive and the battery-optimization exemption), and one card
 * per phone permission in [phoneKinds] (sending and reading notifications, SMS, contacts, calendar,
 * location). A new phone permission only needs its entry in [runtimePermissions], [isOn],
 * [settingsIntent] and [label].
 *
 * "Don't remind me" is not kept as a switch the app obeys: it is told to Orbit, which writes it into
 * PROACTIVE_PREFERENCES.md and decides from there. The card only remembers that the note reached
 * Orbit; while it waits for a free chat the card says so, and a note that never went out (the chat
 * stayed busy, the app closed) leaves the button there to press again.
 */
internal object OrbitPermissionCards {
    const val KEEP_ALIVE = "keep_alive"
    const val NOTIFY = "notify"
    const val NOTIFICATION_READ = "notification_read"
    const val SMS = "sms"
    const val CONTACTS = "contacts"
    const val CALENDAR = "calendar"
    const val MICROPHONE = "microphone"
    const val LOCATION = "location"
    const val GEOFENCE = "geofence"
    /** Operit's own elevated access: ADB debugging through Shizuku, device admin, or Root. */
    const val DEVICE_ACCESS = "device_access"
    val phoneKinds = listOf(NOTIFY, NOTIFICATION_READ, SMS, CONTACTS, CALENDAR, MICROPHONE, LOCATION, GEOFENCE)
    val kinds = setOf(KEEP_ALIVE, DEVICE_ACCESS) + phoneKinds

    const val MARKER = "[orbit-permission]"
    private val markerLine = Regex(Regex.escape(MARKER) + "[ \\t]*([^\\n]+)")
    private const val IDLE_WAIT_MS = 10 * 60_000L
    private const val PREFS = "orbit_permission_cards"
    private const val KEY_DISMISSED = "dismissed"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The saved list is read and written one at a time. */
    private val fileLock = kotlinx.coroutines.sync.Mutex()
    private val loading = java.util.concurrent.atomic.AtomicBoolean(false)
    private val grantSending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Card ids whose "don't remind me" reached Orbit, so every copy of the card shows it at once. */
    private val dismissedFlow = MutableStateFlow<Set<String>?>(null)
    /** Card ids whose note is waiting for the chat; kept in memory only, so a closed app allows a retry. */
    private val sendingFlow = MutableStateFlow<Set<String>>(emptySet())
    val sending: StateFlow<Set<String>> = sendingFlow

    /** The last settings read, so a card scrolled back into view starts at its real height. */
    @Volatile
    var lastStatus: OrbitTaskBlock.Status? = null

    /** A phone permission as a card shows it: on, and whether the system dialog can still ask for it. */
    data class PhoneStatus(val on: Boolean, val canAsk: Boolean, val location: JSONObject? = null)

    /** The last read of each phone permission, for the same reason as [lastStatus]. */
    val lastPhoneStatus = java.util.concurrent.ConcurrentHashMap<String, PhoneStatus>()

    /**
     * Operit's access level as the app is set to use it: [level] is the one picked in the app's
     * permission page (null before any was picked), [working] whether it is an elevated one (ADB
     * debugging, device admin or Root) that answers right now.
     */
    data class DeviceAccess(val level: com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel?, val working: Boolean)

    @Volatile
    var lastDeviceAccess: DeviceAccess? = null

    /** Counts closes of the app's access page, which opens inside Orbit, so cards read the level again. */
    private val deviceAccessClosedFlow = MutableStateFlow(0)
    val deviceAccessPageClosed: StateFlow<Int> = deviceAccessClosedFlow
    fun deviceAccessPageClosed() { deviceAccessClosedFlow.update { it + 1 } }

    private val elevatedLevels = setOf(
        com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel.DEBUGGER,
        com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel.ADMIN,
        com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel.ROOT,
    )

    private val deviceProbeLock = Any()
    /** When the last probe started and finished (elapsed realtime). */
    @Volatile
    private var deviceProbeStartedAt = 0L
    @Volatile
    private var deviceProbeDoneAt = 0L
    /** Reads close together (several cards, a page and a tool) share one probe of Shizuku or su. */
    private const val DEVICE_PROBE_REUSE_MS = 3_000L

    /**
     * Reads the access level and whether it works; it may ask Shizuku or su, so call it off the main
     * thread. Only one probe runs at a time. A read that waited for a running probe takes its answer,
     * as does one within a few seconds after it, unless [fresh] asks for a probe started after the
     * read; a changed level always probes again.
     */
    fun deviceAccess(context: Context, fresh: Boolean = false): DeviceAccess {
        val asked = android.os.SystemClock.elapsedRealtime()
        synchronized(deviceProbeLock) {
            val last = lastDeviceAccess
            val sameLevel = last != null && last.level == preferredLevel()
            val now = android.os.SystemClock.elapsedRealtime()
            val reuse = sameLevel && (deviceProbeStartedAt >= asked ||
                !fresh && (deviceProbeDoneAt >= asked || now - deviceProbeDoneAt < DEVICE_PROBE_REUSE_MS))
            if (reuse) return last!!
            deviceProbeStartedAt = now
            return probeDeviceAccess(context).also { deviceProbeDoneAt = android.os.SystemClock.elapsedRealtime() }
        }
    }

    private fun preferredLevel() = runCatching {
        com.ai.assistance.operit.data.preferences.androidPermissionPreferences.getPreferredPermissionLevel()
    }.getOrNull()

    private fun probeDeviceAccess(context: Context): DeviceAccess {
        val level = preferredLevel()
        val working = level in elevatedLevels && runCatching {
            val executor = com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
                .getExecutor(context.applicationContext, level!!)
            // Root's permission check is its availability check, so it is asked once.
            if (level == com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel.ROOT) executor.hasPermission().granted
            else executor.isAvailable() && executor.hasPermission().granted
        }.getOrDefault(false)
        return DeviceAccess(level, working).also { lastDeviceAccess = it }
    }

    /** The access level in a few words, for the card and Orbit's notes. */
    fun levelLabel(english: Boolean, level: com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel?): String = when (level) {
        com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel.ROOT -> "Root"
        com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel.ADMIN -> if (english) "device admin" else "设备管理员"
        com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel.DEBUGGER -> if (english) "ADB debugging (Shizuku)" else "ADB 调试（Shizuku）"
        com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel.ACCESSIBILITY -> if (english) "accessibility" else "无障碍"
        com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel.STANDARD -> if (english) "standard" else "普通"
        null -> if (english) "not set" else "未设置"
    }

    /** Reads [permission] now; call it off the main thread. */
    fun phoneStatus(context: Context, permission: String): PhoneStatus =
        PhoneStatus(isOn(context, permission), canAsk(context, permission),
            if (permission == LOCATION || permission == GEOFENCE) locationStatus(context) else null)
            .also { lastPhoneStatus[permission] = it }

    fun titleOf(content: String): String? =
        markerLine.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** The system permissions a phone card asks for in a dialog; empty when only a settings screen can turn it on. */
    fun runtimePermissions(permission: String): List<String> = when (permission) {
        NOTIFY -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        SMS -> listOf(Manifest.permission.READ_SMS)
        CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
        CALENDAR -> listOf(Manifest.permission.READ_CALENDAR)
        MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
        LOCATION -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        GEOFENCE -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION) +
            if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else emptyList()
        else -> emptyList()
    }

    /** Foreground precise location must be granted before requesting background access separately. */
    fun requestPermissions(context: Context, permission: String): List<String> = when {
        permission != GEOFENCE -> runtimePermissions(permission)
        !granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ->
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        Build.VERSION.SDK_INT == 29 && !granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
            listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else -> emptyList() // Android 11+: background location is enabled on the app's system settings page.
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun locationStatus(context: Context): JSONObject = JSONObject()
        .put("coarse", granted(context, Manifest.permission.ACCESS_COARSE_LOCATION))
        .put("fine", granted(context, Manifest.permission.ACCESS_FINE_LOCATION))
        .put("background", Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        .put("background_required", Build.VERSION.SDK_INT >= 29)
        .put("location_services", context.getSystemService(android.location.LocationManager::class.java)?.let {
            androidx.core.location.LocationManagerCompat.isLocationEnabled(it)
        } ?: false)

    /** Whether the phone permission is on now. Reads system settings; call it off the main thread. */
    fun isOn(context: Context, permission: String): Boolean = when (permission) {
        DEVICE_ACCESS -> deviceAccess(context).working
        NOTIFY -> NotificationManagerCompat.from(context).areNotificationsEnabled()
        NOTIFICATION_READ -> context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        // The approximate location is enough for Orbit's "where am I".
        LOCATION -> granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) || granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        GEOFENCE -> OrbitGeofence.permissionProblem(context) == null
        else -> runtimePermissions(permission).let { list -> list.isNotEmpty() && list.all { granted(context, it) } }
    }

    /** True when the system dialog can still turn it on; otherwise only the settings screen can. */
    fun canAsk(context: Context, permission: String): Boolean =
        requestPermissions(context, permission).let { list -> list.isNotEmpty() && !list.all { granted(context, it) } }

    private fun settingsIntent(context: Context, permission: String): Intent = when (permission) {
        NOTIFY -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        NOTIFICATION_READ -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        GEOFENCE -> locationStatus(context).let { status ->
            if (status.optBoolean("fine") && status.optBoolean("background") && !status.optBoolean("location_services"))
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }

    /** Opens the system screen that turns [permission] on; false when no screen could open. */
    fun openSettings(context: Context, permission: String): Boolean {
        val tries = listOf(
            settingsIntent(context, permission),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
        for (intent in tries) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        return false
    }

    /** What the card turns on, in a few words, for Orbit's notes and reply quotes. */
    fun label(english: Boolean, permission: String): String = when (permission) {
        KEEP_ALIVE -> if (english) "background keep-alive" else "后台保活"
        DEVICE_ACCESS -> if (english) "device control access (ADB / Root)" else "设备控制权限（ADB / Root）"
        NOTIFY -> if (english) "sending notifications" else "发送通知"
        NOTIFICATION_READ -> if (english) "reading notifications" else "读取通知"
        SMS -> if (english) "reading SMS" else "读取短信"
        CONTACTS -> if (english) "contacts" else "通讯录"
        CALENDAR -> if (english) "calendar" else "日历"
        MICROPHONE -> if (english) "microphone" else "麦克风"
        LOCATION -> if (english) "current location" else "当前位置"
        GEOFENCE -> if (english) "precise and background location for geofences" else "围栏所需的精准和后台定位"
        else -> permission
    }

    /** Called only after a card's recorded off -> on transition; opening settings alone never wakes Orbit. */
    fun grantedFromCard(context: Context, orbitId: String, chatId: String, permission: String, cardId: String) {
        if (chatId.isBlank() || !grantSending.add(cardId)) return
        val app = context.applicationContext
        scope.launch {
            try {
                val orbit = OrbitPreferences.getInstance(app).current().orbits
                    .firstOrNull { it.id == orbitId && !it.deleting && chatId in it.talkChatIds } ?: return@launch
                suspend fun stillOn() = withContext(Dispatchers.IO) {
                    if (permission == KEEP_ALIVE) OrbitTaskBlock.status(app).allOn else isOn(app, permission)
                }
                if (!stillOn()) return@launch
                val english = LocaleUtils.usesEnglishPrompt(app)
                val what = label(english, permission)
                val notice = if (english) {
                    "$MARKER Granted: $what\n" +
                        "The user opened the permission controls from your card and returned. " +
                        "The app checked again: permission=$permission, status=on, original_chat=$chatId. " +
                        "It was off before that attempt and is on now. This is not a message typed by the user. " +
                        "Continue the work in this original chat that needed this permission; recheck any other requirements. " +
                        "This grants only the permission, not approval for unrelated actions. Do not mention hidden notes."
                } else {
                    "$MARKER 已开启：$what\n" +
                        "用户从你的卡片打开权限设置后回来了，App 已重新核实：" +
                        "permission=$permission，status=on，原聊天=$chatId；这次操作前未开启，现在已开启。" +
                        "这不是用户打字发的消息。接着处理原聊天中需要这个权限的事情，其余条件仍要核实。" +
                        "这只表示权限已开启，不代表同意其他操作。不要提隐藏消息。"
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + IDLE_WAIT_MS
                while (true) {
                    val left = deadline - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    val told = OrbitWake.tell(app, orbit, chatId, notice, left, left,
                        ready = {
                            OrbitPreferences.getInstance(app).current().orbits.any {
                                it.id == orbitId && !it.deleting && chatId in it.talkChatIds
                            } && stillOn()
                        }, announce = false)
                    if (told != OrbitWake.Told.BUSY) break
                    kotlinx.coroutines.delay(1_500)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitPermissionCards", "Could not tell Orbit about a granted permission: ${e.message}")
            } finally {
                grantSending.remove(cardId)
            }
        }
    }

    /** Null until read; the first call reads it off the main thread, so a card never waits on the disk. */
    fun dismissed(context: Context): StateFlow<Set<String>?> {
        if (loading.compareAndSet(false, true)) {
            val app = context.applicationContext
            io.launch {
                val saved = fileLock.withLock { readDismissed(app) }
                // A card dismissed while this was read is kept.
                dismissedFlow.update { saved + it.orEmpty() }
            }
        }
        return dismissedFlow
    }

    private fun readDismissed(app: Context): Set<String> =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_DISMISSED, emptySet()).orEmpty().toSet()

    /** Turns on Operit's background keep-alive, the same as the switch in the display settings. */
    suspend fun enableKeepAlive(context: Context) {
        val app = context.applicationContext
        DisplayPreferencesManager.getInstance(app).saveDisplaySettings(enableBackgroundKeepAlive = true)
        AIForegroundService.refreshBackgroundKeepAlive(app)
    }

    /** Opens the system's battery-optimization prompt for Operit; false when no screen could open. */
    fun openBatteryExemption(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pkg = context.packageName
        val tries = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        )
        for (intent in tries) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        return false
    }

    /** The user pressed "don't remind me": Orbit hears it once its chat is free, and then the card shows it. */
    fun dismiss(context: Context, orbit: OrbitProfile, chatId: String, permission: String, cardId: String) {
        val app = context.applicationContext
        if (cardId in sendingFlow.value || cardId in dismissed(app).value.orEmpty()) return
        val target = chatId.takeIf { it in orbit.talkChatIds } ?: orbit.chatId
        if (target.isBlank()) return
        val notice = dismissNotice(LocaleUtils.usesEnglishPrompt(app), permission)
        sendingFlow.value = sendingFlow.value + cardId
        scope.launch {
            try {
                val deadline = android.os.SystemClock.elapsedRealtime() + IDLE_WAIT_MS
                while (true) {
                    val left = deadline - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    val told = OrbitWake.tell(app, orbit, target, notice, left, left,
                        onSent = { markDismissed(app, cardId) }, announce = false)
                    if (told != OrbitWake.Told.BUSY) break
                    kotlinx.coroutines.delay(1_500)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitPermissionCards", "Could not tell Orbit about a dismissed card: ${e.message}")
            } finally {
                sendingFlow.value = sendingFlow.value - cardId
            }
        }
    }

    private fun markDismissed(app: Context, cardId: String) {
        dismissed(app)
        dismissedFlow.update { it.orEmpty() + cardId }
        // Saved off the main thread, merged with the file so earlier dismissals are not dropped.
        io.launch {
            fileLock.withLock {
                val now = (readDismissed(app) + cardId).toList().takeLast(200).toSet()
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_DISMISSED, now).apply()
            }
        }
    }

    fun dismissNotice(english: Boolean, permission: String): String {
        val what = label(english, permission)
        val held = permission == KEEP_ALIVE
        return if (english) {
            "$MARKER No more $what reminders\n" +
                "The user tapped \"Don't remind me\" on the $what card. This is not a message from the user; they typed " +
                "nothing here and cannot see this note.\n" +
                "Write into the \"Never tell me about\" section of PROACTIVE_PREFERENCES.md that they do not want reminders " +
                "about $what" + (if (held) " or about tasks held back for it" else "") +
                ", changing only that section. Do not offer the card again unless they ask. " +
                "Then reply with one short, light line, or call ${OrbitReactionNotice.NOTHING_TO_DO} if nothing needs saying. " +
                "Do not mention hidden notes."
        } else {
            "$MARKER 不再提醒$what\n" +
                "用户在「$what」卡片上点了「以后别提醒」。这不是用户发的消息，用户没有在这里打字，也看不到这段话。\n" +
                "把“不想再收到关于$what" + (if (held) "、以及因此被延后的任务" else "") +
                "的提醒”写进 PROACTIVE_PREFERENCES.md 的「别跟我说」，只改这一节。" +
                "除非用户自己问，不要再放这张卡。然后轻松地回一句短话，没什么要说就调用 ${OrbitReactionNotice.NOTHING_TO_DO}。" +
                "不要提“隐藏消息”这些内部说法。"
        }
    }
}
