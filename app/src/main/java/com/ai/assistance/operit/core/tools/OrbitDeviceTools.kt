package com.ai.assistance.operit.core.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionPermissionRequestCoordinator
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Muse's device commands for the phone Orbit runs on: describe lists the commands and whether each
 * is allowed yet; invoke runs one. A command that is not allowed yet asks the user right then, the
 * way Muse asks on the device, instead of being granted ahead of time.
 */
internal object OrbitDeviceTools {
    const val NAME = "orbit_device"

    private class Command(val id: String, val description: String, val params: String, val permissions: List<String>)

    private val commands = listOf(
        Command("contacts.search", "Find people in the phone's contacts by name, number or email.",
            "query (required), limit 1-20 (default 10)", listOf(Manifest.permission.READ_CONTACTS)),
        Command("calendar.search", "Read calendar events, optionally only those whose title or place matches.",
            "days ahead 1-90 (default 7), past_days 0-30 (default 0), query (optional)", listOf(Manifest.permission.READ_CALENDAR)),
        Command("sms.search", "Search received and sent text messages by text or sender.",
            "query (optional), limit 1-30 (default 10)", listOf(Manifest.permission.READ_SMS)),
        Command("location.get", "The phone's current location.",
            "high_accuracy true/false (default false)",
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    )

    val prompt = ToolPrompt(
        name = NAME,
        description = "读取用户这台手机上的数据：通讯录、日历、短信、位置。先 describe 看有哪些命令、参数和是否已授权，再用 invoke 调用。" +
            "没授权的命令在调用时会弹框请用户批准。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "describe / invoke", required = true),
            ToolParameterSchema("command", description = "invoke：命令名，来自 describe，如 contacts.search", required = false),
            ToolParameterSchema("params", description = "invoke：JSON 对象，按 describe 里写的参数，如 {\"query\":\"妈妈\"}", required = false),
        ),
        details = """
  - 只在用户的请求需要时读，比如「给小王打电话前查下他的号码」「我下周有什么安排」「我现在在哪」。短信只在用户明确要求时读。
  - 结果是这一刻从手机上实时读的。用户拒绝授权时，告诉他可以在系统设置里给 Operit 打开对应权限，不要换别的办法绕过去。
  - 读到的号码、地址、消息只用在当前任务里，不要写进记忆，除非用户让你记。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val runtime = ToolExecutionManager.currentToolRuntimeContext()
            checkNotNull(OrbitMemoryScope.owner(context, runtime?.callerChatId)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value }
            val result = when (args["action"]) {
                "describe" -> describe(context)
                "invoke" -> {
                    val command = commands.firstOrNull { it.id == args["command"]?.trim() }
                        ?: error("Unknown command. Call describe for the list.")
                    val params = args["params"]?.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
                    ensureAllowed(context, command)
                    when (command.id) {
                        "contacts.search" -> contacts(context, params)
                        "calendar.search" -> calendar(context, params)
                        "sms.search" -> sms(context, params)
                        else -> location(context, params)
                    }
                }
                else -> error("action must be describe or invoke")
            }
            ToolResult(tool.name, true, StringResultData(result))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(tool.name, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    private fun granted(context: Context, command: Command) = command.permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun describe(context: Context): String = JSONObject()
        .put("device", "this phone")
        .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("android", Build.VERSION.RELEASE)
        .put("commands", JSONArray(commands.map {
            JSONObject().put("id", it.id).put("description", it.description).put("params", it.params)
                .put("allowed", granted(context, it))
        })).toString()

    /** Asks the user on the spot when the app is open; in the background the user cannot be asked. */
    private suspend fun ensureAllowed(context: Context, command: Command) {
        if (granted(context, command)) return
        val activity = ActivityLifecycleManager.getCurrentActivity()
        val visible = activity != null && !activity.isFinishing && activity.hasWindowFocus()
        check(visible) {
            "${command.id} is not allowed yet and the user is not in the app to approve it. " +
                "Tell the user to allow it next time they open Orbit, or in system settings."
        }
        val answers = WebSessionPermissionRequestCoordinator.requestPermissions(context, command.permissions)
        check(answers.values.any { it } || granted(context, command)) {
            "The user did not allow ${command.id}. It can be turned on for Operit in system settings."
        }
    }

    private fun time(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(millis))

    private fun contacts(context: Context, params: JSONObject): String {
        val query = params.optString("query").trim()
        require(query.isNotEmpty() && query.length <= 80) { "contacts.search needs a query" }
        val limit = params.optInt("limit", 10).coerceIn(1, 20)
        val people = linkedMapOf<Long, JSONObject>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1),
            "(${ContactsContract.Data.MIMETYPE} = ? OR ${ContactsContract.Data.MIMETYPE} = ?) AND " +
                "(${ContactsContract.Data.DISPLAY_NAME} LIKE ? OR ${ContactsContract.Data.DATA1} LIKE ?)",
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                "%$query%", "%$query%"),
            "${ContactsContract.Data.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                if (id !in people && people.size >= limit) continue
                val person = people.getOrPut(id) {
                    JSONObject().put("name", cursor.getString(1).orEmpty()).put("phones", JSONArray()).put("emails", JSONArray())
                }
                val value = cursor.getString(3).orEmpty()
                val key = if (cursor.getString(2) == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) "phones" else "emails"
                val list = person.getJSONArray(key)
                if ((0 until list.length()).none { list.getString(it) == value } && list.length() < 5) list.put(value)
            }
        }
        return JSONObject().put("source", "live").put("contacts", JSONArray(people.values.toList())).toString()
    }

    private fun calendar(context: Context, params: JSONObject): String {
        val days = params.optInt("days", 7).coerceIn(1, 90)
        val past = params.optInt("past_days", 0).coerceIn(0, 30)
        val query = params.optString("query").trim().lowercase()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath((now - past * 86_400_000L).toString()).appendPath((now + days * 86_400_000L).toString()).build()
        val events = JSONArray()
        context.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
            null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && events.length() < 100) {
                val title = cursor.getString(2).orEmpty()
                val place = cursor.getString(3).orEmpty()
                if (query.isNotEmpty() && query !in title.lowercase() && query !in place.lowercase()) continue
                events.put(JSONObject().put("title", title).put("place", place)
                    .put("start", time(cursor.getLong(0))).put("end", time(cursor.getLong(1)))
                    .put("all_day", cursor.getInt(4) == 1))
            }
        }
        return JSONObject().put("source", "live").put("from", time(now - past * 86_400_000L))
            .put("to", time(now + days * 86_400_000L)).put("events", events).toString()
    }

    private fun sms(context: Context, params: JSONObject): String {
        val query = params.optString("query").trim()
        require(query.length <= 80) { "query is at most 80 characters" }
        val limit = params.optInt("limit", 10).coerceIn(1, 30)
        val selection = if (query.isEmpty()) null else "${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.BODY} LIKE ?"
        val args = if (query.isEmpty()) null else arrayOf("%$query%", "%$query%")
        val messages = JSONArray()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            selection, args, "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && messages.length() < limit) {
                messages.put(JSONObject().put("from_or_to", cursor.getString(0).orEmpty())
                    .put("direction", if (cursor.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT) "sent" else "received")
                    .put("time", time(cursor.getLong(2))).put("text", cursor.getString(1).orEmpty().take(1000)))
            }
        }
        return JSONObject().put("source", "live").put("messages", messages).toString()
    }

    private fun location(context: Context, params: JSONObject): String {
        val result = AIToolHandler.getInstance(context).executeTool(AITool("get_device_location",
            listOf(ToolParameter("high_accuracy", params.optBoolean("high_accuracy", false).toString()))))
        check(result.success) { result.error ?: "Location is not available right now" }
        return result.result.toString()
    }
}
