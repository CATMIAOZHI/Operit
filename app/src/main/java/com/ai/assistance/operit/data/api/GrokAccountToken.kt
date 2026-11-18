package com.ai.assistance.operit.data.api

import java.io.IOException

/**
 * The Grok account's access token for the Grok packages, with a user-facing reason when the
 * refresh fails. Only a revoked or spent grant asks for a new login; any other refusal names
 * the server's own reason so a passing fault is not mistaken for a dead login.
 */
internal suspend fun ProviderAccountManager.grokAccessToken(): String = try {
    validAccount().accessToken
} catch (error: AccountHttpException) {
    throw IOException(
        when {
            error.loginRevoked -> "Grok 登录已失效（xAI 作废了这次登录），请重新登录 Grok 账号"
            error.status in setOf(400, 401) ->
                "刷新 Grok 登录被拒绝（HTTP ${error.status}" + (error.oauthError?.let { "，$it" } ?: "") +
                    "），请稍后再试；一直这样再重新登录 Grok 账号"
            else -> "刷新 Grok 登录时服务器出错（HTTP ${error.status}），请稍后再试，不用重新登录"
        },
        error,
    )
} catch (error: IOException) {
    throw IOException("连不上 Grok 登录服务器，请检查网络后稍后再试，不用重新登录", error)
}
