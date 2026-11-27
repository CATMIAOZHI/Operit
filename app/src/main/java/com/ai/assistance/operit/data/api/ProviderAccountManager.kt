package com.ai.assistance.operit.data.api

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.ui.features.codex.CodexOAuthLoopbackCallbackServer
import com.ai.assistance.operit.util.AppLogger
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** [oauthError] is the OAuth `error` code from a token endpoint's refusal, when it sent one. */
internal class AccountHttpException(val status: Int, val oauthError: String? = null) :
    IOException("Account request failed: HTTP $status" + (oauthError?.let { " ($it)" } ?: "")) {
    /** The refresh grant itself was revoked or spent, so only a new login can recover. */
    val loginRevoked: Boolean
        get() = status in setOf(400, 401) && oauthError in setOf("invalid_grant", "refresh_token_reused", "revoked_token")
}

/** The server answered but its body was missing, cut off or not JSON; it may have acted on the request. */
internal class AccountResponseException(message: String, cause: Throwable? = null) : IOException(message, cause)

enum class AccountProvider(val type: ApiProviderType, val port: Int) {
    GROK(ApiProviderType.GROK_ACCOUNT, 56121),
    COMMAND_CODE(ApiProviderType.COMMAND_CODE, 5959),
    ANTIGRAVITY(ApiProviderType.GOOGLE_ANTIGRAVITY, 51121),
    CLAUDE(ApiProviderType.CLAUDE_ACCOUNT, ClaudeOAuthProtocol.CALLBACK_PORT);

    companion object {
        fun from(type: ApiProviderType?) = entries.firstOrNull { it.type == type }
    }
}

data class ProviderAccount(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val projectId: String = "",
    val email: String = "",
    val identity: String = UUID.randomUUID().toString(),
    /** The server revoked this sign-in; it is skipped until the account is signed in again. */
    val needsLogin: Boolean = false,
    /** The provider's stable id for this account, so signing it in again replaces its row. */
    val subject: String = "",
    /** The OAuth client this account signed in with (Antigravity); its refresh grant only works with it. */
    val clientId: String = "",
    val clientSecret: String = "",
)

internal data class ProviderLoginSession(
    val server: CodexOAuthLoopbackCallbackServer,
    val authorizationUrl: String,
    val state: String,
    val verifier: String,
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String,
)

/** Account credentials never share the API-key configuration or its export format. */
class ProviderAccountManager private constructor(context: Context, val provider: AccountProvider) {
    private val appContext = context.applicationContext
    private val storeName = "provider_oauth_${provider.name.lowercase()}"
    private fun createPreferences() = EncryptedSharedPreferences.create(
        appContext, storeName,
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private var preferences = try { createPreferences() } catch (_: java.security.GeneralSecurityException) {
        // Restoring Android backup cannot restore the device-bound Keystore key.
        appContext.deleteSharedPreferences(storeName)
        createPreferences()
    }
    private val mutex = Mutex()

    /** Every signed-in account in the order added, and which one requests use. */
    private data class Pool(val accounts: List<ProviderAccount>, val activeId: String?) {
        fun active() = accounts.firstOrNull { it.identity == activeId }
    }
    private val stateLock = Any()
    private val resting = RestingAccounts()
    private var pool = readPool()
    private val mutableAccounts = MutableStateFlow(pool.accounts)
    private val mutableAccount = MutableStateFlow(pool.active())

    private val familiesLock = Any()
    /** Each account's Antigravity tier families once read, kept in memory; a null value: none saved. */
    private val familiesByAccount = HashMap<String, Map<String, Set<String>>?>()

    /**
     * The tier families [identity]'s model list last showed, as saved, so a request after a restart
     * still picks a tier; null when that account's list was never read. Never another account's.
     */
    suspend fun antigravityFamilies(identity: String): Map<String, Set<String>>? = withContext(Dispatchers.IO) {
        synchronized(familiesLock) {
            if (familiesByAccount.containsKey(identity)) return@synchronized familiesByAccount[identity]
            val saved = runCatching {
                (preferences.getString("$FAMILIES_KEY:$identity", null)
                    ?: preferences.getString(FAMILIES_KEY, null))?.let(::decodeFamilies)
                    ?.takeIf { it.first == identity }?.second
            }.getOrNull()
            familiesByAccount[identity] = saved
            saved
        }
    }

    /** Shows the signed-in account's families (for the chat's thinking menu), read on a background thread. */
    private suspend fun publishAntigravityFamilies(identity: String?) {
        val families = identity?.let { antigravityFamilies(it) }
        synchronized(familiesLock) {
            // Published under the lock with the account checked, so an older read never replaces a newer one.
            if (account.value?.identity != identity) return
            com.ai.assistance.operit.api.chat.llmprovider.AntigravityFamilies.publish(identity?.let { familiesByAccount[it] } ?: families)
        }
    }

    fun accountChangedMessage(): String =
        appContext.getString(com.ai.assistance.operit.R.string.provider_account_changed_reload)

    /**
     * Keeps [families] from [identity]'s model list; false when that account is no longer the one
     * in use (the list then belongs to another account than the one shown), or was removed.
     */
    suspend fun rememberAntigravityFamilies(identity: String, families: Map<String, Set<String>>): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(familiesLock) {
                if (accounts.value.none { it.identity == identity }) return@synchronized false
                familiesByAccount[identity] = families
                val tiers = JSONObject().apply {
                    families.forEach { (base, set) -> put(base, org.json.JSONArray(set.toList())) }
                }
                preferences.edit().putString("$FAMILIES_KEY:$identity",
                    JSONObject().put("identity", identity).put("families", tiers).toString()).commit()
                if (account.value?.identity != identity) return@synchronized false
                com.ai.assistance.operit.api.chat.llmprovider.AntigravityFamilies.publish(families)
                true
            }
        }

