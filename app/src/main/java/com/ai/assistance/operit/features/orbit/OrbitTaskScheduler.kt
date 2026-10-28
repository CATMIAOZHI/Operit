package com.ai.assistance.operit.features.orbit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import java.util.concurrent.TimeUnit

/**
 * Decides when Orbit's tasks are next due and sets one WorkManager wake-up for then. The wake-up
 * only hands over to [OrbitTaskHost], which runs the tasks in Operit's keep-alive service.
 */
object OrbitTaskScheduler {
    /** Runner workers of older versions; cancelled so a queued one never runs a task. */
    private const val WORK = "orbit_durable_tasks"
    private const val LONG_WORK = "orbit_durable_long_tasks"
    private const val WAKE = "orbit_durable_task_wakeup"
    /** While lanes run; separate from [WAKE] so the two never replace each other. */
    private const val SAFETY = "orbit_durable_task_safety"
    private const val BUSY_LANE_SAFETY_WAKE_MS = 30 * 60_000L

    /** [fromWorker]: called when a lane ends, so a due task waits a little before the next wake-up. */
    suspend fun reschedule(context: Context, fromWorker: Boolean = false, restore: Boolean = false) {
        val settings = OrbitPreferences.getInstance(context).current()
        val active = settings.active?.takeUnless { it.deleting }
        OrbitTaskRunner.cancelUnlessActive(active?.id)
        val manager = WorkManager.getInstance(context)
        if (active == null) {
            OrbitGeofence.sync(context)
            manager.cancelUniqueWork(WAKE)
            return
        }
        val repo = OrbitTaskRepository(context)
        val zoneChanged = repo.refreshCalendarTimeZone(active.id)
        val tasks = repo.dao.tasks(active.id).filter { it.state == "ACTIVE" }
        val now = System.currentTimeMillis()
        var due: Long? = null
        for (task in tasks) {
            if (task.goalId.isNotEmpty() && repo.dao.goal(task.goalId)?.state != "ACTIVE") continue
            val event = repo.dao.pendingEvent(task.id, task.version)
            if (event == null && (task.scheduleMode == "EVENT" || task.kind == OrbitGeofence.KIND)) continue
            val run = event?.let { repo.dao.eventRun(it.id) }
            val retryAt = when {
                event == null -> task.nextRunAt
                // An event put back (busy chat, quiet time) carries its new time.
                run == null -> maxOf(now, event.dueAt)
                run.bootId != OrbitTaskRunner.bootId(context) -> now
                else -> minOf(run.leaseUntilWall, now + 120_000)
            }
            // A run in progress here, or a due task waiting for its busy lane, is rescheduled by
            // that lane when it ends; waking every few seconds for hours would only burn
            // battery. A slow safety wake-up still covers a process that dies mid-run.
            if ((run != null && OrbitTaskRunner.isRunningHere(run.id)) ||
                (run == null && retryAt <= now && OrbitTaskRunner.laneBusyHere(OrbitTaskRepository.isLongRun(task)))) {
                val safety = now + BUSY_LANE_SAFETY_WAKE_MS
                due = minOf(due ?: safety, safety)
                continue
            }
            due = minOf(due ?: retryAt, retryAt)
        }
        for (delivery in repo.dao.pendingDeliveries(active.id)) {
            val ready = OrbitDelivery.dueAt(context, delivery, now).coerceAtLeast(now + 60_000)
            due = minOf(due ?: ready, ready)
        }
        if (due == null) {
            manager.cancelUniqueWork(WAKE)
            OrbitGeofence.sync(context)
            return
        }
        val request = OneTimeWorkRequestBuilder<OrbitTaskWakeWorker>()
            .setInitialDelay((due - now).coerceAtLeast(if (fromWorker) 15_000 else 0), TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniqueWork(WAKE,
            if (restore && !zoneChanged) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
        OrbitGeofence.sync(context)
    }

    fun wake(context: Context) {
        // Scheduling changes replace the small wake-up, never a running model turn.
        OrbitTaskHost.wake(context)
    }

    /** A wake-up after [delayMs], for a lane that could not run now (service refused, a failure). */
    fun wakeAfter(context: Context, delayMs: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(WAKE, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<OrbitTaskWakeWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS).build())
    }

    /**
     * A wake-up in 30 minutes, set while tasks run: the process may die before a lane ends and
     * schedules the next one. Each wake-up that finds the lanes still running sets it again.
     */
    fun armSafetyWake(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(SAFETY, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<OrbitTaskWakeWorker>()
                .setInitialDelay(BUSY_LANE_SAFETY_WAKE_MS, TimeUnit.MILLISECONDS).build())
    }

    fun cancelSafetyWake(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SAFETY)
    }

    suspend fun migrateAndSchedule(context: Context, restore: Boolean = false) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("orbit_daily_check_in")
        manager.cancelUniqueWork("orbit_manual_check_in")
        manager.cancelUniqueWork(WORK)
        manager.cancelUniqueWork(LONG_WORK)
        OrbitTaskRepository(context).retireDaily()
        reschedule(context, restore = restore)
    }
}

/** A system time-zone change also reschedules tasks when the app has no open screen. */
class OrbitTimeZoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        WorkManager.getInstance(context).enqueueUniqueWork("orbit_calendar_timezone", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<OrbitTimeZoneWorker>().build())
    }
}

class OrbitTimeZoneWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        OrbitTaskScheduler.reschedule(applicationContext)
        return Result.success()
    }
}

class OrbitTaskWakeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        OrbitTaskScheduler.wake(applicationContext)
        return Result.success()
    }
}
