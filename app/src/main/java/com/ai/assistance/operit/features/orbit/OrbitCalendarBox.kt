package com.ai.assistance.operit.features.orbit

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The main calendar of a connected mailbox: Gmail over CalDAV, Outlook over Microsoft Graph. Times
 * are shown in the phone's time zone; whole-day events keep their dates. Only the user's own simple
 * events can be changed: not repeating ones (that would change the series) and not ones with other
 * people invited (that would notify them).
 */
internal object OrbitCalendarBox {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private const val GRAPH = "https://graph.microsoft.com/v1.0/me"
    private const val MAX_PAGES = 10
    private val XML = "application/xml; charset=utf-8".toMediaType()
    private val ICS = "text/calendar; charset=utf-8".toMediaType()
    private val JSON = "application/json".toMediaType()

    /** When an event happens; [end] is exclusive (the day after, for a whole-day event). */
    class Span(val start: ZonedDateTime, val end: ZonedDateTime, val allDay: Boolean)

    /** [others]: people are invited, so changing it would write to them. [etag]: its version, for safe changes. */
    class Event(val id: String, val title: String, val span: Span, val location: String, val description: String,
        val repeats: Boolean, val others: Boolean, val etag: String?)

    /** What to set: null leaves a field as it is, "" clears it. [span] is set whole. */
    class Change(val title: String?, val span: Span?, val location: String?, val description: String?)

    const val CHANGED = "The event changed since the card was made; nothing was changed. Read it again and make a new card"
    const val GONE = "That event is no longer in the calendar"
    const val REPEATS = "This event repeats; changing it here would change every time it happens. Ask the user to change it in their calendar app"
    const val OTHERS = "Other people are invited to this event; changing it would notify them. Ask the user to change it in their calendar app"
    const val NO_VERSION = "The calendar did not say which version of this event it is, so it cannot be changed safely here. Ask the user to change it in their calendar app"

    // ---- showing ----

    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    /** The last day of a whole-day span. */
    private fun lastDay(span: Span): LocalDate =
        span.end.toLocalDate().minusDays(1).takeIf { !it.isBefore(span.start.toLocalDate()) } ?: span.start.toLocalDate()

    fun show(span: Span, english: Boolean): String = if (span.allDay) {
        val first = span.start.toLocalDate()
        val last = lastDay(span)
        (if (first == last) DAY.format(first) else "${DAY.format(first)} – ${DAY.format(last)}") +
            if (english) " (all day)" else "（全天）"
    } else {
        val start = span.start.withZoneSameInstant(zone)
        val end = span.end.withZoneSameInstant(zone)
        MINUTE.format(start) + " – " + if (end.toLocalDate() == start.toLocalDate()) CLOCK.format(end) else MINUTE.format(end)
    }

    fun json(event: Event, withDescription: Boolean): JSONObject = JSONObject()
        .put("id", event.id).put("title", event.title)
        .put("start", if (event.span.allDay) DAY.format(event.span.start) else MINUTE.format(event.span.start.withZoneSameInstant(zone)))
        .put("end", if (event.span.allDay) DAY.format(lastDay(event.span)) else MINUTE.format(event.span.end.withZoneSameInstant(zone)))
        .apply {
            if (event.span.allDay) put("all_day", true)
            if (event.location.isNotBlank()) put("location", event.location)
            if (event.repeats) put("repeats", true)
            if (event.others) put("with_others", true)
            if (withDescription && event.description.isNotBlank()) put("description", event.description.take(4000))
        }

    // ---- reading ----

    /** Events found, and whether the server had more than could be read ([incomplete]). */
    class Listed(val events: List<Event>, val incomplete: Boolean)

    /** Up to [limit] events overlapping the range, earliest first. */
    suspend fun list(context: Context, orbitId: String, account: OrbitMailAccount, from: ZonedDateTime, to: ZonedDateTime,
        limit: Int): Listed {
        // Asked a day wider on each side, since a whole day is a date that may sit on either side of
        // an instant; what is shown is cut to the range by the dates and times as shown.
        val listed = if (account.provider == OrbitMailProvider.GMAIL) Listed(googleList(context, orbitId, account, from, to), false)
            else graphList(context, orbitId, account, from, to)
        val gmail = account.provider == OrbitMailProvider.GMAIL
        return Listed(listed.events.filter { overlaps(it, from, to, gmail) }.sortedBy { it.span.start.toInstant() }.take(limit),
            listed.incomplete)
    }

