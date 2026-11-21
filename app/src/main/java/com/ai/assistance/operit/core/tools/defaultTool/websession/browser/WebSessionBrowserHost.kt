package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.ui.features.orbit.OrbitAppearanceTheme
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.ui.WebSessionUserscriptUiStateStore
import com.ai.assistance.operit.ui.features.websession.browser.WebSessionBrowserScreen
import com.ai.assistance.operit.ui.features.websession.browser.WebSessionFloatingTheme
import com.ai.assistance.operit.ui.features.websession.browser.WebSessionMinimizedIndicator
import com.ai.assistance.operit.util.AppLogger
import kotlin.math.roundToInt
import org.json.JSONTokener

/** How long the browser window may take to open before the browser shows as an overlay instead. */
private const val ACTIVITY_OPEN_TIMEOUT_MS = 2_000L

/**
 * Shows the browser in the app's own language, like the main window, also when the phone's
 * differs. The browser outlives any one window, so the language comes from the host (read again
 * when the browser opens) and the rest of the configuration from the window it shows in.
 */
@Composable
private fun BrowserUiLocale(language: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val configuration = LocalConfiguration.current
    val localized = remember(base, configuration, language) {
        val locale = com.ai.assistance.operit.util.LocaleUtils.getLocaleForLanguageCode(language, base)
        base.createConfigurationContext(
            android.content.res.Configuration(configuration).apply { setLocales(android.os.LocaleList(locale)) }
        )
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalResources provides localized.resources,
        content = content,
    )
}

