package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.defaultTool.ToolGetter
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.SystemToolPromptCategory
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.ApiPreferences
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The Ubuntu terminal, built into the app. It was the super_admin package's terminal tools; their old
 * names still reach these ([canonical]). An Orbit always has it; the main app has a switch for it.
 */
internal object TerminalTools {
    const val RUN = "terminal"
    const val WAIT = "terminal_wait"
    const val SCREEN = "terminal_getscreen"
    const val INPUT = "terminal_input"
    const val CANCEL = "terminal_cancel"

    val names: Set<String> = linkedSetOf(RUN, WAIT, SCREEN, INPUT, CANCEL)

    /**
     * The app's lower-level terminal tools (sessions, tasks, input), listed among the internal tools a
     * CLI chat can search. The switch covers them too in the model's hands; packages that run Ubuntu
     * through the JS terminal API (code_runner and the like) call them directly and are not affected.
     */
    private val lowLevelNames: Set<String> = setOf(
        "create_terminal_session", "close_terminal_session", "input_in_terminal_session",
        "get_terminal_session_screen", "execute_in_terminal_session", "execute_in_terminal_session_streaming",
        "execute_hidden_terminal_command", "start_terminal_task", "poll_terminal_task", "cancel_terminal_task",
    )

    /** Every tool the terminal switch turns off in a main-app chat. */
    val switchedNames: Set<String> = names + lowLevelNames

    private const val LEGACY_PACKAGE = "super_admin"

    /** The name a call of the terminal had before it was built in ("super_admin:terminal"). */
    fun legacyName(name: String) = "$LEGACY_PACKAGE:$name"

    /** [name] as the built-in terminal tool it now is, or [name] itself for any other tool. */
    fun canonical(name: String): String =
        name.removePrefix("$LEGACY_PACKAGE:").takeIf { name.startsWith("$LEGACY_PACKAGE:") && it in names } ?: name

    // Session names stay what super_admin used, so a chat's terminal is the same one across the move.
    private const val DEFAULT_SESSION = "super_admin_default_session"
    private const val BACKGROUND_SESSION = "super_admin_background"

    /**
     * The terminal is offered to an Orbit's chats always, and elsewhere while its switch is on. The
     * switch is read first, so the chat's owner is only looked up when it is off.
     */
    suspend fun available(context: Context, chatId: String?): Boolean =
        ApiPreferences.getInstance(context).enableTerminalToolsFlow.first() ||
            runCatching { com.ai.assistance.operit.features.orbit.OrbitMemoryScope.owner(context, chatId) != null }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrDefault(false)

    /** [available] for one already known to be (or not be) an Orbit's chat. */
    suspend fun available(context: Context, isOrbitChat: Boolean): Boolean =
        isOrbitChat || ApiPreferences.getInstance(context).enableTerminalToolsFlow.first()

