package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Muse's Secure Vault for one Orbit: saved logins and API keys, kept encrypted outside backups.
 * Orbit asks with a card in the chat; the user taps it and types the value into a secure entry
 * sheet, and it goes straight into the store. Orbit only ever sees the site, the field names and
 * a masked user name. Using a value happens on the host, which fills it into a page of the same
 * site or adds it to a request for one of its hosts.
 */
internal object OrbitVault {
    const val LOGIN = "login"
    const val API_KEY = "api_key"
    const val MAX_ENTRIES = 100

    data class Entry(
        val id: String,
        val kind: String,
        /** Login: the site's host. API key: the provider's name. */
        val site: String,
        val username: String,
        val secret: String,
        /** API key: the only hosts the key may be sent to. */
        val apiHosts: List<String>,
        /** API key: `bearer`, `header:<Name>` or `query:<param>`. */
        val placement: String,
        val savedAt: Long,
        /** Saved from a new-password card: that card's [Request.requestedAt]; 0 for any other save. */
        val card: Long = 0,
    )

    /** What one card asks the user for. The value never passes through the request. */
    data class Request(
        val orbitId: String,
        /** The chat the card is in; Orbit hears there once the user saves. */
        val chatId: String,
        val kind: String,
        val site: String,
        val pageUrl: String,
        val usernameHint: String,
        val apiHosts: List<String>,
        val placement: String,
        /** Login: the user sets a new password (signing up or resetting), typed twice. */
        val newPassword: Boolean = false,
        /** New password: when the card was made, so it shows done once a password is saved after it. */
        val requestedAt: Long = 0,
    )

    private val pending = MutableStateFlow<Request?>(null)
    /** The entry sheet the Orbit page shows, opened from a card in the chat. */
    val request: StateFlow<Request?> = pending
    private val changes = MutableStateFlow(0)
    /** Bumped on every save or delete, so cards and lists read the vault again. */
    val version: StateFlow<Int> = changes
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun store(context: Context, orbitId: String) = OrbitSecretFile.forOrbit(context, orbitId, "vault")
    private fun lock(orbitId: String) = locks.computeIfAbsent(orbitId) { Mutex() }

    /** https only; the host decides which pages and requests a value may reach. */
    fun httpsHost(url: String): String? = runCatching { Uri.parse(url.trim()) }.getOrNull()
        ?.takeIf { it.scheme.equals("https", true) }?.host?.lowercase()?.removePrefix("www.")?.takeIf { it.contains('.') }

    fun matchesHost(host: String, site: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        val s = site.lowercase().removePrefix("www.")
        return s.isNotEmpty() && (h == s || h.endsWith(".$s"))
    }

    fun masked(username: String): String = when {
        username.isBlank() -> ""
        username.contains('@') -> username.substringBefore('@').take(2) + "***@" + username.substringAfter('@')
        username.length <= 3 -> username.take(1) + "***"
        else -> username.take(2) + "***" + username.takeLast(1)
    }

    suspend fun entries(context: Context, orbitId: String): List<Entry> = withContext(Dispatchers.IO) {
        lock(orbitId).withLock { read(context, orbitId) }
    }

