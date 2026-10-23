package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.OrbitDeliveryEntity
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Muse's proactivity.select: when background work hands Orbit draft items, Orbit registers a send
 * or skip call with a fixed reason for each before it writes anything. The reasons are kept, so
 * Orbit can later answer "why did you send me this?" and the calls can be counted.
 */
internal object OrbitProactivityTools {
    const val NAME = "orbit_proactivity_select"
    val SEND_REASONS = setOf("time_sensitive", "changed", "requested", "ready_for_decision", "new_opportunity")
    val SKIP_REASONS = setOf("moot", "already_handled", "unconfirmed", "preferences", "not_useful_now")
    private const val MAX_NOTE = 300

    val prompt = ToolPrompt(
        name = NAME,
        description = "后台备好的条目交给你时，先用它给每一条登记发（send）还是不发（skip）和理由，再开口；用户回应了你发过的提议，用 respond 记下来；history 查以前每条发没发、为什么、用户怎么回的。",
        parametersStructured = listOf(
            ToolParameterSchema("action", "string", "select（默认）、respond 或 history；task 为当前到点任务登记开口的理由", false),
            ToolParameterSchema("reason", "string", "task：这次为什么值得告诉用户，最多300字；安静结束直接用 orbit_nothing_to_do 的 reason", false),
            ToolParameterSchema("offset", "integer", "history 可选：从第几条开始（从0开始，最新的在前），用上次返回的 next_offset；每次20条", false),
            ToolParameterSchema("decisions", "string",
                "select：JSON 数组，每条 {\"item\":\"条目id\",\"decision\":\"send|skip\",\"reason\":\"理由\",\"format\":\"text|options（只有 send 填，默认 text）\",\"note\":\"查了什么，最多300字\"}", false),
            ToolParameterSchema("item", "string", "respond：用户回应的那条条目 id（history 里的 item）", false),
            ToolParameterSchema("response", "string", "respond：accepted（接受）、declined（不要）、already_done（自己已经办了）或 other", false),
            ToolParameterSchema("note", "string", "respond 可选：用户怎么说的、你接着做了什么，最多300字", false),
        ),
        details = """
  - send 的理由：time_sensitive（有截止且没到）、changed（用户在意的事变了）、requested（用户要过这类更新）、ready_for_decision（备好的东西等他拍板）、new_opportunity（没问过但确实相关）。
  - skip 的理由：moot（前提没了）、already_handled（办完了或说过了）、unconfirmed（核实不了）、preferences（偏好里不要）、not_useful_now（此刻不值得打扰）。
  - 一次把这批条目都登记完；没登记的条目算作不发。只说 send 的条目。format：有两个以上真正可选的回复时用 options（配 orbit_create_options），否则 text。
  - 用户回应了你发过的提议（点了选项、回了话、说已经自己办了），用 respond 记下。declined 只是这一次不要，不等于以后这类都别提；用户明确说以后别提，才写进 PROACTIVE_PREFERENCES.md。
  - 用户问“为什么给我发这个”“你怎么没告诉我”时，用 history 查。
  - history 里 selected_for_send=true 只说明当时决定要发，用户有没有真的看到要看你当时在聊天里说了没有。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitPreferences.getInstance(context).current().orbitForChat(caller)) {
                "This only works in a chat with the user"
            }
            val chatId = checkNotNull(caller)
            val args = tool.parameters.associate { it.name to it.value }
            val dao = AppDatabase.getDatabase(context).orbitTaskDao()
            val text = when (args["action"]?.trim()?.ifEmpty { null } ?: "select") {
                "task" -> {
                    val reason = args["reason"]?.trim().orEmpty()
                    require(reason.isNotBlank() && reason.length <= MAX_NOTE) { "task needs reason, 1 to $MAX_NOTE characters" }
                    check(com.ai.assistance.operit.features.orbit.OrbitChatDecision.record(chatId, reason)) {
                        "No scheduled task is being handled in this chat right now"
                    }
                    JSONObject().put("reason_recorded", true).put("delivered", false)
                        .put("next", "The reason will be saved in this task's run. Now tell the user in your own words.").toString()
                }
                "history" -> {
                    val offset = args["offset"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        it.toIntOrNull()?.takeIf { n -> n in 0..1000 } ?: error("offset must be a whole number from 0 to 1000; got \"$it\"")
                    } ?: 0
                    // One extra row tells whether another page exists.
                    val rows = dao.decidedItems(orbit.id, offset + 21).drop(offset)
                    val page = rows.take(20)
                    val next = (offset + 20).takeIf { rows.size > 20 && it <= 1000 }
                    JSONObject().put("offset", offset).put("returned", page.size)
                        .put("next_offset", next ?: JSONObject.NULL)
                        .apply { if (rows.size > 20 && next == null) put("note", "Older history exists but only the newest 1020 decisions can be read.") }
                        .put("items", JSONArray(page.map {
                            val draft = it.body.replace(Regex("\\s+"), " ")
                            JSONObject().put("item", it.id).put("title", it.title).put("key", it.opportunityKey)
                                .put("decision", it.verdict).put("selected_for_send", it.verdict == OrbitDeliveryEntity.SEND)
                                .put("reason", it.verdictReason)
                                .put("note", it.verdictNote).put("decided_at", it.decidedAt)
                                .apply {
                                    if (it.presentation.isNotEmpty()) put("format", it.presentation)
                                    if (it.userResponse.isNotEmpty()) put("user_response", it.userResponse)
                                        .put("user_response_note", it.userResponseNote).put("responded_at", it.respondedAt)
                                }
                                .put("draft", draft.take(200)).apply { if (draft.length > 200) put("draft_cut", true) }
                        })).toString()
                }
                "respond" -> {
                    val item = args["item"]?.trim().orEmpty()
                    val response = args["response"]?.trim()?.lowercase().orEmpty()
                    val note = args["note"]?.trim().orEmpty()
                    require(item.isNotEmpty()) { "respond needs item: the id of an item you told the user (see history)" }
                    require(response in OrbitDeliveryEntity.RESPONSES) {
                        "response must be one of ${OrbitDeliveryEntity.RESPONSES.joinToString()}"
                    }
                    require(note.length <= MAX_NOTE) { "note is longer than $MAX_NOTE characters" }
                    check(dao.respond(item, orbit.id, response, note, System.currentTimeMillis()) > 0) {
                        "No item \"$item\" was told to the user; only items registered as send can get a response"
                    }
                    JSONObject().put("item", item).put("user_response", response).put("recorded", true)
                        .put("next", when (response) {
                            "accepted" -> "Carry out what they accepted now, as prepared."
                            "declined" -> "Leave it: no explaining, no asking again. Only a new fact may bring it back."
                            "already_done" -> "Close the loop: note it on the goal or tracking item if there is one, and adjust related reminders."
                            else -> "Act on what they said."
                        }).toString()
                }
                "select" -> {
                    val raw = args["decisions"]?.trim().orEmpty()
                    require(raw.isNotEmpty()) { "select needs decisions: a JSON array with one {item, decision, reason, note} per item" }
                    val list = runCatching { JSONArray(raw) }.getOrElse {
                        error("decisions must be a JSON array like [{\"item\":\"id\",\"decision\":\"send\",\"reason\":\"time_sensitive\",\"note\":\"…\"}]")
                    }
                    require(list.length() in 1..20) { "Give one call per item, at most 20" }
                    // Checked in full first, so a mistake in one entry writes nothing.
                    val calls = (0 until list.length()).map { i ->
                        val o = list.optJSONObject(i) ?: error("Entry ${i + 1} is not an object")
                        val item = o.optString("item").trim()
                        val decision = o.optString("decision").trim().lowercase()
                        val reason = o.optString("reason").trim().lowercase()
                        val note = o.optString("note").trim()
                        val format = o.optString("format").trim().lowercase()
                        require(item.isNotEmpty()) { "Entry ${i + 1}: missing item" }
                        require(decision == OrbitDeliveryEntity.SEND || decision == OrbitDeliveryEntity.SKIP) {
                            "Entry ${i + 1}: decision must be send or skip"
                        }
                        val allowed = if (decision == OrbitDeliveryEntity.SEND) SEND_REASONS else SKIP_REASONS
                        require(reason in allowed) { "Entry ${i + 1}: reason for $decision must be one of ${allowed.joinToString()}" }
                        require(note.length <= MAX_NOTE) { "Entry ${i + 1}: note is longer than $MAX_NOTE characters" }
                        require(format.isEmpty() || format == OrbitDeliveryEntity.TEXT || format == OrbitDeliveryEntity.OPTIONS) {
                            "Entry ${i + 1}: format must be text or options"
                        }
                        require(format.isEmpty() || decision == OrbitDeliveryEntity.SEND) {
                            "Entry ${i + 1}: format is only for send"
                        }
                        val presentation = if (decision == OrbitDeliveryEntity.SEND) format.ifEmpty { OrbitDeliveryEntity.TEXT } else ""
                        listOf(item, decision, reason, note, presentation)
                    }
                    val repeated = calls.groupBy { it[0] }.filterValues { it.size > 1 }.keys
                    require(repeated.isEmpty()) { "Each item may appear only once; repeated: ${repeated.joinToString()}" }
                    val now = System.currentTimeMillis()
                    val missed = calls.filter { (item, decision, reason, note, presentation) ->
                        dao.decide(item, orbit.id, chatId, decision, reason, note, presentation, now) == 0
                    }.map { it[0] }
                    val saved = calls.filter { it[0] !in missed }
                    val toSend = saved.filter { it[1] == OrbitDeliveryEntity.SEND }.map { it[0] }
                    JSONObject()
                        .put("selected_for_send", JSONArray(toSend))
                        .put("skipped", JSONArray(saved.filter { it[1] == OrbitDeliveryEntity.SKIP }.map { it[0] }))
                        .put("not_saved", JSONArray(missed))
                        .apply { if (missed.isNotEmpty()) put("not_saved_reason", "Not an item handed to this chat, or already decided.") }
                        .put("delivered", false)
                        .put("next", if (toSend.isEmpty()) "Nothing to tell: call ${OrbitChatTools.NOTHING_TO_DO}."
                            else "Nothing has reached the user yet. Now tell only the selected_for_send items in your reply.")
                        .toString()
                }
                else -> error("Unknown action \"${args["action"]}\". Use select, respond, history or task.")
            }
            ToolResult(NAME, true, StringResultData(text))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