    /**
     * Whether [event] falls in the range. A Gmail series the server did not expand (no "#time" in
     * its id) is kept, as it cannot be placed.
     */
    private fun overlaps(event: Event, from: ZonedDateTime, to: ZonedDateTime, gmail: Boolean): Boolean {
        if (gmail && event.repeats && '#' !in event.id) return true
        val start = event.span.start
        val end = if (event.span.end.isAfter(start)) event.span.end else start.plusNanos(1)
        return end.isAfter(from) && start.isBefore(to)
    }

    suspend fun read(context: Context, orbitId: String, account: OrbitMailAccount, id: String): Event =
        if (account.provider == OrbitMailProvider.GMAIL) googleRead(context, orbitId, account, id).first
        else graphRead(context, orbitId, account, id)

    // ---- changing (only after the user allowed it on a card) ----

    /**
     * Adds an event; returns its id. [key] is fixed when the card is made, so a request repeated
     * after a lost answer adds nothing twice.
     */
    suspend fun create(context: Context, orbitId: String, account: OrbitMailAccount, change: Change, key: String): String {
        val title = checkNotNull(change.title)
        val span = checkNotNull(change.span)
        val full = Change(title, span, change.location, change.description)
        return if (account.provider == OrbitMailProvider.GMAIL) {
            require(key.matches(Regex("[A-Za-z0-9-]{8,64}"))) { "Not added: the card is incomplete" }
            val uid = key
            val id = "$key.ics"
            val now = OrbitICal.utc(Instant.now())
            val lines = listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Orbit//Orbit//EN", "BEGIN:VEVENT",
                "UID:$uid", "DTSTAMP:$now", "CREATED:$now") + eventLines(full, sequence = null) +
                listOf("END:VEVENT", "END:VCALENDAR")
            val answer = OrbitConnectorHttp.authorized(context, orbitId, account) { token ->
                Request.Builder().url(googleUrl(account, id)).header("Authorization", "Bearer $token")
                    .header("If-None-Match", "*").put(ics(lines).toRequestBody(ICS)).build()
            }
            // Already there: this very event was added by an earlier try.
            if (answer.status != 412) answer.orFail("Adding the event")
            id
        } else {
            val body = graphBody(full).put("transactionId", key).toString()
            val answer = OrbitConnectorHttp.authorized(context, orbitId, account) { token ->
                Request.Builder().url("$GRAPH/events").header("Authorization", "Bearer $token")
                    .post(body.toRequestBody(JSON)).build()
            }.orFail("Adding the event")
            JSONObject(answer.body).optString("id").ifEmpty { error("Outlook did not return the new event") }
        }
    }

    /** Changes the event, only if it is still the version [etag] the card showed. */
    suspend fun update(context: Context, orbitId: String, account: OrbitMailAccount, id: String, etag: String, change: Change) {
        val answer = if (account.provider == OrbitMailProvider.GMAIL) {
            val (event, raw) = googleRead(context, orbitId, account, id)
            check(event.etag == etag) { CHANGED }
            check(!event.repeats) { REPEATS }
            check(!event.others) { OTHERS }
            OrbitConnectorHttp.authorized(context, orbitId, account) { token ->
                Request.Builder().url(googleUrl(account, id)).header("Authorization", "Bearer $token")
                    .header("If-Match", etag).put(rewrite(raw, change).toRequestBody(ICS)).build()
            }
        } else {
            OrbitConnectorHttp.authorized(context, orbitId, account) { token ->
                Request.Builder().url(graphEventUrl(id)).header("Authorization", "Bearer $token")
                    .header("If-Match", etag).patch(graphBody(change).toString().toRequestBody(JSON)).build()
            }
        }
        check(answer.status != 412) { CHANGED }
        check(answer.status != 404 && answer.status != 410) { GONE }
        answer.orFail("Changing the event")
    }

    /** Deletes the event if it is still the version [etag] the card showed; false when it was already gone. */
    suspend fun delete(context: Context, orbitId: String, account: OrbitMailAccount, id: String, etag: String): Boolean {
        val url = if (account.provider == OrbitMailProvider.GMAIL) googleUrl(account, id) else graphEventUrl(id)
        val answer = OrbitConnectorHttp.authorized(context, orbitId, account) { token ->
            Request.Builder().url(url).header("Authorization", "Bearer $token").header("If-Match", etag).delete().build()
        }
        if (answer.status == 404 || answer.status == 410) return false
        check(answer.status != 412) { CHANGED }
        answer.orFail("Deleting the event")
        return true
    }

    // ---- Gmail: CalDAV ----

    private fun googleCollection(account: OrbitMailAccount): HttpUrl = HttpUrl.Builder().scheme("https")
        .host("apidata.googleusercontent.com").addPathSegment("caldav").addPathSegment("v2")
        .addPathSegment(account.email).addPathSegment("events").addPathSegment("").build()

    /**
     * An event's address from its id: the last part of its path (which cannot point anywhere
     * else), and for one time of a repeating event "#" and when that time was planned.
     */
    private fun googleUrl(account: OrbitMailAccount, id: String): HttpUrl {
        val file = id.substringBefore('#')
        require(file.isNotBlank() && '/' !in file && file != "." && file != "..") { "Unknown event id $id" }
        val base = googleCollection(account)
        return base.newBuilder().setPathSegment(base.pathSegments.lastIndex, file).build()
    }

    private fun dav(url: HttpUrl, method: String, token: String, body: String): Request =
        Request.Builder().url(url).header("Authorization", "Bearer $token").header("Depth", "1")
            .method(method, body.toRequestBody(XML)).build()

    private suspend fun googleList(context: Context, orbitId: String, account: OrbitMailAccount, from: ZonedDateTime,
        to: ZonedDateTime): List<Event> {
        fun query(expand: Boolean): String {
            // Expanded times are cut to the range here, so they are asked a day wider; a series the
            // server cannot expand is kept whole, so it is asked for the range itself.
            val start = OrbitICal.utc((if (expand) from.minusDays(1) else from).toInstant())
            val end = OrbitICal.utc((if (expand) to.plusDays(1) else to).toInstant())
            return """<?xml version="1.0" encoding="utf-8"?>
<C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
<D:prop><D:getetag/><C:calendar-data>${if (expand) "<C:expand start=\"$start\" end=\"$end\"/>" else ""}</C:calendar-data></D:prop>
<C:filter><C:comp-filter name="VCALENDAR"><C:comp-filter name="VEVENT"><C:time-range start="$start" end="$end"/></C:comp-filter></C:comp-filter></C:filter>
</C:calendar-query>"""
        }
        val url = googleCollection(account)
        var answer = OrbitConnectorHttp.authorized(context, orbitId, account) { dav(url, "REPORT", it, query(true)) }
        // A server that cannot expand repeating events lists each series once.
        if (answer.status == 400 || answer.status == 501) {
            answer = OrbitConnectorHttp.authorized(context, orbitId, account) { dav(url, "REPORT", it, query(false)) }
        }
        answer.orFail("Reading the calendar")
        return OrbitDav.multistatus(answer.body).flatMap { item ->
            val data = item.props["calendar-data"] ?: return@flatMap emptyList()
            val file = fileOf(url, item.href) ?: return@flatMap emptyList()
            parsed(file, data, item.props["getetag"]).filter { !it.second }.map { it.first }
        }
    }

    /** The file name of [href] when it is a resource of this calendar, else null. */
    private fun fileOf(collection: HttpUrl, href: String): String? {
        val resolved = collection.resolve(href) ?: return null
        if (resolved.host != collection.host || resolved.pathSegments.size != collection.pathSegments.size ||
            resolved.pathSegments.dropLast(1) != collection.pathSegments.dropLast(1)) return null
        return resolved.pathSegments.last().takeIf { it.isNotBlank() }
    }

    /** Whether a resource's VEVENTs make a repeating series, read from all of them, cancelled ones too. */
    private fun repeats(blocks: List<List<OrbitICal.Prop>>) =
        blocks.size > 1 || blocks.any { b -> b.any { it.name == "RRULE" || it.name == "RDATE" || it.name == "RECURRENCE-ID" } }

    private fun cancelled(block: List<OrbitICal.Prop>) = block.any { it.name == "STATUS" && it.value.trim().equals("CANCELLED", true) }

    /**
     * When this VEVENT stands for one time of a series, that planned time as written in ids: the
     * date for a whole day (the same whatever the phone's time zone), otherwise the UTC time.
     */
    private fun recurrence(block: List<OrbitICal.Prop>): String? = block.firstOrNull { it.name == "RECURRENCE-ID" }?.let { prop ->
        val (at, day) = OrbitICal.timeAsWritten(prop, zone) ?: return@let null
        if (day) OrbitICal.date(at.toLocalDate()) else OrbitICal.utc(at.toInstant())
    }

    /** The events in one calendar resource, each with whether it was cancelled. */
    private fun parsed(file: String, data: String, etag: String?): List<Pair<Event, Boolean>> {
        val blocks = OrbitICal.blocks(OrbitICal.unfold(data), "VEVENT")
        val repeats = repeats(blocks)
        return blocks.mapNotNull { b ->
            val id = recurrence(b)?.let { "$file#$it" } ?: file
            eventOf(id, b, etag, repeats)?.let { it to cancelled(b) }
        }
    }

    private fun eventOf(id: String, props: List<OrbitICal.Prop>, etag: String?, repeats: Boolean): Event? {
        fun text(name: String) = props.firstOrNull { it.name == name }?.value?.let(OrbitICal::unescape).orEmpty()
        // A DURATION is added in the time zone the start was written in, then both are shown in the phone's.
        val (written, allDay) = props.firstOrNull { it.name == "DTSTART" }?.let { OrbitICal.timeAsWritten(it, zone) } ?: return null
        val endWritten = props.firstOrNull { it.name == "DTEND" }?.let { OrbitICal.timeAsWritten(it, zone)?.first }
            ?: props.firstOrNull { it.name == "DURATION" }?.let { OrbitICal.plusDuration(written, it.value) }
            ?: if (allDay) written.plusDays(1) else written
        fun shown(at: ZonedDateTime) = if (allDay) at.toLocalDate().atStartOfDay(zone) else at.withZoneSameInstant(zone)
        val start = shown(written)
        val end = shown(endWritten)
        return Event(id, text("SUMMARY"), Span(start, if (end.isBefore(start)) start else end, allDay), text("LOCATION"),
            text("DESCRIPTION"), repeats, props.any { it.name == "ATTENDEE" }, etag)
    }

    /**
     * The event and its iCalendar text. For one time of a series ("file#time"): that time's own
     * entry, as the server expands the series, so a time since cancelled or removed is gone.
     */
    private suspend fun googleRead(context: Context, orbitId: String, account: OrbitMailAccount, id: String): Pair<Event, String> {
        val time = id.substringAfter('#', "").ifEmpty { null }
        if (time != null) return googleInstance(context, orbitId, account, id, time)
        val url = googleUrl(account, id)
        val answer = OrbitConnectorHttp.authorized(context, orbitId, account) { token ->
            Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        }
        check(answer.status != 404 && answer.status != 410) { "$GONE ($id)" }
        answer.orFail("Reading the event")
        val blocks = OrbitICal.blocks(OrbitICal.unfold(answer.body), "VEVENT")
        val repeats = repeats(blocks)
        val master = blocks.firstOrNull { b -> b.none { it.name == "RECURRENCE-ID" } } ?: blocks.firstOrNull()
        val block = master ?: error("That calendar entry is not an event")
        check(!cancelled(block)) { "$GONE ($id)" }
        return (eventOf(id, block, answer.etag, repeats) ?: error("That calendar entry is not an event")) to answer.body
    }

    /**
     * One time of a repeating event. A time that was moved or cancelled has its own entry in the
     * series, found by its planned time; any other time is asked of the server, with the series
     * expanded around it, so a time since removed reads as gone.
     */
    private suspend fun googleInstance(context: Context, orbitId: String, account: OrbitMailAccount, id: String,
        time: String): Pair<Event, String> {
        val at: Instant = runCatching {
            if (time.length == 8) LocalDate.parse(time, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant()
            else LocalDateTime.parse(time, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")).toInstant(ZoneOffset.UTC)
        }.getOrNull() ?: throw IllegalArgumentException("Unknown event id $id")
        val url = googleUrl(account, id)
        val file = id.substringBefore('#')
        val raw = OrbitConnectorHttp.authorized(context, orbitId, account) { token ->
            Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        }
        check(raw.status != 404 && raw.status != 410) { "$GONE ($id)" }
        raw.orFail("Reading the event")
        parsed(file, raw.body, raw.etag).firstOrNull { it.first.id == id }?.let { (event, cancelled) ->
            check(!cancelled) { "$GONE ($id)" }
            return event to raw.body
        }
        val collection = googleCollection(account)
        val start = OrbitICal.utc(at.minus(Duration.ofDays(2)))
        val end = OrbitICal.utc(at.plus(Duration.ofDays(2)))
        val query = """<?xml version="1.0" encoding="utf-8"?>
<C:calendar-multiget xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
<D:prop><D:getetag/><C:calendar-data><C:expand start="$start" end="$end"/></C:calendar-data></D:prop>
<D:href>${OrbitDav.escape(url.encodedPath)}</D:href>
</C:calendar-multiget>"""
        val answer = OrbitConnectorHttp.authorized(context, orbitId, account) { dav(collection, "REPORT", it, query) }
            .orFail("Reading the event")
        val item = OrbitDav.multistatus(answer.body).firstOrNull { it.props["calendar-data"] != null && fileOf(collection, it.href) == file }
            ?: error("$GONE ($id)")
        val data = item.props.getValue("calendar-data")
        val event = parsed(file, data, item.props["getetag"]).firstOrNull { (event, cancelled) -> event.id == id && !cancelled }?.first
            ?: error("$GONE ($id)")
        return event to data
    }

    private fun ics(lines: List<String>): String = lines.joinToString("") { OrbitICal.fold(it) + "\r\n" }

    /** The properties [change] sets, as iCalendar lines; an empty location or description is left out (cleared). */
    private fun eventLines(change: Change, sequence: Int?): List<String> = buildList {
        change.title?.let { add("SUMMARY:" + OrbitICal.escape(it)) }
        change.span?.let { span ->
            if (span.allDay) {
                add("DTSTART;VALUE=DATE:" + OrbitICal.date(span.start.toLocalDate()))
                add("DTEND;VALUE=DATE:" + OrbitICal.date(span.end.toLocalDate()))
            } else {
                add("DTSTART:" + OrbitICal.utc(span.start.toInstant()))
                add("DTEND:" + OrbitICal.utc(span.end.toInstant()))
            }
        }
        change.location?.takeIf { it.isNotEmpty() }?.let { add("LOCATION:" + OrbitICal.escape(it)) }
        change.description?.takeIf { it.isNotEmpty() }?.let { add("DESCRIPTION:" + OrbitICal.escape(it)) }
        sequence?.let { add("SEQUENCE:$it") }
    }

    /**
     * [raw] with the event's fields that [change] sets replaced, everything else kept. The new
     * fields go before the event's first inner part (a reminder), as iCalendar requires.
     */
    private fun rewrite(raw: String, change: Change): String {
        val replaced = buildSet {
            if (change.title != null) add("SUMMARY")
            if (change.span != null) addAll(listOf("DTSTART", "DTEND", "DURATION"))
            if (change.location != null) add("LOCATION")
            if (change.description != null) add("DESCRIPTION")
            addAll(listOf("DTSTAMP", "LAST-MODIFIED", "SEQUENCE"))
        }
        val lines = OrbitICal.unfold(raw)
        val sequence = lines.asSequence().mapNotNull(OrbitICal::parse).firstOrNull { it.name == "SEQUENCE" }
            ?.value?.trim()?.toIntOrNull() ?: 0
        val now = OrbitICal.utc(Instant.now())
        val added = listOf("DTSTAMP:$now", "LAST-MODIFIED:$now") + eventLines(change, sequence + 1)
        val out = mutableListOf<String>()
        val stack = ArrayList<String>()
        var placed = false
        for (line in lines) {
            val prop = OrbitICal.parse(line)
            val inEvent = stack.size == 2 && stack[1] == "VEVENT"
            if (inEvent && !placed && prop != null && (prop.name == "BEGIN" || prop.name == "END")) {
                out += added
                placed = true
            }
            when {
                prop?.name == "BEGIN" -> stack += prop.value.trim().uppercase()
                prop?.name == "END" -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                inEvent && prop != null && prop.name in replaced -> continue
            }
            out += line
        }
        check(placed) { "That calendar entry is not an event" }
        return ics(out)
    }

    // ---- Outlook: Microsoft Graph ----

    private fun graphEventUrl(id: String): HttpUrl {
        require(id.isNotBlank() && id != "." && id != "..") { "Unknown event id" }
        return "$GRAPH/events".toHttpUrl().newBuilder().addPathSegment(id).build()
    }

    /** Graph's own next page of the same list, never anything else. */
    internal fun graphNext(json: JSONObject, path: String): HttpUrl? =
        json.optString("@odata.nextLink").toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.host == "graph.microsoft.com" && it.port == 443 && it.encodedPath == path
        }

    private fun graphTime(json: JSONObject?, allDay: Boolean): ZonedDateTime? = runCatching {
        val local = LocalDateTime.parse(json!!.getString("dateTime"))
        // A whole day is a date: Graph writes it as that day's midnight, whatever the time zone.
        if (allDay) return@runCatching local.toLocalDate().atStartOfDay(zone)
        val from = runCatching { ZoneId.of(json.optString("timeZone")) }.getOrDefault(ZoneOffset.UTC)
        local.atZone(from).withZoneSameInstant(zone)
    }.getOrNull()

    private fun graphEvent(json: JSONObject): Event? {
        if (json.optBoolean("isCancelled")) return null
        val allDay = json.optBoolean("isAllDay")
        val start = graphTime(json.optJSONObject("start"), allDay) ?: return null
        val end = graphTime(json.optJSONObject("end"), allDay) ?: start
        return Event(json.optString("id"), json.optString("subject"), Span(start, if (end.isBefore(start)) start else end, allDay),
            json.optJSONObject("location")?.optString("displayName").orEmpty(),
            json.optJSONObject("body")?.optString("content").orEmpty().trim(),
            json.optString("type", "singleInstance") != "singleInstance",
            (json.optJSONArray("attendees")?.length() ?: 0) > 0,
            json.optString("@odata.etag").ifEmpty { null })
    }

    private const val SELECT = "id,subject,start,end,isAllDay,isCancelled,location,type,attendees"

    /** Every event of the range, read in pages of 100 up to [MAX_PAGES]; sorted and cut by the caller. */
    private suspend fun graphList(context: Context, orbitId: String, account: OrbitMailAccount, from: ZonedDateTime,
        to: ZonedDateTime): Listed {
        val first = "$GRAPH/calendarView".toHttpUrl().newBuilder()
            .addQueryParameter("startDateTime", from.minusDays(1).toInstant().toString())
            .addQueryParameter("endDateTime", to.plusDays(1).toInstant().toString())
            .addQueryParameter("\$top", "100")
            .addQueryParameter("\$orderby", "start/dateTime")
            .addQueryParameter("\$select", SELECT).build()
        val events = mutableListOf<Event>()
        val seen = mutableSetOf<String>()
        var next: HttpUrl? = first
        var pages = 0
        // Set only when Graph said there is no next page; any other stop means some may be missing.
        var complete = false
        while (next != null && pages < MAX_PAGES && seen.add(next.toString())) {
            val url: HttpUrl = next
            pages++
            val answer = OrbitConnectorHttp.authorized(context, orbitId, account) { token ->
                Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
            }.orFail("Reading the calendar")
            val json = JSONObject(answer.body)
            val array = json.optJSONArray("value") ?: break
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::graphEvent) }
                .filterTo(events) { overlaps(it, from, to, gmail = false) }
            next = graphNext(json, first.encodedPath)
            if (next == null && !json.has("@odata.nextLink")) complete = true
        }
        return Listed(events, !complete)
    }

    private suspend fun graphRead(context: Context, orbitId: String, account: OrbitMailAccount, id: String): Event {
        val url = graphEventUrl(id).newBuilder().addQueryParameter("\$select", "$SELECT,body").build()
        val answer = OrbitConnectorHttp.authorized(context, orbitId, account) { token ->
            Request.Builder().url(url).header("Authorization", "Bearer $token")
                .header("Prefer", "outlook.body-content-type=\"text\"").get().build()
        }
        check(answer.status != 404) { "$GONE ($id)" }
        answer.orFail("Reading the event")
        return graphEvent(JSONObject(answer.body)) ?: error("$GONE ($id)")
    }

    private fun graphBody(change: Change): JSONObject = JSONObject().apply {
        change.title?.let { put("subject", it) }
        change.span?.let { span ->
            fun at(time: ZonedDateTime) = JSONObject().put("timeZone", "UTC").put("dateTime",
                if (span.allDay) time.toLocalDate().toString() + "T00:00:00"
                else time.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            put("isAllDay", span.allDay).put("start", at(span.start)).put("end", at(span.end))
        }
        change.location?.let { put("location", JSONObject().put("displayName", it)) }
        change.description?.let { put("body", JSONObject().put("contentType", "text").put("content", it)) }
    }
}
