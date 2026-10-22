package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.model.OrbitTaskEntity

/** A successful dry run authorizes only the pinned script that was actually tried. */
object OrbitHookChecks {
    private fun prefs(context: Context) = context.getSharedPreferences("orbit_hook_checks", Context.MODE_PRIVATE)
    fun needed(task: OrbitTaskEntity) = task.kind == OrbitWatchSources.KIND && OrbitWatchSources.isScript(task.source)
    fun passed(context: Context, task: OrbitTaskEntity) =
        !needed(task) || prefs(context).getString(task.id, null) == task.source
    fun remember(context: Context, task: OrbitTaskEntity) {
        check(prefs(context).edit().putString(task.id, task.source).commit()) { "Could not save the dry run result; retry dry_run" }
    }
    fun forget(context: Context, taskId: String) {
        check(prefs(context).edit().remove(taskId).commit()) { "Could not reset the dry run result; retry dry_run" }
    }
}
