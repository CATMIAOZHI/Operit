package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.content.Context
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools

/**
 * The window the open browser shows in. Its pages live in the browser's background overlay so
 * the AI can use them at any time; while the user looks at the browser, its screen moves in here.
 * Being an ordinary app window, swiping home, the recent apps and back all work as in any app:
 * the user can leave to read a code in another app and come back. A full-screen overlay stayed
 * on top of everything instead.
 */
class WebSessionBrowserActivity : ComponentActivity() {
    internal lateinit var container: FrameLayout
        private set
    /**
     * False once the window has shown, also in a window the system rebuilt while it waited in the
     * recent apps; a later start is a return from there.
     */
    private var firstStart = true

    /**
     * On only while back has something of the browser's own to close: a page menu, or a popup on
     * Orbit's watch screen. Otherwise back belongs to the system, which shrinks the whole window
     * with the finger and shows what is below; the browser is put away when the window goes
     * ([onPause]). Going back in the page is the button in the browser's bottom bar.
     */
    private val backCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                host()?.handleBack() ?: finishAndRemoveTask()
            }
        }

    /** The app's own language, as the main window has it, also when the phone's differs. */
    override fun attachBaseContext(newBase: Context) {
        val code =
            if (com.ai.assistance.operit.data.backup.MigrationStateStore.isMainDataAccessAllowed(newBase)) {
                com.ai.assistance.operit.util.LocaleUtils.getCurrentLanguage(newBase)
            } else {
                com.ai.assistance.operit.util.LocaleUtils.LanguageCodes.AUTO
            }
        val locale = com.ai.assistance.operit.util.LocaleUtils.getLocaleForLanguageCode(code, newBase)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocales(android.os.LocaleList(locale))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstStart = savedInstanceState == null
        enableEdgeToEdge()
        container = FrameLayout(this)
        setContentView(container)
        onBackPressedDispatcher.addCallback(this, backCallback)
        host()?.onActivityCreated(this)
    }

    internal fun setBrowserTakesBack(takes: Boolean) {
        backCallback.isEnabled = takes
    }

    override fun onStart() {
        super.onStart()
        // Reopened from the recent apps after the app process was gone: nothing left to show.
        val host = host() ?: return finishAndRemoveTask()
        host.onActivityStarted(this, firstStart)
        firstStart = false
    }

    override fun onPause() {
        super.onPause()
        // Closed by the system's back: the browser is put away while the window leaves.
        if (isFinishing) host()?.onActivityFinishing(this)
    }

    override fun onStop() {
        if (!isChangingConfigurations) host()?.onActivityStopped(this)
        super.onStop()
    }

    override fun onDestroy() {
        host()?.onActivityDestroyed(this)
        super.onDestroy()
    }

    /**
     * Paints the window in the browser's background, so no other colour shows while it opens, and
     * picks status and navigation bar icons that stay visible on it. An Orbit can look light on a
     * dark phone, and the other way round.
     */
    internal fun applyBrowserBackground(color: Int) {
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(color))
        container.setBackgroundColor(color)
        val light = androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun host(): WebSessionBrowserHost? = StandardBrowserSessionTools.browserHost
}
