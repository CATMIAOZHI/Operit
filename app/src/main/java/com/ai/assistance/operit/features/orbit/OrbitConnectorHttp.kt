package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resumeWithException

/**
 * HTTP to a mailbox's calendar and contacts servers, signed in as the account. Gmail uses the
 * mail token (its sign-in covers calendar and contacts); Outlook a separate Graph token. Tokens
 * stay here; errors carry only the HTTP status and a provider error code.
 */
internal object OrbitConnectorHttp {
    private const val MAX_BODY = 4L * 1024 * 1024
    /** No redirects: every request goes only to the address it was built for. */
    private val http get() = OrbitMailAccounts.http

    class Answer(val status: Int, val body: String, val etag: String?) {
        val ok: Boolean get() = status in 200..299

        /** This answer when it succeeded; otherwise fails saying [what] failed. */
        fun orFail(what: String): Answer {
            if (ok) return this
            val code = runCatching { JSONObject(body).optJSONObject("error")?.optString("code") }.getOrNull()
                ?.takeIf { it.matches(Regex("[A-Za-z]{1,60}")) }
            throw IllegalStateException("$what failed (HTTP $status${if (code != null) ", $code" else ""})")
        }
    }

    private suspend fun call(request: Request): Answer = suspendCancellableCoroutine { continuation ->
        val call: Call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching {
                    response.use {
                        val text = it.body?.source()?.let { source ->
                            source.request(MAX_BODY + 1)
                            check(source.buffer.size <= MAX_BODY) { "The answer is too large" }
                            source.readUtf8()
                        }.orEmpty()
                        Answer(it.code, text, it.header("ETag"))
                    }
                })
            }
        })
    }

    /**
     * Sends the request [build] makes with [account]'s token. A refused token (401) is refreshed
     * once; a 403 means the account did not allow calendar and contacts.
     */
    suspend fun authorized(context: Context, orbitId: String, account: OrbitMailAccount, build: (token: String) -> Request): Answer {
        val graph = account.provider == OrbitMailProvider.OUTLOOK
        suspend fun token(refused: String?) = (if (graph) OrbitMailAccounts.graphLogin(context, orbitId, account.id, refused)
            else OrbitMailAccounts.login(context, orbitId, account.id, refused)).accessToken
        var current = token(null)
        for (attempt in 0..1) {
            val answer = call(build(current))
            if (answer.status == 403) throw OrbitMailSignInNeeded(notAllowed(account))
            if (answer.status != 401) return answer
            if (attempt == 0) current = token(current)
        }
        throw OrbitMailSignInNeeded(notAllowed(account))
    }

    private fun notAllowed(account: OrbitMailAccount) = "${account.email} did not allow calendar and contacts. If it was " +
        "connected before Orbit used them, ask the user to open Orbit settings → Connectors and sign this account in again"
}

/** Reads a WebDAV multistatus answer (CalDAV, CardDAV). */
internal object OrbitDav {
    /** One resource: its href and the properties its server found (status 200), by local name. */
    class Item(val href: String, val props: Map<String, String>)

    fun multistatus(xml: String): List<Item> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        val items = mutableListOf<Item>()
        val stack = ArrayList<String>()
        val text = StringBuilder()
        var href = ""
        val props = HashMap<String, String>()
        val found = HashMap<String, String>()
        var found200 = true
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return items
                XmlPullParser.START_TAG -> {
                    stack += parser.name
                    text.setLength(0)
                    if (parser.name == "propstat") { found.clear(); found200 = true }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val name = parser.name
                    val parent = stack.getOrNull(stack.size - 2)
                    when {
                        name == "href" && parent == "response" -> href = text.toString().trim()
                        name == "status" && parent == "propstat" -> found200 = " 200" in text
                        parent == "prop" -> found[name] = text.toString().trim()
                        name == "propstat" -> if (found200) props.putAll(found)
                        name == "response" -> {
                            items += Item(href, HashMap(props))
                            href = ""
                            props.clear()
                        }
                    }
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    text.setLength(0)
                }
            }
        }
    }

    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** Just enough iCalendar and vCard (RFC 5545, 6350) for events and contacts. */
internal object OrbitICal {
    class Prop(val name: String, val params: Map<String, String>, val value: String)

