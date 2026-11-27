package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserSignInCatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** Why connecting a mailbox failed, in a form the settings page turns into its own words. */
internal class OrbitMailAuthError(val kind: Kind, val email: String? = null) : IllegalStateException(kind.message) {
    enum class Kind(val message: String) {
        TIMEOUT("The sign-in took too long"),
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
 * Connecting a mailbox, straight between this phone and Google or Microsoft. The provider's sign-in
 * page opens in Orbit's own browser; it ends by sending the tab to the client's return address
 * (`http://localhost/` for Google, `https://localhost` for Microsoft, as Thunderbird's desktop
 * clients have them), which the browser hands back here instead of loading ([BrowserSignInCatch]).
 * The provider names the signed-in address in its ID token, so nothing is typed in first.
 *
 * The new account is tried once over IMAP and SMTP before it is saved, so a wrong account never looks connected.
 */
internal object OrbitMailAuth {
    private const val WAIT_MS = 10 * 60_000L
    private const val GOOGLE_REDIRECT = "http://localhost/"
    private const val MS_REDIRECT = "https://localhost"
    private val random = SecureRandom()

    private fun randomToken(): String = ByteArray(32).also(random::nextBytes)
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

    private fun challenge(verifier: String): String = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

    /**
     * Signs in a [provider] mailbox. [open] shows the provider's page (main thread) in a browser tab
     * with the given id and says whether it could; once the provider answered, [onConnecting] is
     * called while the account is checked and saved. With [expected] (signing in again), only that
     * address is accepted. Null when the user declined or closed the sign-in tab; cancelling stops the wait.
     */
    suspend fun signIn(context: Context, orbitId: String, provider: OrbitMailProvider, expected: String?,
        open: suspend (url: String, tabId: String) -> Boolean, onConnecting: () -> Unit): OrbitMailAccount? {
        val app = context.applicationContext
        val gmail = provider == OrbitMailProvider.GMAIL
        val client = OrbitMailClients.of(provider)
        val redirect = if (gmail) GOOGLE_REDIRECT else MS_REDIRECT
        val verifier = randomToken()
        val state = randomToken()
        val url = Uri.parse(if (gmail) OrbitMailClients.GOOGLE_AUTH else OrbitMailClients.MS_AUTH).buildUpon()
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
        val claims = tokens.idToken?.let(::claims) ?: JSONObject()
        val email = (if (gmail) listOf("email") else listOf("email", "preferred_username", "upn"))
            .map { claims.optString(it).trim() }.firstOrNull { isAddress(it) }
            ?: throw OrbitMailAuthError(OrbitMailAuthError.Kind.NO_ADDRESS)
        if (expected != null && !email.equals(expected, true)) {
            throw OrbitMailAuthError(OrbitMailAuthError.Kind.WRONG_ACCOUNT, email)
        }
        val consumer = !gmail && claims.optString("tid") == OrbitMailClients.MS_CONSUMER_TENANT
        OrbitMailBox.verify(provider, email, tokens.access, consumer)
        return OrbitMailAccounts.save(app, orbitId, provider, email, tokens, consumer, revokesBefore = revokes)
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
}
