package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Each Orbit's unfinished tasks as files in tasks/, one Markdown file a task, so the user and Orbit
 * can look at them like any other file. They are copies: the app rewrites them whenever a task
 * changes, and a task is still changed only with orbit_task (writes to tasks/ are refused).
 */
object OrbitTaskFiles {
    private const val TAG = "OrbitTaskFiles"

    private val changes = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Per Orbit id, a number that goes up each time its tasks/ changed, so an open file list reloads. */
    val versions: StateFlow<Map<String, Int>> = changes

    /**
     * Keeps every Orbit's tasks/ in step with its tasks while the app runs; never returns. Only a change
     * to what the copies show starts a sync, which still checks every copy on disk.
     */
    suspend fun follow(context: Context) {
        val app = context.applicationContext
        AppDatabase.getDatabase(app).orbitTaskDao().observeOpenTasks()
            .map { tasks -> tasks.map(::shown) }.distinctUntilChanged().conflate().collect { tasks ->
            try {
                sync(app, tasks)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Could not update the task copies", e)
            }
            // A run changes its task several times in a row; the copies follow at most once a second.
            delay(1_000)
        }
    }

    /** [task] with only what its copy shows, so a change to anything else does not count. */
    private fun shown(task: OrbitTaskEntity) = task.copy(chatId = "", version = 0, lastObservation = "",
        lastFingerprint = "", createdAt = 0, updatedAt = 0, sourceArtifactVersionId = "")

    private suspend fun sync(context: Context, tasks: List<OrbitTaskEntity>) {
        val english = LocaleUtils.usesEnglishPrompt(context)
        val byOrbit = tasks.groupBy { it.orbitId }
        // Only live Orbits; one deleted meanwhile is skipped, since its gone folder is not made again.
        OrbitPreferences.getInstance(context).current().orbits.filter { !it.deleting }.forEach { orbit ->
            try {
                val own = byOrbit[orbit.id].orEmpty()
                val names = fileNames(own)
                val copies = own.associate { task ->
                    names.getValue(task.id) to render(task, OrbitRoutines.runningInstruction(context, task), english)
                }
                val changed = OrbitFiles.of(context, orbit.id).syncTaskCopies(copies) { name, e ->
                    AppLogger.e(TAG, "Could not update the task copy $name of Orbit ${orbit.id}", e)
                }
                if (changed) {
                    changes.update { it + (orbit.id to (it[orbit.id] ?: 0) + 1) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // One Orbit's trouble does not hold up the others.
                AppLogger.e(TAG, "Could not update the task copies of Orbit ${orbit.id}", e)
            }
        }
    }

    /**
     * Each task's file name, by task id: "<title>-<first 8 of the id>.md", readable and one file per task
     * when titles repeat; the rare two that would still share a name get their whole ids.
     */
    internal fun fileNames(tasks: List<OrbitTaskEntity>): Map<String, String> {
        fun name(task: OrbitTaskEntity, idChars: Int): String {
            var slug = task.title.replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-').take(40)
            // Cut in the middle of a character outside the basic plane: drop its first half.
            if (slug.lastOrNull()?.isHighSurrogate() == true) slug = slug.dropLast(1)
            return "${slug.trim('-').ifEmpty { "task" }}-${task.id.filter { it.isLetterOrDigit() }.take(idChars)}.md"
        }
        val short = tasks.associate { it.id to name(it, 8) }
        val shared = short.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return tasks.associate { it.id to if (short.getValue(it.id) in shared) name(it, Int.MAX_VALUE) else short.getValue(it.id) }
    }

    /** One task's copy: what orbit_task detail shows of it, in the words of its fields. */
    internal fun render(task: OrbitTaskEntity, instruction: String, english: Boolean,
        zone: TimeZone = TimeZone.getDefault()): String {
        fun time(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = zone }.format(Date(millis))
        val fields = buildList {
            add("task_id" to task.id)
            add("state" to task.state)
            add("kind" to task.kind)
            when (task.kind) {
                "URL_CHANGE" -> add("url" to task.source)
                OrbitWatchSources.KIND -> {
                    val hook = OrbitWatchSources.hook(task.source)
                    if (hook == null) add("watch" to task.source)
                    else {
                        // The file; orbit_task detail gives the pinned script itself as script.
                        add("script_path" to hook.path)
                        add("language" to if (hook.python) "python" else "sh")
                    }
                }
                OrbitGeofence.KIND -> runCatching { OrbitGeofenceDefinition.parse(task.source) }.getOrNull()?.let {
                    add("location" to "${it.name} · ${it.transition} · ${it.radius.toInt()} m")
                }
            }
            if (task.kind != OrbitGeofence.KIND) add("schedule_mode" to task.scheduleMode)
            if (task.scheduleMode == "DAILY" || task.scheduleMode in OrbitCalendarSchedule.modes)
                add("daily_time" to String.format(Locale.ROOT, "%02d:%02d", task.dailyMinute / 60, task.dailyMinute % 60))
            if (task.calendarRule.isNotEmpty()) add("repeat_on" to task.calendarRule)
            if (task.scheduleMode == "INTERVAL" && task.kind != OrbitGeofence.KIND) add("interval_minutes" to task.intervalMinutes.toString())
            if (task.scheduleMode == "EVENT") add("trigger_task_id" to task.triggerTaskId)
            if (task.state == "ACTIVE" && task.nextRunAt > 0) add("next_run" to time(task.nextRunAt))
            add("notify_policy" to task.notifyPolicy)
            add("use_subagent" to task.useSubagent.toString())
            if (task.useSubagent) add("timeout_minutes" to OrbitTaskRepository.timeoutMinutes(task).toString())
            add("model" to task.modelRole.lowercase(Locale.ROOT))
            if (task.goalId.isNotEmpty()) add("goal_id" to task.goalId)
        }
        val note = when {
            english && OrbitRoutines.isProtected(task) -> "> A read-only copy the app keeps up to date; editing this file changes nothing. " +
                "This is one of Orbit's own routines: only its schedule and model can change, with orbit_task update."
            english -> "> A read-only copy the app keeps up to date; editing this file changes nothing. " +
                "Change the task with orbit_task (update, pause, resume, cancel)."
            OrbitRoutines.isProtected(task) -> "> 应用自动更新的只读副本，改这个文件不会生效。这是 Orbit 自己的例行任务，只能用 orbit_task update 改时间和模型。"
            else -> "> 应用自动更新的只读副本，改这个文件不会生效。要改任务，请用 orbit_task（update、pause、resume、cancel）。"
        }
        return buildString {
            append("---\n").append(OrbitFiles.TASK_COPY_MARK).append("\n---\n")
            // One line whatever line breaks it holds (\r too), so the heading stays right under the mark.
            append("# ").append(task.title.lineSequence().joinToString(" ")).append("\n\n")
            append(note).append("\n\n")
            fields.forEach { (key, value) -> append("- ").append(key).append(": ").append(value.lineSequence().joinToString(" ")).append('\n') }
            append("\n## ").append(if (english) "Instruction" else "要做什么（instruction）").append("\n\n")
            append(instruction.trimEnd()).append('\n')
        }
    }
}