    private fun decodeFamilies(raw: String): Pair<String, Map<String, Set<String>>> {
        val root = JSONObject(raw)
        val json = root.getJSONObject("families")
        return root.getString("identity") to json.keys().asSequence().associateWith { base ->
            val tiers = json.getJSONArray(base)
            (0 until tiers.length()).map { tiers.getString(it) }.toSet()
        }
    }
    /** The account requests use now; null when none is signed in. */
    val account = mutableAccount.asStateFlow()
    /** Every signed-in account, in the order they were added. */
    val accounts = mutableAccounts.asStateFlow()

    init {
        // The thinking menu shows the tier a request will use, so it follows the signed-in account.
        if (provider == AccountProvider.ANTIGRAVITY) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
                account.map { it?.identity }.distinctUntilChanged().collectLatest { publishAntigravityFamilies(it) }
            }
        }
    }
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    private fun readPool(): Pool {
        return try { readStoredPool() } catch (_: SecurityException) {
            appContext.deleteSharedPreferences(storeName)
            preferences = createPreferences()
            Pool(emptyList(), null)
        } catch (_: JSONException) {
            preferences.edit().remove(ACCOUNTS_KEY).remove(ACTIVE_KEY).remove(LEGACY_ACCOUNT_KEY).apply()
            Pool(emptyList(), null)
        }
    }

    private fun readStoredPool(): Pool {
        val raw = preferences.getString(ACCOUNTS_KEY, null)
        if (raw != null) {
            val rows = org.json.JSONArray(raw)
            val list = (0 until rows.length()).map { decodeAccount(rows.getJSONObject(it)) }
            val active = preferences.getString(ACTIVE_KEY, null)?.takeIf { id -> list.any { it.identity == id } }
                ?: list.firstOrNull()?.identity
            return Pool(list, active)
        }
        // One account from before the pool: it becomes the first, and the old row goes once the pool is written.
        val legacy = preferences.getString(LEGACY_ACCOUNT_KEY, null) ?: return Pool(emptyList(), null)
        // Read only: the next write (on a background thread) saves the pool and drops the old row.
        val account = decodeAccount(JSONObject(legacy)).let {
            // It signed in with the client saved then, which a later account may replace.
            if (provider != AccountProvider.ANTIGRAVITY) it else it.copy(
                clientId = preferences.getString("client_id", "").orEmpty(),
                clientSecret = preferences.getString("client_secret", "").orEmpty(),
            )
        }
        return Pool(listOf(account), account.identity)
    }

    private fun decodeAccount(json: JSONObject) = ProviderAccount(
        json.getString("access"), json.getString("refresh"), json.getLong("expires"),
        json.optString("project"), json.optString("email"), json.getString("identity"),
        json.optBoolean("needs_login"), json.optString("subject"),
        json.optString("client_id"), json.optString("client_secret"),
    )

    private fun encodeAccount(value: ProviderAccount) = JSONObject().apply {
        put("access", value.accessToken); put("refresh", value.refreshToken)
        put("expires", value.expiresAt); put("project", value.projectId)
        put("email", value.email); put("identity", value.identity)
        if (value.needsLogin) put("needs_login", true)
        if (value.subject.isNotBlank()) put("subject", value.subject)
        if (value.clientId.isNotBlank()) put("client_id", value.clientId)
        if (value.clientSecret.isNotBlank()) put("client_secret", value.clientSecret)
    }

    private fun writePool(next: Pool): Boolean {
        val raw = org.json.JSONArray(next.accounts.map(::encodeAccount)).toString()
        fun write() = preferences.edit().putString(ACCOUNTS_KEY, raw).remove(LEGACY_ACCOUNT_KEY).apply {
            if (next.activeId == null) remove(ACTIVE_KEY) else putString(ACTIVE_KEY, next.activeId)
        }.commit()
        // commit: a rotated refresh token lost to a process kill would leave only the spent one.
        // If storage still refuses, the new grant stays in memory so the spent one is never sent.
        val written = write() || write()
        if (!written) AppLogger.e(TAG, "${provider.name} sign-in could not be written to storage; kept in memory only")
        return written
    }

    /** Saves and shows [next]; callers hold [stateLock]. */
    private fun commit(next: Pool) {
        if (next == pool) return
        writePool(next)
        pool = next
        mutableAccounts.value = next.accounts
        mutableAccount.value = next.active()
    }

    private fun usable(account: ProviderAccount) = !account.needsLogin && !resting.isResting(account.identity)

    /** The account after [identity] that can still answer, made the one in use; null when there is none. */
    private fun switchFrom(identity: String): ProviderAccount? {
        val current = pool
        val next = AccountPoolRules.nextUsable(current.accounts.map { it.identity }, identity) { id ->
            current.accounts.first { it.identity == id }.let(::usable)
        } ?: return null
        commit(current.copy(activeId = next))
        AppLogger.i(TAG, "${provider.name} switched to the next signed-in account")
        return current.accounts.first { it.identity == next }
    }

    /** A refreshed sign-in replaces its own row; one removed meanwhile stays removed. */
    private fun save(value: ProviderAccount) = synchronized(stateLock) {
        val current = pool
        if (current.accounts.none { it.identity == value.identity }) return@synchronized
        commit(current.copy(accounts = current.accounts.map { if (it.identity == value.identity) value else it }))
    }

    /**
     * A new sign-in joins the pool and is the one used; signing in an account already there (same
     * provider id, else same email) replaces its row and keeps its identity.
     */
    private fun addOrReplace(value: ProviderAccount): ProviderAccount = synchronized(stateLock) {
        val current = pool
        val same = value.subject.takeIf { it.isNotBlank() }?.let { id -> current.accounts.firstOrNull { it.subject == id } }
            ?: value.email.takeIf { it.isNotBlank() }?.let { email ->
                current.accounts.firstOrNull {
                    it.email.equals(email, ignoreCase = true) &&
                        (it.subject.isBlank() || value.subject.isBlank())
                }
            }
        val stored = if (same != null) value.copy(identity = same.identity, needsLogin = false) else value
        val list = if (same != null) current.accounts.map { if (it.identity == same.identity) stored else it }
            else current.accounts + stored
        resting.clear(stored.identity)
        commit(Pool(list, stored.identity))
        stored
    }

    /** Makes [identity] the account requests use, as the user picked it. */
    suspend fun setActive(identity: String) = withContext(Dispatchers.IO) {
        synchronized(stateLock) {
            val current = pool
            if (current.accounts.none { it.identity == identity } || current.activeId == identity) return@synchronized
            resting.clear(identity)
            commit(current.copy(activeId = identity))
        }
    }

    /** Signs [identity] out of this app; the next account that can answer takes over when it was in use. */
    suspend fun remove(identity: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            synchronized(stateLock) {
                val current = pool
                if (current.accounts.none { it.identity == identity }) return@synchronized
                val left = current.accounts.filter { it.identity != identity }
                val active = if (current.activeId != identity) current.activeId else {
                    AccountPoolRules.nextUsable(current.accounts.map { it.identity }, identity) { id ->
                        left.first { it.identity == id }.let(::usable)
                    } ?: left.firstOrNull()?.identity
                }
                resting.clear(identity)
                commit(Pool(left, active))
            }
            synchronized(familiesLock) { familiesByAccount.remove(identity) }
            preferences.edit().remove("$FAMILIES_KEY:$identity").apply()
        }
    }

    /** Signs out the account in use. */
    suspend fun logout() {
        account.value?.identity?.let { remove(it) }
    }

    /** Accounts that can answer a request now, for the retry notice; at least one while any is signed in. */
    fun usableAccountCount(): Int = synchronized(stateLock) {
        if (pool.accounts.isEmpty()) 0 else pool.accounts.count(::usable).coerceAtLeast(1)
    }

    /** The short chat note for having moved to [account]. */
    fun switchedNotice(account: ProviderAccount): String {
        val label = account.email.ifBlank {
            appContext.getString(com.ai.assistance.operit.R.string.provider_account_unnamed,
                accounts.value.indexOfFirst { it.identity == account.identity } + 1)
        }
        return appContext.getString(com.ai.assistance.operit.R.string.provider_account_pool_switched, label)
    }

    /**
     * A server refused the account [request] was signed with: a refused token (401) is refreshed
     * before its next use, a spent quota or rate (402, 429) rests the account, and the next
     * account that can answer takes over. Command Code keys cannot be refreshed, so a 401 there
     * means the key is gone. Requests signed with a token no longer stored change nothing.
     */
    internal val refusalSink = AccountRefusalSink { request, status ->
        val token = request.signedToken() ?: return@AccountRefusalSink
        synchronized(stateLock) {
            val current = pool
            val used = current.accounts.firstOrNull { it.accessToken == token } ?: return@AccountRefusalSink
            if (status == 401 && provider != AccountProvider.COMMAND_CODE) {
                commit(current.copy(accounts = current.accounts.map {
                    if (it.identity == used.identity) it.copy(expiresAt = 0) else it
                }))
                return@AccountRefusalSink
            }
            if (status == 401) {
                commit(current.copy(accounts = current.accounts.map {
                    if (it.identity == used.identity) it.copy(needsLogin = true) else it
                }))
            } else {
                resting.rest(used.identity)
            }
            if (pool.activeId == used.identity) switchFrom(used.identity)
        }
    }

    private val pooledClients = PooledClients(refusalSink)

    /** [base] for chat requests, also telling this pool which account a server refused. */
    fun requestClient(base: OkHttpClient): OkHttpClient = pooledClients.around(base)

    /** Marks [identity]'s sign-in as revoked; true when another account is now in use. */
    private fun loginRevoked(identity: String): Boolean = synchronized(stateLock) {
        val current = pool
        commit(current.copy(accounts = current.accounts.map {
            if (it.identity == identity) it.copy(needsLogin = true) else it
        }))
        if (pool.activeId != identity) return@synchronized pool.active() != null
        switchFrom(identity) != null
    }

    internal suspend fun startLogin(
        googleClientId: String = "",
        googleClientSecret: String = "",
    ): ProviderLoginSession {
        check(provider != AccountProvider.COMMAND_CODE) { "Use Command Code login" }
        val discovery = if (provider == AccountProvider.GROK) discoverXai() else null
        val id = when (provider) {
            AccountProvider.GROK -> XAI_CLIENT_ID
            AccountProvider.CLAUDE -> ClaudeOAuthProtocol.CLIENT_ID
            else -> googleClientId.trim().ifBlank { preferences.getString("client_id", "").orEmpty() }
                .ifBlank { AntigravityClientConfig.CLIENT_ID }
        }
        val secret = if (provider == AccountProvider.ANTIGRAVITY) {
            googleClientSecret.ifBlank { preferences.getString("client_secret", "").orEmpty() }
                .ifBlank { AntigravityClientConfig.CLIENT_SECRET }
        } else ""
        require(id.isNotBlank() && (provider != AccountProvider.ANTIGRAVITY || secret.isNotBlank())) {
            "Antigravity OAuth client ID and client secret are required"
        }
        val pkce = CodexOAuthProtocol.generatePkce()
        val state = CodexOAuthProtocol.generateState()
        // Claude's OAuth app registers the `localhost` spelling of its callback, so that is the one
        // the redirect has to advertise; the listener still binds loopback.
        val callbackHost =
            if (provider == AccountProvider.CLAUDE) ClaudeOAuthProtocol.CALLBACK_HOST else "127.0.0.1"
        val server = CodexOAuthLoopbackCallbackServer.open(provider.port, "/callback", callbackHost)
        try {
            val url = if (provider == AccountProvider.CLAUDE) {
                ClaudeOAuthProtocol.buildAuthorizationUrl(
                    redirectUri = server.redirectUri,
                    challenge = pkce.challenge,
                    state = state,
                )
            } else {
                val endpoint = discovery?.first ?: "https://accounts.google.com/o/oauth2/v2/auth"
                Uri.parse(endpoint).buildUpon()
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("client_id", id)
                    .appendQueryParameter("redirect_uri", server.redirectUri)
                    .appendQueryParameter("scope", if (provider == AccountProvider.GROK) XAI_SCOPE else GOOGLE_SCOPE)
                    .appendQueryParameter("code_challenge", pkce.challenge)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("state", state)
                    .apply {
                        if (provider == AccountProvider.GROK) appendQueryParameter("nonce", UUID.randomUUID().toString())
                        else {
                            appendQueryParameter("access_type", "offline")
                            appendQueryParameter("prompt", "consent select_account")
                        }
                    }.build().toString()
            }
            val tokenEndpoint = when (provider) {
                AccountProvider.CLAUDE -> ClaudeOAuthProtocol.TOKEN_ENDPOINT
                else -> discovery?.second ?: GOOGLE_TOKEN
            }
            return ProviderLoginSession(server, url, state, pkce.verifier, tokenEndpoint, id, secret)
        } catch (error: Throwable) {
            server.close()
            throw error
        }
    }

    internal suspend fun completeLogin(session: ProviderLoginSession, callback: Uri) = mutex.withLock {
        // Claude's callback can fold the state into the code as `code#state`; the others carry it as
        // a query parameter, so their codes are taken verbatim and the state → declined → code check
        // order below stays exactly as it was.
        val callbackCode = callback.getQueryParameter("code").orEmpty()
        val callbackState = callback.getQueryParameter("state").orEmpty()
        val (code, state) =
            if (provider == AccountProvider.CLAUDE) {
                ClaudeOAuthProtocol.splitAuthorizationCode(callbackCode, callbackState)
            } else {
                callbackCode to callbackState
            }
        require(state == session.state) { "OAuth state mismatch" }
        check(callback.getQueryParameter("error") == null) { "OAuth authorization was declined" }
        require(code.isNotBlank()) { "Missing OAuth authorization code" }
        val tokens = if (provider == AccountProvider.CLAUDE) {
            requestJson(
                session.tokenEndpoint,
                ClaudeOAuthProtocol.authorizationCodeBody(
                    code = code,
                    state = state,
                    redirectUri = session.server.redirectUri,
                    verifier = session.verifier,
                ).toBody(),
            )
        } else {
            val form = tokenForm(session.clientId, session.clientSecret).apply {
                add("grant_type", "authorization_code"); add("code", code)
                add("redirect_uri", session.server.redirectUri); add("code_verifier", session.verifier)
            }.build()
            requestJson(session.tokenEndpoint, form)
        }
        val value = parseTokens(tokens)
        val complete = if (provider == AccountProvider.ANTIGRAVITY) {
            // Google may answer without an ID token; its user info then names the account.
            val named = if (value.subject.isNotBlank() && value.email.isNotBlank()) value else {
                val info = try {
                    requestJson(GOOGLE_USERINFO, token = value.accessToken)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                value.copy(
                    subject = value.subject.ifBlank { info?.optString("id").orEmpty().trim() },
                    email = value.email.ifBlank { info?.optString("email").orEmpty().trim() },
                )
            }
            named.copy(projectId = discoverProject(value.accessToken),
                clientId = session.clientId, clientSecret = session.clientSecret)
        } else value
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) {
            preferences.edit().putString("client_id", session.clientId)
                .putString("client_secret", session.clientSecret).apply()
            addOrReplace(complete)
        }
    }

    suspend fun validAccount(): ProviderAccount = takeAccount().first

    /**
     * The account to sign a request with, refreshed, and whether this call itself moved to it from
     * the account in use before (so the chat can be told).
     */
    internal suspend fun takeAccount(): Pair<ProviderAccount, Boolean> = mutex.withLock {
        var moved = false
        while (true) {
            val current = account.value ?: throw IOException("Account is not logged in")
            // An account resting after a refusal, or whose sign-in was revoked, gives way to one
            // that can answer now, when there is one. Checked again under the lock: the user may
            // have picked another account meanwhile, which is then used as picked.
            if (!usable(current)) {
                val step = withContext(Dispatchers.IO) {
                    synchronized(stateLock) {
                        when {
                            pool.activeId != current.identity -> 0
                            !usable(pool.active() ?: current) -> if (switchFrom(current.identity) != null) 1 else -1
                            else -> -1
                        }
                    }
                }
                if (step == 1) moved = true
                if (step >= 0) continue
            }
            // Nothing else can answer: a revoked sign-in is not sent again; it needs a new login.
            if (current.needsLogin) throw AccountLoginExpiredException(loginExpiredMessage())
            if (provider == AccountProvider.COMMAND_CODE) return@withLock current to moved
            if (current.expiresAt - System.currentTimeMillis() > 120_000) return@withLock current to moved
            try {
                return@withLock refresh(current) to moved
            } catch (error: AccountHttpException) {
                if (!error.loginRevoked) throw error
                // This sign-in is over; the next account that still works answers instead.
                if (!withContext(Dispatchers.IO) { loginRevoked(current.identity) }) {
                    throw AccountLoginExpiredException(loginExpiredMessage())
                }
                moved = true
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    internal fun loginExpiredMessage() =
        appContext.getString(com.ai.assistance.operit.R.string.provider_account_login_expired)

    private suspend fun refresh(current: ProviderAccount): ProviderAccount {
        val endpoint = when (provider) {
            AccountProvider.GROK -> retryTransient { discoverXai() }.second
            AccountProvider.CLAUDE -> ClaudeOAuthProtocol.TOKEN_ENDPOINT
            else -> GOOGLE_TOKEN
        }
        val body = if (provider == AccountProvider.CLAUDE) {
            ClaudeOAuthProtocol.refreshBody(current.refreshToken).toBody()
        } else {
            // Antigravity accounts refresh with the OAuth client they signed in with.
            val clientId = current.clientId.ifBlank { preferences.getString("client_id", "").orEmpty() }
            val clientSecret = current.clientSecret.ifBlank { preferences.getString("client_secret", "").orEmpty() }
            tokenForm(
                if (provider == AccountProvider.GROK) XAI_CLIENT_ID else clientId,
                if (provider == AccountProvider.GROK) "" else clientSecret,
            ).add("grant_type", "refresh_token").add("refresh_token", current.refreshToken).build()
        }
        val updated = withContext(NonCancellable + Dispatchers.IO) {
            // Refresh grants can rotate. Persist the successful response even if the caller stops.
            val tokens = try {
                if (provider == AccountProvider.GROK) retryTransient { requestJson(endpoint, body) }
                else requestJson(endpoint, body)
            } catch (error: AccountHttpException) {
                AppLogger.w(TAG, "${provider.name} token refresh refused: HTTP ${error.status} error=${error.oauthError}")
                throw error
            }
            parseTokens(tokens, current).also(::save)
        }
        currentCoroutineContext().ensureActive()
        return updated
    }

    /**
     * Up to three tries for a failed connection, 429 or 5xx, as the Grok CLI does; anything else is
     * final. A timeout or a broken answer is final too: the server may already have rotated the
     * refresh grant, and sending the old one again would get the whole login revoked.
     */
    private suspend fun <T> retryTransient(block: suspend () -> T): T {
        for (attempt in 1..2) {
            try {
                return block()
            } catch (error: AccountHttpException) {
                if (error.status != 429 && error.status < 500) throw error
            } catch (error: IOException) {
                if (error is java.io.InterruptedIOException || error is AccountResponseException) throw error
            }
            delay(if (attempt == 1) 100L else 250L)
        }
        return block()
    }

    suspend fun saveCommandCodeApiKey(rawKey: String) = mutex.withLock {
        check(provider == AccountProvider.COMMAND_CODE)
        val key = rawKey.trim()
        require(key.isNotEmpty() && key.none { it.isWhitespace() || it.isISOControl() }) { "Invalid API key" }
        val user = commandCodeJson("https://api.commandcode.ai/alpha/whoami", key).optJSONObject("user")
        val id = user?.optString("id").orEmpty()
        val name = user?.optString("userName").orEmpty()
        if (id.isBlank() || name.isBlank()) throw IOException("Invalid account identity")
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) { addOrReplace(ProviderAccount(key, "", Long.MAX_VALUE, email = name, subject = id)) }
    }

    suspend fun availableCommandCodeModels(): List<com.ai.assistance.operit.data.model.ModelOption> {
        check(provider == AccountProvider.COMMAND_CODE)
        val json = commandCodeJson("https://api.commandcode.ai/provider/v1/models", validAccount().accessToken)
        val rows = json.optJSONArray("data") ?: throw IOException("Missing model catalog")
        return (0 until minOf(rows.length(), 256)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            com.ai.assistance.operit.data.model.ModelOption(id, row.optString("name").ifBlank { id })
        }.distinctBy { it.id }
    }

    /**
     * Command Code reports the same billing surface its official CLI's usage view reads: the rolling
     * 5-hour and weekly windows off `/alpha/billing/credits`, plus subscription-scoped spend for the
     * USD figure. Every sub-request soft-fails, so a partial outage still shows what did answer;
     * only the credits call is load-bearing because it carries the windows themselves.
     */
    suspend fun fetchCommandCodeQuota(): ProviderQuota {
        check(provider == AccountProvider.COMMAND_CODE)
        val bearer = validAccount().accessToken
        val orgId = commandCodeJsonOrNull(COMMAND_CODE_WHOAMI, bearer)
            ?.let { it.optJSONObject("data") ?: it }
            ?.optJSONObject("org")?.optString("id")?.trim()?.takeIf { it.isNotEmpty() }
        val orgQuery = CommandCodeQuota.orgIdQuery(orgId?.let { Uri.encode(it) })
        val credits = commandCodeJsonOrNull("$COMMAND_CODE_CREDITS$orgQuery", bearer)
            ?: throw IOException("Command Code credits are unavailable")
        val body = credits.optJSONObject("data") ?: credits
        val limits = body.optJSONObject("windowLimits")
        val windows = CommandCodeQuota.windows(limits)
        val spend = commandCodeSpend(bearer, body.optJSONObject("credits"), orgQuery)
        val quota = ProviderQuota(
            windows = windows,
            creditsUsedUsd = spend?.used,
            creditsLimitUsd = spend?.limit,
            creditsRemainingUsd = spend?.remaining,
        )
        if (quota.isEmpty) throw IOException("Command Code reported no quota")
        return quota
    }

    /**
     * Spend against the remaining credit pools. The unscoped usage summary is lifetime spend, so a
     * percentage is only truthful once a billing period start scopes the query; without one the USD
     * figure is omitted rather than computed against the wrong denominator.
     */
    private suspend fun commandCodeSpend(
        bearer: String,
        pools: JSONObject?,
        orgQuery: String,
    ): CommandCodeQuota.Spend? {
        if (pools == null) return null
        val subscription = commandCodeJsonOrNull("$COMMAND_CODE_SUBSCRIPTIONS$orgQuery", bearer)
            ?.let { it.optJSONObject("data") ?: it }
        // Trimmed, not just tested for blankness: padding must never reach the `since` query value.
        val periodStart = subscription?.optString("currentPeriodStart")?.trim()
        val summary = periodStart?.takeIf { it.isNotBlank() }?.let {
            commandCodeJsonOrNull(
                CommandCodeQuota.appendQuery(
                    "$COMMAND_CODE_USAGE_SUMMARY$orgQuery",
                    "since=${Uri.encode(it)}",
                ),
                bearer,
            )?.let { body -> body.optJSONObject("data") ?: body }
        }
        return CommandCodeQuota.spend(pools, periodStart, summary)
    }

    /** Soft-fail GET: a quota probe must never turn a provider hiccup into a thrown error path. */
    private suspend fun commandCodeJsonOrNull(url: String, key: String): JSONObject? = try {
        commandCodeJson(url, key)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun commandCodeJson(url: String, key: String): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url)
            .header("Authorization", "Bearer $key").header("Accept", "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) {
                continuation.resumeWithException(error)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val json = response.use {
                        if (!it.isSuccessful) throw AccountHttpException(it.code)
                        val source = it.body?.source() ?: throw IOException("Empty account response")
                        if (source.request(262145)) throw IOException("Account response too large")
                        try { JSONObject(source.readUtf8()) }
                        catch (_: JSONException) { throw IOException("Invalid account response") }
                    }
                    continuation.resume(json)
                } catch (error: Exception) { continuation.resumeWithException(error) }
            }
        })
    }

    private fun tokenForm(id: String, secret: String) = FormBody.Builder().add("client_id", id).apply {
        if (secret.isNotBlank()) add("client_secret", secret)
    }

    private fun parseTokens(json: JSONObject, previous: ProviderAccount? = null): ProviderAccount {
        val access = json.optString("access_token").also { require(it.isNotBlank()) { "Missing access token" } }
        val refresh = json.optString("refresh_token").ifBlank { previous?.refreshToken.orEmpty() }
            .also { require(it.isNotBlank()) { "Missing refresh token" } }
        val seconds = json.optLong("expires_in", 3600).also { require(it in 1..31_536_000) }
        // Claude names the subscribed account in the token body; the OIDC providers put it in the
        // id token instead.
        val claims = CodexOAuthProtocol.parseJwtClaims(json.optString("id_token"))
        val email = ClaudeOAuthProtocol.accountEmail(json).ifBlank { claims?.email.orEmpty() }
        val subject = json.optJSONObject("account")?.optString("uuid").orEmpty().trim()
            .ifBlank { claims?.subject.orEmpty() }
        // A refresh keeps everything else the account row holds (project, OAuth client, identity).
        val base = previous ?: ProviderAccount("", "", 0)
        return base.copy(
            accessToken = access, refreshToken = refresh,
            expiresAt = System.currentTimeMillis() + seconds * 1000,
            email = email.ifBlank { base.email }, subject = subject.ifBlank { base.subject },
            needsLogin = false,
        )
    }

    private suspend fun discoverXai(): Pair<String, String> {
        val json = requestJson("https://auth.x.ai/.well-known/openid-configuration")
        return validateXaiEndpoint(json.getString("authorization_endpoint")) to
            validateXaiEndpoint(json.getString("token_endpoint"))
    }

    private suspend fun discoverProject(token: String): String {
        val loaded = try { requestJson("$CCA/v1internal:loadCodeAssist",
            JSONObject("""{"metadata":{"ideType":"ANTIGRAVITY"}}""").toBody(), token)
        } catch (_: AccountHttpException) { null }
        extractProject(loaded)?.let { return it }
        repeat(5) {
            val result = try {
                requestJson("https://daily-cloudcode-pa.googleapis.com/v1internal:onboardUser",
                    JSONObject().put("tier_id", "free-tier").put("metadata", JSONObject()
                        .put("ide_type", "ANTIGRAVITY").put("ide_name", "antigravity")
                        .put("ide_version", ANTIGRAVITY_IDE_VERSION)).toBody(), token)
            } catch (failure: AccountHttpException) {
                if (failure.status != 429 && failure.status < 500) throw failure
                delay(2000)
                return@repeat
            }
            if (result.optBoolean("done")) extractProject(result.optJSONObject("response"))?.let { return it }
            delay(2000)
        }
        throw IOException("No Cloud Code Assist project is available for this account")
    }

    suspend fun availableModels(): JSONObject = availableModelsOfAccount().second

    /** The model list with the identity of the account it was asked for. */
    suspend fun availableModelsOfAccount(): Pair<String, JSONObject> {
        val value = validAccount()
        // Gemini 4 models are listed only to the CLI client; the list is otherwise the IDE one. The
        // daily host is the one the IDE and opencodex use: only it lists Gemini 4 Neon, Carbon and Hydrogen.
        return value.identity to requestJson("$CCA_DAILY/v1internal:fetchAvailableModels",
            JSONObject().put("project", value.projectId).toBody(), value.accessToken,
            mapOf("User-Agent" to ANTIGRAVITY_CLI_UA))
    }

    /**
     * Antigravity has no billing endpoint; what is left shows in its model list, per model. One
     * row each for Gemini and Claude, the way opencodex asks and shows it: the IDE's daily host and
     * the IDE client, which serves the agent models (Claude among them) whose allowance is shown.
     */
    suspend fun fetchAntigravityQuota(): ProviderQuota {
        check(provider == AccountProvider.ANTIGRAVITY)
        val value = validAccount()
        // The account's 5-hour and weekly allowance per model family, as opencodex reads it. The
        // CLI client also gets the Gemini 4 group; an account that refuses it for this is asked
        // again as the plain client. Only a server that says it has no summary for this account falls
        // back to the model list below; an unreachable or busy server fails this check as it is.
        val body = JSONObject().put("project", value.projectId).toBody()
        fun unsupported(failure: AccountHttpException) =
            failure.status in 400..499 && failure.status !in setOf(401, 408, 429)
        val summary = try {
            try {
                requestJson("$CCA_DAILY/v1internal:retrieveUserQuotaSummary", body, value.accessToken,
                    mapOf("User-Agent" to ANTIGRAVITY_CLI_UA), readOAuthError = false)
            } catch (refused: AccountHttpException) {
                if (refused.status != 403) throw refused
                requestJson("$CCA_DAILY/v1internal:retrieveUserQuotaSummary",
                    JSONObject().put("project", value.projectId).toBody(), value.accessToken,
                    mapOf("User-Agent" to "antigravity/1.0"), readOAuthError = false)
            }
        } catch (denied: AccountHttpException) {
            if (!unsupported(denied)) throw denied
            null
        } catch (unreadable: AccountResponseException) {
            // A body cut off while it was read is a network failure; only an empty or invalid one falls back.
            if (unreadable.cause is IOException) throw unreadable
            null
        }
        AntigravityQuota.summaryWindows(summary).takeIf { it.isNotEmpty() }?.let { return ProviderQuota(windows = it) }
        val models = requestJson("$CCA_DAILY/v1internal:fetchAvailableModels",
            JSONObject().put("project", value.projectId).toBody(), value.accessToken,
            mapOf("User-Agent" to ANTIGRAVITY_UA))
        val quota = ProviderQuota(windows = AntigravityQuota.windows(models))
        if (quota.isEmpty) throw IOException("Antigravity reported no quota")
        return quota
    }

    suspend fun availableGrokModels(): List<com.ai.assistance.operit.data.model.ModelOption> {
        val value = validAccount()
        val root = requestJson("https://cli-chat-proxy.grok.com/v1/models", token = value.accessToken)
        val models = root.optJSONArray("data") ?: throw IOException("Grok model list is missing")
        return (0 until models.length()).mapNotNull { index ->
            val model = models.optJSONObject(index) ?: return@mapNotNull null
            val id = model.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // The Grok OAuth chat-completions route does not support this Responses-only model.
            if (id == "grok-4.20-multi-agent-0309") return@mapNotNull null
            com.ai.assistance.operit.data.model.ModelOption(id, model.optString("name").ifBlank { id })
        }
    }

    /**
     * Grok reports the weekly credits window that actually gates prompting, and the legacy monthly
     * dollar pool when that window is not reported. Both live behind the same account token the
     * chat transport already uses, so this never touches an API-key configuration.
     */
    suspend fun fetchGrokQuota(): ProviderQuota {
        check(provider == AccountProvider.GROK)
        val bearer = validAccount().accessToken
        GrokQuota.userIdFromAccessToken(bearer)?.let { userId ->
            val body = grokJsonOrNull(XAI_CREDITS_URL, bearer, mapOf("x-userid" to userId))
            GrokQuota.weeklyCredits(body)?.let { (percent, resetAt) ->
                return ProviderQuota(listOf(ProviderQuotaWindow(QuotaWindow.WEEKLY, percent, resetAt)))
            }
        }
        GrokQuota.monthlyDollars(grokJsonOrNull(XAI_BILLING_URL, bearer, emptyMap()))?.let {
            (percent, resetAt) ->
            return ProviderQuota(listOf(ProviderQuotaWindow(QuotaWindow.MONTHLY, percent, resetAt)))
        }
        throw IOException("Grok reported no quota")
    }

    /**
     * Claude reports subscription windows per credential, so the probe rides the account's own
     * bearer token. Only the two canonical windows are surfaced: the per-model Opus/Sonnet buckets
     * have no place in [QuotaWindow].
     */
    suspend fun fetchClaudeQuota(): ProviderQuota {
        check(provider == AccountProvider.CLAUDE)
        val bearer = validAccount().accessToken
        val body = requestJson(
            ClaudeOAuthProtocol.USAGE_ENDPOINT,
            token = bearer,
            extraHeaders =
                ClaudeOAuthProtocol.USAGE_EXTRA_HEADERS +
                    ("anthropic-beta" to ClaudeOAuthProtocol.OAUTH_USAGE_BETA),
        )
        val windows =
            listOf(
                QuotaWindow.FIVE_HOUR to body.optJSONObject("five_hour"),
                QuotaWindow.WEEKLY to body.optJSONObject("seven_day"),
            ).mapNotNull { (window, row) ->
                val percent = QuotaParsing.percent(row?.opt("utilization")) ?: return@mapNotNull null
                ProviderQuotaWindow(window, percent, QuotaParsing.resetAt(row?.opt("resets_at")))
            }
        val quota = ProviderQuota(windows = windows)
        if (quota.isEmpty) throw IOException("Claude reported no quota")
        return quota
    }

    /** Soft-fail GET: a quota probe must never turn a provider hiccup into a thrown error path. */
    private suspend fun grokJsonOrNull(
        url: String,
        bearer: String,
        extraHeaders: Map<String, String>,
    ): JSONObject? = try {
        requestJson(url, token = bearer, extraHeaders = extraHeaders)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun requestJson(
        url: String,
        body: RequestBody? = null,
        token: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        /** False where the OAuth error code is of no use: a refusal is reported without reading its body. */
        readOAuthError: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) {
            val grokHeaders = if (token != null && provider == AccountProvider.GROK) GrokClientIdentity.headers() else emptyMap()
            val request = Request.Builder().url(url).header("Accept", "application/json").apply {
                if (body != null) post(body)
                if (token != null) {
                    header("Authorization", "Bearer $token")
                    if (provider == AccountProvider.ANTIGRAVITY) header("User-Agent", ANTIGRAVITY_UA)
                    grokHeaders.forEach { (key, value) -> header(key, value) }
                }
                extraHeaders.forEach { (key, value) -> header(key, value) }
            }.build()
            client.newCall(request).execute().use { response ->
                currentCoroutineContext().ensureActive()
                if (!response.isSuccessful) {
                    throw AccountHttpException(response.code, if (readOAuthError) oauthError(response) else null)
                }
                val text = try {
                    response.body?.string()
                } catch (error: IOException) {
                    throw AccountResponseException("Account response was cut off", error)
                } ?: throw AccountResponseException("Empty account response")
                try { JSONObject(text) }
                catch (_: JSONException) { throw AccountResponseException("Invalid account response") }
            }
        }

    /** The OAuth `error` code of a refusal; other bodies, and an `error` object, give none. */
    private fun oauthError(response: okhttp3.Response): String? = try {
        val text = response.peekBody(16_384).string()
        (JSONObject(text).opt("error") as? String)?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TAG = "ProviderAccountManager"
        private const val FAMILIES_KEY = "antigravity_tier_families"
        private const val ACCOUNTS_KEY = "accounts"
        private const val ACTIVE_KEY = "active_account"
        private const val LEGACY_ACCOUNT_KEY = "account"
        const val CCA = "https://cloudcode-pa.googleapis.com"
        /** The host the Antigravity IDE reads its model list and allowance from. */
        const val CCA_DAILY = "https://daily-cloudcode-pa.googleapis.com"
        const val ANTIGRAVITY_IDE_VERSION = "2.5.5"
        const val ANTIGRAVITY_UA =
            "antigravity/ide/$ANTIGRAVITY_IDE_VERSION (os_type=windows; arch=amd64; aidev_client; auth_method=oauth)"
        const val ANTIGRAVITY_CLI_UA =
            "antigravity/cli/1.3.4 (os_type=darwin; arch=arm64; aidev_client; auth_method=oauth)"
        /** CCA serves Gemini 4 (any of its ids) only to the CLI client and other agent models only to the IDE client. */
        fun antigravityUserAgent(wireModel: String) =
            if (wireModel.startsWith("gemini-4-")) ANTIGRAVITY_CLI_UA else ANTIGRAVITY_UA
        private const val GOOGLE_TOKEN = "https://oauth2.googleapis.com/token"
        private const val GOOGLE_USERINFO = "https://www.googleapis.com/oauth2/v2/userinfo"
        private const val COMMAND_CODE_WHOAMI = "https://api.commandcode.ai/alpha/whoami"
        private const val COMMAND_CODE_CREDITS = "https://api.commandcode.ai/alpha/billing/credits"
        private const val COMMAND_CODE_SUBSCRIPTIONS = "https://api.commandcode.ai/alpha/billing/subscriptions"
        private const val COMMAND_CODE_USAGE_SUMMARY = "https://api.commandcode.ai/alpha/usage/summary"
        private const val XAI_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        private const val XAI_SCOPE = "openid profile email offline_access grok-cli:access api:access"
        private const val XAI_BILLING_URL = "https://cli-chat-proxy.grok.com/v1/billing"
        private const val XAI_CREDITS_URL = "$XAI_BILLING_URL?format=credits"
        private val GOOGLE_SCOPE = listOf("cloud-platform", "userinfo.email", "userinfo.profile",
            "cclog", "experimentsandconfigs").joinToString(" ") { "https://www.googleapis.com/auth/$it" }
        private val instances = mutableMapOf<AccountProvider, ProviderAccountManager>()
        @Synchronized fun get(context: Context, provider: AccountProvider): ProviderAccountManager =
            instances.getOrPut(provider) { ProviderAccountManager(context.applicationContext, provider) }
        internal fun validateXaiEndpoint(url: String): String {
            val uri = java.net.URI(url)
            require(uri.scheme == "https" && uri.userInfo == null &&
                (uri.host == "x.ai" || uri.host?.endsWith(".x.ai") == true)) { "Unexpected xAI OAuth endpoint" }
            return url
        }
        internal fun extractProject(json: JSONObject?): String? =
            listOf("cloudaicompanionProject", "projectId", "project").firstNotNullOfOrNull { key ->
                when (val value = json?.opt(key)) {
                    is String -> value.takeIf { it.isNotBlank() }
                    is JSONObject -> value.optString("id").takeIf { it.isNotBlank() }
                    else -> null
                }
            }
        private fun JSONObject.toBody() = toString().toRequestBody("application/json".toMediaType())
    }
}
