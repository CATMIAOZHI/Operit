package com.ai.assistance.operit.features.orbit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.system.AndroidShellExecutor
import com.ai.assistance.operit.services.notification.OperitNotificationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * What a WATCH task looks at. Phone sources wake Orbit when they change; scripts explicitly
 * choose silent or wake. Quiet checks cost no model tokens.
 *
 * - `notifications` or `notifications:<package>,<package>`: notifications now on the phone
 * - `sms` or `sms:<sender>`: the latest received text messages
 * - `calendar` or `calendar:<days>`: calendar events in the next days (1 to 30, default 7)
 * - `script:files/hooks/<name>.sh@<hash>`: a small shell script Orbit wrote; its final verdict
 *   chooses whether to wake. The hash pins the reviewed script: if edited, the check stops until the
 *   task is updated with the new script.
 */
object OrbitWatchSources {
    const val KIND = "WATCH"
    private const val MAX_TEXT = 20_000
    /** Below the plain shell executor's own 30 s kill, so the script's `timeout` fires first. */
    private const val SCRIPT_SECONDS = 25

    /** The source is not ready yet (the notification listener is still binding); skip this check quietly. */
    class NotReady(message: String) : IllegalStateException(message)
    private val PACKAGE = Regex("[A-Za-z0-9_.]{1,120}")
    val SCRIPT = Regex("script:(files/hooks/[^/@]{1,80}\\.sh)@([0-9a-f]{16})")
    fun isScript(source: String) = SCRIPT.matches(source)

    data class ScriptResult(val outcome: String, val reason: String, val payload: String, val output: String) {
        val wake get() = outcome == "wake"
        fun observation() = "$reason\n$payload"
    }
    private const val RESULT_MARKER = "__ORBIT_HOOK_RESULT__"

    /** Logs may precede the verdict; a missing, repeated or malformed final verdict is a failure. */
    fun parseScriptOutput(output: String): ScriptResult {
        val lines = output.trim().lines()
        val verdicts = lines.filter { it.startsWith("$RESULT_MARKER\t") }
        require(verdicts.size == 1 && lines.lastOrNull() == verdicts.singleOrNull()) {
            "End the script with exactly one silent \"reason\" '<json>' or wake \"reason\" '<json>'"
        }
        val fields = verdicts.single().split('\t')
        require(fields.size == 4 && fields[1] in setOf("silent", "wake")) { "Invalid hook verdict" }
        fun decode(text: String) = String(java.util.Base64.getDecoder().decode(text), Charsets.UTF_8)
        val reason = decode(fields[2])
        require(reason.isNotBlank() && reason.length <= 300) { "Hook reason must be 1 to 300 characters" }
        val payload = decode(fields[3])
        require(payload.length <= 8000) { "Hook payload must be at most 8000 characters" }
        JSONObject(payload) // Require an object, like Muse's terminal verdict payload.
        return ScriptResult(fields[1], reason, payload, lines.dropLast(1).joinToString("\n"))
    }

    fun validate(source: String) {
        require(source.length <= 2048) { "watch source is too long" }
        val (type, arg) = split(source)
        when (type) {
            "notifications" -> require(arg.isEmpty() || arg.split(',').all { PACKAGE.matches(it.trim()) }) {
                "notifications:<package>,<package> takes app package names"
            }
            "sms" -> require(arg.length <= 40) { "sms:<sender> takes a phone number or sender name" }
            "calendar" -> require(arg.isEmpty() || arg.toIntOrNull() in 1..30) { "calendar:<days> takes 1 to 30" }
            "script" -> require(SCRIPT.matches(source)) { "Create script watches with watch=script and the script text" }
            else -> error("watch must be notifications, sms, calendar or script")
        }
    }

    private fun split(source: String): Pair<String, String> =
        source.substringBefore(':') to source.substringAfter(':', "").trim()

