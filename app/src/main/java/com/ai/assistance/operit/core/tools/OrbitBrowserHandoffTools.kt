package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitBrowserWatch

/**
 * Orbit, or a subagent it sent to browse, asks the user to take over its browser page: a password
 * only the user should type, a human check. A card saying so goes in the Orbit chat and the tool
 * returns at once; when the user hands the page back, the agent is told and goes on.
 */
internal object OrbitBrowserHandoffTools {
    const val NAME = "orbit_browser_handoff"

    val prompt = ToolPrompt(
        name = NAME,
        description = "Ask the user to take over your open browser page (type a password, pass a human check, " +
            "anything they must do themselves). 请用户接手你正在用的浏览器页面（输入密码、人机验证等必须本人做的事）。",
        parametersStructured = listOf(
            ToolParameterSchema("reason", description = "What the user should do there, in a few words, in the user's " +
                "language, e.g. \"输入 GitHub 密码\" or \"完成人机验证\"", required = true),
        ),
        details = """
  - Use it on a page you opened with the browser tools, when only the user should or can go on there.
  - A card goes in the chat: "<your name> asks you to take over", with the reason and one button that opens the page in their control.
  - It returns at once. End your turn now with one short line saying what you need; do not call browser tools until they hand it back.
  - When they hand it back you are told; look at the page again (snapshot) before going on, since it changed.
  - Never ask the user to type a password into the chat; this or a vault card is the way.
  - 用在你用浏览器工具打开的页面上。卡片放出后立刻返回：用一句话结束这一轮，交还前不要再调用浏览器工具；交还后先重新看一下页面再继续。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult {
        return try {
            val chatId = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val caller = checkNotNull(OrbitBrowserWatch.caller(chatId)) { "This only works for an Orbit's browser work" }
            val reason = tool.parameters.firstOrNull { it.name == "reason" }?.value
                ?.replace(Regex("[\\r\\n]+"), " ")?.trim()?.take(80).orEmpty()
            require(reason.isNotEmpty()) { "Say in a few words what the user should do there (reason)" }
            OrbitBrowserWatch.askUser(caller, reason)
            ToolResult(NAME, true, StringResultData(
                "A card asking the user to take over is now in the chat (\"$reason\"). End this turn now with one short " +
                    "line telling them what to do. Do not call browser tools until they hand the page back; you will be " +
                    "told then. Look at the page again before going on."
            ))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
