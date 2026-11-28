package com.ai.assistance.operit.features.orbit

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A connected mailbox's contacts, read only: Gmail over CardDAV, Outlook over Microsoft Graph.
 * The whole list is loaded (up to [MAX]) and searched on the phone; it is kept in memory a few
 * minutes so several searches in a row ask the server once.
 */
internal object OrbitContactsBox {
    private const val MAX = 2000
    private const val BATCH = 100
    private const val MAX_PAGES = 10
    private const val KEEP_MS = 10 * 60_000L
    private val XML = "application/xml; charset=utf-8".toMediaType()

    class Contact(val name: String, val emails: List<String>, val phones: List<String>, val org: String, val birthday: String)

    private val cache = ConcurrentHashMap<String, Pair<Long, List<Contact>>>()
    /** One load per account at a time; a second search waits for it and uses what it loaded. */
    private val loading = ConcurrentHashMap<String, Mutex>()
    /** Bumped when an account is disconnected, so a load still running then keeps nothing. */
    private val epochs = ConcurrentHashMap<String, Long>()
    /** Makes forgetting and keeping a loaded list one step each, so neither slips between the other's check and write. */
    private val guard = Any()

    /** Forgets what was loaded for an account (it was disconnected). */
    fun forget(orbitId: String, accountId: String) {
        val key = "$orbitId/$accountId"
        synchronized(guard) {
            epochs.merge(key, 1L, Long::plus)
            cache.remove(key)
            loading.remove(key)
        }
    }

    /** Contacts whose name, address, phone or company has [query] (all of them when blank), and how many matched. */
    suspend fun search(context: Context, orbitId: String, account: OrbitMailAccount, query: String?, limit: Int): Pair<List<Contact>, Int> {
        val all = load(context, orbitId, account)
        val words = query?.trim()?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        val found = all.filter { c ->
            val digits = c.phones.joinToString(" ") { p -> p.filter { it.isDigit() } }
            val text = (listOf(c.name, c.org) + c.emails + c.phones).joinToString(" ").lowercase() + " " + digits
            words.all { it in text }
        }.sortedBy { it.name.lowercase() }
        return found.take(limit) to found.size
    }

    fun json(contact: Contact): JSONObject = JSONObject().put("name", contact.name).apply {
        if (contact.emails.isNotEmpty()) put("emails", JSONArray(contact.emails))
        if (contact.phones.isNotEmpty()) put("phones", JSONArray(contact.phones))
        if (contact.org.isNotBlank()) put("company", contact.org)
        if (contact.birthday.isNotBlank()) put("birthday", contact.birthday)
    }

    private suspend fun load(context: Context, orbitId: String, account: OrbitMailAccount): List<Contact> {
        val key = "$orbitId/${account.id}"
        val now = System.currentTimeMillis()
        cache.entries.removeIf { now - it.value.first >= KEEP_MS }
        return loading.getOrPut(key) { Mutex() }.withLock {
            cache[key]?.second ?: run {
                val epoch = epochs[key]
                val list = if (account.provider == OrbitMailProvider.GMAIL) google(context, orbitId, account)
                    else graph(context, orbitId, account)
                synchronized(guard) { if (epochs[key] == epoch) cache[key] = System.currentTimeMillis() to list }
                list
            }
        }
    }

    // ---- Gmail: CardDAV, the way Thunderbird reads it (list the cards, then fetch them in batches) ----

    private fun book(account: OrbitMailAccount): HttpUrl = HttpUrl.Builder().scheme("https").host("www.googleapis.com")
        .addPathSegment("carddav").addPathSegment("v1").addPathSegment("principals").addPathSegment(account.email)
        .addPathSegment("lists").addPathSegment("default").addPathSegment("").build()

    private fun dav(url: HttpUrl, method: String, token: String, body: String): Request =
        Request.Builder().url(url).header("Authorization", "Bearer $token").header("Depth", "1")
            .method(method, body.toRequestBody(XML)).build()

