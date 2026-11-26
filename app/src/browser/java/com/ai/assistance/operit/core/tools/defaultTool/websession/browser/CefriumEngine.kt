package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ai.assistance.operit.util.AppLogger
import com.cefrium.Cefrium
import com.cefrium.CefriumProfile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val CEFRIUM_TAG = "CefriumEngine"

/**
 * Cefrium, the Chromium the browser build carries. Each login set is a persistent profile: the
 * main app's is the engine's default one, an Orbit's has the Orbit's profile name. The engine
 * starts with the first tab, not with the app.
 */
internal object CefriumEngine : BrowserEngine {
    private val main = Handler(Looper.getMainLooper())

    override val chromeFullVersion: String get() = Cefrium.getChromiumVersion()

    fun profile(name: String): CefriumProfile =
        if (name == BrowserProfiles.MAIN) CefriumProfile.getDefault() else CefriumProfile.get(name)

    override fun createPage(context: Context, profileName: String, desktop: Boolean): EngineBrowserPage =
        CefriumBrowserPage.create(context, profile(profileName), desktop)

    override fun cookieFor(profileName: String, url: String): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val result = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        main.post {
            runCatching {
                profile(profileName).cookieManager.getCookie(url) { cookie ->
                    result.set(cookie)
                    latch.countDown()
                }
            }.onFailure {
                AppLogger.w(CEFRIUM_TAG, "Cookies could not be read: ${it.message}")
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        return result.get()?.takeIf { it.isNotBlank() }
    }

    override fun setCookies(profileName: String, url: String, setCookies: List<String>): Boolean {
        if (setCookies.isEmpty()) return true
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        val latch = CountDownLatch(setCookies.size)
        val stored = AtomicInteger(0)
        main.post {
            setCookies.forEach { line ->
                runCatching {
                    profile(profileName).cookieManager.setCookie(url, line) { ok ->
                        if (ok == true) stored.incrementAndGet()
                        latch.countDown()
                    }
                }.onFailure {
                    AppLogger.w(CEFRIUM_TAG, "A cookie could not be stored: ${it.message}")
                    latch.countDown()
                }
            }
        }
        return latch.await(3, TimeUnit.SECONDS) && stored.get() == setCookies.size
    }

    override fun clearLogins(profileName: String, onDone: (Boolean) -> Unit) {
        val profile = profile(profileName)
        profile.listSiteData { sites ->
            val left = AtomicInteger(sites.size + 1)
            val finish = {
                if (left.decrementAndGet() == 0) {
                    profile.cookieManager.flush { main.post { onDone(true) } }
                }
            }
            sites.forEach { site -> profile.clearSiteData(site.site) { finish() } }
            profile.cookieManager.removeAllCookies { finish() }
        }
    }

    override fun deleteProfile(profileName: String) {
        if (profileName == BrowserProfiles.MAIN) return
        // Deleted now, or at the next start when this run already used it.
        CefriumProfile.delete(profileName)
    }
}
