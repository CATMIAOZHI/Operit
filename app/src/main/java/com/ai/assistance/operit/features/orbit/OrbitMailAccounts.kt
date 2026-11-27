package com.ai.assistance.operit.features.orbit

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The mail services Orbit can connect to. */
internal enum class OrbitMailProvider(val id: String, val label: String) {
    GMAIL("gmail", "Gmail"),
    OUTLOOK("outlook", "Outlook");

    companion object {
        fun of(id: String?): OrbitMailProvider? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Who Orbit signs in as with Google and Microsoft. For now these are Thunderbird's published desktop
 * clients (the git-credential-email project uses the same ones); once Orbit's own clients are
 * registered, only this object changes. Their scopes reach IMAP and SMTP only, so mail is read and
 * sent over IMAP/SMTP for both services.
 */
internal object OrbitMailClients {
    class Client(val clientId: String, val clientSecret: String?, val scope: String)

    val gmail = Client(
        clientId = "406964657835-aq8lmia8j95dhl1a2bvharmfk3t1hgqj.apps.googleusercontent.com",
        // Thunderbird's installed-app secret, published in its source; Google does not treat it as secret.
        clientSecret = "kSmqreRr0qwBWJgbf5Y-PjSU",
        scope = "https://mail.google.com/",
    )
    val outlook = Client(
        clientId = "9e5f94bc-e8a4-4e73-b8be-63364c29d753",
        clientSecret = null,
        // openid/profile only name the signed-in address; mail itself needs the first two.
        scope = "https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send offline_access openid profile",
    )

    const val GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
    const val GOOGLE_TOKEN = "https://oauth2.googleapis.com/token"
    const val GOOGLE_REVOKE = "https://oauth2.googleapis.com/revoke"
    const val MS_DEVICE_CODE = "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode"
    const val MS_TOKEN = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    /** Microsoft's tenant for personal accounts (outlook.com, hotmail.com), which send through another SMTP host. */
    const val MS_CONSUMER_TENANT = "9188040d-6c67-4c5b-b112-36a304b66dad"

    fun of(provider: OrbitMailProvider) = if (provider == OrbitMailProvider.GMAIL) gmail else outlook
}

/** A connected mailbox as the app shows it; its tokens never leave [OrbitMailAccounts]. */
internal data class OrbitMailAccount(
    val id: String,
    val provider: OrbitMailProvider,
    val email: String,
    val addedAt: Long,
    /** The sign-in stopped working (revoked, password changed); the user has to connect it again. */
    val needsSignIn: Boolean,
    /** A personal Microsoft account (outlook.com, hotmail.com). */
    val consumer: Boolean,
) {
    /** How the model names this mailbox when the same address is on both services. */
    val qualified: String get() = "${provider.id}:$email"
}

/** Thrown when an account has to be connected again; the message says so in plain words. */
internal class OrbitMailSignInNeeded(message: String) : IllegalStateException(message)

/**
 * The mailboxes an Orbit is connected to, kept encrypted on this phone only (Android Keystore,
 * outside backups). Access tokens are refreshed here, straight with Google or Microsoft; no token is
 * ever returned to the model, written to a log or put in an error.
 */
internal object OrbitMailAccounts {
    private const val STORE = "mail"
    private const val MAX_ACCOUNTS = 10
    /** Refreshed this long before it runs out, so a slow IMAP login does not cross the end. */
    private const val REFRESH_MARGIN_MS = 3 * 60_000L
    /** Guards the stored list; never held across a network call. */
    private val lock = Mutex()
    /** One refresh per account at a time, so a refresh token Microsoft rotates is never used twice. */
    private val refreshing = ConcurrentHashMap<String, Mutex>()
    private val version = MutableStateFlow(0)
    /** For asking Google to revoke a removed sign-in, which nobody waits for. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Provider answers meaning the user has to sign in again; any other refusal leaves the account as it is. */
    private val SIGN_IN_AGAIN = setOf("invalid_grant", "interaction_required", "consent_required", "login_required")
    /** Provider error codes that may be shown; anything else is reported only by its HTTP status. */
    private val KNOWN_ERRORS = SIGN_IN_AGAIN + setOf(
        "invalid_client", "unauthorized_client", "invalid_request", "invalid_scope", "access_denied",
        "temporarily_unavailable", "server_error", "slow_down", "authorization_pending", "authorization_declined",
        "expired_token", "bad_verification_code",
    )

    /** Changes whenever an account is added, removed or needs signing in again. */
    val changes: StateFlow<Int> = version.asStateFlow()

    internal val http by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS).build()
    }

