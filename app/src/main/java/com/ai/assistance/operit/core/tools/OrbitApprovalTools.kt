package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.orbit.OrbitApprovals
import com.ai.assistance.operit.features.orbit.OrbitChatCards
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import org.json.JSONObject

/**
 * Orbit asks the user before something it is unsure about. The request goes into the approval
 * list and a card goes into the chat; the answer comes back later as a hidden note.
 */
internal object OrbitApprovalTools {
    const val NAME = "orbit_request_approval"

    val prompt = ToolPrompt(
        name = NAME,
        description = "Ask the user to approve one thing you are about to do and are not sure you should (send an email for them, " +
            "post or buy something, open a site you doubt, delete their data). 请用户批准一件你拿不准该不该做的事。",
        parametersStructured = listOf(
            ToolParameterSchema("title", description = "short title in the user's language, e.g. 给张三发邮件 (at most ${OrbitApprovals.MAX_TITLE} characters)", required = true),
            ToolParameterSchema("detail", description = "exactly what you will do: who/where, what content, any amount; one approval covers only this (at most ${OrbitApprovals.MAX_DETAIL} characters)", required = true),
        ),
        details = """
  - Puts an approval card in the chat and adds the request to the approval list, then returns at once. End the turn after it with one short line, e.g. "I've asked; tap approve and I'll send it."
  - Do not do the thing before the answer comes. You get a hidden note with the user's answer (and any reason they typed); then act on it.
  - One request is one concrete action. Put everything that matters into detail; if any of it changes later, ask again.
  - Ask only when it matters: things you can undo, or that only touch your own notes and files, need no approval.
  - 一次申请只对应一件具体的事，detail 里写清楚收件人/网站、内容、金额；之后有变化就重新申请。答复到之前不要动手。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            check(OrbitPreferences.getInstance(context).current().orbitForChat(caller) != null) {
                "Approval cards only work in a chat with the user; ask the user in chat"
            }
            val args = tool.parameters.associate { it.name to it.value }
            val approval = OrbitApprovals.request(context, orbit.id, caller.orEmpty(),
                args["title"].orEmpty(), args["detail"].orEmpty())
            val result = JSONObject()
                .put("approval_id", approval.id)
                .put("state", approval.state)
                .put("note", "An approval card is now in the chat and the request is in the approval list. End this turn now " +
                    "with one short line; do not do it yet. You get a hidden note with the user's answer.")
                .put(OrbitChatCards.KEY, OrbitChatCards.approval(orbit.id, approval))
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
