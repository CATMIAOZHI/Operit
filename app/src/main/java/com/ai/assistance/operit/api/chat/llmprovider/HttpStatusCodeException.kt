package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.util.AppLogger

internal interface HttpStatusCodeException {
    val statusCode: Int
}

/** A key source backed by a pool of signed-in accounts; it says once that it moved to another account. */
interface AccountSwitchNotices {
    /** The chat note for this chat request's attempt going out with another account than before, or null. */
    suspend fun takeSwitchNotice(): String?

    /**
     * Whether this chat request may retry once more without spending a retry because the pool
     * moved to an account it has not tried yet; each account is granted this once per request.
     */
    suspend fun claimSwitchRetry(): Boolean
}

/**
 * Which account each chat request last signed with and the note still to show, kept per request
 * (its coroutine), since one cached provider can serve several chats at once and a pick made
 * between two messages is not news to the next one.
 */
internal class RequestSwitchNotices {
    private class State {
        var issued: String? = null
        var pending: String? = null
        val freed = HashSet<String>()
    }
    private val states = java.util.WeakHashMap<kotlinx.coroutines.Job, State>()

    private suspend fun state(): State? {
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job] ?: return null
        return synchronized(states) { states.getOrPut(job) { State() } }
    }

    /** This request now signs with [id]; [moved] when getting it moved the pool to it. */
    suspend fun signed(id: String, moved: Boolean, notice: () -> String) {
        val state = state() ?: return
        val before = state.issued
        state.issued = id
        if (moved || (before != null && before != id)) state.pending = notice()
    }

    /** One retry without cost toward [active], once per account, when it is not the one last signed. */
    suspend fun claimRetry(active: () -> String?): Boolean {
        val state = state() ?: return false
        val target = active() ?: return false
        if (target == state.issued || target in state.freed) return false
        state.freed += target
        return true
    }

    suspend fun take(): String? = state()?.let { state -> state.pending.also { state.pending = null } }
}

/**
 * Whether [exception] is a server refusing the signed-in account (401, 402, 429) and the pool has
 * moved to an account this request has not tried: that retry does not spend the retry budget.
 * Anything else, a failed refresh included, retries the usual way.
 */
internal suspend fun freeAccountSwitchRetry(apiKeyProvider: ApiKeyProvider, exception: Exception): Boolean {
    val status = (exception as? HttpStatusCodeException)?.statusCode ?: return false
    if (status != 401 && status != 402 && status != 429) return false
    return (apiKeyProvider as? AccountSwitchNotices)?.claimSwitchRetry() == true
}

/**
 * The note a retry shows: the usual retry message unless a key pool hides it. A retry that moves
 * to another signed-in account returns before this and is told when it goes out.
 */
internal suspend fun retryNotice(
    apiKeyProvider: ApiKeyProvider,
    exception: Exception,
    logTag: String,
    retryMessage: () -> String,
): String? {
    return if (shouldSuppressKeyPoolRateLimitNotice(apiKeyProvider, exception, logTag)) null else retryMessage()
}

/** Tells the chat once when this request goes out with another signed-in account than the last one. */
internal suspend fun announceAccountSwitch(apiKeyProvider: ApiKeyProvider, onNonFatalError: suspend (String) -> Unit) {
    (apiKeyProvider as? AccountSwitchNotices)?.takeSwitchNotice()?.let { onNonFatalError(it) }
}

internal suspend fun shouldSuppressKeyPoolRateLimitNotice(
    apiKeyProvider: ApiKeyProvider,
    exception: Exception,
    logTag: String
): Boolean {
    val httpException = exception as? HttpStatusCodeException ?: return false
    if (httpException.statusCode != 429) return false

    val candidateKeyCount = apiKeyProvider.getCandidateKeyCount()
    val shouldSuppress = candidateKeyCount > 1
    if (shouldSuppress) {
        AppLogger.w(
            logTag,
            "多密钥轮询遇到 429，跳过本次中间提示，继续尝试其他密钥。candidateKeyCount=$candidateKeyCount",
            exception
        )
    }

    return shouldSuppress
}
