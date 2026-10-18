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
        description = "安排当前 Orbit 以后继续处理的事情。到点时默认在安排它的聊天里叫醒你本人处理；也可以交给后台子代理做完再把结果交回给你。可暂停、取消、查询执行记录或根据用户答复继续。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "create/list/detail/update/pause/resume/cancel/run/pending（列出在等用户答复、等核实、或最近一次运行失败的任务；waiting_for 是在等什么或失败原因，unverified_actions 是还没核实结果的操作）"),
            ToolParameterSchema("kind", description = "ASSIST（默认）：使用现有工具推进事情；URL_CHANGE：网页变化预检查，无变化可跳过模型；WATCH：盯通知、短信、日历或小脚本，内容没变就不叫醒你", required = false),
            ToolParameterSchema("watch", description = "WATCH 必填：notifications（通知栏里还在的通知）或 notifications:包名,包名；sms 或 sms:发件人；calendar 或 calendar:天数（1-30，默认7）；script（同时填 script）", required = false),
            ToolParameterSchema("script", description = "watch=script 时的 shell 脚本全文（最多8000字符），不要同时填别的 watch 来源。以 Operit 当前的 shell 权限运行（开了 root/Shizuku 就是那个身份；只有无障碍权限时不能运行脚本），只能做只读检查：不改文件、不发送数据。每次检查运行它，输出就是要盯的内容，输出变了才叫醒你；运行超过25秒算失败。输出要短，自己截断（如 | head -c 4000）。不要放密码", required = false),
            ToolParameterSchema("schedule_mode", description = "ONCE 一次执行（ASSIST默认，配 run_at 即日程提醒）；DAILY 每天；WEEKLY 每周；MONTHLY 每月；YEARLY 每年（这四种在 daily_time 执行）；INTERVAL 每隔 interval_minutes 执行（网页监控默认）；EVENT 指定任务成功后执行", required = false),
            ToolParameterSchema("run_at", description = "ONCE 的执行时间，本地时间 yyyy-MM-dd HH:mm；不填则立即执行。到点时你会被唤醒处理并提醒用户", required = false),
            ToolParameterSchema("daily_time", description = "DAILY/WEEKLY/MONTHLY/YEARLY 必填：执行的本地时间 HH:mm", required = false),
            ToolParameterSchema("repeat_on", description = "WEEKLY/MONTHLY/YEARLY 必填。WEEKLY：星期几，逗号分隔，1=周一…7=周日，如 1,3,5；MONTHLY：每月几号 1-31，如 15（小月取最后一天）；YEARLY：月-日，如 12-03（2月29日在平年取2月28日）", required = false),
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
            ToolParameterSchema("use_subagent", "boolean", "可选，默认 false：到点在聊天里叫醒你本人处理（提醒、问候、几步就能做完的事）。true：到点由后台子代理去做，做完把结果交回给你，再由你决定怎么告诉用户；适合步骤多、耗时长、不需要聊天上下文的活（如批量审查、长时间整理）", false),
            ToolParameterSchema("timeout_minutes", "integer", "use_subagent=true 时子代理单次运行时限，1到1440分钟（最多24小时），不填默认5分钟；超时会停下并暂停任务。超过8分钟的任务会切到前台运行并显示一条通知", false),
        ),
        details = "根据用户交代的目标安排后续工作。用户说“某天几点提醒我”用 ONCE+run_at+notify_policy=ALWAYS；" +
            "“每天几点”用 DAILY+daily_time；“每周几几点”用 WEEKLY+repeat_on+daily_time；“每月几号”用 MONTHLY+repeat_on+daily_time；" +
            "“每年几月几号”（生日、纪念日、续费）用 YEARLY+repeat_on+daily_time；周期提醒同样配 notify_policy=ALWAYS；" +
            "“每隔几小时”用 INTERVAL。手机关机/强停/断网时不能承诺准点。" +
            "到点时默认在安排它的聊天里给你一条隐藏的“到点了”消息，你带着完整的聊天上下文自己处理，用户只看到你说的话；提醒就直接用自己的话提醒，不需要额外设置。" +
            "只有活很重、很长时才设 use_subagent=true：子代理在后台做（默认最多5分钟，按需调大 timeout_minutes，最多1440分钟；超过8分钟会在前台运行并显示通知，系统不允许切前台时只能跑8分钟），结果交回给你后再由你告诉用户。" +
            "子代理与主聊天使用相同的现有工具和快速审核；需要技能或工具包时按需发现和加载。" +
            "用户在聊天里回答后，调用 resume 转达 user_note。中断过的操作先看 detail 核实，不能盲目重做。" +
            "子代理的工具过程保存在执行记录，它的结果先交给你，由你在安排它的聊天（主聊天或侧聊）里告诉用户。" +
            "用户说“有某某消息/短信/日程变化就告诉我”时用 WATCH+INTERVAL+notify_policy=CHANGES，间隔按急不急定（最少15分钟）。" +
            "通知、短信、日历需要用户在系统设置里给 Operit 对应权限，没给时任务会记下原因并提醒用户一次。" +
            "现成来源不够时写一个只读的小脚本（比如查一个接口、看一个文件），只输出要比较的内容，不要输出时间戳这类每次都变的东西。" +
            "读短信、通知必须是用户明确要求的；汇报时只说和任务有关的内容，不要照抄无关的私密消息。" +
            "用户问“还有什么在等我”时用 pending。",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        val runtime = ToolExecutionManager.currentToolRuntimeContext()
        val orbit = OrbitMemoryScope.owner(context, runtime?.callerChatId) ?: error("This tool requires an Orbit chat")
        val repository = OrbitTaskRepository(context)
        val arguments = tool.parameters.associate { it.name to it.value }
        val action = arguments["action"] ?: error("Missing action")
        val payload: Any = when (action) {
            "create" -> {
                val kind = arguments["kind"] ?: "ASSIST"
                val mode = arguments["schedule_mode"] ?: if (kind == "ASSIST") "ONCE" else "INTERVAL"
                val runAt = arguments["run_at"]?.takeIf { it.isNotBlank() }?.let(::parseRunAt)
                val calendar = mode in OrbitCalendarSchedule.modes
                val requestId = runtime?.callId ?: java.util.UUID.randomUUID().toString()
                val source = if (kind == OrbitWatchSources.KIND) {
                    watchSource(context, orbit.id, arguments, arguments["title"].orEmpty(), requestId)
                } else arguments["url"].orEmpty()
                json(repository.create(orbit,
                arguments["title"] ?: error("Missing title"), arguments["instruction"] ?: error("Missing instruction"),
                source, arguments["interval_minutes"]?.toInt() ?: 60,
                // A reminder exists to reach the user, so it always reports unless told otherwise.
                arguments["notify_policy"] ?: if (runAt != null || calendar) "ALWAYS" else "CHANGES",
                requestId, kind,
                mode,
                arguments["goal_id"].orEmpty(), arguments["trigger_task_id"].orEmpty(), arguments["source_artifact_version_id"].orEmpty(),
                runAt = runAt,
                dailyMinute = arguments["daily_time"]?.takeIf { it.isNotBlank() }?.let(::parseDailyTime)
                    ?: if (mode == "DAILY" || calendar) error("$mode needs daily_time (HH:mm)") else 0,
                calendarRule = arguments["repeat_on"].orEmpty(),
                timeoutMinutes = parseTimeout(arguments["timeout_minutes"]) ?: 0,
                useSubagent = parseFlag(arguments["use_subagent"]) ?: false,
                // Made in a side chat: its reports go back to that side chat, like Muse. Made by a
                // running task: its reports go where that task reports.
                chatId = reportChatId(context, orbit, runtime?.callerChatId)))
            }
            "list" -> JSONArray(repository.dao.tasks(orbit.id).map(::json))
            "pending" -> JSONArray(repository.dao.tasks(orbit.id).mapNotNull { task ->
                val last = repository.dao.latestRun(task.id)
                // Waiting for the user, waiting to be checked, or failing (stopped or still retrying).
                val waiting = task.state in setOf("WAITING_USER", "VERIFY") ||
                    (task.state in setOf("PAUSED", "ACTIVE") && last?.state == "FAILED")
                if (!waiting) null else json(task).put("waiting_for", last?.error.orEmpty())
                    .put("unverified_actions", JSONArray(repository.dao.unresolvedActions(task.id).map { it.summary }))
            })
            else -> {
                val id = arguments["task_id"] ?: error("Missing task_id")
                val task = repository.dao.task(id)?.takeIf { it.orbitId == orbit.id } ?: error("Task not found")
                when (action) {
                    "update" -> JSONObject().put("updated", repository.edit(task,
                        arguments["title"] ?: task.title, arguments["instruction"] ?: task.instruction,
                        if (task.kind == OrbitWatchSources.KIND &&
                            (arguments["watch"] != null || arguments["script"] != null || arguments["url"] != null)) {
                            watchSource(context, orbit.id, arguments, task.title, runtime?.callId ?: java.util.UUID.randomUUID().toString())
                        } else arguments["url"] ?: task.source,
                        arguments["interval_minutes"]?.toInt() ?: task.intervalMinutes,
                        arguments["notify_policy"] ?: task.notifyPolicy,
                        arguments["run_at"]?.takeIf { it.isNotBlank() }?.let(::parseRunAt),
                        arguments["daily_time"]?.takeIf { it.isNotBlank() }?.let(::parseDailyTime) ?: task.dailyMinute,
                        arguments["repeat_on"]?.takeIf { it.isNotBlank() } ?: task.calendarRule,
                        parseTimeout(arguments["timeout_minutes"]) ?: task.timeoutMinutes,
                        parseFlag(arguments["use_subagent"]) ?: task.useSubagent))
                    "detail" -> json(task).put("executionRecord", repository.executionRecord(id))
                        .put("lastRun", repository.dao.latestRun(id)?.let {
                        JSONObject().put("state", it.state).put("observation", it.observation)
                            .put("error", it.error).put("finishedAt", it.finishedAt)
                            .put("decision", when (it.decision) {
                                OrbitRunEntity.UNCHANGED -> context.getString(com.ai.assistance.operit.R.string.orbit_decision_unchanged)
                                OrbitRunEntity.IN_CHAT -> context.getString(com.ai.assistance.operit.R.string.orbit_decision_in_chat)
                                OrbitRunEntity.SKIPPED -> com.ai.assistance.operit.features.orbit.OrbitRoutines.skippedReason(context, task)
                                else -> it.decision
                            })
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

    private fun parseRunAt(value: String): Long {
        val text = value.trim().replace('T', ' ')
        val zone = java.time.ZoneId.systemDefault()
        val local = runCatching {
            java.time.LocalDateTime.parse(text, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm"))
        }.getOrNull() ?: runCatching {
            java.time.LocalDate.parse(text).atTime(9, 0)
        }.getOrNull()
        val millis = local?.atZone(zone)?.toInstant()?.toEpochMilli()
            ?: runCatching { java.time.OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli() }.getOrNull()
            ?: error("run_at must look like 2026-12-03 18:30")
        require(millis > System.currentTimeMillis() - 60_000) { "run_at is already in the past" }
        return millis
    }

    /**
     * The source of a WATCH task. A script is saved in files/hooks/ and pinned by its hash, so the
     * script that was reviewed with this call is the only one the background check will run.
     */
    private suspend fun watchSource(context: Context, orbitId: String, args: Map<String, String>,
        title: String, requestId: String): String {
        val watch = args["watch"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (!args["script"].isNullOrBlank()) "script" else error("WATCH needs watch")
        // A script is only ever pinned here, from the text given in this call.
        require(!watch.startsWith("script:")) { "Give the script text in script and set watch=script" }
        if (watch != "script") {
            require(args["script"].isNullOrBlank()) { "script only goes with watch=script" }
            OrbitWatchSources.validate(watch)
            return watch
        }
        val script = args["script"]?.takeIf { it.isNotBlank() } ?: error("watch=script needs the script text in script")
        require(script.length <= 8000) { "script is longer than 8000 characters" }
        val slug = title.replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-').take(40).ifEmpty { "watch" }
        val path = "files/hooks/$slug-${OrbitWatchSources.hash(requestId).take(8)}.sh"
        OrbitFiles.of(context, orbitId).write(path, script)
        return "script:$path@${OrbitWatchSources.hash(script)}"
    }

    private fun parseDailyTime(value: String): Int {
        val match = Regex("""^\s*(\d{1,2})[:：](\d{2})\s*$""").find(value) ?: error("daily_time must look like 08:30")
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        require(hour in 0..23 && minute in 0..59) { "daily_time must look like 08:30" }
        return hour * 60 + minute
    }

    /**
     * The chat a new task reports to: the chat it was made in, or, when a running task made it
     * from its own work chat, the chat that task reports to. Anything else goes to the main chat.
     */
    private suspend fun reportChatId(context: Context, orbit: com.ai.assistance.operit.data.preferences.OrbitProfile, caller: String?): String {
        caller?.takeIf { it in orbit.talkChatIds }?.let { return it }
        val running = try {
            OrbitTaskExecution.resolve(context, caller)?.task
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return running?.chatId?.takeIf { it in orbit.talkChatIds } ?: orbit.chatId
    }

    /** Null when the model left it out; a value outside 1..MAX_TIMEOUT_MINUTES is rejected. */
    private fun parseTimeout(value: String?): Int? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val number = text.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }
            ?: error("timeout_minutes must be a whole number of minutes from 1 to ${OrbitTaskRepository.MAX_TIMEOUT_MINUTES}")
        require(number in 1.0..OrbitTaskRepository.MAX_TIMEOUT_MINUTES.toDouble()) {
            "timeout_minutes must be 1 to ${OrbitTaskRepository.MAX_TIMEOUT_MINUTES} (24 hours)"
        }
        return number.toInt()
    }

    /** Null when the model left it out; anything but true/false is rejected. */
    private fun parseFlag(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        null, "" -> null
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> error("use_subagent must be true or false")
    }

    private fun json(task: OrbitTaskEntity) = JSONObject().put("id", task.id).put("title", task.title)
        .put("kind", task.kind)
        .put("use_subagent", task.useSubagent)
        .put("schedule_mode", task.scheduleMode).put("goal_id", task.goalId).put("trigger_task_id", task.triggerTaskId)
        .put("state", task.state).put("url", task.source).put("instruction", task.instruction)
        .put("interval_minutes", task.intervalMinutes).put("notify_policy", task.notifyPolicy)
        .put("timeout_minutes", OrbitTaskRepository.timeoutMinutes(task))
        .apply {
            if (task.scheduleMode == "DAILY" || task.scheduleMode in OrbitCalendarSchedule.modes)
                put("daily_time", String.format(java.util.Locale.ROOT, "%02d:%02d", task.dailyMinute / 60, task.dailyMinute % 60))
            if (task.calendarRule.isNotEmpty()) put("repeat_on", task.calendarRule)
        }
        .put("nextRunAt", task.nextRunAt)
        .put("next_run", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(task.nextRunAt)))
        .put("lastObservation", task.lastObservation)
}
