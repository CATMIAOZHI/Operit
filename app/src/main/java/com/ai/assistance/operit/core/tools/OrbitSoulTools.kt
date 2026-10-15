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
        description = "读取或更新自己的 Soul 人格。更新直接生效，保留历史供用户恢复；不改变工具权限。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "read/history/update", required = true),
            ToolParameterSchema("body", description = "update：完整 Soul 内容，最多24000字符", required = false),
            ToolParameterSchema("reason", description = "update：解释为什么修改，最多2000字符", required = false),
            ToolParameterSchema("version", description = "update：read返回的当前version", required = false)))
    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
        val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller))
        val task = OrbitTaskExecution.resolve(context, caller)
        check(orbit.chatId == caller || task?.binding?.rootChatId == caller) {
            "Only this Orbit's main conversation or root background turn can change Soul"
        }
        val repository = OrbitSoulRepository(context, orbit.id)
        val args = tool.parameters.associate { it.name to it.value }
        val result: Any = when (args["action"]) {
            "read" -> repository.snapshot().current.let {
                JSONObject().put("version", it.id).put("body", it.body)
            }
            "history" -> JSONArray(repository.snapshot().history.map {
                JSONObject().put("version", it.id).put("state", it.state).put("reason", it.reason).put("createdAt", it.createdAt)
            })
            "update" -> repository.update(args["body"].orEmpty(), args["reason"].orEmpty(),
                args["version"].orEmpty()).let {
                JSONObject().put("version", it.id).put("state", "applied")
            }
            else -> error("Unknown action")
        }
        ToolResult(tool.name, true, StringResultData(result.toString()))
    }
}
