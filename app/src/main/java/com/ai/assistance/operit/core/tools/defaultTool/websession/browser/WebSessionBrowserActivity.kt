package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.content.Context
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.BackEventCompat
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
     * True while back has something of the browser's own to close: a page menu, or a popup on
     * Orbit's watch screen. Otherwise back closes the window: the page shrinks with the finger, as
     * Orbit's pages do (the system draws nothing of its own here on every phone), and leaves the way
     * the close button sends it; the browser is put away as the window goes ([onPause]). Going back
     * in the page is the button in the browser's bottom bar.
     */
    private var browserTakesBack = false

    /** How far the swipe has shrunk the page: 0 at rest, 1 at the end of the swipe. */
    private var shrink = 0f
    private var shrinkFromLeft = true
    /** This swipe closes something of the browser's own, so the page stays as it is. */
    private var swipeClosesOwn = false
    /** Where this swipe found the page: a page still growing back goes on from there, without a jump. */
    private var swipeStartShrink = 0f
    private var swipeStartShift = 0f
    private var settle: android.animation.ValueAnimator? = null
    private var browserColor = android.graphics.Color.WHITE
    /** The window shows the darker colour around a shrinking page. */
    private var darkened = false

    private val backCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                swipeClosesOwn = browserTakesBack
                // A page still shrunk from a swipe let go a moment ago keeps growing back into place.
                if (swipeClosesOwn) {
                    if (shrink > 0f && settle?.isRunning != true) settleBack()
                    return
                }
                settle?.cancel()
                swipeStartShrink = shrink
                swipeStartShift = container.translationX
                shrinkFromLeft = backEvent.swipeEdge == BackEventCompat.EDGE_LEFT
                // Around the shrinking page: the browser's own colour, a little darker.
                darkened = true
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
                    androidx.core.graphics.ColorUtils.blendARGB(browserColor, android.graphics.Color.BLACK, 0.18f)))
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (swipeClosesOwn) return
                val progress = backEvent.progress.coerceIn(0f, 1f)
                // Size and side both go on from where the page is, also when this swipe comes from the other edge.
                val endShift = (if (shrinkFromLeft) 1f else -1f) * container.width * 0.03f
                setShrink(
                    swipeStartShrink + (1f - swipeStartShrink) * progress,
                    swipeStartShift + (endShift - swipeStartShift) * progress,
                )
            }

            override fun handleOnBackCancelled() {
                if (!swipeClosesOwn) settleBack()
                swipeClosesOwn = false
            }

            override fun handleOnBackPressed() {
                // What back closes is decided now: a menu may have opened or gone since the swipe began.
                val own = browserTakesBack
                val startedOwn = swipeClosesOwn
                swipeClosesOwn = false
                if (startedOwn && !own) {
                    // The swipe began to close a menu that has gone meanwhile; it closes nothing else.
                    if (shrink > 0f && settle?.isRunning != true) settleBack()
                    return
                }
                if (own) {
                    // A menu that opened mid-swipe closes; the page grows back into place.
                    if (shrink > 0f && settle?.isRunning != true) settleBack()
                    host()?.handleBack() ?: close()
                    return
                }
                // The window leaves from where the finger left it, as the close button sends it.
                settle?.cancel()
                closeLikeAPage()
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
        browserTakesBack = takes
    }

    /** Shrinks the page toward the middle, a little to the side the swipe came from, with rounded corners. */
    private fun setShrink(progress: Float, shift: Float) {
        val p = progress.coerceIn(0f, 1f)
        shrink = p
        val c = container
        val scale = 1f - 0.1f * p
        c.scaleX = scale
        c.scaleY = scale
        c.translationX = if (p > 0f) shift else 0f
        if (p > 0f) {
            if (!c.clipToOutline) {
                c.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        val radius = 28f * view.resources.displayMetrics.density * shrink
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                c.clipToOutline = true
            }
            c.invalidateOutline()
        } else {
            c.clipToOutline = false
            c.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            if (darkened) {
                darkened = false
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(browserColor))
            }
        }
    }

    /** The swipe was let go before the edge: the page grows back into place. */
    private fun settleBack() {
        settle?.cancel()
        val fromShrink = shrink
        val fromShift = container.translationX
        settle = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 180
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val left = it.animatedValue as Float
                setShrink(fromShrink * left, fromShift * left)
            }
            start()
        }
    }

    /**
     * Closes the window. In the app's task only the window goes, back to the page under it; a
     * task of its own (opened over another app) goes with it, back to that app.
     */
    internal fun close() {
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }

    /**
     * Closes the window the way it opened, back into the page under it: for the browser's close
     * button and for back, which first shrinks the page with the finger. Over another app the
     * system's own task animation plays.
     */
    internal fun closeLikeAPage() {
        val enter = com.ai.assistance.operit.R.anim.browser_window_hold
        val exit = com.ai.assistance.operit.R.anim.browser_window_exit
        if (android.os.Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, enter, exit)
        close()
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT < 34) overridePendingTransition(enter, exit)
    }

    override fun onStart() {
        super.onStart()
        // Reopened from the recent apps after the app process was gone: nothing left to show.
        val host = host() ?: return close()
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
        browserColor = color
        if (shrink == 0f) window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(color))
        container.setBackgroundColor(color)
        val light = androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun host(): WebSessionBrowserHost? = StandardBrowserSessionTools.browserHost
}
