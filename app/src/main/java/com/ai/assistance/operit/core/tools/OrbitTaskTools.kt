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
        description = "管理当前 Orbit 的持久只读监控任务。任务会在手机允许运行时检查网页变化，可暂停、取消、查看结果；普通聊天能力不受影响。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "create/list/detail/update/pause/resume/cancel/run"),
            ToolParameterSchema("task_id", description = "list 返回的任务编号；detail/update/pause/resume/cancel/run 必填", required = false),
            ToolParameterSchema("title", description = "创建时任务名称，最多80字符", required = false),
            ToolParameterSchema("instruction", description = "创建时用户希望关注的变化及汇报要求，最多4000字符", required = false),
            ToolParameterSchema("url", description = "创建时需要检查的 http(s) 网页，不带账号密码；仅执行 GET", required = false),
            ToolParameterSchema("interval_minutes", "integer", "检查间隔，15到10080分钟，默认60", false),
            ToolParameterSchema("notify_policy", description = "CHANGES 仅有价值变化时通知，ALWAYS 每次汇报", required = false),
        ),
        details = "只有用户要求持续检查、跟踪或提醒时创建。手机关机/强停/断网时不能承诺准点。" +
            "当前持久任务只执行网页读取与分析，不支持后台发送邮件、购买或提交表单。" +
            "任务的中间工具输出不会进入主聊天，正式结果可在任务详情中继续查询。",
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
                arguments["url"] ?: error("Missing url"), arguments["interval_minutes"]?.toInt() ?: 60,
                arguments["notify_policy"] ?: "CHANGES",
                runtime?.callId ?: java.util.UUID.randomUUID().toString()))
            "list" -> JSONArray(repository.dao.tasks(orbit.id).filter { it.kind != "ONCE" }.map(::json))
            else -> {
                val id = arguments["task_id"] ?: error("Missing task_id")
                val task = repository.dao.task(id)?.takeIf { it.orbitId == orbit.id } ?: error("Task not found")
                when (action) {
                    "update" -> JSONObject().put("updated", repository.edit(task,
                        arguments["title"] ?: task.title, arguments["instruction"] ?: task.instruction,
                        arguments["url"] ?: task.source, arguments["interval_minutes"]?.toInt() ?: task.intervalMinutes,
                        arguments["notify_policy"] ?: task.notifyPolicy))
                    "detail" -> json(task).put("lastRun", repository.dao.latestRun(id)?.let {
                        JSONObject().put("state", it.state).put("observation", it.observation)
                            .put("error", it.error).put("finishedAt", it.finishedAt)
                    })
                    "pause", "resume", "cancel" -> JSONObject().put("updated",
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
        .put("state", task.state).put("url", task.source).put("instruction", task.instruction)
        .put("interval_minutes", task.intervalMinutes).put("notify_policy", task.notifyPolicy)
        .put("nextRunAt", task.nextRunAt).put("lastObservation", task.lastObservation)
}
