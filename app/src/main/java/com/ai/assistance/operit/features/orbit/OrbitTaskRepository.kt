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

    suspend fun executionRecord(taskId: String, excludeRun: String = ""): String {
        val run = dao.previousRun(taskId, excludeRun) ?: return ""
        val bindings = dao.bindings(run.id, run.generation).sortedWith(
            compareBy<com.ai.assistance.operit.data.model.OrbitTaskChatEntity> { it.chatId != it.rootChatId }.thenBy { it.chatId })
        if (bindings.isEmpty()) return ""
        // Every child gets space: a child's external operation may never have reached its parent.
        val perChat = (20_000 / bindings.size - 150).coerceAtLeast(200)
        return bindings.map { binding ->
            val body = dao.executionSource(binding.chatId).reversed().joinToString("\n\n") {
                "${it.sender}: ${com.ai.assistance.operit.util.ChatUtils.removeThinkingContent(it.content)}"
            }
            "[${if (binding.chatId == binding.rootChatId) "root" else "child"} ${binding.chatId}]\n" +
                (if (body.length > perChat) "[Earlier content omitted]\n" else "") + body.takeLast(perChat)
        }.joinToString("\n\n")
    }

    suspend fun create(
        orbit: OrbitProfile, title: String, instruction: String, source: String,
        intervalMinutes: Int, notifyPolicy: String, requestId: String, kind: String = "URL_CHANGE",
        scheduleMode: String = if (kind == "ASSIST") "ONCE" else "INTERVAL",
        goalId: String = "", triggerTaskId: String = "", sourceArtifactVersionId: String = "",
        runAt: Long? = null, dailyMinute: Int = 0,
    ): OrbitTaskEntity {
        require(title.isNotBlank() && title.length <= 80)
        require(instruction.isNotBlank() && instruction.length <= 4000)
        require(intervalMinutes in 15..10080)
        require(notifyPolicy in setOf("CHANGES", "ALWAYS", "DIGEST"))
        require(kind in setOf("URL_CHANGE", "ASSIST"))
        require(scheduleMode in setOf("ONCE", "INTERVAL", "EVENT", "DAILY"))
        require(dailyMinute in 0 until 24 * 60)
        require(runAt == null || scheduleMode == "ONCE") { "run_at only works with schedule_mode ONCE" }
        if (kind == "URL_CHANGE" || source.isNotBlank()) validateSource(source)
        check(OrbitPreferences.getInstance(context).current().orbits.any { it.id == orbit.id && !it.deleting })
        val id = UUID.nameUUIDFromBytes("${orbit.id}:$requestId".toByteArray()).toString()
        dao.task(id)?.let { return it }
        val now = System.currentTimeMillis()
        val firstRun = when (scheduleMode) {
            "ONCE" -> maxOf(runAt ?: now, now)
            "DAILY" -> now + OrbitCompanion.delayUntilNextCheckIn(now, dailyMinute)
            else -> now
        }
        val task = OrbitTaskEntity(id, orbit.id, orbit.chatId, title.trim(), instruction.trim(),
            kind, source, intervalMinutes, dailyMinute, notifyPolicy, "ACTIVE", 1, firstRun,
            "", "", now, now, scheduleMode, goalId, triggerTaskId, sourceArtifactVersionId)
        OrbitExecutionGate.serialized {
            check(OrbitPreferences.getInstance(context).current().orbits.any { it.id == orbit.id && !it.deleting })
            if (dao.task(id) != null) return@serialized
            check(dao.tasks(orbit.id).count { it.state !in setOf("CANCELLED", "DONE") } < 20) { "At most 20 unfinished tasks" }
            if (goalId.isNotBlank()) check(dao.goal(goalId)?.let { it.orbitId == orbit.id && it.state == "ACTIVE" } == true)
            if (scheduleMode == "EVENT") {
                check(triggerTaskId != id && dao.task(triggerTaskId)?.orbitId == orbit.id) { "Choose another task in this Orbit" }
                // New task IDs cannot already be part of a cycle; trigger changes are not exposed.
            } else require(triggerTaskId.isBlank())
            if (sourceArtifactVersionId.isNotBlank()) check(dao.artifact(sourceArtifactVersionId)?.orbitId == orbit.id)
            dao.insertSubscribedTask(task, now)
        }
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
            if (state == "ACTIVE" && task.goalId.isNotEmpty()) check(dao.goal(task.goalId)?.state == "ACTIVE") {
                "Resume the goal before continuing this task"
            }
            if (state == "ACTIVE") check(dao.unresolvedActions(taskId).isEmpty()) {
                "Verify the unfinished operation in task details before resuming"
            }
            val resumeAt = if (task.scheduleMode in setOf("ONCE", "DAILY") && task.kind != "DAILY") nextAt(task, now) else now
            dao.replaceDefinition(task.copy(state = targetState, nextRunAt = if (state == "ACTIVE") resumeAt else nextAt(task, now),
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
        intervalMinutes: Int, notifyPolicy: String, runAt: Long? = null, dailyMinute: Int = task.dailyMinute): Boolean {
        check(task.kind in setOf("URL_CHANGE", "ASSIST") && task.state != "CANCELLED")
        check(task.state !in setOf("WAITING_USER", "VERIFY")) {
            "Complete or cancel the pending user request before editing this task"
        }
        require(title.isNotBlank() && title.length <= 80 && instruction.length in 1..4000)
        require(intervalMinutes in 15..10080 && notifyPolicy in setOf("CHANGES", "ALWAYS", "DIGEST"))
        require(dailyMinute in 0 until 24 * 60)
        require(runAt == null || task.scheduleMode == "ONCE") { "run_at only works with schedule_mode ONCE" }
        if (task.kind == "URL_CHANGE" || source.isNotBlank()) validateSource(source)
        val now = System.currentTimeMillis()
        val nextRun = when (task.scheduleMode) {
            "ONCE" -> maxOf(runAt ?: task.nextRunAt, now)
            "DAILY" -> now + OrbitCompanion.delayUntilNextCheckIn(now, dailyMinute)
            else -> now
        }
        val changed = OrbitExecutionGate.serialized {
            dao.replaceDefinition(task.copy(title = title, instruction = instruction, source = source,
                intervalMinutes = intervalMinutes, notifyPolicy = notifyPolicy, nextRunAt = nextRun,
                dailyMinute = dailyMinute,
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
            val receipt = checkNotNull(dao.action(actionId))
            check(receipt.taskId == taskId)
            if (completed && receipt.toolName == OrbitTaskActionTools.WRITE) {
                OrbitArtifactRepository(context).verifySavedFile(orbitId, receipt)
            } else check(dao.resolveAction(taskId, actionId, completed, System.currentTimeMillis()))
        }
    }

    /** Idempotent migration of the old daily settings; changing focus/time invalidates old work. */
    suspend fun syncDaily(): OrbitTaskEntity? = OrbitExecutionGate.serialized {
        val settings = OrbitPreferences.getInstance(context).current()
        val orbit = settings.active?.takeUnless { it.deleting } ?: return@serialized null
        val id = "daily-${orbit.id}"
        val now = System.currentTimeMillis()
        val old = dao.task(id)
        val state = when {
            !settings.proactiveEnabled -> "PAUSED"
            old?.state in setOf("WAITING_USER", "VERIFY") -> old!!.state
            old?.state == "PAUSED" && dao.latestRun(id)?.state == "INTERRUPTED" -> "PAUSED"
            else -> "ACTIVE"
        }
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
            if (task.kind == "DAILY" || task.scheduleMode == "DAILY") now + OrbitCompanion.delayUntilNextCheckIn(now, task.dailyMinute)
            else if (task.scheduleMode == "ONCE") maxOf(task.nextRunAt, now)
            else now + task.intervalMinutes.coerceAtLeast(15) * 60_000L
    }
}
