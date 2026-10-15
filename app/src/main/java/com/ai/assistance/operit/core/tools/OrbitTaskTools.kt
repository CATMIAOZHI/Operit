package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.features.orbit.*
import org.json.JSONArray
import org.json.JSONObject

internal object OrbitTaskTools {
    const val NAME = "orbit_task"
    val prompt = ToolPrompt(
        name = NAME,
        description = "安排当前 Orbit 以后继续处理的事情。后台复用现有工具和自动审核快速，可暂停、取消、查询执行记录或根据用户答复继续。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "create/list/detail/update/pause/resume/cancel/run"),
            ToolParameterSchema("kind", description = "ASSIST（默认）：使用现有工具推进事情；URL_CHANGE：网页变化预检查，无变化可跳过模型", required = false),
            ToolParameterSchema("schedule_mode", description = "ONCE 一次执行（ASSIST默认）；INTERVAL 周期执行（网页监控默认）；EVENT 指定任务成功后执行", required = false),
            ToolParameterSchema("goal_id", description = "可选，orbit_goal 返回的当前 Orbit 目标编号", required = false),
            ToolParameterSchema("trigger_task_id", description = "EVENT 必填：成功后触发本任务的同 Orbit 任务编号", required = false),
            ToolParameterSchema("source_artifact_version_id", description = "可选，继续修改的成果版本编号，由 orbit_artifact 获取", required = false),
            ToolParameterSchema("task_id", description = "list 返回的任务编号；detail/update/pause/resume/cancel/run 必填", required = false),
            ToolParameterSchema("user_note", description = "resume：用户对待办问题的答复，最多4000字符；仅转达实际答复，不代替用户授权", required = false),
            ToolParameterSchema("title", description = "创建时任务名称，最多80字符", required = false),
            ToolParameterSchema("instruction", description = "创建时用户希望关注的变化及汇报要求，最多4000字符", required = false),
            ToolParameterSchema("url", description = "创建时需要检查的 http(s) 网页，不带账号密码；仅执行 GET", required = false),
            ToolParameterSchema("interval_minutes", "integer", "检查间隔，15到10080分钟，默认60", false),
            ToolParameterSchema("notify_policy", description = "CHANGES 仅有价值变化时通知，ALWAYS 每次汇报，DIGEST 每日汇总有价值的结果", required = false),
        ),
        details = "根据用户交代的目标安排后续工作。手机关机/强停/断网时不能承诺准点。" +
            "后台与主聊天使用相同的现有工具和快速审核；需要技能或工具包时按需发现和加载。" +
            "用户在聊天里回答后，调用 resume 转达 user_note。中断过的操作先看 detail 核实，不能盲目重做。" +
            "工具过程保存在执行记录，有价值的正式结果才回到主聊天。",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        val runtime = ToolExecutionManager.currentToolRuntimeContext()
        val orbit = OrbitMemoryScope.owner(context, runtime?.callerChatId) ?: error("This tool requires an Orbit chat")
        val repository = OrbitTaskRepository(context)
        val arguments = tool.parameters.associate { it.name to it.value }
        val action = arguments["action"] ?: error("Missing action")
        val payload: Any = when (action) {
            "create" -> json(repository.create(orbit,
                arguments["title"] ?: error("Missing title"), arguments["instruction"] ?: error("Missing instruction"),
                arguments["url"].orEmpty(), arguments["interval_minutes"]?.toInt() ?: 60,
                arguments["notify_policy"] ?: "CHANGES",
                runtime?.callId ?: java.util.UUID.randomUUID().toString(), arguments["kind"] ?: "ASSIST",
                arguments["schedule_mode"] ?: if (arguments["kind"] != "URL_CHANGE") "ONCE" else "INTERVAL",
                arguments["goal_id"].orEmpty(), arguments["trigger_task_id"].orEmpty(), arguments["source_artifact_version_id"].orEmpty()))
            "list" -> JSONArray(repository.dao.tasks(orbit.id).map(::json))
            else -> {
                val id = arguments["task_id"] ?: error("Missing task_id")
                val task = repository.dao.task(id)?.takeIf { it.orbitId == orbit.id } ?: error("Task not found")
                when (action) {
                    "update" -> JSONObject().put("updated", repository.edit(task,
                        arguments["title"] ?: task.title, arguments["instruction"] ?: task.instruction,
                        arguments["url"] ?: task.source, arguments["interval_minutes"]?.toInt() ?: task.intervalMinutes,
                        arguments["notify_policy"] ?: task.notifyPolicy))
                    "detail" -> json(task).put("executionRecord", repository.executionRecord(id))
                        .put("lastRun", repository.dao.latestRun(id)?.let {
                        JSONObject().put("state", it.state).put("observation", it.observation)
                            .put("error", it.error).put("finishedAt", it.finishedAt)
                    })
                    "resume" -> {
                        if (task.state == "WAITING_USER") {
                            OrbitBrowserHandoff.resume(context, task, arguments["user_note"].orEmpty())
                            JSONObject().put("updated", true)
                        } else JSONObject().put("updated", repository.changeState(orbit.id, id, "ACTIVE"))
                    }
                    "pause", "cancel" -> JSONObject().put("updated",
                        repository.changeState(orbit.id, id,
                            when (action) { "pause" -> "PAUSED"; "resume" -> "ACTIVE"; else -> "CANCELLED" }))
                    "run" -> {
                        val queued = repository.dao.enqueueManual(id,
                            runtime?.callId ?: java.util.UUID.randomUUID().toString(), System.currentTimeMillis())
                        OrbitTaskScheduler.reschedule(context)
                        JSONObject().put("queued", queued)
                    }
                    else -> error("Unknown action")
                }
            }
        }
        ToolResult(NAME, true, StringResultData(payload.toString()))
    }

    private fun json(task: OrbitTaskEntity) = JSONObject().put("id", task.id).put("title", task.title)
        .put("kind", task.kind)
        .put("schedule_mode", task.scheduleMode).put("goal_id", task.goalId).put("trigger_task_id", task.triggerTaskId)
        .put("state", task.state).put("url", task.source).put("instruction", task.instruction)
        .put("interval_minutes", task.intervalMinutes).put("notify_policy", task.notifyPolicy)
        .put("nextRunAt", task.nextRunAt).put("lastObservation", task.lastObservation)
}
