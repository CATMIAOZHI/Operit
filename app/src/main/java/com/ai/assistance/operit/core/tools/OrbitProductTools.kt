package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.features.orbit.*
import org.json.JSONArray
import org.json.JSONObject

internal object OrbitProductTools {
    const val GOAL = "orbit_goal"
    const val ARTIFACT = "orbit_artifact"
    val prompts = listOf(
        ToolPrompt(name = GOAL, description = "管理当前 Orbit 的长期目标和计划。进度由关联任务的真实状态计算；创建后用 orbit_task.goal_id 关联执行步骤。",
            parametersStructured = listOf(
                ToolParameterSchema("action", description = "create/list/update/pause/resume/cancel", required = true),
                ToolParameterSchema("goal_id", description = "已有目标编号", required = false),
                ToolParameterSchema("title", description = "目标标题，最多80字符", required = false),
                ToolParameterSchema("plan", description = "步骤、预期成果和完成标准，最多8000字符", required = false))),
        ToolPrompt(name = ARTIFACT, description = "列出或分页读取当前 Orbit 的成果版本，继续修改时将 version_id 交给 orbit_task.source_artifact_version_id。",
            parametersStructured = listOf(
                ToolParameterSchema("action", description = "list/read", required = true),
                ToolParameterSchema("version_id", description = "成果版本编号，read必填", required = false),
                ToolParameterSchema("offset", "integer", "读取的字符偏移，默认0", false))),
    )
    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        val runtime = ToolExecutionManager.currentToolRuntimeContext()
        val orbit = checkNotNull(OrbitMemoryScope.owner(context, runtime?.callerChatId))
        val args = tool.parameters.associate { it.name to it.value }
        val dao = OrbitTaskRepository(context).dao
        val result: Any = if (tool.name == ARTIFACT) when (args["action"]) {
            "list" -> JSONArray(dao.artifacts(orbit.id).map {
                JSONObject().put("version_id", it.id).put("artifact_id", it.artifactId)
                    .put("name", it.name).put("task_id", it.taskId).put("createdAt", it.createdAt)
            })
            "read" -> {
                val text = OrbitArtifactRepository(context).read(orbit.id, args["version_id"] ?: error("Missing version_id"))
                val offset = args["offset"]?.toInt() ?: 0
                require(offset in 0..text.length)
                JSONObject().put("text", text.drop(offset).take(20_000)).put("totalChars", text.length)
                    .put("nextOffset", (offset + 20_000).coerceAtMost(text.length))
            }
            else -> error("Unknown action")
        } else {
            check(tool.name == GOAL)
            val repository = OrbitGoalRepository(context)
            when (val action = args["action"]) {
                "create" -> json(repository.create(orbit.id, args["title"] ?: error("Missing title"),
                    args["plan"].orEmpty(), runtime?.callId ?: java.util.UUID.randomUUID().toString()))
                "list" -> JSONArray(dao.goals(orbit.id).map(::json))
                else -> {
                    val goal = checkNotNull(dao.goal(args["goal_id"] ?: error("Missing goal_id")))
                    check(goal.orbitId == orbit.id)
                    val state = when (action) { "update" -> goal.state; "pause" -> "PAUSED"; "resume" -> "ACTIVE"; "cancel" -> "CANCELLED"; else -> error("Unknown action") }
                    repository.update(goal, args["title"] ?: goal.title, args["plan"] ?: goal.plan, state)
                    json(checkNotNull(dao.goal(goal.id)))
                }
            }
        }
        ToolResult(tool.name, true, StringResultData(result.toString()))
    }
    private fun json(goal: OrbitGoalEntity) = JSONObject().put("id", goal.id).put("title", goal.title)
        .put("plan", goal.plan).put("state", goal.state)
}
