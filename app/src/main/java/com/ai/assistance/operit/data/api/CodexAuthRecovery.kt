package com.ai.assistance.operit.data.api

import com.ai.assistance.operit.data.preferences.CodexAuthState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Authentication failures must not enter the chat provider's generic network retry loop. */
open class CodexAuthenticationException(message: String) : IOException(message)

/**
 * [accountId]'s refresh grant was revoked: only signing that account in again brings it back.
 * [refreshToken] is the grant refused, so a newer sign-in of the account is not set aside; it is
 * never part of the message.
 */
class CodexLoginRequiredException(
    message: String,
    val accountId: String,
    internal val refreshToken: String,
) : CodexAuthenticationException(message)

internal data class CodexAuthLease(val state: CodexAuthState, val generation: Long)

internal class CodexHttpException(val status: Int, val expiredToken: Boolean) :
    IOException("Codex HTTP $status")

internal fun isCodexExpiredToken(status: Int, body: String): Boolean =
    status == 401 && runCatching {
        JSONObject(body).optJSONObject("error")?.optString("code") == "token_expired"
    }.getOrDefault(false)

internal class CodexRefreshRejectedException(val status: Int, val requiresLogin: Boolean) :
    IOException("Codex OAuth refresh HTTP $status")

internal data class CodexAuthMessages(
    val notLoggedIn: String,
    val loginChanged: String,
    val loginRequired: String,
    val temporaryFailure: String,
    val recoveryRejected: String,
)

/** Shares one lock for refresh, login and logout. Leases bind retries to a login session, not just an account ID. */
internal class CodexAuthRecovery(
    private val read: () -> CodexAuthState?,
    private val refreshAndSave: suspend (CodexAuthState) -> CodexAuthState,
    private val messages: CodexAuthMessages,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    @Volatile private var generation = 0L
    private var rejectedRefreshToken: String? = null

    suspend fun <T> changeLogin(change: suspend () -> T): T = underLock { endSession ->
        // Invalidate outstanding requests even when a logout's remote revocation fails.
        endSession()
        change()
    }

    /**
     * Runs [change] under the same lock as refresh, sign-in and sign-out; it calls `endSession`
     * when what it changed ends the requests still signed with the account in use.
     */
    suspend fun <T> underLock(change: suspend (endSession: () -> Unit) -> T): T = mutex.withLock {
        change {
            generation++
            rejectedRefreshToken = null
        }
    }

    suspend fun credentials(): CodexAuthLease = mutex.withLock {
        val state = current()
        val valid = if (state.expiresAtMillis - clock() > 300_000) state
            else refresh(state, "expiry_window")
        CodexAuthLease(valid, generation)
    }

    /** Whether no sign-in or sign-out happened since [lease] was handed out. */
    fun isCurrent(lease: CodexAuthLease): Boolean = lease.generation == generation

    suspend fun ensureCurrent(lease: CodexAuthLease) = mutex.withLock {
        checkSession(lease, current())
    }

    suspend fun afterUnauthorized(failed: CodexAuthLease): CodexAuthLease = mutex.withLock {
        val state = current()
        if (failed.generation != generation) throw changedLogin()
        if (state.accountId != failed.state.accountId || state.accessToken != failed.state.accessToken) {
            // Another request refreshed meanwhile, or the pool moved to another signed-in account.
            log("401 recovery reused concurrent refresh or account switch")
            CodexAuthLease(state, generation)
        } else {
            val refreshed = refresh(state, "http_401_token_expired")
            checkSession(failed, refreshed)
            CodexAuthLease(refreshed, generation)
        }
    }

    private fun current(): CodexAuthState =
        read() ?: throw CodexAuthenticationException(messages.notLoggedIn)

    private fun checkSession(lease: CodexAuthLease, state: CodexAuthState) {
        if (lease.generation != generation || lease.state.accountId != state.accountId) throw changedLogin()
    }

    private fun changedLogin() = CodexAuthenticationException(messages.loginChanged)

    private suspend fun refresh(state: CodexAuthState, reason: String): CodexAuthState {
        if (rejectedRefreshToken == state.refreshToken) throw loginRequired(state)
        log("refresh start reason=$reason remainingSeconds=${(state.expiresAtMillis - clock()) / 1000}")
        try {
            val refreshed = persistCodexRefreshBeforeCancellation { refreshAndSave(state) }
            log("refresh succeeded remainingSeconds=${(refreshed.expiresAtMillis - clock()) / 1000}")
            return refreshed
        } catch (error: CancellationException) {
            throw error
        } catch (error: CodexRefreshRejectedException) {
            log("refresh failed httpStatus=${error.status} requiresLogin=${error.requiresLogin}")
            if (error.requiresLogin) {
                rejectedRefreshToken = state.refreshToken
                throw loginRequired(state)
            }
            throw CodexAuthenticationException(messages.temporaryFailure)
        } catch (_: Exception) {
            // Do not copy response bodies, token values or arbitrary exception messages into logs.
            log("refresh failed reason=network_or_invalid_response")
            throw CodexAuthenticationException(messages.temporaryFailure)
        }
    }

    private fun loginRequired(state: CodexAuthState) =
        CodexLoginRequiredException(messages.loginRequired, state.accountId, state.refreshToken)
}

/** One recovery per logical operation, including callers that have their own network retries. */
internal class CodexRecoveryAttempt(private val rejectedMessage: String) {
    var used: Boolean = false
        private set

    suspend fun recover(lease: CodexAuthLease, refresh: suspend (CodexAuthLease) -> CodexAuthLease): CodexAuthLease {
        if (used) throw CodexAuthenticationException(rejectedMessage)
        used = true
        return refresh(lease)
    }
}