    private fun prompts(zh: Boolean): List<ToolPrompt> {
        fun t(cn: String, en: String) = if (zh) cn else en
        val sessionParam = ToolParameterSchema(
            "sessionId", "string",
            t("可选目标会话ID。不传则使用当前对话的默认会话。", "Optional target session ID. If omitted, uses the current chat's default session."),
            false,
        )
        return listOf(
            ToolPrompt(
                name = RUN,
                description = t(
                    "在持久 Ubuntu 会话执行命令，保留目录、变量和函数。command、background、timeoutMs、yieldMs 四个参数每次都必须传，缺一个就报错，例如 background=\"false\"、timeoutMs=\"1800000\"、yieldMs=\"10000\"。yieldMs内未结束返回running和runId，用terminal_wait查询或terminal_cancel取消。yieldMs仅限制本次等待；timeoutMs是执行总上限。output为日志尾部快照；命令未结束、结束但没有output、或全屏程序仍占着画面时，另附screen当前终端画面（可能包含先前输出），不要重复拼接。completed仅表示结束；exitCode为0才表示命令成功，非0为失败，null为未知。sessionExitCode仅表示会话进程退出码，不能当作命令码。大日志请重定向到文件。不要在复用会话直接exit，需要时用子Shell。",
                    "Run in a persistent Ubuntu shell, preserving cwd, variables and functions. Pass all four parameters on every call (command, background, timeoutMs, yieldMs); a missing one is an error. Typical values: background=\"false\", timeoutMs=\"1800000\", yieldMs=\"10000\". If it has not finished within yieldMs, running and runId mean it continues. Poll with terminal_wait or cancel with terminal_cancel. yieldMs bounds this wait; timeoutMs bounds execution. output is a log tail; while the command has not finished, finished without output, or a full-screen program still holds the display, screen adds the current terminal display, which may include earlier output. Do not concatenate snapshots. completed means ended; exitCode=0 means command success, nonzero means failure, null means unknown. sessionExitCode describes only the session process. Redirect large logs to a file. Use a subshell instead of exiting the persistent shell.",
                ),
                parametersStructured = listOf(
                    ToolParameterSchema("command", "string", t(
                        "要执行的 Ubuntu 命令；切换目录请在命令中使用 cd。本工具不接受 environment/path/timeout 参数，超时参数名为 timeoutMs。",
                        "Ubuntu command to execute; use cd inside the command to change directory. environment/path/timeout are unsupported; the timeout parameter is timeoutMs."), true),
                    ToolParameterSchema("background", "string", t(
                        "必填，\"true\" 或 \"false\"。true：独立后台会话，立即返回可查询的runId（不等 yieldMs）；false：在本聊天的持久会话里执行。",
                        "Required, \"true\" or \"false\". true: use a separate background session and immediately return a queryable runId (yieldMs is not waited); false: run in this chat's persistent session."), true),
                    ToolParameterSchema("timeoutMs", "string", t(
                        "必填，执行总上限（毫秒，至少3000），一般用1800000（30分钟）；与短等待yieldMs分开。",
                        "Required. Execution deadline in ms, minimum 3000; usually 1800000 (30 minutes). Independent of yieldMs."), true),
                    ToolParameterSchema("yieldMs", "string", t(
                        "必填，本次等待毫秒数，0–30000，一般用10000；background为true时不等待。",
                        "Required. Wait for this call in ms, 0–30000; usually 10000. Not waited when background is true."), true),
                ),
            ),
            ToolPrompt(
                name = WAIT,
                description = t(
                    "查询runId，返回日志尾部output；未结束、结束但没有output或全屏程序仍占着画面时另附动态画面screen快照；不要重复拼接。新消息到达时可能提前返回running，命令继续执行；稍后用同一runId查询，不要重跑。completed后检查exitCode，null不能视为成功。查询不会中断命令或排入检测命令。不传runId时查询指定sessionId或当前会话最近任务。ID失效时不要自动重跑命令。",
                    "Poll runId for the output log tail, plus a dynamic screen snapshot while it has not finished, finished without output, or a full-screen program still holds the display; do not concatenate repeats. New input may return running early while the command continues; poll the same runId later instead of rerunning. After completed, inspect exitCode; null is not success. Polling never interrupts or queues a probe command. Without runId, polls the latest task in the specified/current session. Do not rerun automatically if an ID expires.",
                ),
                parametersStructured = listOf(
                    ToolParameterSchema("runId", "string", t("terminal 返回的任务ID，建议传入。", "Task ID returned by terminal; recommended."), false),
                    ToolParameterSchema("sessionId", "string", t("兼容：不传runId时查询此会话最近任务。", "Compatibility: latest task in this session when runId is omitted."), false),
                    ToolParameterSchema("yieldMs", "string", t("本次等待0–30000毫秒，默认10000。", "Wait 0–30000 ms; default 10000."), false),
                    ToolParameterSchema("timeoutMs", "string", t("旧参数，作为等待时间使用，最多30000毫秒，不取消任务。", "Legacy wait alias, capped at 30000 ms; never cancels the task."), false),
                ),
            ),
            ToolPrompt(
                name = SCREEN,
                description = t(
                    "获取目标终端会话可见屏幕内容（仅一屏，不包含历史滚动缓冲）。可通过 sessionId 指定会话；不传则使用当前对话的默认会话。",
                    "Get the visible screen content for the target terminal session (single screen only, no scrollback history). Pass sessionId to target a session; if omitted, uses the current chat's default session.",
                ),
                parametersStructured = listOf(sessionParam),
            ),
            ToolPrompt(
                name = INPUT,
                description = t(
                    "向目标终端会话写入输入。可通过 sessionId 指定会话；不传则使用当前对话的默认会话。input 与 control 至少传一个。常见用法：先写 input，再写 control=enter 提交；control=ctrl 且 input=c 可发送 Ctrl+C。",
                    "Write input to the target terminal session. Pass sessionId to target a session; if omitted, uses the current chat's default session. Provide at least one of input or control. Typical usage: send input first, then control=enter to submit; use control=ctrl with input=c for Ctrl+C.",
                ),
                parametersStructured = listOf(
                    sessionParam,
                    ToolParameterSchema("input", "string", t("写入终端的文本", "Text to write to terminal."), false),
                    ToolParameterSchema("control", "string", t("控制键，例如 enter / tab / esc / ctrl", "Control key, e.g. enter / tab / esc / ctrl."), false),
                ),
            ),
            ToolPrompt(
                name = CANCEL,
                description = t(
                    "取消指定runId。排队任务只取消自己；执行中任务先中断，无法恢复时关闭故障会话。",
                    "Cancel a runId. Queued tasks only cancel themselves; running tasks are interrupted, and an unrecoverable session is closed.",
                ),
                parametersStructured = listOf(
                    ToolParameterSchema("runId", "string", t("terminal 返回的任务ID。", "Task ID from terminal."), true),
                ),
            ),
        )
    }

