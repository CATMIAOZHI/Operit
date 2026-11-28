package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.JsPackageToolExecutorMarker
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.ui.permissions.ToolPermissionSystem
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A connector tool sends something out for the user (a message, a post, a call). In Orbit's chats
 * such a tool never runs on Orbit's word alone: the call becomes an approval card with allow once /
 * always allow / decline, and the app runs the very call the user saw once they allow it. Sending
 * any other way (the terminal, a script) is left to Orbit, as is asking first with
 * orbit_request_approval when it is unsure.
 */
internal object OrbitConnectorSend {
    /** The approval action that runs one tool call ([OrbitApprovalActions.run]). */
    const val RUN_TOOL = "run_tool"

    /** First line of the tool error that carries the card, so the chat can draw it. */
    const val PREFIX = "[orbit-connector-approval]"

    /** Built-in tools that send for the user; other packages are matched by the name of the tool. */
    private val BUILT_IN = setOf(
        "daily_life:send_message", "daily_life:wechat_send_message", "daily_life:qq_send_message",
        "daily_life:wechat_post_moments", "daily_life:make_phone_call",
    )
    /** Tools whose name says "send" but that only talk to the phone itself. */
    private val NOT_SENDING = setOf("system_tools:send_broadcast")
    private const val MAIL_SEND = com.ai.assistance.operit.core.tools.OrbitMailTools.SEND
    private const val CALENDAR_CHANGE = com.ai.assistance.operit.core.tools.OrbitCalendarTools.CHANGE
    /**
     * Orbit's own tools that act in the user's name (send a mail, change their calendar); Orbit's
     * other tools only touch its own things. Each call shows the exact message or change on its own
     * card and is never "always allowed".
     */
    private val ORBIT_SENDING = setOf(MAIL_SEND, CALENDAR_CHANGE)

    fun confirmsEach(toolName: String?): Boolean = toolName in ORBIT_SENDING

    /** A card for one of Orbit's own tools: its title, what it shows, and the exact call the app makes once allowed. */
    class Prepared(val title: String, val detail: String, val params: List<ToolParameter>)
    /** A word of the tool name that starts with a sending word: send_mail, sendmail, postMessage, reply_all. */
    private val sendingName = Regex("(^|_)(send|post|publish|reply|tweet|forward)")

    /** A package or MCP tool ("package:tool") that sends something out in the user's name. */
    fun isSendTool(toolName: String): Boolean {
        if (toolName in ORBIT_SENDING) return true
        if (':' !in toolName || toolName in NOT_SENDING) return false
        return toolName in BUILT_IN || sendingName.containsMatchIn(words(toolName.substringAfter(':')))
    }

    /** The tool name as lowercase words joined by "_": sendEmail, chat.postMessage, POSTMessage and send-mail all split. */
    private fun words(name: String): String =
        name.replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2").replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .replace(Regex("[^A-Za-z0-9]+"), "_").lowercase()

    private val hostContextParameters = setOf(
        "__operit_package_chat_id", "__operit_package_caller_name", "__operit_package_caller_card_id",
    )

    internal fun publicParameters(parameters: List<ToolParameter>): List<ToolParameter> {
        // Proxy calls already contain host context. Never persist it as part of an approval;
        // the approved call obtains its identity from the approval's chat, not parameter values.
        val public = parameters.filterNot { it.name in hostContextParameters }
        require(public.none { it.name.startsWith("__operit_") }) {
            "Not sent. Host-reserved __operit_ parameters cannot be approved. Call the named tool with its public parameters."
        }
        return public
    }

