package com.ai.assistance.operit.features.orbit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.system.AndroidShellExecutor
import com.ai.assistance.operit.services.notification.OperitNotificationStore
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * - `reading` or `reading:<chapters>`: the book open in Legado; keeps Orbit's comments ready that many
 *   chapters ahead (2 to 50, default 20), waking it once fewer than half are (see [OrbitReadingWatch])
 * - `script:files/hooks/<name>.sh@<hash>` or `.py`: a small script Orbit wrote, in the phone's shell
 *   or in Python in the Ubuntu terminal; its final verdict chooses whether to wake. The hash pins the
 *   reviewed script: if edited, the check stops until the task is updated with the new script.
 *   `?proxy=<id>,…` hands it surrogates of those vault keys for the proxy; `&env=<id>,…` loads those
 *   keys' real values, which the user approves for the pinned script (see [OrbitHookVault]).
 */
object OrbitWatchSources {
    const val KIND = "WATCH"
    private const val MAX_TEXT = 20_000
    /** Below the plain shell executor's own 30 s kill, so the script's `timeout` fires first. */
    private const val SCRIPT_SECONDS = 25
    /** Python starts inside the Ubuntu environment, which takes a few seconds by itself. */
    private const val PYTHON_SECONDS = 60
    private val pythonTurn = kotlinx.coroutines.sync.Mutex()

    /** The source is not ready yet (the notification listener is still binding); skip this check quietly. */
    class NotReady(message: String) : IllegalStateException(message)
    private val PACKAGE = Regex("[A-Za-z0-9_.]{1,120}")
    val SCRIPT = Regex("script:(files/hooks/[^/@?&]{1,80}\\.(sh|py))@([0-9a-f]{16})" +
        "(?:\\?proxy=([A-Za-z0-9,-]{1,400}))?(?:[?&]env=([A-Za-z0-9,-]{1,400}))?")
    fun isScript(source: String) = SCRIPT.matches(source)

    /** A script source taken apart: [proxy] and [env] are vault entry ids. */
    data class Hook(val path: String, val python: Boolean, val hash: String, val proxy: List<String>, val env: List<String>)

