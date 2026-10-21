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
        description = "后台备好的条目交给你时，先用它给每一条登记发（send）还是不发（skip）和理由，再开口；history 查以前每条发没发、为什么。",
        parametersStructured = listOf(
            ToolParameterSchema("action", "string", "select（默认）或 history", false),
            ToolParameterSchema("decisions", "string",
                "select：JSON 数组，每条 {\"item\":\"条目id\",\"decision\":\"send|skip\",\"reason\":\"理由\",\"note\":\"查了什么，最多300字\"}", false),
        ),
        details = """
  - send 的理由：time_sensitive（有截止且没到）、changed（用户在意的事变了）、requested（用户要过这类更新）、ready_for_decision（备好的东西等他拍板）、new_opportunity（没问过但确实相关）。
  - skip 的理由：moot（前提没了）、already_handled（办完了或说过了）、unconfirmed（核实不了）、preferences（偏好里不要）、not_useful_now（此刻不值得打扰）。
  - 一次把这批条目都登记完；没登记的条目算作不发。只说 send 的条目。
  - 用户问“为什么给我发这个”“你怎么没告诉我”时，用 history 查。
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
                "history" -> JSONArray(dao.decidedItems(orbit.id, 20).map {
                    JSONObject().put("item", it.id).put("title", it.title).put("key", it.opportunityKey)
                        .put("told", it.verdict == OrbitDeliveryEntity.SEND).put("reason", it.verdictReason)
                        .put("note", it.verdictNote).put("decided_at", it.decidedAt)
                        .put("draft", it.body.replace(Regex("\\s+"), " ").take(200))
                }).toString()
                "select" -> {
                    val raw = args["decisions"]?.trim().orEmpty()
                    require(raw.isNotEmpty()) { "Missing decisions" }
                    val list = JSONArray(raw)
                    require(list.length() in 1..20) { "Give one call per item, at most 20" }
                    // Checked in full first, so a mistake in one entry writes nothing.
                    val calls = (0 until list.length()).map { i ->
                        val o = list.getJSONObject(i)
                        val item = o.optString("item").trim()
                        val decision = o.optString("decision").trim().lowercase()
                        val reason = o.optString("reason").trim().lowercase()
                        val note = o.optString("note").trim()
                        require(item.isNotEmpty()) { "Entry ${i + 1}: missing item" }
                        require(decision == OrbitDeliveryEntity.SEND || decision == OrbitDeliveryEntity.SKIP) {
                            "Entry ${i + 1}: decision must be send or skip"
                        }
                        val allowed = if (decision == OrbitDeliveryEntity.SEND) SEND_REASONS else SKIP_REASONS
                        require(reason in allowed) { "Entry ${i + 1}: reason for $decision must be one of ${allowed.joinToString()}" }
                        require(note.length <= MAX_NOTE) { "Entry ${i + 1}: note is longer than $MAX_NOTE characters" }
                        listOf(item, decision, reason, note)
                    }
                    require(calls.map { it[0] }.toSet().size == calls.size) { "Each item once" }
                    val now = System.currentTimeMillis()
                    val missed = calls.filter { (item, decision, reason, note) ->
                        dao.decide(item, orbit.id, chatId, decision, reason, note, now) == 0
                    }.map { it[0] }
                    val sent = calls.count { it[1] == OrbitDeliveryEntity.SEND && it[0] !in missed }
                    buildString {
                        append("Saved: $sent to tell, ${calls.size - missed.size - sent} left out.")
                        if (missed.isNotEmpty()) append(" Not saved (not an item handed to this chat, or already decided): ${missed.joinToString()}.")
                        append(if (sent == 0) " Nothing to tell: call ${OrbitChatTools.NOTHING_TO_DO}." else " Now tell only the send items.")
                    }
                }
                else -> error("Unknown action")
            }
            ToolResult(NAME, true, StringResultData(text))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
