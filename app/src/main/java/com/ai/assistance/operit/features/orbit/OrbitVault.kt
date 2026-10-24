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
    const val OAUTH = "oauth"
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
        /**
         * The environment variable the user let Orbit load it into in the terminal, for uses the
         * proxy or the browser cannot cover (signing, SDKs, a script that logs in). A login also
         * gets its user name as `<name>_USERNAME`. Empty when it stays out of the terminal.
         */
        val envName: String = "",
        /**
         * API key: the variable name its card asked for, allowed or not. Two keys for one service
         * (an id and a secret) are told apart by it, so saving one never replaces the other.
         */
        val slot: String = "",
        val oauth: OAuth? = null,
        /**
         * API key: the tool package that gets it as the environment variable [slot] when Orbit runs
         * that package. Only that package sees it, and only in this Orbit's chats. Empty otherwise.
         */
        val packageName: String = "",
    )

    data class OAuth(
        val tokenUrl: String,
        val clientId: String,
        val scope: String,
        val refreshToken: String,
        /** Zero means the provider did not report an expiry. */
        val expiresAt: Long,
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
        /** API key: the variable Orbit asks to load the key into; the user decides on the card. */
        val envName: String = "",
        /** Why Orbit needs the key itself, shown next to that choice. */
        val envReason: String = "",
        val authorizationUrl: String = "",
        val tokenUrl: String = "",
        val clientId: String = "",
        val scope: String = "",
        /** An update card: the saved entry it changes. Empty for a card that adds one. */
        val updateId: String = "",
        /** API key: the tool package that gets it as [envName]; it stays out of the terminal. */
        val packageName: String = "",
    )

    /** A shell variable name: letters, digits and underscores, not starting with a digit. */
    val ENV_NAME = Regex("[A-Z_][A-Z0-9_]{1,63}")
    /** A tool package name, as the package manager writes it. */
    val PACKAGE_NAME = Regex("[A-Za-z0-9_.-]{1,80}")
    /**
     * Shortest value that may go into the terminal: tool output is masked by matching the value, and
     * a shorter one (a PIN) would match ordinary text everywhere, so it could not be masked.
     */
    const val MIN_ENV_SECRET = 6

    private val pending = MutableStateFlow<Request?>(null)
    /** The entry sheet the Orbit page shows, opened from a card in the chat. */
    val request: StateFlow<Request?> = pending
    private val changes = MutableStateFlow(0)
    /** Bumped on every save or delete, so cards and lists read the vault again. */
    val version: StateFlow<Int> = changes
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

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

    /** OAuth grants exactly the listed API hosts; existing password/key site matching stays compatible. */
    fun permitsApiHost(kind: String, host: String, allowed: List<String>): Boolean =
        allowed.any { if (kind == OAUTH) host.equals(it, ignoreCase = true) else matchesHost(host, it) }

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
                o.optString("placement"), o.optLong("savedAt"), o.optLong("card"), o.optString("envName"), o.optString("slot"),
                o.optJSONObject("oauth")?.let { auth ->
                    OAuth(auth.getString("tokenUrl"), auth.getString("clientId"), auth.optString("scope"),
                        auth.optString("refreshToken"), auth.optLong("expiresAt"))
                }, o.optString("packageName"))
        }
    }

    private fun write(context: Context, orbitId: String, entries: List<Entry>) {
        store(context, orbitId).write(JSONObject().put("entries", JSONArray().apply {
            entries.forEach {
                put(JSONObject().put("id", it.id).put("kind", it.kind).put("site", it.site).put("username", it.username)
                    .put("secret", it.secret).put("apiHosts", JSONArray(it.apiHosts)).put("placement", it.placement)
                    .put("savedAt", it.savedAt).put("card", it.card).put("envName", it.envName).put("slot", it.slot)
                    .apply { if (it.packageName.isNotEmpty()) put("packageName", it.packageName) }
                    .apply { it.oauth?.let { auth ->
                        put("oauth", JSONObject().put("tokenUrl", auth.tokenUrl).put("clientId", auth.clientId)
                            .put("scope", auth.scope).put("refreshToken", auth.refreshToken).put("expiresAt", auth.expiresAt))
                    } })
            }
        }).toString())
    }

    /**
     * Saves what the user typed. A second login for the same site and user name replaces the first,
     * except a new password: a change-password form still asks for the old one, so it stays until
     * Orbit deletes it. [allowEnv] is the user's choice on a key card that asked for a variable.
     */
    suspend fun save(context: Context, orbitId: String, request: Request, username: String, secret: String,
        allowEnv: Boolean = false): String =
        withContext(Dispatchers.IO) {
            require(secret.isNotEmpty() && secret.length <= 4000) { "Empty value" }
            // A package key is masked wherever it could turn up, which a very short one cannot be.
            require(request.packageName.isEmpty() || secret.length >= MIN_ENV_SECRET) { "Too short for a package key" }
            lock(orbitId).withLock {
                val current = read(context, orbitId)
                val name = username.trim().take(200)
                val same = if (request.newPassword) null else current.firstOrNull {
                    it.kind == request.kind && it.site == request.site &&
                        (if (request.kind == API_KEY) it.slot == request.envName && it.packageName == request.packageName
                            else it.username == name)
                }
                // A key card that asked for a variable decides it; any other card keeps what was allowed.
                // A package's key goes to that package only, never into the terminal.
                val envName = when {
                    request.packageName.isNotEmpty() -> ""
                    request.kind == API_KEY && request.envName.isNotEmpty() ->
                        if (allowEnv && request.envName.matches(ENV_NAME) && secret.length >= MIN_ENV_SECRET) request.envName else ""
                    else -> same?.envName.orEmpty().takeIf { secret.length >= MIN_ENV_SECRET }.orEmpty()
                }
                val entry = Entry(same?.id ?: UUID.randomUUID().toString().take(8), request.kind, request.site,
                    name, secret, if (request.packageName.isNotEmpty()) emptyList() else request.apiHosts,
                    request.placement, System.currentTimeMillis(),
                    card = if (request.newPassword) request.requestedAt else 0,
                    envName = envName,
                    slot = if (request.kind == API_KEY) request.envName else "",
                    packageName = if (request.kind == API_KEY) request.packageName else "")
                val next = current.filterNot { it.id == entry.id } + entry
                require(next.size <= MAX_ENTRIES) { "The vault is full" }
                write(context, orbitId, next)
                changes.value++
                entry.id
            }
        }

    /**
     * Changes a saved login or key from an update card: a new [secret] (null keeps it), a new user
     * name for a login, and whether it may be loaded into the terminal as [envName] (empty: not).
     * Returns the saved entry, or null when it was deleted meanwhile.
     */
    suspend fun update(context: Context, orbitId: String, id: String, username: String?, secret: String?,
        envName: String): Entry? = withContext(Dispatchers.IO) {
        require(secret == null || secret.isNotEmpty() && secret.length <= 4000) { "Empty value" }
        require(envName.isEmpty() || envName.matches(ENV_NAME)) { "Bad variable name" }
        lock(orbitId).withLock {
            val current = read(context, orbitId)
            val old = current.firstOrNull { it.id == id } ?: return@withLock null
            require(old.kind == LOGIN || old.kind == API_KEY) { "Only logins and API keys can be updated" }
            require(envName.isEmpty() || old.packageName.isEmpty()) { "A package's key stays out of the terminal" }
            require(envName.isEmpty() || (secret ?: old.secret).length >= MIN_ENV_SECRET) { "Too short for the terminal" }
            require(old.packageName.isEmpty() || (secret ?: old.secret).length >= MIN_ENV_SECRET) { "Too short for a package key" }
            val updated = old.copy(
                username = if (old.kind == LOGIN && username != null) username.trim().take(200) else old.username,
                secret = secret ?: old.secret,
                envName = envName,
                savedAt = System.currentTimeMillis(),
            )
            write(context, orbitId, current.map { if (it.id == id) updated else it })
            changes.value++
            updated
        }
    }

    /** A variable name for an entry that never asked for one, such as GITHUB_COM_PASSWORD. */
    fun defaultEnvName(entry: Entry): String {
        val base = entry.site.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_').take(40)
            .let { if (it.isEmpty() || it[0].isDigit()) "X_$it" else it }
        return base + if (entry.kind == LOGIN) "_PASSWORD" else "_API_KEY"
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

    suspend fun saveOAuth(context: Context, request: Request, tokens: OrbitOAuthProtocol.Tokens): String =
        withContext(Dispatchers.IO) {
            require(request.kind == OAUTH)
            lock(request.orbitId).withLock {
                val current = read(context, request.orbitId)
                val same = current.firstOrNull {
                    it.kind == OAUTH && it.site == request.site && it.oauth?.clientId == request.clientId
                }
                val entry = Entry(same?.id ?: UUID.randomUUID().toString().take(8), OAUTH, request.site,
                    "", tokens.accessToken, request.apiHosts, "bearer", System.currentTimeMillis(),
                    card = request.requestedAt,
                    oauth = OAuth(request.tokenUrl, request.clientId, tokens.scope ?: request.scope,
                        tokens.refreshToken.orEmpty(), tokens.expiresAt))
                val next = current.filterNot { it.id == entry.id } + entry
                require(next.size <= MAX_ENTRIES) { "The vault is full" }
                write(context, request.orbitId, next)
                changes.value++
                entry.id
            }
        }

    /** One rotation per credential. Network waits never hold the vault's read/save/delete lock. */
    suspend fun forHttp(context: Context, orbitId: String, id: String, rejectedToken: String? = null): Entry = withContext(Dispatchers.IO) {
        refreshLocks.computeIfAbsent("$orbitId/$id") { Mutex() }.withLock {
            val entry = lock(orbitId).withLock { read(context, orbitId).firstOrNull { it.id == id } }
                ?: error("Credential was deleted; use list")
            val auth = entry.oauth ?: return@withLock entry
            // A 401 can reveal expiry when a provider did not give expires_in. Another caller may already have refreshed it.
            if (rejectedToken != null && rejectedToken != entry.secret) return@withLock entry
            if (rejectedToken == null && !OrbitOAuthProtocol.needsRefresh(auth.expiresAt)) return@withLock entry
            check(auth.refreshToken.isNotEmpty()) {
                "OAuth login expired and the provider supplied no refresh token. Use request_oauth to reconnect."
            }
            val tokens = OrbitOAuth.refresh(auth)
            // The provider may have rotated its refresh token already; finish saving even if the API task was cancelled.
            withContext(kotlinx.coroutines.NonCancellable) {
                lock(orbitId).withLock update@ {
                    val current = read(context, orbitId)
                    val latest = current.firstOrNull { it.id == id } ?: error("Credential was deleted; use list")
                    // Deletion or a new user authorization wins over an older refresh response.
                    if (latest != entry) return@update latest
                    val updated = entry.copy(secret = tokens.accessToken, oauth = auth.copy(
                        refreshToken = tokens.refreshToken ?: auth.refreshToken,
                        scope = tokens.scope ?: auth.scope, expiresAt = tokens.expiresAt))
                    write(context, orbitId, current.map { if (it.id == id) updated else it })
                    changes.value++
                    updated
                }
            }
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

    /** True when a login for [site], or that provider's key for [slot] (its variable name), is already saved. */
    fun has(entries: List<Entry>, kind: String, site: String, slot: String = "", packageName: String = ""): Boolean =
        entries.any { it.kind == kind && it.site.equals(site, true) &&
            (kind != API_KEY || it.slot == slot && it.packageName == packageName) }

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
        entries(context, orbitId).any { e ->
            listOf(e.secret, e.oauth?.refreshToken.orEmpty()).any { it.length >= 4 && text.contains(it) }
        }

    private class EnvCache(val version: Int, val secrets: List<String>)
    private val envCache = ConcurrentHashMap<String, EnvCache>()
    private class PackageEnvCache(val version: Int, val byPackage: Map<String, Map<String, String>>)
    private val packageEnvCache = ConcurrentHashMap<String, PackageEnvCache>()

    /** Drops what is held in memory for [orbitId], once its vault file is gone with the Orbit. */
    fun forgetCached(orbitId: String) {
        envCache.remove(orbitId)
        packageEnvCache.remove(orbitId)
        changes.value++
    }

    /**
     * The keys saved for tool package [packageName], by variable name, which the package reads as
     * its environment when this Orbit runs it. Kept in memory until the vault changes.
     */
    suspend fun packageEnv(context: Context, orbitId: String, packageName: String): Map<String, String> {
        val now = changes.value
        val all = packageEnvCache[orbitId]?.takeIf { it.version == now }?.byPackage
            ?: entries(context, orbitId).filter { it.kind == API_KEY && it.packageName.isNotEmpty() && it.slot.isNotEmpty() }
                .groupBy { it.packageName }
                .mapValues { (_, list) -> list.sortedBy { it.savedAt }.associate { it.slot to it.secret } }
                .also {
                    // Values from before a change are never read again; do not keep them around.
                    packageEnvCache.entries.removeIf { e -> e.value.version != now }
                    packageEnvCache[orbitId] = PackageEnvCache(now, it)
                }
        return all[packageName].orEmpty()
    }

    /**
     * The keys the user let into the terminal, which can turn up in a command's output; a tool's
     * output is masked with [redact] before Orbit reads it. A soft guard against seeing one by accident.
     */
    suspend fun envKeys(context: Context, orbitId: String): List<String> {
        val now = changes.value
        return envCache[orbitId]?.takeIf { it.version == now }?.secrets
            // A package's key can show up in that package's output too.
            ?: entries(context, orbitId).filter { it.envName.isNotEmpty() || it.packageName.isNotEmpty() }
                // A login's user name goes into the terminal with it; it is masked too when long enough.
                .flatMap { listOf(it.secret) + if (it.kind == LOGIN) listOf(it.username) else emptyList() }
                .filter { it.length >= MIN_ENV_SECRET }.distinct()
                .also { envCache[orbitId] = EnvCache(now, it) }
    }

    /** Masks each value and the escaped forms a page or snapshot may show it in. */
    fun redact(text: String, secrets: Collection<String>, maxChars: Int = Int.MAX_VALUE): String {
        val forms = secrets.filter { it.isNotEmpty() }.flatMap(::variants).distinct().sortedByDescending { it.length }
        // Keep enough lookahead to mask a value crossing the display boundary, without scanning a whole large API response.
        val input = text.take(minOf(text.length.toLong(), maxChars.toLong() + (forms.firstOrNull()?.length ?: 0)).toInt())
        return forms.fold(input) { acc, s -> acc.replace(s, "••••") }.take(maxChars)
    }

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
        // How the terminal proxy writes a key into a URL path.
        OrbitVaultBroker.encodePathSegment(secret),
        secret.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"),
        JSONObject.quote(secret).let { it.substring(1, it.length - 1) },
        secret.trim().replace(Regex("\\s+"), " "),
    ).filter { it.length >= minOf(4, secret.length) && it.isNotEmpty() }
}
