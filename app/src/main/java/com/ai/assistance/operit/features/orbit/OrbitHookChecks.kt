package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.model.OrbitTaskEntity

/** A successful dry run authorizes only the pinned script that was actually tried. */
object OrbitHookChecks {
    private fun prefs(context: Context) = context.getSharedPreferences("orbit_hook_checks", Context.MODE_PRIVATE)
    fun needed(task: OrbitTaskEntity) = task.kind == OrbitWatchSources.KIND && OrbitWatchSources.isScript(task.source)
    fun passed(context: Context, task: OrbitTaskEntity) =
        !needed(task) || prefs(context).getString(task.id, null) == task.source

    /**
     * The pinned script on disk is still the one [task] names: a hook file edited by hand (or by a
     * forget) since is refused at run time, so it no longer counts as tried.
     */
    suspend fun intact(context: Context, task: OrbitTaskEntity): Boolean {
        if (!needed(task)) return true
        val hook = OrbitWatchSources.hook(task.source) ?: return false
        return try {
            OrbitWatchSources.hash(OrbitFiles.of(context, task.orbitId).read(hook.path)) == hook.hash
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
    fun remember(context: Context, task: OrbitTaskEntity) {
        check(prefs(context).edit().putString(task.id, task.source).commit()) { "Could not save the dry run result; retry dry_run" }
    }
    fun forget(context: Context, taskId: String) {
        check(prefs(context).edit().remove(taskId).commit()) { "Could not reset the dry run result; retry dry_run" }
    }
}
