package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitBrowserWatch

/**
 * Orbit, or a subagent it sent to browse, shows its browser page in the Orbit chat as a card. Browsing
 * itself puts nothing in the chat, so the chat stays quiet; the user can still find every page in
 * the browser tab of Orbit's profile.
 */
internal object OrbitBrowserShowTools {
    const val NAME = "orbit_browser_show"

    val prompt = ToolPrompt(
        name = NAME,
        description = "Show your open browser page in the chat as a live card the user can tap to watch or take over. " +
            "把你正在用的浏览器页面以卡片形式放进聊天，用户点开可以看或接手。",
        parametersStructured = emptyList(),
        details = """
  - Browsing puts no card in the chat by itself. Show the page when the user asked to see it, when the page itself is what they should look at (a result, a chart, a form you filled for them to check), or when a long browsing job starts and they may want to follow it.
  - Do not show it for a quick search or for reading a page you only summarize.
  - Until the user writes again, showing again keeps the same card, which follows the page anyway. After they write, showing puts a new card where the chat is now.
  - To ask the user to type a password or pass a human check, use orbit_browser_handoff instead.
  - 浏览本身不会在聊天里放卡片。用户要看、页面本身值得他看（结果、图表、你填好等他核对的表单），或者开始一个要跑很久的浏览任务时再放；只是快速搜一下、读完网页自己总结时不要放。用户再说话之前重复放只会沿用同一张卡。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val chatId = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val caller = checkNotNull(OrbitBrowserWatch.caller(chatId)) { "This only works for an Orbit's browser work" }
            val added = OrbitBrowserWatch.show(caller)
            ToolResult(NAME, true, StringResultData(
                if (added) "Your browser page is now shown in the chat as a card. Go on with your work; the card follows the page."
                else "Your browser page is already shown in the chat since the user last wrote; that card follows the page, so nothing new was added."
            ))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
