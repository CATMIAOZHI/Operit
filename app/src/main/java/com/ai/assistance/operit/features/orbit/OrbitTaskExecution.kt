package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.os.SystemClock
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.OrbitRunEntity
import com.ai.assistance.operit.data.model.OrbitTaskChatEntity
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.ui.permissions.UnattendedChats

/** Every child resolves its authority from durable host data, never from the generated prompt. */
internal object OrbitTaskExecution {
    data class Scope(val binding: OrbitTaskChatEntity, val run: OrbitRunEntity, val task: OrbitTaskEntity)

    fun childPrompts() = OrbitTaskActionTools.prompts.filter { it.name != OrbitTaskActionTools.WAIT } +
        com.ai.assistance.operit.core.agent.collaboration.CollaborationTools.category(
            false, com.ai.assistance.operit.core.agent.AgentProfileRepository.instance.listAvailableSubagents()
        ).tools

    suspend fun resolve(context: Context, chatId: String?): Scope? {
        if (chatId == null) return null
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        val binding = dao.binding(chatId) ?: return null
        val run = checkNotNull(dao.run(binding.runId)) { "Task execution no longer exists" }
        check(run.generation == binding.generation) { "Task execution was replaced" }
        val task = checkNotNull(dao.task(run.taskId))
        check(task.kind == "ASSIST" || binding.chatId == binding.rootChatId) { "This task cannot delegate" }
        val settings = OrbitPreferences.getInstance(context).current()
        check(settings.activeOrbitId == task.orbitId && settings.active?.deleting == false) { "Orbit is paused" }
        check(dao.currentLease(run, OrbitTaskRunner.bootId(context),
            System.currentTimeMillis(), SystemClock.elapsedRealtime()) != null) { "Task is no longer running" }
        return Scope(binding, run, task)
    }

    suspend fun bindRoot(context: Context, chatId: String, run: OrbitRunEntity) {
        AppDatabase.getDatabase(context).orbitTaskDao().insertBinding(
            OrbitTaskChatEntity(chatId, run.id, run.generation, chatId))
    }

    suspend fun bindChild(context: Context, parentChatId: String, childChatId: String) {
        val parent = resolve(context, parentChatId) ?: return
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        val binding = parent.binding.copy(chatId = childChatId)
        val old = dao.binding(childChatId)
        check(old == null || old == binding) { "Child belongs to another task execution" }
        if (old == null) dao.insertBinding(binding)
        OrbitPreferences.getInstance(context).rememberChatOwner(childChatId, parent.task.orbitId)
        UnattendedChats.begin(childChatId)
    }

    suspend fun checkBeforeTool(context: Context, chatId: String?) {
        resolve(context, chatId)
    }

    /** A CLI visibility exception, not a permission-review exemption. Execution rechecks the lease. */
    fun isTaskCapability(name: String) = name in OrbitTaskActionTools.names
}
