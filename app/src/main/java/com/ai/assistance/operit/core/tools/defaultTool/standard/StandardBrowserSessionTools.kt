package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.*
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptInstallSourceType
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.runtime.WebSessionUserscriptManager
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.storage.UserscriptRepository
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class StandardBrowserSessionTools(internal val context: Context) : ToolExecutor {

    companion object {
        private const val TAG = "BrowserSessionTools"
        internal const val DEFAULT_TIMEOUT_MS = 10_000L
        internal const val MAX_EVENT_LOG_ENTRIES = 500
        internal const val DESKTOP_CHROME_MAJOR_VERSION = "124"
        internal const val DESKTOP_CHROME_FULL_VERSION = "124.0.0.0"
        internal const val DEFAULT_DESKTOP_VIEWPORT_WIDTH = 1280
        internal const val DEFAULT_DESKTOP_VIEWPORT_HEIGHT = 720
        internal const val MAX_VIEWPORT_DIMENSION_CSS_PX = 4096
        internal const val MAX_VIEWPORT_PIXEL_COUNT = 8_388_608L
        internal const val MAX_VIEWPORT_LAYOUT_PIXEL_COUNT = 33_554_432L
        internal const val MAX_SCREENSHOT_DIMENSION_PX = 32_768
        internal const val MAX_SCREENSHOT_PIXEL_COUNT = 16_777_216L
        private const val DEVTOOLS_INLINE_CHARS = 20_000
        private const val DOWNLOAD_LIST_PAGE_SIZE = 20
        private const val DEVTOOLS_PRETTY_LIMIT_CHARS = 200_000
        private val DEVTOOLS_METHOD = Regex("^[A-Z][A-Za-z]*\\.[a-z][A-Za-z]*$")
        /**
         * DevTools domains that stay inside the current tab. Browser-wide and target domains could
         * reach other tabs and their logins; Fetch would pause requests with no one to resume them.
         */
        private val DEVTOOLS_DOMAINS =
            setOf(
                "Accessibility", "Animation", "Audits", "CSS", "CacheStorage", "Console", "DOM",
                "DOMDebugger", "DOMSnapshot", "DOMStorage", "Debugger", "Emulation", "HeapProfiler",
                "IO", "IndexedDB", "Input", "LayerTree", "Log", "Memory", "Network", "Overlay", "Page",
                "Performance", "PerformanceTimeline", "Profiler", "Runtime", "Security", "ServiceWorker",
                "WebAudio"
            )

        /**
         * Chrome's version from the phone's WebView, so the user agent and its client hints name
         * the engine that really runs the page.
         */
        internal val chromeFullVersion: String by lazy {
            runCatching {
                androidx.webkit.WebViewCompat.getCurrentWebViewPackage(
                    com.ai.assistance.operit.core.application.OperitApplication.instance.applicationContext
                )?.versionName
            }.getOrNull()
                ?.let { Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+").find(it)?.value }
                ?: DESKTOP_CHROME_FULL_VERSION
        }
        internal val chromeMajorVersion: String get() = chromeFullVersion.substringBefore('.')

        /** Chrome's reduced user agents, which carry only the major version. */
        internal val DEFAULT_USER_AGENT: String
            get() =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$chromeMajorVersion.0.0.0 Safari/537.36"
        internal val MOBILE_USER_AGENT: String
            get() =
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$chromeMajorVersion.0.0.0 Mobile Safari/537.36"

        internal val mainHandler = Handler(Looper.getMainLooper())

        internal val sessions = ConcurrentHashMap<String, WebSession>()
        internal val sessionOrder = mutableListOf<String>()
        internal val pendingAsyncJsCalls = ConcurrentHashMap<String, PendingAsyncJsCall>()
        private val snapshotGenerationSeed = AtomicLong(0L)

        internal val sessionOrderLock = Any()
        internal val overlayLock = Any()
        internal val sessionConfigLock = Any()

        @Volatile internal var browserHost: WebSessionBrowserHost? = null
        /** The last tab each login profile had in front, so an Orbit returns to its own tab. */
        internal val lastActiveByProfile = ConcurrentHashMap<String, String>()
        @Volatile internal var activeSessionId: String? = null
            set(value) {
                field = value
                value?.let { id -> sessions[id]?.let { lastActiveByProfile[it.profileName] = id } }
            }
        @Volatile internal var desktopModeEnabled: Boolean = true
        @Volatile internal var desktopModeInitialized: Boolean = false
        @Volatile internal var pendingExternalOpenRequest: PendingExternalOpenRequest? = null
        /** Tabs whose page process died, newest last, so they can be reopened instead of silently lost. */
        internal val crashedTabs = java.util.concurrent.CopyOnWriteArrayList<CrashedTab>()
    }

    internal data class CrashedTab(
        val url: String,
        val title: String,
        val profileName: String,
        val at: Long,
        val crashed: Boolean
    )

    internal val historyStore by lazy { WebSessionHistoryStore.getInstance(context.applicationContext) }
    private val userscriptRepository by lazy { UserscriptRepository.getInstance(context.applicationContext) }
    internal val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val userscriptManager by lazy {
        WebSessionUserscriptManager(
            context = context.applicationContext,
            onOpenUserscriptUi = {
                mainHandler.post {
                    openUserscriptSheetOnMain()
                }
            },
            onOpenTab = { url, active ->
                runOnMainSync<String?> {
                    openUserscriptTabOnMain(context.applicationContext, url, active)
                }
            },
            onActivateSession = { sessionId ->
                runOnMainSync<Unit> {
                    activateSessionOnMain(sessionId)
                }
            },
            onCloseSession = { sessionId ->
                closeSession(sessionId)
            },
            onDownload = { sessionId, url, fileName ->
                runOnMainSync<Unit> {
                    handleUserscriptDownloadOnMain(sessionId, url, fileName)
                }
            },
            onMenuCommandsChanged = { sessionId ->
                mainHandler.post {
                    refreshSessionUiOnMain(sessionId)
                }
            },
            onToast = { message -> showToast(message) }
        )
    }

    init {
        ensureDesktopModeInitialized()
        initializeBrowserDownloadSupport()
    }

    internal data class WebSession(
        val id: String,
        val webView: WebView,
        val sessionName: String?,
        val customUserAgent: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        /** Whose saved logins this tab uses; see [BrowserProfiles]. */
        val profileName: String = BrowserProfiles.MAIN
    ) {
        @Volatile var currentUrl: String = "about:blank"
        @Volatile var pageTitle: String = ""
        @Volatile var pageLoaded: Boolean = false
        @Volatile var isLoading: Boolean = false
        @Volatile var canGoBack: Boolean = false
        @Volatile var canGoForward: Boolean = false
        @Volatile var hasSslError: Boolean = false
        /** Why the last certificate was refused, for the AI; null when there was no problem. */
        @Volatile var sslErrorDetail: String? = null
        @Volatile var sslErrorUrl: String? = null
        @Volatile var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
        @Volatile var lastFileChooserRequestAt: Long = 0L
        /** What the open file picker accepts, so the AI knows a picker is waiting and for what. */
        @Volatile var pendingFileChooserAccept: List<String> = emptyList()
        @Volatile var pendingFileChooserMultiple: Boolean = false
        @Volatile var pendingFileChooserCapture: Boolean = false
        /** The latest camera/microphone/location request and its answer, shown to the AI. */
        @Volatile var lastPermissionNote: String? = null
        @Volatile var lastDownloadEvent: WebDownloadEvent? = null
        @Volatile var lastDownloadEventAt: Long = 0L
        @Volatile var pendingDialog: PendingDialog? = null
        @Volatile var viewportWidthPx: Int? = null
        @Volatile var viewportHeightPx: Int? = null
        @Volatile var hasExplicitViewportSize: Boolean = false
        @Volatile var lastSnapshot: BrowserSnapshot? = null
        val stateSignal: Object = Object()
        @Volatile var stateVersion: Long = 0L
        val consoleEntries: MutableList<BrowserConsoleEntry> = mutableListOf()
        val networkEntries: MutableList<BrowserNetworkRequestEntry> = mutableListOf()
    }

    internal data class BrowserActionSettlementPolicy(
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val waitForDocumentReady: Boolean = false,
        val waitForNavigationChange: Boolean = false,
        val waitForText: String? = null,
        val waitForTextGone: String? = null,
        val waitForTimeSeconds: Double? = null,
        val allowActivePageSwitch: Boolean = true,
        val captureSnapshot: Boolean = true
    )

    internal data class BrowserActionMarkers(
        val initialSessionId: String,
        val initialUrl: String,
        val consoleTimestamp: Long,
        val downloadTimestamp: Long,
        val snapshotGeneration: Long,
        val startedAt: Long
    )

    internal data class BrowserActionSettlement(
        val registry: BrowserPageRegistry,
        val session: WebSession,
        val snapshot: BrowserSnapshot?,
        val consoleMarker: Long,
        val downloadMarker: Long,
        val timedOut: Boolean = false
    )

    override fun invoke(tool: AITool): ToolResult {
        return try {
            val profile = BrowserProfiles.forCurrentCaller(context, tool)
            BrowserProfiles.withScope(profile) {
            // Downloads and saved site permissions do not touch a tab, so a long wait for a
            // download must not hold up other agents' page actions in the shared queue.
            when (tool.name) {
                "browser_downloads" -> return@withScope browserDownloads(tool)
                "browser_permissions" -> return@withScope browserPermissions(tool)
            }
            com.ai.assistance.operit.features.orbit.OrbitBrowserControl.aiCall { when (tool.name) {
                "browser_click" -> browserClick(tool)
                "browser_close" -> browserClose(tool)
                "browser_close_all" -> browserCloseAll(tool)
                "browser_console_messages" -> browserConsoleMessages(tool)
                "browser_drag" -> browserDrag(tool)
                "browser_evaluate" -> browserEvaluate(tool)
                "browser_file_upload" -> browserFileUpload(tool)
                "browser_fill_form" -> browserFillForm(tool)
                "browser_handle_dialog" -> browserHandleDialog(tool)
                "browser_hover" -> browserHover(tool)
                "browser_navigate" -> browserNavigate(tool)
                "browser_navigate_back" -> browserNavigateBack(tool)
                "browser_network_requests" -> browserNetworkRequests(tool)
                "browser_press_key" -> browserPressKey(tool)
                "browser_resize" -> browserResize(tool)
                "browser_run_code" -> browserRunCode(tool)
                "browser_select_option" -> browserSelectOption(tool)
                "browser_wait_for" -> browserWaitFor(tool)
                "browser_snapshot" -> browserSnapshot(tool)
                "browser_tabs" -> browserTabs(tool)
                "browser_take_screenshot" -> browserTakeScreenshot(tool)
                "browser_type" -> browserType(tool)
                "browser_mouse_click_xy" -> browserMouseClickXy(tool)
                "browser_devtools" -> browserDevtools(tool)
                "browser_clipboard" -> browserClipboard(tool)
                else -> error(tool.name, "Unsupported browser session tool: ${tool.name}")
            } }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Tool execution failed: ${tool.name}", e)
            error(tool.name, e.message ?: "Unknown error")
        }
    }

    private fun browserNavigate(tool: AITool): ToolResult {
        val targetUrl = param(tool, "url")?.trim()
        if (targetUrl.isNullOrBlank()) {
            return error(tool.name, "url is required")
        }
        ensureOverlayPermission(tool.name)?.let { return it }
        val headers = parseHeaders(param(tool, "headers"))
        val session =
            runOnMainSync {
                getSession(null) ?: createSessionTabOnMain(context.applicationContext, "about:blank")
            }
        val markers = captureActionMarkers(session)
        // Attach DevTools before loading so this page's requests get their status codes.
        BrowserCdp.connection(this, session)

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
            navigateSessionOnMain(session, targetUrl, headers)
        }
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy =
                    BrowserActionSettlementPolicy(
                        timeoutMs = DEFAULT_TIMEOUT_MS.coerceAtLeast(6_000L),
                        waitForDocumentReady = true,
                        waitForNavigationChange = true,
                        captureSnapshot = false
                    )
            )

        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = "await page.goto(${quoteJsCode(targetUrl)});",
                result = "Navigated to ${settlement.session.currentUrl.ifBlank { targetUrl }}"
            )
        )
    }

    private fun browserClick(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
        val selector = param(tool, "selector")?.trim()?.takeIf { it.isNotBlank() }
        if (ref == null && selector == null) {
            return error(tool.name, "ref or selector is required")
        }

        val buttonRaw = param(tool, "button")?.trim()
        val button =
            when {
                buttonRaw.isNullOrBlank() -> "left"
                buttonRaw == "left" || buttonRaw == "right" || buttonRaw == "middle" -> buttonRaw
                else -> return error(tool.name, "button must be one of: left, right, middle")
            }

        val doubleClick = boolParam(tool, "doubleClick", false)

        val (modifiers, invalidModifiers) = parseClickModifiers(param(tool, "modifiers"))
        if (invalidModifiers.isNotEmpty()) {
            return error(
                tool.name,
                "Invalid modifiers: ${invalidModifiers.joinToString(", ")}. Allowed: Alt, Control, ControlOrMeta, Meta, Shift"
            )
        }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        if (ref != null && requireSnapshotNode(session, ref) == null) {
            return pageError(
                tool.name,
                session,
                "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
            )
        }
        val markers = captureActionMarkers(session)
        val code =
            when {
                ref != null -> buildClickCode(session, ref, button, doubleClick, modifiers)
                else -> buildClickCodeForSelector(selector!!, button, doubleClick, modifiers)
            }
        val useNativeTap =
            ref != null &&
                button == "left" &&
                !doubleClick &&
                modifiers.isEmpty()
        val jsResult =
            when {
                useNativeTap && dispatchNativeTapByRef(session.webView, ref!!) == true ->
                    JSONObject()
                        .put("ok", true)
                        .put("ref", ref)
                        .put("button", button)
                        .put("doubleClick", false)
                        .put("activationMethod", "native_webview_tap")
                ref != null ->
                    dispatchClickByRef(
                        webView = session.webView,
                        ref = ref,
                        button = button,
                        modifiers = modifiers,
                        doubleClick = doubleClick
                    )
                else ->
                    dispatchClickBySelector(
                        webView = session.webView,
                        selector = selector!!,
                        button = button,
                        modifiers = modifiers,
                        doubleClick = doubleClick
                    )
            }
        if (jsResult?.optBoolean("ok", false) != true) {
            if (jsResult?.optString("error") == "ref_not_found") {
                return pageError(
                    tool.name,
                    session,
                    "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
                )
            }
            if (jsResult?.optString("error") == "selector_not_found") {
                return pageError(
                    tool.name,
                    session,
                    "Selector ${selector ?: ""} did not match any elements."
                )
            }
            return error(tool.name, "Click failed: ${jsResult?.optString("error") ?: "unknown"}")
        }
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy =
                    BrowserActionSettlementPolicy(
                        waitForDocumentReady = true,
                        waitForTimeSeconds = 0.5
                    )
            )
        val downloadEvent = latestProfileDownloadEventAfter(settlement.downloadMarker, settlement.session.profileName)
        if (downloadEvent?.status == "failed") {
            return pageError(
                tool.name,
                settlement.session,
                "Click triggered a download, but it failed: ${downloadEvent.error ?: downloadEvent.fileName}"
            )
        }

        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = code,
                result =
                    when {
                        ref != null -> "Clicked ref=$ref with button=$button${if (doubleClick) " (double)" else ""}"
                        else -> "Clicked selector=${selector ?: ""} with button=$button${if (doubleClick) " (double)" else ""}"
                    }
            )
        )
    }

    private fun dispatchNativeTapByRef(webView: WebView, ref: String): Boolean {
        val rect = resolveElementRect(webView, ref) ?: return false
        val pageScale = webView.scale.takeIf { it > 0f } ?: 1f
        val x =
            (((rect.left + rect.right) / 2f) * pageScale)
                .coerceIn(1f, (webView.width - 1).coerceAtLeast(1).toFloat())
        val y =
            (((rect.top + rect.bottom) / 2f) * pageScale)
                .coerceIn(1f, (webView.height - 1).coerceAtLeast(1).toFloat())
        return runOnMainSync {
            try {
                webView.requestFocus()
                val downTime = SystemClock.uptimeMillis()
                val down =
                    MotionEvent.obtain(
                        downTime,
                        downTime,
                        MotionEvent.ACTION_DOWN,
                        x,
                        y,
                        0
                    )
                val up =
                    MotionEvent.obtain(
                        downTime,
                        downTime + 16L,
                        MotionEvent.ACTION_UP,
                        x,
                        y,
                        0
                    )
                try {
                    val downHandled = webView.dispatchTouchEvent(down)
                    val upHandled = webView.dispatchTouchEvent(up)
                    downHandled || upHandled
                } finally {
                    down.recycle()
                    up.recycle()
                }
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun buildClickCodeForSelector(
        selector: String,
        button: String,
        doubleClick: Boolean,
        modifiers: Set<String>
    ): String {
        val method = if (doubleClick) "dblclick" else "click"
        val options = mutableListOf<String>()
        if (button != "left") {
            options += "button: ${quoteJsCode(button)}"
        }
        if (modifiers.isNotEmpty()) {
            options += "modifiers: ${renderJsArrayCode(modifiers.toList())}"
        }
        val locator = "page.locator(${quoteJsCode(selector)})"
        return if (options.isEmpty()) {
            "await $locator.$method();"
        } else {
            "await $locator.$method({ ${options.joinToString(", ")} });"
        }
    }

    private fun dispatchClickByRef(
        webView: WebView,
        ref: String,
        button: String,
        modifiers: Set<String>,
        doubleClick: Boolean
    ): JSONObject? {
        val buttonValue =
            when (button) {
                "middle" -> 1
                "right" -> 2
                else -> 0
            }
        val buttonsValue =
            when (button) {
                "middle" -> 4
                "right" -> 2
                else -> 1
            }

        val altKey = modifiers.contains("Alt")
        val controlKey = modifiers.contains("Control") || modifiers.contains("ControlOrMeta")
        val metaKey = modifiers.contains("Meta") || modifiers.contains("ControlOrMeta")
        val shiftKey = modifiers.contains("Shift")

        val script =
            """
            (function() {
                try {
                    const refValue = ${quoteJs(ref)};
                    ${browserRefResolverScript()}
                    const resolved = __operitResolveRef(refValue);
                    if (!resolved || !resolved.element) {
                        return JSON.stringify({ ok: false, error: "ref_not_found", ref: refValue });
                    }
                    const target = resolved.element;
                    const targetWindow = resolved.window || window;
                    const anchor = target.closest('a[href]');
                    try { target.scrollIntoView({ block: "center", inline: "center" }); } catch (_) {}
                    const rect = target.getBoundingClientRect();
                    const x = rect.left + rect.width / 2;
                    const y = rect.top + rect.height / 2;

                    try { target.focus({ preventScroll: true }); } catch (_) {}

                    const buttonValue = ${buttonValue};
                    const buttonsValue = ${buttonsValue};
                    const altKey = ${if (altKey) "true" else "false"};
                    const ctrlKey = ${if (controlKey) "true" else "false"};
                    const metaKey = ${if (metaKey) "true" else "false"};
                    const shiftKey = ${if (shiftKey) "true" else "false"};

                    function emit(type, detail) {
                        try {
                            const MouseEventCtor = targetWindow.MouseEvent || MouseEvent;
                            target.dispatchEvent(new MouseEventCtor(type, {
                                bubbles: true,
                                cancelable: true,
                                composed: true,
                                view: targetWindow,
                                detail: detail,
                                clientX: x,
                                clientY: y,
                                screenX: x,
                                screenY: y,
                                button: buttonValue,
                                buttons: buttonsValue,
                                altKey,
                                ctrlKey,
                                metaKey,
                                shiftKey
                            }));
                        } catch (_) {}
                    }

                    function clickOnce(detail) {
                        emit("mousedown", detail);
                        emit("mouseup", detail);
                        emit("click", detail);
                    }

                    let activationMethod = "mouse_event";
                    let activationTag = String(target.tagName || "").toLowerCase();
                    const nativeAnchorClickEligible = !${if (doubleClick) "true" else "false"} &&
                        buttonValue === 0 && !altKey && !ctrlKey && !metaKey && !shiftKey &&
                        !!anchor && typeof anchor.click === "function";

                    setTimeout(() => {
                        try {
                            if (nativeAnchorClickEligible) {
                                activationMethod = "native_anchor_click";
                                activationTag = String(anchor.tagName || "").toLowerCase();
                                anchor.click();
                            } else if (${if (doubleClick) "true" else "false"}) {
                                clickOnce(1);
                                clickOnce(2);
                                emit("dblclick", 2);
                            } else {
                                clickOnce(1);
                            }
                        } catch (_) {}
                    }, 0);

                    return JSON.stringify({
                        ok: true,
                        ref: refValue,
                        button: ${quoteJs(button)},
                        doubleClick: ${if (doubleClick) "true" else "false"},
                        tag: String(target.tagName || "").toLowerCase(),
                        activationMethod,
                        activationTag,
                        href: anchor ? String(anchor.href || "") : ""
                    });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                }
            })();
            """.trimIndent()

        return runJsonScript(webView, script, "click_dispatch_error")
    }

    private fun dispatchHoverByRef(webView: WebView, ref: String): JSONObject? {
        val script =
            """
            (function() {
                try {
                    const refValue = ${quoteJs(ref)};
                    ${browserRefResolverScript()}
                    const resolved = __operitResolveRef(refValue);
                    if (!resolved || !resolved.element) {
                        return JSON.stringify({ ok: false, error: "ref_not_found" });
                    }
                    const target = resolved.element;
                    const targetWindow = resolved.window || window;
                    try { target.scrollIntoView({ block: "center", inline: "center" }); } catch (_) {}
                    const rect = target.getBoundingClientRect();
                    const x = rect.left + rect.width / 2;
                    const y = rect.top + rect.height / 2;
                    ["pointerover", "mouseover", "mouseenter", "mousemove"].forEach((type) => {
                        try {
                            const MouseEventCtor = targetWindow.MouseEvent || MouseEvent;
                            target.dispatchEvent(new MouseEventCtor(type, {
                                bubbles: true,
                                cancelable: true,
                                composed: true,
                                view: targetWindow,
                                clientX: x,
                                clientY: y
                            }));
                        } catch (_) {}
                    });
                    return JSON.stringify({ ok: true, ref: refValue });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                }
            })();
            """.trimIndent()
        return runJsonScript(webView, script, "hover_dispatch_error")
    }

    private fun dispatchDragByRef(webView: WebView, startRef: String, endRef: String): JSONObject? {
        val script =
            """
            (function() {
                try {
                    ${browserRefResolverScript()}
                    const startResolved = __operitResolveRef(${quoteJs(startRef)});
                    const endResolved = __operitResolveRef(${quoteJs(endRef)});
                    const start = startResolved && startResolved.element;
                    const end = endResolved && endResolved.element;
                    if (!start || !end) {
                        return JSON.stringify({ ok: false, error: !start ? "start_ref_not_found" : "end_ref_not_found" });
                    }
                    const startWindow = (startResolved && startResolved.window) || window;
                    const endWindow = (endResolved && endResolved.window) || window;
                    try { start.scrollIntoView({ block: "center", inline: "center" }); } catch (_) {}
                    try { end.scrollIntoView({ block: "center", inline: "center" }); } catch (_) {}
                    const DataTransferCtor = startWindow.DataTransfer || endWindow.DataTransfer || (typeof DataTransfer === "function" ? DataTransfer : null);
                    const DragEventCtor = startWindow.DragEvent || endWindow.DragEvent || (typeof DragEvent === "function" ? DragEvent : null);
                    const EventCtor = startWindow.Event || endWindow.Event || Event;
                    const dataTransfer = typeof DataTransferCtor === "function" ? new DataTransferCtor() : null;
                    const dispatchDrag = (target, type) => {
                        try {
                            const event = DragEventCtor ? new DragEventCtor(type, {
                                bubbles: true,
                                cancelable: true,
                                composed: true,
                                dataTransfer
                            }) : new EventCtor(type, { bubbles: true, cancelable: true, composed: true });
                            target.dispatchEvent(event);
                        } catch (_) {
                            try {
                                const fallback = new EventCtor(type, { bubbles: true, cancelable: true, composed: true });
                                fallback.dataTransfer = dataTransfer;
                                target.dispatchEvent(fallback);
                            } catch (_) {}
                        }
                    };
                    dispatchDrag(start, "dragstart");
                    dispatchDrag(end, "dragenter");
                    dispatchDrag(end, "dragover");
                    dispatchDrag(end, "drop");
                    dispatchDrag(start, "dragend");
                    return JSON.stringify({ ok: true, startRef: ${quoteJs(startRef)}, endRef: ${quoteJs(endRef)} });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                }
            })();
            """.trimIndent()
        return runJsonScript(webView, script, "drag_dispatch_error")
    }

    private fun dispatchClickBySelector(
        webView: WebView,
        selector: String,
        button: String,
        modifiers: Set<String>,
        doubleClick: Boolean
    ): JSONObject? {
        val buttonValue =
            when (button) {
                "middle" -> 1
                "right" -> 2
                else -> 0
            }
        val buttonsValue =
            when (button) {
                "middle" -> 4
                "right" -> 2
                else -> 1
            }

        val altKey = modifiers.contains("Alt")
        val controlKey = modifiers.contains("Control") || modifiers.contains("ControlOrMeta")
        val metaKey = modifiers.contains("Meta") || modifiers.contains("ControlOrMeta")
        val shiftKey = modifiers.contains("Shift")

        val script =
            """
            (function() {
                try {
                    const selectorValue = ${quoteJs(selector)};
                    const target = document.querySelector(selectorValue);
                    if (!target) {
                        return JSON.stringify({ ok: false, error: "selector_not_found", selector: selectorValue });
                    }
                    const targetWindow = target.ownerDocument && target.ownerDocument.defaultView ? target.ownerDocument.defaultView : window;
                    const anchor = target.closest('a[href]');
                    try { target.scrollIntoView({ block: "center", inline: "center" }); } catch (_) {}
                    const rect = target.getBoundingClientRect();
                    const x = rect.left + rect.width / 2;
                    const y = rect.top + rect.height / 2;

                    try { target.focus({ preventScroll: true }); } catch (_) {}

                    const buttonValue = ${buttonValue};
                    const buttonsValue = ${buttonsValue};
                    const altKey = ${if (altKey) "true" else "false"};
                    const ctrlKey = ${if (controlKey) "true" else "false"};
                    const metaKey = ${if (metaKey) "true" else "false"};
                    const shiftKey = ${if (shiftKey) "true" else "false"};

                    function emit(type, detail) {
                        try {
                            const MouseEventCtor = targetWindow.MouseEvent || MouseEvent;
                            target.dispatchEvent(new MouseEventCtor(type, {
                                bubbles: true,
                                cancelable: true,
                                composed: true,
                                view: targetWindow,
                                detail: detail,
                                clientX: x,
                                clientY: y,
                                screenX: x,
                                screenY: y,
                                button: buttonValue,
                                buttons: buttonsValue,
                                altKey,
                                ctrlKey,
                                metaKey,
                                shiftKey
                            }));
                        } catch (_) {}
                    }

                    function clickOnce(detail) {
                        emit("mousedown", detail);
                        emit("mouseup", detail);
                        emit("click", detail);
                    }

                    let activationMethod = "mouse_event";
                    let activationTag = String(target.tagName || "").toLowerCase();
                    const nativeAnchorClickEligible = !${if (doubleClick) "true" else "false"} &&
                        buttonValue === 0 && !altKey && !ctrlKey && !metaKey && !shiftKey &&
                        !!anchor && typeof anchor.click === "function";

                    setTimeout(() => {
                        try {
                            if (nativeAnchorClickEligible) {
                                activationMethod = "anchor_click";
                                activationTag = String(anchor.tagName || "").toLowerCase();
                                anchor.click();
                                return;
                            }
                            clickOnce(1);
                            if (${if (doubleClick) "true" else "false"}) {
                                emit("mousedown", 2);
                                emit("mouseup", 2);
                                emit("click", 2);
                                emit("dblclick", 2);
                            }
                            if (buttonValue === 0 && typeof target.click === "function") {
                                activationMethod = "native_click";
                                target.click();
                            }
                        } catch (_) {}
                    }, 0);

                    return JSON.stringify({
                        ok: true,
                        selector: selectorValue,
                        button: ${quoteJs(button)},
                        doubleClick: ${if (doubleClick) "true" else "false"},
                        activationMethod,
                        activationTag
                    });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                }
            })();
            """.trimIndent()
        return runJsonScript(webView, script, "click_dispatch_error")
    }

    internal fun runJsonScript(webView: WebView, script: String, fallbackError: String): JSONObject? {
        return try {
            val payload =
                evaluateJavascriptAsync(
                    webView,
                    script,
                    DEFAULT_TIMEOUT_MS.coerceIn(2_000L, 8_000L)
                )
            JSONObject(extractAsyncJsValue(payload))
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: fallbackError)
        }
    }

    private fun parseClickModifiers(raw: String?): Pair<Set<String>, List<String>> {
        if (raw.isNullOrBlank()) {
            return emptySet<String>() to emptyList()
        }

        val allowed = setOf("Alt", "Control", "ControlOrMeta", "Meta", "Shift")
        val parsed = linkedSetOf<String>()
        val invalid = mutableListOf<String>()

        val arr =
            try {
                JSONArray(raw)
            } catch (_: Exception) {
                return emptySet<String>() to listOf(raw)
            }

        for (i in 0 until arr.length()) {
            val token = arr.optString(i, "").trim()
            if (token in allowed) {
                parsed.add(token)
            } else {
                invalid.add(token.ifBlank { "<empty>" })
            }
        }

        return parsed to invalid
    }

    internal fun readCurrentUrl(webView: WebView, fallback: String): String {
        return runCatching {
            extractAsyncJsValue(
                evaluateJavascriptAsync(
                    webView,
                    "(function(){ return String(location.href || ''); })();",
                    2_000L
                )
            )
        }.getOrDefault(fallback)
    }

    private fun browserFileUpload(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")

        val rawPaths = param(tool, "paths")?.trim().orEmpty()
        val shouldCancel = rawPaths.isBlank()

        val files: List<File> =
            if (shouldCancel) {
                emptyList()
            } else {
                val pathList = parseStringArrayParam(rawPaths)
                    ?: return error(tool.name, "paths must be a JSON array")

                val resolved = mutableListOf<File>()
                for (rawPath in pathList) {
                    val path = rawPath.trim()
                    if (path.isBlank()) {
                        return error(tool.name, "paths contains an empty item")
                    }
                    val file = File(path)
                    if (!file.isAbsolute) {
                        return error(tool.name, "path must be absolute: $path")
                    }
                    if (!file.exists() || !file.isFile) {
                        return error(tool.name, "file does not exist: $path")
                    }
                    resolved.add(file)
                }
                resolved
            }

        var resolvedCode: String? = null
        var resolvedResult: String? = null
        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        val markers = captureActionMarkers(session)
        val callbackError =
            runOnMainSync {
                ensureSessionAttachedOnMain(session.id)

                val callback = session.pendingFileChooserCallback
                    ?: return@runOnMainSync "No file chooser is active"
                if (!shouldCancel && files.size > 1 && !session.pendingFileChooserMultiple) {
                    return@runOnMainSync "This file picker accepts only one file; pass a single path."
                }

                return@runOnMainSync try {
                    if (shouldCancel) {
                        callback.onReceiveValue(null)
                        session.pendingFileChooserCallback = null
                        notifySessionStateChanged(session)
                        resolvedCode = "await fileChooser.cancel();"
                        resolvedResult = "Cancelled the active file chooser."
                    } else {
                        val uris = files.map { Uri.fromFile(it) }.toTypedArray()
                        callback.onReceiveValue(uris)
                        session.pendingFileChooserCallback = null
                        notifySessionStateChanged(session)
                        resolvedCode =
                            "await fileChooser.setFiles(${renderJsArrayCode(files.map { it.absolutePath })});"
                        resolvedResult =
                            "Uploaded ${files.size} file(s): ${files.joinToString(", ") { it.absolutePath }}"
                    }
                    null
                } catch (e: Exception) {
                    "Failed to resolve file chooser: ${e.message}"
                }
            }

        if (callbackError != null) {
            return error(tool.name, callbackError)
        }
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy = BrowserActionSettlementPolicy(waitForDocumentReady = true)
            )
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = resolvedCode,
                result = resolvedResult
            )
        )
    }

    private fun browserWaitFor(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val timeRaw = param(tool, "time")?.trim()?.takeIf { it.isNotBlank() }
        val parsedTimeSeconds = timeRaw?.toDoubleOrNull()
        if (timeRaw != null && parsedTimeSeconds == null) {
            return error(tool.name, "time must be a number")
        }
        val timeSeconds = parsedTimeSeconds?.takeIf { it > 0.0 }
        val text = param(tool, "text")?.takeIf { it.isNotBlank() }
        val textGone = param(tool, "textGone")?.takeIf { it.isNotBlank() }
        val selector = param(tool, "selector")?.trim()?.takeIf { it.isNotBlank() }
        val state = param(tool, "state")?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "visible"
        if (state !in setOf("visible", "hidden", "attached", "detached")) {
            return error(tool.name, "state must be visible, hidden, attached or detached")
        }
        val timeoutSeconds = param(tool, "timeout")?.trim()?.toDoubleOrNull()?.coerceIn(1.0, 60.0) ?: 10.0
        if (timeSeconds == null && text == null && textGone == null && selector == null) {
            return error(tool.name, "One of time, text, textGone or selector is required")
        }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        val markers = captureActionMarkers(session)
        timeSeconds?.let { seconds ->
            Thread.sleep(((seconds * 1000.0).toLong()).coerceAtLeast(0L).coerceAtMost(30_000L))
        }
        if (text != null || textGone != null || selector != null) {
            val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000).toLong()
            var lastProblem: String? = null
            while (true) {
                val check = checkWaitCondition(session, text, textGone, selector, state)
                if (check.badSelector != null) return error(tool.name, check.badSelector)
                if (check.met) break
                lastProblem = check.problem
                if (System.currentTimeMillis() >= deadline) {
                    return pageError(
                        tool.name,
                        sessionById(session.id) ?: session,
                        "Timed out after ${timeoutSeconds}s waiting for the condition" +
                            (lastProblem?.let { " ($it)" } ?: "") + "."
                    )
                }
                Thread.sleep(250L)
            }
        }
        val finalSession = sessionById(session.id) ?: session
        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(finalSession.id)
        }
        val settlement =
            BrowserActionSettlement(
                registry = buildPageRegistry(),
                session = finalSession,
                snapshot = latestSnapshot(finalSession),
                consoleMarker = markers.consoleTimestamp,
                downloadMarker = markers.downloadTimestamp,
                timedOut = false
            )

        val summary =
            listOfNotNull(
                text?.let { "\"$it\" appeared" },
                textGone?.let { "\"$it\" disappeared" },
                selector?.let { "$it is $state" }
            ).takeIf { it.isNotEmpty() }?.let { "Waited until " + it.joinToString(" and ") + "." }
                ?: "Waited for ${timeSeconds ?: 0.0} second(s)."

        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code =
                    buildWaitForCode(timeSeconds, text, textGone) +
                        (selector?.let { "\nawait page.locator(${quoteJsCode(it)}).waitFor({ state: '$state' });" } ?: ""),
                result = summary
            )
        )
    }

    private data class WaitCheck(val met: Boolean, val problem: String? = null, val badSelector: String? = null)

    /**
     * One look at the page for browser_wait_for: text of the page, its same-origin frames and open
     * shadow roots, and the selector's state. A page that cannot be read counts as not ready.
     */
    private fun checkWaitCondition(
        session: WebSession,
        text: String?,
        textGone: String?,
        selector: String?,
        state: String
    ): WaitCheck {
        val script =
            """
            (function() {
                // Every document and open shadow root, at any depth, including frames that sit
                // inside shadow roots. Each root's querySelectorAll stops at nested shadow roots,
                // so every element is visited once.
                const roots = [document];
                const seen = new Set([document]);
                for (let i = 0; i < roots.length; i++) {
                    let all = [];
                    try { all = roots[i].querySelectorAll("*"); } catch (_) {}
                    for (const el of all) {
                        if (el.shadowRoot && !seen.has(el.shadowRoot)) { seen.add(el.shadowRoot); roots.push(el.shadowRoot); }
                        const tag = el.tagName;
                        if (tag === "IFRAME" || tag === "FRAME") {
                            let fd = null;
                            try { fd = el.contentDocument; } catch (_) {}
                            if (fd && !seen.has(fd)) { seen.add(fd); roots.push(fd); }
                        }
                    }
                }
                const needText = ${if (text != null || textGone != null) "true" else "false"};
                const selector = ${selector?.let(JSONObject::quote) ?: "null"};
                let pageText = "";
                let attached = false;
                let visible = false;
                {
                    for (const root of roots) {
                        const d = root.nodeType === 9 ? root : null;
                        if (needText) {
                            try {
                                pageText += "\n" + (d ? ((d.body && d.body.innerText) || "") : (root.textContent || ""));
                            } catch (_) {}
                        }
                        if (selector && !visible) {
                            let found;
                            try { found = root.querySelectorAll(selector); }
                            catch (e) { return JSON.stringify({ ok: false, badSelector: String(e && e.message ? e.message : e) }); }
                            for (const el of found) {
                                attached = true;
                                try {
                                    const style = el.ownerDocument.defaultView.getComputedStyle(el);
                                    const rect = el.getBoundingClientRect();
                                    if (style.visibility !== "hidden" && style.display !== "none" && (rect.width > 0 || rect.height > 0)) {
                                        visible = true;
                                        break;
                                    }
                                } catch (_) {}
                            }
                        }
                    }
                }
                return JSON.stringify({ ok: true, text: pageText, attached: attached, visible: visible });
            })();
            """.trimIndent()
        val json = runJsonScript(session.webView, script, "wait_check_error") ?: return WaitCheck(false, "the page could not be read")
        json.optString("badSelector").takeIf { it.isNotBlank() }?.let {
            return WaitCheck(false, badSelector = "selector is not valid: $it")
        }
        if (!json.optBoolean("ok", false)) return WaitCheck(false, "the page could not be read")
        val pageText = json.optString("text")
        val textOk = text == null || pageText.contains(text)
        val goneOk = textGone == null || !pageText.contains(textGone)
        val attached = json.optBoolean("attached", false)
        val visible = json.optBoolean("visible", false)
        val selectorOk =
            selector == null ||
                when (state) {
                    "visible" -> visible
                    "hidden" -> !visible
                    "attached" -> attached
                    else -> !attached
                }
        val problem =
            when {
                !textOk -> "\"$text\" not found"
                !goneOk -> "\"$textGone\" still shown"
                !selectorOk -> "$selector is not $state yet"
                else -> null
            }
        return WaitCheck(textOk && goneOk && selectorOk, problem)
    }

    private fun browserSnapshot(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val selector = param(tool, "selector")?.trim()?.takeIf { it.isNotBlank() }
        val depthRaw = param(tool, "depth")?.trim()?.takeIf { it.isNotBlank() }
        val depth =
            when {
                depthRaw == null -> null
                depthRaw.toIntOrNull() == null -> return error(tool.name, "depth must be a non-negative integer")
                depthRaw.toInt() < 0 -> return error(tool.name, "depth must be a non-negative integer")
                else -> depthRaw.toInt()
            }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }

        val snapshot = captureSnapshotModel(session, selector = selector, depth = depth)
        session.lastSnapshot = snapshot
        val filename = param(tool, "filename")?.trim()?.takeIf { it.isNotBlank() }
        val snapshotSection =
            if (filename != null) {
                formatBrowserFileLink(
                    "Snapshot",
                    writeBrowserTextOutput(filename, snapshot.yaml, "page", "yml")
                )
            } else {
                snapshot.yaml
            }

        return ok(
            tool.name,
            buildBrowserResponse(
                openTabs = renderOpenTabs(),
                pageState = renderPageState(session),
                snapshot = snapshotSection,
                modalState = renderModalState(session),
                downloads = renderManagedDownloads(session, marker = 0L, includeAll = true)
            )
        )
    }

    private fun browserNavigateBack(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val couldGoBack = session.canGoBack || runCatching { session.webView.canGoBack() }.getOrDefault(false)
        val markers = captureActionMarkers(session)

        runOnMainSync(timeoutMs = 8_000L) {
            ensureSessionAttachedOnMain(session.id)
            if (session.webView.canGoBack()) {
                session.webView.goBack()
            }
            refreshNavigationStateAsync(session)
            Unit
        }
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy =
                    BrowserActionSettlementPolicy(
                        timeoutMs = DEFAULT_TIMEOUT_MS.coerceAtLeast(4_000L),
                        waitForDocumentReady = true,
                        waitForNavigationChange = couldGoBack,
                        captureSnapshot = false
                    )
            )

        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = "await page.goBack();",
                result =
                    if (couldGoBack) {
                        "Navigated back."
                    } else {
                        "No back history entry was available."
                    }
            )
        )
    }

    private fun browserHover(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
            ?: return error(tool.name, "ref is required")

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        if (requireSnapshotNode(session, ref) == null) {
            return pageError(
                tool.name,
                session,
                "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
            )
        }
        val markers = captureActionMarkers(session)
        val geometry =
            elementGeometry(session.webView, ref, "center")
                ?: return pageError(
                    tool.name,
                    session,
                    "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
                )
        val connection = BrowserCdp.connection(this, session)
        val hoverResult =
            if (connection != null) {
                if (cdpHoverElement(connection, session.webView, ref, geometry) != null) {
                    "Hovered ref=$ref with the mouse; the page shows it as hovered."
                } else {
                    "Moved the mouse over ref=$ref, but the page does not show it as hovered. " +
                        "Another element may cover it; check the snapshot or screenshot."
                }
            } else {
                val jsResult = dispatchHoverByRef(session.webView, ref)
                if (jsResult?.optBoolean("ok", false) != true) {
                    return error(tool.name, "Hover failed: ${jsResult?.optString("error") ?: "unknown"}")
                }
                "Sent hover events to ref=$ref. DevTools is unavailable, so CSS :hover styles may not apply."
            }
        val settlement = settleBrowserAction(session, markers)

        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = "await ${locatorExpressionForRef(session, ref)}.hover();",
                result = hoverResult
            )
        )
    }

    private fun browserDrag(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val startRef = param(tool, "startRef")?.trim()?.takeIf { it.isNotBlank() }
            ?: return error(tool.name, "startRef is required")
        val endRef = param(tool, "endRef")?.trim()?.takeIf { it.isNotBlank() }
            ?: return error(tool.name, "endRef is required")

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        if (requireSnapshotNode(session, startRef) == null) {
            return pageError(
                tool.name,
                session,
                "Ref $startRef was not found in the current snapshot. Capture a new snapshot and retry."
            )
        }
        if (requireSnapshotNode(session, endRef) == null) {
            return pageError(
                tool.name,
                session,
                "Ref $endRef was not found in the current snapshot. Capture a new snapshot and retry."
            )
        }
        val markers = captureActionMarkers(session)
        val requestedMode = param(tool, "mode")?.trim()?.lowercase(Locale.ROOT)?.ifBlank { null } ?: "auto"
        if (requestedMode !in setOf("auto", "mouse", "touch", "html5")) {
            return error(tool.name, "mode must be auto, mouse, touch or html5")
        }
        val dragResult =
            performDrag(session, startRef, endRef, requestedMode)
                ?: return pageError(
                    tool.name,
                    session,
                    "Ref $startRef or $endRef was not found in the current snapshot. Capture a new snapshot and retry."
                )
        val settlement = settleBrowserAction(session, markers)
        val startLocator = locatorExpressionForRef(session, startRef)
        val endLocator = locatorExpressionForRef(session, endRef)

        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = "await $startLocator.dragTo($endLocator);",
                result = dragResult
            )
        )
    }

    /**
     * Drags like a person: press on the start element, move along an eased path, release on the
     * end element. Sliders and puzzle checks need this; elements marked draggable="true" use the
     * page's HTML drag events instead. Null when either ref is gone.
     */
    private fun performDrag(session: WebSession, startRef: String, endRef: String, requestedMode: String): String? {
        val webView = session.webView
        val start = elementGeometry(webView, startRef, "center") ?: return null
        val end = elementGeometry(webView, endRef, null) ?: return null
        val connection = if (requestedMode == "auto" || requestedMode == "mouse") BrowserCdp.connection(this, session) else null
        val mode =
            when {
                requestedMode == "html5" -> "html5"
                requestedMode == "touch" -> "touch"
                requestedMode == "auto" && start.explicitDraggable -> "html5"
                connection != null -> "mouse"
                else -> "touch"
            }
        val endHidden = !end.isPointVisible(end.centerX, end.centerY)
        val steps = 24
        val path = easedPath(start.centerX, start.centerY, end.centerX, end.centerY, steps)
        when (mode) {
            "html5" -> {
                val jsResult = dispatchDragByRef(webView, startRef, endRef)
                if (jsResult?.optBoolean("ok", false) != true) {
                    return if (jsResult?.optString("error")?.endsWith("ref_not_found") == true) null
                    else throw BrowserInputException("Drag failed: ${jsResult?.optString("error") ?: "unknown"}")
                }
            }
            "mouse" -> {
                val cdp = connection ?: throw BrowserInputException("DevTools is unavailable for a mouse drag. Use mode=touch.")
                val scaled = cdpHoverElement(cdp, webView, startRef, start) ?: true
                val jitter = java.util.Random()
                cdpMouse(cdp, "mousePressed", start.cdpX(start.centerX, scaled), start.cdpY(start.centerY, scaled), "left", 1, 1)
                Thread.sleep(80L)
                path.drop(1).forEachIndexed { index, (x, y) ->
                    val wobble = if (index in 1 until steps - 1) jitter.nextGaussian() * 0.6 else 0.0
                    cdpMouse(cdp, "mouseMoved", start.cdpX(x, scaled), start.cdpY(y + wobble, scaled), "left", 1)
                    Thread.sleep(16L + jitter.nextInt(10))
                }
                Thread.sleep(60L)
                cdpMouse(cdp, "mouseReleased", start.cdpX(end.centerX, scaled), start.cdpY(end.centerY, scaled), "left", 0, 1)
            }
            else -> {
                val viewSize = runOnMainSync { webView.width to webView.height }
                if (viewSize.first <= 0 || viewSize.second <= 0) {
                    throw BrowserInputException("The page is not laid out yet. Take a snapshot and try again.")
                }
                val points = path.map { (x, y) -> start.toViewX(x, viewSize.first) to start.toViewY(y, viewSize.second) }
                dispatchTouchPath(webView, points, stepDelayMs = 18L, holdBeforeMoveMs = 120L)
            }
        }
        val how =
            when (mode) {
                "html5" -> "with HTML drag-and-drop events"
                "mouse" -> "with a real mouse press, move and release"
                else -> "with a real finger drag"
            }
        return "Dragged ref=$startRef to ref=$endRef $how." +
            if (endHidden && mode != "html5") {
                " The target was outside the visible area, so the drop point may be wrong; scroll and check."
            } else {
                ""
            } +
            " Check the page to confirm it took effect; if not, try another mode (mouse, touch or html5)."
    }

    private fun browserEvaluate(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val functionSource = param(tool, "function")?.trim()
        if (functionSource.isNullOrBlank()) {
            return error(tool.name, "function is required")
        }
        val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
        val elementDescription = param(tool, "element")?.trim()?.takeIf { it.isNotBlank() }
        if (!elementDescription.isNullOrBlank() && ref == null) {
            return error(tool.name, "ref is required when element is provided")
        }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        if (ref != null && requireSnapshotNode(session, ref) == null) {
            return pageError(
                tool.name,
                session,
                "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
            )
        }
        val markers = captureActionMarkers(session)
        val value = evaluatePageFunction(session.webView, functionSource, ref, DEFAULT_TIMEOUT_MS)
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy = BrowserActionSettlementPolicy(waitForDocumentReady = true)
            )
        val code =
            if (ref != null) {
                "await ${locatorExpressionForRef(session, ref)}.evaluate(${functionSource});"
            } else {
                "await page.evaluate(${functionSource});"
            }
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = code,
                result = inlineOrSaved(value, "evaluate_result", "txt")
            )
        )
    }

    private fun browserFillForm(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val rawFields = param(tool, "fields")?.trim()
        if (rawFields.isNullOrBlank()) {
            return error(tool.name, "fields is required")
        }
        val fields = parseFormFields(rawFields) ?: return error(tool.name, "fields must be a JSON array")
        if (fields.isEmpty()) {
            return error(tool.name, "fields must not be empty")
        }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        fields.forEach { field ->
            val ref = field.optString("ref").trim()
            if (ref.isNotBlank() && requireSnapshotNode(session, ref) == null) {
                return pageError(
                    tool.name,
                    session,
                    "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
                )
            }
        }
        val markers = captureActionMarkers(session)
        val result = fillFormFields(session.webView, rawFields)
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy = BrowserActionSettlementPolicy(waitForDocumentReady = true)
            )
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = "await page.fillForm(/* ${fields.size} field(s) */);",
                result = result
            )
        )
    }

    private fun browserHandleDialog(tool: AITool): ToolResult {
        val acceptRaw = param(tool, "accept")
            ?: return error(tool.name, "accept is required")
        val accept =
            acceptRaw.equals("true", ignoreCase = true) ||
                acceptRaw == "1" ||
                acceptRaw.equals("yes", ignoreCase = true)
        val promptText = param(tool, "promptText")
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val pending = session.pendingDialog ?: return error(tool.name, "No dialog is currently open")
        val markers = captureActionMarkers(session)

        runOnMainSync<Unit> {
            resolvePendingDialogOnMain(
                session = session,
                accept = accept,
                promptText = promptText
            )
        }
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy = BrowserActionSettlementPolicy(waitForDocumentReady = true)
            )

        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code =
                    if (accept) {
                        "await dialog.accept(${quoteJsCode(promptText ?: pending.defaultValue.orEmpty())});"
                    } else {
                        "await dialog.dismiss();"
                    },
                modalState = "No modal dialog is currently open.",
                result = "Handled ${pending.type} dialog."
            )
        )
    }

    private fun browserConsoleMessages(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val level = param(tool, "level")?.trim()?.lowercase(Locale.ROOT).orEmpty().ifBlank { "info" }
        if (level !in setOf("error", "warning", "warn", "info", "debug")) {
            return error(tool.name, "level must be one of: error, warning, info, debug")
        }
        val rendered = renderAllConsoleMessages(session, level)
        val filename = param(tool, "filename")?.trim()?.takeIf { it.isNotBlank() }
        val sectionContent =
            if (filename != null) {
                "Saved console messages to ${writeBrowserTextOutput(filename, rendered, "console_messages", "log")}"
            } else {
                inlineOrSaved(rendered, "console_messages", "log", keepTail = true)
            }
        return ok(
            tool.name,
            buildBrowserResponse(
                openTabs = renderOpenTabs(),
                pageState = renderPageState(session),
                consoleMessages = sectionContent
            )
        )
    }

    private fun browserNetworkRequests(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val includeStatic = boolParam(tool, "includeStatic", false)
        val devtoolsConnected = BrowserCdp.connection(this, session) != null
        val rendered =
            renderNetworkRequestLog(session, includeStatic) +
                if (devtoolsConnected) "" else "\n(Status codes are unavailable: DevTools is not connected to this tab.)"
        val filename = param(tool, "filename")?.trim()?.takeIf { it.isNotBlank() }
        val resultText =
            if (filename != null) {
                "Saved network requests to ${writeBrowserTextOutput(filename, rendered, "network_requests", "log")}"
            } else {
                inlineOrSaved(rendered, "network_requests", "log", keepTail = true)
            }
        return ok(
            tool.name,
            buildBrowserResponse(
                openTabs = renderOpenTabs(),
                pageState = renderPageState(session),
                result = resultText
            )
        )
    }

    private fun browserPressKey(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val key = param(tool, "key")?.trim()
        if (key.isNullOrBlank()) {
            return error(tool.name, "key is required")
        }
        val combo = BrowserKeys.parse(key)

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        val markers = captureActionMarkers(session)
        val connection = BrowserCdp.connection(this, session)
        val result =
            if (connection != null) {
                cdpPressKey(connection, combo)
                "Pressed ${combo.label}"
            } else {
                // DevTools unavailable: only page-level key events, so the browser's own default
                // actions (moving the caret, Tab focus, Control+A) may not happen.
                pressKeyOnPage(session.webView, combo.label) +
                    "\nNote: sent as page key events only; check the page state, because default " +
                    "actions such as caret movement or Tab focus may not have happened."
            }
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy =
                    if (combo.key.key == "Enter") {
                        BrowserActionSettlementPolicy(
                            waitForDocumentReady = true,
                            waitForTimeSeconds = 0.5
                        )
                    } else {
                        BrowserActionSettlementPolicy()
                    }
            )
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = "await page.keyboard.press(${quoteJsCode(combo.label)});",
                result = result
            )
        )
    }

    private fun browserResize(tool: AITool): ToolResult {
        ensureOverlayPermission(tool.name)?.let { return it }
        val width = intParam(tool, "width", -1)
        val height = intParam(tool, "height", -1)
        if (width <= 0 || height <= 0) {
            return error(tool.name, "width and height must be positive integers")
        }
        val density =
            context.resources.displayMetrics.density
                .takeIf { it.isFinite() && it > 0f }
                ?: 1f
        val cssPixelCount = width.toLong() * height.toLong()
        val layoutWidth = kotlin.math.ceil(width.toDouble() * density).toLong()
        val layoutHeight = kotlin.math.ceil(height.toDouble() * density).toLong()
        val layoutPixelCount = layoutWidth * layoutHeight
        if (
            width > MAX_VIEWPORT_DIMENSION_CSS_PX ||
                height > MAX_VIEWPORT_DIMENSION_CSS_PX ||
                cssPixelCount > MAX_VIEWPORT_PIXEL_COUNT ||
                layoutPixelCount > MAX_VIEWPORT_LAYOUT_PIXEL_COUNT
        ) {
            return error(
                tool.name,
                "viewport is too large for this device; maximum side is " +
                    "$MAX_VIEWPORT_DIMENSION_CSS_PX CSS px and the requested pixel area " +
                    "must fit the device rendering budget"
            )
        }

        val session =
            runOnMainSync {
                getSession(null) ?: createSessionTabOnMain(context.applicationContext, "about:blank")
            }
        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
            session.viewportWidthPx = width
            session.viewportHeightPx = height
            session.hasExplicitViewportSize = true
            browserHost?.setViewportSize(width, height)
            applyViewportOverride(session)
            refreshSessionUiOnMain(session.id)
        }
        Thread.sleep(100)

        return ok(
            tool.name,
            buildBrowserResponse(
                code = "await page.setViewportSize({ width: $width, height: $height });",
                openTabs = renderOpenTabs(),
                pageState = renderPageState(session),
                snapshot = captureSnapshotText(session),
                result = "Resized browser viewport to ${width}x${height}."
            )
        )
    }

    private fun browserRunCode(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val code = param(tool, "code")?.trim()
        if (code.isNullOrBlank()) {
            return error(tool.name, "code is required")
        }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        val markers = captureActionMarkers(session)
        val result = runPlaywrightLikeCode(session, code)
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy = BrowserActionSettlementPolicy(waitForDocumentReady = true)
            )
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = code,
                result = inlineOrSaved(result, "run_code_result", "txt")
            )
        )
    }

    private fun browserSelectOption(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
            ?: return error(tool.name, "ref is required")
        val values = parseStringArrayParam(param(tool, "values"))
            ?: return error(tool.name, "values must be a JSON array")
        if (values.isEmpty()) {
            return error(tool.name, "values must not be empty")
        }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        if (requireSnapshotNode(session, ref) == null) {
            return pageError(
                tool.name,
                session,
                "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
            )
        }
        val markers = captureActionMarkers(session)
        val result = selectOptionsByRef(session.webView, ref, values)
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy = BrowserActionSettlementPolicy(waitForDocumentReady = true)
            )
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = "await ${locatorExpressionForRef(session, ref)}.selectOption(${renderJsArrayCode(values)});",
                result = result
            )
        )
    }

    private fun browserTabs(tool: AITool): ToolResult {
        val action = param(tool, "action")?.trim()?.lowercase(Locale.ROOT)
            ?: return error(tool.name, "action is required")
        return when (action) {
            "list" -> {
                val registry = buildPageRegistry()
                val session = registry.activeSessionId?.let { sessionId -> sessionById(sessionId) }
                ok(
                    tool.name,
                    buildBrowserResponse(
                        openTabs = renderOpenTabs(registry),
                        pageState = session?.let { activeSession -> renderPageState(activeSession) } ?: "No active page.",
                        snapshot = session?.let { activeSession -> captureSnapshotText(activeSession) },
                        result = "Listed open tabs."
                    )
                )
            }

            "create" -> {
                ensureOverlayPermission(tool.name)?.let { return it }
                val session =
                    runOnMainSync {
                        createSessionTabOnMain(context.applicationContext, initialUrl = "about:blank")
                    }
                val settlement = settleBrowserAction(session, captureActionMarkers(session))
                ok(
                    tool.name,
                    buildSettledBrowserResponse(
                        settlement = settlement,
                        result = "Created tab ${currentTabIndex(session.id)}."
                    )
                )
            }

            "restore" -> {
                ensureOverlayPermission(tool.name)?.let { return it }
                val profile = BrowserProfiles.currentScope() ?: BrowserProfiles.MAIN
                val crashed =
                    crashedTabs.lastOrNull { it.profileName == profile }
                        ?: return error(tool.name, "No crashed tab to restore.")
                crashedTabs.remove(crashed)
                val session =
                    runOnMainSync {
                        createSessionTabOnMain(context.applicationContext, initialUrl = crashed.url)
                    }
                val settlement =
                    settleBrowserAction(
                        session,
                        captureActionMarkers(session),
                        BrowserActionSettlementPolicy(waitForDocumentReady = true)
                    )
                ok(
                    tool.name,
                    buildSettledBrowserResponse(
                        settlement = settlement,
                        result =
                            "Reopened ${crashed.url} in tab ${currentTabIndex(session.id)}. " +
                                "Anything typed before the crash is gone; check the page before continuing."
                    )
                )
            }

            "select" -> {
                val index = intParam(tool, "index", -1)
                if (index < 0) {
                    return error(tool.name, "index is required for action=select")
                }
                val targetId = sessionIdAtIndex(index) ?: return error(tool.name, "Tab index out of range: $index")
                runOnMainSync<Unit> {
                    ensureSessionAttachedOnMain(targetId)
                }
                val session = sessions[targetId] ?: return error(tool.name, "Tab index out of range: $index")
                val settlement = settleBrowserAction(session, captureActionMarkers(session))
                ok(
                    tool.name,
                    buildSettledBrowserResponse(
                        settlement = settlement,
                        result = "Selected tab $index."
                    )
                )
            }

            "close" -> {
                val requestedIndex = param(tool, "index")?.trim()?.toIntOrNull()
                val targetId =
                    when {
                        requestedIndex != null -> sessionIdAtIndex(requestedIndex)
                        else -> resolvePreferredSessionId()
                    } ?: return error(tool.name, "No tab available to close")
                if (!closeSession(targetId)) {
                    return error(tool.name, "Failed to close tab")
                }
                val registry = buildPageRegistry()
                val active = registry.activeSessionId?.let { sessionId -> sessionById(sessionId) }
                ok(
                    tool.name,
                    buildBrowserResponse(
                        openTabs = renderOpenTabs(registry),
                        pageState = active?.let { activeSession -> renderPageState(activeSession) } ?: "No active page.",
                        snapshot = active?.let { activeSession -> captureSnapshotText(activeSession) },
                        result =
                            if (active != null) {
                                "Closed tab. Active tab is now ${currentTabIndex(active.id)}."
                            } else {
                                "Closed the last tab."
                            }
                    )
                )
            }

            else -> error(tool.name, "action must be one of: list, create, select, close, restore")
        }
    }

    private fun browserTakeScreenshot(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val type = param(tool, "type")?.trim()?.lowercase(Locale.ROOT).orEmpty().ifBlank { "png" }
        if (type !in setOf("png", "jpeg", "jpg", "pdf")) {
            return error(tool.name, "type must be png, jpeg or pdf")
        }
        val element = param(tool, "element")?.trim()?.takeIf { it.isNotBlank() }
        val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
        val fullPage = boolParam(tool, "fullPage", false)
        if (element != null && ref == null) {
            return error(tool.name, "ref is required when element is provided")
        }
        if (fullPage && ref != null) {
            return error(tool.name, "fullPage cannot be used with element screenshots")
        }
        if (type == "pdf" && ref != null) {
            return error(tool.name, "type=pdf saves the whole page; leave out ref and element")
        }
        val filename = param(tool, "filename")?.trim()?.takeIf { it.isNotBlank() }
        val shot =
            if (type == "pdf") savePageAsPdf(session, filename) else takeScreenshot(session, filename, type, fullPage, ref)
        return ok(
            tool.name,
            buildBrowserResponse(
                openTabs = renderOpenTabs(),
                pageState = renderPageState(session),
                result = "Saved ${if (type == "pdf") "PDF" else "screenshot"} to ${shot.path}" + (shot.note?.let { "\nNote: $it" } ?: "")
            )
        )
    }

    /**
     * Taps a point given in pixels of the page screenshot. The screenshot and the WebView only
     * differ by a fixed scale, so the point maps straight onto the view.
     */
    private fun browserMouseClickXy(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val x = param(tool, "x")?.trim()?.toDoubleOrNull()
            ?: return error(tool.name, "x is required, in pixels of the page screenshot")
        val y = param(tool, "y")?.trim()?.toDoubleOrNull()
            ?: return error(tool.name, "y is required, in pixels of the page screenshot")
        val doubleClick = boolParam(tool, "doubleClick", false)
        val holdMs = param(tool, "holdMs")?.trim()?.toDoubleOrNull()?.toLong()?.coerceIn(0L, 10_000L) ?: 0L
        val endX = param(tool, "endX")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toDoubleOrNull() ?: return error(tool.name, "endX must be a number")
        }
        val endY = param(tool, "endY")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toDoubleOrNull() ?: return error(tool.name, "endY must be a number")
        }
        val button = param(tool, "button")?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: "left"
        if (button !in setOf("left", "right", "middle")) return error(tool.name, "button must be left, right or middle")
        if ((endX == null) != (endY == null)) return error(tool.name, "endX and endY must be given together")
        if (endX != null && button != "left") return error(tool.name, "A drag to endX/endY uses the left button only")
        if (holdMs > 0L || endX != null || button != "left") {
            return gestureAtScreenshotPoint(tool, session, x, y, endX, endY, holdMs, button, doubleClick)
        }
        val markers = captureActionMarkers(session)
        val problem =
            runOnMainSync<String?> {
                ensureSessionAttachedOnMain(session.id)
                val webView = session.webView
                val imageWidth = session.viewportWidthPx ?: webView.width
                val imageHeight = session.viewportHeightPx ?: webView.height
                when {
                    webView.width <= 0 || webView.height <= 0 || imageWidth <= 0 || imageHeight <= 0 ->
                        "The page is not laid out yet. Take a screenshot and try again."
                    x < 0 || y < 0 || x >= imageWidth || y >= imageHeight ->
                        "($x, $y) is outside the page screenshot, which is ${imageWidth}x$imageHeight."
                    else -> {
                        val viewX = (x * webView.width / imageWidth).toFloat()
                        val viewY = (y * webView.height / imageHeight).toFloat()
                        webView.requestFocus()
                        val start = SystemClock.uptimeMillis()
                        repeat(if (doubleClick) 2 else 1) { index ->
                            val downAt = start + index * 120L
                            val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, viewX, viewY, 0)
                            val up = MotionEvent.obtain(downAt, downAt + 40L, MotionEvent.ACTION_UP, viewX, viewY, 0)
                            try {
                                webView.dispatchTouchEvent(down)
                                webView.dispatchTouchEvent(up)
                            } finally {
                                down.recycle()
                                up.recycle()
                            }
                        }
                        null
                    }
                }
            }
        if (problem != null) return pageError(tool.name, session, problem)
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy = BrowserActionSettlementPolicy(waitForDocumentReady = true, waitForTimeSeconds = 0.5)
            )
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                result = "Tapped (${x.toInt()}, ${y.toInt()}) in screenshot pixels${if (doubleClick) " twice" else ""}"
            )
        )
    }

    /**
     * Long press, drag/swipe to a second point, or a right/middle click, all in screenshot pixels.
     * Touches go through Android like a finger; other mouse buttons go through DevTools.
     */
    private fun gestureAtScreenshotPoint(
        tool: AITool,
        session: WebSession,
        x: Double,
        y: Double,
        endX: Double?,
        endY: Double?,
        holdMs: Long,
        button: String,
        doubleClick: Boolean
    ): ToolResult {
        data class Mapping(val viewWidth: Int, val viewHeight: Int, val imageWidth: Int, val imageHeight: Int)
        val mapping =
            runOnMainSync {
                ensureSessionAttachedOnMain(session.id)
                val webView = session.webView
                Mapping(
                    webView.width,
                    webView.height,
                    session.viewportWidthPx ?: webView.width,
                    session.viewportHeightPx ?: webView.height
                )
            }
        if (mapping.viewWidth <= 0 || mapping.viewHeight <= 0 || mapping.imageWidth <= 0 || mapping.imageHeight <= 0) {
            return pageError(tool.name, session, "The page is not laid out yet. Take a screenshot and try again.")
        }
        fun outside(px: Double, py: Double) = px < 0 || py < 0 || px >= mapping.imageWidth || py >= mapping.imageHeight
        if (outside(x, y) || (endX != null && endY != null && outside(endX, endY))) {
            return pageError(
                tool.name,
                session,
                "A point is outside the page screenshot, which is ${mapping.imageWidth}x${mapping.imageHeight}."
            )
        }
        fun viewX(px: Double) = (px * mapping.viewWidth / mapping.imageWidth).toFloat()
        fun viewY(py: Double) = (py * mapping.viewHeight / mapping.imageHeight).toFloat()
        val markers = captureActionMarkers(session)
        val result: String
        if (button != "left") {
            val connection =
                BrowserCdp.connection(this, session)
                    ?: return error(tool.name, "DevTools is unavailable, so only left-button taps work right now.")
            val density = context.resources.displayMetrics.density.toDouble().takeIf { it > 0.0 } ?: 1.0
            val cx = viewX(x) / density
            val cy = viewY(y) / density
            val buttonsMask = if (button == "right") 2 else 4
            cdpMouse(connection, "mouseMoved", cx, cy)
            repeat(if (doubleClick) 2 else 1) { index ->
                cdpMouse(connection, "mousePressed", cx, cy, button, buttonsMask, index + 1)
                if (holdMs > 0L) Thread.sleep(holdMs)
                cdpMouse(connection, "mouseReleased", cx, cy, button, 0, index + 1)
            }
            result = "Clicked the $button mouse button at (${x.toInt()}, ${y.toInt()})${if (doubleClick) " twice" else ""}."
        } else if (endX != null && endY != null) {
            val path = easedPath(x, y, endX, endY, 20).map { (px, py) -> viewX(px) to viewY(py) }
            dispatchTouchPath(session.webView, path, stepDelayMs = 18L, holdBeforeMoveMs = holdMs.coerceAtLeast(80L))
            result = "Dragged a finger from (${x.toInt()}, ${y.toInt()}) to (${endX.toInt()}, ${endY.toInt()})."
        } else {
            dispatchTouchPath(session.webView, listOf(viewX(x) to viewY(y)), holdBeforeMoveMs = holdMs)
            result = "Pressed and held (${x.toInt()}, ${y.toInt()}) for ${holdMs} ms."
        }
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy = BrowserActionSettlementPolicy(waitForDocumentReady = true, waitForTimeSeconds = 0.5)
            )
        return ok(tool.name, buildSettledBrowserResponse(settlement = settlement, result = result))
    }

    /** Lists, checks, waits for, cancels or retries this caller's own downloads. */
    private fun browserDownloads(tool: AITool): ToolResult {
        val profile = BrowserProfiles.currentScope() ?: BrowserProfiles.MAIN
        val manager = browserDownloadManager()
        val action = param(tool, "action")?.trim()?.lowercase(Locale.ROOT)?.ifBlank { null } ?: "list"
        val downloadId = param(tool, "downloadId")?.trim()?.takeIf { it.isNotBlank() }
        fun owned(): BrowserDownloadTaskRecord? =
            downloadId?.let { id -> manager.task(id)?.takeIf { it.profileName == profile } }
        val missing = "No download with that downloadId. Use action=list to see download IDs."
        return when (action) {
            "list" -> {
                // Ordered by start time so pages stay put while downloads progress.
                val tasks = manager.tasksFor(profile).sortedByDescending { it.createdAt }
                val offset = param(tool, "offset")?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val page = tasks.drop(offset).take(DOWNLOAD_LIST_PAGE_SIZE)
                val text =
                    when {
                        tasks.isEmpty() -> "No downloads yet."
                        page.isEmpty() -> "No downloads at offset $offset; there are ${tasks.size} in total."
                        else -> {
                            val rest = tasks.size - offset - page.size
                            "Downloads ${offset + 1}-${offset + page.size} of ${tasks.size}, newest first:\n\n" +
                                page.joinToString("\n\n") { manager.renderTask(it) } +
                                if (rest > 0) "\n\n($rest older downloads; call again with offset=${offset + page.size})" else ""
                        }
                    }
                ok(tool.name, buildBrowserResponse(downloads = text))
            }
            "status" -> {
                if (downloadId == null) return error(tool.name, "downloadId is required for action=status")
                val task = owned() ?: return error(tool.name, missing)
                ok(tool.name, buildBrowserResponse(downloads = manager.renderTask(task)))
            }
            "wait" -> {
                if (downloadId == null) return error(tool.name, "downloadId is required for action=wait")
                val timeoutSeconds = param(tool, "timeout")?.trim()?.toDoubleOrNull()?.coerceIn(1.0, 120.0) ?: 60.0
                val task =
                    manager.awaitFinished(downloadId, profile, (timeoutSeconds * 1000).toLong())
                        ?: return error(tool.name, missing)
                val note = if (task.activeOrPending()) "Still downloading after ${timeoutSeconds}s; wait again or check later." else null
                ok(tool.name, buildBrowserResponse(downloads = manager.renderTask(task), result = note))
            }
            "cancel", "retry" -> {
                if (downloadId == null) return error(tool.name, "downloadId is required for action=$action")
                owned() ?: return error(tool.name, missing)
                manager.performAction(downloadId, if (action == "cancel") BrowserDownloadAction.CANCEL else BrowserDownloadAction.RETRY)
                val task = owned() ?: return error(tool.name, missing)
                ok(tool.name, buildBrowserResponse(downloads = manager.renderTask(task), result = "Requested $action."))
            }
            else -> error(tool.name, "action must be list, status, wait, cancel or retry")
        }
    }

    /**
     * Copy and paste between the page and Android's clipboard. Paste is first offered to the page
     * as a paste event, so editors and split code boxes handle it their own way.
     */
    private fun browserClipboard(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val action = param(tool, "action")?.trim()?.lowercase(Locale.ROOT).orEmpty()
        fun shown(text: String) = if (text.length <= 4_000) text else text.take(4_000) + "\n... (${text.length - 4_000} more characters)"
        runOnMainSync<Unit> { ensureSessionAttachedOnMain(session.id) }
        return when (action) {
            "read" -> {
                val (selection, _) = readPageSelection(session.webView)
                val clip = readAndroidClipboard()
                ok(
                    tool.name,
                    buildBrowserResponse(
                        result =
                            "Selected in the page: " + (selection.takeIf { it.isNotEmpty() }?.let { "\n" + shown(it) } ?: "(nothing)") +
                                "\nClipboard: " + (clip?.let { "\n" + shown(it) } ?: "(empty, or Android did not allow reading it right now)")
                    )
                )
            }
            "copy" -> {
                val (selection, _) = readPageSelection(session.webView)
                if (selection.isEmpty()) return pageError(tool.name, session, "Nothing is selected in the page. Select text first, for example with Control+A.")
                writeAndroidClipboard(selection)
                ok(tool.name, buildBrowserResponse(result = "Copied ${selection.length} characters to the clipboard:\n" + shown(selection)))
            }
            "write" -> {
                val text = param(tool, "text") ?: return error(tool.name, "text is required for action=write")
                writeAndroidClipboard(text)
                ok(tool.name, buildBrowserResponse(result = "Put ${text.length} characters on the clipboard."))
            }
            "paste" -> {
                val text =
                    param(tool, "text")
                        ?: readAndroidClipboard()
                        ?: return error(tool.name, "The clipboard is empty or cannot be read now; pass text to paste.")
                val markers = captureActionMarkers(session)
                val how =
                    if (offerPasteEvent(session.webView, text)) {
                        "the page handled the paste itself"
                    } else {
                        // Inserting needs a focused, writable text field; otherwise nothing lands.
                        val (_, editable) = readPageSelection(session.webView)
                        if (!editable) {
                            return pageError(
                                tool.name,
                                session,
                                "Nothing was pasted: no writable text field has focus. Click the text field first, then paste again."
                            )
                        }
                        val connection = BrowserCdp.connection(this, session)
                        if (connection != null) {
                            connection.send("Input.insertText", JSONObject().put("text", text), 5_000L)
                            "inserted at the cursor"
                        } else {
                            val inserted =
                                runJsonScript(
                                    session.webView,
                                    "(function(){ let ok = false; try { ok = document.execCommand('insertText', false, ${JSONObject.quote(text)}); } catch (_) {} return JSON.stringify({ ok: true, inserted: ok }); })();",
                                    "paste_error"
                                )?.optBoolean("inserted", false) == true
                            if (!inserted) return pageError(tool.name, session, "Could not paste: click a text field first.")
                            "inserted at the cursor"
                        }
                    }
                val settlement = settleBrowserAction(session, markers)
                ok(tool.name, buildSettledBrowserResponse(settlement = settlement, result = "Pasted ${text.length} characters ($how)."))
            }
            else -> error(tool.name, "action must be read, copy, write or paste")
        }
    }

    /** Saved camera, microphone and location answers per site, for this caller's login profile. */
    private fun browserPermissions(tool: AITool): ToolResult {
        val profile = BrowserProfiles.currentScope() ?: BrowserProfiles.MAIN
        val action = param(tool, "action")?.trim()?.lowercase(Locale.ROOT)?.ifBlank { null } ?: "list"
        if (action == "list") {
            val rules = BrowserSitePermissions.list(context, profile)
            val text =
                if (rules.isEmpty()) {
                    "No saved site permissions. Sites without one get the normal Android permission prompt."
                } else {
                    rules.joinToString("\n") { (origin, kind, allowed) -> "- $origin $kind: ${if (allowed) "allowed" else "blocked"}" }
                }
            return ok(tool.name, buildBrowserResponse(result = text))
        }
        val rawOrigin =
            param(tool, "origin")?.trim()?.takeIf { it.isNotBlank() }
                ?: getSession(null)?.currentUrl
                ?: return error(tool.name, "origin is required, for example https://example.com")
        val origin =
            BrowserSitePermissions.originOf(rawOrigin)
                ?: return error(tool.name, "origin must be an http or https address, for example https://example.com")
        val kind = param(tool, "permission")?.trim()?.lowercase(Locale.ROOT)?.ifBlank { null } ?: "all"
        if (kind != "all" && kind !in BrowserSitePermissions.KINDS) {
            return error(tool.name, "permission must be all or one of: ${BrowserSitePermissions.KINDS.joinToString(", ")}")
        }
        val allow =
            when (action) {
                "allow" -> true
                "block" -> false
                "reset" -> null
                else -> return error(tool.name, "action must be list, allow, block or reset")
            }
        BrowserSitePermissions.set(context, profile, origin, kind, allow)
        val what = if (kind == "all") "all permissions" else kind
        val result =
            when (allow) {
                true -> "Allowed $what for $origin. Android may still ask the user once for the phone permission."
                false -> "Blocked $what for $origin. Reload the page if it already has access."
                null -> "Forgot the saved answer for $what on $origin; the site will be asked again."
            }
        return ok(tool.name, buildBrowserResponse(result = result))
    }

    /** Sends one Chrome DevTools Protocol command to the current tab and returns its result. */
    private fun browserDevtools(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val method = param(tool, "method")?.trim().orEmpty()
        val readEvents = boolParam(tool, "events", false)
        if (method.isEmpty() && readEvents) {
            val connection =
                BrowserCdp.connection(this, session)
                    ?: return error(tool.name, "DevTools is not available for this tab right now. Try again after the page loads.")
            return ok(tool.name, buildBrowserResponse(result = renderDevtoolsEvents(connection)))
        }
        if (!DEVTOOLS_METHOD.matches(method)) {
            return error(
                tool.name,
                "method must look like Domain.command, for example Runtime.evaluate (or pass events=true alone to read events)"
            )
        }
        val domain = method.substringBefore('.')
        if (domain !in DEVTOOLS_DOMAINS) {
            return error(
                tool.name,
                "$domain is not available here. Allowed domains: ${DEVTOOLS_DOMAINS.sorted().joinToString(", ")}"
            )
        }
        val params =
            param(tool, "params")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                runCatching { JSONObject(raw) }.getOrElse {
                    return error(tool.name, "params must be a JSON object")
                }
            } ?: JSONObject()
        val connection =
            BrowserCdp.connection(this, session)
                ?: return error(tool.name, "DevTools is not available for this tab right now. Try again after the page loads.")
        val result =
            try {
                connection.send(method, params, 15_000L)
            } catch (e: CdpException) {
                return error(tool.name, "DevTools: ${e.message}")
            }
        val filename = param(tool, "filename")?.trim()?.takeIf { it.isNotBlank() }
        // Pretty-printing copies the whole result again; only small results get it.
        val compact = result.toString()
        val text = if (filename == null && compact.length <= DEVTOOLS_PRETTY_LIMIT_CHARS) result.toString(2) else compact
        val shown =
            when {
                filename != null -> "Saved the result to ${writeBrowserTextOutput(filename, text, "devtools", "json")}"
                text.length > DEVTOOLS_INLINE_CHARS ->
                    text.take(DEVTOOLS_INLINE_CHARS) +
                        "\n... (${text.length - DEVTOOLS_INLINE_CHARS} more characters; pass filename to save all)"
                else -> text
            }
        val eventsText = if (readEvents) "\n\n" + renderDevtoolsEvents(connection) else ""
        return ok(tool.name, buildBrowserResponse(code = "await cdp.send('$method')", result = shown + eventsText))
    }

    /** Events the tab sent since the last read, for domains the AI enabled (Debugger, Runtime, ...). */
    private fun renderDevtoolsEvents(connection: CdpConnection): String {
        val (events, dropped) = connection.drainEvents()
        if (events.isEmpty()) {
            return "Events: none since the last read. Events only arrive for domains you enabled, for example Debugger.enable or Runtime.enable."
        }
        val body =
            "Events since the last read (oldest first; time in ms):\n" + events.joinToString("\n") +
                if (dropped > 0) "\n($dropped older events were dropped because there were too many)" else ""
        return inlineOrSaved(body, "devtools_events", "log", keepTail = true)
    }

    private fun browserType(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
            ?: return error(tool.name, "ref is required")
        val text = param(tool, "text") ?: return error(tool.name, "text is required")
        val submit = boolParam(tool, "submit", false)
        val slowly = boolParam(tool, "slowly", false)

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        if (requireSnapshotNode(session, ref) == null) {
            return pageError(
                tool.name,
                session,
                "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
            )
        }
        val markers = captureActionMarkers(session)
        val result = typeIntoElementByRef(session.webView, ref, text, submit, slowly)
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy =
                    if (submit || slowly) {
                        BrowserActionSettlementPolicy(
                            waitForDocumentReady = true,
                            waitForTimeSeconds = 0.5
                        )
                    } else {
                        BrowserActionSettlementPolicy()
                    }
            )
        val code =
            buildString {
                val locator = locatorExpressionForRef(session, ref)
                if (slowly) {
                    append("await $locator.pressSequentially(${quoteJsCode(text)});")
                } else {
                    append("await $locator.fill(${quoteJsCode(text)});")
                }
                if (submit) {
                    append("\nawait $locator.press('Enter');")
                }
            }
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = code,
                result = result
            )
        )
    }

    private fun browserClose(tool: AITool): ToolResult {
        val activeId = resolvePreferredSessionId()
            ?: return ok(tool.name, buildBrowserResponse(openTabs = "No open tabs.", result = "No browser tab was open."))
        if (!closeSession(activeId)) {
            return error(tool.name, "Failed to close the current tab")
        }
        val registry = buildPageRegistry()
        val active = registry.activeSessionId?.let { sessionId -> sessionById(sessionId) }
        return ok(
            tool.name,
            buildBrowserResponse(
                openTabs = renderOpenTabs(registry),
                pageState = active?.let { activeSession -> renderPageState(activeSession) } ?: "No active page.",
                snapshot = active?.let { activeSession -> captureSnapshotText(activeSession) },
                result =
                    if (active != null) {
                        "Closed the current tab."
                    } else {
                        "Closed the last tab and browser overlay."
                    }
            )
        )
    }

    private fun browserCloseAll(tool: AITool): ToolResult {
        val ids = orderedSessionIds()
        if (ids.isEmpty()) {
            return ok(tool.name, buildBrowserResponse(openTabs = "No open tabs.", result = "No browser tabs were open."))
        }

        ids.forEach { sessionId ->
            closeSession(sessionId)
        }

        return ok(
            tool.name,
            buildBrowserResponse(
                openTabs = "No open tabs.",
                pageState = "No active page.",
                result = "Closed all browser tabs."
            )
        )
    }

    private fun determineExtensionFromMimeType(mimeType: String): String =
        when {
            mimeType.lowercase(Locale.ROOT).startsWith("image/") -> ".${mimeType.lowercase(Locale.ROOT).substringAfter('/')}"
            mimeType.lowercase(Locale.ROOT).startsWith("audio/") -> ".${mimeType.lowercase(Locale.ROOT).substringAfter('/')}"
            mimeType.lowercase(Locale.ROOT).startsWith("video/") -> ".${mimeType.lowercase(Locale.ROOT).substringAfter('/')}"
            mimeType.lowercase(Locale.ROOT).contains("pdf") -> ".pdf"
            mimeType.lowercase(Locale.ROOT).contains("json") -> ".json"
            mimeType.lowercase(Locale.ROOT).contains("xml") -> ".xml"
            mimeType.lowercase(Locale.ROOT).contains("csv") -> ".csv"
            mimeType.lowercase(Locale.ROOT).contains("zip") -> ".zip"
            mimeType.lowercase(Locale.ROOT).contains("html") -> ".html"
            mimeType.lowercase(Locale.ROOT).contains("javascript") -> ".js"
            mimeType.lowercase(Locale.ROOT).contains("plain") -> ".txt"
            else -> ".bin"
        }

    private fun quoteJs(value: String): String = JSONObject.quote(value)

    internal fun quoteJsCode(value: String): String {
        val escaped =
            buildString(value.length + 8) {
                value.forEach { ch ->
                    when (ch) {
                        '\\' -> append("\\\\")
                        '\'' -> append("\\'")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
            }
        return "'$escaped'"
    }

    internal fun renderJsArrayCode(values: Collection<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ", ") { quoteJsCode(it) }

    internal fun nextSnapshotGeneration(): Long = snapshotGenerationSeed.incrementAndGet()
    internal fun <T> runOnMainSync(timeoutMs: Long = 8_000L, block: () -> T): T {
        val orbitToken = com.ai.assistance.operit.features.orbit.OrbitBrowserControl.token()
        val profileScope = BrowserProfiles.currentScope()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            com.ai.assistance.operit.features.orbit.OrbitBrowserControl.check(orbitToken)
            return block()
        }

        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        // 0 waiting, 1 running, 2 given up: a late runnable must not act after the caller was told it failed.
        val state = java.util.concurrent.atomic.AtomicInteger(0)

        mainHandler.post {
            if (!state.compareAndSet(0, 1)) return@post
            try {
                com.ai.assistance.operit.features.orbit.OrbitBrowserControl.check(orbitToken)
                result = BrowserProfiles.withScope(profileScope, block)
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            if (state.compareAndSet(0, 2)) {
                throw RuntimeException(
                    "The app was busy for ${timeoutMs / 1000.0}s, so this browser step was cancelled before it ran. " +
                        "Nothing was changed; try again."
                )
            }
            throw RuntimeException(
                "A browser step took longer than ${timeoutMs / 1000.0}s and may still finish in the background. " +
                    "Take a snapshot to see the current state before retrying."
            )
        }

        if (error != null) {
            val failure = error!!
            throw (failure as? BrowserInputException) ?: RuntimeException(failure)
        }

        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
