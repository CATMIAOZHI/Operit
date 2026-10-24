package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.orbit.OrbitChatCards
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.features.orbit.OrbitOnboarding
import com.ai.assistance.operit.features.orbit.OrbitPermissionCards
import com.ai.assistance.operit.features.orbit.OrbitTaskBlock
import org.json.JSONObject
import java.util.UUID

/** Orbit puts a card in the chat that lets the user turn on a phone setting it needs with one tap. */
internal object OrbitPermissionTools {
    const val NAME = "orbit_permission_card"

    val prompt = ToolPrompt(
        name = NAME,
        description = "Put a card in the chat that lets the user turn on a phone setting you need, with one tap. " +
            "在聊天里放一张权限卡片，用户点一下就能打开你需要的手机设置。",
        parametersStructured = listOf(
            ToolParameterSchema("permission", description = "One of: keep_alive (Operit's background keep-alive plus the " +
                "battery-optimization exemption, so background tasks start on time while Operit is closed); notify (send " +
                "phone notifications); notification_read (read other apps' notifications, for watching deliveries and the " +
                "like); sms (read text messages); contacts (read contacts); calendar (read calendar events); location " +
                "(the phone's current location)", required = true),
        ),
        details = """
  - Checks what is on first. When it is already on, no card is placed and the result says so.
  - Otherwise the card shows the setting with a button that opens the system prompt or settings screen, and a "Don't remind me" button; then it returns at once. End the turn with one short line.
  - The user turns things on from the card; you are not told when they do. If they tap "Don't remind me" you get a hidden note.
  - Offer it when something the user wants needs it: a tool said the permission is off, a watch needs SMS or notifications, background tasks ran late. Say in one line why you need it. One card per turn; do not offer it again after they ignored or declined it.
  - 先检查当前状态，开着就不放卡。用户要做的事需要某个权限（工具报没权限、盯短信/通知、后台任务被延后）时才放，并用一句话说明为什么要它。卡片放出后用一句话结束这一轮；用户没理或拒绝过，就别再放。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            check(OrbitPreferences.getInstance(context).current().orbitForChat(caller) != null) {
                "Permission cards only work in a chat with the user"
            }
            val permission = tool.parameters.firstOrNull { it.name == "permission" }?.value?.trim().orEmpty()
            require(permission in OrbitPermissionCards.kinds) {
                "Unknown permission \"$permission\". Supported now: ${OrbitPermissionCards.kinds.joinToString()}"
            }
            if (permission != OrbitPermissionCards.KEEP_ALIVE) return@runBlockingIoPreservingToolRuntimeContext phoneCard(context, orbit.id, caller.orEmpty(), permission)
            // Offered once: the first-talk hint about keep-alive is over either way.
            runCatching { OrbitOnboarding.markKeepAliveOffered(context, orbit.id) }
            val status = OrbitTaskBlock.status(context)
            val result = JSONObject()
                .put("permission", permission)
                .put("background_keep_alive", if (status.keepAlive) "on" else "off")
                .put("battery_exemption", if (status.battery) "on" else "off")
            if (status.allOn) {
                result.put("card", "none")
                    .put("note", "Background keep-alive and the battery exemption are both on already, so no card was placed. " +
                        "If background tasks are still held back, the phone's own manager is the likely cause: tell the user to " +
                        "allow Operit to auto-start and run in the background there.")
            } else {
                result.put("card", "placed")
                    .put("note", "A card is now in the chat with a button for each setting that is off. End this turn with one " +
                        "short line. You are not told when the user turns them on; do not ask again if they ignore it.")
                    .put(OrbitChatCards.KEY, OrbitChatCards.permission(orbit.id, caller.orEmpty(), permission,
                        UUID.randomUUID().toString()))
            }
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    /** A card for one phone permission, unless it is on already. */
    private fun phoneCard(context: Context, orbitId: String, chatId: String, permission: String): ToolResult {
        val result = JSONObject().put("permission", permission)
        if (OrbitPermissionCards.isOn(context, permission)) {
            result.put("status", "on").put("card", "none")
                .put("note", "It is on already, so no card was placed. Go ahead with what needed it.")
        } else {
            result.put("status", "off").put("card", "placed")
                .put("note", "A card is now in the chat with a button to turn it on. End this turn with one short line " +
                    "saying why you need it. You are not told when the user turns it on; carry on when they come back to it, " +
                    "and do not offer the card again if they ignore it.")
                .put(OrbitChatCards.KEY, OrbitChatCards.permission(orbitId, chatId, permission, UUID.randomUUID().toString()))
        }
        return ToolResult(NAME, true, StringResultData(result.toString()))
    }
}
