package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.OrbitGoalEntity
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import java.util.UUID

internal class OrbitGoalRepository(private val context: Context) {
    val dao = AppDatabase.getDatabase(context).orbitTaskDao()
    suspend fun create(orbitId: String, title: String, plan: String, requestId: String): OrbitGoalEntity {
        require(title.isNotBlank() && title.length <= 80 && plan.length <= 8000)
        check(OrbitPreferences.getInstance(context).current().orbits.any { it.id == orbitId && !it.deleting })
        val id = UUID.nameUUIDFromBytes("$orbitId:goal:$requestId".toByteArray()).toString()
        val now = System.currentTimeMillis()
        OrbitExecutionGate.serialized {
            check(dao.goals(orbitId).count { it.state != "CANCELLED" } < 50 || dao.goal(id) != null)
            dao.insertGoal(OrbitGoalEntity(id, orbitId, title.trim(), plan.trim(), "ACTIVE", 1, now, now))
        }
        // The first goal starts the morning briefing; it skips itself while no goal is active.
        OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == orbitId }
            ?.let { OrbitRoutines.ensureGoalBriefing(context, it) }
        return checkNotNull(dao.goal(id))
    }
    suspend fun update(goal: OrbitGoalEntity, title: String, plan: String, state: String) {
        require(title.isNotBlank() && title.length <= 80 && plan.length <= 8000)
        require(state in setOf("ACTIVE", "PAUSED", "CANCELLED"))
        check(goal.state != "CANCELLED" || state == "CANCELLED")
        OrbitExecutionGate.serialized {
            check(dao.saveGoal(goal.copy(title = title.trim(), plan = plan.trim(), state = state,
                updatedAt = System.currentTimeMillis()), goal.version))
        }
        if (state != "ACTIVE") dao.tasks(goal.orbitId).filter { it.goalId == goal.id }.forEach {
            OrbitTaskRunner.cancelTask(it.id)
            OrbitBrowserControl.release(it.id)
        }
        OrbitTaskScheduler.reschedule(context)
    }
}
