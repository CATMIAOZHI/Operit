package com.ai.assistance.operit.features.orbit

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
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
    private const val WAKE = "orbit_durable_task_wakeup"

    suspend fun reschedule(context: Context, fromWorker: Boolean = false, restore: Boolean = false) {
        val settings = OrbitPreferences.getInstance(context).current()
        val active = settings.active?.takeUnless { it.deleting }
        OrbitTaskRunner.cancelUnlessActive(active?.id)
        val manager = WorkManager.getInstance(context)
        if (active == null) {
            manager.cancelUniqueWork(WAKE)
            if (!fromWorker) manager.cancelUniqueWork(WORK)
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
                run == null -> now
                run.bootId != OrbitTaskRunner.bootId(context) -> now
                else -> minOf(run.leaseUntilWall, now + 120_000)
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
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OrbitTaskWorker>().build())
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

class OrbitTaskWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        when (OperitApplication.initializeMainApplication(applicationContext)) {
            OperitApplication.MainApplicationInitResult.Initialized -> Unit
            OperitApplication.MainApplicationInitResult.MigrationInProgress -> return Result.retry()
            else -> return Result.failure()
        }
        try {
            OrbitTaskRunner.runNext(applicationContext)
            // A replacement worker owns the next wake-up after cancellation.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            OrbitTaskScheduler.reschedule(applicationContext, fromWorker = true)
            return Result.success()
        } catch (e: CancellationException) {
            if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                // Cancelling this task leaves the other tasks eligible for their next wake-up.
                OrbitTaskScheduler.reschedule(applicationContext, fromWorker = true)
                return Result.success()
            }
            throw e
        } catch (e: Exception) {
            com.ai.assistance.operit.util.AppLogger.e("OrbitTasks", "Task worker failed", e)
            return Result.retry()
        }
    }
}
