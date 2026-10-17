package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Muse's Secure Vault for one Orbit: saved logins and API keys, kept encrypted outside backups.
 * Values are typed by the user into a secure entry sheet and go straight into the store; Orbit only
 * ever sees the site, the field names and a masked user name. Using a value happens on the host,
 * which fills it into a page of the same site or adds it to a request for one of its hosts.
 */
internal object OrbitVault {
    const val LOGIN = "login"
    const val API_KEY = "api_key"
    const val MAX_ENTRIES = 100
    private const val WAIT_MS = 5 * 60_000L

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
    )

    /** One secure entry sheet waiting for the user. The value never passes through the request. */
    class Request(
        val orbitId: String,
        val kind: String,
        val site: String,
        val pageUrl: String,
        val usernameHint: String,
        val apiHosts: List<String>,
        val placement: String,
    ) {
        internal val result = CompletableDeferred<String?>()
        /** Set when the user did not answer in time, so the sheet can say why it closed. */
        @Volatile var expired = false
            internal set
    }

    private val pending = MutableStateFlow<Request?>(null)
    /** The sheet the Orbit page shows. Only one at a time, like Muse's one card per turn. */
    val request: StateFlow<Request?> = pending
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
                o.optString("placement"), o.optLong("savedAt"))
        }
    }

    private fun write(context: Context, orbitId: String, entries: List<Entry>) {
        store(context, orbitId).write(JSONObject().put("entries", JSONArray().apply {
            entries.forEach {
                put(JSONObject().put("id", it.id).put("kind", it.kind).put("site", it.site).put("username", it.username)
                    .put("secret", it.secret).put("apiHosts", JSONArray(it.apiHosts)).put("placement", it.placement)
                    .put("savedAt", it.savedAt))
            }
        }).toString())
    }

    /** Saves what the user typed. A second login for the same site and user name replaces the first. */
    suspend fun save(context: Context, orbitId: String, request: Request, username: String, secret: String): String =
        withContext(Dispatchers.IO) {
            require(secret.isNotEmpty() && secret.length <= 4000) { "Empty value" }
            lock(orbitId).withLock {
                val current = read(context, orbitId)
                val name = username.trim().take(200)
                val same = current.firstOrNull {
                    it.kind == request.kind && it.site == request.site && (request.kind == API_KEY || it.username == name)
                }
                val entry = Entry(same?.id ?: UUID.randomUUID().toString().take(8), request.kind, request.site,
                    name, secret, request.apiHosts, request.placement, System.currentTimeMillis())
                val next = current.filterNot { it.id == entry.id } + entry
                require(next.size <= MAX_ENTRIES) { "The vault is full" }
                write(context, orbitId, next)
                entry.id
            }
        }

    suspend fun delete(context: Context, orbitId: String, id: String): Boolean = withContext(Dispatchers.IO) {
        lock(orbitId).withLock {
            val current = read(context, orbitId)
            if (current.none { it.id == id }) return@withLock false
            write(context, orbitId, current.filterNot { it.id == id })
            true
        }
    }

    /**
     * Shows the secure entry sheet on the Orbit page and waits for the user. Returns the saved
     * entry's id, or null when the user closed the sheet or did not answer within five minutes.
     */
    suspend fun ask(request: Request): String? {
        // A newer card replaces an older one the user walked away from, so a turn is never stuck behind it.
        pending.value?.let { finish(it, null) }
        check(pending.compareAndSet(null, request)) { "Another secure entry card is already waiting for the user" }
        return try {
            withTimeoutOrNull(WAIT_MS) { request.result.await() }.also {
                if (!request.result.isCompleted) request.expired = true
            }
        } finally {
            pending.compareAndSet(request, null)
        }
    }

    /** Called by the sheet: [id] of the saved entry, or null when the user closed it. */
    fun finish(request: Request, id: String?) {
        request.result.complete(id)
        pending.compareAndSet(request, null)
    }

    /** True when [text] carries any saved value, so it must not be sent or stored. */
    suspend fun containsSecret(context: Context, orbitId: String, text: String): Boolean =
        entries(context, orbitId).any { it.secret.length >= 4 && text.contains(it.secret) }

    /** Masks each value and the escaped forms a page or snapshot may show it in. */
    fun redact(text: String, secrets: Collection<String>): String =
        secrets.filter { it.isNotEmpty() }.flatMap(::variants).distinct().sortedByDescending { it.length }
            .fold(text) { acc, s -> acc.replace(s, "••••") }

    private fun variants(secret: String): List<String> = listOfNotNull(
        secret,
        runCatching { java.net.URLEncoder.encode(secret, "UTF-8") }.getOrNull(),
        runCatching { java.net.URLEncoder.encode(secret, "UTF-8").replace("+", "%20") }.getOrNull(),
        secret.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"),
        JSONObject.quote(secret).let { it.substring(1, it.length - 1) },
        secret.trim().replace(Regex("\\s+"), " "),
    ).filter { it.length >= minOf(4, secret.length) && it.isNotEmpty() }
}
