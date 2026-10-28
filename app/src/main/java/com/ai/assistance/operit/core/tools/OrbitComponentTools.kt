package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.orbit.OrbitApprovals
import com.ai.assistance.operit.features.orbit.OrbitChatCards
import com.ai.assistance.operit.features.orbit.OrbitComponents
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import org.json.JSONArray
import org.json.JSONObject

/** Orbit sees which of the app's downloadable parts are installed, and asks to install some. */
internal object OrbitComponentTools {
    const val NAME = "orbit_components"

    val prompt = ToolPrompt(
        name = NAME,
        description = "See which of the app's downloadable parts are installed (terminal environment, offline speech, local voices, " +
            "app export templates), and ask the user to install some. 查看并申请安装 App 的组件（终端环境、离线语音等，需用户批准）。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "list / request_install", required = true),
            ToolParameterSchema("ids", description = "request_install: component ids from list, comma-separated", required = false),
            ToolParameterSchema("title", description = "request_install: optional short title in the user's language, e.g. 安装终端环境", required = false),
            ToolParameterSchema("reason", description = "request_install: one sentence telling the user why you need it", required = false),
        ),
        details = """
  - list shows each part with its id, what it is for, its download size and state (installed / not_installed / downloading).
  - request_install puts an approval card in the chat showing the parts and their size, and returns at once; end the turn with one short line. When the user approves, the app downloads them in the background, the user sees the progress, and you get a hidden note when they are done or failed.
  - Ask for the terminal environment when a task needs the terminal tool, or when your first-talk guidance asks for it, and list says it is not installed. Otherwise ask only for what the task needs; these are large downloads.
  - 终端环境这类组件很大，只在任务需要或初次见面指引要求时申请；用户同意后 App 在后台下载，完成后你会收到通知。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value }
            fun arg(name: String) = args[name]?.trim().orEmpty()
            val result: JSONObject = when (arg("action")) {
                "list" -> OrbitComponents.list(context)
                "request_install" -> {
                    check(OrbitPreferences.getInstance(context).current().orbitForChat(caller) != null) {
                        "Approval cards only work in a chat with the user; ask the user in chat"
                    }
                    val ids = arg("ids").split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    require(ids.isNotEmpty()) { "Give ids: component ids from list" }
                    val all = OrbitComponents.parts(context).associateBy { it.id }
                    val unknown = ids.filter { it !in all }
                    require(unknown.isEmpty()) { "Unknown ids: ${unknown.joinToString()}; use the ids list shows" }
                    val installed = ids.filter { all.getValue(it).installed }
                    require(installed.size < ids.size) { "Already installed: ${installed.joinToString()}; nothing to ask for" }
                    val parts = ids.map { all.getValue(it) }.filter { !it.installed }
                    val title = arg("title").ifEmpty { context.getString(com.ai.assistance.operit.R.string.orbit_component_title) }
                        .take(OrbitApprovals.MAX_TITLE)
                    val approval = OrbitApprovals.request(context, orbit.id, caller.orEmpty(), title,
                        OrbitComponents.detail(context, parts, arg("reason").take(300)), OrbitComponents.action(parts.map { it.id }))
                    JSONObject().put("approval_id", approval.id).put("state", approval.state)
                        .put("components", JSONArray(parts.map { it.name }))
                        .apply { if (installed.isNotEmpty()) put("already_installed", JSONArray(installed)) }
                        .put("note", "An approval card is now in the chat. End this turn now with one short line. Once the user " +
                            "approves, the app downloads them in the background and you get a hidden note when they are done.")
                        .put(OrbitChatCards.KEY, OrbitChatCards.approval(orbit.id, approval))
                }
                else -> error("action must be list or request_install")
            }
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
