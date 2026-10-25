package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.features.orbit.OrbitWeixin

/** Whether the user's WeChat is linked to Orbit, and how that link is doing. Read-only. */
internal object OrbitChannelTools {
    const val NAME = "orbit_channel"

    val prompt = ToolPrompt(
        name = NAME,
        description = "查看消息频道（目前是微信）有没有连上、在哪个侧聊、有没有发不出去的消息。只读。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "status", required = true),
        ),
        details = """
  - 微信连上后，用户在微信里发的话会进一个标题为「微信」的侧聊，以 `[微信]` 开头；你在这个侧聊里说的话会自动发回微信。is_current_chat=true 说明你现在就在这个侧聊里。
  - 连接和断开只能由用户在「设置 → 消息频道 → 微信」里扫码完成，你不能替用户连接。用户问起时告诉他去那里。
  - login_expired=true 说明微信登录失效了，请用户在同一个页面重新连接。waiting_to_send 大于0、last_error 以 send 开头，多半是用户很久没在微信里说话，微信暂时不让主动发；用户在微信里发一句话后会自动补发。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val action = tool.parameters.firstOrNull { it.name == "action" }?.value?.trim()
            require(action == "status") { "Unknown action \"${action.orEmpty()}\". Use status." }
            ToolResult(tool.name, true, StringResultData(OrbitWeixin.describe(context, orbit.id, caller).toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(tool.name, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
