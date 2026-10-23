package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitOptions

/** Orbit offers the user a few replies to tap, like Muse's reply options (see [OrbitOptions]). */
internal object OrbitOptionsTools {
    const val NAME = "orbit_create_options"

    val prompt = ToolPrompt(
        name = NAME,
        description = "Create tappable reply options and get an embed_token; write the token in your reply where the buttons should appear. " +
            "创建可点的回复选项，拿到 embed_token 后原样写进回复正文。",
        parametersStructured = listOf(
            ToolParameterSchema(
                "options", "string",
                "A JSON array of 2 to 4 strings, e.g. [\"Yes, book it\", \"Not now\"]. Each is exactly what the user sends when they tap it, in their voice (e.g. \"Yes, book it\"), not a number or a label.",
                true,
            ),
        ),
        details = """
  - Use it when the user is likely to answer with one of a few short replies (a choice, a yes/no, a next step).
  - The buttons only appear if you put the returned embed_token in your reply text, exactly as given. Saying you sent options without the token shows nothing.
  - Make a new set each time; a token works once and only in this chat. Usually one set per reply, placed after the words it answers.
  - A tap sends that option as the user's next message, the same as if they typed it. After one tap the set is used up.
  - 用户大概率会从几句短回复里选一句时用（选择、是否、下一步）。每个选项就是用户点了会原样发出的话。必须把返回的 embed_token 原样写进回复正文，按钮才会出现；令牌只能用一次、只在本聊天有效。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = try {
        val chatId = checkNotNull(ToolExecutionManager.currentToolRuntimeContext()?.callerChatId?.takeIf { it.isNotBlank() }) {
            "This only works in an Orbit chat"
        }
        val raw = tool.parameters.firstOrNull { it.name == "options" }?.value?.trim().orEmpty()
        val options = runCatching {
            org.json.JSONArray(raw).let { a -> (0 until a.length()).map { a.get(it).toString() } }
        }.getOrElse { raw.split('\n').filter { it.isNotBlank() } }
        val id = OrbitOptions.create(context, chatId, options)
        ToolResult(NAME, true, StringResultData(
            "embed_token: ${OrbitOptions.tokenOf(id)}\n" +
                "Put this token in your reply text exactly as written, where the options should appear. It works once, in this chat only."
        ))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
    }
}