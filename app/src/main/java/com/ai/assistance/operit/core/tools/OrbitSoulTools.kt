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
        description = "读取或更新自己的 SOUL.md 人格或 IDENTITY.md 身份。更新直接生效；Soul 保留历史供用户恢复；不改变工具权限。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "read/history/update", required = true),
            ToolParameterSchema("file", description = "soul（默认）或 identity", required = false),
            ToolParameterSchema("body", description = "update：完整文件内容，最多24000字符", required = false),
            ToolParameterSchema("reason", description = "soul update：解释为什么修改，最多2000字符", required = false),
            ToolParameterSchema("version", description = "soul update：read返回的当前version", required = false)))
    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
        val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller))
        val task = OrbitTaskExecution.resolve(context, caller)
        check(caller in orbit.talkChatIds || task?.binding?.rootChatId == caller) {
            "Only this Orbit's conversations or root background turn can change Soul"
        }
        val repository = OrbitSoulRepository(context, orbit.id)
        val args = tool.parameters.associate { it.name to it.value }
        if (args["file"]?.trim()?.lowercase()?.removeSuffix(".md") == "identity") {
            val identity: Any = when (args["action"]) {
                "read" -> JSONObject().put("body", OrbitPromptFiles.identity(context, orbit.id))
                "update" -> {
                    OrbitPromptFiles.saveIdentity(context, orbit.id, args["body"].orEmpty())
                    JSONObject().put("state", "applied")
                }
                else -> error("IDENTITY.md supports read and update")
            }
            return@runBlockingIoPreservingToolRuntimeContext ToolResult(tool.name, true, StringResultData(identity.toString()))
        }
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
