package com.ai.assistance.operit.data.api

import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
private data class GitHubOAuthExchangeRequest(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String
)

@Serializable
data class GitHubOAuthExchangeResponse(
    val accessToken: String,
    val tokenType: String = "bearer",
    val scope: String,
    val expiresIn: Long? = null,
    val refreshToken: String? = null
)

/** Exchanges a GitHub authorization code without placing the OAuth client secret in the APK. */
class GitHubOAuthBrokerService(
    brokerBaseUrl: String = ""
) {
    private val brokerOrigin = requireBrokerOrigin(brokerBaseUrl)
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): Result<GitHubOAuthExchangeResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload =
                    json.encodeToString(
                        GitHubOAuthExchangeRequest(
                            code = code,
                            codeVerifier = codeVerifier,
                            redirectUri = redirectUri
                        )
                    )
                val request =
                    Request.Builder()
                        .url(brokerOrigin.newBuilder().addPathSegments(EXCHANGE_PATH).build())
                        .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                        .addHeader("Accept", "application/json")
                        .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException(
                            "GitHub OAuth broker request failed: HTTP ${response.code}"
                        )
                    }
                    val responseBody =
                        response.body?.string()
                            ?: throw IllegalStateException("GitHub OAuth broker returned an empty response")
                    val tokenResponse =
                        try {
                            json.decodeFromString<GitHubOAuthExchangeResponse>(responseBody)
                        } catch (_: Exception) {
                            // Serialization errors may quote the source JSON. Never let a response
                            // containing access or refresh tokens escape this boundary.
                            AppLogger.e(TAG, "GitHub OAuth broker returned a malformed token response")
                            throw IllegalStateException(MALFORMED_RESPONSE_MESSAGE)
                        }
                    if (
                        tokenResponse.accessToken.isBlank() ||
                            tokenResponse.tokenType.isBlank() ||
                            tokenResponse.scope.isBlank()
                    ) {
                        throw IllegalStateException(MALFORMED_RESPONSE_MESSAGE)
                    }
                    tokenResponse
                }
            }.onFailure { error ->
                AppLogger.e(TAG, "GitHub OAuth broker exchange failed", error)
            }
        }

    companion object {
        private const val TAG = "GitHubOAuthBroker"
        private const val EXCHANGE_PATH = "oauth/github/exchange"
        private const val MALFORMED_RESPONSE_MESSAGE =
            "GitHub OAuth broker returned an invalid response"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        internal fun requireBrokerOrigin(rawUrl: String) =
            rawUrl.trim().trimEnd('/').toHttpUrlOrNull()?.takeIf { url ->
                url.isHttps &&
                    url.username.isEmpty() &&
                    url.password.isEmpty() &&
                    url.query == null &&
                    url.fragment == null
            } ?: throw IllegalStateException(
                "GITHUB_OAUTH_BROKER_BASE_URL must be a valid HTTPS URL"
            )
    }
}
