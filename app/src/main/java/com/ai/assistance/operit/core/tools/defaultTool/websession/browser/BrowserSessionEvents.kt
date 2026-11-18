package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.Manifest
import android.net.Uri
import android.webkit.ValueCallback
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.Executors
import kotlinx.coroutines.launch
import org.json.JSONArray

private const val SESSION_EVENTS_TAG = "BrowserSessionEvents"

/*
 * What a tab's page reports, handled the same way for the WebView and the built-in engine.
 * All on the main thread.
 */

internal fun StandardBrowserSessionTools.sessionPageStarted(session: BrowserToolSession, url: String) {
    session.currentUrl = url
    session.pageLoaded = false
    session.isLoading = true
    // The error page shown after a refused certificate starts with the same URL; keep its reason.
    if (url != session.sslErrorUrl) {
        session.hasSslError = false
        session.sslErrorDetail = null
        session.sslErrorUrl = null
    }
    session.lastPermissionNote = null
    session.lastSnapshot = null
    clearEventLogs(session)
    session.pendingDialog = null
    notifySessionStateChanged(session)
    userscriptManager.onPageChanged(session.id, url, forceReset = true)
    syncNavigationStateUi(session)
}

internal fun StandardBrowserSessionTools.sessionPageCommitted(session: BrowserToolSession, url: String) {
    session.currentUrl = url
    session.lastSnapshot = null
    notifySessionStateChanged(session)
    userscriptManager.onPageChanged(session.id, url)
    refreshNavigationStateTwice(session)
}

internal fun StandardBrowserSessionTools.sessionPageFinished(session: BrowserToolSession, url: String) {
    session.currentUrl = url
    userscriptManager.onPageChanged(session.id, url)
    session.pageTitle = session.page.title ?: ""
    session.pageLoaded = true
    session.isLoading = false
    notifySessionStateChanged(session)
    applyViewportOverride(session)
    refreshNavigationStateTwice(session)
    injectDownloadHelper(session.page)
    injectTextSelectionHelper(session.page)
    ioScope.launch {
        historyStore.updateTitle(url, session.pageTitle)
    }
}

internal fun StandardBrowserSessionTools.sessionHistoryUpdated(session: BrowserToolSession, url: String, isReload: Boolean) {
    session.currentUrl = url
    val pageTitle = session.page.title.orEmpty()
    notifySessionStateChanged(session)
    refreshNavigationStateTwice(session)
    ioScope.launch {
        historyStore.recordVisit(url, pageTitle, isReload)
    }
}

internal fun StandardBrowserSessionTools.sessionTitleChanged(session: BrowserToolSession, title: String?) {
    session.pageTitle = title.orEmpty()
    refreshSessionUiOnMain(session.id)
    ioScope.launch {
        historyStore.updateTitle(session.currentUrl, session.pageTitle)
    }
}

/** Blob and data links become downloads; other schemes may open another app. True cancels the navigation. */
internal fun StandardBrowserSessionTools.sessionShouldOverrideNavigation(session: BrowserToolSession, uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase()
    if (scheme == "blob") {
        injectBlobDownloaderScript(session, uri.toString())
        return true
    }
    if (scheme == "data") {
        handleInlineDownload(
            session = session,
            base64Data = uri.toString(),
            fileName = "download_${System.currentTimeMillis()}",
            mimeType = guessMimeTypeFromDataUrl(uri.toString()),
            type = "data",
            sourceUrl = uri.toString()
        )
        return true
    }
    return handleNavigationOverrideOnMain(session, uri)
}

internal fun StandardBrowserSessionTools.sessionJsDialog(session: BrowserToolSession, dialog: PendingDialog) {
    session.pendingDialog = dialog
    notifySessionStateChanged(session)
    refreshSessionUiOnMain(session.id)
    AppLogger.d(SESSION_EVENTS_TAG, "web_session js ${dialog.type} pending: ${dialog.message}")
}

internal fun StandardBrowserSessionTools.sessionFileChooser(
    session: BrowserToolSession,
    callback: ValueCallback<Array<Uri>>,
    acceptTypes: List<String>,
    multiple: Boolean,
    capture: Boolean
) {
    session.pendingFileChooserCallback?.onReceiveValue(null)
    session.pendingFileChooserCallback = callback
    session.pendingFileChooserAccept =
        acceptTypes
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    session.pendingFileChooserMultiple = multiple
    session.pendingFileChooserCapture = capture
    session.lastFileChooserRequestAt = System.currentTimeMillis()
    notifySessionStateChanged(session)
    AppLogger.d(SESSION_EVENTS_TAG, "Captured file chooser request for session=${session.id}, multiple=$multiple")
}

internal fun StandardBrowserSessionTools.sessionSslError(session: BrowserToolSession, url: String, reason: String) {
    AppLogger.w(SESSION_EVENTS_TAG, "web_session SSL error, refusing the connection. session=${session.id}, url=$url")
    session.hasSslError = true
    session.sslErrorDetail = "$reason ($url)"
    session.sslErrorUrl = url
    notifySessionStateChanged(session)
    updateNavigationState(session)
    refreshSessionUiOnMain(session.id)
}

