package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.util.AppLogger

/**
 * Which saved logins a browser tab uses. The main app has one set; each Orbit has its own unless
 * the user lets it share the main app's. Switching to sharing keeps the Orbit's own set on disk so
 * switching back finds it again; only deleting the Orbit removes it.
 */
internal object BrowserProfiles {
    private const val TAG = "BrowserProfiles"
    const val MAIN: String = Profile.DEFAULT_PROFILE_NAME

    /** The profile of the AI tool call running on this thread; null for the user's own taps. */
    private val callScope = ThreadLocal<String?>()

    fun orbitProfileName(orbitId: String): String =
        "orbit-" + orbitId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Whether this phone's WebView can keep logins apart. Without it every tab uses [MAIN]. */
    fun isSupported(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    /**
     * The profile for an AI call: its Orbit's, or [MAIN]. Package calls run on their own thread
     * without the tool runtime, so they carry the host-filled chat ID as a reserved parameter.
     */
    fun forCurrentCaller(context: Context, tool: AITool): String {
        if (!isSupported()) return MAIN
        val chatId = callerChatId(tool)
        val root = chatId?.let {
            com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator.getInstance(context).rootChatOf(it)
        }
        val orbit =
            runCatching { OrbitPreferences.getInstance(context).loadedSettingsBlocking().let {
                it.ownerForChat(chatId) ?: it.ownerForChat(root)
            } }
                .getOrNull() ?: return MAIN
        return if (orbit.browserSharesMainLogin) MAIN else orbitProfileName(orbit.id)
    }

    /** The chat an AI browser call comes from: the tool runtime's, or the one a package call carries. */
    fun callerChatId(tool: AITool): String? =
        ToolExecutionManager.currentToolRuntimeContext()?.callerChatId?.takeIf { it.isNotBlank() }
            ?: tool.parameters.find { it.name == "__operit_package_chat_id" }?.value?.trim()?.takeIf { it.isNotBlank() }

    /** The profile an Orbit's browser uses now. */
    fun forOrbit(context: Context, orbitId: String): String {
        if (!isSupported()) return MAIN
        val orbit = OrbitPreferences.getInstance(context).loadedSettingsBlocking().orbits.firstOrNull { it.id == orbitId }
        return if (orbit == null || orbit.browserSharesMainLogin) MAIN else orbitProfileName(orbitId)
    }

    fun currentScope(): String? = callScope.get()

    fun <T> withScope(profile: String?, block: () -> T): T {
        val previous = callScope.get()
        callScope.set(profile)
        return try { block() } finally { callScope.set(previous) }
    }

    /**
     * Must run on the main thread, right after the WebView is built and before it loads anything.
     * Fails instead of quietly falling back, so an Orbit tab never runs on the main app's logins.
     */
    fun applyTo(webView: WebView, profile: String) {
        if (profile == MAIN || !isSupported()) return
        try {
            ProfileStore.getInstance().getOrCreateProfile(profile)
            WebViewCompat.setProfile(webView, profile)
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Could not give the tab its own logins: ${e.message}")
            throw IllegalStateException("Could not open this Orbit's own browser logins: ${e.message}", e)
        }
    }

    /** Cookies of [profile]; main-thread only, like the WebView profile API itself. Throws rather than hand back the main app's. */
    fun cookieManager(profile: String): CookieManager {
        if (profile == MAIN || !isSupported()) return CookieManager.getInstance()
        return ProfileStore.getInstance().getOrCreateProfile(profile).cookieManager
    }

    /** Cookie header for [url] in [profile], usable from any thread. */
    fun cookieFor(profile: String, url: String): String? {
        if (profile == MAIN || !isSupported()) return CookieManager.getInstance().getCookie(url)
        return onMain { cookieManager(profile).getCookie(url) }
    }

    private const val PENDING_PREFS = "browser_profiles"
    private const val PENDING_KEY = "pending_delete"

    /**
     * Signs the browser an Orbit uses out of every site: its cookies and site storage. With
     * [shared] logins (or a WebView without profiles) that is the main app's browser. Main thread
     * only; [onDone] gets whether it worked.
     */
    fun clearOrbitLogins(orbitId: String, shared: Boolean, onDone: (Boolean) -> Unit) {
        val profile = if (shared || !isSupported()) MAIN else orbitProfileName(orbitId)
        try {
            val storage =
                if (profile == MAIN) android.webkit.WebStorage.getInstance()
                else ProfileStore.getInstance().getOrCreateProfile(profile).webStorage
            storage.deleteAllData()
            val cookies = cookieManager(profile)
            cookies.removeAllCookies {
                // Writing cookies to disk can take a moment; keep it off the main thread.
                val main = Handler(Looper.getMainLooper())
                Thread {
                    val flushed = runCatching { cookies.flush() }.isSuccess
                    main.post { onDone(flushed) }
                }.start()
            }
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Could not clear the browser logins: ${e.message}")
            onDone(false)
        }
    }

    /**
     * Removes a deleted Orbit's logins. WebView refuses to delete a profile this process has
     * already loaded, so the name is written down first and removed for good at the next start,
     * before anything loads it. A profile this process never touched goes right away.
     */
    fun deleteOrbitData(context: Context, orbitId: String) {
        val name = orbitProfileName(orbitId)
        BrowserSitePermissions.clearProfile(context.applicationContext, name)
        val prefs = context.applicationContext.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
        // Written down even when this WebView cannot handle profiles: a later one may have the data.
        prefs.edit().putStringSet(PENDING_KEY, pendingNames(prefs) + name).apply()
        if (!isSupported()) return
        Handler(Looper.getMainLooper()).post { deletePending(prefs, setOf(name)) }
    }

    /** Call once on the main thread at app start, before any browser tab exists. */
    fun deletePendingAtStartup(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
        val pending = pendingNames(prefs)
        // Kept until a WebView that supports profiles can actually remove them.
        if (pending.isEmpty() || !isSupported()) return
        deletePending(prefs, pending)
    }

    private fun pendingNames(prefs: android.content.SharedPreferences): Set<String> =
        prefs.getStringSet(PENDING_KEY, null)?.toSet().orEmpty()

    private fun deletePending(prefs: android.content.SharedPreferences, names: Set<String>) {
        val done =
            names.filter { name ->
                runCatching { ProfileStore.getInstance().deleteProfile(name) }
                    .onFailure { AppLogger.i(TAG, "Orbit browser data stays until the next start: ${it.message}") }
                    .isSuccess
            }
        if (done.isNotEmpty()) prefs.edit().putStringSet(PENDING_KEY, pendingNames(prefs) - done.toSet()).apply()
    }

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrNull()
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: T? = null
        Handler(Looper.getMainLooper()).post {
            try { result = block() } catch (_: Throwable) {} finally { latch.countDown() }
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }
}
