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
        val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "orbit_soul works only inside an Orbit chat" }
        val task = OrbitTaskExecution.resolve(context, caller)
        check(caller in orbit.talkChatIds || task?.binding?.rootChatId == caller) {
            "Only this Orbit's conversations or root background turn can change Soul"
        }
        val repository = OrbitSoulRepository(context, orbit.id)
        val args = tool.parameters.associate { it.name to it.value }
        val file = args["file"]?.trim()?.lowercase()?.removeSuffix(".md")?.takeIf { it.isNotEmpty() } ?: "soul"
        require(file == "soul" || file == "identity") { "Unknown file \"${args["file"]}\". Use soul (SOUL.md) or identity (IDENTITY.md)." }
        val action = args["action"]?.trim()?.lowercase().orEmpty()
        if (file == "identity") {
            val identity: Any = when (action) {
                "read" -> OrbitPromptFiles.identity(context, orbit.id).let {
                    JSONObject().put("file", "IDENTITY.md").put("chars", it.length).put("body", it)
                }
                "update" -> {
                    val before = OrbitPromptFiles.identity(context, orbit.id)
                    val body = args["body"].orEmpty()
                    OrbitPromptFiles.saveIdentity(context, orbit.id, body)
                    JSONObject().put("file", "IDENTITY.md").put("state", "applied").put("changed", before != body)
                        .put("chars_before", before.length).put("chars", body.length)
                        .put("note", "The whole file was replaced with body; it takes effect in the next turn.")
                }
                else -> error("IDENTITY.md supports action read or update (history is only kept for SOUL.md)")
            }
            return@runBlockingIoPreservingToolRuntimeContext ToolResult(tool.name, true, StringResultData(identity.toString()))
        }
        val result: Any = when (action) {
            "read" -> repository.snapshot().current.let {
                JSONObject().put("file", "SOUL.md").put("version", it.id).put("chars", it.body.length)
                    .put("max_chars", OrbitSoulRepository.MAX_CHARS).put("body", it.body)
            }
            "history" -> repository.snapshot().let { snap ->
                JSONObject().put("file", "SOUL.md").put("current_version", snap.current.id)
                    .put("versions", JSONArray(snap.history.reversed().map {
                        JSONObject().put("version", it.id).put("state", it.state).put("source", it.source)
                            .put("reason", it.reason).put("created_at", it.createdAt).put("chars", it.body.length)
                    }))
            }
            "update" -> {
                val before = repository.snapshot().current
                repository.update(args["body"].orEmpty(), args["reason"].orEmpty(), args["version"].orEmpty().trim()).let {
                    JSONObject().put("file", "SOUL.md").put("state", "applied").put("version", it.id)
                        .put("previous_version", before.id).put("changed", before.body != it.body)
                        .put("chars_before", before.body.length).put("chars", it.body.length)
                        .put("note", "The whole file was replaced with body; the old version stays in history for the user to restore.")
                }
            }
            else -> error("Unknown action \"$action\". Use read, history or update.")
        }
        ToolResult(tool.name, true, StringResultData(result.toString()))
    }
}
