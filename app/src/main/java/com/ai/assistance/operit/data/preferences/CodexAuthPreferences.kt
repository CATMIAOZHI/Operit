package com.ai.assistance.operit.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ai.assistance.operit.util.AppLogger
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Credentials shared by all Codex model configurations in this app. */
data class CodexAuthState(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val accountId: String,
    val residency: String? = null,
    val email: String? = null,
    /** The refresh grant was revoked; this account is skipped until it is signed in again. */
    val needsLogin: Boolean = false,
)

/** Every signed-in ChatGPT account, in the order added; [authState] is the one requests use. */
class CodexAuthPreferences private constructor(context: Context) {
    private val appContext = context.applicationContext
    private var preferences = createPreferences(appContext)
    private val lock = Any()

    private var accountList: List<CodexAuthState>
    private var activeId: String?
    init {
        val (list, active) = readPool()
        accountList = list
        activeId = active
    }

    private val _accounts = MutableStateFlow(accountList)
    val accounts: StateFlow<List<CodexAuthState>> = _accounts.asStateFlow()
    private val _authState = MutableStateFlow(active())
    val authState: StateFlow<CodexAuthState?> = _authState.asStateFlow()

    fun currentState(): CodexAuthState? = _authState.value

    private fun active() = accountList.firstOrNull { it.accountId == activeId }

    /**
     * Saves [state] in the row of [replacing] (its own account by default; a new account joins the
     * end). [activate] makes it the one requests use, as a new sign-in is; a refresh only replaces
     * its row, also when the server now names the account differently.
     */
    fun save(state: CodexAuthState, activate: Boolean = false, replacing: String = state.accountId) {
        require(state.accessToken.isNotBlank()) { "Codex access token is empty" }
        require(state.refreshToken.isNotBlank()) { "Codex refresh token is empty" }
        require(state.accountId.isNotBlank()) { "Codex account ID is empty" }
        require(state.expiresAtMillis > 0L) { "Codex token expiration is invalid" }
        synchronized(lock) {
            val known = accountList.any { it.accountId == replacing }
            // A refresh for an account removed meanwhile does not bring it back.
            if (!known && !activate) return
            val list = if (known) {
                accountList.filter { it.accountId != state.accountId || it.accountId == replacing }
                    .map { if (it.accountId == replacing) state else it }
            } else {
                accountList.filter { it.accountId != state.accountId } + state
            }
            val active = when {
                activate || activeId == null -> state.accountId
                activeId == replacing -> state.accountId
                else -> activeId
            }
            commit(list, active)
        }
    }

    /** Makes [accountId] the account requests use. */
    fun setActive(accountId: String) = synchronized(lock) {
        if (accountList.none { it.accountId == accountId } || activeId == accountId) return@synchronized
        commit(accountList, accountId)
    }

    /** Marks [accountId]'s sign-in as revoked. */
    fun markNeedsLogin(accountId: String) = synchronized(lock) {
        if (accountList.none { it.accountId == accountId && !it.needsLogin }) return@synchronized
        commit(accountList.map { if (it.accountId == accountId) it.copy(needsLogin = true) else it }, activeId)
    }

    /** Removes [accountId]; [nextActive] picks the account used next when it was the one in use. */
    fun remove(accountId: String, nextActive: (List<CodexAuthState>) -> String?) = synchronized(lock) {
        if (accountList.none { it.accountId == accountId }) return@synchronized
        val left = accountList.filter { it.accountId != accountId }
        commit(left, if (activeId != accountId) activeId else nextActive(left))
    }

    fun clear() = synchronized(lock) {
        preferences.edit().clear().apply()
        accountList = emptyList()
        activeId = null
        _accounts.value = emptyList()
        _authState.value = null
    }

    private fun commit(list: List<CodexAuthState>, active: String?) {
        val raw = JSONArray(list.map(::encode)).toString()
        fun write() = preferences.edit().putString(KEY_ACCOUNTS, raw).apply {
            if (active == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, active)
            LEGACY_KEYS.forEach(::remove)
        }.commit()
        // commit: a rotated refresh token lost to a process kill would leave only the spent one.
        if (!write() && !write()) AppLogger.e(TAG, "Codex sign-in could not be written to storage; kept in memory only")
        accountList = list
        activeId = active
        _accounts.value = list
        _authState.value = active()
    }

