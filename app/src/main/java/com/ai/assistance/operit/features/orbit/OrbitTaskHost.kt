package com.ai.assistance.operit.features.orbit

import android.app.NotificationManager
import android.content.Context
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.core.application.OperitApplication
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
 * background without keep-alive or a battery exemption), nothing runs in a cut-down way: the
 * refusal is written down ([OrbitTaskBlock]), and the tasks run once Operit is opened or a later
 * wake-up works. Opening Operit also tells Orbit, in its chat, which tasks ran late and why.
 */
object OrbitTaskHost {
    private const val TAG = "OrbitTasks"
    /** Channels of earlier versions, removed once: a refusal no longer shows a notification. */
    private val OLD_CHANNELS = listOf("orbit_task_blocked", "orbit_long_task")
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

    @Volatile private var oldChannelsRemoved = false
    /**
     * True while opening Operit reads which tasks were late; a wake-up from the scheduler in that
     * moment waits, so nothing runs before it is read. The foreground path wakes right after.
     */
    /** How many foreground reads are running; wake-ups wait while any is (Operit can come back twice quickly). */
    private val readingLate = java.util.concurrent.atomic.AtomicInteger(0)
    /** A wake-up that came while [readingLate]; the foreground path runs it once the read is done. */
    @Volatile private var wakeWaiting = false

    /** Starts the lanes that are not running yet. Safe to call any number of times. */
    fun wake(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            // Checked under the lock the foreground path takes the late tasks in, so a wake-up that
            // got here first runs before the read and one that comes after waits for it.
            if (readingLate.get() > 0) {
                wakeWaiting = true
                return
            }
            val idle = listOf(false, true).filter { lanes[it]?.isActive != true }
            if (idle.isEmpty()) {
                // Still running: the next safety wake-up covers the process dying before the lanes end.
                OrbitTaskScheduler.armSafetyWake(app)
                acquireWakeLock(app)
                return
            }
            if (lanes.values.none { it.isActive } && !AIForegroundService.holdForOrbitTasks(app)) {
                onBlocked(app, AIForegroundService.lastOrbitTasksRefusal)
                return
            }
            // Set before this wake-up ends: if the process dies mid-run, the tasks are picked up again.
            OrbitTaskScheduler.armSafetyWake(app)
            acquireWakeLock(app)
            // Started after all, without the user opening Operit: the delay passed on its own.
            OrbitTaskBlock.clear(app)
            for (lane in idle) {
                // Registered before it starts, so a lane that ends at once still finds itself.
                val job = scope.launch(start = CoroutineStart.LAZY) { runLane(app, lane) }
                lanes[lane] = job
                job.invokeOnCompletion { laneEnded(app, lane, job) }
                job.start()
            }
        }
    }

    /**
     * Operit came to the screen: tasks a refused start held back can run now. Which ones were late is
     * read first, while they are still waiting, and Orbit hears about them in its chat afterwards.
     */
    fun onAppForeground(context: Context) {
        val app = context.applicationContext
        removeOldChannels(app)
        // Set at once (no disk read on the main thread), so a background start in the meantime
        // waits and cannot clear the refusal unseen.
        readingLate.incrementAndGet()
        scope.launch {
            var episode: OrbitTaskBlock.Episode? = null
            val pending = try {
                episode = synchronized(lock) { OrbitTaskBlock.take(app) }
                episode?.let { OrbitTaskBlock.prepare(app, it) }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Late tasks not read: ${e.message}")
                emptyList()
            } finally {
                synchronized(lock) { readingLate.decrementAndGet() }
            }
            val waited = wakeWaiting
            wakeWaiting = false
            if (episode != null || waited) wake(app)
            // After the late tasks were read: settings may have changed while Operit was away (a
            // permission granted, a place to watch), and a rescheduled wake-up could run them first.
            try {
                OrbitTaskScheduler.reschedule(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Reschedule on foreground failed: ${e.message}")
            }
            if (pending.isNotEmpty()) OrbitTaskBlock.deliver(app, pending)
        }
    }

    private fun removeOldChannels(app: Context) {
        if (oldChannelsRemoved) return
        oldChannelsRemoved = true
        runCatching {
            val manager = app.getSystemService(NotificationManager::class.java) ?: return
            manager.cancel(BLOCKED_NOTIFICATION_ID)
            OLD_CHANNELS.forEach { manager.deleteNotificationChannel(it) }
        }
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

    private fun onBlocked(app: Context, refusal: String?) {
        // Written down at once, so a refusal right before the process dies is still known.
        OrbitTaskBlock.record(app, refusal)
        scope.launch {
            try {
                OrbitTaskBlock.classify(app)
                OrbitTaskScheduler.wakeAfter(app, BLOCKED_RETRY_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Refused start not recorded fully: ${e.message}")
            }
        }
    }
}
