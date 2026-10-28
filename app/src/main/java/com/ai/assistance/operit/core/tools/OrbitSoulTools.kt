package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.features.orbit.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

internal object OrbitSoulTools {
    const val NAME = "orbit_soul"
    val prompt = ToolPrompt(name = NAME,
        description = "读取或更新自己的 SOUL.md 人格或 IDENTITY.md 身份；也能列出、读取用户的角色卡，用来参考性格，或放一张卡片带用户去导入角色卡。更新直接生效，只保留当前内容；不改变工具权限。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "read/update/list_role_cards/read_role_card/open_role_cards", required = true),
            ToolParameterSchema("file", description = "soul（默认）或 identity", required = false),
            ToolParameterSchema("body", description = "update：完整文件内容，最多24000字符", required = false),
            ToolParameterSchema("reason", description = "soul update 必填：解释为什么修改，最多2000字符；identity 不需要", required = false),
            ToolParameterSchema("version", description = "soul update 必填：先 read，再原样传入 read 返回的当前 version；identity 不需要", required = false),
            ToolParameterSchema("role_card_id", description = "read_role_card 必填：list_role_cards 返回的 id", required = false)),
        details = "修改 SOUL.md 前必须先 action=read；action=update 必须带完整 body、read 返回的 version 和修改理由 reason。IDENTITY.md 的 update 只需完整 body。\n" +
            "list_role_cards 列出用户的角色卡（id、名字、简介）；read_role_card 读一张卡的人设全文。读角色卡不会改变你自己，要参考它就再 update SOUL.md。\n" +
            "用户想用的角色卡还没导入（列表里没有），或想导入、管理角色卡时，用 open_role_cards 在聊天里放一张卡片，他点一下就到角色卡页面，导入后按返回回到聊天。卡片放出后用一句话结束这一轮；他回来说好了，再 list_role_cards。")
    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
        val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "orbit_soul works only inside an Orbit chat" }
        val task = OrbitTaskExecution.resolve(context, caller)
        check(caller in orbit.talkChatIds || task?.binding?.rootChatId == caller) {
            "Only this Orbit's conversations or root background turn can change Soul"
        }
        val repository = OrbitSoulRepository(context, orbit.id)
        val args = tool.parameters.associate { it.name to it.value }
        val action = args["action"]?.trim()?.lowercase().orEmpty()
        if (action == "open_role_cards") {
            check(com.ai.assistance.operit.data.preferences.OrbitPreferences.getInstance(context).current()
                .orbitForChat(caller) != null) { "Cards only work in a chat with the user" }
            val result = JSONObject().put("card", "placed")
                .put("note", "A card that opens the role card page is now in the chat. End this turn with one short line. " +
                    "When the user says they are done, call action=list_role_cards again.")
                .put(OrbitChatCards.KEY, OrbitChatCards.openPage(orbit.id, OrbitChatCards.PAGE_ROLE_CARDS))
            return@runBlockingIoPreservingToolRuntimeContext ToolResult(tool.name, true, StringResultData(result.toString()))
        }
        if (action == "list_role_cards" || action == "read_role_card") {
            val cards = com.ai.assistance.operit.data.preferences.CharacterCardManager.getInstance(context)
            val result = if (action == "list_role_cards") {
                val all = cards.getAllCharacterCards()
                JSONObject().put("count", all.size).put("role_cards", org.json.JSONArray().apply {
                    all.forEach { card ->
                        put(JSONObject().put("id", card.id).put("name", card.name)
                            .put("description", card.description.trim().take(200)))
                    }
                }).put("note", "Read one with action=read_role_card and its id.")
            } else {
                val id = args["role_card_id"]?.trim().orEmpty()
                require(id.isNotEmpty()) { "read_role_card needs role_card_id, an id from action=list_role_cards" }
                require(id in cards.characterCardListFlow.first()) {
                    "No role card with id $id; list them with action=list_role_cards"
                }
                val card = cards.getCharacterCard(id)
                val persona = cards.combinePrompts(id).trim()
                JSONObject().put("id", card.id).put("name", card.name)
                    .put("description", card.description.trim().take(MAX_ROLE_CARD_DESCRIPTION_CHARS))
                    .put("persona", persona.take(MAX_ROLE_CARD_CHARS))
                    .apply {
                        if (persona.length > MAX_ROLE_CARD_CHARS) put("note",
                            "persona shows the first $MAX_ROLE_CARD_CHARS of ${persona.length} characters.")
                    }
            }
            return@runBlockingIoPreservingToolRuntimeContext ToolResult(tool.name, true, StringResultData(result.toString()))
        }
        val file = args["file"]?.trim()?.lowercase()?.removeSuffix(".md")?.takeIf { it.isNotEmpty() } ?: "soul"
        require(file == "soul" || file == "identity") { "Unknown file \"${args["file"]}\". Use soul (SOUL.md) or identity (IDENTITY.md)." }
        if (file == "identity") {
            val identity: Any = when (action) {
                "read" -> OrbitPromptFiles.identity(context, orbit.id).let {
                    JSONObject().put("file", "IDENTITY.md").put("chars", it.length).put("body", it)
                }
                "update" -> {
                    val before = OrbitPromptFiles.identity(context, orbit.id)
                    val body = args["body"].orEmpty()
                    OrbitPromptFiles.saveIdentity(context, orbit.id, body)
                    JSONObject().put("file", "IDENTITY.md").put("state", "applied").put("changed", before != body)
                        .put("chars_before", before.length).put("chars", body.length)
                        .put("note", "The whole file was replaced with body; it takes effect in the next turn.")
                }
                else -> error("IDENTITY.md supports action read or update.")
            }
            return@runBlockingIoPreservingToolRuntimeContext ToolResult(tool.name, true, StringResultData(identity.toString()))
        }
        val result: Any = when (action) {
            "read" -> repository.snapshot().current.let {
                JSONObject().put("file", "SOUL.md").put("version", it.id).put("chars", it.body.length)
                    .put("max_chars", OrbitSoulRepository.MAX_CHARS).put("body", it.body)
            }
            "update" -> {
                val before = repository.snapshot().current
                repository.update(args["body"].orEmpty(), args["reason"].orEmpty(), args["version"].orEmpty().trim()).let {
                    JSONObject().put("file", "SOUL.md").put("state", "applied").put("version", it.id)
                        .put("previous_version", before.id).put("changed", before.body != it.body)
                        .put("chars_before", before.body.length).put("chars", it.body.length)
                        .put("note", "The whole file was replaced with body; it takes effect in the next turn. Only the current content is retained.")
                }
            }
            else -> error("Unknown action \"$action\". Use read, update, list_role_cards or read_role_card; SOUL.md history is no longer available.")
        }
        ToolResult(tool.name, true, StringResultData(result.toString()))
    }

    private const val MAX_ROLE_CARD_CHARS = 20_000
    private const val MAX_ROLE_CARD_DESCRIPTION_CHARS = 2_000
}
