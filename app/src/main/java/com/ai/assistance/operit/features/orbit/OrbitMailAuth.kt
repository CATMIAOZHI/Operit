package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserSignInCatch
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/** Why connecting a mailbox failed, in a form the settings page turns into its own words. */
internal class OrbitMailAuthError(val kind: Kind, val email: String? = null) : IllegalStateException(kind.message) {
    enum class Kind(val message: String) {
        TIMEOUT("The sign-in took too long"),
        CODE_EXPIRED("The sign-in code ran out"),
        NO_BROWSER("The browser may not show"),
        REFUSED("The sign-in was refused"),
        WRONG_ACCOUNT("Another account signed in"),
        NO_ADDRESS("The service did not say which address signed in"),
        IMAP_REFUSED("The mailbox refused IMAP sign-in"),
        SMTP_REFUSED("The mailbox may not send mail"),
        NO_REFRESH("The service did not allow staying signed in"),
        TOO_MANY("Too many mailboxes are connected"),
        BUSY("A disconnect of this mailbox is still being finished"),
    }
}

/**
 * Connecting a mailbox, straight between this phone and Google or Microsoft, in one of two ways:
 *
 * - In Orbit's own browser ([signIn]): the provider's page opens in a tab, which ends on the client's
 *   return address (`http://localhost/` for Google, `https://localhost` for Microsoft, as
 *   Thunderbird's desktop clients have them); the tab hands that back here instead of loading it
 *   ([BrowserSignInCatch]). Orbit's browser then stays signed in to the account too.
 * - In the phone's own browser: Gmail returns to a one-time page this app serves on 127.0.0.1
 *   ([signInGmailElsewhere]); Outlook shows a short code to enter on Microsoft's page ([startOutlookCode]).
 *
 * Either way the provider names the signed-in address in its ID token, so nothing is typed in first,
 * and the new account is tried once over IMAP and SMTP before it is saved.
 */
internal object OrbitMailAuth {
    private const val WAIT_MS = 10 * 60_000L
    private const val GOOGLE_REDIRECT = "http://localhost/"
    private const val MS_REDIRECT = "https://localhost"
    private const val MAX_REQUEST_LINE = 8192
    private const val REQUEST_MS = 3000L
    private val random = SecureRandom()

    private fun randomToken(): String = ByteArray(32).also(random::nextBytes)
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

    private fun challenge(verifier: String): String = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

