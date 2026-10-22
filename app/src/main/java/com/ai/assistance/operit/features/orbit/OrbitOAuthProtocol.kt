package com.ai.assistance.operit.features.orbit

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Public-client authorization code flow only. No provider response text reaches a tool or log. */
internal object OrbitOAuthProtocol {
    const val UNSUPPORTED = "Only public-client authorization code + PKCE (S256), without client_secret, is supported. " +
        "If the service requires client_secret, device flow or another redirect URI, use request_api_key instead."
    data class Tokens(val accessToken: String, val refreshToken: String?, val expiresAt: Long, val scope: String?)

    fun random(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun endpoint(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "OAuth endpoints must be https URLs without a user name, password or fragment."
        }
        require(url.queryParameterNames.none {
            it.lowercase() in setOf("client_secret", "access_token", "refresh_token", "code", "code_verifier")
        }) { UNSUPPORTED }
        return url
    }

    fun validate(request: OrbitVault.Request) {
        endpoint(request.authorizationUrl)
        endpoint(request.tokenUrl)
        require(request.clientId.isNotBlank() && request.clientId.length <= 500) { "Missing or invalid client_id" }
        require(request.scope.length <= 2000) { "scope is too long" }
        require(request.apiHosts.size in 1..5 && request.apiHosts.all {
            it.matches(Regex("[a-z0-9](?:[a-z0-9.-]{1,118}[a-z0-9])")) && it.contains('.') && !it.contains("..")
        }) { "api_hosts must list 1-5 host names" }
    }

    fun authorizationUrl(request: OrbitVault.Request, redirect: String, state: String, verifier: String): String {
        validate(request)
        return endpoint(request.authorizationUrl).newBuilder()
            .setQueryParameter("response_type", "code").setQueryParameter("client_id", request.clientId)
            .setQueryParameter("redirect_uri", redirect).setQueryParameter("scope", request.scope)
            .removeAllQueryParameters("response_mode")
            .setQueryParameter("state", state).setQueryParameter("code_challenge", challenge(verifier))
            .setQueryParameter("code_challenge_method", "S256").build().toString()
    }

    fun needsRefresh(expiresAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        expiresAt > 0 && expiresAt <= now + 30_000

    fun tokens(json: JSONObject, now: Long = System.currentTimeMillis()): Tokens {
        check(!json.has("error")) { "OAuth token exchange was refused. $UNSUPPORTED" }
        val access = json.opt("access_token") as? String ?: error("Invalid OAuth token response")
        require(access.length in 1..16000 && access.all { it.code in 33..126 }) { "Invalid OAuth token response" }
        require(json.optString("token_type").equals("Bearer", true)) { "Only Bearer OAuth tokens are supported" }
        val refresh = if (json.has("refresh_token"))
            json.opt("refresh_token") as? String ?: error("Invalid OAuth refresh response") else null
        require(refresh == null || refresh.length <= 16000 && refresh.all { it.code in 33..126 }) { "Invalid OAuth refresh response" }
        val expires = if (!json.has("expires_in")) 0L else {
            val seconds = json.optLong("expires_in", -1)
            require(seconds > 0 && seconds <= (Long.MAX_VALUE - now) / 1000) { "Invalid OAuth expiry" }
            now + seconds * 1000
        }
        return Tokens(access, refresh, expires, if (json.has("scope")) json.optString("scope").take(2000) else null)
    }
}
