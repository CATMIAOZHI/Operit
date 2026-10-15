package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import java.net.URI
import java.util.UUID

class OrbitTaskRepository(private val context: Context) {
    val dao = AppDatabase.getDatabase(context).orbitTaskDao()

    suspend fun create(
        orbit: OrbitProfile, title: String, instruction: String, source: String,
        intervalMinutes: Int, notifyPolicy: String, requestId: String, kind: String = "URL_CHANGE",
    ): OrbitTaskEntity {
        require(title.isNotBlank() && title.length <= 80)
        require(instruction.isNotBlank() && instruction.length <= 4000)
        require(intervalMinutes in 15..10080)
        require(notifyPolicy in setOf("CHANGES", "ALWAYS"))
        require(kind in setOf("URL_CHANGE", "ASSIST"))
        if (kind == "URL_CHANGE" || source.isNotBlank()) validateSource(source)
        check(OrbitPreferences.getInstance(context).current().orbits.any { it.id == orbit.id && !it.deleting })
        val id = UUID.nameUUIDFromBytes("${orbit.id}:$requestId".toByteArray()).toString()
        dao.task(id)?.let { return it }
        check(dao.tasks(orbit.id).count { it.state !in setOf("CANCELLED", "DONE") } < 20) { "At most 20 unfinished tasks" }
        val now = System.currentTimeMillis()
        val task = OrbitTaskEntity(id, orbit.id, orbit.chatId, title.trim(), instruction.trim(),
            kind, source, intervalMinutes, 0, notifyPolicy, "ACTIVE", 1, now,
            "", "", now, now)
        dao.insertTask(task)
        OrbitTaskScheduler.reschedule(context)
        return checkNotNull(dao.task(id))
    }

    suspend fun changeState(orbitId: String, taskId: String, state: String): Boolean {
        require(state in setOf("ACTIVE", "PAUSED", "CANCELLED"))
        val task = dao.task(taskId) ?: error("Task not found")
        check(task.orbitId == orbitId)
        check(task.state != "CANCELLED" || state == "CANCELLED") { "A cancelled task cannot be resumed" }
        check(state != "ACTIVE" || task.state != "WAITING_USER") {
            "Open task details and complete the user request before continuing"
        }
        check(state != "PAUSED" || task.state != "WAITING_USER") {
            "This task is already waiting for the user; leave it waiting or cancel it"
        }
        if (task.state == state) return true
        val now = System.currentTimeMillis()
        val targetState = if (task.kind == "DAILY" && state == "CANCELLED") "PAUSED" else state
        val changed = OrbitExecutionGate.serialized {
            if (state == "ACTIVE") check(dao.unresolvedActions(taskId).isEmpty()) {
                "Verify the unfinished operation in task details before resuming"
            }
            dao.replaceDefinition(task.copy(state = targetState, nextRunAt = nextAt(task, now),
                updatedAt = now), task.version)
        }
        if (changed) {
            OrbitBrowserControl.release(task.id)
            if (task.kind == "DAILY") OrbitPreferences.getInstance(context).setProactiveEnabled(targetState == "ACTIVE", orbitId)
            OrbitTaskRunner.cancelTask(task.id)
            OrbitTaskScheduler.reschedule(context)
        }
        return changed
    }

    suspend fun edit(task: OrbitTaskEntity, title: String, instruction: String, source: String,
        intervalMinutes: Int, notifyPolicy: String): Boolean {
        check(task.kind in setOf("URL_CHANGE", "ASSIST") && task.state != "CANCELLED")
        check(task.state !in setOf("WAITING_USER", "VERIFY")) {
            "Complete or cancel the pending user request before editing this task"
        }
        require(title.isNotBlank() && title.length <= 80 && instruction.length in 1..4000)
        require(intervalMinutes in 15..10080 && notifyPolicy in setOf("CHANGES", "ALWAYS"))
        if (task.kind == "URL_CHANGE" || source.isNotBlank()) validateSource(source)
        val now = System.currentTimeMillis()
        val changed = OrbitExecutionGate.serialized {
            dao.replaceDefinition(task.copy(title = title, instruction = instruction, source = source,
                intervalMinutes = intervalMinutes, notifyPolicy = notifyPolicy, nextRunAt = now,
                lastFingerprint = if (source == task.source) task.lastFingerprint else "",
                lastObservation = if (source == task.source) task.lastObservation else "",
                updatedAt = now), task.version)
        }
        if (changed) {
            OrbitTaskRunner.cancelTask(task.id)
            OrbitTaskScheduler.reschedule(context)
        }
        return changed
    }

    suspend fun resolveAction(orbitId: String, taskId: String, actionId: String, completed: Boolean) {
        check(dao.task(taskId)?.orbitId == orbitId)
        OrbitExecutionGate.serialized {
            check(dao.resolveAction(taskId, actionId, completed, System.currentTimeMillis()))
        }
    }

    /** Idempotent migration of the old daily settings; changing focus/time invalidates old work. */
    suspend fun syncDaily(): OrbitTaskEntity? = OrbitExecutionGate.serialized {
        val settings = OrbitPreferences.getInstance(context).current()
        val orbit = settings.active?.takeUnless { it.deleting } ?: return@serialized null
        val id = "daily-${orbit.id}"
        val now = System.currentTimeMillis()
        val old = dao.task(id)
        val state = if (settings.proactiveEnabled) "ACTIVE" else "PAUSED"
        val title = context.getString(com.ai.assistance.operit.R.string.orbit_daily_task)
        val proposed = OrbitTaskEntity(id, orbit.id, orbit.chatId, title, settings.focusNotes,
            "DAILY", "", 1440, settings.checkInMinuteOfDay, "CHANGES", state,
            old?.version ?: 1, now + OrbitCompanion.delayUntilNextCheckIn(now, settings.checkInMinuteOfDay),
            old?.lastObservation.orEmpty(), "", old?.createdAt ?: now, now)
        if (old == null) dao.insertTask(proposed)
        else if (old.instruction != proposed.instruction || old.dailyMinute != proposed.dailyMinute ||
            old.state != proposed.state || old.chatId != proposed.chatId) {
            dao.replaceDefinition(proposed, old.version)
        }
        dao.task(id)
    }

    companion object {
        fun validateSource(source: String) {
            require(source.length <= 2048)
            val uri = URI(source)
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null) { "Use an http(s) URL without credentials or a fragment" }
        }

        fun nextAt(task: OrbitTaskEntity, now: Long): Long =
            if (task.kind == "DAILY") now + OrbitCompanion.delayUntilNextCheckIn(now, task.dailyMinute)
            else now + task.intervalMinutes.coerceAtLeast(15) * 60_000L
    }
}