    fun hook(source: String): Hook? {
        val g = SCRIPT.matchEntire(source)?.groupValues ?: return null
        fun ids(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return Hook(g[1], g[2] == "py", g[3], ids(g[4]), ids(g[5]))
    }

    fun scriptSource(path: String, hash: String, proxy: List<String>, env: List<String>): String =
        "script:$path@$hash" + (if (proxy.isEmpty()) "" else "?proxy=" + proxy.joinToString(",")) +
            (if (env.isEmpty()) "" else (if (proxy.isEmpty()) "?" else "&") + "env=" + env.joinToString(","))

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
            "reading" -> require(arg.isEmpty() || arg.toIntOrNull() in OrbitReadingWatch.AHEAD_RANGE) {
                "reading:<chapters> takes 2 to 50 (default 20)"
            }
            "script" -> require(SCRIPT.matches(source)) { "Create script watches with watch=script and the script text" }
            else -> error("watch must be notifications, sms, calendar, reading or script")
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
            else -> error("Scripts are read with readScript and reading with OrbitReadingWatch")
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
        // Access is on but the listener may not be bound yet (e.g. right after a reboot or an update).
        // If it cannot be brought back, skip this check quietly instead of reporting an empty tray as a
        // change or the task as failed.
        if (!awaitNotificationListener(context)) throw NotReady(context.getString(R.string.orbit_watch_notifications_starting))
        val wanted = packages.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return OperitNotificationStore.snapshot(200, includeOngoing = false)
            .filter { it.packageName != context.packageName && (wanted.isEmpty() || it.packageName in wanted) }
            .take(50)
            .joinToString("\n") { "[${time(it.timestamp)}] ${it.packageName}: ${it.text.replace('\n', ' ').take(500)}" }
            .ifEmpty { "(no notifications)" }
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private val listenerRecovery = Mutex()
    private var listenerRecoveryFailedAt = 0L
    private var listenerRestartedAt = 0L

    /**
     * Brings the notification listener back once for all tasks that need it at the same moment. After a
     * failed attempt, tasks due shortly after skip without waiting; turning the component off and on is
     * tried at most once per cooldown so a lasting outage does not keep toggling it.
     */
    private suspend fun awaitNotificationListener(context: Context): Boolean = listenerRecovery.withLock {
        if (OperitNotificationStore.connected) return@withLock true
        val now = SystemClock.elapsedRealtime()
        if (listenerRecoveryFailedAt != 0L && now - listenerRecoveryFailedAt < LISTENER_RETRY_MS) return@withLock false
        val listener = android.content.ComponentName(context,
            com.ai.assistance.operit.services.notification.OperitNotificationListenerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) runCatching {
            android.service.notification.NotificationListenerService.requestRebind(listener)
        }
        repeat(10) { if (!OperitNotificationStore.connected) delay(600) }
        if (!OperitNotificationStore.connected &&
            (listenerRestartedAt == 0L || SystemClock.elapsedRealtime() - listenerRestartedAt >= LISTENER_RESTART_COOLDOWN_MS)
        ) {
            // The system ignored the rebind (common after an in-place update). Turning our own listener
            // component off and on makes it bind again; the user's notification access stays granted.
            listenerRestartedAt = SystemClock.elapsedRealtime()
            val pm = context.packageManager
            try {
                pm.setComponentEnabledSetting(listener,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            } catch (e: Exception) {
                AppLogger.w("OrbitWatchSources", "Could not restart the notification listener: ${e.message}")
            } finally {
                runCatching {
                    pm.setComponentEnabledSetting(listener,
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
                }.onFailure { AppLogger.w("OrbitWatchSources", "Could not re-enable the notification listener: ${it.message}") }
            }
            repeat(10) { if (!OperitNotificationStore.connected) delay(600) }
        }
        OperitNotificationStore.connected.also { listenerRecoveryFailedAt = if (it) 0L else SystemClock.elapsedRealtime() }
    }

    private const val LISTENER_RETRY_MS = 2 * 60_000L
    private const val LISTENER_RESTART_COOLDOWN_MS = 30 * 60_000L

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

    /**
     * The variables a hook starts with: proxy surrogates of [Hook.proxy] keys and, once approved,
     * the real values of [Hook.env] keys. Also returns those real values, to mask them in what comes back.
     */
    private suspend fun hookVariables(context: Context, orbitId: String, source: String, hook: Hook, taskId: String,
        title: String): Pair<Map<String, String>, List<String>> {
        if (hook.proxy.isEmpty() && hook.env.isEmpty()) return emptyMap<String, String>() to emptyList()
        // The phone's shells log the commands they run, so real values only go to Python in Ubuntu.
        check(hook.env.isEmpty() || hook.python) { "Only Python hooks can load real values (vault_env)" }
        val entries = OrbitVault.entries(context, orbitId)
        fun entry(id: String) = entries.firstOrNull { it.id == id }
            ?: error("Vault entry $id used by this hook is no longer in the vault; update the hook")
        val vars = linkedMapOf<String, String>()
        hook.proxy.forEach { id ->
            vars[OrbitHookVault.surrogateVariable(id)] = OrbitVaultBroker.hookSurrogate(context, orbitId, entry(id), taskId)
        }
        if (hook.proxy.isNotEmpty()) vars["ORBIT_VAULT_PROXY"] = OrbitVaultBroker.base(context)
        val secrets = mutableListOf<String>()
        val missing = OrbitHookVault.unapproved(context, taskId, source, hook.env.map(::entry))
        check(missing.isEmpty()) {
            "The user has not approved this hook loading ${missing.joinToString { it.site }}; wait for the approval card"
        }
        hook.env.forEach { id ->
            val e = entry(id)
            check(e.envName.isNotEmpty() && e.secret.length >= OrbitVault.MIN_ENV_SECRET) {
                "${e.site} is not allowed in the terminal any more; use vault_proxy or ask with orbit_vault request_update"
            }
            vars[e.envName] = e.secret
            if (e.kind == OrbitVault.LOGIN && e.username.isNotEmpty()) {
                vars[e.envName + "_USERNAME"] = e.username
                // Masked like the terminal masks it, when long enough to mask.
                if (e.username.length >= OrbitVault.MIN_ENV_SECRET) secrets += e.username
            }
            secrets += e.secret
            OrbitVaultUsage.record(context, orbitId, e, OrbitVaultUsage.hook(taskId), title)
        }
        return vars to secrets
    }

    suspend fun readScript(context: Context, orbitId: String, source: String, dryRun: Boolean = false,
        taskId: String, title: String): ScriptResult {
        val hook = checkNotNull(hook(source))
        val path = hook.path
        val text = OrbitFiles.of(context, orbitId).read(path)
        check(hash(text) == hook.hash) {
            context.getString(R.string.orbit_watch_script_changed, path.substringAfterLast('/'))
        }
        val (vars, secrets) = hookVariables(context, orbitId, source, hook, taskId, title)
        // Also the escaped forms (JSON, URL, HTML) a value takes in a payload.
        fun mask(value: String) = if (secrets.isEmpty()) value else OrbitVault.redact(value, secrets)
        val result = try {
            if (hook.python) runPython(context, text, vars, dryRun, ::mask) else runShell(context, text, vars, dryRun, ::mask)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Failures quote the script's output, which may hold a loaded value.
            if (secrets.isEmpty()) throw e
            throw IllegalStateException(mask(e.message ?: e.javaClass.simpleName))
        }
        if (secrets.isEmpty()) return result
        // The payload is masked value by value: escaped text could hide a value from plain matching.
        fun maskJson(value: Any?): Any? = when (value) {
            is String -> mask(value)
            is JSONObject -> JSONObject().also { out -> value.keys().forEach { k -> out.put(mask(k), maskJson(value.get(k))) } }
            is org.json.JSONArray -> org.json.JSONArray().also { out -> (0 until value.length()).forEach { out.put(maskJson(value.get(it))) } }
            else -> value
        }
        val payload = runCatching { (maskJson(JSONObject(result.payload)) as JSONObject).toString() }.getOrElse { mask(result.payload) }
        return result.copy(reason = mask(result.reason), payload = payload, output = mask(result.output))
    }

    /** Python 3 in the Ubuntu terminal, on this phone even when the terminal is set to SSH. */
    private suspend fun runPython(context: Context, text: String, vars: Map<String, String>, dryRun: Boolean,
        mask: (String) -> String): ScriptResult {
        check(com.ai.assistance.operit.util.OnDemandResources.terminalInstalled(context)) {
            "Python hooks run in the Ubuntu terminal, which is not installed; install it first or write a sh hook"
        }
        fun b64(value: String) = java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val env = JSONObject(vars + ("HATCH_HOOK_DRY_RUN" to if (dryRun) "1" else "0")).toString()
        // The script runs as hook.py with silent()/wake() given; its text travels as base64, never as
        // arguments. The terminal logs the start of each command, so the text comes last.
        // The values are fetched once from the app's local vault service: the terminal keeps the
        // command in a temporary file while it runs, so the command carries only a one-time address.
        fun runtime(handoff: String?) = """
            import os as _o, sys as _s, json as _j, base64 as _b, urllib.request as _u
            def _verdict(kind, reason, payload):
                if not isinstance(reason, str) or not reason.strip():
                    _s.stderr.write(kind + ' needs a reason\n'); _s.stderr.flush(); _o._exit(2)
                text = payload if isinstance(payload, str) else _j.dumps({} if payload is None else payload, ensure_ascii=False)
                _s.stdout.write('\n$RESULT_MARKER\t%s\t%s\t%s\n' % (kind, _b.b64encode(reason.encode()).decode(), _b.b64encode(text.encode()).decode()))
                _s.stdout.flush(); _o._exit(0)
            def silent(reason, payload=None): _verdict('silent', reason, payload)
            def wake(reason, payload=None): _verdict('wake', reason, payload)
            ${if (handoff == null) "_o.environ.update(_j.loads(_b.b64decode('${b64(env)}').decode()))" else """
            def _env():
                import time as _t
                for _i in range(30):
                    try: return _u.build_opener(_u.ProxyHandler({})).open('$handoff', timeout=15).read().decode()
                    except _u.HTTPError as e:
                        if e.code != 503: raise
                        _t.sleep(0.5)
                raise RuntimeError('The vault service stayed busy')
            _o.environ.update(_j.loads(_env()))""".trimIndent().replace("\n", "\n            ")}
            exec(compile(_b.b64decode('${b64(text)}').decode(), 'hook.py', 'exec'), {'__name__': '__main__', 'silent': silent, 'wake': wake})
        """.trimIndent()
        // One at a time, so a hook waiting for the shared shell does not spend its own time limit waiting.
        val result = pythonTurn.withLock {
            // Only a hook given vault values fetches them; made once this run's turn has come, so it
            // cannot expire while waiting for it (starting the terminal also counts against its time).
            val handoff = if (vars.isEmpty()) null else OrbitVaultBroker.handoff(context, env)
            try {
                val command = "printf '%s' '${b64(runtime(handoff))}' | base64 -d | timeout $PYTHON_SECONDS python3 -u - 2>&1"
                com.ai.assistance.operit.core.tools.system.Terminal.getInstance(context)
                    .executeHiddenCommand(command, executorKey = "orbit-hooks", timeoutMs = (PYTHON_SECONDS + 30) * 1000L,
                        local = true, quiet = true)
            } finally {
                // Never fetched (the run failed to start): it stops being given out now.
                handoff?.let(OrbitVaultBroker::dropHandoff)
            }
        }
        check(result.state != com.ai.assistance.operit.terminal.provider.type.HiddenExecResult.State.TIMEOUT &&
            result.exitCode != 124) { context.getString(R.string.orbit_watch_script_timeout, PYTHON_SECONDS) }
        check(result.isOk) { context.getString(R.string.orbit_watch_script_failed, mask(result.error.ifBlank { result.output }).trim().take(300)) }
        check(result.exitCode == 0) {
            context.getString(R.string.orbit_watch_script_failed, mask(result.output).trim().takeLast(300))
        }
        return verdict(result.output, mask)
    }

    /** [mask] runs on the whole output before any of it is cut for an error. */
    private fun verdict(stdout: String, mask: (String) -> String): ScriptResult {
        check(stdout.length <= MAX_TEXT) { "Hook output exceeds $MAX_TEXT characters; keep logs and payload short" }
        // The result line carries reason and payload in base64, which masking cannot see; it is left out of errors.
        fun shown() = mask(stdout.lineSequence().filterNot { it.startsWith(RESULT_MARKER) }.joinToString("\n")).take(2000)
        return try { parseScriptOutput(stdout) }
        catch (e: IllegalArgumentException) {
            error("${e.message}\nScript output:\n${shown()}")
        } catch (e: org.json.JSONException) {
            error("The terminal payload must be a JSON object.\nScript output:\n${shown()}")
        }
    }

    private suspend fun runShell(context: Context, text: String, vars: Map<String, String>, dryRun: Boolean,
        mask: (String) -> String): ScriptResult {
        fun quoted(value: String) = "'" + value.replace("'", "'\\''") + "'"
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
            ${vars.entries.joinToString("\n") { (name, value) -> "export $name=${quoted(value)}" }}
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
            context.getString(R.string.orbit_watch_script_failed, mask(result.stderr.ifBlank { result.stdout }).trim().take(300))
        }
        return verdict(result.stdout, mask)
    }
}