    /** The provider's sign-in page for an authorization code returned to [redirect]. */
    private fun authUrl(provider: OrbitMailProvider, redirect: String, state: String, verifier: String, expected: String?): String {
        val gmail = provider == OrbitMailProvider.GMAIL
        val client = OrbitMailClients.of(provider)
        return Uri.parse(if (gmail) OrbitMailClients.GOOGLE_AUTH else OrbitMailClients.MS_AUTH).buildUpon()
            .appendQueryParameter("client_id", client.clientId)
            .appendQueryParameter("redirect_uri", redirect)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", client.scope)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .apply {
                if (gmail) {
                    // A refresh token is only given with offline access, and reliably only on a fresh consent.
                    appendQueryParameter("access_type", "offline")
                    appendQueryParameter("prompt", "consent select_account")
                } else {
                    appendQueryParameter("prompt", "select_account")
                }
                expected?.let { appendQueryParameter("login_hint", it) }
            }.build().toString()
    }

    /**
     * Signs in a [provider] mailbox in Orbit's browser. [open] shows the provider's page (main thread)
     * in a browser tab with the given id and says whether it could; once the provider answered,
     * [onConnecting] is called while the account is checked and saved. With [expected] (signing in
     * again), only that address is accepted. Null when the user declined or closed the sign-in tab;
     * cancelling stops the wait.
     */
    suspend fun signIn(context: Context, orbitId: String, provider: OrbitMailProvider, expected: String?,
        open: suspend (url: String, tabId: String) -> Boolean, onConnecting: () -> Unit): OrbitMailAccount? {
        val redirect = if (provider == OrbitMailProvider.GMAIL) GOOGLE_REDIRECT else MS_REDIRECT
        val verifier = randomToken()
        val state = randomToken()
        val url = authUrl(provider, redirect, state, verifier, expected)
        val outcome = withTimeoutOrNull(WAIT_MS) {
            BrowserSignInCatch.await(redirect, state) { tab -> withContext(Dispatchers.Main.immediate) { open(url, tab) } }
        } ?: throw OrbitMailAuthError(OrbitMailAuthError.Kind.TIMEOUT)
        val callback = when (outcome) {
            is BrowserSignInCatch.Outcome.Answered -> outcome.uri
            BrowserSignInCatch.Outcome.TabClosed -> return null
            BrowserSignInCatch.Outcome.NotShown -> throw OrbitMailAuthError(OrbitMailAuthError.Kind.NO_BROWSER)
        }
        callback.getQueryParameter("error")?.let { error ->
            if (error == "access_denied") return null
            throw OrbitMailAuthError(OrbitMailAuthError.Kind.REFUSED)
        }
        val code = callback.getQueryParameter("code").orEmpty()
        if (code.isEmpty() || code.length > 4096) throw OrbitMailAuthError(OrbitMailAuthError.Kind.REFUSED)
        withContext(Dispatchers.Main.immediate) { onConnecting() }
        return exchangeAndSave(context, orbitId, provider, expected, code, redirect, verifier)
    }

    /**
     * Signs in a Gmail address in the phone's own browser. [onUrl] gets Google's page to open (main
     * thread); Google sends its answer to a one-time page this app serves on 127.0.0.1, which exists
     * only while this runs. Then as [signIn].
     */
    suspend fun signInGmailElsewhere(context: Context, orbitId: String, expected: String?, onUrl: (String) -> Unit,
        onConnecting: () -> Unit): OrbitMailAccount? {
        val app = context.applicationContext
        val verifier = randomToken()
        val (code, redirect) = withContext(Dispatchers.IO) {
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                val redirect = "http://127.0.0.1:${server.localPort}/"
                val state = randomToken()
                val url = authUrl(OrbitMailProvider.GMAIL, redirect, state, verifier, expected)
                withContext(Dispatchers.Main.immediate) { onUrl(url) }
                awaitCode(app, server, state)?.let { it to redirect }
            }
        } ?: return null
        withContext(Dispatchers.Main.immediate) { onConnecting() }
        return exchangeAndSave(context, orbitId, OrbitMailProvider.GMAIL, expected, code, redirect, verifier)
    }

    /** Trades the provider's [code] for tokens, then checks and saves the mailbox. */
    private suspend fun exchangeAndSave(context: Context, orbitId: String, provider: OrbitMailProvider, expected: String?,
        code: String, redirect: String, verifier: String): OrbitMailAccount {
        val gmail = provider == OrbitMailProvider.GMAIL
        val client = OrbitMailClients.of(provider)
        // A Gmail disconnect still revoking at Google would undo the new grant of that address.
        // Which address signed in is known only after the exchange, so every revoke on its way ends
        // first; one started from here on makes saving refuse the address.
        val revokes = OrbitMailAccounts.revokeMark()
        if (gmail) OrbitMailAccounts.awaitRevokes()
        val form = FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
            .add("client_id", client.clientId).add("redirect_uri", redirect).add("code_verifier", verifier)
        client.clientSecret?.let { form.add("client_secret", it) }
        if (!gmail) form.add("scope", client.scope)
        val tokens = try {
            OrbitMailAccounts.tokenCall(if (gmail) OrbitMailClients.GOOGLE_TOKEN else OrbitMailClients.MS_TOKEN, form.build())
        } catch (e: OrbitMailSignInNeeded) {
            throw OrbitMailAuthError(OrbitMailAuthError.Kind.REFUSED)
        }
        return save(context, orbitId, provider, expected, tokens, revokes)
    }

    /** Names the signed-in address from the ID token, checks the mailbox and saves it. */
    private suspend fun save(context: Context, orbitId: String, provider: OrbitMailProvider, expected: String?,
        tokens: OrbitMailAccounts.Tokens, revokes: Long): OrbitMailAccount {
        val gmail = provider == OrbitMailProvider.GMAIL
        val claims = tokens.idToken?.let(::claims) ?: JSONObject()
        val email = (if (gmail) listOf("email") else listOf("email", "preferred_username", "upn"))
            .map { claims.optString(it).trim() }.firstOrNull { isAddress(it) }
            ?: throw OrbitMailAuthError(OrbitMailAuthError.Kind.NO_ADDRESS)
        if (expected != null && !email.equals(expected, true)) {
            throw OrbitMailAuthError(OrbitMailAuthError.Kind.WRONG_ACCOUNT, email)
        }
        val consumer = !gmail && claims.optString("tid") == OrbitMailClients.MS_CONSUMER_TENANT
        OrbitMailBox.verify(provider, email, tokens.access, consumer)
        return OrbitMailAccounts.save(context.applicationContext, orbitId, provider, email, tokens, consumer, revokesBefore = revokes)
    }

    /** What one request to the local page said. */
    private sealed interface Answer {
        class Code(val code: String) : Answer
        object Declined : Answer
        object Refused : Answer
    }

    /**
     * The code Google sent back to the local page; null when the user declined. A request without
     * our state, or one that is slow or broken, is answered (or dropped) and waiting goes on.
     */
    private suspend fun awaitCode(context: Context, server: ServerSocket, state: String): String? = coroutineScope {
        val end = android.os.SystemClock.elapsedRealtime() + WAIT_MS
        server.soTimeout = 1000
        val open = AtomicReference<Socket?>(null)
        // Cancelling closes the local page at once, which ends a blocked accept or read. Started right
        // here, so it is in place even when cancelling comes before anything else runs.
        val closer = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                runCatching { server.close() }
                runCatching { open.get()?.close() }
            }
        }
        try {
            while (true) {
                ensureActive()
                if (android.os.SystemClock.elapsedRealtime() > end) throw OrbitMailAuthError(OrbitMailAuthError.Kind.TIMEOUT)
                val socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: java.io.IOException) {
                    ensureActive()
                    throw e
                }
                val answer = runCatching {
                    socket.use {
                        open.set(it)
                        // Cancelled before it was listed: the closer may have missed it, so stop here.
                        ensureActive()
                        answer(context, it, state)
                    }
                }.getOrNull()
                open.set(null)
                ensureActive()
                when (answer) {
                    null -> continue
                    is Answer.Code -> {
                        bringBack(context)
                        return@coroutineScope answer.code
                    }
                    Answer.Declined -> return@coroutineScope null
                    Answer.Refused -> throw OrbitMailAuthError(OrbitMailAuthError.Kind.REFUSED)
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        } finally {
            closer.cancel()
        }
    }

    /** Reads one request line (bounded in size and time) and answers it. */
    private fun answer(context: Context, socket: Socket, state: String): Answer? {
        val input = BufferedInputStream(socket.getInputStream())
        val line = ByteArrayOutputStream()
        val deadline = android.os.SystemClock.elapsedRealtime() + REQUEST_MS
        while (line.size() < MAX_REQUEST_LINE) {
            val left = deadline - android.os.SystemClock.elapsedRealtime()
            if (left <= 0) break
            socket.soTimeout = left.toInt().coerceAtLeast(1)
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            line.write(b)
        }
        val target = line.toString(Charsets.ISO_8859_1.name()).trim().split(' ').getOrNull(1).orEmpty()
        val uri = Uri.parse("http://127.0.0.1$target")
        if (uri.getQueryParameter("state") != state) {
            respond(socket, 404, "")
            return null
        }
        val shown = LocaleUtils.getLocalizedContext(context)
        uri.getQueryParameter("error")?.let { error ->
            val declined = error == "access_denied"
            respond(socket, 200, page(shown, if (declined) PageKind.CANCELLED else PageKind.REFUSED))
            return if (declined) Answer.Declined else Answer.Refused
        }
        // Our state with no usable code: this sign-in ended, refused.
        val code = uri.getQueryParameter("code").orEmpty()
        if (code.isEmpty() || code.length > 4096) {
            respond(socket, 200, page(shown, PageKind.REFUSED))
            return Answer.Refused
        }
        respond(socket, 200, page(shown, PageKind.DONE))
        return Answer.Code(code)
    }

    /** Opens [url] in a Custom Tab when one is there, otherwise in the browser. Throws when nothing can open it. */
    fun openInBrowser(context: Context, url: String) {
        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().apply {
                if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.launchUrl(context, uri)
        } catch (e: android.content.ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** An Outlook sign-in by code: the user enters [userCode] at [verificationUrl] in any browser. */
    class OutlookCode internal constructor(
        val userCode: String,
        val verificationUrl: String,
        private val deviceCode: String,
        private val intervalSeconds: Long,
        private val expiresAt: Long,
    ) {
        /**
         * Waits until the user finished on Microsoft's page, then saves the account; null when
         * declined. With [expected] (signing in again), only that address is accepted.
         */
        suspend fun finish(context: Context, orbitId: String, expected: String?, onConnecting: () -> Unit): OrbitMailAccount? {
            val client = OrbitMailClients.outlook
            var interval = intervalSeconds
            while (true) {
                val left = expiresAt - android.os.SystemClock.elapsedRealtime()
                if (left <= 0) throw OrbitMailAuthError(OrbitMailAuthError.Kind.CODE_EXPIRED)
                delay(minOf(interval * 1000, left))
                if (android.os.SystemClock.elapsedRealtime() >= expiresAt) throw OrbitMailAuthError(OrbitMailAuthError.Kind.CODE_EXPIRED)
                val form = FormBody.Builder().add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("client_id", client.clientId).add("device_code", deviceCode).build()
                val (status, json) = OrbitMailAccounts.postJson(OrbitMailClients.MS_TOKEN, form)
                if (status in 200..299) {
                    val access = json.optString("access_token")
                    if (access.isEmpty()) throw OrbitMailAuthError(OrbitMailAuthError.Kind.REFUSED)
                    val tokens = OrbitMailAccounts.Tokens(access, json.optString("refresh_token").takeIf { it.isNotEmpty() },
                        json.optLong("expires_in", 3600).coerceIn(60, 24 * 3600), json.optString("id_token").takeIf { it.isNotEmpty() })
                    withContext(Dispatchers.Main.immediate) { onConnecting() }
                    return save(context, orbitId, OrbitMailProvider.OUTLOOK, expected, tokens, OrbitMailAccounts.revokeMark())
                }
                when (OrbitMailAccounts.errorCode(json)) {
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += 5
                    "authorization_declined" -> return null
                    "expired_token" -> throw OrbitMailAuthError(OrbitMailAuthError.Kind.CODE_EXPIRED)
                    else -> throw OrbitMailAuthError(OrbitMailAuthError.Kind.REFUSED)
                }
            }
        }
    }

    /** Asks Microsoft for a sign-in code. */
    suspend fun startOutlookCode(): OutlookCode {
        val client = OrbitMailClients.outlook
        val (status, json) = OrbitMailAccounts.postJson(OrbitMailClients.MS_DEVICE_CODE,
            FormBody.Builder().add("client_id", client.clientId).add("scope", client.scope).build())
        if (status !in 200..299) throw OrbitMailAuthError(OrbitMailAuthError.Kind.REFUSED)
        val userCode = json.optString("user_code")
        val deviceCode = json.optString("device_code")
        val verification = json.optString("verification_uri").ifEmpty { "https://microsoft.com/devicelogin" }
        if (userCode.isEmpty() || deviceCode.isEmpty()) throw OrbitMailAuthError(OrbitMailAuthError.Kind.REFUSED)
        require(Uri.parse(verification).let { it.scheme == "https" && it.host.orEmpty().endsWith("microsoft.com") }) {
            "Unexpected sign-in page"
        }
        return OutlookCode(userCode, verification, deviceCode, json.optLong("interval", 5).coerceIn(1, 30),
            android.os.SystemClock.elapsedRealtime() + json.optLong("expires_in", 900).coerceIn(60, 1800) * 1000)
    }

    /** The claims of an ID token that came straight from the provider over TLS; only the address is read. */
    private fun claims(idToken: String): JSONObject? = runCatching {
        val part = idToken.split('.')[1]
        JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8))
    }.getOrNull()

    /** A single address with a domain: name@host. */
    fun isAddress(text: String): Boolean = text.length in 3..254 && runCatching {
        javax.mail.internet.InternetAddress(text, true)
        val at = text.lastIndexOf('@')
        at > 0 && at < text.length - 1
    }.getOrDefault(false)

    /** Answers the browser; best effort: a browser that went away does not undo the answer already read. */
    private fun respond(socket: Socket, status: Int, html: String) {
        runCatching { write(socket, status, html) }
    }

    private fun write(socket: Socket, status: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Not Found" }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
    }

    private enum class PageKind { DONE, CANCELLED, REFUSED }

    /** The page the browser shows after Google, in the app's language: says how it went and how to get back. */
    private fun page(context: Context, kind: PageKind): String {
        val title = context.getString(when (kind) {
            PageKind.DONE -> R.string.orbit_connectors_page_done
            PageKind.CANCELLED -> R.string.orbit_connectors_cancelled
            PageKind.REFUSED -> R.string.orbit_connectors_page_refused
        })
        val note = context.getString(if (kind == PageKind.DONE) R.string.orbit_connectors_page_done_note else R.string.orbit_connectors_page_back)
        fun esc(text: String) = android.text.TextUtils.htmlEncode(text)
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>Orbit</title></head><body style=\"font-family:sans-serif;text-align:center;padding:48px 24px\">" +
            "<h2>${esc(title)}</h2><p>${esc(note)}</p></body></html>"
    }

    /** Brings the app back over the browser; Android may not allow it, and the page then says to press Back. */
    private fun bringBack(context: Context) {
        runCatching {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(it)
            }
        }
    }
}