    val categoryEn = SystemToolPromptCategory(categoryName = "Ubuntu Terminal", tools = prompts(zh = false))
    val categoryCn = SystemToolPromptCategory(categoryName = "Ubuntu 终端", tools = prompts(zh = true))

    fun register(handler: AIToolHandler, context: Context) {
        fun s(id: Int, vararg args: Any) = context.getString(id, *args)
        fun p(tool: AITool, name: String) = tool.parameters.find { it.name == name }?.value.orEmpty()
        handler.registerTool(
            name = RUN,
            descriptionGenerator = { s(R.string.terminal_task_start_desc, p(it, "command")) },
            executor = { run(context, it) },
        )
        handler.registerTool(
            name = WAIT,
            descriptionGenerator = { s(R.string.terminal_task_poll_desc, p(it, "runId")) },
            executor = { wait(context, it) },
        )
        handler.registerTool(
            name = CANCEL,
            descriptionGenerator = { s(R.string.terminal_task_cancel_desc, p(it, "runId")) },
            executor = { cancel(context, it) },
        )
        handler.registerTool(
            name = SCREEN,
            descriptionGenerator = { s(R.string.toolreg_get_terminal_session_screen_desc, p(it, "sessionId")) },
            executor = { screen(context, it) },
        )
        handler.registerTool(
            name = INPUT,
            descriptionGenerator = {
                s(R.string.toolreg_input_in_terminal_session_desc, p(it, "sessionId"), p(it, "control").ifEmpty { "-" })
            },
            executor = { input(context, it) },
        )
    }

    // ---- running the tools: the same steps and results as the super_admin package had ----

    private fun chatSuffix(): String =
        ToolExecutionManager.currentToolRuntimeContext()?.callerChatId?.trim().orEmpty()
            .replace(Regex("[^a-zA-Z0-9._-]+"), "_")

    private fun defaultSessionName() = chatSuffix().let { if (it.isEmpty()) DEFAULT_SESSION else "${DEFAULT_SESSION}_$it" }

    private fun backgroundSessionName() =
        chatSuffix().let { if (it.isEmpty()) BACKGROUND_SESSION else "${BACKGROUND_SESSION}_$it" } + "_" + System.currentTimeMillis()

    private class Failure(message: String) : Exception(message)

    private fun param(tool: AITool, name: String): String? = tool.parameters.find { it.name == name }?.value

    private val jsDecimal = Regex("""[+-]?(?:\d++(?:\.\d*+)?|\.\d++)(?:[eE][+-]?\d++)?""")

