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
        description = "你和用户的聊天：主聊天和侧聊。list 列出所有聊天；create 新开一个侧聊；read 读另一个聊天最近的消息；message 读一条消息的全文。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "list/create/read/message", required = true),
            ToolParameterSchema("title", description = "create：侧聊的标题，最多40字符", required = false),
            ToolParameterSchema("chat_id", description = "read/message：聊天编号（来自 list）", required = false),
            ToolParameterSchema("limit", description = "read：读最近几条，1-40，默认20", required = false),
            ToolParameterSchema("before", description = "read：可选，上次返回的 next_before，用来往前翻更早的消息", required = false),
            ToolParameterSchema("message_id", description = "message：read 返回的 message_id", required = false),
            ToolParameterSchema("start_char", description = "message：可选，从第几个字开始读（从 1 开始），用上次返回的 next_start_char 接着读", required = false),
            ToolParameterSchema("include_thinking", description = "read/message：可选，true 时连你当时的思考内容一起返回；默认不返回，省上下文", required = false),
        ),
        details = """
  - 侧聊是和用户的另一段一对一聊天，有自己的标题和记录。用户想为某件事单独开一个聊天时用 create；用户自己也能在页面上开。
  - 主聊天和所有侧聊共用你的同一份记忆。找用户以前说过的话，用 search_chat_history，它会一起搜主聊天和全部侧聊。要读某个聊天最近的原话，先 list 找到它，再 read。
  - read 按时间从旧到新返回。hidden=true 的是用户看不到的系统提示（比如表情回应、到点的任务），不是用户说的话。单条超过2000字会截断并标 cut=true，用 message 带上 chat_id 和 message_id 读全文。has_more=true 时把 next_before 传给 before 再读更早的（这一页可能是空的，照样接着翻）。
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
            val orbit = checkNotNull(OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == owner.id }) {
                "This Orbit no longer exists"
            }
            val args = tool.parameters.associate { it.name to it.value }
            val history = ChatHistoryManager.getInstance(context)
            // Orbit's own thinking is left out unless asked for: it is long and rarely what is wanted.
            val thinking = args["include_thinking"]?.trim()?.lowercase() == "true"
            fun visible(m: com.ai.assistance.operit.data.model.ChatMessage) =
                (if (thinking) m.content else ChatUtils.removeThinkingContent(m.content)).trim()
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
                    require(title.isNotEmpty()) { "create needs title (at most 40 characters)" }
                    val id = OrbitCompanion.createSideChat(context, orbit.id, title)
                    val saved = OrbitPreferences.getInstance(context).current().orbits
                        .firstOrNull { it.id == orbit.id }?.sideChats?.firstOrNull { it.chatId == id }?.title ?: title
                    JSONObject().put("chat_id", id).put("title", saved)
                        .put("note", "The user opens it from the menu button at the top left of the Orbit page.")
                }
                "read" -> {
                    val id = args["chat_id"]?.trim().orEmpty()
                    require(id.isNotEmpty()) { "read needs chat_id; get it from list" }
                    require(id in orbit.talkChatIds) { "No chat $id in this Orbit; use list to see its chats" }
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 40) ?: 20
                    var before = args["before"]?.trim()?.toLongOrNull()
                    val picked = mutableListOf<com.ai.assistance.operit.data.model.ChatMessage>()
                    var exhausted = false
                    // Tool and summary rows are skipped, so page until enough chat rows are found.
                    repeat(5) {
                        if (picked.size >= limit || exhausted) return@repeat
                        val page = history.loadChatMessagesDesc(id, 80, before)
                        if (page.size < 80) exhausted = true
                        page.lastOrNull()?.let { before = it.timestamp }
                        page.filter { it.sender == "user" || it.sender == "ai" }.forEach { if (picked.size < limit) picked += it }
                    }
                    // Full page: continue before its oldest message. Otherwise the scan stopped either at the
                    // start of the chat or at its row budget, and then it continues where the scan stopped.
                    val cursor = if (picked.size >= limit) picked.last().timestamp else if (!exhausted) before else null
                    val hasMore = cursor != null && history.loadChatMessagesDesc(id, 1, cursor).isNotEmpty()
                    JSONObject().put("chat_id", id).put("returned", picked.size).put("has_more", hasMore)
                        .put("next_before", if (hasMore) cursor else JSONObject.NULL)
                        .put("messages", JSONArray(picked.reversed().map {
                            val text = visible(it)
                            JSONObject().put("message_id", it.timestamp)
                                .put("from", if (it.sender == "user") "user" else "you").put("time", it.timestamp)
                                .apply { if (it.displayMode != com.ai.assistance.operit.data.model.ChatMessageDisplayMode.NORMAL) put("hidden", true) }
                                .put("text", text.take(2000))
                                .apply { if (text.length > 2000) put("cut", true).put("chars", text.length) }
                        }))
                }
                "message" -> {
                    val id = args["chat_id"]?.trim().orEmpty()
                    require(id in orbit.talkChatIds) { "message needs the chat_id of one of this Orbit's chats; use list" }
                    val stamp = args["message_id"]?.trim()?.toLongOrNull() ?: error("message needs message_id from read")
                    val m = history.loadChatMessagesByTimestamps(id, listOf(stamp)).firstOrNull()
                        ?: error("No message $stamp in chat $id; read the chat again for current ids")
                    val text = visible(m)
                    val from = args["start_char"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: error("start_char must be a whole number from 1")
                    } ?: 1
                    require(from == 1 || from <= text.length) { "start_char $from is past the end; the message has ${text.length} characters" }
                    val end = minOf(text.length, from - 1 + 20_000)
                    JSONObject().put("chat_id", id).put("message_id", stamp)
                        .put("from", if (m.sender == "user") "user" else if (m.sender == "ai") "you" else m.sender)
                        .apply { if (m.displayMode != com.ai.assistance.operit.data.model.ChatMessageDisplayMode.NORMAL) put("hidden", true) }
                        .put("chars", text.length).put("start_char", from).put("end_char", end)
                        .put("next_start_char", if (end < text.length) end + 1 else JSONObject.NULL)
                        .put("text", text.substring(from - 1, end))
                }
                else -> error("Unknown action \"${args["action"]}\". Use list, create, read or message.")
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
            require(OrbitReactions.isEmoji(emoji)) { "emoji must be exactly one emoji, got \"${emoji.take(20)}\"" }
            val chatId = checkNotNull(caller)
            // Only what the user typed: hidden notices (tapbacks, due tasks) are not on screen.
            val latest = ChatHistoryManager.getInstance(context).loadChatMessagesDesc(chatId, 24)
                .firstOrNull { it.sender == "user" && it.displayMode == com.ai.assistance.operit.data.model.ChatMessageDisplayMode.NORMAL }
                ?: error("No user message to react to")
            OrbitReactions.of(context, orbit.id).set(chatId, latest.timestamp.toString(), emoji)
            ToolResult(REACT, true, StringResultData(JSONObject().put("reacted", emoji).put("message_id", latest.timestamp)
                .put("message_excerpt", latest.content.trim().take(80))
                .put("note", "A reaction replaces any earlier one of yours on that message. You may still reply in words.").toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(REACT, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
