package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.features.orbit.OrbitWeixin

/** Whether the user's WeChat is linked to Orbit and how that link is doing; and a card that opens the link page. */
internal object OrbitChannelTools {
    const val NAME = "orbit_channel"

    val prompt = ToolPrompt(
        name = NAME,
        description = "查看消息频道（目前是微信）有没有连上、在哪个侧聊、有没有发不出去的消息；也能放一张卡片，带用户去连接微信。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "status / open_page", required = true),
        ),
        details = """
  - 微信连上后，用户在微信里发的话会进一个标题为「微信」的侧聊，以 `[微信]` 开头；你在这个侧聊里说的话会自动发回微信。is_current_chat=true 说明你现在就在这个侧聊里。
  - 连接和断开只能由用户扫码完成，你不能替用户连接。用户想连接、重新连接时，用 action=open_page 在聊天里放一张卡片，他点一下就到微信连接页（也可以自己去「设置 → 消息频道 → 微信」）。卡片放出后用一句话结束这一轮。
  - login_expired=true 说明微信登录失效了，用 open_page 放卡片请用户重新连接。waiting_to_send 大于0、last_error 以 send 开头，多半是用户很久没在微信里说话，微信暂时不让主动发；用户在微信里发一句话后会自动补发。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val action = tool.parameters.firstOrNull { it.name == "action" }?.value?.trim()
            when (action) {
                "status" -> ToolResult(tool.name, true, StringResultData(OrbitWeixin.describe(context, orbit.id, caller).toString()))
                "open_page" -> {
                    check(com.ai.assistance.operit.data.preferences.OrbitPreferences.getInstance(context).current()
                        .orbitForChat(caller) != null) { "Cards only work in a chat with the user" }
                    val result = org.json.JSONObject().put("card", "placed")
                        .put("note", "A card that opens the WeChat link page is now in the chat. End this turn with one short line. " +
                            "Once they scan the code, orbit_channel status shows connected=true.")
                        .put(com.ai.assistance.operit.features.orbit.OrbitChatCards.KEY,
                            com.ai.assistance.operit.features.orbit.OrbitChatCards.openPage(orbit.id,
                                com.ai.assistance.operit.features.orbit.OrbitChatCards.PAGE_WEIXIN))
                    ToolResult(tool.name, true, StringResultData(result.toString()))
                }
                else -> error("Unknown action \"${action.orEmpty()}\". Use status or open_page.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(tool.name, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
