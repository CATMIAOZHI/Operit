package com.ai.assistance.operit.data.api

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.CodexAuthPreferences
import com.ai.assistance.operit.data.preferences.CodexAuthState
import com.ai.assistance.operit.data.preferences.CodexStoredUsageSnapshot
import com.ai.assistance.operit.data.preferences.CodexUsagePreferences
import com.ai.assistance.operit.util.AppLogger
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class CodexAuthManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = CodexAuthPreferences.getInstance(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val oauthClient = CodexOAuthClient(client = httpClient)
    private val usageClient = CodexUsageClient(client = httpClient)
    private val usagePreferences = CodexUsagePreferences.getInstance(context)
    internal val authMessages = CodexAuthMessages(
        context.getString(R.string.codex_auth_sign_in_required),
        context.getString(R.string.codex_auth_login_changed),
        context.getString(R.string.codex_auth_login_required),
        context.getString(R.string.codex_auth_refresh_temporary),
        context.getString(R.string.codex_auth_recovery_rejected),
    )
    private val recovery = CodexAuthRecovery(
        read = preferences::currentState,
        refreshAndSave = ::refreshAccessToken,
        messages = authMessages,
        log = { AppLogger.i(TAG, it) },
    )

    val authState: StateFlow<CodexAuthState?> = preferences.authState
    /** Every signed-in ChatGPT account, in the order added. */
    val accounts: StateFlow<List<CodexAuthState>> = preferences.accounts
    val usageSnapshotFlow: Flow<CodexStoredUsageSnapshot?> = usagePreferences.snapshotFlow
    private val resting = RestingAccounts()
    private val poolLock = Any()

    suspend fun saveLoginTokens(tokens: CodexOAuthTokenResponse): CodexAuthState = recovery.changeLogin {
        val completeTokens = tokens.requireComplete()
        val accessToken = requireNotNull(completeTokens.accessToken)
        val idToken = requireNotNull(completeTokens.idToken)
        val refreshToken = requireNotNull(completeTokens.refreshToken)
        val accessClaims = CodexOAuthProtocol.parseJwtClaims(accessToken)
            ?: throw IOException("Codex access token is not a valid JWT")
        val idClaims = CodexOAuthProtocol.parseJwtClaims(idToken)
        val accountId = idClaims?.accountId ?: accessClaims.accountId
            ?: throw IOException("Codex token has no ChatGPT account ID")
        val expiresAt = accessClaims.expiresAtMillis
            ?: completeTokens.expiresInSeconds?.let { System.currentTimeMillis() + it * 1000L }
            ?: throw IOException("Codex access token has no expiration")
        val state = CodexAuthState(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = expiresAt,
            accountId = accountId,
            residency = accessClaims.residency ?: idClaims?.residency,
            email = idClaims?.email ?: accessClaims.email,
        )
        withContext(Dispatchers.IO) {
            synchronized(poolLock) {
                resting.clear(state.accountId)
                preferences.save(state, activate = true)
            }
        }
        state
    }

    suspend fun getValidAccessToken(): String = credentials().state.accessToken

    internal suspend fun credentials(): CodexAuthLease = takeCredentials().first

    /**
     * The account in use, refreshed, and whether this call itself moved to it (so the chat can be
     * told). One resting after a refusal or whose sign-in was revoked gives way to the next account
     * that can answer; a revoked one with nothing else to use needs a login.
     */
    internal suspend fun takeCredentials(): Pair<CodexAuthLease, Boolean> {
        var moved = false
        while (true) {
            val current = preferences.currentState()
            if (current != null && !usable(current)) {
                val step = withContext(Dispatchers.IO) { moveOffUnusable(current.accountId) }
                if (step == 1) moved = true
                if (step >= 0) continue
                if (preferences.currentState()?.needsLogin == true) {
                    throw CodexAuthenticationException(authMessages.loginRequired)
                }
            }
            val lease = try {
                recovery.credentials()
            } catch (error: CodexLoginRequiredException) {
                val step = withContext(Dispatchers.IO) { setAsideRevoked(error) }
                if (step < 0) throw error
                if (step == 1) moved = true
                continue
            }
            // Removed while it was being refreshed: the next account in use answers instead.
            if (preferences.accounts.value.none { it.accountId == lease.state.accountId }) continue
            return lease to moved
        }
    }

    /**
     * Under the pool lock, moves off [accountId] when it is still in use and still cannot answer:
     * 1 moved to another account, 0 the account in use changed meanwhile (use that one), -1 stay.
     */
    private fun moveOffUnusable(accountId: String): Int = synchronized(poolLock) {
        val active = preferences.currentState() ?: return@synchronized -1
        when {
            active.accountId != accountId -> 0
            usable(active) -> -1
            switchFrom(accountId) -> 1
            else -> -1
        }
    }
    internal suspend fun ensureCurrent(lease: CodexAuthLease) = recovery.ensureCurrent(lease)
    internal suspend fun refreshAfterUnauthorized(lease: CodexAuthLease): CodexAuthLease =
        recovery.afterUnauthorized(lease)

    /**
     * Marks the account whose refresh grant [error] reports revoked, only while that grant is still
     * the one stored: 1 moved to another account, 0 try again with the account now in use (signed
     * in again, or another one picked meanwhile), -1 nothing else can answer.
     */
    private fun setAsideRevoked(error: CodexLoginRequiredException): Int = synchronized(poolLock) {
        val stored = preferences.accounts.value.firstOrNull { it.accountId == error.accountId }
        // Signed in again since: that newer sign-in is used as it is.
        if (stored != null && stored.refreshToken != error.refreshToken) return@synchronized 0
        if (stored != null) preferences.markNeedsLogin(error.accountId)
        val active = preferences.currentState() ?: return@synchronized -1
        when {
            active.accountId != error.accountId -> 0
            switchFrom(error.accountId) -> 1
            else -> -1
        }
    }

    /**
     * A chat request signed with [lease] got a 401. An expired token is refreshed; a sign-in the
     * server no longer accepts is set aside and the next account that works takes over. A 401 for
     * a token no longer stored (refreshed or signed in again meanwhile) only retries with the
     * current one. Throws when no account is left to try.
     */
    internal suspend fun recoverUnauthorized(lease: CodexAuthLease, expiredToken: Boolean) {
        if (!expiredToken) {
            val moved = withContext(Dispatchers.IO) {
                synchronized(poolLock) {
                    val stored = preferences.accounts.value.firstOrNull { it.accountId == lease.state.accountId }
                    if (stored == null || stored.accessToken != lease.state.accessToken ||
                        !recovery.isCurrent(lease)) {
                        preferences.currentState() != null
                    } else {
                        preferences.markNeedsLogin(lease.state.accountId)
                        switchFrom(lease.state.accountId)
                    }
                }
            }
            if (!moved) throw CodexAuthenticationException(authMessages.loginRequired)
            return
        }
        try {
            refreshAfterUnauthorized(lease)
        } catch (error: CodexLoginRequiredException) {
            if (withContext(Dispatchers.IO) { setAsideRevoked(error) } < 0) throw error
        }
    }

    /**
     * [lease]'s account was refreshed and still refused: it rests and the next account that can
     * answer takes over; true when requests now use another account.
     */
    internal suspend fun setAsideAfterFailedRecovery(lease: CodexAuthLease): Boolean = withContext(Dispatchers.IO) {
        synchronized(poolLock) {
            val active = preferences.currentState() ?: return@synchronized false
            // Signed in again, refreshed or moved on since: retry with what is stored now.
            if (active.accountId != lease.state.accountId || active.accessToken != lease.state.accessToken ||
                !recovery.isCurrent(lease)) return@synchronized true
            resting.rest(lease.state.accountId)
            switchFrom(lease.state.accountId)
        }
    }

    private fun usable(state: CodexAuthState) = !state.needsLogin && !resting.isResting(state.accountId)

    /**
     * Moves off [accountId] to the next account that can answer; true when requests now use
     * another account (also when one already moved meanwhile).
     */
    private fun switchFrom(accountId: String): Boolean = synchronized(poolLock) {
        val active = preferences.currentState() ?: return@synchronized false
        if (active.accountId != accountId) return@synchronized true
        val list = preferences.accounts.value
        val next = AccountPoolRules.nextUsable(list.map { it.accountId }, accountId) { id ->
            list.first { it.accountId == id }.let(::usable)
        } ?: return@synchronized false
        preferences.setActive(next)
        AppLogger.i(TAG, "Switched to the next signed-in ChatGPT account")
        true
    }

    /** Makes [accountId] the account requests use, as the user picked it. */
    suspend fun setActive(accountId: String) = withContext(Dispatchers.IO) {
        synchronized(poolLock) {
            resting.clear(accountId)
            preferences.setActive(accountId)
        }
    }

    /** Accounts that can answer a request now; at least one while any is signed in. */
    fun usableAccountCount(): Int {
        val list = preferences.accounts.value
        return if (list.isEmpty()) 0 else list.count(::usable).coerceAtLeast(1)
    }

    /** The short chat note for having moved to [state]. */
    fun switchedNotice(state: CodexAuthState): String = appContext.getString(
        R.string.provider_account_pool_switched, state.email?.takeIf { it.isNotBlank() } ?: state.accountId)

    /** A spent quota or rate (402, 429) rests the account the request was signed with; the next one takes over. */
    private val refusalSink = AccountRefusalSink { request, status ->
        if (status == 401) return@AccountRefusalSink
        val accountId = request.header("ChatGPT-Account-ID") ?: return@AccountRefusalSink
        val token = request.signedToken() ?: return@AccountRefusalSink
        synchronized(poolLock) {
            val used = preferences.accounts.value.firstOrNull { it.accountId == accountId }
            // Only the sign-in still stored counts: a request from before a re-login says nothing about it.
            if (used == null || used.accessToken != token) return@AccountRefusalSink
            resting.rest(accountId)
            switchFrom(accountId)
        }
    }
    private val pooledClients = PooledClients(refusalSink)

    /** [base] for chat requests, also telling the pool which account a server refused. */
    fun requestClient(base: OkHttpClient): OkHttpClient = pooledClients.around(base)

    internal suspend fun <T> withAuthRecovery(block: suspend (CodexAuthState) -> T): T {
        var lease = credentials()
        val attempt = CodexRecoveryAttempt(authMessages.recoveryRejected)
        while (true) {
            ensureCurrent(lease)
            try {
                val result = block(lease.state)
                ensureCurrent(lease)
                return result
            } catch (error: CodexHttpException) {
                if (error.status != 401) throw error
                if (!error.expiredToken) throw CodexAuthenticationException(authMessages.loginRequired)
                lease = attempt.recover(lease, ::refreshAfterUnauthorized)
            }
        }
    }

    private suspend fun refreshAccessToken(current: CodexAuthState): CodexAuthState {
        val response = oauthClient.refreshAccessToken(current.refreshToken)
        val accessToken = response.accessToken
            ?: throw IOException("Codex refresh response has no access token")
        val accessClaims = CodexOAuthProtocol.parseJwtClaims(accessToken)
            ?: throw IOException("Codex refreshed access token is not a valid JWT")
        val idClaims = response.idToken?.let(CodexOAuthProtocol::parseJwtClaims)
        val accountId = idClaims?.accountId ?: accessClaims.accountId ?: current.accountId
        val expiresAt = accessClaims.expiresAtMillis
            ?: response.expiresInSeconds?.let { System.currentTimeMillis() + it * 1000L }
            ?: throw IOException("Codex refreshed access token has no expiration")
        val updated = current.copy(
            accessToken = accessToken,
            refreshToken = response.refreshToken ?: current.refreshToken,
            expiresAtMillis = expiresAt,
            accountId = accountId,
            residency = accessClaims.residency ?: idClaims?.residency ?: current.residency,
            email = idClaims?.email ?: accessClaims.email ?: current.email,
            needsLogin = false,
        )
        // Saved in the row it refreshed, even if the server now names the account differently.
        withContext(Dispatchers.IO) { synchronized(poolLock) { preferences.save(updated, replacing = current.accountId) } }
        return updated
    }

    /** Signs out the account in use; the next one that works takes over. */
    suspend fun logout() {
        preferences.currentState()?.accountId?.let { removeAccount(it) }
    }

    /**
     * Signs [accountId] out: it leaves the list at once, and its grant is then revoked in the
     * background where the server allows. Removing the account in use ends the requests still signed with it; removing
     * another one leaves them alone.
     */
    suspend fun removeAccount(accountId: String) {
        // Under the refresh lock, so a refresh of this account finishes first and the grant
        // revoked is the newest one.
        val removed = recovery.underLock { endSession ->
            withContext(NonCancellable + Dispatchers.IO) {
                synchronized(poolLock) {
                    val wasInUse = preferences.currentState()?.accountId == accountId
                    removeLocally(accountId)?.also { if (wasInUse) endSession() }
                }
            }
        } ?: return
        // Best effort and off the caller: the account is already gone here, whatever the server says.
        revokeScope.launch {
            try {
                oauthClient.revokeToken(removed.refreshToken, "refresh_token")
            } catch (error: Exception) {
                AppLogger.e(TAG, "Failed to revoke Codex OAuth token: ${error.javaClass.simpleName}")
            }
        }
    }
    private val revokeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private fun removeLocally(accountId: String): CodexAuthState? = synchronized(poolLock) {
        val list = preferences.accounts.value
        val removed = list.firstOrNull { it.accountId == accountId } ?: return@synchronized null
        resting.clear(accountId)
        preferences.remove(accountId) { left ->
            AccountPoolRules.nextUsable(list.map { it.accountId }, accountId) { id ->
                left.firstOrNull { it.accountId == id }?.let(::usable) == true
            } ?: left.firstOrNull()?.accountId
        }
        removed
    }

    fun currentAccountId(): String? = preferences.currentState()?.accountId

    fun currentResidency(): String? = preferences.currentState()?.residency

    fun accountForAccessToken(accessToken: String): CodexAuthState =
        preferences.accounts.value.firstOrNull { it.accessToken == accessToken }
            ?: throw IOException("Codex login changed; retry with the current account")

    suspend fun fetchUsage(): Result<CodexUsageSnapshot> {
        return try {
            val (accountId, result) = withAuthRecovery { account ->
                account.accountId to usageClient.fetch(account.accessToken, account.accountId, account.residency).getOrThrow()
            }
            usagePreferences.save(accountId, result)
            Result.success(result)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to prepare Codex usage request", error)
            Result.failure(error)
        }
    }

    companion object {
        private const val TAG = "CodexAuthManager"

        @Volatile
        private var instance: CodexAuthManager? = null

        fun getInstance(context: Context): CodexAuthManager {
            return instance ?: synchronized(this) {
                instance ?: CodexAuthManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

/** A rotated refresh token must be saved even if the caller stops the chat during the request. */
internal suspend fun <T> persistCodexRefreshBeforeCancellation(refreshAndSave: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    val result = withContext(NonCancellable) { refreshAndSave() }
    currentCoroutineContext().ensureActive()
    return result
}