@Composable
private fun BrowserAppearanceTheme(orbitId: String?, content: @Composable () -> Unit) {
    if (orbitId == null) {
        WebSessionFloatingTheme(content)
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val settings by remember(context) { OrbitPreferences.getInstance(context).settingsFlow }
        .collectAsState(initial = null)
    // Do not show the app's light palette for a frame while the companion's appearance loads.
    val loaded = settings ?: return
    val orbit = loaded.orbits.firstOrNull { it.id == orbitId }
    if (orbit != null) {
        WebSessionFloatingTheme {
            OrbitAppearanceTheme(orbit, updateAppAppearance = false, content = content)
        }
    } else {
        WebSessionFloatingTheme(content)
    }
}

internal class WebSessionBrowserHost(
    private val appContext: Context,
    private val store: WebSessionHistoryStore,
    private val userscriptStore: WebSessionUserscriptUiStateStore,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onNavigate(url: String)
        fun onBack()
        fun onForward()
        fun onRefreshOrStop()
        fun onSelectTab(sessionId: String)
        fun onCloseTab(sessionId: String)
        fun onNewTab()
        fun onMinimize()
        fun onCloseCurrentTab()
        fun onCloseAllTabs()
        fun onToggleBookmark(url: String, title: String)
        fun onRemoveBookmark(url: String)
        fun onSelectSessionHistory(index: Int)
        fun onOpenUrl(url: String)
        fun onClearHistory()
        fun onToggleDesktopMode()
        fun onOpenUserscripts()
        fun onImportUserscript()
        fun onInstallUserscriptFromUrl(url: String)
        fun onConfirmUserscriptInstall()
        fun onCancelUserscriptInstall()
        fun onSetUserscriptEnabled(scriptId: Long, enabled: Boolean)
        fun onDeleteUserscript(scriptId: Long)
        fun onCheckUserscriptUpdate(scriptId: Long)
        fun onInvokeUserscriptMenu(commandId: String)
        fun onPauseDownload(taskId: String)
        fun onResumeDownload(taskId: String)
        fun onCancelDownload(taskId: String)
        fun onRetryDownload(taskId: String)
        fun onDeleteDownload(taskId: String, deleteFile: Boolean)
        fun onOpenDownloadedFile(taskId: String)
        fun onOpenDownloadLocation(taskId: String)
        fun onConfirmExternalOpen(requestId: String)
        fun onCancelExternalOpen(requestId: String)
        fun onHandlePendingDialog(accept: Boolean, promptText: String?)
        /** Opens or puts away the browser through the tool that made it, as the browser window comes and goes. */
        fun onSetExpanded(expanded: Boolean)
    }

    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val webViewHost = WebSessionWebViewHost()

    private var rootView: DeceptiveMinimizedLayout? = null
    private var backgroundViews: FrameLayout? = null
    private var composeView: ComposeView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayLifecycleOwner: WebSessionOverlayLifecycleOwner? = null

    private var indicatorView: ComposeView? = null
    private var indicatorParams: WindowManager.LayoutParams? = null
    private var indicatorLifecycleOwner: WebSessionOverlayLifecycleOwner? = null
    private var selectionActionsView: View? = null
    private var selectionActionsParams: WindowManager.LayoutParams? = null

    private var isExpanded: Boolean = false
    private var hostState by mutableStateOf(WebSessionBrowserHostState())
    /** The app's own language, read again each time the browser opens so a change in settings shows. */
    private var uiLanguage by mutableStateOf(com.ai.assistance.operit.util.LocaleUtils.getCurrentLanguage(appContext))
    /** True while the browser shows in its activity, which leaves room for the keyboard itself. */
    private var hostedInActivity by mutableStateOf(false)
    /** The browser's background, which the activity's window and system bars follow. */
    private var browserBackground: Int? = null
    /** True while Orbit's watch screen shows and the user has not taken control: the page takes no input. */
    private var watchLocked = false
    /** The Orbit chat whose watch screen showed last; when its work ends the browser closes instead of unlocking. */
    private var lastWatchTalk: String? = null
    private var a11yHiddenWebView: java.lang.ref.WeakReference<View>? = null
    fun ensureCreated(initialExpanded: Boolean = false) {
        if (rootView != null) {
            if (isExpanded != initialExpanded) {
                setExpanded(initialExpanded)
            }
            return
        }

        val lifecycleOwner =
            WebSessionOverlayLifecycleOwner().apply {
                handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                handleLifecycleEvent(Lifecycle.Event.ON_START)
                handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }

        val root =
            DeceptiveMinimizedLayout(appContext).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                setOnClickListener {}
            }
        installViewTreeOwners(root, lifecycleOwner)
        // Keep task pages attached and laid out for JavaScript, snapshots and native taps, without
        // replacing the page the user is watching. The three-task/two-tab limits bound this cost.
        val background = FrameLayout(appContext).apply {
            // Off-screen/GONE views stop Chromium rendering, leaving screenshots stale. The
            // pages stay faintly drawn underneath the opaque browser surface, like minimized mode.
            alpha = 0.01f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        root.addView(background, FrameLayout.LayoutParams(
            appContext.resources.displayMetrics.widthPixels, appContext.resources.displayMetrics.heightPixels))
        backgroundViews = background

        val compose =
            ComposeView(appContext).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                alpha = 1f
                // The screen moves between the overlay and the browser window; it lives as long as the host.
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(lifecycleOwner))
                installViewTreeOwners(this, lifecycleOwner)
                setContent {
                  BrowserUiLocale(uiLanguage) {
                    val bookmarks by store.bookmarksFlow.collectAsState(initial = emptyList())
                    val history by store.historyFlow.collectAsState(initial = emptyList())
                    val userscriptUiState by userscriptStore.state.collectAsState()
                    val orbitWorks by com.ai.assistance.operit.features.orbit.OrbitBrowserWatch.works.collectAsState()
                    val watchingTalk by com.ai.assistance.operit.features.orbit.OrbitBrowserWatch.watching.collectAsState()
                    val watchWork = orbitWatchWork(orbitWorks, watchingTalk, hostState.browserState)
                    val watch = watchWork?.let { orbitWatchUi(it, hostState.browserState) }
                    val locked = watch != null && !watch.controlling
                    androidx.compose.runtime.SideEffect {
                        setWatchLocked(locked)
                        // The phone's back key puts the watch screen away, also while the user drives
                        // the page; control stays with the user until they hand it back with the
                        // button. Going back in the page is the back button in the bottom bar. A
                        // dialog the page put up is dismissed first, like any popup; the same in
                        // the plain browser. Watching Orbit work, the user has nothing to close.
                        backDismiss = if (watch != null && !watch.controlling) null else {
                            when {
                                hostState.browserState.pendingDialog != null ->
                                    ({ callbacks.onHandlePendingDialog(false, null) })
                                // The "open in another app" prompt and the sheets close first, too.
                                hostState.externalOpenPrompt != null -> {
                                    val id = hostState.externalOpenPrompt!!.requestId
                                    ({ callbacks.onCancelExternalOpen(id) })
                                }
                                hostState.sheetRoute != WebSessionBrowserSheetRoute.NONE ->
                                    ({ updateHostState { it.copy(sheetRoute = WebSessionBrowserSheetRoute.NONE) } })
                                else -> null
                            }
                        }
                        watchPutAway = watchWork?.executorChatId?.let { talk ->
                            { com.ai.assistance.operit.features.orbit.OrbitBrowserWatch.close(talk) }
                        }
                        root.onBackKey = watch?.let { backDismiss ?: watchPutAway }
                        syncBrowserBack()
                        val ended = lastWatchTalk?.takeIf { watchWork == null && it !in orbitWorks }
                        lastWatchTalk = watchWork?.executorChatId
                        if (ended != null && isExpanded) {
                            // The work is over: close the watch screen rather than leave an unlocked page behind.
                            root.post { callbacks.onMinimize() }
                        }
                    }

                    BrowserAppearanceTheme(watchWork?.orbitId) {
                        val background = androidx.compose.material3.MaterialTheme.colorScheme.background.toArgb()
                        androidx.compose.runtime.SideEffect { reportBrowserBackground(background) }
                        // In its activity the window draws behind the keyboard: the page ends at the
                        // keyboard's top and the bottom bar stays behind it.
                        CompositionLocalProvider(
                            com.ai.assistance.operit.ui.features.websession.browser.LocalBrowserFollowsKeyboard provides hostedInActivity,
                        ) {
                        WebSessionBrowserScreen(
                            // In a small (freeform) window its activity has the system's title bar
                            // over the top and a handle bar over the bottom; the page keeps clear
                            // of them, and the bars and keyboard inside it count from there.
                            modifier =
                                if (hostedInActivity) {
                                    Modifier.windowInsetsPadding(WindowInsets.captionBar)
                                } else {
                                    Modifier
                                },
                            hostState = if (watchWork == null) hostState else hostState.copy(browserState =
                                hostState.browserState.copy(tabs = hostState.browserState.tabs.filter { tab ->
                                    com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools.sessions[tab.sessionId]?.browserTaskId == watchWork.executorChatId
                                })),
                            watch = watch,
                            bookmarks = bookmarks,
                            globalHistory = history,
                            userscriptUiState = userscriptUiState,
                            webViewHost = webViewHost,
                            onHostStateChange = { transform ->
                                updateHostState(transform)
                            },
                            onNavigate = callbacks::onNavigate,
                            onBack = callbacks::onBack,
                            onForward = callbacks::onForward,
                            onRefreshOrStop = callbacks::onRefreshOrStop,
                            onSelectTab = callbacks::onSelectTab,
                            onCloseTab = callbacks::onCloseTab,
                            onNewTab = callbacks::onNewTab,
                            onMinimize = callbacks::onMinimize,
                            onCloseCurrentTab = callbacks::onCloseCurrentTab,
                            onCloseAllTabs = callbacks::onCloseAllTabs,
                            onToggleBookmark = callbacks::onToggleBookmark,
                            onRemoveBookmark = callbacks::onRemoveBookmark,
                            onSelectSessionHistory = callbacks::onSelectSessionHistory,
                            onOpenUrl = callbacks::onOpenUrl,
                            onClearHistory = callbacks::onClearHistory,
                            onToggleDesktopMode = callbacks::onToggleDesktopMode,
                            onOpenUserscripts = callbacks::onOpenUserscripts,
                            onImportUserscript = callbacks::onImportUserscript,
                            onInstallUserscriptFromUrl = callbacks::onInstallUserscriptFromUrl,
                            onConfirmUserscriptInstall = callbacks::onConfirmUserscriptInstall,
                            onCancelUserscriptInstall = callbacks::onCancelUserscriptInstall,
                            onSetUserscriptEnabled = callbacks::onSetUserscriptEnabled,
                            onDeleteUserscript = callbacks::onDeleteUserscript,
                            onCheckUserscriptUpdate = callbacks::onCheckUserscriptUpdate,
                            onInvokeUserscriptMenu = callbacks::onInvokeUserscriptMenu,
                            onPauseDownload = callbacks::onPauseDownload,
                            onResumeDownload = callbacks::onResumeDownload,
                            onCancelDownload = callbacks::onCancelDownload,
                            onRetryDownload = callbacks::onRetryDownload,
                            onDeleteDownload = callbacks::onDeleteDownload,
                            onOpenDownloadedFile = callbacks::onOpenDownloadedFile,
                            onOpenDownloadLocation = callbacks::onOpenDownloadLocation,
                            onConfirmExternalOpen = callbacks::onConfirmExternalOpen,
                            onCancelExternalOpen = callbacks::onCancelExternalOpen,
                            onHandlePendingDialog = callbacks::onHandlePendingDialog
                        )
                        }
                    }
                  }
                }
            }

        root.addView(
            compose,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        overlayLifecycleOwner = lifecycleOwner
        rootView = root
        composeView = compose
        // The overlay only keeps the pages running; the open browser shows in its own activity.
        overlayParams = createOverlayLayoutParams(false)
        windowManager.addView(root, overlayParams)
        BrowserPageMenus.onChanged = ::syncBrowserBack
        setExpanded(initialExpanded)
    }

    fun destroy() {
        launchToken++
        BrowserPageMenus.onChanged = null
        backDismiss = null
        watchPutAway = null
        val activity = createdActivity?.get()
        createdActivity = null
        browserActivity = null
        closingActivity = null
        overlayFallback = false
        composeView?.let { compose -> (compose.parent as? ViewGroup)?.takeIf { it !== rootView }?.removeView(compose) }
        activity?.takeIf { !it.isFinishing }?.finishAndRemoveTask()
        hideTextSelectionActionsOverlay()
        hideIndicator()
        overlayLifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        overlayLifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        overlayLifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        rootView?.let { root ->
            try {
                windowManager.removeView(root)
            } catch (_: Exception) {
            }
        }

        overlayLifecycleOwner = null
        composeView = null
        rootView = null
        backgroundViews = null
        overlayParams = null
        indicatorParams = null
        webViewHost.clear()
    }

    fun updateHostProjection(
        browserState: WebSessionBrowserState,
        downloadUiState: BrowserDownloadUiState,
        externalOpenPrompt: ExternalOpenPromptState?
    ) {
        hostState =
            hostState.copy(
                browserState = browserState,
                downloadUiState =
                    downloadUiState.copy(
                        selectedFilter = hostState.downloadUiState.selectedFilter
                    ),
                externalOpenPrompt = externalOpenPrompt,
                urlDraft =
                    if (hostState.isEditingUrl) {
                        hostState.urlDraft
                    } else {
                        browserState.currentUrl
                    }
            )
        syncIndicatorWithPages()
        updateIndicatorLayoutForCurrentState()
    }

    /**
     * Orbit puts nothing on the screen: its pages open from the card in its chat. The floating
     * button is only for the main app's own tabs (those without an Orbit task).
     */
    private fun wantsIndicator(): Boolean =
        com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools.sessions.values
            .any { it.browserTaskId == null }

    private fun syncIndicatorWithPages() {
        if (isExpanded || rootView == null) return
        if (wantsIndicator()) showIndicator() else hideIndicator()
    }

    fun attachActiveWebView(page: BrowserPage?, viewportWidth: Int? = null, viewportHeight: Int? = null) {
        webViewHost.currentPage()?.takeIf { it !== page }?.let { previous ->
            com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools.sessions.values
                .firstOrNull { it.page === previous && it.browserTaskId != null }?.let {
                    parkWebView(previous, it.viewportWidthPx, it.viewportHeightPx)
                }
        }
        updateHostState {
            if (it.viewportWidthPx == viewportWidth && it.viewportHeightPx == viewportHeight) {
                it
            } else {
                it.copy(viewportWidthPx = viewportWidth, viewportHeightPx = viewportHeight)
            }
        }
        webViewHost.setActivePage(page, viewportWidth, viewportHeight)
        applyWatchAccessibility(page?.view)
    }

    fun attachBackgroundWebView(page: BrowserPage, width: Int?, height: Int?) {
        if (webViewHost.currentPage() !== page) parkWebView(page, width, height)
    }

    private fun parkWebView(page: BrowserPage, width: Int?, height: Int?) {
        val webView = page.view
        val target = backgroundViews ?: return
        if (webView.parent !== target) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            target.addView(webView)
        }
        val density = appContext.resources.displayMetrics.density
        val w = width?.let { (it * density).roundToInt() } ?: appContext.resources.displayMetrics.widthPixels
        val h = height?.let { (it * density).roundToInt() } ?: appContext.resources.displayMetrics.heightPixels
        webView.layoutParams = FrameLayout.LayoutParams(w, h)
        val scale = minOf(appContext.resources.displayMetrics.widthPixels.toFloat() / w,
            appContext.resources.displayMetrics.heightPixels.toFloat() / h)
        webView.pivotX = 0f
        webView.pivotY = 0f
        webView.scaleX = scale
        webView.scaleY = scale
        webView.translationX = 0f
        webView.translationY = 0f
        // A page Orbit stopped using stays at rest when it goes back to the background.
        val task = com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools.sessions.values
            .firstOrNull { it.page === page }?.browserTaskId
        val watch = com.ai.assistance.operit.features.orbit.OrbitBrowserWatch
        if (watch.resting(task)) watch.rest(page) else page.onResume()
        webView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        webView.layout(0, 0, w, h)
    }
    fun showTextSelectionActionsOverlay(anchorX: Double, anchorY: Double) {
        if (!isExpanded) {
            return
        }

        val currentView = selectionActionsView
        if (currentView != null) {
            updateTextSelectionActionsLayout(currentView, anchorX, anchorY)
            return
        }

        val actionsView = createTextSelectionActionsView()
        val params = createTextSelectionActionsLayoutParams()
        selectionActionsParams = params
        try {
            windowManager.addView(actionsView, params)
            selectionActionsView = actionsView
            actionsView.post {
                updateTextSelectionActionsLayout(actionsView, anchorX, anchorY)
            }
        } catch (e: Exception) {
            selectionActionsParams = null
            AppLogger.e("WebSessionBrowserHost", "Failed to show WebView text selection actions", e)
        }
    }

    fun performTextSelectionHaptic() {
        (composeView ?: rootView)?.performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    fun hideTextSelectionActionsOverlay() {
        val view = selectionActionsView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            AppLogger.e("WebSessionBrowserHost", "Failed to hide WebView text selection actions", e)
        }
        selectionActionsView = null
        selectionActionsParams = null
    }

    fun isExpanded(): Boolean = isExpanded

    fun setViewportSize(width: Int, height: Int) {
        updateHostState {
            it.copy(
                viewportWidthPx = width,
                viewportHeightPx = height
            )
        }
        webViewHost.setViewportSize(width, height)
    }

    fun clearViewportSizeOverride() {
        updateHostState { it.copy(viewportWidthPx = null, viewportHeightPx = null) }
        webViewHost.setViewportSize(null, null)
    }

    fun currentViewportSize(): Pair<Int, Int> {
        val metrics = appContext.resources.displayMetrics
        val width = hostState.viewportWidthPx ?: metrics.widthPixels
        val height = hostState.viewportHeightPx ?: metrics.heightPixels
        return width to height
    }

    fun currentBrowserAreaSize(): Pair<Int, Int> =
        hostState.browserAreaWidthPx.coerceAtLeast(0) to hostState.browserAreaHeightPx.coerceAtLeast(0)

    fun setExpanded(expanded: Boolean) {
        rootView ?: return
        composeView ?: return

        if (!expanded) {
            hideTextSelectionActionsOverlay()
            // Put away, the browser is no longer Orbit's watch screen; the card opens it again.
            com.ai.assistance.operit.features.orbit.OrbitBrowserWatch.unwatch()
        } else if (com.ai.assistance.operit.features.orbit.OrbitBrowserWatch.watching.value == null) {
            com.ai.assistance.operit.features.orbit.OrbitBrowserWatch.watchSession(hostState.browserState.activeSessionId)
        }

        isExpanded = expanded
        hostState =
            if (expanded) {
                hostState
            } else {
                hostState.copy(
                    sheetRoute = WebSessionBrowserSheetRoute.NONE,
                    isEditingUrl = false,
                    urlDraft = hostState.browserState.currentUrl
                )
            }

        if (expanded) {
            hideIndicator()
            uiLanguage = com.ai.assistance.operit.util.LocaleUtils.getCurrentLanguage(appContext)
            if (browserActivity == null) launchBrowserActivity()
        } else {
            if (wantsIndicator()) showIndicator() else hideIndicator()
            launchToken++
            overlayFallback = false
            // Put away by the browser itself: its window goes too. Left for another app, the
            // window stays in the recent apps to come back to.
            if (!leavingActivity) {
                createdActivity?.get()?.takeIf { !it.isFinishing }?.let { closing ->
                    closingActivity = closing
                    closing.finishAndRemoveTask()
                }
                // A closing window shows nothing more: opening again right away starts a new one.
                browserActivity = null
            }
        }

        placeBrowserScreen()
        updateIndicatorLayoutForCurrentState()
    }

    /** The activity the open browser shows in, while it is on screen; null otherwise. */
    private var browserActivity: WebSessionBrowserActivity? = null
    /**
     * The window the user just closed, while it leaves the screen: the browser stays in it so the
     * closing animation shows the page, not an empty window. Its screen goes back to the overlay
     * once the window is gone.
     */
    private var closingActivity: WebSessionBrowserActivity? = null
    /** The browser's activity while it exists, also when it waits in the recent apps. */
    private var createdActivity: java.lang.ref.WeakReference<WebSessionBrowserActivity>? = null
    /** The activity could not open (the system blocked it); the browser shows over everything as before. */
    private var overlayFallback = false
    private var launchToken = 0
    /** Set while the browser is put away because the user left its window for another app. */
    private var leavingActivity = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun launchBrowserActivity() {
        val token = ++launchToken
        val started = runCatching {
            appContext.startActivity(
                android.content.Intent(appContext, WebSessionBrowserActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { AppLogger.w("WebSessionBrowserHost", "Could not open the browser window: ${it.message}") }.isSuccess
        if (!started) {
            overlayFallback = true
            return
        }
        // Some systems quietly refuse to open a window from the background; show it the old way then.
        mainHandler.postDelayed({
            if (token == launchToken && isExpanded && browserActivity == null) {
                AppLogger.w("WebSessionBrowserHost", "The browser window did not open; showing it as an overlay")
                overlayFallback = true
                placeBrowserScreen()
            }
        }, ACTIVITY_OPEN_TIMEOUT_MS)
    }

    internal fun onActivityCreated(activity: WebSessionBrowserActivity) {
        val previous = createdActivity?.get()
        createdActivity = java.lang.ref.WeakReference(activity)
        if (previous != null && previous !== activity && !previous.isFinishing) previous.finishAndRemoveTask()
    }

    /**
     * [firstStart] tells a window just opened from one coming back from the recent apps. A new
     * window finding the browser already put away was asked for and then cancelled: it closes.
     */
    internal fun onActivityStarted(activity: WebSessionBrowserActivity, firstStart: Boolean) {
        if (activity.isFinishing) return
        if (createdActivity?.get() !== activity) onActivityCreated(activity)
        if (!isExpanded && firstStart) {
            activity.finishAndRemoveTask()
            return
        }
        browserActivity = activity
        overlayFallback = false
        browserBackground?.let(activity::applyBrowserBackground)
        syncBrowserBack()
        if (isExpanded) {
            placeBrowserScreen()
        } else {
            // Back from the recent apps: the browser opens again.
            callbacks.onSetExpanded(true)
        }
    }

    internal fun onActivityStopped(activity: WebSessionBrowserActivity) {
        if (activity === closingActivity) {
            closingActivity = null
            placeBrowserScreen()
            return
        }
        if (browserActivity !== activity) return
        browserActivity = null
        if (!isExpanded || activity.isFinishing) {
            placeBrowserScreen()
            return
        }
        // The user went to another app (or locked the phone). The page goes back to the overlay,
        // where it keeps running for the AI; a user holding control keeps it.
        leavingActivity = true
        try {
            callbacks.onSetExpanded(false)
        } finally {
            leavingActivity = false
        }
    }

    /** The browser's background changed (another Orbit's appearance, say): its window follows. */
    private fun reportBrowserBackground(color: Int) {
        if (browserBackground == color) return
        browserBackground = color
        browserActivity?.applyBrowserBackground(color)
    }

    internal fun onActivityDestroyed(activity: WebSessionBrowserActivity) {
        if (activity === closingActivity) {
            closingActivity = null
            placeBrowserScreen()
        }
        if (browserActivity === activity) {
            browserActivity = null
            placeBrowserScreen()
        }
        if (createdActivity?.get() === activity) createdActivity = null
    }

    /**
     * Back in the browser window while it has something of its own to close: the page's long-press
     * menu, or a popup on Orbit's watch screen. Going back in the page is the button in the bottom
     * bar; anything else closes the window ([onActivityFinishing]).
     */
    internal fun handleBack() {
        if (BrowserPageMenus.dismissOpen()) return
        backDismiss?.let { return it() }
        // Only when the window's back state is stale: put away as the system's back would.
        callbacks.onMinimize()
        watchPutAway?.invoke()
    }

    /** What back closes on the watch screen (a page dialog, a prompt, the downloads sheet). */
    private var backDismiss: (() -> Unit)? = null
    /** Leaves the watch screen without handing control back: only its button does that. */
    private var watchPutAway: (() -> Unit)? = null

    /**
     * Whether back is the browser's own right now (see [WebSessionBrowserActivity]); otherwise the
     * system takes it, shrinks the window with the finger and closes it.
     */
    private fun syncBrowserBack() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::syncBrowserBack)
            return
        }
        browserActivity?.setBrowserTakesBack(BrowserPageMenus.open != null || backDismiss != null)
    }

    /**
     * The window is closing on its own: the system's back closed it. The browser is put away now
     * and keeps showing in the window until it is off the screen, as when it closes itself. A user
     * holding control keeps it.
     */
    internal fun onActivityFinishing(activity: WebSessionBrowserActivity) {
        if (activity !== browserActivity || createdActivity?.get() !== activity || !isExpanded) return
        closingActivity = activity
        browserActivity = null
        val putAway = watchPutAway
        callbacks.onMinimize()
        putAway?.invoke()
    }

    /**
     * Puts the browser screen where it belongs: in the browser activity while it shows, full screen
     * in the overlay when the activity could not open, and otherwise laid out unseen in the 1×1
     * overlay, so the page in front keeps rendering for the AI.
     */
    private fun placeBrowserScreen() {
        val root = rootView ?: return
        val compose = composeView ?: return
        val params = overlayParams ?: return
        val activity = browserActivity?.takeIf { isExpanded }
        // A window being closed keeps showing the page until it is off the screen, also when the
        // browser is opened again meanwhile, until the new window (or the overlay) takes over.
        val leaving = closingActivity?.takeIf { activity == null && !(isExpanded && overlayFallback) && !it.isDestroyed }
        hostedInActivity = activity != null || leaving != null
        val target: ViewGroup = activity?.container ?: leaving?.container ?: root
        if (compose.parent !== target) {
            (compose.parent as? ViewGroup)?.removeView(compose)
            target.addView(
                compose,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        }
        val overlayFull = isExpanded && activity == null && overlayFallback
        val shown = activity != null || overlayFull
        compose.alpha = if (shown || leaving != null) 1f else 0.01f
        applyBrowserFocusability()
        if (overlayFull) {
            root.setMinimizedMeasure(false)
            applyExpandedLayoutParams(params)
        } else {
            val displayMetrics = appContext.resources.displayMetrics
            root.setMinimizedMeasure(
                enabled = true,
                fakeWidth = displayMetrics.widthPixels,
                fakeHeight = displayMetrics.heightPixels
            )
            params.width = 1
            params.height = 1
            params.gravity = Gravity.TOP or Gravity.START
            params.flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            indicatorParams?.let {
                params.x = it.x
                params.y = it.y
            }
        }
        root.setBackgroundColor(AndroidColor.TRANSPARENT)
        overlayParams = params
        if (root.windowToken != null) {
            windowManager.updateViewLayout(root, params)
        }
    }

    /** Only a browser on screen takes input, and Orbit's locked watch screen none. */
    private fun applyBrowserFocusability() {
        val root = rootView ?: return
        val compose = composeView ?: return
        val shown = isExpanded && (browserActivity != null || overlayFallback)
        val focus = if (shown && !watchLocked) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        compose.descendantFocusability = focus
        root.descendantFocusability =
            if (shown && browserActivity == null) focus else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun applyExpandedLayoutParams(params: WindowManager.LayoutParams) {
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.CENTER
        params.x = 0
        params.y = 0
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    fun showSheet(route: WebSessionBrowserSheetRoute) {
        updateHostState { it.copy(sheetRoute = route) }
    }

    /**
     * The Orbit work the tab in front belongs to. A user in control keeps the watch screen on any
     * tab they reach (a page may open a new one), so the hand-back button never disappears.
     */
    private fun orbitWatchWork(
        works: Map<String, com.ai.assistance.operit.features.orbit.OrbitBrowserWatch.Work>,
        watchingTalk: String?,
        browserState: WebSessionBrowserState,
    ): com.ai.assistance.operit.features.orbit.OrbitBrowserWatch.Work? {
        // Opened from Orbit's card, the browser stays the watch screen on whatever tab shows,
        // even before the first page is ready or after the last tab closed.
        watchingTalk?.let(works::get)?.let { return it }
        val active = browserState.activeSessionId
        return active?.let { id -> works.values.firstOrNull { it.sessionId == id } }
            ?: works.values.filter { it.control != com.ai.assistance.operit.features.orbit.OrbitBrowserWatch.Control.NONE }
                .maxByOrNull { it.lastActionAt }
    }

    /** Orbit's watch screen for [work]. */
    private fun orbitWatchUi(
        work: com.ai.assistance.operit.features.orbit.OrbitBrowserWatch.Work,
        browserState: WebSessionBrowserState,
    ): BrowserWatchUi {
        val watch = com.ai.assistance.operit.features.orbit.OrbitBrowserWatch
        val url = browserState.currentUrl.takeIf { it.isNotBlank() && it != "about:blank" } ?: work.url
        return BrowserWatchUi(
            host = runCatching { android.net.Uri.parse(url).host }.getOrNull()?.removePrefix("www.").orEmpty(),
            orbitName = work.orbitName,
            controlling = work.control == com.ai.assistance.operit.features.orbit.OrbitBrowserWatch.Control.USER,
            waiting = work.control == com.ai.assistance.operit.features.orbit.OrbitBrowserWatch.Control.WAITING,
            finished = work.finished,
            onClose = { watch.close(work.executorChatId) },
            onTakeOver = { watch.takeOver(work.executorChatId) },
            onHandBack = { watch.handBack(work.executorChatId) },
            onStop = { watch.stop(work.executorChatId) },
        )
    }

    /** While locked the page cannot take focus, so no keyboard opens over it and nothing is typed. */
    private fun setWatchLocked(locked: Boolean) {
        if (watchLocked == locked) return
        watchLocked = locked
        rootView ?: return
        if (locked) {
            webViewHost.currentPage()?.view?.let { webView ->
                webView.clearFocus()
                (appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                    ?.hideSoftInputFromWindow(webView.windowToken, 0)
            }
        }
        applyBrowserFocusability()
        applyWatchAccessibility(webViewHost.currentPage()?.view)
    }

    /** While locked, screen readers skip the page too, so it cannot be clicked through TalkBack. */
    private fun applyWatchAccessibility(webView: View?) {
        val hidden = a11yHiddenWebView?.get()
        if (hidden != null && (hidden !== webView || !watchLocked)) {
            hidden.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            a11yHiddenWebView = null
        }
        if (watchLocked && webView != null) {
            webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            a11yHiddenWebView = java.lang.ref.WeakReference(webView)
        }
    }

    private fun updateHostState(transform: (WebSessionBrowserHostState) -> WebSessionBrowserHostState) {
        val updated = transform(hostState)
        if (updated == hostState) {
            return
        }
        hostState = updated
    }

    private fun copyActiveWebViewSelection() {
        evaluateActiveWebViewSelectedText { selectedText ->
            if (selectedText.isEmpty()) {
                Toast.makeText(appContext, appContext.getString(R.string.no_text_to_copy), Toast.LENGTH_SHORT).show()
                return@evaluateActiveWebViewSelectedText
            }
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("web_selection", selectedText))
            clearActiveWebViewSelection()
        }
    }

    private fun evaluateActiveWebViewSelectedText(onResult: (String) -> Unit) {
        val page = webViewHost.currentPage() ?: return
        page.evaluateJavascript(activeWebViewSelectionTextScript()) { rawValue ->
            try {
                val selectedText = JSONTokener(rawValue).nextValue() as String
                onResult(selectedText)
            } catch (e: Exception) {
                AppLogger.e("WebSessionBrowserHost", "Failed to read selected WebView text", e)
            }
        }
    }

    private fun activeWebViewSelectionTextScript(): String =
        """
        (function() {
            return String(window.__operitTextSelection.getText());
        })();
        """.trimIndent()

    private fun selectAllActiveWebViewText() {
        val page = webViewHost.currentPage() ?: return
        page.evaluateJavascript(
            """
            (function() {
                window.__operitTextSelection.selectAll();
            })();
            """.trimIndent(),
            null
        )
    }

    private fun dismissTextSelectionActions() {
        clearActiveWebViewSelection()
    }

    private fun clearActiveWebViewSelection() {
        val page = webViewHost.currentPage() ?: return
        page.evaluateJavascript(
            """
            (function() {
                window.__operitTextSelection.clear();
            })();
            """.trimIndent(),
            null
        )
        hideTextSelectionActionsOverlay()
    }

    private fun createTextSelectionActionsView(): View {
        val density = appContext.resources.displayMetrics.density
        val cornerRadius = (12f * density)
        val horizontalPadding = (4f * density).roundToInt()
        val verticalPadding = (2f * density).roundToInt()

        return LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            background =
                GradientDrawable().apply {
                    setColor(0xF2242424.toInt())
                    setCornerRadius(cornerRadius)
                    setStroke(dp(1), 0x33FFFFFF)
                }
            elevation = 10f * density

            addView(
                createTextSelectionActionButton(appContext.getString(R.string.copy)) {
                    copyActiveWebViewSelection()
                }
            )
            addView(
                createTextSelectionActionButton(appContext.getString(android.R.string.selectAll)) {
                    selectAllActiveWebViewText()
                }
            )
            addView(
                createTextSelectionActionButton(appContext.getString(R.string.cancel)) {
                    dismissTextSelectionActions()
                }
            )
        }
    }

    private fun createTextSelectionActionButton(
        label: String,
        onClick: () -> Unit
    ): TextView =
        TextView(appContext).apply {
            text = label
            setTextColor(AndroidColor.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setIncludeFontPadding(false)
            setMinWidth(0)
            setMinHeight(dp(38))
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun createTextSelectionActionsLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

    private fun updateTextSelectionActionsLayout(
        actionsView: View,
        anchorX: Double,
        anchorY: Double
    ) {
        val params = selectionActionsParams ?: return
        val page = webViewHost.currentPage() ?: return
        val webView = page.view
        if (actionsView.measuredWidth == 0 || actionsView.measuredHeight == 0) {
            actionsView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }

        val location = IntArray(2)
        webView.getLocationOnScreen(location)
        val scale = page.scale * webView.scaleX
        val width = actionsView.measuredWidth
        val height = actionsView.measuredHeight
        val metrics = appContext.resources.displayMetrics
        val margin = dp(10)
        val screenX = (location[0] + anchorX * scale).roundToInt()
        val selectionTop = (location[1] + anchorY * scale).roundToInt()
        val aboveY = selectionTop - height - margin
        val belowY = selectionTop + dp(28)
        val maxX = (metrics.widthPixels - width - margin).coerceAtLeast(margin)
        val maxY = (metrics.heightPixels - height - margin).coerceAtLeast(margin)

        params.x = (screenX - width / 2).coerceIn(margin, maxX)
        params.y =
            if (aboveY >= margin) {
                aboveY
            } else {
                belowY.coerceIn(margin, maxY)
            }

        selectionActionsParams = params
        if (actionsView.windowToken != null) {
            windowManager.updateViewLayout(actionsView, params)
        }
    }

    fun syncIndicatorWithMinimizedWindow() {
        if (isExpanded) {
            return
        }
        val params = overlayParams ?: return
        val indicator = indicatorParams ?: return
        val root = rootView ?: return

        params.x = indicator.x
        params.y = indicator.y
        overlayParams = params

        if (root.windowToken != null) {
            windowManager.updateViewLayout(root, params)
        }
    }

    private fun showIndicator() {
        if (indicatorView != null) {
            return
        }

        val lifecycleOwner =
            WebSessionOverlayLifecycleOwner().apply {
                handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                handleLifecycleEvent(Lifecycle.Event.ON_START)
                handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }

        val params = indicatorParams ?: createIndicatorLayoutParams().also { indicatorParams = it }

        val indicator =
            ComposeView(appContext).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                installViewTreeOwners(this, lifecycleOwner)
                setContent {
                    WebSessionFloatingTheme {
                        WebSessionMinimizedIndicator(
                            contentDescription =
                                appContext.getString(R.string.web_session_accessibility_minimized_indicator),
                            activeDownloadCount = hostState.browserState.activeDownloadCount,
                            hasFailedDownloads = hostState.browserState.hasFailedDownloads,
                            externalOpenPrompt = hostState.externalOpenPrompt,
                            onToggleFullscreen = { setExpanded(true) },
                            onDragBy = { dx, dy -> moveIndicatorBy(dx, dy) },
                            onConfirmExternalOpen = callbacks::onConfirmExternalOpen,
                            onCancelExternalOpen = callbacks::onCancelExternalOpen
                        )
                    }
                }
            }

        indicatorLifecycleOwner = lifecycleOwner
        indicatorView = indicator
        windowManager.addView(indicator, params)
    }

    private fun hideIndicator() {
        val view = indicatorView ?: return
        indicatorLifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        indicatorLifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        indicatorLifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        indicatorView = null
        indicatorLifecycleOwner = null
    }

    private fun moveIndicatorBy(dx: Int, dy: Int) {
        val indicator = indicatorView ?: return
        val params = indicatorParams ?: return
        val maxX = (appContext.resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0)
        val maxY = (appContext.resources.displayMetrics.heightPixels - params.height).coerceAtLeast(0)

        params.x = (params.x + dx).coerceIn(0, maxX)
        params.y = (params.y + dy).coerceIn(0, maxY)
        indicatorParams = params
        windowManager.updateViewLayout(indicator, params)
        syncIndicatorWithMinimizedWindow()
    }

    private fun updateIndicatorLayoutForCurrentState() {
        if (isExpanded) {
            return
        }
        val indicator = indicatorView ?: return
        val params = indicatorParams ?: return
        val newWidth = indicatorWidthPx()
        val newHeight = indicatorHeightPx()
        if (params.width == newWidth && params.height == newHeight) {
            return
        }
        params.width = newWidth
        params.height = newHeight
        indicatorParams = params
        if (indicator.windowToken != null) {
            windowManager.updateViewLayout(indicator, params)
        }
        syncIndicatorWithMinimizedWindow()
    }

    private fun installViewTreeOwners(
        view: View,
        lifecycleOwner: WebSessionOverlayLifecycleOwner
    ) {
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }

    private fun createOverlayLayoutParams(expanded: Boolean): WindowManager.LayoutParams {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        return if (expanded) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                x = 0
                y = 0
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        } else {
            WindowManager.LayoutParams(
                1,
                1,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(16)
                y = dp(16)
            }
        }
    }

    private fun createIndicatorLayoutParams(): WindowManager.LayoutParams {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        return WindowManager.LayoutParams(
            indicatorWidthPx(),
            indicatorHeightPx(),
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(16)
        }
    }

    private fun indicatorWidthPx(): Int =
        if (hostState.externalOpenPrompt != null) {
            dp(248)
        } else {
            dp(40).coerceAtLeast(1)
        }

    private fun indicatorHeightPx(): Int =
        if (hostState.externalOpenPrompt != null) {
            dp(86)
        } else {
            dp(40).coerceAtLeast(1)
        }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).roundToInt()
}