    /**
     * Puts the card for [tool] in Orbit's chat [chatId] and returns the error text Orbit reads
     * instead of a result. A chat that is not one with the user (a helper agent) gets no card.
     */
    suspend fun ask(context: Context, chatId: String?, tool: AITool): String {
        val parameters = try {
            publicParameters(tool.parameters)
        } catch (e: IllegalArgumentException) {
            return "$PREFIX\n${e.message}"
        }
        val english = LocaleUtils.usesEnglishPrompt(context)
        val prefs = OrbitPreferences.getInstance(context).current()
        val orbit = chatId?.let { prefs.orbitForChat(it) }
        if (chatId.isNullOrBlank() || orbit == null) {
            return "$PREFIX\nNot sent. ${tool.name} sends something out for the user and needs their OK on a card in " +
                "a chat with them, which this chat cannot show. Hand it back to the main agent with the exact text and " +
                "recipient, so it calls ${tool.name} there."
        }
        // Mail shows the message as it will go out (final recipients, subject, text, quote), the
        // calendar the exact change, and the card keeps exactly that; other tools show their call.
        val mail = tool.name == MAIL_SEND
        val verb = if (mail) "sent" else "changed"
        val prepared = if (confirmsEach(tool.name)) {
            try {
                if (mail) com.ai.assistance.operit.core.tools.OrbitMailTools.prepare(context, orbit, tool)
                else com.ai.assistance.operit.core.tools.OrbitCalendarTools.prepare(context, orbit, tool)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return "$PREFIX\nNot $verb: ${e.message ?: e.javaClass.simpleName}"
            }
        } else null
        val shown = prepared?.params ?: parameters
        val detail = prepared?.detail ?: buildString {
            append(context.getString(R.string.orbit_send_card_tool, tool.name))
            parameters.forEach { append('\n').append(context.getString(R.string.orbit_send_card_param, it.name, it.value)) }
        }
        if (detail.length > OrbitApprovals.MAX_DETAIL) {
            return if (mail) "$PREFIX\nNot sent. This email (text and quoted original) is longer than " +
                "${OrbitApprovals.MAX_DETAIL} characters, too long for its card. Keep it one email to the same people: " +
                "shorten the text, or ask the user what to cut."
            else if (prepared != null) "$PREFIX\nNot changed. This event is longer than ${OrbitApprovals.MAX_DETAIL} " +
                "characters, too long for its card: shorten the description."
            else "$PREFIX\nNot sent. This call is longer than ${OrbitApprovals.MAX_DETAIL} characters, too long for the " +
                "user to read on an approval card. Split it into shorter messages and send them one by one."
        }
        val title = prepared?.title ?: context.getString(R.string.orbit_send_card_title, tool.name)
        val action = JSONObject().put("type", RUN_TOOL).put("tool", tool.name).put("params", JSONArray().apply {
            shown.forEach { put(JSONObject().put("name", it.name).put("value", it.value)) }
        }).toString()
        val approval = try {
            OrbitApprovals.request(context, orbit.id, chatId, title.take(OrbitApprovals.MAX_TITLE), detail, action)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Too many open requests, or the list could not be written: nothing was sent and no card shows.
            return "$PREFIX\nNot sent, and no card could be put in the chat: ${e.message ?: e.javaClass.simpleName}. " +
                "Tell the user in one short line; do not send it another way."
        }
        val body = JSONObject()
            .put("sent", false)
            .put("approval_id", approval.id)
            .put("state", approval.state)
            .put("note", "Not $verb yet. ${tool.name} acts for the user, so it waits for their OK: a card in the chat shows " +
                "exactly this ${if (mail) "message" else if (prepared != null) "change" else "call"} to allow or decline. End this " +
                "turn with one short line; do not call it again or do it another way. If they allow it, the app does it itself " +
                "and a hidden note tells you the result.")
            .put(OrbitChatCards.KEY, OrbitChatCards.approval(orbit.id, approval))
        return "$PREFIX\n$body"
    }

    /** The card inside a tool error written by [ask], as JSON, or null. */
    fun cardIn(error: String): JSONObject? {
        val text = error.trim()
        if (!text.startsWith(PREFIX)) return null
        return runCatching { JSONObject(text.removePrefix(PREFIX).trim()).optJSONObject(OrbitChatCards.KEY) }.getOrNull()
    }

    fun toolOf(action: String): String? =
        runCatching { JSONObject(action).takeIf { it.optString("type") == RUN_TOOL }?.optString("tool") }
            .getOrNull()?.takeIf { it.isNotBlank() }

    /** The user chose "always allow": the tool now runs in Orbit's chats without a card. */
    suspend fun allowAlways(context: Context, action: String) {
        val name = toolOf(action) ?: return
        // Every mail and calendar change is confirmed on its own card.
        if (confirmsEach(name)) return
        approvedTool(JSONObject(action))
        ToolPermissionSystem.getInstance(context).saveOrbitToolPermission(name, PermissionLevel.ALLOW)
    }