/** The page's renderer is gone: the tab closes and the AI can reopen it from the crashed-tab list. */
internal fun StandardBrowserSessionTools.sessionRenderProcessGone(session: BrowserToolSession, crashed: Boolean) {
    AppLogger.e(SESSION_EVENTS_TAG, "web_session render process gone: session=${session.id}, didCrash=$crashed")
    session.pageLoaded = false
    session.isLoading = false
    session.lastSnapshot = null
    session.pendingDialog?.cancel()
    session.pendingDialog = null
    session.pendingFileChooserCallback?.onReceiveValue(null)
    session.pendingFileChooserCallback = null
    notifySessionStateChanged(session)
    session.currentUrl.takeIf { it.isNotBlank() && it != "about:blank" }?.let { url ->
        StandardBrowserSessionTools.crashedTabs.add(
            StandardBrowserSessionTools.CrashedTab(
                url = url,
                title = session.pageTitle,
                profileName = session.profileName,
                at = System.currentTimeMillis(),
                crashed = crashed,
                browserTaskId = session.browserTaskId,
            )
        )
        while (StandardBrowserSessionTools.crashedTabs.size > 10) {
            StandardBrowserSessionTools.crashedTabs.removeAt(0)
        }
    }
    closeSession(session.id)
    showToast(
        if (crashed) {
            context.getString(R.string.web_session_render_process_crashed)
        } else {
            context.getString(R.string.web_session_render_process_gone)
        }
    )
}

/** Navigation state now, and again once the page has settled this frame. */
internal fun StandardBrowserSessionTools.refreshNavigationStateTwice(session: BrowserToolSession) {
    syncNavigationStateUi(session)
    session.page.view.post { syncNavigationStateUi(session) }
}

/** Answers an open dialog with Cancel, whichever engine asked. */
internal fun PendingDialog.cancel() {
    jsPromptResult?.cancel()
    jsResult?.cancel()
    answer?.invoke(false, null)
}

/*
 * The built-in engine's tabs.
 */

/** The app's script objects (downloads, async results, text selection) run off the page's thread, as WebView's do. */
private val engineBridgeExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "operit-engine-bridge").apply { isDaemon = true }
}

/** A tab on the built-in engine, or null when there is none or it cannot start. */
internal fun StandardBrowserSessionTools.createEngineSessionOnMain(
    sessionId: String,
    sessionName: String?,
    customUserAgent: String?,
    profileName: String,
    browserTaskId: String?,
    popup: EngineBrowserPage? = null,
): BrowserToolSession? {
    val engine = BrowserEngines.current ?: return null
    val page =
        popup ?: runCatching { engine.createPage(context.applicationContext, profileName) }
            .onFailure { AppLogger.e(SESSION_EVENTS_TAG, "The browser engine could not open a tab; using the WebView", it) }
            .getOrNull() ?: return null
    val session =
        BrowserToolSession(
            id = sessionId,
            page = page,
            sessionName = sessionName,
            customUserAgent = customUserAgent,
            profileName = profileName,
            browserTaskId = browserTaskId,
        )
    if (StandardBrowserSessionTools.desktopModeEnabled) {
        session.viewportWidthPx = StandardBrowserSessionTools.DEFAULT_DESKTOP_VIEWPORT_WIDTH
        session.viewportHeightPx = StandardBrowserSessionTools.DEFAULT_DESKTOP_VIEWPORT_HEIGHT
    }
    page.view.contentDescription = context.getString(R.string.web_session_accessibility_web_content)
    page.events = engineEvents(session)
    applySessionUserAgent(session, resolveUserAgent(customUserAgent))
    BrowserCdp.attachEngineOnMain(this, session, page)
    userscriptManager.attachSession(session.id, session.page)
    return session
}