    private suspend fun google(context: Context, orbitId: String, account: OrbitMailAccount): List<Contact> {
        val url = book(account)
        val listed = OrbitConnectorHttp.authorized(context, orbitId, account) {
            dav(url, "PROPFIND", it, """<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:prop><D:getetag/><D:getcontenttype/></D:prop></D:propfind>""")
        }.orFail("Reading contacts")
        val hrefs = OrbitDav.multistatus(listed.body).map { it.href }
            // Only cards of this address book.
            .filter { href ->
                url.resolve(href)?.let {
                    it.host == url.host && it.pathSegments.size == url.pathSegments.size &&
                        it.pathSegments.dropLast(1) == url.pathSegments.dropLast(1) && it.pathSegments.last().isNotBlank()
                } == true
            }
            .distinct().take(MAX)
        return hrefs.chunked(BATCH).flatMap { batch ->
            val body = """<?xml version="1.0" encoding="utf-8"?><C:addressbook-multiget xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:carddav">""" +
                "<D:prop><D:getetag/><C:address-data/></D:prop>" +
                batch.joinToString("") { "<D:href>${OrbitDav.escape(it)}</D:href>" } + "</C:addressbook-multiget>"
            val answer = OrbitConnectorHttp.authorized(context, orbitId, account) { dav(url, "REPORT", it, body) }
                .orFail("Reading contacts")
            OrbitDav.multistatus(answer.body).mapNotNull { it.props["address-data"]?.let(::vcard) }
        }
    }

    private fun vcard(data: String): Contact? {
        val props = OrbitICal.blocks(OrbitICal.unfold(data), "VCARD").firstOrNull() ?: return null
        fun values(name: String) = props.filter { it.name == name }.map { OrbitICal.unescape(it.value).trim() }.filter { it.isNotEmpty() }
        val name = values("FN").firstOrNull()
            ?: values("N").firstOrNull()?.split(';')?.let { (it.getOrNull(1).orEmpty() + " " + it.getOrNull(0).orEmpty()).trim() }
            ?: ""
        val emails = values("EMAIL").map { it.removePrefix("mailto:") }.distinct()
        val phones = values("TEL").map { it.removePrefix("tel:") }.distinct()
        if (name.isBlank() && emails.isEmpty() && phones.isEmpty()) return null
        return Contact(name, emails, phones, values("ORG").firstOrNull()?.replace(";", " ")?.trim().orEmpty(),
            values("BDAY").firstOrNull().orEmpty())
    }

    // ---- Outlook: Microsoft Graph ----

    private suspend fun graph(context: Context, orbitId: String, account: OrbitMailAccount): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val first = "https://graph.microsoft.com/v1.0/me/contacts".toHttpUrl().newBuilder()
            .addQueryParameter("\$top", "500")
            .addQueryParameter("\$select", "displayName,emailAddresses,mobilePhone,homePhones,businessPhones,companyName,birthday")
            .build()
        var next: HttpUrl? = first
        val seen = mutableSetOf<String>()
        var pages = 0
        while (next != null && contacts.size < MAX && pages < MAX_PAGES && seen.add(next.toString())) {
            val url: HttpUrl = next
            pages++
            val answer = OrbitConnectorHttp.authorized(context, orbitId, account) { token ->
                Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
            }.orFail("Reading contacts")
            val json = JSONObject(answer.body)
            val array = json.optJSONArray("value") ?: break
            if (array.length() == 0) break
            for (i in 0 until array.length()) {
                val c = array.optJSONObject(i) ?: continue
                fun strings(name: String) = c.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                val emails = c.optJSONArray("emailAddresses")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("address")?.takeIf { s -> s.isNotBlank() } }
                }.orEmpty()
                val phones = (listOf(c.optString("mobilePhone")) + strings("homePhones") + strings("businessPhones"))
                    .filter { it.isNotBlank() && it != "null" }.distinct()
                contacts += Contact(c.optString("displayName"), emails, phones, c.optString("companyName").takeIf { it != "null" }.orEmpty(),
                    c.optString("birthday").takeIf { it != "null" }?.take(10).orEmpty())
            }
            next = OrbitCalendarBox.graphNext(json, first.encodedPath)
        }
        return contacts.take(MAX)
    }
}