    private fun approvedTool(action: JSONObject): AITool {
        val name = action.optString("tool")
        require(isSendTool(name)) { "Not a sending tool" }
        val params = action.optJSONArray("params") ?: JSONArray()
        return AITool(name, publicParameters((0 until params.length()).map { i ->
            params.getJSONObject(i).let { ToolParameter(it.optString("name"), it.optString("value")) }
        }))
    }

    /** Runs the approved call exactly as the card showed it, and says what came of it. */
    suspend fun run(context: Context, orbit: OrbitProfile, approval: OrbitApprovals.Approval, action: JSONObject,
        english: Boolean, always: Boolean): String {
        val tool = approvedTool(action)
        val name = tool.name
        val runtime = ToolExecutionManager.ToolRuntimeContext(
            callerName = orbit.name.ifBlank { null }, callerChatId = approval.chatId.ifBlank { orbit.chatId },
        )
        val result = withContext(Dispatchers.IO + ToolExecutionManager.toolRuntimeContextElement(runtime)) {
            // A mail or calendar change the user allowed is made as stored on its card, straight
            // from here: the tools the model calls only ever make cards.
            if (name == MAIL_SEND) {
                return@withContext com.ai.assistance.operit.core.tools.OrbitMailTools.sendApproved(context, orbit, tool.parameters)
            }
            if (name == CALENDAR_CHANGE) {
                return@withContext com.ai.assistance.operit.core.tools.OrbitCalendarTools.changeApproved(context, orbit, tool.parameters)
            }
            val handler = AIToolHandler.getInstance(context)
            val executor = requireNotNull(handler.getToolExecutorOrActivate(name)) { "Tool not found: $name" }
            val contextualTool = if (executor is JsPackageToolExecutorMarker) {
                tool.copy(parameters = tool.parameters + listOfNotNull(
                    runtime.callerChatId?.let { ToolParameter("__operit_package_chat_id", it) },
                    runtime.callerName?.let { ToolParameter("__operit_package_caller_name", it) },
                ))
            } else tool
            handler.executeToolWithExecutor(contextualTool, executor)
        }
        // Some tools only open the phone's own send screen; what they returned says whether it went out.
        val outcome = if (name == CALENDAR_CHANGE) {
            // A calendar change says plainly what happened.
            val done = runCatching { JSONObject(result.result.toString()) }.getOrNull()
            val error = (result.error ?: "")
            when {
                // Known reasons in the user's words; anything else as a plain failure.
                !result.success && error.startsWith(OrbitCalendarBox.CHANGED) -> context.getString(R.string.orbit_calendar_err_changed)
                !result.success && error.startsWith(OrbitCalendarBox.GONE) -> context.getString(R.string.orbit_calendar_already_gone)
                !result.success && (error.startsWith(OrbitCalendarBox.REPEATS) || error.startsWith(OrbitCalendarBox.OTHERS)) ->
                    context.getString(R.string.orbit_calendar_err_elsewhere)
                !result.success && "Connectors" in error -> context.getString(R.string.orbit_calendar_err_sign_in)
                !result.success || done == null -> context.getString(R.string.orbit_calendar_failed)
                done.optBoolean("added") -> context.getString(R.string.orbit_calendar_added, done.optString("title"))
                done.optBoolean("changed") -> context.getString(R.string.orbit_calendar_changed)
                done.has("note") -> context.getString(R.string.orbit_calendar_already_gone)
                else -> context.getString(R.string.orbit_calendar_deleted)
            }
        } else if (result.success) {
            context.getString(R.string.orbit_send_ran, name,
                result.result.toString().ifBlank { context.getString(R.string.orbit_send_nothing) })
        } else {
            context.getString(R.string.orbit_send_failed, name,
                (result.error ?: result.result.toString()).ifBlank { context.getString(R.string.orbit_send_no_reason) })
        }
        val note = if (always) "\n" + context.getString(R.string.orbit_send_always_note, name) else ""
        return outcome + note
    }
}
