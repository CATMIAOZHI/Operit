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
        return runRecord(run)
    }

    /** One run's transcript: the hidden chat it ran in and any subagents it started; empty when it used none. */
    suspend fun runRecord(taskId: String, runId: String): String =
        dao.runOfTask(taskId, runId)?.let { runRecord(it) }.orEmpty()

    private suspend fun runRecord(run: com.ai.assistance.operit.data.model.OrbitRunEntity): String {
        val bindings = dao.bindings(run.id, run.generation).sortedWith(
            compareBy<com.ai.assistance.operit.data.model.OrbitTaskChatEntity> { it.chatId != it.rootChatId }.thenBy { it.chatId })
        if (bindings.isEmpty()) return ""
        // Every child gets space: a child's external operation may never have reached its parent.
        val perChat = (20_000 / bindings.size - 150).coerceAtLeast(200)
        return bindings.map { binding ->
            val body = dao.executionSource(binding.chatId).reversed().joinToString("\n\n") {
                // A record from before the alignment steps were reworded may still carry old wording.
                "${it.sender}: ${OrbitRoutines.withoutRetiredWording(com.ai.assistance.operit.util.ChatUtils.removeThinkingContent(it.content))}"
            }
            "[${if (binding.chatId == binding.rootChatId) "root" else "child"} ${binding.chatId}]\n" +
                (if (body.length > perChat) "[Earlier content omitted]\n" else "") + body.takeLast(perChat)
        }.joinToString("\n\n")
    }

    suspend fun create(
        orbit: OrbitProfile, title: String, instruction: String, source: String,
        intervalMinutes: Int, notifyPolicy: String, requestId: String, kind: String = "URL_CHANGE",
        scheduleMode: String = if (kind in setOf("ASSIST", OrbitGeofence.KIND)) "ONCE" else "INTERVAL",
        goalId: String = "", triggerTaskId: String = "", sourceArtifactVersionId: String = "",
        runAt: Long? = null, dailyMinute: Int = 0, calendarRule: String = "",
        timeoutMinutes: Int = 0, chatId: String? = null, useSubagent: Boolean = false,
    ): OrbitTaskEntity {
        checkTaskFields(title, instruction, intervalMinutes, notifyPolicy, dailyMinute)
        require(kind in setOf("URL_CHANGE", "ASSIST", OrbitWatchSources.KIND, OrbitGeofence.KIND)) { "kind must be URL_CHANGE, ASSIST, WATCH or GEOFENCE" }
        checkLocationSchedule(kind, scheduleMode, runAt)
        val modes = setOf("ONCE", "INTERVAL", "EVENT", "DAILY") + OrbitCalendarSchedule.modes
        require(scheduleMode in modes) { "schedule_mode must be one of ${modes.joinToString()}" }
        checkTimeout(timeoutMinutes)
        require(runAt == null || scheduleMode == "ONCE") { "run_at only works with schedule_mode ONCE" }
        val rule = checkedRule(scheduleMode, calendarRule)
        checkSource(kind, source)
        val latest = OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == orbit.id && !it.deleting }
        checkNotNull(latest)
        val reportChatId = chatId ?: latest.chatId
        require(reportChatId in latest.talkChatIds) { "Reports can only go to this Orbit's chats" }
        val id = UUID.nameUUIDFromBytes("${orbit.id}:$requestId".toByteArray()).toString()
        dao.task(id)?.let { return it }
        val now = System.currentTimeMillis()
        val firstRun = if (kind == OrbitGeofence.KIND) 0L else when (scheduleMode) {
            "ONCE" -> maxOf(runAt ?: now, now)
            "DAILY" -> now + OrbitCompanion.delayUntilNextCheckIn(now, dailyMinute)
            in OrbitCalendarSchedule.modes -> OrbitCalendarSchedule.next(scheduleMode, rule, dailyMinute, now)
            else -> now
        }
        val task = OrbitTaskEntity(id, orbit.id, reportChatId, title.trim(), instruction.trim(),
            kind, source, intervalMinutes, dailyMinute, notifyPolicy,
            if (kind == OrbitWatchSources.KIND && OrbitWatchSources.isScript(source)) "PAUSED" else "ACTIVE", 1, firstRun,
            "", "", now, now, scheduleMode, goalId, triggerTaskId, sourceArtifactVersionId, rule, timeoutMinutes,
            useSubagent = useSubagent)
        OrbitExecutionGate.serialized {
            check(OrbitPreferences.getInstance(context).current().orbits.any { it.id == orbit.id && !it.deleting }) { "This Orbit no longer exists" }
            if (dao.task(id) != null) return@serialized
            check(dao.tasks(orbit.id).count { it.state !in setOf("CANCELLED", "DONE") } < 20) { "At most 20 unfinished tasks" }
            if (goalId.isNotBlank()) check(dao.goal(goalId)?.let { it.orbitId == orbit.id && it.state == "ACTIVE" } == true) {
                "goal_id $goalId is not an active goal of this Orbit; check orbit_goal list"
            }
            if (scheduleMode == "EVENT") {
                check(triggerTaskId != id && dao.task(triggerTaskId)?.orbitId == orbit.id) { "Choose another task in this Orbit" }
                // New task IDs cannot already be part of a cycle; trigger changes are not exposed.
            } else require(triggerTaskId.isBlank()) { "trigger_task_id only works with schedule_mode EVENT" }
            if (sourceArtifactVersionId.isNotBlank()) check(dao.artifact(sourceArtifactVersionId)?.orbitId == orbit.id) {
                "No artifact version $sourceArtifactVersionId in this Orbit; check orbit_artifact list"
            }
            dao.insertSubscribedTask(task, now)
        }
        OrbitTaskScheduler.reschedule(context)
        return checkNotNull(dao.task(id))
    }

    suspend fun changeState(orbitId: String, taskId: String, state: String): Boolean {
        require(state in setOf("ACTIVE", "PAUSED", "CANCELLED")) { "state must be ACTIVE, PAUSED or CANCELLED" }
        val task = dao.task(taskId)?.takeIf { it.orbitId == orbitId } ?: error("No task $taskId in this Orbit; check orbit_task list")
        // The Feed and Ideas tabs live on their routines: they pause, they are never cancelled.
        check(state != "CANCELLED" || !OrbitRoutines.isProtected(task)) {
            "This routine can only be paused, not cancelled"
        }
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
            if (state == "ACTIVE") check(OrbitHookChecks.passed(context, task)) {
                context.getString(com.ai.assistance.operit.R.string.orbit_hook_needs_dry_run)
            }
            if (state == "ACTIVE" && task.goalId.isNotEmpty()) check(dao.goal(task.goalId)?.state == "ACTIVE") {
                "Resume the goal before continuing this task"
            }
            if (state == "ACTIVE") check(dao.unresolvedActions(taskId).isEmpty()) {
                "Verify the unfinished operation in task details before resuming"
            }
            val resumeAt = if (task.kind == OrbitGeofence.KIND ||
                (task.scheduleMode in timedModes && task.kind != "DAILY")) nextAt(task, now) else now
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
        intervalMinutes: Int, notifyPolicy: String, runAt: Long? = null, dailyMinute: Int = task.dailyMinute,
        calendarRule: String = task.calendarRule, timeoutMinutes: Int = task.timeoutMinutes,
        useSubagent: Boolean = task.useSubagent, scheduleMode: String = task.scheduleMode): Boolean {
        check(task.state != "CANCELLED") { "A cancelled task cannot be edited; create a new one" }
        check(task.kind in setOf("URL_CHANGE", "ASSIST", OrbitWatchSources.KIND, OrbitGeofence.KIND)) { "This kind of task (${task.kind}) cannot be edited" }
        checkLocationSchedule(task.kind, scheduleMode, runAt)
        check(task.state !in setOf("WAITING_USER", "VERIFY")) {
            "Complete or cancel the pending user request before editing this task"
        }
        if (scheduleMode != task.scheduleMode && task.kind != OrbitGeofence.KIND) {
            // Only a repeating task changes how it repeats; one-off and chained tasks keep theirs.
            val repeating = setOf("INTERVAL", "DAILY") + OrbitCalendarSchedule.modes
            require(task.scheduleMode in repeating && scheduleMode in repeating) {
                "schedule_mode can only change between INTERVAL, DAILY, WEEKLY, MONTHLY and YEARLY"
            }
        }
        val protected = OrbitRoutines.isProtected(task)
        if (protected) {
            // Only when it runs can change; what it does stays as Orbit made it.
            require(title == task.title && instruction == task.instruction && source == task.source &&
                useSubagent == task.useSubagent && notifyPolicy == task.notifyPolicy &&
                timeoutMinutes == task.timeoutMinutes) {
                "Only the schedule of this routine can change (schedule_mode, interval_minutes, daily_time, repeat_on)"
            }
        }
        checkTaskFields(title, instruction, intervalMinutes, notifyPolicy, dailyMinute)
        checkTimeout(timeoutMinutes)
        require(runAt == null || scheduleMode == "ONCE") { "run_at only works with schedule_mode ONCE" }
        // Only the calendar modes keep a day rule.
        val rule = checkedRule(scheduleMode, if (scheduleMode in OrbitCalendarSchedule.modes) calendarRule else "")
        checkSource(task.kind, source)
        val now = System.currentTimeMillis()
        // Only a change to when it runs (or to the page it watches) sets a new next run; a new title or
        // notify policy keeps the time. Any saved edit still fences out a run in progress (replaceDefinition).
        val timingChanged = scheduleMode != task.scheduleMode || intervalMinutes != task.intervalMinutes ||
            dailyMinute != task.dailyMinute || rule != task.calendarRule || (runAt != null && runAt != task.nextRunAt)
        // Nothing to save: leave the task, its run and its pending results as they are.
        if (!timingChanged && title == task.title && instruction == task.instruction && source == task.source &&
            notifyPolicy == task.notifyPolicy && timeoutMinutes == task.timeoutMinutes && useSubagent == task.useSubagent) return true
        val nextRun = if (task.kind == OrbitGeofence.KIND) 0L else if (!timingChanged && source == task.source) task.nextRunAt else when (scheduleMode) {
            "ONCE" -> maxOf(runAt ?: task.nextRunAt, now)
            "DAILY" -> now + OrbitCompanion.delayUntilNextCheckIn(now, dailyMinute)
            in OrbitCalendarSchedule.modes -> OrbitCalendarSchedule.next(scheduleMode, rule, dailyMinute, now)
            // A routine that runs more often starts on its new rhythm instead of running at once.
            "INTERVAL" -> if (protected) minOf(maxOf(task.nextRunAt, now), now + intervalMinutes * 60_000L) else now
            else -> now
        }
        val changed = OrbitExecutionGate.serialized {
            dao.replaceDefinition(task.copy(title = title, instruction = instruction, source = source,
                state = if (task.kind == OrbitWatchSources.KIND && OrbitWatchSources.isScript(source) &&
                    source != task.source) "PAUSED" else task.state,
                intervalMinutes = intervalMinutes, notifyPolicy = notifyPolicy, nextRunAt = nextRun,
                dailyMinute = dailyMinute, calendarRule = rule, timeoutMinutes = timeoutMinutes,
                useSubagent = useSubagent, scheduleMode = scheduleMode,
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

    /** Try the pinned script without creating an event, a run, or a chat wake. */
    suspend fun dryRun(task: OrbitTaskEntity): OrbitWatchSources.ScriptResult {
        require(OrbitHookChecks.needed(task)) { "dry_run is only for WATCH scripts; other watch sources do not need it" }
        check(task.state == "PAUSED") { "Pause this hook before dry_run" }
        OrbitExecutionGate.serialized {
            check(dao.task(task.id)?.let { it.version == task.version && it.state == "PAUSED" } == true) {
                "The task changed; read detail and retry"
            }
            OrbitHookChecks.forget(context, task.id)
        }
        val result = OrbitWatchSources.readScript(context, task.orbitId, task.source, dryRun = true)
        OrbitExecutionGate.serialized {
            check(dao.task(task.id)?.let { it.version == task.version && it.state == "PAUSED" } == true) {
                "The task changed during dry_run; read detail and retry"
            }
            OrbitHookChecks.remember(context, task)
        }
        return result
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

    /**
     * The daily check-in is no longer a built-in task. An Orbit that still has one from an older
     * version, or a manual check-in still queued, gets it cancelled; the user's own tasks are untouched.
     */
    suspend fun retireDaily() {
        for (orbit in OrbitPreferences.getInstance(context).current().orbits) {
            val retired = OrbitExecutionGate.serialized {
                val now = System.currentTimeMillis()
                dao.tasks(orbit.id).filter { isRetiredCheckIn(it) && it.state != "CANCELLED" }
                    .filter { dao.replaceDefinition(it.copy(state = "CANCELLED", updatedAt = now), it.version) }
            }
            retired.forEach {
                OrbitBrowserControl.release(it.id)
                OrbitTaskRunner.cancelTask(it.id)
            }
        }
    }

    companion object {
        /** The retired built-in daily check-in, or a manual run of it. */
        fun isRetiredCheckIn(task: OrbitTaskEntity): Boolean =
            (task.kind == "DAILY" && task.id == "daily-${task.orbitId}") ||
                (task.kind == "ONCE" && task.id.startsWith("manual-${task.orbitId}-"))

        /** A run without its own limit gets five minutes. */
        const val DEFAULT_TIMEOUT_MINUTES = 5

        /**
         * Runs longer than this get a lane of their own in [OrbitTaskHost], so a run of hours never
         * holds back a reminder.
         */
        const val LONG_RUN_MINUTES = 8

        /** The ceiling matches Muse's 24 hours. */
        const val MAX_TIMEOUT_MINUTES = 24 * 60

        /** 0 keeps the default; otherwise 1 to [MAX_TIMEOUT_MINUTES]. */
        fun checkTimeout(minutes: Int) = require(minutes in 0..MAX_TIMEOUT_MINUTES) {
            "timeout_minutes must be 1 to $MAX_TIMEOUT_MINUTES (24 hours)"
        }

        fun checkTaskFields(title: String, instruction: String, intervalMinutes: Int, notifyPolicy: String, dailyMinute: Int) {
            require(title.isNotBlank()) { "title is required" }
            require(title.length <= 80) { "title has ${title.length} characters; at most 80" }
            require(instruction.isNotBlank()) { "instruction is required: say what the task should do each run" }
            require(instruction.length <= 4000) { "instruction has ${instruction.length} characters; at most 4000" }
            require(intervalMinutes in 15..10080) { "interval_minutes must be 15 to 10080 (one week), got $intervalMinutes" }
            require(notifyPolicy in setOf("CHANGES", "ALWAYS", "DIGEST")) { "notify_policy must be CHANGES, ALWAYS or DIGEST" }
            require(dailyMinute in 0 until 24 * 60) { "daily_time must be a time of day between 00:00 and 23:59" }
        }

        /** The alignment review's limit lives in the app, like its steps, so older stored copies get it too. */
        fun timeoutMinutes(task: OrbitTaskEntity): Int =
            if (task.kind == "ASSIST" && task.source == OrbitRoutines.ALIGNMENT_SOURCE) OrbitRoutines.REVIEW_TIMEOUT_MINUTES
            else task.timeoutMinutes.takeIf { it in 1..MAX_TIMEOUT_MINUTES } ?: DEFAULT_TIMEOUT_MINUTES

        /** Only a subagent run can be long; a task handled by Orbit in the chat just wakes it. */
        fun isLongRun(task: OrbitTaskEntity): Boolean =
            task.useSubagent && timeoutMinutes(task) > LONG_RUN_MINUTES

        /** Modes that run at a chosen time of day or moment, so resuming waits for the next one. */
        private val timedModes = setOf("ONCE", "DAILY") + OrbitCalendarSchedule.modes

        /** The stored day rule: required and normalized for calendar modes, empty for the rest. */
        fun checkedRule(scheduleMode: String, rule: String): String =
            if (scheduleMode in OrbitCalendarSchedule.modes) {
                require(rule.isNotBlank()) { "$scheduleMode needs repeat_on" }
                OrbitCalendarSchedule.normalize(scheduleMode, rule)
            } else {
                require(rule.isBlank()) { "repeat_on only works with WEEKLY, MONTHLY or YEARLY" }
                ""
            }

        /** A WATCH task names what it watches; other tasks may name a web page. */
        fun checkSource(kind: String, source: String) {
            if (kind == OrbitGeofence.KIND) OrbitGeofenceDefinition.parse(source)
            else if (kind == OrbitWatchSources.KIND) OrbitWatchSources.validate(source)
            else if (kind == "ASSIST" && OrbitRoutines.isRoutineSource(source)) return
            else if (kind == "URL_CHANGE" || source.isNotBlank()) validateSource(source)
        }

        /** Tasks whose source is read without the model; an unchanged source can skip the model. */
        fun prechecked(task: OrbitTaskEntity) = task.kind == "URL_CHANGE" || task.kind == OrbitWatchSources.KIND ||
            (task.kind == "ASSIST" && task.source == OrbitRoutines.CHAT_SOURCE)

        fun validateSource(source: String) {
            require(source.length <= 2048)
            val uri = URI(source)
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null) { "Use an http(s) URL without credentials or a fragment" }
        }

        private fun checkLocationSchedule(kind: String, mode: String, runAt: Long?) {
            if (kind == OrbitGeofence.KIND) require(mode in setOf("ONCE", "INTERVAL") && runAt == null) {
                "GEOFENCE uses ONCE (one arrival/departure) or INTERVAL (every arrival/departure), without run_at"
            }
        }

        fun nextAt(task: OrbitTaskEntity, now: Long): Long =
            if (task.kind == OrbitGeofence.KIND) 0L
            else if (task.kind == "DAILY" || task.scheduleMode == "DAILY") now + OrbitCompanion.delayUntilNextCheckIn(now, task.dailyMinute)
            else if (task.scheduleMode in OrbitCalendarSchedule.modes)
                OrbitCalendarSchedule.next(task.scheduleMode, task.calendarRule, task.dailyMinute, now)
            else if (task.scheduleMode == "ONCE") maxOf(task.nextRunAt, now)
            else now + task.intervalMinutes.coerceAtLeast(15) * 60_000L
    }
}