    private fun read(context: Context, orbitId: String): List<Entry> {
        val text = store(context, orbitId).read() ?: return emptyList()
        val array = JSONObject(text).optJSONArray("entries") ?: return emptyList()
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Entry(o.getString("id"), o.getString("kind"), o.getString("site"), o.optString("username"),
                o.getString("secret"),
                o.optJSONArray("apiHosts")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                o.optString("placement"), o.optLong("savedAt"), o.optLong("card"))
        }
    }

    private fun write(context: Context, orbitId: String, entries: List<Entry>) {
        store(context, orbitId).write(JSONObject().put("entries", JSONArray().apply {
            entries.forEach {
                put(JSONObject().put("id", it.id).put("kind", it.kind).put("site", it.site).put("username", it.username)
                    .put("secret", it.secret).put("apiHosts", JSONArray(it.apiHosts)).put("placement", it.placement)
                    .put("savedAt", it.savedAt).put("card", it.card))
            }
        }).toString())
    }

    /**
     * Saves what the user typed. A second login for the same site and user name replaces the first,
     * except a new password: a change-password form still asks for the old one, so it stays until
     * Orbit deletes it.
     */
    suspend fun save(context: Context, orbitId: String, request: Request, username: String, secret: String): String =
        withContext(Dispatchers.IO) {
            require(secret.isNotEmpty() && secret.length <= 4000) { "Empty value" }
            lock(orbitId).withLock {
                val current = read(context, orbitId)
                val name = username.trim().take(200)
                val same = if (request.newPassword) null else current.firstOrNull {
                    it.kind == request.kind && it.site == request.site && (request.kind == API_KEY || it.username == name)
                }
                val entry = Entry(same?.id ?: UUID.randomUUID().toString().take(8), request.kind, request.site,
                    name, secret, request.apiHosts, request.placement, System.currentTimeMillis(),
                    card = if (request.newPassword) request.requestedAt else 0)
                val next = current.filterNot { it.id == entry.id } + entry
                require(next.size <= MAX_ENTRIES) { "The vault is full" }
                write(context, orbitId, next)
                changes.value++
                entry.id
            }
        }

    suspend fun delete(context: Context, orbitId: String, id: String): Boolean = withContext(Dispatchers.IO) {
        lock(orbitId).withLock {
            val current = read(context, orbitId)
            if (current.none { it.id == id }) return@withLock false
            write(context, orbitId, current.filterNot { it.id == id })
            changes.value++
            true
        }
    }

    /** A tap on a card in the chat: shows its entry sheet. */
    fun open(request: Request) {
        pending.value = request
    }

    /** The sheet closed, saved or not. */
    fun close(request: Request) {
        pending.compareAndSet(request, null)
    }

    /** True when a login for [site], or the key for that provider, is already saved. */
    fun has(entries: List<Entry>, kind: String, site: String): Boolean =
        entries.any { it.kind == kind && it.site.equals(site, true) }

    /** True when this new-password card itself was filled in (and that password is still saved). */
    fun newPasswordSaved(entries: List<Entry>, request: Request): Boolean = request.requestedAt > 0 &&
        entries.any { it.kind == LOGIN && it.site.equals(request.site, true) && it.card == request.requestedAt }

    /**
     * After a new password [saved] for a user: the password saved before any new-password card (the
     * newest such), and the earlier new passwords for that user. Whether the site took one of those
     * is known only from what the site said, so none of them is called current.
     */
    fun earlierPasswords(entries: List<Entry>, saved: Entry): Pair<Entry?, List<Entry>> {
        val others = entries.filter { it.id != saved.id && it.kind == LOGIN && it.site == saved.site && it.username == saved.username }
        val current = others.filter { it.card == 0L }.maxByOrNull { it.savedAt }
        return current to others.filter { it.card != 0L }.sortedBy { it.savedAt }
    }

    /** True when [text] carries any saved value, so it must not be sent or stored. */
    suspend fun containsSecret(context: Context, orbitId: String, text: String): Boolean =
        entries(context, orbitId).any { it.secret.length >= 4 && text.contains(it.secret) }

    /** Masks each value and the escaped forms a page or snapshot may show it in. */
    fun redact(text: String, secrets: Collection<String>): String =
        secrets.filter { it.isNotEmpty() }.flatMap(::variants).distinct().sortedByDescending { it.length }
            .fold(text) { acc, s -> acc.replace(s, "••••") }

    /**
     * [redact] for raw bytes (a response of any type): each form of a value is masked where it appears.
     * Masking a very short value can make the bytes grow; past [maxSize] it stops with [TooLarge].
     */
    fun redact(bytes: ByteArray, secrets: Collection<String>, maxSize: Int = Int.MAX_VALUE): ByteArray {
        val mask = "••••".toByteArray(Charsets.UTF_8)
        return secrets.filter { it.isNotEmpty() }.flatMap(::variants).distinct().sortedByDescending { it.length }
            .map { it.toByteArray(Charsets.UTF_8) }.fold(bytes) { acc, needle -> replaceBytes(acc, needle, mask, maxSize) }
    }

    class TooLarge : IllegalStateException("Too large after masking")

    private fun replaceBytes(source: ByteArray, needle: ByteArray, replacement: ByteArray, maxSize: Int): ByteArray {
        if (needle.isEmpty() || source.size < needle.size) return source
        var out: java.io.ByteArrayOutputStream? = null
        var copied = 0
        var i = 0
        val last = source.size - needle.size
        while (i <= last) {
            var match = source[i] == needle[0]
            var j = 1
            while (match && j < needle.size) { match = source[i + j] == needle[j]; j++ }
            if (match) {
                val o = out ?: java.io.ByteArrayOutputStream(source.size).also { out = it }
                o.write(source, copied, i - copied)
                o.write(replacement)
                if (o.size() > maxSize) throw TooLarge()
                i += needle.size
                copied = i
            } else i++
        }
        val o = out ?: return source
        o.write(source, copied, source.size - copied)
        if (o.size() > maxSize) throw TooLarge()
        return o.toByteArray()
    }

    private fun variants(secret: String): List<String> = listOfNotNull(
        secret,
        runCatching { java.net.URLEncoder.encode(secret, "UTF-8") }.getOrNull(),
        runCatching { java.net.URLEncoder.encode(secret, "UTF-8").replace("+", "%20") }.getOrNull(),
        secret.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"),
        JSONObject.quote(secret).let { it.substring(1, it.length - 1) },
        secret.trim().replace(Regex("\\s+"), " "),
    ).filter { it.length >= minOf(4, secret.length) && it.isNotEmpty() }
}
