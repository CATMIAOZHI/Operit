package com.ai.assistance.operit.data.api

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Account pool rules shared by every sign-in provider: one account is used until a server
 * refuses it, then the next one that can still answer, in the order the accounts were added.
 */
internal object AccountPoolRules {
    /** How long an account a server refused for its quota or rate is skipped. */
    const val REST_MILLIS = 10 * 60 * 1000L

    /**
     * The first id after [activeId], going round the list, that [usable] accepts; null when no
     * other account can be used. [activeId] itself is never returned.
     */
    fun nextUsable(ids: List<String>, activeId: String?, usable: (String) -> Boolean): String? {
        if (ids.isEmpty()) return null
        val start = ids.indexOf(activeId)
        for (step in 1..ids.size) {
            val id = ids[(start + step).mod(ids.size)]
            if (id != activeId && usable(id)) return id
        }
        return null
    }
}

/** Accounts a server just refused, skipped until their rest is over. Not saved: a restart tries them again. */
internal class RestingAccounts(private val clock: () -> Long = System::currentTimeMillis) {
    private val until = HashMap<String, Long>()

    @Synchronized fun rest(id: String) { until[id] = clock() + AccountPoolRules.REST_MILLIS }
    @Synchronized fun clear(id: String) { until.remove(id) }
    @Synchronized fun isResting(id: String): Boolean {
        val end = until[id] ?: return false
        if (end > clock()) return true
        until.remove(id)
        return false
    }
}

/** The sign-in in use is over and no other account can answer: only a new login helps, so it is not retried. */
class AccountLoginExpiredException(message: String) : java.io.IOException(message)

/** Told which signed-in account a server refused, so the next request goes out with another. */
internal fun interface AccountRefusalSink {
    fun accountRefused(request: Request, status: Int)
}

/** Reports refusals of a signed-in account (401, 402, 429) to its pool; the response passes on unchanged. */
internal class AccountRefusalInterceptor(private val sink: AccountRefusalSink) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 401 || response.code == 402 || response.code == 429) {
            try {
                // The request that got this answer, after any redirect: one that lost its
                // credentials on the way refused no account.
                sink.accountRefused(response.request, response.code)
            } catch (error: Exception) {
                com.ai.assistance.operit.util.AppLogger.w("AccountPool", "Refusal could not be recorded: ${error.javaClass.simpleName}")
            }
        }
        return response
    }
}

/** The bearer token a request was signed with, from either header the providers use. */
internal fun Request.signedToken(): String? =
    header("Authorization")?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        ?: header("x-api-key")?.trim()
/** One client per base that also reports refusals to the pool, kept so each request does not build its own. */
internal class PooledClients(private val sink: AccountRefusalSink) {
    @Volatile private var cached: Pair<okhttp3.OkHttpClient, okhttp3.OkHttpClient>? = null

    fun around(base: okhttp3.OkHttpClient): okhttp3.OkHttpClient {
        cached?.takeIf { it.first === base }?.let { return it.second }
        return base.newBuilder().addInterceptor(AccountRefusalInterceptor(sink)).build().also { cached = base to it }
    }
}