    /** Tokens as a provider returned them. */
    class Tokens(val access: String, val refresh: String?, val expiresInSeconds: Long, val idToken: String?)

    /** What a mail request needs: who signs in, with which token. */
    class Login(val account: OrbitMailAccount, val accessToken: String)

    private fun store(context: Context, orbitId: String) = OrbitSecretFile.forOrbit(context, orbitId, STORE)

    private fun readLocked(context: Context, orbitId: String): List<JSONObject> =
        (store(context, orbitId).read()?.let { JSONObject(it).optJSONArray("accounts") } ?: JSONArray())
            .let { array -> (0 until array.length()).map { array.getJSONObject(it) } }

    private fun writeLocked(context: Context, orbitId: String, accounts: List<JSONObject>) {
        if (accounts.isEmpty()) store(context, orbitId).delete()
        else store(context, orbitId).write(JSONObject().put("accounts", JSONArray(accounts)).toString())
        version.value++
    }

    private fun account(json: JSONObject): OrbitMailAccount? {
        val provider = OrbitMailProvider.of(json.optString("provider")) ?: return null
        return OrbitMailAccount(json.optString("id"), provider, json.optString("email"), json.optLong("added_at"),
            json.optBoolean("needs_sign_in"), json.optBoolean("consumer"))
    }

    /** Whether this Orbit has any mailbox, without decrypting anything (the store is deleted when empty). */
    suspend fun connected(context: Context, orbitId: String?): Boolean =
        orbitId != null && withContext(Dispatchers.IO) { store(context, orbitId).exists() }

    suspend fun list(context: Context, orbitId: String): List<OrbitMailAccount> = withContext(Dispatchers.IO) {
        lock.withLock { readLocked(context, orbitId).mapNotNull(::account) }
    }

    /**
     * Saves a mailbox that just signed in. The same address on the same service replaces the old
     * entry (signing in again), keeping its id; a refresh still running for the old sign-in is then
     * thrown away ("gen").
     */
    suspend fun save(context: Context, orbitId: String, provider: OrbitMailProvider, email: String, tokens: Tokens,
        consumer: Boolean): OrbitMailAccount = withContext(Dispatchers.IO) {
        val refresh = tokens.refresh?.takeIf { it.isNotBlank() } ?: throw OrbitMailAuthError(OrbitMailAuthError.Kind.NO_REFRESH)
        lock.withLock {
            val all = readLocked(context, orbitId)
            val same = all.firstOrNull { it.optString("provider") == provider.id && it.optString("email").equals(email, true) }
            if (same == null && all.size >= MAX_ACCOUNTS) throw OrbitMailAuthError(OrbitMailAuthError.Kind.TOO_MANY)
            val entry = JSONObject()
                .put("id", same?.optString("id") ?: UUID.randomUUID().toString())
                .put("gen", UUID.randomUUID().toString())
                .put("provider", provider.id).put("email", email)
                .put("added_at", same?.optLong("added_at") ?: System.currentTimeMillis())
                .put("consumer", consumer)
                .put("refresh_token", refresh).put("access_token", tokens.access)
                .put("expires_at", System.currentTimeMillis() + tokens.expiresInSeconds * 1000)
            writeLocked(context, orbitId, if (same == null) all + entry else all.map { if (it === same) entry else it })
            checkNotNull(account(entry))
        }
    }