    /** The content lines, unfolded. */
    fun unfold(text: String): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var started = false
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            if ((line.startsWith(" ") || line.startsWith("\t")) && started) current.append(line, 1, line.length)
            else if (line.isNotEmpty()) {
                if (started) lines += current.toString()
                current.setLength(0)
                current.append(line)
                started = true
            }
        }
        if (started) lines += current.toString()
        return lines
    }

    /** A content line as name (group dropped, upper case), parameters and raw value. */
    fun parse(line: String): Prop? {
        var quoted = false
        var colon = -1
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) { colon = i; break }
            }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull { p ->
            p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it).uppercase() to p.substring(it + 1).trim('"') }
        }.toMap()
        return Prop(head[0].substringAfterLast('.').uppercase(), params, line.substring(colon + 1))
    }

    /** The top-level components named [name] (VEVENT, VCARD), as their own properties; nested ones (VALARM) left out. */
    fun blocks(lines: List<String>, name: String): List<List<Prop>> {
        val blocks = mutableListOf<List<Prop>>()
        var current: MutableList<Prop>? = null
        var nested = 0
        for (line in lines) {
            val prop = parse(line) ?: continue
            val value = prop.value.trim().uppercase()
            when {
                prop.name == "BEGIN" && current == null && value == name -> current = mutableListOf()
                prop.name == "BEGIN" && current != null -> nested++
                prop.name == "END" && current != null && nested > 0 -> nested--
                prop.name == "END" && current != null && value == name -> { blocks += current; current = null }
                current != null && nested == 0 -> current += prop
            }
        }
        return blocks
    }

    fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                val next = value[i + 1]
                append(if (next == 'n' || next == 'N') '\n' else next)
                i += 2
            } else { append(c); i++ }
        }
    }

    fun escape(value: String): String = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\\n")

    /** A content line folded at 75 bytes, never inside a character. */
    fun fold(line: String): String = buildString {
        var bytes = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > 74) { append("\r\n "); bytes = 1 }
            appendCodePoint(cp)
            bytes += size
            i += Character.charCount(cp)
        }
    }

    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE = DateTimeFormatter.BASIC_ISO_DATE

    /** A DTSTART/DTEND as a time in [zone], and whether it is a whole day. */
    fun time(prop: Prop, zone: ZoneId): Pair<ZonedDateTime, Boolean>? =
        timeAsWritten(prop, zone)?.let { (at, day) -> (if (day) at else at.withZoneSameInstant(zone)) to day }

    /**
     * A DTSTART/DTEND in the time zone it was written in ([fallback] when it names none), so a
     * DURATION is added there; a whole day is that date in [fallback].
     */
    fun timeAsWritten(prop: Prop, fallback: ZoneId): Pair<ZonedDateTime, Boolean>? = runCatching {
        val value = prop.value.trim()
        if (prop.params["VALUE"].equals("DATE", true) || value.length == 8) {
            LocalDate.parse(value, DATE).atStartOfDay(fallback) to true
        } else {
            val utc = value.endsWith("Z", true)
            val local = LocalDateTime.parse(value.removeSuffix("Z").removeSuffix("z"), LOCAL)
            val from = if (utc) ZoneOffset.UTC else prop.params["TZID"]?.let { id ->
                runCatching { ZoneId.of(id) }.getOrNull() ?: runCatching { ZoneId.of(id.substringAfterLast("/zoneinfo/")) }.getOrNull()
            } ?: fallback
            local.atZone(from) to false
        }
    }.getOrNull()

    /** [start] plus an iCalendar DURATION (P1W, P1DT2H, PT30M); weeks and days are calendar days. */
    fun plusDuration(start: ZonedDateTime, value: String): ZonedDateTime? = runCatching {
        val m = Regex("([+-]?)P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")
            .matchEntire(value.trim()) ?: return@runCatching null
        fun n(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        val sign = if (m.groupValues[1] == "-") -1 else 1
        start.plusDays(sign * (n(2) * 7 + n(3)))
            .plus(Duration.ofHours(n(4)).plusMinutes(n(5)).plusSeconds(n(6)).multipliedBy(sign.toLong()))
    }.getOrNull()

    fun utc(instant: Instant): String = LOCAL.format(instant.atZone(ZoneOffset.UTC)) + "Z"
    fun date(day: LocalDate): String = DATE.format(day)
}
