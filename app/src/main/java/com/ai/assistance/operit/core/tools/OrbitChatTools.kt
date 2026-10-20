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
    const val NOTHING_TO_DO = com.ai.assistance.operit.features.orbit.OrbitReactionNotice.NOTHING_TO_DO

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
  - 主聊天和所有侧聊共用你的同一份记忆。找用户以前说过的话，用 search_chat_history，它会一起搜主聊天和全部侧聊。要读某个聊天最近的原话，先 list 找到它，再 read。
  - 在哪个聊天里安排的提醒和定时任务，结果就回到哪个聊天。
""",
    )

    val reactPrompt = ToolPrompt(
        name = REACT,
        description = "给用户最新的一条消息点一个表情回应，就像在聊天软件里点表情。任何表情都可以，但只能是一个表情。",
        parametersStructured = listOf(
            ToolParameterSchema("emoji", description = "任意一个表情，比如 😂 ❤️ 🎉 🫂 🐱 🍰 👏🏻 🇨🇳", required = true),
        ),
        details = """
  - 所有表情都能用，挑最贴合这句话的那个，不必局限于常见的几个。
  - 朋友会点表情的时候就点：好笑、温暖、小成就、一起开心、重要的近况，或者接住他话里某个具体的东西。
  - 难过、脆弱的时候用 🫂 这类关心的表情，不要用庆祝或调皮的。
  - 有意义才点，不要每条都点。点了表情照样可以回复文字。
""",
    )

    val nothingToDoPrompt = ToolPrompt(
        name = NOTHING_TO_DO,
        description = "这一轮什么都不做、什么都不说，安静结束。用户只是点表情表示满意、喜欢，不需要你回应时调用。",
        parametersStructured = emptyList(),
        details = """
  - 调用后这一轮立刻结束，用户在聊天里看不到任何东西。
  - 不要先写一段话再调用它；要安静就只调用它。
  - 用户在等你做事或回答时不要用它。
""",
    )

    /** Ends the turn without a word, so a pleased tapback gets no reply. */
    fun executeNothingToDo(context: Context, tool: AITool): ToolResult =
        ToolResult(NOTHING_TO_DO, true, StringResultData("Staying quiet."), interruptTurn = true)

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
                        put(JSONObject().put("chat_id", it.chatId).put("title", it.title).put("current", caller == it.chatId)
                            .put("archived", it.archived))
                    }
                }
                "create" -> {
                    val title = args["title"]?.trim().orEmpty()
                    require(title.isNotEmpty()) { "Missing title" }
                    val id = OrbitCompanion.createSideChat(context, orbit.id, title)
                    val saved = OrbitPreferences.getInstance(context).current().orbits
                        .firstOrNull { it.id == orbit.id }?.sideChats?.firstOrNull { it.chatId == id }?.title ?: title
                    JSONObject().put("chat_id", id).put("title", saved)
                        .put("note", "The user opens it from the menu button at the top left of the Orbit page.")
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
            // Only what the user typed: hidden notices (tapbacks, due tasks) are not on screen.
            val latest = ChatHistoryManager.getInstance(context).loadChatMessagesDesc(chatId, 24)
                .firstOrNull { it.sender == "user" && it.displayMode == com.ai.assistance.operit.data.model.ChatMessageDisplayMode.NORMAL }
                ?: error("No user message to react to")
            OrbitReactions.of(context, orbit.id).set(chatId, latest.timestamp, emoji)
            ToolResult(REACT, true, StringResultData("Reacted $emoji"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(REACT, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
