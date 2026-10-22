package com.ai.assistance.operit.features.orbit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.ui.main.MainActivity
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Runs Orbit's background tasks inside Operit's keep-alive foreground service, so the system's
 * ten minute background worker limit does not apply; each run is bounded only by its own time
 * limit. WorkManager is just the alarm: it wakes this up when something is due.
 *
 * Two lanes run side by side, so a run of hours never holds back a reminder (see
 * [OrbitTaskRepository.isLongRun]). When Android refuses the foreground service (Operit in the
 * background without keep-alive or a battery exemption), nothing runs in a cut-down way: the user
 * gets a notification saying why, and the tasks run once Operit is opened or a later wake-up works.
 */
object OrbitTaskHost {
    private const val TAG = "OrbitTasks"
    private const val BLOCKED_CHANNEL = "orbit_task_blocked"
    private const val OLD_LONG_TASK_CHANNEL = "orbit_long_task"
    private const val BLOCKED_NOTIFICATION_ID = 0x0B18
    /** How soon a wake-up that could not start the service tries again. */
    private const val BLOCKED_RETRY_MS = 15 * 60_000L
    private const val FAILURE_RETRY_MS = 60_000L
    private const val FAILURE_RETRY_MAX_MS = 60 * 60_000L
    /** Due tasks one lane runs in a row before handing back to the scheduler. */
    private const val MAX_RUNS_PER_WAKE = 50
    /** Longer than any one run (24 hours); renewed at each task, only a backstop if a release is missed. */
    private const val WAKE_LOCK_TIMEOUT_MS = 25 * 60 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    /** Lane (true = long) to the job running it. */
    private val lanes = HashMap<Boolean, Job>()
    @Volatile private var failures = 0
    /** Keeps the CPU awake while lanes run; a foreground service alone does not, with the screen off. */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /** Kept on disk, so tasks held back before the process died still run when Operit is opened. */
    private fun blockedPrefs(app: Context) = app.getSharedPreferences("orbit_task_host", Context.MODE_PRIVATE)
    private const val KEY_BLOCKED = "blocked"

    /** Starts the lanes that are not running yet. Safe to call any number of times. */
    fun wake(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            val idle = listOf(false, true).filter { lanes[it]?.isActive != true }
            if (idle.isEmpty()) {
                // Still running: the next safety wake-up covers the process dying before the lanes end.
                OrbitTaskScheduler.armSafetyWake(app)
                acquireWakeLock(app)
                return
            }
            if (lanes.values.none { it.isActive } && !AIForegroundService.holdForOrbitTasks(app)) {
                onBlocked(app)
                return
            }
            // Set before this wake-up ends: if the process dies mid-run, the tasks are picked up again.
            OrbitTaskScheduler.armSafetyWake(app)
            acquireWakeLock(app)
            if (blockedPrefs(app).getBoolean(KEY_BLOCKED, false)) {
                blockedPrefs(app).edit().putBoolean(KEY_BLOCKED, false).apply()
                app.getSystemService(NotificationManager::class.java)?.cancel(BLOCKED_NOTIFICATION_ID)
            }
            for (lane in idle) {
                // Registered before it starts, so a lane that ends at once still finds itself.
                val job = scope.launch(start = CoroutineStart.LAZY) { runLane(app, lane) }
                lanes[lane] = job
                job.invokeOnCompletion { laneEnded(app, lane, job) }
                job.start()
            }
        }
    }

    /** Operit came to the screen: tasks a refused start held back can run now. */
    fun onAppForeground(context: Context) {
        if (blockedPrefs(context.applicationContext).getBoolean(KEY_BLOCKED, false)) wake(context)
    }

    private fun laneEnded(app: Context, lane: Boolean, job: Job) {
        // Under the lock, so a wake-up holding the service again cannot be undone by this release.
        synchronized(lock) {
            if (lanes[lane] === job) lanes.remove(lane)
            if (lanes.values.any { it.isActive }) return
            OrbitTaskScheduler.cancelSafetyWake(app)
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
            AIForegroundService.releaseForOrbitTasks(app)
        }
    }

    /** Takes the lock, or renews its timeout; it is not reference counted. Call under [lock]. */
    private fun acquireWakeLock(app: Context) {
        runCatching {
            val held = wakeLock ?: app.getSystemService(android.os.PowerManager::class.java)
                ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Operit:OrbitTasks")
                ?.apply { setReferenceCounted(false) }
            held?.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = held
        }.onFailure { AppLogger.w(TAG, "Wake lock not taken: ${it.message}") }
    }

    private suspend fun runLane(app: Context, longLane: Boolean) {
        when (OperitApplication.initializeMainApplication(app)) {
            OperitApplication.MainApplicationInitResult.Initialized -> Unit
            OperitApplication.MainApplicationInitResult.MigrationInProgress -> {
                OrbitTaskScheduler.wakeAfter(app, FAILURE_RETRY_MS)
                return
            }
            else -> return
        }
        try {
            // Due tasks run one after another while the service is held; a task put off for a busy
            // chat or a quiet time ends the run of them and goes back to the scheduler.
            var runs = 0
            while (runs < MAX_RUNS_PER_WAKE) {
                // Each task gets the full timeout, however long the ones before it ran.
                synchronized(lock) { if (lanes.values.any { it.isActive }) acquireWakeLock(app) }
                val ran = try {
                    OrbitTaskRunner.runNext(app, longLane)
                } catch (e: CancellationException) {
                    // A cancelled task (paused, edited) leaves the others eligible.
                    currentCoroutineContext().ensureActive()
                    true
                }
                currentCoroutineContext().ensureActive()
                if (!ran) break
                runs++
            }
            failures = 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Task lane failed", e)
            val delay = (FAILURE_RETRY_MS shl failures.coerceAtMost(6)).coerceAtMost(FAILURE_RETRY_MAX_MS)
            failures++
            OrbitTaskScheduler.wakeAfter(app, delay)
            return
        }
        OrbitTaskScheduler.reschedule(app, fromWorker = true)
    }

    private fun onBlocked(app: Context) {
        // Only the first refusal is told; the retries after it stay quiet until a start works.
        val first = !blockedPrefs(app).getBoolean(KEY_BLOCKED, false)
        blockedPrefs(app).edit().putBoolean(KEY_BLOCKED, true).apply()
        scope.launch {
            try {
                if (first) showBlocked(app)
                OrbitTaskScheduler.wakeAfter(app, BLOCKED_RETRY_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Blocked notice not shown: ${e.message}")
            }
        }
    }

    private suspend fun showBlocked(app: Context) {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel(OLD_LONG_TASK_CHANNEL)
        manager.createNotificationChannel(NotificationChannel(BLOCKED_CHANNEL,
            app.getString(R.string.orbit_task_blocked_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val name = OrbitPreferences.getInstance(app).current().active?.name.orEmpty()
            .ifBlank { app.getString(R.string.nav_orbit) }
        val open = PendingIntent.getActivity(app, BLOCKED_NOTIFICATION_ID,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = app.getString(R.string.orbit_task_blocked_text)
        manager.notify(BLOCKED_NOTIFICATION_ID, NotificationCompat.Builder(app, BLOCKED_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_simple_foreground)
            .setContentTitle(app.getString(R.string.orbit_task_blocked_title, name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build())
    }
}
