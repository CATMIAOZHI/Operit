package com.ai.assistance.operit.features.orbit

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.util.concurrent.TimeUnit

object OrbitTaskScheduler {
    private const val WORK = "orbit_durable_tasks"
    private const val LONG_WORK = "orbit_durable_long_tasks"
    private const val WAKE = "orbit_durable_task_wakeup"
    private const val BUSY_LANE_SAFETY_WAKE_MS = 30 * 60_000L

    suspend fun reschedule(context: Context, fromWorker: Boolean = false, restore: Boolean = false) {
        val settings = OrbitPreferences.getInstance(context).current()
        val active = settings.active?.takeUnless { it.deleting }
        OrbitTaskRunner.cancelUnlessActive(active?.id)
        val manager = WorkManager.getInstance(context)
        if (active == null) {
            manager.cancelUniqueWork(WAKE)
            if (!fromWorker) {
                manager.cancelUniqueWork(WORK)
                manager.cancelUniqueWork(LONG_WORK)
            }
            return
        }
        val repo = OrbitTaskRepository(context)
        val tasks = repo.dao.tasks(active.id).filter { it.state == "ACTIVE" }
        val now = System.currentTimeMillis()
        var due: Long? = null
        for (task in tasks) {
            if (task.goalId.isNotEmpty() && repo.dao.goal(task.goalId)?.state != "ACTIVE") continue
            val event = repo.dao.pendingEvent(task.id, task.version)
            if (event == null && task.scheduleMode == "EVENT") continue
            val run = event?.let { repo.dao.eventRun(it.id) }
            val retryAt = when {
                event == null -> task.nextRunAt
                // An event put back (busy chat, quiet time) carries its new time.
                run == null -> maxOf(now, event.dueAt)
                run.bootId != OrbitTaskRunner.bootId(context) -> now
                else -> minOf(run.leaseUntilWall, now + 120_000)
            }
            // A run in progress here, or a due task waiting for its busy lane, is rescheduled by
            // that lane's worker when it ends; waking every few seconds for hours would only burn
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
            return
        }
        val request = OneTimeWorkRequestBuilder<OrbitTaskWakeWorker>()
            .setInitialDelay((due - now).coerceAtLeast(if (fromWorker) 15_000 else 0), TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniqueWork(WAKE,
            if (restore) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
    }

    fun wake(context: Context) {
        // Scheduling changes replace the small wake-up, never a running model turn.
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OrbitTaskWorker>().build())
        manager.enqueueUniqueWork(LONG_WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OrbitLongTaskWorker>().build())
    }

    suspend fun migrateAndSchedule(context: Context, restore: Boolean = false) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("orbit_daily_check_in")
        manager.cancelUniqueWork("orbit_manual_check_in")
        OrbitTaskRepository(context).syncDaily()
        reschedule(context, restore = restore)
    }

    suspend fun runNow(context: Context) {
        val repository = OrbitTaskRepository(context)
        val daily = repository.syncDaily() ?: return
        // Manual check-in is one event; it does not turn automatic check-ins on.
        val now = System.currentTimeMillis()
        // A manual check-in still waiting (a busy chat put it off) is the one the user asked for.
        val waiting = repository.dao.tasks(daily.orbitId).any {
            (it.id == daily.id || it.id.startsWith("manual-${daily.orbitId}-")) && it.state == "ACTIVE" &&
                repository.dao.pendingManualCount(it.id, it.version) > 0
        }
        if (waiting) { reschedule(context); return }
        val task = if (daily.state == "ACTIVE") daily else {
            val oneShot = daily.copy(id = "manual-${daily.orbitId}-${java.util.UUID.randomUUID()}",
                state = "ACTIVE", nextRunAt = now, intervalMinutes = 0, kind = "ONCE")
            repository.dao.insertTask(oneShot)
            oneShot
        }
        repository.dao.enqueueManual(task.id, java.util.UUID.randomUUID().toString(), now)
        reschedule(context)
    }
}

class OrbitTaskWakeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        OrbitTaskScheduler.wake(applicationContext)
        return Result.success()
    }
}

/** Runs tasks that fit a normal background worker (up to about ten minutes). */
class OrbitTaskWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runOrbitLane(applicationContext, longLane = false) { false }
}

/**
 * Runs tasks whose time limit is longer than a background worker allows. The worker moves to the
 * foreground with a notification first, which lifts the ten minute limit.
 */
class OrbitLongTaskWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runOrbitLane(applicationContext, longLane = true) { task ->
        if (!OrbitLongRunNotification.mayStartFromBackground(applicationContext)) return@runOrbitLane false
        setForeground(OrbitLongRunNotification.foregroundInfo(applicationContext, task.title))
        OrbitLongRunNotification.isShowing(applicationContext)
    }
}

private suspend fun runOrbitLane(
    applicationContext: Context,
    longLane: Boolean,
    promote: suspend (com.ai.assistance.operit.data.model.OrbitTaskEntity) -> Boolean,
): ListenableWorker.Result {
    when (OperitApplication.initializeMainApplication(applicationContext)) {
        OperitApplication.MainApplicationInitResult.Initialized -> Unit
        OperitApplication.MainApplicationInitResult.MigrationInProgress -> return ListenableWorker.Result.retry()
        else -> return ListenableWorker.Result.failure()
    }
    try {
        OrbitTaskRunner.runNext(applicationContext, longLane, promote)
        // A replacement worker owns the next wake-up after cancellation.
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        OrbitTaskScheduler.reschedule(applicationContext, fromWorker = true)
        return ListenableWorker.Result.success()
    } catch (e: CancellationException) {
        if (kotlinx.coroutines.currentCoroutineContext().isActive) {
            // Cancelling this task leaves the other tasks eligible for their next wake-up.
            OrbitTaskScheduler.reschedule(applicationContext, fromWorker = true)
            return ListenableWorker.Result.success()
        }
        throw e
    } catch (e: Exception) {
        com.ai.assistance.operit.util.AppLogger.e("OrbitTasks", "Task worker failed", e)
        return ListenableWorker.Result.retry()
    }
}
