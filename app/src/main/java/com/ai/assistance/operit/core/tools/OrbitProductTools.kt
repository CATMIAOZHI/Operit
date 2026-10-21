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
        ToolPrompt(name = GOAL, description = "管理当前 Orbit 的长期目标和计划。进度由关联任务的真实状态计算；创建后用 orbit_task.goal_id 关联执行步骤。" +
            "list 给每个目标的计划前300字和进度（progress：steps_total 步骤总数、done 做完、running_now 正在运行、scheduled 已启用等下次运行、paused 暂停、waiting_user 等用户、needs_verification 等核实、failed 上次失败）；" +
            "detail 给完整计划和步骤，每页30步，每步的 last_observation 只给前600字（observation_cut=true 表示被截），next_offset 不为空时用 offset 看后面的步骤；" +
            "step 读一个步骤的完整 last_observation，每次最多20000字，next_offset 不为空时用 offset 接着读。汇报进度只按 progress 说，不要猜，scheduled 不等于正在做。",
            parametersStructured = listOf(
                ToolParameterSchema("action", description = "create/list/detail/step/update/pause/resume/cancel", required = true),
                ToolParameterSchema("goal_id", description = "list 返回的 goal_id；detail/step/update/pause/resume/cancel 必填", required = false),
                ToolParameterSchema("task_id", description = "step 必填：detail 里步骤的 task_id", required = false),
                ToolParameterSchema("offset", "integer", "detail：从第几个步骤开始（从0开始）；step：字符偏移。默认0，用上次返回的 next_offset", false),
                ToolParameterSchema("title", description = "create 必填、update 可改：目标标题，最多80字符", required = false),
                ToolParameterSchema("plan", description = "create 可选、update 可改（会整段替换）：步骤、预期成果和完成标准，最多8000字符", required = false))),
        ToolPrompt(name = ARTIFACT, description = "列出或分页读取当前 Orbit 的成果版本，继续修改时将 version_id 交给 orbit_task.source_artifact_version_id。" +
            "list 每页最多100个，read 每次最多 20000 字；next_offset 不为空时用 offset 接着列或接着读。",
            parametersStructured = listOf(
                ToolParameterSchema("action", description = "list/read", required = true),
                ToolParameterSchema("version_id", description = "list 返回的 version_id，read 必填", required = false),
                ToolParameterSchema("offset", "integer", "list：从第几个版本开始（从0开始）；read：字符偏移。默认0，用上次返回的 next_offset", false))),
    )
    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        val runtime = ToolExecutionManager.currentToolRuntimeContext()
        val orbit = checkNotNull(OrbitMemoryScope.owner(context, runtime?.callerChatId)) { "This tool only works in an Orbit chat" }
        val args = tool.parameters.associate { it.name to it.value }
        val dao = OrbitTaskRepository(context).dao
        val result: Any = if (tool.name == ARTIFACT) when (args["action"]) {
            "list" -> {
                val total = dao.artifactCount(orbit.id)
                val offset = offset(args["offset"])
                require(offset == 0 || offset < total) { "offset $offset is past the end; there are $total versions" }
                val page = dao.artifacts(orbit.id, MAX_ARTIFACTS, offset)
                val next = (offset + page.size).takeIf { page.isNotEmpty() && it < total }
                JSONObject().put("total", total).put("offset", offset).put("returned", page.size)
                    .put("next_offset", next ?: JSONObject.NULL)
                    .put("versions", JSONArray(page.map {
                        JSONObject().put("version_id", it.id).put("artifact_id", it.artifactId)
                            .put("name", it.name).put("task_id", it.taskId).put("created_at", time(it.createdAt))
                    }))
            }
            "read" -> {
                val versionId = args["version_id"]?.trim()?.takeIf { it.isNotEmpty() } ?: error("read needs version_id; get it from list")
                val text = OrbitArtifactRepository(context).read(orbit.id, versionId)
                val offset = offset(args["offset"])
                require(offset in 0..text.length) { "offset must be 0..${text.length}; got $offset" }
                val end = (offset + 20_000).coerceAtMost(text.length)
                JSONObject().put("version_id", versionId).put("text", text.substring(offset, end))
                    .put("offset", offset).put("total_chars", text.length).put("has_more", end < text.length)
                    .put("next_offset", if (end < text.length) end else JSONObject.NULL)
            }
            else -> error("Unknown action \"${args["action"].orEmpty()}\"; use list or read")
        } else {
            check(tool.name == GOAL)
            val repository = OrbitGoalRepository(context)
            val action = args["action"]?.trim()
            // Outside the user's chats (background runs) goals are read, never changed.
            val talk = com.ai.assistance.operit.data.preferences.OrbitPreferences.getInstance(context).current()
                .orbitForChat(runtime?.callerChatId) != null
            require(talk || action == "list" || action == "detail" || action == "step") {
                "In a background run orbit_goal can only list, detail or step; changes to goals happen in the chat with the user"
            }
            when (action) {
                "create" -> json(repository.create(orbit.id, args["title"]?.takeIf { it.isNotBlank() } ?: error("create needs title"),
                    args["plan"].orEmpty(), runtime?.callId ?: java.util.UUID.randomUUID().toString()), dao.tasks(orbit.id), full = true, dao)
                "list" -> dao.tasks(orbit.id).let { tasks -> JSONObject().put("goals", JSONArray(dao.goals(orbit.id).map { json(it, tasks, full = false, dao) })) }
                    .put("note", "plan is cut to $PLAN_EXCERPT characters here; detail gives it in full with each step")
                "detail", "step", "update", "pause", "resume", "cancel" -> {
                    val goalId = args["goal_id"]?.trim()?.takeIf { it.isNotEmpty() } ?: error("$action needs goal_id; get it from list")
                    val goal = dao.goal(goalId)?.takeIf { it.orbitId == orbit.id }
                        ?: error("No goal with goal_id \"$goalId\"; call orbit_goal with action=list to find it")
                    if (action == "detail") {
                        json(goal, dao.tasks(orbit.id), full = true, dao, stepOffset = offset(args["offset"]))
                    } else if (action == "step") {
                        val taskId = args["task_id"]?.trim()?.takeIf { it.isNotEmpty() } ?: error("step needs task_id; get it from detail")
                        val task = dao.task(taskId)?.takeIf { it.orbitId == orbit.id && it.goalId == goal.id }
                            ?: error("Goal \"${goal.id}\" has no step with task_id \"$taskId\"; call orbit_goal with action=detail to find it")
                        val text = task.lastObservation
                        val from = offset(args["offset"])
                        require(from == 0 || from < text.length) { "offset $from is past the end; this observation has ${text.length} characters" }
                        val end = minOf(text.length, from + 20_000)
                        val lastRun = if (task.state in setOf("ACTIVE", "PAUSED")) dao.latestRun(task.id)?.state else null
                        val state = if (lastRun == "FAILED") "FAILED" else if (lastRun == "RUNNING") "RUNNING" else task.state
                        JSONObject().put("goal_id", goal.id).put("task_id", task.id).put("title", task.title).put("state", state)
                            .put("last_observation", text.substring(from, end)).put("offset", from).put("total_chars", text.length)
                            .put("next_offset", if (end < text.length) end else JSONObject.NULL)
                    } else {
                        val state = when (action) { "pause" -> "PAUSED"; "resume" -> "ACTIVE"; "cancel" -> "CANCELLED"; else -> goal.state }
                        repository.update(goal, args["title"]?.takeIf { it.isNotBlank() } ?: goal.title, args["plan"] ?: goal.plan, state)
                        val now = checkNotNull(dao.goal(goal.id))
                        val changed = buildList {
                            if (now.title != goal.title) add("title")
                            if (now.plan != goal.plan) add("plan")
                            if (now.state != goal.state) add("state")
                        }
                        json(now, dao.tasks(orbit.id), full = false, dao)
                            .put("changed", changed.isNotEmpty()).put("changed_fields", JSONArray(changed))
                    }
                }
                else -> error("Unknown action \"${action.orEmpty()}\"; use create, list, detail, update, pause, resume or cancel")
            }
        }
        ToolResult(tool.name, true, StringResultData(result.toString()))
    }

    private const val PLAN_EXCERPT = 300
    private const val MAX_ARTIFACTS = 100
    private const val STEP_PAGE = 30
    private const val OBSERVATION_EXCERPT = 600

    private fun offset(raw: String?): Int = raw?.trim()?.takeIf { it.isNotEmpty() }?.let {
        it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: error("offset must be a whole number from 0; got \"$it\"")
    } ?: 0

    private fun time(at: Long) = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(at))

    /**
     * A goal with its progress counted from the tasks linked to it. [full] gives the whole plan and
     * every step; otherwise the plan is cut for a list.
     */
    private suspend fun json(goal: OrbitGoalEntity, all: List<OrbitTaskEntity>, full: Boolean,
        dao: com.ai.assistance.operit.data.dao.OrbitTaskDao, stepOffset: Int = 0): JSONObject {
        val steps = all.filter { it.goalId == goal.id && it.state != "CANCELLED" }
        // The latest run tells a step that is running now or whose last run failed (stopped or retrying).
        val lastRun = steps.filter { it.state in setOf("ACTIVE", "PAUSED") }.associate { it.id to dao.latestRun(it.id)?.state }
        val failed = lastRun.filterValues { it == "FAILED" }.keys
        val running = lastRun.filterValues { it == "RUNNING" }.keys
        val progress = JSONObject().put("steps_total", steps.size)
            .put("done", steps.count { it.state == "DONE" })
            .put("running_now", running.size)
            .put("scheduled", steps.count { it.state == "ACTIVE" && it.id !in failed && it.id !in running })
            .put("paused", steps.count { it.state == "PAUSED" && it.id !in failed })
            .put("waiting_user", steps.count { it.state == "WAITING_USER" })
            .put("needs_verification", steps.count { it.state == "VERIFY" })
            .put("failed", failed.size)
        return JSONObject().put("goal_id", goal.id).put("title", goal.title).put("state", goal.state)
            .put("plan", if (full || goal.plan.length <= PLAN_EXCERPT) goal.plan else goal.plan.take(PLAN_EXCERPT) + "…")
            .put("progress", progress)
            .apply {
                if (full) {
                    require(stepOffset == 0 || stepOffset < steps.size) { "offset $stepOffset is past the end; this goal has ${steps.size} steps" }
                    val page = steps.drop(stepOffset).take(STEP_PAGE)
                    val next = (stepOffset + page.size).takeIf { it < steps.size }
                    put("steps_offset", stepOffset).put("steps_returned", page.size).put("next_offset", next ?: JSONObject.NULL)
                    put("steps", JSONArray(page.map {
                        val cut = it.lastObservation.length > OBSERVATION_EXCERPT
                        JSONObject().put("task_id", it.id).put("title", it.title)
                            .put("state", if (it.id in failed) "FAILED" else if (it.id in running) "RUNNING" else it.state)
                            .put("last_observation", if (cut) it.lastObservation.take(OBSERVATION_EXCERPT) + "…" else it.lastObservation)
                            .apply { if (cut) put("observation_cut", true).put("observation_chars", it.lastObservation.length) }
                    }))
                }
            }
    }
}