    fun hash(script: String): String = MessageDigest.getInstance("SHA-256")
        .digest(script.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    suspend fun read(context: Context, orbitId: String, source: String): String {
        validate(source)
        val (type, arg) = split(source)
        val text = when (type) {
            "notifications" -> notifications(context, arg)
            "sms" -> sms(context, arg)
            "calendar" -> calendar(context, arg.toIntOrNull() ?: 7)
            else -> readScript(context, orbitId, source).observation()
        }
        if (text.length <= MAX_TEXT) return text
        // Only the head is shown, but the full-text digest rides along so a change past the cut
        // still changes the run fingerprint and is not mistaken for "unchanged".
        return text.take(MAX_TEXT) + "\n…(${text.length - MAX_TEXT} more characters not shown; full text digest " +
            hash(text) + ")"
    }

    private fun time(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(millis))

    private suspend fun notifications(context: Context, packages: String): String {
        check(context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)) {
            context.getString(R.string.orbit_watch_needs_notifications)
        }
        if (!OperitNotificationStore.connected) {
            // Access is on but the listener is not bound yet (e.g. right after a reboot): ask the system
            // to bind it and wait a little; if it is still not there, skip this check quietly instead of
            // reporting an empty tray as a change or the task as failed.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) runCatching {
                android.service.notification.NotificationListenerService.requestRebind(android.content.ComponentName(context,
                    com.ai.assistance.operit.services.notification.OperitNotificationListenerService::class.java))
            }
            repeat(10) { if (!OperitNotificationStore.connected) kotlinx.coroutines.delay(600) }
            if (!OperitNotificationStore.connected) throw NotReady(context.getString(R.string.orbit_watch_notifications_starting))
        }
        val wanted = packages.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return OperitNotificationStore.snapshot(200, includeOngoing = false)
            .filter { it.packageName != context.packageName && (wanted.isEmpty() || it.packageName in wanted) }
            .take(50)
            .joinToString("\n") { "[${time(it.timestamp)}] ${it.packageName}: ${it.text.replace('\n', ' ').take(500)}" }
            .ifEmpty { "(no notifications)" }
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun sms(context: Context, sender: String): String = withContext(Dispatchers.IO) {
        check(granted(context, Manifest.permission.READ_SMS)) {
            context.getString(R.string.orbit_watch_needs_sms)
        }
        val selection = if (sender.isEmpty()) null else "${Telephony.Sms.ADDRESS} LIKE ?"
        val args = if (sender.isEmpty()) null else arrayOf("%$sender%")
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            selection, args, "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val lines = mutableListOf<String>()
            while (cursor.moveToNext() && lines.size < 50) {
                lines += "[${time(cursor.getLong(2))}] ${cursor.getString(0).orEmpty()}: ${cursor.getString(1).orEmpty().replace('\n', ' ').take(500)}"
            }
            lines.joinToString("\n")
        }.orEmpty().ifEmpty { "(no messages)" }
    }

    private suspend fun calendar(context: Context, days: Int): String = withContext(Dispatchers.IO) {
        check(granted(context, Manifest.permission.READ_CALENDAR)) {
            context.getString(R.string.orbit_watch_needs_calendar)
        }
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString()).appendPath((now + days * 86_400_000L).toString()).build()
        context.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
            null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            val lines = mutableListOf<String>()
            while (cursor.moveToNext() && lines.size < 500) {
                val allDay = cursor.getInt(4) == 1
                val begin = if (allDay) SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(cursor.getLong(0))) + " (all day)"
                    else time(cursor.getLong(0)) + " - " + SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(cursor.getLong(1)))
                val place = cursor.getString(3)?.takeIf { it.isNotBlank() }?.let { " @ $it" }.orEmpty()
                lines += "$begin ${cursor.getString(2).orEmpty()}$place"
            }
            lines.joinToString("\n")
        }.orEmpty().ifEmpty { "(no events in the next $days days)" }
    }

    suspend fun readScript(context: Context, orbitId: String, source: String, dryRun: Boolean = false): ScriptResult {
        val match = checkNotNull(SCRIPT.matchEntire(source))
        val path = match.groupValues[1]
        val text = OrbitFiles.of(context, orbitId).read(path)
        check(hash(text) == match.groupValues[2]) {
            context.getString(R.string.orbit_watch_script_changed, path.substringAfterLast('/'))
        }
        // The checked text travels as base64 so no shell (plain, root or Shizuku) re-splits its quotes,
        // and the pipe makes the plain executor hand the whole line to `sh -c`. `timeout` stops the
        // script itself where the device has it; the coroutine timeout is only a backstop.
        // A soft read-only contract, not an OS sandbox. Preserve the configured shell identity and
        // network access for public API polling. The terminal functions end the script immediately.
        val runtime = """
            silent() {
                [ "${'$'}#" = 2 ] || { echo 'silent needs reason and JSON payload' >&2; exit 2; }
                printf '\n$RESULT_MARKER\tsilent\t%s\t%s\n' "${'$'}(printf '%s' "${'$'}1" | base64 | tr -d '\n')" "${'$'}(printf '%s' "${'$'}2" | base64 | tr -d '\n')"
                exit 0
            }
            wake() {
                [ "${'$'}#" = 2 ] || { echo 'wake needs reason and JSON payload' >&2; exit 2; }
                printf '\n$RESULT_MARKER\twake\t%s\t%s\n' "${'$'}(printf '%s' "${'$'}1" | base64 | tr -d '\n')" "${'$'}(printf '%s' "${'$'}2" | base64 | tr -d '\n')"
                exit 0
            }
            export HATCH_HOOK_DRY_RUN=${if (dryRun) 1 else 0}
            $text
        """.trimIndent()
        val encoded = android.util.Base64.encodeToString(runtime.toByteArray(), android.util.Base64.NO_WRAP)
        val command = "s=\"\$(echo $encoded | base64 -d)\" && " +
            "if command -v timeout > /dev/null 2>&1; then timeout $SCRIPT_SECONDS sh -c \"\$s\"; else sh -c \"\$s\"; fi"
        val result = withTimeoutOrNull((SCRIPT_SECONDS + 10) * 1000L) { AndroidShellExecutor.executeShellCommand(command) }
            ?: error(context.getString(R.string.orbit_watch_script_timeout, SCRIPT_SECONDS))
        check(result.exitCode != 124) { context.getString(R.string.orbit_watch_script_timeout, SCRIPT_SECONDS) }
        check(!(result.stderr + result.stdout).contains("Command timed out after")) {
            context.getString(R.string.orbit_watch_script_timeout, SCRIPT_SECONDS)
        }
        check(result.success) {
            context.getString(R.string.orbit_watch_script_failed, result.stderr.ifBlank { result.stdout }.trim().take(300))
        }
        check(result.stdout.length <= MAX_TEXT) { "Hook output exceeds $MAX_TEXT characters; keep logs and payload short" }
        return try { parseScriptOutput(result.stdout) }
        catch (e: IllegalArgumentException) {
            error("${e.message}\nScript output:\n${result.stdout.take(2000)}")
        } catch (e: org.json.JSONException) {
            error("The terminal payload must be a JSON object.\nScript output:\n${result.stdout.take(2000)}")
        }
    }
}