    /**
     * Forgets a mailbox; it is gone once this returns. Google is then asked, in the background, to
     * revoke the sign-in; Microsoft has no such call.
     */
    suspend fun remove(context: Context, orbitId: String, id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val all = readLocked(context, orbitId)
            val gone = all.firstOrNull { it.optString("id") == id } ?: return@withLock null
            writeLocked(context, orbitId, all.filter { it !== gone })
            // Listed under the same lock that removed the mailbox, so signing the address in again always sees it.
            if (gone.optString("provider") == OrbitMailProvider.GMAIL.id) {
                val address = gone.optString("email").lowercase()
                val job = background.launch {
                    runCatching { postJson(OrbitMailClients.GOOGLE_REVOKE, FormBody.Builder().add("token", gone.optString("refresh_token")).build()) }
                }
                revoking[address] = job
                job.invokeOnCompletion { revoking.remove(address, job) }
            }
        }
    }

    /** Revokes still on their way to Google, by address: they would also undo a newer sign-in of that address. */
    private val revoking = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * Waits for a revoke of [email] to finish, so signing it in again is not undone. One that takes
     * too long is left to finish (a request Google already has cannot be called back), and the
     * sign-in stops with [OrbitMailAuthError.Kind.BUSY].
     */
    suspend fun awaitRevoke(email: String) {
        val job = lock.withLock { revoking[email.trim().lowercase()] } ?: return
        kotlinx.coroutines.withTimeoutOrNull(REVOKE_WAIT_MS) { job.join() }
            ?: throw OrbitMailAuthError(OrbitMailAuthError.Kind.BUSY)
    }

    private const val REVOKE_WAIT_MS = 20_000L

    /**
     * The account to use: the one named by [name] (an address, or "gmail:address" / "outlook:address"
     * when one address is on both), or the only one when [name] is blank. Throws with a message the
     * model can act on.
     */
    suspend fun pick(context: Context, orbitId: String, name: String?): OrbitMailAccount {
        val all = list(context, orbitId)
        check(all.isNotEmpty()) { "No mailbox is connected. Ask the user to connect Gmail or Outlook in Orbit settings → Connectors" }
        val names = all.joinToString { a -> if (all.count { it.email.equals(a.email, true) } > 1) a.qualified else a.email }
        val wanted = name?.trim().orEmpty()
        if (wanted.isEmpty()) {
            check(all.size == 1) { "Several mailboxes are connected; pass account (one of: $names)" }
            return all.single()
        }
        val provider = OrbitMailProvider.entries.firstOrNull { wanted.startsWith(it.id + ":", true) }
        val address = if (provider != null) wanted.substringAfter(':').trim() else wanted
        val found = all.filter { it.email.equals(address, true) && (provider == null || it.provider == provider) }
        check(found.size <= 1) { "$address is connected on both services; pass account as one of: $names" }
        return found.firstOrNull() ?: error("No connected mailbox $wanted; connected: $names")
    }

    /** The account with this id, as stored. */
    suspend fun byId(context: Context, orbitId: String, id: String): OrbitMailAccount =
        list(context, orbitId).firstOrNull { it.id == id } ?: error("That mailbox is no longer connected; nothing was sent")

    /** The stored entry of [accountId], checked to still be signed in. */
    private suspend fun current(context: Context, orbitId: String, accountId: String): Pair<OrbitMailAccount, JSONObject> =
        lock.withLock {
            val json = readLocked(context, orbitId).firstOrNull { it.optString("id") == accountId }
                ?: error("That mailbox is no longer connected")
            val account = checkNotNull(account(json))
            if (account.needsSignIn) throw signInNeeded(account)
            account to json
        }

    /**
     * A fresh access token for [accountId], refreshed with its provider when it is about to run out,
     * or when the mail server refused [refused] (unless another call already replaced it). The stored
     * list stays readable while the provider is asked, and a cancelled call stops the request.
     */
    suspend fun login(context: Context, orbitId: String, accountId: String, refused: String? = null): Login =
        withContext(Dispatchers.IO) {
            fun usable(json: JSONObject): String? = json.optString("access_token").takeIf {
                it.isNotEmpty() && it != refused && json.optLong("expires_at") - System.currentTimeMillis() > REFRESH_MARGIN_MS
            }
            current(context, orbitId, accountId).let { (account, json) -> usable(json)?.let { return@withContext Login(account, it) } }
            refreshing.getOrPut("$orbitId/$accountId") { Mutex() }.withLock {
                // Another call may have refreshed (or the user signed in again) while this one waited.
                val (account, json) = current(context, orbitId, accountId)
                usable(json)?.let { return@withLock Login(account, it) }
                val gen = json.optString("gen")
                val tokens = try {
                    refresh(account.provider, json.optString("refresh_token"))
                } catch (e: OrbitMailSignInNeeded) {
                    if (update(context, orbitId, accountId, gen) { it.put("needs_sign_in", true).remove("access_token") }) {
                        throw signInNeeded(account)
                    }
                    null
                }
                if (tokens != null && update(context, orbitId, accountId, gen) {
                        it.put("access_token", tokens.access)
                            .put("expires_at", System.currentTimeMillis() + tokens.expiresInSeconds * 1000)
                        // Microsoft may hand out a new refresh token; the old one then stops working.
                        tokens.refresh?.takeIf { r -> r.isNotBlank() }?.let { r -> it.put("refresh_token", r) }
                    }) return@withLock Login(account, tokens.access)
                // The user signed in again meanwhile: that sign-in's token wins.
                val (now, fresh) = current(context, orbitId, accountId)
                Login(now, fresh.optString("access_token").takeIf { it.isNotEmpty() } ?: error("The mailbox was just signed in again; try again"))
            }
        }

    /** Changes the stored entry of [accountId] if it is still the sign-in [gen]; false otherwise. */
    private suspend fun update(context: Context, orbitId: String, accountId: String, gen: String,
        change: (JSONObject) -> Unit): Boolean = lock.withLock {
        val all = readLocked(context, orbitId)
        val json = all.firstOrNull { it.optString("id") == accountId && it.optString("gen") == gen } ?: return@withLock false
        change(json)
        writeLocked(context, orbitId, all)
        true
    }

    private fun signInNeeded(account: OrbitMailAccount) = OrbitMailSignInNeeded(
        "${account.email} needs to be connected again: ask the user to open Orbit settings → Connectors and sign in again")

    private suspend fun refresh(provider: OrbitMailProvider, refreshToken: String): Tokens {
        val client = OrbitMailClients.of(provider)
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("client_id", client.clientId)
            .add("refresh_token", refreshToken)
        client.clientSecret?.let { form.add("client_secret", it) }
        if (provider == OrbitMailProvider.OUTLOOK) form.add("scope", client.scope)
        val url = if (provider == OrbitMailProvider.GMAIL) OrbitMailClients.GOOGLE_TOKEN else OrbitMailClients.MS_TOKEN
        return tokenCall(url, form.build())
    }

    /**
     * Posts [form] and reads a small JSON answer: the HTTP status and the object (empty when
     * unreadable). The answer is read on OkHttp's thread before this returns, so cancelling the
     * coroutine cancels the whole request, body included.
     */
    internal suspend fun postJson(url: String, form: FormBody): Pair<Int, JSONObject> = suspendCancellableCoroutine { continuation ->
        val call: Call = http.newCall(Request.Builder().url(url).header("Accept", "application/json").post(form).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val body = it.body?.source()?.let { source ->
                            source.request(65_537)
                            check(source.buffer.size <= 65_536) { "Sign-in response is too large" }
                            source.readUtf8()
                        }.orEmpty()
                        it.code to (runCatching { JSONObject(body) }.getOrNull() ?: JSONObject())
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }

    /** A provider's error code when it is one of the known ones, never free text from the answer. */
    internal fun errorCode(json: JSONObject): String? = json.optString("error").takeIf { it in KNOWN_ERRORS }

    /**
     * Posts to a token endpoint. A refused grant (revoked, expired) becomes [OrbitMailSignInNeeded];
     * other failures say only what failed, never what was sent or returned.
     */
    internal suspend fun tokenCall(url: String, form: FormBody): Tokens {
        val (status, json) = postJson(url, form)
        if (status !in 200..299) {
            val error = errorCode(json)
            if (error in SIGN_IN_AGAIN) throw OrbitMailSignInNeeded("The sign-in was refused ($error)")
            throw IllegalStateException("Sign-in request failed (HTTP $status${if (error != null) ", $error" else ""})")
        }
        val access = json.optString("access_token")
        check(access.isNotEmpty()) { "Sign-in response had no access token" }
        return Tokens(access, json.optString("refresh_token").takeIf { it.isNotEmpty() },
            json.optLong("expires_in", 3600).coerceIn(60, 24 * 3600), json.optString("id_token").takeIf { it.isNotEmpty() })
    }
}
