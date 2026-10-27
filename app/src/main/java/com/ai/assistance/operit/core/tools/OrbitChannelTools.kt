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
        description = "查看消息频道（目前是微信）有没有连上、在哪个侧聊、有没有发不出去的消息；放一张卡片让用户直接扫码连接微信；申请断开微信（要用户批准）。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "status / request_connect / request_disconnect / open_page", required = true),
            ToolParameterSchema("reason", description = "request_disconnect：一句话告诉用户为什么", required = false),
        ),
        details = """
  - 微信连上后，用户在微信里发的话会进一个标题为「微信」的侧聊，以 `[微信]` 开头；你在这个侧聊里说的话会自动发回微信。is_current_chat=true 说明你现在就在这个侧聊里。
  - 用户想连接或重新连接时，用 request_connect 在聊天里放一张连接卡片，用户点它就能直接扫码，不用去设置页。连接要用户自己用微信扫码，你不能替他连。放完用一句话结束这一轮；连好后你会收到一条隐藏消息。
  - 用户想断开时，用 request_disconnect 放一张批准卡片，用户同意后 App 就断开。断开后「微信」侧聊和记录还在。
  - open_page 放一张打开微信连接页的卡片，用户想自己看连接状态时用。
  - login_expired=true 说明微信登录失效了，用 request_connect 放卡片请用户重新连接。waiting_to_send 大于0、last_error 以 send 开头，多半是用户很久没在微信里说话，微信暂时不让主动发；用户在微信里发一句话后会自动补发。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val action = tool.parameters.firstOrNull { it.name == "action" }?.value?.trim()
            suspend fun inChat() = check(com.ai.assistance.operit.data.preferences.OrbitPreferences.getInstance(context).current()
                .orbitForChat(caller) != null) { "Cards only work in a chat with the user" }
            when (action) {
                "status" -> ToolResult(tool.name, true, StringResultData(OrbitWeixin.describe(context, orbit.id, caller).toString()))
                "request_connect" -> {
                    inChat()
                    val state = OrbitWeixin.load(context, orbit.id)
                    val status = OrbitWeixin.describe(context, orbit.id, caller)
                    require(state == null || status.optBoolean("login_expired")) {
                        "WeChat is already linked and working; check orbit_channel status"
                    }
                    val result = org.json.JSONObject().put("card", "placed")
                        .put("note", "A card that links WeChat is now in the chat; the user taps it and scans the code with WeChat. " +
                            "End this turn with one short line. You get a hidden note once they are linked.")
                        .put(com.ai.assistance.operit.features.orbit.OrbitChatCards.KEY,
                            com.ai.assistance.operit.features.orbit.OrbitChatCards.weixinLogin(orbit.id, caller.orEmpty()))
                    ToolResult(tool.name, true, StringResultData(result.toString()))
                }
                "request_disconnect" -> {
                    inChat()
                    checkNotNull(OrbitWeixin.load(context, orbit.id)) { "WeChat is not linked" }
                    val reason = tool.parameters.firstOrNull { it.name == "reason" }?.value?.trim().orEmpty().take(300)
                    val detail = buildString {
                        append(context.getString(com.ai.assistance.operit.R.string.orbit_weixin_disconnect_note, orbit.name))
                        if (reason.isNotBlank()) append("\n\n").append(context.getString(com.ai.assistance.operit.R.string.orbit_approval_reason, reason))
                    }
                    val approval = com.ai.assistance.operit.features.orbit.OrbitApprovals.request(context, orbit.id, caller.orEmpty(),
                        context.getString(com.ai.assistance.operit.R.string.orbit_weixin_disconnect_title).trimEnd('？', '?'), detail,
                        org.json.JSONObject().put("type", com.ai.assistance.operit.features.orbit.OrbitApprovalActions.WEIXIN_DISCONNECT).toString())
                    val result = org.json.JSONObject().put("approval_id", approval.id).put("state", approval.state)
                        .put("note", "An approval card is now in the chat. End this turn with one short line. You get a hidden note with the result.")
                        .put(com.ai.assistance.operit.features.orbit.OrbitChatCards.KEY,
                            com.ai.assistance.operit.features.orbit.OrbitChatCards.approval(orbit.id, approval))
                    ToolResult(tool.name, true, StringResultData(result.toString()))
                }
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
                else -> error("Unknown action \"${action.orEmpty()}\". Use status, request_connect, request_disconnect or open_page.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(tool.name, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
