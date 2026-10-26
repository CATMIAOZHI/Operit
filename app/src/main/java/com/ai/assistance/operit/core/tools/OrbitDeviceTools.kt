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
import com.ai.assistance.operit.features.orbit.OrbitNotificationHistory
import com.ai.assistance.operit.features.orbit.OrbitPermissionCards
import com.ai.assistance.operit.features.orbit.OrbitTaskBlock
import com.ai.assistance.operit.services.notification.OperitNotificationStore
import androidx.core.app.NotificationManagerCompat
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

    /** [card] is the orbit_permission_card kind that turns the command on. */
    private class Command(val id: String, val description: String, val params: String, val permissions: List<String>,
                          val card: String = id.substringBefore('.'))

    private val commands = listOf(
        Command("contacts.search", "Find people in the phone's contacts by name, number or email.",
            "query (required), limit 1-20 (default 10)", listOf(Manifest.permission.READ_CONTACTS)),
        Command("calendar.search", "Read calendar events, optionally only those whose title or place matches.",
            "days ahead 1-90 (default 7), past_days 0-30 (default 0), query (optional)", listOf(Manifest.permission.READ_CALENDAR)),
        Command("sms.search", "Search received and sent text messages by text or sender.",
            "query (optional), limit 1-30 (default 10), before (optional: next_before from the last result, for older messages)",
            listOf(Manifest.permission.READ_SMS)),
        Command("sms.read", "Read one text message in full; use it when sms.search marked a text as cut. Up to 20000 characters per call.",
            "id (required, from sms.search), start_char (optional, from 0; use next_start_char from the last result to read on)",
            listOf(Manifest.permission.READ_SMS)),
        Command("location.get", "The phone's current location.",
            "high_accuracy true/false (default false)",
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
        Command("notifications.search", "Search the phone's notification history, newest first. It holds what other apps " +
            "posted while notification reading was on, for the last ${OrbitNotificationHistory.KEEP_DAYS} days, even after the user cleared them.",
            "hours back from now 1-${OrbitNotificationHistory.MAX_HOURS} (default 24; 168 = a week, 720 = a month), " +
                "app (optional: app name or package, see notifications.apps), query (optional: words in the title or text), " +
                "limit 1-50 (default 20), before (optional: next_before from the last result, for older ones)",
            emptyList(), OrbitPermissionCards.NOTIFICATION_READ),
        Command("notifications.apps", "Which apps posted notifications in a time window, with counts; use it to pick the app for notifications.search.",
            "hours back from now 1-${OrbitNotificationHistory.MAX_HOURS} (default 24)",
            emptyList(), OrbitPermissionCards.NOTIFICATION_READ),
        Command("notifications.read", "Read one notification from the history in full; use it when notifications.search marked one as cut.",
            "id (required, from notifications.search)",
            emptyList(), OrbitPermissionCards.NOTIFICATION_READ),
    )

    val prompt = ToolPrompt(
        name = NAME,
        description = "读取用户这台手机上的数据：通讯录、日历、短信、位置、通知历史。先 describe 看有哪些命令、参数、是否已授权，" +
            "以及手机上每项权限现在开没开，再用 invoke 调用。没授权的命令在调用时会弹框请用户批准。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "describe / invoke", required = true),
            ToolParameterSchema("command", description = "invoke：命令名，来自 describe，如 contacts.search", required = false),
            ToolParameterSchema("params", description = "invoke：JSON 对象，按 describe 里写的参数，如 {\"query\":\"妈妈\"}", required = false),
        ),
        details = """
  - 只在用户的请求需要时读，比如「给小王打电话前查下他的号码」「我下周有什么安排」「我现在在哪」。短信只在用户明确要求时读。
  - describe 的 permissions 列出每项权限现在开没开（名字和 orbit_permission_card 的一样），由 App 自己读，不需要 ADB、Shizuku 或终端；想知道自己有什么权限就看它，不要用终端命令去查。
  - 结果是这一刻从手机上实时读的。用户拒绝授权时不要换别的办法绕过去；他之后想开，用 orbit_permission_card 放一张对应的权限卡。
  - 时间都是手机本地时间（结果里有 timezone）。has_more=true 说明还有没返回的：通讯录和日历请缩小 query 或天数，短信把 next_before 原样放进 params.before 往前翻。短信标了 cut=true 时用 sms.read 加它的 id 读全文；一次最多20000字，next_start_char 不为空时放进 params.start_char 接着读。
  - 通知历史要开着「读取通知」（notification_read）才会记录：开启时通知栏里还在的和之后收到的，保留 ${OrbitNotificationHistory.KEEP_DAYS} 天；常驻通知（音乐、下载、正在运行）不记。用户问「刚才谁找我」「今天快递到了没」「最近一周银行发了什么」时用 notifications.search；按应用筛就填 app（应用名或包名，不确定先 notifications.apps 看有哪些应用）。翻页同短信（next_before 原样放进 params.before）；cut=true 时用 notifications.read 读全文，每条最多存 4000 字，stored_cut=true 说明原文更长、后面没存下来。没查到不代表没收到：应用可能没发通知，或只显示了部分内容。
  - 读到的号码、地址、消息只用在当前任务里，不要写进记忆，除非用户让你记。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val runtime = ToolExecutionManager.currentToolRuntimeContext()
            checkNotNull(OrbitMemoryScope.owner(context, runtime?.callerChatId)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value }
            val result = when (args["action"]?.trim()) {
                "describe" -> describe(context)
                "invoke" -> {
                    val command = commands.firstOrNull { it.id == args["command"]?.trim() }
                        ?: error("Unknown command \"${args["command"].orEmpty()}\". Use one of: ${commands.joinToString { it.id }}.")
                    val params = args["params"]?.takeIf { it.isNotBlank() }?.let {
                        runCatching { JSONObject(it) }.getOrElse { error("params must be a JSON object such as {\"query\":\"Mom\"}") }
                    } ?: JSONObject()
                    ensureAllowed(context, command)
                    when (command.id) {
                        "contacts.search" -> contacts(context, params)
                        "calendar.search" -> calendar(context, params)
                        "sms.search" -> sms(context, params)
                        "sms.read" -> smsRead(context, params)
                        "notifications.search" -> notificationSearch(context, params)
                        "notifications.apps" -> notificationApps(context, params)
                        "notifications.read" -> notificationRead(context, params)
                        else -> location(context, params)
                    }
                }
                else -> error("Unknown action \"${args["action"].orEmpty()}\". Use describe or invoke.")
            }
            ToolResult(tool.name, true, StringResultData(result))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(tool.name, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    private fun notificationReading(context: Context) =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    private fun granted(context: Context, command: Command) =
        if (command.card == OrbitPermissionCards.NOTIFICATION_READ) notificationReading(context)
        else command.permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    private suspend fun describe(context: Context): String = JSONObject()
        .put("device", "this phone")
        .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("android", Build.VERSION.RELEASE)
        .put("commands", JSONArray(commands.map {
            JSONObject().put("id", it.id).put("description", it.description).put("params", it.params)
                .put("allowed", granted(context, it))
        }))
        .put("permissions", permissions(context))
        .put("notification_history", recordingNote(context).let { (recording, note) ->
            JSONObject().put("recording", recording)
                .apply { if (note != null) put("recording_note", note) }
                .put("kept_days", OrbitNotificationHistory.KEEP_DAYS)
                .put("oldest", OrbitNotificationHistory.oldest(context)?.let { time(it) } ?: JSONObject.NULL)
        })
        .toString()

    /** Every setting orbit_permission_card can ask for, read by the app itself; no ADB or Shizuku needed. */
    private suspend fun permissions(context: Context): JSONObject {
        val out = JSONObject()
        val keep = OrbitTaskBlock.status(context)
        out.put(OrbitPermissionCards.KEEP_ALIVE, JSONObject().put("on", keep.allOn)
            .put("background_keep_alive", keep.keepAlive).put("battery_exemption", keep.battery))
        val access = runCatching { OrbitPermissionCards.deviceAccess(context) }.getOrNull()
        out.put(OrbitPermissionCards.DEVICE_ACCESS, JSONObject().put("on", access?.working ?: false)
            .put("level", access?.level?.name?.lowercase() ?: "not_set"))
        for (kind in OrbitPermissionCards.phoneKinds) {
            val entry = JSONObject().put("on", runCatching { OrbitPermissionCards.isOn(context, kind) }.getOrDefault(false))
            if (kind == OrbitPermissionCards.GEOFENCE) {
                val location = OrbitPermissionCards.locationStatus(context)
                for (key in listOf("fine", "background", "location_services")) entry.put(key, location.get(key))
            }
            out.put(kind, entry)
        }
        return out
    }

    /** Asks the user on the spot when the app is open; in the background the user cannot be asked. */
    private suspend fun ensureAllowed(context: Context, command: Command) {
        if (granted(context, command)) return
        val card = "orbit_permission_card (permission=${command.card})"
        check(command.card != OrbitPermissionCards.NOTIFICATION_READ) {
            "Notification reading is off, so no notifications are recorded and ${command.id} cannot run. " +
                "If the user wants it, put $card in the chat; history starts from when they turn it on."
        }
        val activity = ActivityLifecycleManager.getCurrentActivity()
        val visible = activity != null && !activity.isFinishing && activity.hasWindowFocus()
        check(visible) {
            "${command.id} is not allowed yet and the user is not in the app to approve it. " +
                "If it matters, put $card in the chat so they can allow it with one tap when they are back."
        }
        val answers = WebSessionPermissionRequestCoordinator.requestPermissions(context, command.permissions)
        check(answers.values.any { it } || granted(context, command)) {
            "The user did not allow ${command.id}. Do not ask again now; if they later want it, $card opens the setting."
        }
    }

    private fun time(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(millis))
    private fun zone() = java.util.TimeZone.getDefault().id

    private fun contacts(context: Context, params: JSONObject): String {
        val query = params.optString("query").trim()
        require(query.isNotEmpty() && query.length <= 80) { "contacts.search needs a query" }
        val limit = params.optInt("limit", 10).coerceIn(1, 20)
        val people = linkedMapOf<Long, JSONObject>()
        var more = false
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
                if (id !in people && people.size >= limit) { more = true; continue }
                val person = people.getOrPut(id) {
                    JSONObject().put("name", cursor.getString(1).orEmpty()).put("phones", JSONArray()).put("emails", JSONArray())
                }
                val value = cursor.getString(3).orEmpty()
                val key = if (cursor.getString(2) == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) "phones" else "emails"
                val list = person.getJSONArray(key)
                if ((0 until list.length()).none { list.getString(it) == value } && list.length() < 5) list.put(value)
            }
        }
        return JSONObject().put("source", "live").put("returned", people.size).put("has_more", more)
            .apply { if (more) put("note", "More people match; use a narrower query.") }
            .put("contacts", JSONArray(people.values.toList())).toString()
    }

    private fun calendar(context: Context, params: JSONObject): String {
        val days = params.optInt("days", 7).coerceIn(1, 90)
        val past = params.optInt("past_days", 0).coerceIn(0, 30)
        val query = params.optString("query").trim().lowercase()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath((now - past * 86_400_000L).toString()).appendPath((now + days * 86_400_000L).toString()).build()
        val events = JSONArray()
        var more = false
        context.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
            null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val title = cursor.getString(2).orEmpty()
                val place = cursor.getString(3).orEmpty()
                if (query.isNotEmpty() && query !in title.lowercase() && query !in place.lowercase()) continue
                if (events.length() >= 100) { more = true; break }
                events.put(JSONObject().put("title", title).put("place", place)
                    .put("start", time(cursor.getLong(0))).put("end", time(cursor.getLong(1)))
                    .put("all_day", cursor.getInt(4) == 1))
            }
        }
        return JSONObject().put("source", "live").put("timezone", zone()).put("from", time(now - past * 86_400_000L))
            .put("to", time(now + days * 86_400_000L)).put("returned", events.length()).put("has_more", more)
            .apply { if (more) put("note", "Only the first 100 events are shown; use fewer days or a query.") }
            .put("events", events).toString()
    }

    private fun sms(context: Context, params: JSONObject): String {
        val query = params.optString("query").trim()
        require(query.length <= 80) { "query is at most 80 characters" }
        val limit = params.optInt("limit", 10).coerceIn(1, 30)
        // The cursor is "date:id", so texts sharing a timestamp across a page edge are not skipped.
        val before = params.optString("before").trim().takeIf { it.isNotEmpty() }?.let { raw ->
            val parts = raw.split(':')
            val date = parts[0].toLongOrNull()
            val id = parts.getOrNull(1)?.toLongOrNull() ?: Long.MAX_VALUE
            require(date != null && parts.size <= 2) { "before must be the next_before value from the last sms.search" }
            date to id
        }
        val clauses = mutableListOf<String>()
        val values = mutableListOf<String>()
        if (query.isNotEmpty()) { clauses += "(${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.BODY} LIKE ?)"; values += "%$query%"; values += "%$query%" }
        if (before != null) {
            clauses += "(${Telephony.Sms.DATE} < ? OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?))"
            values += before.first.toString(); values += before.first.toString(); values += before.second.toString()
        }
        val selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val args = values.takeIf { it.isNotEmpty() }?.toTypedArray()
        val messages = JSONArray()
        var more = false
        var oldest = ""
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms._ID),
            selection, args, "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (messages.length() >= limit) { more = true; break }
                oldest = "${cursor.getLong(2)}:${cursor.getLong(4)}"
                val body = cursor.getString(1).orEmpty()
                messages.put(JSONObject().put("id", cursor.getLong(4)).put("from_or_to", cursor.getString(0).orEmpty())
                    .put("direction", if (cursor.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT) "sent" else "received")
                    .put("time", time(cursor.getLong(2))).put("text", body.take(1000))
                    .apply { if (body.length > 1000) put("cut", true).put("chars", body.length) })
            }
        }
        return JSONObject().put("source", "live").put("timezone", zone()).put("returned", messages.length())
            .put("has_more", more).put("next_before", if (more) oldest else JSONObject.NULL)
            .put("messages", messages).toString()
    }

    private fun smsRead(context: Context, params: JSONObject): String {
        val id = params.optString("id").trim().toLongOrNull() ?: error("sms.read needs id from sms.search")
        val raw = params.opt("start_char")?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val from = raw?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: error("start_char must be a whole number from 0; got \"$it\"") } ?: 0
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            "${Telephony.Sms._ID} = ?", arrayOf(id.toString()), null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val body = cursor.getString(1).orEmpty()
                require(from == 0 || from < body.length) { "start_char $from is past the end; this text has ${body.length} characters" }
                val end = minOf(body.length, from + 20_000)
                return JSONObject().put("source", "live").put("timezone", zone()).put("id", id)
                    .put("from_or_to", cursor.getString(0).orEmpty())
                    .put("direction", if (cursor.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT) "sent" else "received")
                    .put("time", time(cursor.getLong(2))).put("chars", body.length)
                    .put("start_char", from).put("text", body.substring(from, end))
                    .put("next_start_char", if (end < body.length) end else JSONObject.NULL)
                    .toString()
            }
        }
        error("No text message with id $id; search again")
    }

    private fun hours(params: JSONObject): Int {
        val raw = params.opt("hours")?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return 24
        val hours = raw.toDoubleOrNull()?.toInt() ?: error("hours must be a whole number from 1 to ${OrbitNotificationHistory.MAX_HOURS}; got \"$raw\"")
        return hours.coerceIn(1, OrbitNotificationHistory.MAX_HOURS)
    }

    /** Whether new notifications are being kept right now, and a line for the AI when they are not. */
    private fun recordingNote(context: Context): Pair<Boolean, String?> = when {
        !notificationReading(context) -> false to "Notification reading is off, so nothing new is kept. orbit_permission_card " +
            "(permission=notification_read) turns it on if the user wants it."
        !OperitNotificationStore.connected -> false to "Notification reading is on, but the phone has not connected the listener " +
            "yet (for example right after a restart), so new notifications are not kept at the moment. It usually reconnects by itself."
        else -> true to null
    }

    private const val PAGE_TEXT_BUDGET = 20_000

    private fun notificationSearch(context: Context, params: JSONObject): String {
        val app = params.optString("app").trim()
        val query = params.optString("query").trim()
        require(app.length <= 120) { "app is at most 120 characters" }
        require(query.length <= 80) { "query is at most 80 characters" }
        val limit = params.optInt("limit", 20).coerceIn(1, 50)
        // The cursor is "since:time:id": the window's start stays where the first page put it.
        val cursor = params.optString("before").trim().takeIf { it.isNotEmpty() }?.let { raw ->
            val parts = raw.split(':').map { it.toLongOrNull() }
            require(parts.size == 3 && parts.all { it != null }) { "before must be the next_before value from the last notifications.search" }
            Triple(parts[0]!!, parts[1]!!, parts[2]!!)
        }
        val since = cursor?.first ?: (System.currentTimeMillis() - hours(params) * 3_600_000L)
        val page = OrbitNotificationHistory.search(context, since, app, query, limit, cursor?.let { it.second to it.third })
        val list = JSONArray()
        var used = 0
        var more = page.more
        var last: OrbitNotificationHistory.Item? = null
        for (item in page.items) {
            val text = item.body.take(1000)
            // Keeps the page well under the tool result limit; the cursor follows the last item shown.
            if (last != null && used + item.title.length + text.length > PAGE_TEXT_BUDGET) { more = true; break }
            used += item.title.length + text.length
            last = item
            list.put(JSONObject().put("id", item.id).put("time", time(item.time)).put("app", item.app).put("package", item.pkg)
                .put("title", item.title).put("text", text)
                .apply { if (item.body.length > 1000 || item.storedCut) put("cut", true).put("chars", item.chars) })
        }
        val (recording, note) = recordingNote(context)
        return JSONObject().put("source", "history").put("timezone", zone()).put("from", time(since))
            .put("recording", recording)
            .apply { if (note != null) put("recording_note", note) }
            .put("returned", list.length()).put("has_more", more)
            .put("next_before", if (more && last != null) "$since:${last.time}:${last.id}" else JSONObject.NULL)
            .apply {
                if (list.length() == 0) put("note", "Nothing matches. Kept are the notifications still in the tray when notification " +
                    "reading was turned on and those that arrived after, for ${OrbitNotificationHistory.KEEP_DAYS} days (oldest kept: " +
                    "${OrbitNotificationHistory.oldest(context)?.let { time(it) } ?: "none yet"}), and an app may not post one at all. " +
                    "Try more hours, fewer words, or notifications.apps for the app names.")
            }
            .put("notifications", list).toString()
    }

    private fun notificationApps(context: Context, params: JSONObject): String {
        val since = System.currentTimeMillis() - hours(params) * 3_600_000L
        val found = OrbitNotificationHistory.apps(context, since, 51)
        val apps = found.take(50)
        return JSONObject().put("source", "history").put("timezone", zone()).put("from", time(since))
            .put("returned", apps.size).put("has_more", found.size > 50)
            .apply { if (found.size > 50) put("note", "Only the 50 apps with the most notifications are listed; others posted too. Use fewer hours to narrow it.") }
            .put("apps", JSONArray(apps.map {
                JSONObject().put("app", it.app).put("package", it.pkg).put("count", it.count).put("latest", time(it.latest))
            })).toString()
    }

    private fun notificationRead(context: Context, params: JSONObject): String {
        val id = params.optString("id").trim().toLongOrNull() ?: error("notifications.read needs id from notifications.search")
        val item = OrbitNotificationHistory.read(context, id) ?: error("No notification with id $id in the history (it may be older " +
            "than ${OrbitNotificationHistory.KEEP_DAYS} days); search again")
        return JSONObject().put("source", "history").put("timezone", zone()).put("id", item.id).put("time", time(item.time))
            .put("app", item.app).put("package", item.pkg).put("title", item.title).put("chars", item.chars)
            .put("stored_cut", item.storedCut)
            .apply {
                if (item.storedCut) put("note", "Part of it was cut when it arrived: ${item.body.length} of about ${item.chars} " +
                    "characters were kept (very long parts and lines are shortened), and the rest is not recorded anywhere.")
            }
            .put("text", item.body).toString()
    }

    private fun location(context: Context, params: JSONObject): String {
        val result = AIToolHandler.getInstance(context).executeTool(AITool("get_device_location",
            listOf(ToolParameter("high_accuracy", params.optBoolean("high_accuracy", false).toString()))))
        check(result.success) { result.error ?: "Location is not available right now" }
        return result.result.toString()
    }
}
