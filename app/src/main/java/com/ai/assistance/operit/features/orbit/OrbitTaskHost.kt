package com.ai.assistance.operit.features.orbit

import android.app.NotificationManager
import android.content.Context
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Runs Orbit's background tasks inside Operit's keep-alive foreground service, so the system's
 * ten minute background worker limit does not apply; each run is bounded only by its own time
 * limit. WorkManager is just the alarm: it wakes this up when something is due.
 *
 * Short tasks have one lane and long ones [OrbitTaskRepository.LONG_LANES] side by side, so a run of
 * hours never holds back a reminder; the last long lane only takes page and script watches, so
 * those never wait behind long routines (see [OrbitTaskRepository.laneTakes]). Only one long lane
 * looks for a due task at a time; when it takes
 * one, the next lane starts looking, so idle lanes never read the tasks for nothing. The same task
 * never runs twice at once: claiming it checks that. When Android refuses the foreground service (Operit in the
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
    /** Lane (0 = short, 1 and up = long) to the job running it. */
    private val lanes = HashMap<Int, Job>()
    private val longLanes = (1..OrbitTaskRepository.LONG_LANES).toList()
    /** Long lanes now looking for a due task rather than running one. Under [lock]. */
    private val looking = HashSet<Int>()
    /** Lanes done with their tasks, only scheduling the next wake-up: a wake-up may start them anew. Under [lock]. */
    private val ending = HashSet<Int>()
    /** A wake-up came while a lane looked and left a long lane idle; the lane done looking starts it. Under [lock]. */
    private var lookMissed = false
    /** Jobs a wake-up took a lane over from, still scheduling the next wake-up: they keep the service held. Under [lock]. */
    private val finishing = HashSet<Job>()
    /** The [lanes] key of the job handing over reports beside the lanes. */
    private const val DELIVERY = -1
    /** More reports came while the delivery job ran; it goes on. Under [lock]. */
    private var deliverAgain = false
    /** The delivery job is past its last look; a new request starts another. Under [lock]. */
    private var deliveryClosing = false
    /** A wake-up that only the delivery job handles asked for it; it reschedules when reports wait. Under [lock]. */
    private var deliverForWake = false
    /** Lanes ending together schedule the next wake-up once: each would read every task. */
    private val rescheduling = java.util.concurrent.atomic.AtomicBoolean(false)
    private val rescheduleAgain = java.util.concurrent.atomic.AtomicBoolean(false)
    /** One place sync at a time when lanes end; it may wait half a minute for a location fix. */
    private val syncingPlaces = java.util.concurrent.atomic.AtomicBoolean(false)
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
            val idle = buildList {
                if (isFree(0)) add(0)
                // One long lane looking is enough; it calls the next once it takes a task.
                val long = idleLongLane()
                if (long != null) add(long)
                // The lane looking may have read the tasks before this wake-up's one was due.
                else if (longLanes.any { isFree(it) }) lookMissed = true
            }
            if (idle.isEmpty()) {
                // Still running or looking: the next safety wake-up covers the process dying before the lanes end.
                OrbitTaskScheduler.armSafetyWake(app)
                acquireWakeLock(app)
                // The service is held: a report due now goes out without waiting for a lane.
                // No lane started for this wake-up, so none reschedules for it when it ends.
                deliver(app, wakeTaken = true)
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
            for (lane in idle) startLane(app, lane)
            // A lane started here may run for an hour before it reschedules.
            deliver(app, wakeTaken = true)
        }
    }

    /**
     * Whether a lane other than [lane] already looks for what [lane] would take: any other long lane
     * also finds watches, only the watch lane itself ([OrbitTaskRepository.laneTakes]) finds just those.
     * Under [lock].
     */
    private fun lookedFor(lane: Int) =
        looking.any { it != lane && (it < OrbitTaskRepository.LONG_LANES || lane == OrbitTaskRepository.LONG_LANES) }

    /** Not running, or only scheduling the next wake-up after its tasks. Under [lock]. */
    private fun isFree(lane: Int) = lanes[lane]?.isActive != true || lane in ending

    /** The first free long lane whose tasks no lane looks for yet, and that takes one of [due] if given. Under [lock]. */
    private fun idleLongLane(due: List<OrbitTaskEntity>? = null): Int? = longLanes.firstOrNull { lane ->
        isFree(lane) && !lookedFor(lane) && (due == null || due.any { OrbitTaskRepository.laneTakes(lane, it) })
    }

    /**
     * Whether no lane here could take [task] now: each that could runs a task, hands over reports, or
     * looks for tasks; it reschedules when it ends, so waking every few seconds would only burn battery.
     */
    internal fun laneBusy(task: OrbitTaskEntity): Boolean = synchronized(lock) {
        (0..OrbitTaskRepository.LONG_LANES).none { OrbitTaskRepository.laneTakes(it, task) && isFree(it) }
    }

    /** Under [lock]. Registered before it starts, so a lane that ends at once still finds itself. */
    private fun startLane(app: Context, lane: Int) {
        // Taken over while it still schedules the next wake-up: it holds the service until it ends.
        lanes[lane]?.takeIf { it.isActive }?.let { finishing.add(it) }
        val job = scope.launch(start = CoroutineStart.LAZY) { runLane(app, lane) }
        lanes[lane] = job
        ending.remove(lane)
        if (lane > 0) looking.add(lane)
        job.invokeOnCompletion { laneEnded(app, lane, job) }
        job.start()
    }

    /**
     * A long lane is done looking: when it took a task while others ([due]) look due, or a wake-up
     * came while it looked, one more long lane looks. The lanes running already hold the service,
     * the wake lock and the safety wake-up.
     */
    private fun lookedDone(app: Context, lane: Int, me: Job, due: List<OrbitTaskEntity>): Unit = synchronized(lock) {
        if (lanes[lane] === me) looking.remove(lane)
        val missed = lookMissed
        if (due.isEmpty() && !missed) return@synchronized
        if (readingLate.get() > 0) {
            // The foreground path wakes once it has read the late tasks.
            lookMissed = false
            wakeWaiting = true
            return@synchronized
        }
        if (lanes.values.none { it.isActive }) return@synchronized
        // Kept until a lane starts for it: another lane still looking may leave none free yet.
        val next = idleLongLane(if (missed) null else due) ?: return@synchronized
        startLane(app, next)
        lookMissed = false
    }

    /**
     * Reports wait for a chat for minutes at times; they go out beside the lanes, which go on meanwhile.
     * The next wake-up is scheduled again when reports changed, or with [wakeTaken] (a wake-up, which a
     * lane may not reschedule for until long after) when any wait. A lane asking after each task
     * reschedules only on a change: else a burst of quick tasks would keep moving it for nothing.
     */
    private fun deliver(app: Context, wakeTaken: Boolean = false): Unit = synchronized(lock) {
        if (wakeTaken) deliverForWake = true
        if (lanes[DELIVERY]?.isActive == true && !deliveryClosing) {
            deliverAgain = true
            return@synchronized
        }
        lanes[DELIVERY]?.takeIf { it.isActive }?.let { finishing.add(it) }
        deliverAgain = false
        deliveryClosing = false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // The same gate as the lanes: no stored data is opened while the app migrates or restores;
            // the lanes started with it schedule the retry.
            if (OperitApplication.initializeMainApplication(app) !=
                OperitApplication.MainApplicationInitResult.Initialized) return@launch
            var reschedule = false
            while (true) {
                val forWake = synchronized(lock) { deliverForWake.also { deliverForWake = false } }
                try {
                    val changed = OrbitTaskRunner.deliverReports(app)
                    if (changed == true || (changed != null && forWake)) reschedule = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Reports not handed over: ${e.message}")
                    // Tried again soon: this wake-up may have been the only one for them.
                    OrbitTaskScheduler.wakeAfter(app, FAILURE_RETRY_MS)
                }
                val again = synchronized(lock) {
                    deliverAgain.also {
                        deliverAgain = false
                        if (!it) deliveryClosing = true
                    }
                }
                if (!again) break
            }
            // What is still waiting (a busy chat, the report interval) gets its wake-up. Reports change no
            // watched place, so this only reads the tasks, without a location fix.
            if (reschedule) {
                try {
                    OrbitTaskScheduler.reschedule(app, fromWorker = true, syncPlaces = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Reschedule after reports failed: ${e.message}")
                    OrbitTaskScheduler.wakeAfter(app, FAILURE_RETRY_MS)
                }
            }
        }
        lanes[DELIVERY] = job
        job.invokeOnCompletion { laneEnded(app, DELIVERY, job) }
        job.start()
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

    private fun laneEnded(app: Context, lane: Int, job: Job) {
        // Under the lock, so a wake-up holding the service again cannot be undone by this release.
        synchronized(lock) {
            finishing.remove(job)
            // A newer job may hold this lane already; its marks stay.
            if (lanes[lane] === job) {
                lanes.remove(lane)
                looking.remove(lane)
                ending.remove(lane)
            }
            if (lanes.values.any { it.isActive } || finishing.isNotEmpty()) return
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

    private suspend fun runLane(app: Context, lane: Int) {
        val longLane = lane > 0
        val me = currentCoroutineContext().job
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
                val look = synchronized(lock) {
                    if (lanes.values.any { it.isActive }) acquireWakeLock(app)
                    when {
                        !longLane -> true
                        // A newer job took this lane over.
                        lanes[lane] !== me -> false
                        // Started to look by a wake-up.
                        lane in looking -> true
                        // Another lane looks already; what it misses, this lane's reschedule wakes for.
                        lookedFor(lane) -> false
                        else -> {
                            looking.add(lane)
                            true
                        }
                    }
                }
                if (!look) break
                val ran = try {
                    OrbitTaskRunner.runNext(app, lane) { moreDue ->
                        if (longLane) lookedDone(app, lane, me, moreDue)
                    }
                } catch (e: CancellationException) {
                    // A cancelled task (paused, edited) leaves the others eligible.
                    currentCoroutineContext().ensureActive()
                    true
                }
                // Done looking either way (also when it failed before telling); a wake-up may start another lane.
                if (longLane) synchronized(lock) { if (lanes[lane] === me) looking.remove(lane) }
                deliver(app)
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
        // From here a wake-up may start this lane anew, and the scheduler counts it free.
        synchronized(lock) { if (lanes[lane] === me) ending.add(lane) }
        rescheduleOnce(app)
    }

    /**
     * Lanes ending together share one reschedule; one asked for while it runs runs again after. If
     * it fails, a wake-up soon tries again for every lane that asked.
     */
    private suspend fun rescheduleOnce(app: Context) {
        rescheduleAgain.set(true)
        var drained = false
        while (rescheduleAgain.get() && rescheduling.compareAndSet(false, true)) {
            drained = true
            try {
                while (rescheduleAgain.getAndSet(false)) {
                    OrbitTaskScheduler.reschedule(app, fromWorker = true, syncPlaces = false)
                }
            } catch (e: Throwable) {
                runCatching { OrbitTaskScheduler.wakeAfter(app, FAILURE_RETRY_MS) }
                if (e is CancellationException) throw e
                AppLogger.w(TAG, "Reschedule after lanes failed: ${e.message}")
            } finally {
                rescheduling.set(false)
            }
        }
        // A lane whose request another one drains leaves the places to it.
        if (!drained) return
        // One sync at a time: a second would mostly wait for another location fix. A place task that
        // ends while one runs is cleaned up by the place upkeep, every 15 minutes while places are watched.
        if (syncingPlaces.compareAndSet(false, true)) {
            try {
                OrbitGeofence.sync(app)
            } finally {
                syncingPlaces.set(false)
            }
        }
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
