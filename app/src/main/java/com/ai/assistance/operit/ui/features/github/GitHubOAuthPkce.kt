package com.ai.assistance.operit.ui.features.github

import android.net.Uri
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal data class GitHubOAuthAuthorization(
    val state: String,
    val codeVerifier: String,
    val authorizationUrl: String
)

internal object GitHubOAuthPkce {
    private val secureRandom = SecureRandom()

    fun createAuthorization(
        clientId: String,
        redirectUri: String,
        scope: String
    ): GitHubOAuthAuthorization {
        require(clientId.isNotBlank()) { "GITHUB_CLIENT_ID is not configured" }
        val state = randomBase64Url(32)
        val codeVerifier = randomBase64Url(32)
        val challenge = codeChallenge(codeVerifier)
        val authorizationUrl =
            Uri.parse("https://github.com/login/oauth/authorize")
                .buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("scope", scope)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()
                .toString()
        return GitHubOAuthAuthorization(state, codeVerifier, authorizationUrl)
    }

    internal fun codeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun randomBase64Url(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