private class WebSessionOverlayLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val viewModelStoreField = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            savedStateRegistryController.performRestore(null)
        }
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = viewModelStoreField

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            lifecycleRegistry.handleLifecycleEvent(event)
        } else {
            Handler(Looper.getMainLooper()).post {
                lifecycleRegistry.handleLifecycleEvent(event)
            }
        }
    }
}

private class DeceptiveMinimizedLayout(context: Context) : FrameLayout(context) {
    var minimizedMeasureEnabled: Boolean = false
    var fakeWidthPx: Int = 1
    var fakeHeightPx: Int = 1
    /** Takes the back key while set; Orbit's watch screen closes with it. */
    var onBackKey: (() -> Unit)? = null
        set(value) {
            val wasWatching = field != null
            field = value
            if (value == null && wasWatching) resetBackTransform()
            syncBackCallback()
        }

    /**
     * With the app's predictive back on, Android 13+ hands the back key to a window callback before
     * it reaches the views, so [dispatchKeyEvent] alone never sees it. Registered only while
     * [onBackKey] is set, so other times back goes where it always did.
     */
    private var backCallback: Any? = null
    private var backCommitting = false

    private fun syncBackCallback() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val want = onBackKey != null && isAttachedToWindow
        val dispatcher = findOnBackInvokedDispatcher()
        if (want && backCallback == null && dispatcher != null) {
            val callback = if (android.os.Build.VERSION.SDK_INT >= 34) {
                object : android.window.OnBackAnimationCallback {
                    override fun onBackStarted(backEvent: android.window.BackEvent) {
                        if (backCommitting) return
                        animate().cancel()
                    }
                    override fun onBackProgressed(backEvent: android.window.BackEvent) {
                        if (backCommitting) return
                        val p = backEvent.progress.coerceIn(0f, 1f)
                        scaleX = 1f - 0.06f * p
                        scaleY = scaleX
                        translationX = width * 0.12f * p *
                            if (backEvent.swipeEdge == android.window.BackEvent.EDGE_LEFT) 1f else -1f
                        alpha = 1f - 0.2f * p
                    }
                    override fun onBackCancelled() {
                        if (backCommitting) return
                        animate().scaleX(1f).scaleY(1f).translationX(0f).alpha(1f)
                            .setDuration(180).withEndAction(null).start()
                    }
                    override fun onBackInvoked() {
                        if (backCommitting) return
                        if (BrowserPageMenus.dismissOpen()) {
                            onBackCancelled()
                            return
                        }
                        backCommitting = true
                        animate().scaleX(0.94f).scaleY(0.94f).alpha(0.8f).setDuration(140)
                            .withEndAction {
                                onBackKey?.invoke()
                                resetBackTransform()
                            }.start()
                    }
                }
            } else {
                android.window.OnBackInvokedCallback { if (!BrowserPageMenus.dismissOpen()) onBackKey?.invoke() }
            }
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (!want && backCallback != null) {
            runCatching { dispatcher?.unregisterOnBackInvokedCallback(backCallback as android.window.OnBackInvokedCallback) }
            backCallback = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncBackCallback()
    }

    override fun onDetachedFromWindow() {
        resetBackTransform()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { cb ->
                runCatching { findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(cb as android.window.OnBackInvokedCallback) }
            }
        }
        backCallback = null
        super.onDetachedFromWindow()
    }

