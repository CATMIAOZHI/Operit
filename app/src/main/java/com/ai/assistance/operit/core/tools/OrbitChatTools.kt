package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.features.orbit.OrbitCompanion
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.features.orbit.OrbitReactions
import com.ai.assistance.operit.util.ChatUtils
import org.json.JSONArray
import org.json.JSONObject

/** Orbit's chats: the main chat and its side chats (Muse's chat.*), and emoji reactions. */
internal object OrbitChatTools {
    const val CHAT = "orbit_chat"
    const val REACT = "orbit_react"

    val chatPrompt = ToolPrompt(
        name = CHAT,
        description = "你和用户的聊天：主聊天和侧聊。list 列出所有聊天；create 新开一个侧聊；read 读另一个聊天最近的消息。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "list/create/read", required = true),
            ToolParameterSchema("title", description = "create：侧聊的标题，最多40字符", required = false),
            ToolParameterSchema("chat_id", description = "read：要读的聊天编号（来自 list）", required = false),
            ToolParameterSchema("limit", description = "read：读最近几条，1-40，默认20", required = false),
        ),
        details = """
  - 侧聊是和用户的另一段一对一聊天，有自己的标题和记录。用户想为某件事单独开一个聊天时用 create；用户自己也能在页面上开。
  - 每个聊天的记录是分开的。用户提到在另一个聊天里做过的事，先 list 找到那个聊天，再 read 读原话，不要凭印象回答。
  - 在哪个聊天里安排的提醒和定时任务，结果就回到哪个聊天。
""",
    )

    val reactPrompt = ToolPrompt(
        name = REACT,
        description = "给用户最新的一条消息点一个表情回应，就像在聊天软件里点表情。emoji 只能是一个表情。",
        parametersStructured = listOf(
            ToolParameterSchema("emoji", description = "一个表情，比如 😂 ❤️ 🎉 🫂", required = true),
        ),
        details = """
  - 朋友会点表情的时候就点：好笑、温暖、小成就、一起开心、重要的近况，或者接住他话里某个具体的东西。
  - 难过、脆弱的时候用 🫂 这类关心的表情，不要用庆祝或调皮的。
  - 有意义才点，不要每条都点。点了表情照样可以回复文字。
""",
    )

    fun executeChat(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val owner = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val orbit = checkNotNull(OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == owner.id })
            val args = tool.parameters.associate { it.name to it.value }
            val history = ChatHistoryManager.getInstance(context)
            val result: Any = when (args["action"]) {
                "list" -> JSONArray().apply {
                    put(JSONObject().put("chat_id", orbit.chatId).put("title", "main").put("current", caller == orbit.chatId))
                    orbit.sideChats.forEach {
                        put(JSONObject().put("chat_id", it.chatId).put("title", it.title).put("current", caller == it.chatId))
                    }
                }
                "create" -> {
                    val title = args["title"]?.trim().orEmpty()
                    require(title.isNotEmpty()) { "Missing title" }
                    val id = OrbitCompanion.createSideChat(context, orbit.id, title)
                    val saved = OrbitPreferences.getInstance(context).current().orbits
                        .firstOrNull { it.id == orbit.id }?.sideChats?.firstOrNull { it.chatId == id }?.title ?: title
                    JSONObject().put("chat_id", id).put("title", saved)
                        .put("note", "The user opens it from the chats button at the top right of the Orbit page.")
                }
                "read" -> {
                    val id = args["chat_id"]?.trim().orEmpty()
                    require(id in orbit.talkChatIds) { "No such chat: $id" }
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 40) ?: 20
                    JSONArray(history.loadChatMessagesDesc(id, limit).reversed()
                        .filter { it.sender == "user" || it.sender == "ai" }
                        .map {
                            JSONObject().put("from", if (it.sender == "user") "user" else "you").put("time", it.timestamp)
                                .put("text", ChatUtils.removeThinkingContent(it.content).trim().take(2000))
                        })
                }
                else -> error("Unknown action")
            }
            ToolResult(CHAT, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(CHAT, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    fun executeReact(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitPreferences.getInstance(context).current().orbitForChat(caller)) {
                "Reactions only work in a chat with the user"
            }
            val emoji = tool.parameters.firstOrNull { it.name == "emoji" }?.value?.trim().orEmpty()
            require(OrbitReactions.isEmoji(emoji)) { "emoji must be a single emoji" }
            val chatId = checkNotNull(caller)
            val latest = ChatHistoryManager.getInstance(context).loadChatMessagesDesc(chatId, 12)
                .firstOrNull { it.sender == "user" } ?: error("No user message to react to")
            OrbitReactions.of(context, orbit.id).set(chatId, latest.timestamp, emoji)
            ToolResult(REACT, true, StringResultData("Reacted $emoji"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(REACT, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
