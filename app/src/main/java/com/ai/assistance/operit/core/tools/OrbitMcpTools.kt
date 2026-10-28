package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.orbit.OrbitApprovalActions
import com.ai.assistance.operit.features.orbit.OrbitApprovals
import com.ai.assistance.operit.features.orbit.OrbitChatCards
import com.ai.assistance.operit.features.orbit.OrbitMcp
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's MCP servers as Orbit sees them: which exist, which are on for it and running, starting
 * one, and asking the user on a card to switch them on or off.
 */
internal object OrbitMcpTools {
    const val NAME = "orbit_mcp"

    val prompt = ToolPrompt(
        name = NAME,
        description = "See the app's MCP servers, start one, or ask the user to switch them on/off for you. " +
            "查看主程序配好的 MCP 服务、启动、申请开关。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "list / start / request_toggle", required = true),
            ToolParameterSchema("name", description = "start: the server name from list", required = false),
            ToolParameterSchema("on", description = "request_toggle: server names to switch on, comma-separated", required = false),
            ToolParameterSchema("off", description = "request_toggle: server names to switch off, comma-separated", required = false),
            ToolParameterSchema("reason", description = "request_toggle: one or two sentences telling the user why", required = false),
        ),
        details = """
  - MCP servers are set up by the user in the app's MCP settings; you cannot add one. You have your own on/off list for them, apart from the main app's chats.
  - list: every server set up, whether it is on for you, and its state: ready, starting, not_started, failed, off_for_orbit, off_in_app. Each has a one-line note on what to do. ready means the app has it set up; use_package connects to it.
  - Servers that are on for you are listed with the packages. Load one with use_package as usual; if it is not started yet, it is started then (this can take up to a minute).
  - start: starts the server if needed and connects to it once to check it answers; also retries one that failed. Starting also starts the app's other MCP servers.
  - request_toggle: puts an approval card in the chat to switch servers on or off for you. End the turn with one short line; you get a hidden note with the result.
  - off_in_app means the user switched it off in the app's MCP settings; only they can switch it back on there. When one fails, tell the user the reason from the note in one short line.
  - 只能用主程序 MCP 设置里配好的服务。需要但关着就用 request_toggle 申请；连不上时把原因一句话告诉用户。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext { run(context, tool) }

    suspend fun run(context: Context, tool: AITool): ToolResult {
        return try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value?.trim().orEmpty() }
            fun items(name: String) = args[name].orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val configured = OrbitMcp.configured(context)
            val on = OrbitMcp.enabled(context, orbit.id)
            val result = when (args["action"].orEmpty()) {
                "list" -> {
                    val servers = JSONArray()
                    configured.values.forEach { s ->
                        val status = OrbitMcp.status(context, s.id, on)
                        servers.put(JSONObject().put("name", s.id)
                            .apply { if (s.name != s.id) put("title", s.name) }
                            .put("description", s.description.take(300))
                            .put("on", s.id in on)
                            .put("state", status.state.key)
                            .put("note", OrbitMcp.explain(s.id, status)))
                    }
                    JSONObject().put("servers", servers).apply {
                        if (configured.isEmpty()) put("note", "No MCP server is set up in the app. The user adds them in the app's MCP settings.")
                    }
                }
                "start" -> {
                    val name = args["name"].orEmpty()
                    require(name.isNotEmpty()) { "Give name, a server name from list" }
                    require(name in configured) { "No MCP server '$name'; see list" }
                    val status = OrbitMcp.ensureStarted(context, name, on, manual = true)
                    JSONObject().put("name", name).put("state", status.state.key).put("note", OrbitMcp.explain(name, status))
                }
                "request_toggle" -> {
                    check(OrbitPreferences.getInstance(context).current().orbitForChat(caller) != null) {
                        "Approval cards only work in a chat with the user; ask the user in chat"
                    }
                    val wantOn = items("on")
                    val wantOff = items("off")
                    require(wantOn.isNotEmpty() || wantOff.isNotEmpty()) { "Give on, off, or both" }
                    require(wantOn.size + wantOff.size <= OrbitApprovalActions.MAX_ITEMS) {
                        "At most ${OrbitApprovalActions.MAX_ITEMS} servers in one request"
                    }
                    require(wantOn.intersect(wantOff.toSet()).isEmpty()) { "A server cannot be in both on and off" }
                    val unknown = (wantOn + wantOff).filter { it !in configured }
                    require(unknown.isEmpty()) { "No such MCP server: ${unknown.joinToString()}; see list" }
                    val alreadyOn = wantOn.filter { it in on }
                    val alreadyOff = wantOff.filter { it !in on }
                    require(alreadyOn.isEmpty() && alreadyOff.isEmpty()) {
                        "Already on: ${alreadyOn.joinToString().ifEmpty { "(none)" }}; already off: " +
                            "${alreadyOff.joinToString().ifEmpty { "(none)" }}. Remove these and request again"
                    }
                    fun titled(name: String) = configured.getValue(name).let { if (it.name == name) name else "${it.name} ($name)" }
                    val detail = buildString {
                        if (wantOn.isNotEmpty()) {
                            append(context.getString(R.string.orbit_approval_mcp_on))
                            wantOn.forEach { append("\n• ").append(titled(it)) }
                        }
                        if (wantOff.isNotEmpty()) {
                            if (isNotEmpty()) append("\n\n")
                            append(context.getString(R.string.orbit_approval_mcp_off))
                            wantOff.forEach { append("\n• ").append(titled(it)) }
                        }
                        val reason = args["reason"].orEmpty().take(300)
                        if (reason.isNotBlank()) append("\n\n").append(context.getString(R.string.orbit_approval_reason, reason))
                    }
                    require(detail.length <= OrbitApprovals.MAX_DETAIL) { "Too long for one card; request fewer servers" }
                    val approval = OrbitApprovals.request(context, orbit.id, caller.orEmpty(),
                        context.getString(R.string.orbit_approval_mcp_title), detail,
                        OrbitApprovalActions.toggleMcpAction(wantOn, wantOff))
                    JSONObject().put("approval_id", approval.id).put("state", approval.state)
                        .put("note", "An approval card is now in the chat. End this turn now with one short line. Once the user approves, the app changes the switches and you get a hidden note with the result.")
                        .put(OrbitChatCards.KEY, OrbitChatCards.approval(orbit.id, approval))
                }
                else -> error("action must be list, start or request_toggle")
            }
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
