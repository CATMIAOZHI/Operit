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
                "like); sms (read text messages); contacts (read contacts); calendar (read calendar events); " +
                "microphone (record audio); location (current location, approximate is enough); " +
                "geofence (arrival/departure reminders: precise and background location); device_access (Operit's " +
                "elevated access through ADB debugging/Shizuku or Root, which phone control, app installs " +
                "and system settings need; the device admin level only allows locking the screen and a few similar actions)", required = true),
        ),
        details = """
  - Checks what is on first. When it is already on, no card is placed and the result says so.
  - To see every permission at once without placing any card, call orbit_device action=describe and read its permissions; the app reads them itself, so no ADB, Shizuku or terminal command is needed.
  - device_access opens Operit's own access page over the chat (pick a level there and follow its steps). The result gives the picked level and whether it answers now.
  - Otherwise the card shows the setting with a button that opens the system prompt or settings screen, and a "Don't remind me" button; then it returns at once. End the turn with one short line.
  - After the user opens permission controls from the card and returns, the app checks again. Only an off-to-on change sends a hidden note to this original chat, so you can continue the work. A denial or unchanged setting sends nothing. "Don't remind me" also sends a hidden note.
  - location is only for the current position; coarse location is enough. geofence requires fine location, background location on Android 10+, and the phone's location service. Check coarse/fine/background/location_services separately in the result. Do not continue a geofence while can_continue=false.
  - For geofence, first ask for foreground precise location (coarse + fine together). Once granted, ask separately for background access: Android 10 has another system dialog; Android 11+ requires choosing "Allow all the time" on the app's system location-permission page. The card handles these steps; never request foreground and background together.
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
            // The device-access check is the last step of the first-talk setup check.
            if (permission == OrbitPermissionCards.DEVICE_ACCESS) runCatching { OrbitOnboarding.markEnvChecked(context, orbit.id) }
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
                        "short line. You will hear when both settings actually turn on through the card; do not ask again if they ignore it.")
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
        val location = if (permission == OrbitPermissionCards.LOCATION || permission == OrbitPermissionCards.GEOFENCE)
            OrbitPermissionCards.locationStatus(context) else null
        if (location != null) {
            for (key in listOf("coarse", "fine", "background", "background_required", "location_services"))
                result.put(key, location.get(key))
        }
        val access = if (permission == OrbitPermissionCards.DEVICE_ACCESS) OrbitPermissionCards.deviceAccess(context) else null
        if (access != null) {
            result.put("level", access.level?.name?.lowercase() ?: "not_set")
                .put("level_working", access.working)
        }
        val on = access?.working ?: OrbitPermissionCards.isOn(context, permission)
        val ready = on && (location == null || location.optBoolean("location_services"))
        result.put("can_continue", ready)
        if (on) {
            result.put("status", "on").put("card", "none")
                .put("note", if (ready) "It is on already, so no card was placed. Go ahead with what needed it."
                    else "The permission is granted, but the phone's location service is off. Ask the user to enable Location in system settings before continuing.")
        } else {
            result.put("status", "off").put("card", "placed")
                .put("note", "A card is now in the chat with a button to turn it on. End this turn with one short line " +
                    "saying why you need it. Do not continue the work that needs this permission yet. " +
                    "You will hear in this chat only after the card's off-to-on change is verified; " +
                    "do not offer the card again if they ignore or decline it.")
                .put(OrbitChatCards.KEY, OrbitChatCards.permission(orbitId, chatId, permission, UUID.randomUUID().toString()))
            if (permission == OrbitPermissionCards.GEOFENCE) {
                result.put("required", "fine + background (Android 10+) + location_services")
                    .put("next_permission_step", when {
                        location?.optBoolean("fine") != true ->
                            "Tap the card to grant foreground precise location first (coarse and fine together). Then return and tap again for background access."
                        location.optBoolean("background") != true && android.os.Build.VERSION.SDK_INT == 29 ->
                            "Tap the card again for the separate background-location dialog, and choose Allow all the time."
                        location.optBoolean("background") != true ->
                            "Tap the card to open this app's system settings, choose Permissions > Location > Allow all the time, then return."
                        else -> "Tap the card to enable the phone's Location service, then return."
                    })
            }
        }
        return ToolResult(NAME, true, StringResultData(result.toString()))
    }
}