    private fun readPool(): Pair<List<CodexAuthState>, String?> {
        return try {
            readPoolFromPreferences()
        } catch (error: SecurityException) {
            // Restored encrypted values can be unreadable when Android Keystore kept the key
            // device-local. Reset only Codex OAuth state so the settings screen can open.
            AppLogger.e(TAG, "Codex OAuth credentials are unreadable; resetting encrypted store", error)
            resetEncryptedStore()
            emptyList<CodexAuthState>() to null
        } catch (_: JSONException) {
            // The parser's message can quote the stored text, tokens included, so it is not logged.
            AppLogger.e(TAG, "Codex account list is unreadable; resetting it")
            preferences.edit().remove(KEY_ACCOUNTS).remove(KEY_ACTIVE).apply()
            emptyList<CodexAuthState>() to null
        }
    }

    private fun readPoolFromPreferences(): Pair<List<CodexAuthState>, String?> {
        val raw = preferences.getString(KEY_ACCOUNTS, null)
        if (raw != null) {
            val rows = JSONArray(raw)
            val list = (0 until rows.length()).mapNotNull { decode(rows.getJSONObject(it)) }
            val active = preferences.getString(KEY_ACTIVE, null)?.takeIf { id -> list.any { it.accountId == id } }
                ?: list.firstOrNull()?.accountId
            return list to active
        }
        // The one account from before the list, read only: the next write (on a background
        // thread) saves the list and drops the old keys in the same edit.
        val legacy = readLegacyState() ?: return emptyList<CodexAuthState>() to null
        return listOf(legacy) to legacy.accountId
    }

    private fun readLegacyState(): CodexAuthState? {
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null)?.trim()
        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)?.trim()
        val accountId = preferences.getString(KEY_ACCOUNT_ID, null)?.trim()
        val expiresAtMillis = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (accessToken.isNullOrEmpty() ||
            refreshToken.isNullOrEmpty() ||
            accountId.isNullOrEmpty() ||
            expiresAtMillis <= 0L
        ) {
            return null
        }

        return CodexAuthState(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = expiresAtMillis,
            accountId = accountId,
            residency = preferences.getString(KEY_RESIDENCY, null),
            email = preferences.getString(KEY_EMAIL, null),
        )
    }

    private fun encode(state: CodexAuthState) = JSONObject().apply {
        put("access", state.accessToken)
        put("refresh", state.refreshToken)
        put("expires", state.expiresAtMillis)
        put("account", state.accountId)
        state.residency?.takeIf { it.isNotBlank() }?.let { put("residency", it) }
        state.email?.takeIf { it.isNotBlank() }?.let { put("email", it) }
        if (state.needsLogin) put("needs_login", true)
    }

    private fun decode(json: JSONObject): CodexAuthState? {
        val access = json.optString("access").trim()
        val refresh = json.optString("refresh").trim()
        val account = json.optString("account").trim()
        val expires = json.optLong("expires", 0L)
        if (access.isEmpty() || refresh.isEmpty() || account.isEmpty() || expires <= 0L) return null
        return CodexAuthState(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMillis = expires,
            accountId = account,
            residency = json.optString("residency").takeIf { it.isNotBlank() },
            email = json.optString("email").takeIf { it.isNotBlank() },
            needsLogin = json.optBoolean("needs_login"),
        )
    }

    private fun resetEncryptedStore() {
        appContext.deleteSharedPreferences(STORE_NAME)
        preferences = createEncryptedPreferences(appContext)
    }

    companion object {
        private const val TAG = "CodexAuthPreferences"
        private const val STORE_NAME = "codex_oauth_credentials"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE = "active_account"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_RESIDENCY = "residency"
        private const val KEY_EMAIL = "email"
        private val LEGACY_KEYS = listOf(
            KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_EXPIRES_AT, KEY_ACCOUNT_ID, KEY_RESIDENCY, KEY_EMAIL,
        )

        @Volatile
        private var instance: CodexAuthPreferences? = null

        fun getInstance(context: Context): CodexAuthPreferences {
            return instance ?: synchronized(this) {
                instance ?: CodexAuthPreferences(context.applicationContext).also { instance = it }
            }
        }

        private fun createPreferences(context: Context): SharedPreferences {
            return try {
                createEncryptedPreferences(context)
            } catch (error: GeneralSecurityException) {
                recreatePreferencesAfterUnreadableStore(context, error)
            } catch (error: IOException) {
                recreatePreferencesAfterUnreadableStore(context, error)
            }
        }

        private fun recreatePreferencesAfterUnreadableStore(
            context: Context,
            error: Exception
        ): SharedPreferences {
            // Android backup restores SharedPreferences XML but not the app's Android Keystore
            // entry. Tink then rejects the encrypted keyset with AEADBadTagException.
            AppLogger.e(TAG, "Codex OAuth encrypted store cannot be opened; resetting it", error)
            context.deleteSharedPreferences(STORE_NAME)
            return createEncryptedPreferences(context)
        }

        private fun createEncryptedPreferences(context: Context): SharedPreferences {
            return EncryptedSharedPreferences.create(
                context,
                STORE_NAME,
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}