    /**
     * [value] read as JavaScript's Number() and Number.isSafeInteger() read it, as the super_admin
     * package did: "1e4", "10000.0" and "0x2710" are 10000, a blank text is 0, anything else is null.
     */
    internal fun safeInteger(value: String): Long? {
        val text = value.trim(::isJsSpace)
        if (text.isEmpty()) return 0L
        val radix = when (text.take(2).lowercase()) { "0x" -> 16; "0o" -> 8; "0b" -> 2; else -> 10 }
        val number = if (radix != 10) {
            val digits = text.drop(2).lowercase()
            if (digits.isEmpty() || digits.any { it !in "0123456789abcdef".take(radix) }) return null
            digits.toBigInteger(radix).toDouble()
        } else {
            if (!jsDecimal.matches(text)) return null
            text.toDouble()
        }
        if (!number.isFinite() || number != Math.floor(number)) return null
        return number.takeIf { kotlin.math.abs(it) <= 9_007_199_254_740_991.0 }?.toLong()
    }

    /** JavaScript's white space and line terminators, which Number() ignores around a number. */
    private fun isJsSpace(c: Char): Boolean =
        c in "\t\n\u000B\u000C\r \u00A0\u1680\u2028\u2029\u202F\u205F\u3000\uFEFF" || c in '\u2000'..'\u200A'

    /** A parameter as the package's `params.x ? … : …` read it: given and not empty. */
    private fun given(tool: AITool, name: String): String? = param(tool, name)?.takeIf { it.isNotEmpty() }

    /** A wait in ms, 0..30000; [fallback] when not given. */
    private fun waitMs(value: String?, fallback: Long = 10_000L): Long {
        if (value == null) return fallback
        val parsed = safeInteger(value)
        if (parsed == null || parsed !in 0L..30_000L) throw Failure("yieldMs must be an integer between 0 and 30000")
        return parsed
    }

    private fun call(name: String, vararg params: Pair<String, String?>): AITool =
        AITool(name, params.mapNotNull { (key, value) -> value?.let { ToolParameter(key, it) } })

    private fun ToolResult.orFail(): ToolResult {
        if (!success) throw Failure(error?.takeIf { it.isNotBlank() } ?: result.toString())
        return this
    }

    /** The session [name] of this chat, made if missing, or with [existingOnly] only one this chat made. */
    private fun session(context: Context, name: String, existingOnly: Boolean = false): TerminalSessionCreationResultData {
        val result = ToolGetter.getTerminalCommandExecutor(context).createOrGetSession(
            call("create_terminal_session", "session_name" to name, "existing_only" to if (existingOnly) "true" else null)
        ).orFail()
        return result.result as? TerminalSessionCreationResultData ?: throw Failure(result.result.toString())
    }

    private fun task(context: Context, action: String, vararg params: Pair<String, String?>): TerminalTaskResultData {
        val result = ToolGetter.getTerminalCommandExecutor(context)
            .terminalTask(call("${action}_terminal_task", *params), action).orFail()
        return result.result as? TerminalTaskResultData ?: throw Failure(result.result.toString())
    }

    // The same fields the package passed on: the result's own JSON (with its "__type"), plus [extra].
    private fun taskJson(data: TerminalTaskResultData, extra: Map<String, JsonElement> = emptyMap()): JsonObject =
        JsonObject(Json.parseToJsonElement(data.toJson()).jsonObject + extra)

    private inline fun respond(context: Context, tool: AITool, block: () -> JsonElement): ToolResult = try {
        // Checked here too: packages and workflows call the tools without going through a chat's tool list.
        val chatId = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
        if (!kotlinx.coroutines.runBlocking { available(context, chatId) }) {
            throw Failure(context.getString(R.string.terminal_tools_switched_off))
        }
        ToolResult(tool.name, true, StringResultData(block().toString()))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolResult(tool.name, false, StringResultData(""), e.message ?: e.toString())
    }

