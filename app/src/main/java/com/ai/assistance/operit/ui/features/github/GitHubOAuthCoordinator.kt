package com.ai.assistance.operit.ui.features.github

import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.data.api.GitHubApiService
import com.ai.assistance.operit.data.api.GitHubOAuthBrokerService
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import com.ai.assistance.operit.data.preferences.GitHubUser

class GitHubOAuthCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val githubAuth = GitHubAuthPreferences.getInstance(appContext)
    private val githubApiService = GitHubApiService(appContext)
    private val oauthBrokerService by lazy { GitHubOAuthBrokerService() }

    suspend fun createExternalAuthorizationUrl(): String {
        val authorization =
            GitHubOAuthPkce.createAuthorization(
                clientId = GitHubAuthPreferences.GITHUB_CLIENT_ID,
                redirectUri = GitHubAuthPreferences.GITHUB_REDIRECT_URI,
                scope = GitHubAuthPreferences.GITHUB_SCOPE
            )
        // Constructing the broker here fails before the browser opens when its URL is missing.
        oauthBrokerService
        githubAuth.savePendingOAuthRequest(authorization.state, authorization.codeVerifier)
        return authorization.authorizationUrl
    }

    suspend fun completeExternalLogin(uri: Uri): Result<GitHubUser> {
        return completeLoginFromRedirect(uri)
    }

    suspend fun completeLoginFromRedirect(uri: Uri): Result<GitHubUser> {
        if (!GitHubAuthPreferences.isOAuthRedirectUri(uri)) {
            return Result.failure(IllegalArgumentException("Unsupported OAuth redirect URI"))
        }

        val pendingRequest = githubAuth.consumePendingOAuthRequest()
            ?: return Result.failure(IllegalStateException("Missing pending OAuth request"))
        val returnedState = uri.getQueryParameter("state")
        if (returnedState.isNullOrBlank() || returnedState != pendingRequest.state) {
            return Result.failure(IllegalStateException("OAuth state mismatch"))
        }

        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val errorDescription = uri.getQueryParameter("error_description").orEmpty()
            return Result.failure(
                IllegalStateException(errorDescription.ifBlank { error })
            )
        }

        val code = uri.getQueryParameter("code")
            ?: return Result.failure(IllegalStateException("Missing authorization code"))

        return completeLoginWithCode(code, pendingRequest.codeVerifier)
    }

    private suspend fun completeLoginWithCode(
        code: String,
        codeVerifier: String
    ): Result<GitHubUser> {
        return runCatching {
            val tokenResponse =
                oauthBrokerService.exchangeAuthorizationCode(
                    code = code,
                    codeVerifier = codeVerifier,
                    redirectUri = GitHubAuthPreferences.GITHUB_REDIRECT_URI
                ).getOrElse { error -> throw error }

            val user = githubApiService.getCurrentUser(tokenResponse.accessToken).getOrElse { error ->
                throw error
            }

            githubAuth.saveAuthInfo(
                accessToken = tokenResponse.accessToken,
                tokenType = tokenResponse.tokenType,
                expiresIn = tokenResponse.expiresIn,
                refreshToken = tokenResponse.refreshToken,
                userInfo = user,
                grantedScope = tokenResponse.scope
            )
            user
        }
    }
}