    init {
        clipChildren = false
        clipToPadding = false
    }

    private fun resetBackTransform() {
        backCommitting = false
        animate().cancel()
        animate().withEndAction(null)
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        alpha = 1f
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val back = onBackKey
        if (back != null && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && !BrowserPageMenus.dismissOpen()) back()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun setMinimizedMeasure(enabled: Boolean, fakeWidth: Int = fakeWidthPx, fakeHeight: Int = fakeHeightPx) {
        minimizedMeasureEnabled = enabled
        fakeWidthPx = fakeWidth.coerceAtLeast(1)
        fakeHeightPx = fakeHeight.coerceAtLeast(1)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!minimizedMeasureEnabled) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val childWidthSpec =
            View.MeasureSpec.makeMeasureSpec(fakeWidthPx, View.MeasureSpec.EXACTLY)
        val childHeightSpec =
            View.MeasureSpec.makeMeasureSpec(fakeHeightPx, View.MeasureSpec.EXACTLY)

        for (i in 0 until childCount) {
            getChildAt(i).measure(childWidthSpec, childHeightSpec)
        }

        setMeasuredDimension(1, 1)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!minimizedMeasureEnabled) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }

        for (i in 0 until childCount) {
            getChildAt(i).layout(0, 0, fakeWidthPx, fakeHeightPx)
        }
    }
}
