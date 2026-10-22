package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object OrbitOAuth {
    private const val PENDING_MS = 10 * 60_000L
    private val pendingLock = Mutex()
    private val client by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS).build()
    }

    fun redirectUri(context: Context) = "${context.packageName}.orbit-oauth://callback/oauth2"
    private fun store(context: Context, orbitId: String) = OrbitSecretFile.forOrbit(context, orbitId, "oauth")

    /** Called only by the user's Continue button. Pending verifier/state survive an ordinary process restart. */
    suspend fun start(context: Context, request: OrbitVault.Request) {
        OrbitOAuthProtocol.validate(request)
        val browser = CustomTabsClient.getPackageName(context, null)
            ?: error("No browser with Custom Tabs")
        val state = OrbitOAuthProtocol.random()
        val verifier = OrbitOAuthProtocol.random()
        val url = OrbitOAuthProtocol.authorizationUrl(request, redirectUri(context), state, verifier)
        withContext(Dispatchers.IO) {
            pendingLock.withLock {
                check(OrbitPreferences.getInstance(context).current().orbitForChat(request.chatId)?.id == request.orbitId)
                store(context, request.orbitId).write(JSONObject()
                    .put("state", state).put("verifier", verifier).put("created_at", System.currentTimeMillis())
                    .put("request", OrbitChatCards.vault(request)).toString())
            }
        }
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().apply {
                intent.setPackage(browser)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { pendingLock.withLock { store(context, request.orbitId).delete() } }
            throw e
        }
    }

    /** null is a user cancellation. Invalid, expired and already-used callbacks never exchange a code. */
    suspend fun complete(context: Context, callback: Uri): Boolean? = withContext(Dispatchers.IO) {
        val redirect = Uri.parse(redirectUri(context))
        require(callback.scheme == redirect.scheme && callback.host == redirect.host && callback.path == redirect.path &&
            callback.port == -1 && callback.userInfo == null && callback.fragment == null) { "Invalid OAuth callback" }
        val state = callback.getQueryParameter("state").orEmpty()
        require(state.length == 43 && callback.getQueryParameters("state").size == 1) { "Invalid OAuth state" }
        val pending = pendingLock.withLock {
            val settings = OrbitPreferences.getInstance(context).current()
            var found: Pair<OrbitVault.Request, String>? = null
            for (orbit in settings.orbits) {
                val file = store(context, orbit.id)
                val json = file.read()?.let(::JSONObject) ?: continue
                val age = System.currentTimeMillis() - json.optLong("created_at")
                if (age !in 0..PENDING_MS) { file.delete(); continue }
                if (json.optString("state") != state) continue
                // Single use, including an explicit provider cancellation.
                file.delete()
                val request = OrbitChatCards.vaultRequest(json.getJSONObject("request")) ?: break
                check(request.orbitId == orbit.id && settings.orbitForChat(request.chatId)?.id == orbit.id)
                found = request to json.getString("verifier")
                break
            }
            checkNotNull(found) { "OAuth session expired; tap the card again" }
        }
        val (request, verifier) = pending
        if (callback.getQueryParameter("error") == "access_denied") return@withContext null
        check(callback.getQueryParameter("error") == null) { OrbitOAuthProtocol.UNSUPPORTED }
        val code = callback.getQueryParameter("code").orEmpty()
        require(code.isNotEmpty() && code.length <= 8000 && callback.getQueryParameters("code").size == 1)
        val tokens = exchange(request.tokenUrl, FormBody.Builder()
            .add("grant_type", "authorization_code").add("client_id", request.clientId)
            .add("code", code).add("redirect_uri", redirectUri(context)).add("code_verifier", verifier).build())
        val orbit = OrbitPreferences.getInstance(context).current().orbitForChat(request.chatId)
        check(orbit?.id == request.orbitId) { "Orbit no longer exists" }
        val id = OrbitVault.saveOAuth(context, request, tokens)
        OrbitVaultNotice.send(context, checkNotNull(orbit), request, id)
        true
    }

    /** Called on IO under the credential's refresh lock, so token rotation cannot race another request. */
    fun refresh(auth: OrbitVault.OAuth): OrbitOAuthProtocol.Tokens = try {
        exchange(auth.tokenUrl, FormBody.Builder().add("grant_type", "refresh_token")
            .add("client_id", auth.clientId).add("refresh_token", auth.refreshToken).build())
    } catch (e: Exception) {
        // Never propagate a provider response or a request containing a token.
        throw IllegalStateException("OAuth refresh failed. Try again later, or use request_oauth to reconnect. " +
            OrbitOAuthProtocol.UNSUPPORTED)
    }

    private fun exchange(url: String, form: FormBody): OrbitOAuthProtocol.Tokens {
        val request = Request.Builder().url(OrbitOAuthProtocol.endpoint(url))
            .header("Accept", "application/json").post(form).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "OAuth token exchange failed. ${OrbitOAuthProtocol.UNSUPPORTED}" }
            val source = checkNotNull(response.body).source()
            // A bounded body also avoids storing enormous provider responses in memory.
            source.request(65_537)
            require(source.buffer.size <= 65_536) { "OAuth token response is too large" }
            return OrbitOAuthProtocol.tokens(JSONObject(source.readUtf8()))
        }
    }
}