    fun run(context: Context, tool: AITool): ToolResult = respond(context, tool) {
        // Every parameter is required: a call never runs on values the model did not choose.
        val missing = listOf("command", "background", "timeoutMs", "yieldMs").filter { param(tool, it)?.trim(::isJsSpace).isNullOrEmpty() }
        if (missing.isNotEmpty()) {
            throw Failure("terminal needs all of command, background, timeoutMs and yieldMs; missing: ${missing.joinToString(", ")}. " +
                "For example background=\"false\", timeoutMs=\"1800000\", yieldMs=\"10000\".")
        }
        val command = param(tool, "command")!!
        val background = when (param(tool, "background")!!.trim(::isJsSpace).lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw Failure("background must be \"true\" or \"false\"")
        }
        val timeoutMs = safeInteger(param(tool, "timeoutMs")!!)
        if (timeoutMs == null || timeoutMs < 3000) throw Failure("timeoutMs must be an integer of at least 3000")
        val requestedYield = waitMs(param(tool, "yieldMs"))
        val session = session(context, if (background) backgroundSessionName() else defaultSessionName())
        val result = task(context, "start", "session_id" to session.sessionId, "command" to command,
            "timeout_ms" to timeoutMs.toString(), "yield_ms" to (if (background) 0L else requestedYield).toString())
        taskJson(result, mapOf(
            "command" to JsonPrimitive(command),
            "background" to JsonPrimitive(background),
            "timeoutMsUsed" to JsonPrimitive(timeoutMs),
            "context_preserved" to JsonPrimitive(!session.isNewSession && result.status != "failed" && !result.timedOut),
        ))
    }

    fun wait(context: Context, tool: AITool): ToolResult = respond(context, tool) {
        if (tool.parameters.any { it.name == "command" }) throw Failure("terminal_wait does not execute commands; use terminal.")
        val legacyWait = param(tool, "timeoutMs")?.let {
            safeInteger(it)?.takeIf { v -> v >= 0 } ?: throw Failure("timeoutMs must be a non-negative integer")
        }
        val yieldMs = param(tool, "yieldMs")?.let { waitMs(it) } ?: legacyWait?.coerceAtMost(30_000L) ?: 10_000L
        // Ids are passed on as given (an empty one for the terminal to refuse), as the package did.
        val runId = param(tool, "runId")
        // Waiting never starts a terminal: without runId or sessionId only this chat's own one is read.
        val sessionId = param(tool, "sessionId")
            ?: if (runId.isNullOrEmpty()) session(context, defaultSessionName(), existingOnly = true).sessionId else null
        taskJson(task(context, "poll", "run_id" to runId, "session_id" to sessionId, "yield_ms" to yieldMs.toString()))
    }

    fun cancel(context: Context, tool: AITool): ToolResult = respond(context, tool) {
        val runId = given(tool, "runId") ?: throw Failure("runId is required")
        taskJson(task(context, "cancel", "run_id" to runId))
    }

    fun screen(context: Context, tool: AITool): ToolResult = respond(context, tool) {
        // Looking never starts a terminal: without sessionId only this chat's existing one is read.
        val sessionId = given(tool, "sessionId")
            ?: session(context, defaultSessionName(), existingOnly = true).sessionId
        val result = ToolGetter.getTerminalCommandExecutor(context)
            .getSessionScreen(call("get_terminal_session_screen", "session_id" to sessionId)).orFail()
        val data = result.result as? TerminalSessionScreenResultData ?: throw Failure(result.result.toString())
        buildJsonObject {
            put("sessionId", JsonPrimitive(data.sessionId))
            put("rows", JsonPrimitive(data.rows))
            put("cols", JsonPrimitive(data.cols))
            put("content", JsonPrimitive(data.content))
        }
    }

    fun input(context: Context, tool: AITool): ToolResult = respond(context, tool) {
        val input = param(tool, "input")
        val control = param(tool, "control")
        if (input == null && control == null) throw Failure("input和control至少需要提供一个")
        // A sessionId of spaces is someone else's mistake to report, never this chat's terminal.
        val sessionId = given(tool, "sessionId")
            ?: session(context, defaultSessionName()).sessionId
        val result = ToolGetter.getTerminalCommandExecutor(context).inputInSession(
            call("input_in_terminal_session", "session_id" to sessionId, "input" to input, "control" to control)
        ).orFail()
        buildJsonObject {
            put("sessionId", JsonPrimitive(sessionId))
            input?.let { put("input", JsonPrimitive(it)) }
            control?.let { put("control", JsonPrimitive(it)) }
            put("result", JsonPrimitive(result.result.toString()))
        }
    }
}