private fun StandardBrowserSessionTools.engineEvents(session: BrowserToolSession): BrowserPageEvents {
    val downloadBridge = BrowserWebDownloadBridge(this, session)
    val asyncBridge = BrowserAsyncBridge()
    val selectionBridge = BrowserTextSelectionBridge()
    return object : BrowserPageEvents {
        override fun onPageStarted(url: String) = sessionPageStarted(session, url)

        override fun onUrlChanged(url: String) {
            sessionPageCommitted(session, url)
            sessionHistoryUpdated(session, url, isReload = false)
        }

        override fun onPageFinished(url: String) = sessionPageFinished(session, url)

        override fun interceptRequest(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? =
            userscriptManager.interceptWebRequest(session.id, request)

        override fun onTitleChanged(title: String) = sessionTitleChanged(session, title)

        override fun shouldOverrideNavigation(url: String): Boolean =
            sessionShouldOverrideNavigation(session, Uri.parse(url))

        override fun onSslError(url: String, reason: String) = sessionSslError(session, url, reason)

        override fun onRenderProcessGone(crashed: Boolean) = sessionRenderProcessGone(session, crashed)

        override fun onConsoleMessage(level: String, message: String, sourceId: String?, lineNumber: Int?) {
            appendConsoleEntry(session, BrowserConsoleEntry(level, message, sourceId, lineNumber))
        }

        override fun onJsDialog(
            type: String,
            message: String,
            defaultValue: String?,
            url: String?,
            answer: (Boolean, String?) -> Unit
        ) = sessionJsDialog(session, PendingDialog(type, message, defaultValue, url, answer = answer))

        override fun onFileChooser(acceptTypes: List<String>, multiple: Boolean, answer: (Array<Uri>?) -> Unit) =
            sessionFileChooser(session, ValueCallback { answer(it) }, acceptTypes, multiple, capture = false)

        override fun onPermissionRequest(origin: String, kinds: Set<String>, answer: (Boolean) -> Unit) =
            handleEnginePermissionRequest(session, origin, kinds, answer)

        override fun onCreateWindow(popup: EngineBrowserPage, url: String): Boolean {
            val popupSession = runCatching { createPopupSessionOnMain(session, popup) }
                .onFailure { AppLogger.w(SESSION_EVENTS_TAG, "Could not open the page's window: ${it.message}") }
                .getOrNull() ?: return false
            refreshSessionUiOnMain(popupSession.id)
            return true
        }

        override fun onCloseWindow(popup: EngineBrowserPage) {
            StandardBrowserSessionTools.sessions.values.firstOrNull { it.page === popup }?.let { closeSession(it.id) }
        }

        override fun onDownload(url: String, mimeType: String?, suggestedName: String?, contentLength: Long) {
            val disposition = suggestedName?.takeIf { it.isNotBlank() }?.let { "attachment; filename=\"${it.replace("\"", "")}\"" }
            createDownloadListener(session).onDownloadStart(url, session.page.userAgent, disposition, mimeType, contentLength)
        }

        override fun onScriptBridge(bridge: String, method: String, args: JSONArray) {
            engineBridgeExecutor.execute {
                runCatching {
                    when (bridge) {
                        "OperitWebDownloadBridge" -> when (method) {
                            "downloadBase64" -> downloadBridge.downloadBase64(args.optString(0), args.optString(1), args.optString(2))
                            "log" -> downloadBridge.log(args.optString(0))
                        }
                        "OperitAsyncBridge" -> when (method) {
                            "resolve" -> asyncBridge.resolve(args.optString(0), args.optString(1))
                            "reject" -> asyncBridge.reject(args.optString(0), args.optString(1))
                        }
                        "OperitTextSelectionBridge" -> when (method) {
                            "showActions" -> selectionBridge.showActions(args.optDouble(0), args.optDouble(1))
                            "performHapticFeedback" -> selectionBridge.performHapticFeedback()
                            "hideActions" -> selectionBridge.hideActions()
                        }
                    }
                }.onFailure { AppLogger.w(SESSION_EVENTS_TAG, "Script call $bridge.$method failed: ${it.message}") }
            }
        }
    }
}

/**
 * Camera, microphone and location requests of an engine tab: a saved "deny" for the site wins,
 * then the Android permission decides, as for the WebView. Kinds the browser does not offer are refused.
 */
internal fun StandardBrowserSessionTools.handleEnginePermissionRequest(
    session: BrowserToolSession,
    rawOrigin: String,
    kinds: Set<String>,
    answer: (Boolean) -> Unit
) {
    val origin = BrowserSitePermissions.originOf(rawOrigin)
    val kindsText = kinds.joinToString(", ")
    val site = origin ?: "the page"
    val offered = setOf("camera", "microphone", "location")
    if (kinds.isEmpty() || !offered.containsAll(kinds)) {
        answer(false)
        session.lastPermissionNote = "$site asked for $kindsText: refused (not offered by this browser)"
        return
    }
    val refused = kinds.filter { kind ->
        origin != null && BrowserSitePermissions.decision(context, session.profileName, origin, kind) == false
    }
    if (refused.isNotEmpty()) {
        answer(false)
        session.lastPermissionNote = "$site asked for $kindsText: refused by a saved site setting"
        return
    }
    val required =
        kinds.flatMap { kind ->
            when (kind) {
                "camera" -> listOf(Manifest.permission.CAMERA)
                "microphone" -> listOf(Manifest.permission.RECORD_AUDIO)
                else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
    ioScope.launch {
        val results = ensureAndroidPermissions(required)
        val granted = kinds.all { kind ->
            when (kind) {
                "camera" -> results[Manifest.permission.CAMERA] == true
                "microphone" -> results[Manifest.permission.RECORD_AUDIO] == true
                else -> results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            }
        }
        StandardBrowserSessionTools.mainHandler.post {
            answer(granted)
            session.lastPermissionNote =
                "$site asked for $kindsText: " + if (granted) "allowed" else "refused (Android permission not given)"
            if (!granted) showToast(context.getString(R.string.web_session_permission_denied))
        }
    }
}
