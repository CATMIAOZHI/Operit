package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.features.orbit.*
import org.json.JSONArray
import org.json.JSONObject

internal object OrbitSoulTools {
    const val NAME = "orbit_soul"
    val prompt = ToolPrompt(name = NAME,
        description = "读取自己的 Soul 人格与版本，或提出改进。提案不会直接生效，需要用户在 Orbit 的人格与历史页面确认；不改变工具权限。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "read/history/propose", required = true),
            ToolParameterSchema("body", description = "propose：完整 Soul 内容，最多24000字符", required = false),
            ToolParameterSchema("reason", description = "propose：解释为什么修改，最多2000字符", required = false),
            ToolParameterSchema("version", description = "propose：read返回的当前version", required = false)))
    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
        val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller))
        check(orbit.chatId == caller) { "Soul proposals belong in the main Orbit conversation" }
        val repository = OrbitSoulRepository(context, orbit.id)
        val args = tool.parameters.associate { it.name to it.value }
        val result: Any = when (args["action"]) {
            "read" -> repository.snapshot().current.let {
                JSONObject().put("version", it.id).put("body", it.body)
            }
            "history" -> JSONArray(repository.snapshot().history.map {
                JSONObject().put("version", it.id).put("state", it.state).put("reason", it.reason).put("createdAt", it.createdAt)
            })
            "propose" -> repository.propose(args["body"].orEmpty(), args["reason"].orEmpty(),
                args["version"].orEmpty()).let {
                JSONObject().put("proposal", it.id).put("state", "pending_user_review")
                    .put("next", "Ask the user to review it in Orbit settings > Soul and history")
            }
            else -> error("Unknown action")
        }
        ToolResult(tool.name, true, StringResultData(result.toString()))
    }
}
